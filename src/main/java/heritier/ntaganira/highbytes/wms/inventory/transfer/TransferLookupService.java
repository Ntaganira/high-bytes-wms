package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferLookupService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Pickers and stock figures the transfer and receipt screens need
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.lookup.StockAt;
import heritier.ntaganira.highbytes.wms.inventory.lookup.BinOption;
import heritier.ntaganira.highbytes.wms.inventory.lookup.ItemOption;
import heritier.ntaganira.highbytes.wms.inventory.lookup.LocationOption;
import heritier.ntaganira.highbytes.wms.inventory.lookup.UnitOption;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the transfer forms let a user choose from, and how much stock there is.
 *
 * <p>A transfer leaves from a warehouse or bonded location at the branch that
 * raises it and goes to one at a different branch. Stock is shown per bin
 * before anyone tries to send it, because a consignment the shelf cannot cover
 * is refused at the gate.
 */
@Service
@Transactional(readOnly = true)
public class TransferLookupService {

    public record DestinationOption(UUID id, String code, String name, String branchName, boolean bonded) {
        public String label() { return branchName + " · " + code + " — " + name + (bonded ? " (bonded)" : ""); }
    }

    private final JdbcClient jdbc;

    public TransferLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAuthority('transfer.create')")
    public List<LocationOption> sourceLocations(UUID branchId) {
        return jdbc.sql("""
                SELECT id, code, name, is_bonded FROM location
                 WHERE branch_id = :branch AND is_active AND location_type IN ('WAREHOUSE', 'BONDED')
                 ORDER BY code
                """)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new LocationOption(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getBoolean("is_bonded")))
                .list();
    }

    /** Where a transfer from this branch may go: a warehouse or bonded location at any other branch. */
    @PreAuthorize("hasAuthority('transfer.create')")
    public List<DestinationOption> destinations(UUID sourceBranchId) {
        return jdbc.sql("""
                SELECT l.id, l.code, l.name, l.is_bonded, b.name AS branch_name
                  FROM location l JOIN branch b ON b.id = l.branch_id
                 WHERE l.branch_id <> :branch AND l.is_active AND b.is_active
                   AND l.location_type IN ('WAREHOUSE', 'BONDED')
                 ORDER BY b.name, l.code
                """)
                .param("branch", sourceBranchId, Types.OTHER)
                .query((rs, n) -> new DestinationOption(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("branch_name"), rs.getBoolean("is_bonded")))
                .list();
    }

    @PreAuthorize("hasAuthority('transfer.create')")
    public List<ItemOption> items() {
        return jdbc.sql("""
                SELECT i.id, i.item_code, i.description, i.product_type, u.code AS base_uom
                  FROM item i JOIN uom u ON u.id = i.base_uom_id
                 WHERE i.is_active ORDER BY i.item_code
                """)
                .query((rs, n) -> new ItemOption(rs.getObject("id", UUID.class), rs.getString("item_code"),
                        rs.getString("description"), rs.getString("product_type"), rs.getString("base_uom")))
                .list();
    }

    @PreAuthorize("hasAuthority('transfer.create')")
    public List<UnitOption> units() {
        return jdbc.sql("SELECT id, code, name FROM uom WHERE is_active ORDER BY code")
                .query((rs, n) -> new UnitOption(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name")))
                .list();
    }

    @PreAuthorize("hasAuthority('transfer.view')")
    public List<BinOption> binsOf(UUID locationId) {
        if (locationId == null) return List.of();
        return jdbc.sql("""
                SELECT id, location_id, bin_code, zone FROM storage_bin
                 WHERE location_id = :location AND is_active ORDER BY bin_code
                """)
                .param("location", locationId, Types.OTHER)
                .query((rs, n) -> new BinOption(rs.getObject("id", UUID.class),
                        rs.getObject("location_id", UUID.class), rs.getString("bin_code"), rs.getString("zone")))
                .list();
    }

    /** Whether either location, or either branch, is bonded: the customs reference is then required. */
    @PreAuthorize("hasAuthority('transfer.view')")
    public boolean touchesBonded(UUID fromLocationId, UUID toLocationId) {
        if (fromLocationId == null || toLocationId == null) return false;
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM location l JOIN branch b ON b.id = l.branch_id
                                WHERE l.id IN (:from, :to) AND (l.is_bonded OR b.is_bonded))
                """)
                .param("from", fromLocationId, Types.OTHER)
                .param("to", toLocationId, Types.OTHER)
                .query(Boolean.class).single();
    }

    /** What is on hand of these items at the location, per bin and unbinned, in the base unit. */
    @PreAuthorize("hasAuthority('transfer.view')")
    public Map<UUID, List<StockAt>> stockAt(UUID locationId, Collection<UUID> itemIds) {
        Map<UUID, List<StockAt>> stock = new HashMap<>();
        if (locationId == null || itemIds.isEmpty()) return stock;
        jdbc.sql("""
                SELECT sb.item_id, sb.storage_bin_id, b.bin_code, sb.qty_on_hand
                  FROM stock_balance sb LEFT JOIN storage_bin b ON b.id = sb.storage_bin_id
                 WHERE sb.location_id = :location AND sb.item_id IN (:items) AND sb.qty_on_hand > 0
                   -- A place under a live count shows no book, and nothing moves from it (V15).
                   AND count_freezing(sb.item_id, sb.location_id) IS NULL
                 ORDER BY b.bin_code NULLS FIRST
                """)
                .param("location", locationId, Types.OTHER)
                .param("items", itemIds)
                .query((rs, n) -> {
                    UUID item = rs.getObject("item_id", UUID.class);
                    stock.computeIfAbsent(item, k -> new ArrayList<>()).add(new StockAt(item,
                            rs.getObject("storage_bin_id", UUID.class), rs.getString("bin_code"),
                            rs.getBigDecimal("qty_on_hand")));
                    return null;
                }).list();
        return stock;
    }
}
