package heritier.ntaganira.highbytes.wms.inventory.opening;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.opening
 * - File       : OpeningLookupService.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The pickers the opening balance form needs: places, bins, items, units
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.lookup.BinOption;
import heritier.ntaganira.highbytes.wms.inventory.lookup.ItemOption;
import heritier.ntaganira.highbytes.wms.inventory.lookup.LocationOption;
import heritier.ntaganira.highbytes.wms.inventory.lookup.UnitOption;
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
 * What the opening balance form lets a user choose from, behind
 * {@code opening.create} — each module asks its own pickers under its own
 * right, so reading the cutover form never depends on holding a receiving
 * right.
 *
 * <p>Transit locations are excluded: stock is in transit because a transfer
 * put it there, and no transfer precedes the cutover. V19 refuses one anyway;
 * leaving it out of the picker means nobody is offered a choice the database
 * will reject.
 *
 * <p>Only active places, bins, items and units are offered; the database
 * refuses an inactive one regardless.
 */
@Service
@Transactional(readOnly = true)
public class OpeningLookupService {

    private final JdbcClient jdbc;

    public OpeningLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The places a cutover may load: warehouses and bonded stores, never transit. */
    @PreAuthorize("hasAuthority('opening.view')")
    public List<LocationOption> loadableLocations(UUID branchId) {
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

    @PreAuthorize("hasAuthority('opening.view')")
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

    @PreAuthorize("hasAuthority('opening.view')")
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

    @PreAuthorize("hasAuthority('opening.view')")
    public List<UnitOption> units() {
        return jdbc.sql("SELECT id, code, name FROM uom WHERE is_active ORDER BY code")
                .query((rs, n) -> new UnitOption(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name")))
                .list();
    }

    /**
     * The product type of each item named on the form, so the controller can
     * require a measured thickness for glass before the database does.
     */
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
