package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : ReceivingLookupService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The pickers the goods received form needs: suppliers, locations, bins, items, units
 * </pre>
 */

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
 * What the receiving form lets a user choose from.
 *
 * <p>Suppliers are listed to holders of {@code receiving.create} but created
 * only under {@code partner.manage}: the receiver picks a supplier Finance
 * has set up and cannot invent one, which keeps who may be paid separate from
 * who receives goods.
 *
 * <p>Only active suppliers, locations, bins and items are offered; the
 * database refuses an inactive one anyway.
 */
@Service
@Transactional(readOnly = true)
public class ReceivingLookupService {

    public record SupplierOption(UUID id, String code, String name) {
        public String label() { return code + " — " + name; }
    }

    public record LocationOption(UUID id, String code, String name, boolean bonded) {
        public String label() { return code + " — " + name + (bonded ? " (bonded)" : ""); }
    }

    public record BinOption(UUID id, UUID locationId, String code, String zone) {
        public String label() { return zone == null ? code : code + " · " + zone; }
    }

    public record ItemOption(UUID id, String code, String description, String productType, String baseUomCode) {
        public String label() { return code + " — " + description; }
        public boolean glass() { return "GLASS".equals(productType); }
    }

    public record UnitOption(UUID id, String code, String name) {
        public String label() { return code + " — " + name; }
    }

    private final JdbcClient jdbc;

    public ReceivingLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAuthority('receiving.create')")
    public List<SupplierOption> suppliers() {
        return jdbc.sql("SELECT id, code, name FROM supplier WHERE is_active ORDER BY name")
                .query((rs, n) -> new SupplierOption(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name")))
                .list();
    }

    /** Where goods may be received at a branch: its warehouses and bonded stores. */
    @PreAuthorize("hasAuthority('receiving.create')")
    public List<LocationOption> receivingLocations(UUID branchId) {
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

    @PreAuthorize("hasAuthority('receiving.create')")
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

    @PreAuthorize("hasAuthority('receiving.create')")
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

    @PreAuthorize("hasAuthority('receiving.create')")
    public List<UnitOption> units() {
        return jdbc.sql("SELECT id, code, name FROM uom WHERE is_active ORDER BY code")
                .query((rs, n) -> new UnitOption(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name")))
                .list();
    }

    /** The product type of each item, for the glass-needs-thickness check. */
    @PreAuthorize("hasAuthority('receiving.create')")
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
}
