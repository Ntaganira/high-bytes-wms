package heritier.ntaganira.highbytes.wms.masterdata.supplier;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.supplier
 * - File       : SupplierService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The supplier master: search, create, update, deactivate and reactivate, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The supplier master.
 *
 * <p>Suppliers are set up and changed by whoever holds
 * {@code partner.manage} (Finance), not by the people who receive goods from
 * them: the receiver picks a supplier from this list and cannot invent one.
 * A supplier that a receipt has named is deactivated, never deleted.
 */
@Service
@Transactional(readOnly = true)
public class SupplierService {

    private static final String SELECT = """
            SELECT s.id, s.code, s.name, s.tin, s.country_code, s.is_foreign, s.address, s.phone, s.email,
                   s.is_active,
                   (SELECT COUNT(*) FROM goods_received_note g WHERE g.supplier_id = s.id) AS receipt_count
              FROM supplier s
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public SupplierService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- reads -----------------------------------------------------------

    @PreAuthorize("hasAuthority('partner.manage')")
    public List<SupplierRow> search(String query, boolean includeInactive) {
        // A nullable parameter tested with IS NULL carries a cast: PostgreSQL cannot type a bare NULL.
        return jdbc.sql(SELECT + """
                 WHERE (:query::text IS NULL OR s.code ILIKE :like OR s.name ILIKE :like OR s.tin ILIKE :like)
                   AND (:includeInactive OR s.is_active)
                 ORDER BY s.is_active DESC, s.name
                 LIMIT 300
                """)
                .param("query", blankToNull(query))
                .param("like", query == null ? null : "%" + query.trim() + "%")
                .param("includeInactive", includeInactive)
                .query(this::map)
                .list();
    }

    @PreAuthorize("hasAuthority('partner.manage')")
    public Optional<SupplierRow> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE s.id = :id")
                .param("id", id, Types.OTHER)
                .query(this::map)
                .optional();
    }

    @PreAuthorize("hasAuthority('partner.manage')")
    public SupplierForm formFor(UUID id) {
        var row = findById(id).orElseThrow(() -> new SupplierNotFoundException(id));
        var f = new SupplierForm();
        f.setId(row.id());
        f.setCode(row.code());
        f.setName(row.name());
        f.setTin(row.tin());
        f.setCountryCode(row.countryCode());
        f.setAddress(row.address());
        f.setPhone(row.phone());
        f.setEmail(row.email());
        f.setActive(row.active());
        return f;
    }

    // ---- writes ----------------------------------------------------------

    @Transactional
    @PreAuthorize("hasAuthority('partner.manage')")
    public UUID create(SupplierForm form, BranchView branch) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO supplier (id, code, name, tin, country_code, is_foreign, address, phone, email, is_active)
                    VALUES (:id, :code, :name, :tin, :country, :foreign, :address, :phone, :email, :active)
                    """)
                    .param("id", id, Types.OTHER)
                    .params(columns(form))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new SupplierCodeTakenException(form.getCode());
        }
        audit.record("supplier", id, "Supplier · " + form.getCode(), AuditAction.CREATE,
                snapshotOf(null, form), branch, null);
        return id;
    }

    @Transactional
    @PreAuthorize("hasAuthority('partner.manage')")
    public void update(UUID id, SupplierForm form, BranchView branch) {
        SupplierForm before = formFor(id);
        try {
            jdbc.sql("""
                    UPDATE supplier
                       SET code = :code, name = :name, tin = :tin, country_code = :country, is_foreign = :foreign,
                           address = :address, phone = :phone, email = :email, is_active = :active,
                           updated_at = now()
                     WHERE id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .params(columns(form))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new SupplierCodeTakenException(form.getCode());
        }
        var snapshot = snapshotOf(before, form);
        if (!snapshot.unchanged()) {
            audit.record("supplier", id, "Supplier · " + form.getCode(),
                    form.isActive() ? AuditAction.UPDATE : AuditAction.DEACTIVATE, snapshot, branch, null);
        }
    }

    /** Deactivation, never deletion: past receipts keep pointing at the supplier. */
    @Transactional
    @PreAuthorize("hasAuthority('partner.manage')")
    public void setActive(UUID id, boolean active, String reason, BranchView branch) {
        SupplierForm before = formFor(id);
        if (before.isActive() == active) return;
        jdbc.sql("UPDATE supplier SET is_active = :active, updated_at = now() WHERE id = :id")
                .param("id", id, Types.OTHER)
                .param("active", active)
                .update();
        audit.record("supplier", id, "Supplier · " + before.getCode(),
                active ? AuditAction.UPDATE : AuditAction.DEACTIVATE,
                AuditSnapshot.of().field("Active", before.isActive(), active), branch, reason);
    }

    // ---- helpers ---------------------------------------------------------

    private java.util.Map<String, Object> columns(SupplierForm form) {
        var m = new java.util.HashMap<String, Object>();
        m.put("code", form.getCode());
        m.put("name", form.getName());
        m.put("tin", form.getTin());
        m.put("country", form.getCountryCode());
        m.put("foreign", form.isForeign());
        m.put("address", form.getAddress());
        m.put("phone", form.getPhone());
        m.put("email", form.getEmail());
        m.put("active", form.isActive());
        return m;
    }

    private AuditSnapshot snapshotOf(SupplierForm before, SupplierForm after) {
        return AuditSnapshot.of()
                .field("Code",    before == null ? null : before.getCode(),        after.getCode())
                .field("Name",    before == null ? null : before.getName(),        after.getName())
                .field("TIN",     before == null ? null : before.getTin(),         after.getTin())
                .field("Country", before == null ? null : before.getCountryCode(), after.getCountryCode())
                .field("Address", before == null ? null : before.getAddress(),     after.getAddress())
                .field("Phone",   before == null ? null : before.getPhone(),       after.getPhone())
                .field("Email",   before == null ? null : before.getEmail(),       after.getEmail())
                .field("Active",  before == null ? null : before.isActive(),       after.isActive());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SupplierRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SupplierRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("tin"),
                rs.getString("country_code"),
                rs.getBoolean("is_foreign"),
                rs.getString("address"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getBoolean("is_active"),
                rs.getInt("receipt_count"));
    }

    // ---- failures the user should see, not a stack trace -------------------

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class SupplierNotFoundException extends RuntimeException {
        public SupplierNotFoundException(UUID id) {
            super("No supplier with id " + id);
        }
    }

    public static class SupplierCodeTakenException extends RuntimeException {
        public SupplierCodeTakenException(String code) {
            super("Supplier code " + code + " is already in use.");
        }
    }
}
