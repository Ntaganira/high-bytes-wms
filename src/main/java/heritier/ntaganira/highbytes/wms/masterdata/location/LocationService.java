package heritier.ntaganira.highbytes.wms.masterdata.location;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.location
 * - File       : LocationService.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Locations and the bins inside them: list, create, update, bins, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Locations and the bins inside them.
 *
 * <p>A location that holds stock cannot be deleted or have its branch moved:
 * the ledger points at it, and a movement whose location disappeared cannot
 * be read back.
 */
@Service
@Transactional(readOnly = true)
public class LocationService {

    private static final String SELECT = """
            SELECT l.id, l.branch_id, b.name AS branch_name, l.code, l.name,
                   l.location_type, l.is_bonded, l.is_sellable, l.is_active,
                   COALESCE(bins.n, 0)      AS bin_count,
                   COALESCE(stock.items, 0) AS distinct_items,
                   COALESCE(stock.value, 0) AS total_value
              FROM location l
              JOIN branch b ON b.id = l.branch_id
         LEFT JOIN LATERAL (SELECT COUNT(*) AS n FROM storage_bin sb
                             WHERE sb.location_id = l.id AND sb.is_active) bins ON TRUE
         LEFT JOIN LATERAL (SELECT COUNT(*) AS items, SUM(sb.total_value) AS value
                              FROM stock_balance sb
                             WHERE sb.location_id = l.id AND sb.qty_on_hand <> 0) stock ON TRUE
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public LocationService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- reads -----------------------------------------------------------

    public List<LocationRow> forBranch(UUID branchId, boolean includeInactive) {
        return jdbc.sql(SELECT + """
                 WHERE (:branchId::uuid IS NULL OR l.branch_id = :branchId::uuid)
                   AND (:includeInactive OR l.is_active)
                 ORDER BY b.name, l.location_type, l.code
                """)
                .param("branchId", branchId, Types.OTHER)
                .param("includeInactive", includeInactive)
                .query(this::map)
                .list();
    }

    public Optional<LocationRow> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE l.id = :id")
                .param("id", id, Types.OTHER)
                .query(this::map)
                .optional();
    }

    public List<BinRow> binsIn(UUID locationId) {
        return jdbc.sql("""
                SELECT sb.id, sb.bin_code, sb.zone, sb.is_active,
                       COALESCE(held.items, 0) AS distinct_items
                  FROM storage_bin sb
             LEFT JOIN LATERAL (SELECT COUNT(*) AS items FROM stock_balance b
                                 WHERE b.storage_bin_id = sb.id AND b.qty_on_hand <> 0) held ON TRUE
                 WHERE sb.location_id = :locationId
                 ORDER BY sb.is_active DESC, sb.bin_code
                """)
                .param("locationId", locationId, Types.OTHER)
                .query((rs, n) -> new BinRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("bin_code"),
                        rs.getString("zone"),
                        rs.getBoolean("is_active"),
                        rs.getInt("distinct_items")))
                .list();
    }

    public boolean holdsStock(UUID locationId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM stock_balance
                                WHERE location_id = :id AND qty_on_hand <> 0)
                """)
                .param("id", locationId, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    public LocationForm formFor(UUID id) {
        return jdbc.sql("""
                SELECT id, branch_id, code, name, location_type,
                       is_bonded, is_sellable, is_active
                  FROM location WHERE id = :id
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> {
                    var f = new LocationForm();
                    f.setId(rs.getObject("id", UUID.class));
                    f.setBranchId(rs.getObject("branch_id", UUID.class));
                    f.setCode(rs.getString("code"));
                    f.setName(rs.getString("name"));
                    f.setLocationType(LocationType.valueOf(rs.getString("location_type")));
                    f.setBonded(rs.getBoolean("is_bonded"));
                    f.setSellable(rs.getBoolean("is_sellable"));
                    f.setActive(rs.getBoolean("is_active"));
                    return f;
                })
                .single();
    }

    // ---- writes ----------------------------------------------------------

    @Transactional
    @PreAuthorize("hasAuthority('location.manage')")
    public UUID create(LocationForm form, BranchView branch) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO location (id, branch_id, code, name, location_type,
                                          is_bonded, is_sellable, is_active)
                    VALUES (:id, :branchId, :code, :name, :type, :bonded, :sellable, :active)
                    """)
                    .param("id", id, Types.OTHER)
                    .param("branchId", form.getBranchId(), Types.OTHER)
                    .param("code", form.getCode())
                    .param("name", form.getName())
                    .param("type", form.getLocationType().name())
                    .param("bonded", form.isBonded())
                    .param("sellable", form.isSellable())
                    .param("active", form.isActive())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new LocationCodeTakenException(form.getCode());
        }

        audit.record("location", id, "Location · " + form.getCode(),
                AuditAction.CREATE, snapshotOf(null, form), branch, null);
        return id;
    }

    @Transactional
    @PreAuthorize("hasAuthority('location.manage')")
    public void update(UUID id, LocationForm form, BranchView branch) {
        LocationForm before = formFor(id);

        // Moving a location that holds stock would move that stock between
        // branches without a transfer document. Refused.
        if (holdsStock(id) && !before.getBranchId().equals(form.getBranchId())) {
            throw new LocationHoldsStockException(before.getCode(),
                    "its branch cannot be changed");
        }
        // Same for the type: a bonded location's stock is duty-suspended, and
        // reclassifying it silently would lose that.
        if (holdsStock(id) && before.getLocationType() != form.getLocationType()) {
            throw new LocationHoldsStockException(before.getCode(),
                    "its type cannot be changed");
        }

        try {
            jdbc.sql("""
                    UPDATE location
                       SET branch_id = :branchId, code = :code, name = :name,
                           location_type = :type, is_bonded = :bonded,
                           is_sellable = :sellable, is_active = :active
                     WHERE id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .param("branchId", form.getBranchId(), Types.OTHER)
                    .param("code", form.getCode())
                    .param("name", form.getName())
                    .param("type", form.getLocationType().name())
                    .param("bonded", form.isBonded())
                    .param("sellable", form.isSellable())
                    .param("active", form.isActive())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new LocationCodeTakenException(form.getCode());
        }

        var snapshot = snapshotOf(before, form);
        if (!snapshot.unchanged()) {
            audit.record("location", id, "Location · " + form.getCode(),
                    form.isActive() ? AuditAction.UPDATE : AuditAction.DEACTIVATE,
                    snapshot, branch, null);
        }
    }

    @Transactional
    @PreAuthorize("hasAuthority('location.manage')")
    public UUID addBin(UUID locationId, String binCode, String zone, BranchView branch) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO storage_bin (id, location_id, bin_code, zone, is_active)
                    VALUES (:id, :locationId, :code, :zone, TRUE)
                    """)
                    .param("id", id, Types.OTHER)
                    .param("locationId", locationId, Types.OTHER)
                    .param("code", binCode.trim().toUpperCase())
                    .param("zone", (zone == null || zone.isBlank()) ? null : zone.trim())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new LocationCodeTakenException(binCode);
        }

        audit.record("storage_bin", id, "Bin · " + binCode, AuditAction.CREATE,
                AuditSnapshot.of().value("Bin code", binCode).value("Zone", zone),
                branch, null);
        return id;
    }

    @Transactional
    @PreAuthorize("hasAuthority('location.manage')")
    public void setBinActive(UUID binId, boolean active, BranchView branch) {
        jdbc.sql("UPDATE storage_bin SET is_active = :active WHERE id = :id")
                .param("id", binId, Types.OTHER)
                .param("active", active)
                .update();

        audit.record("storage_bin", binId, "Bin",
                active ? AuditAction.UPDATE : AuditAction.DEACTIVATE,
                AuditSnapshot.of().field("Active", !active, active), branch, null);
    }

    // ---- helpers ---------------------------------------------------------

    private AuditSnapshot snapshotOf(LocationForm before, LocationForm after) {
        return AuditSnapshot.of()
                .field("Code",     before == null ? null : before.getCode(),         after.getCode())
                .field("Name",     before == null ? null : before.getName(),         after.getName())
                .field("Type",     before == null ? null : before.getLocationType(), after.getLocationType())
                .field("Bonded",   before == null ? null : before.isBonded(),        after.isBonded())
                .field("Sellable", before == null ? null : before.isSellable(),      after.isSellable())
                .field("Active",   before == null ? null : before.isActive(),        after.isActive());
    }

    private LocationRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LocationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"),
                rs.getString("code"),
                rs.getString("name"),
                LocationType.valueOf(rs.getString("location_type")),
                rs.getBoolean("is_bonded"),
                rs.getBoolean("is_sellable"),
                rs.getBoolean("is_active"),
                rs.getInt("bin_count"),
                rs.getInt("distinct_items"),
                rs.getBigDecimal("total_value") == null
                        ? BigDecimal.ZERO : rs.getBigDecimal("total_value"));
    }

    public record BinRow(UUID id, String binCode, String zone, boolean active, int distinctItems) {
        public boolean holdsStock() { return distinctItems > 0; }
    }

    public static class LocationCodeTakenException extends RuntimeException {
        public LocationCodeTakenException(String code) {
            super("Code " + code + " is already used at this branch.");
        }
    }

    public static class LocationHoldsStockException extends RuntimeException {
        public LocationHoldsStockException(String code, String what) {
            super("Location " + code + " holds stock, so " + what
                  + ". Move the stock out on a transfer first.");
        }
    }
}
