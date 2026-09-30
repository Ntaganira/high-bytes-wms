package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DispatchLookupService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Pickers and stock figures the delivery authorization and delivery note screens need
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.BinOption;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.ItemOption;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.LocationOption;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.UnitOption;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the dispatch forms let a user choose from, and how much stock there is.
 *
 * <p>Customers are listed to holders of {@code dispatch.create} but managed
 * only under {@code partner.manage}: the person authorizing a delivery picks a
 * customer Finance has set up, and an inactive or blocked customer is not
 * offered (the database refuses one anyway). Stock is shown before anyone
 * tries to load it, per location and per bin, because a load the shelf cannot
 * cover is refused at the gate, where it costs a truck's time.
 */
@Service
@Transactional(readOnly = true)
public class DispatchLookupService {

    public record CustomerOption(UUID id, String code, String name) {
        public String label() { return code + " — " + name; }
    }

    private final JdbcClient jdbc;

    public DispatchLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAuthority('dispatch.create')")
    public List<CustomerOption> customers() {
        return jdbc.sql("SELECT id, code, name FROM customer WHERE is_active AND NOT is_blocked ORDER BY name")
                .query((rs, n) -> new CustomerOption(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name")))
                .list();
    }

    /** Where goods may leave from at a branch: its saleable warehouses and bonded stores. */
    @PreAuthorize("hasAuthority('dispatch.create')")
    public List<LocationOption> dispatchLocations(UUID branchId) {
        return jdbc.sql("""
                SELECT id, code, name, is_bonded FROM location
                 WHERE branch_id = :branch AND is_active AND is_sellable AND location_type IN ('WAREHOUSE', 'BONDED')
                 ORDER BY code
                """)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new LocationOption(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getBoolean("is_bonded")))
                .list();
    }

    @PreAuthorize("hasAuthority('dispatch.create')")
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

    @PreAuthorize("hasAuthority('dispatch.create')")
    public List<UnitOption> units() {
        return jdbc.sql("SELECT id, code, name FROM uom WHERE is_active ORDER BY code")
                .query((rs, n) -> new UnitOption(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name")))
                .list();
    }

    @PreAuthorize("hasAuthority('dispatch.view')")
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

    /** Whether the location, or the branch it is at, is bonded: the customs reference is then required. */
    @PreAuthorize("hasAuthority('dispatch.view')")
    public boolean releasesBonded(UUID locationId) {
        return jdbc.sql("""
                SELECT (l.is_bonded OR b.is_bonded) FROM location l JOIN branch b ON b.id = l.branch_id
                 WHERE l.id = :id
                """)
                .param("id", locationId, Types.OTHER).query(Boolean.class).optional().orElse(false);
    }

    @PreAuthorize("hasAuthority('dispatch.create')")
    public Map<UUID, String> productTypes(Collection<UUID> itemIds) {
        Map<UUID, String> types = new HashMap<>();
        if (itemIds.isEmpty()) return types;
        jdbc.sql("SELECT id, product_type FROM item WHERE id IN (:ids)")
                .param("ids", itemIds)
                .query((rs, n) -> {
                    types.put(rs.getObject("id", UUID.class), rs.getString("product_type"));
                    return null;
                }).list();
        return types;
    }

    /**
     * What is on hand of these items at the location, per bin (and unbinned),
     * in the base unit. Only places holding something are listed.
     */
    @PreAuthorize("hasAuthority('dispatch.view')")
    public Map<UUID, List<StockAt>> stockAt(UUID locationId, Collection<UUID> itemIds) {
        Map<UUID, List<StockAt>> stock = new HashMap<>();
        if (locationId == null || itemIds.isEmpty()) return stock;
        jdbc.sql("""
                SELECT sb.item_id, sb.storage_bin_id, b.bin_code, sb.qty_on_hand
                  FROM stock_balance sb LEFT JOIN storage_bin b ON b.id = sb.storage_bin_id
                 WHERE sb.location_id = :location AND sb.item_id IN (:items) AND sb.qty_on_hand > 0
                 ORDER BY b.bin_code NULLS FIRST
                """)
                .param("location", locationId, Types.OTHER)
                .param("items", itemIds)
                .query((rs, n) -> {
                    UUID item = rs.getObject("item_id", UUID.class);
                    stock.computeIfAbsent(item, k -> new java.util.ArrayList<>()).add(new StockAt(item,
                            rs.getObject("storage_bin_id", UUID.class), rs.getString("bin_code"),
                            rs.getBigDecimal("qty_on_hand")));
                    return null;
                }).list();
        return stock;
    }
}
