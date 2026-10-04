package heritier.ntaganira.highbytes.wms.inventory.cutting;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.cutting
 * - File       : CuttingLookupService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the cutting order form offers: customers, places, sheets of glass and their stock, bins
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.lookup.CustomerOption;
import heritier.ntaganira.highbytes.wms.inventory.lookup.StockAt;
import heritier.ntaganira.highbytes.wms.inventory.lookup.BinOption;
import heritier.ntaganira.highbytes.wms.inventory.lookup.LocationOption;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The choices on a cutting order. Customers are Finance's to set up; an inactive or blocked one is not offered (the
 * database refuses one anyway). Sheets are glass counted by the sheet with a size on the item master, off-cuts
 * included: an off-cut is cut again like any sheet. Stock is shown per bin before the order is raised, leaving out
 * any place under a live count (V15).
 */
@Service
@Transactional(readOnly = true)
public class CuttingLookupService {

    /** A sheet that can be cut: its code, its size, and how many there are at the place (null: being counted). */
    public record SheetOption(UUID id, String code, String description, BigDecimal widthMm, BigDecimal heightMm,
                              BigDecimal thicknessMm, boolean offcut, BigDecimal onHand) {

        public String label() {
            return code + " — " + description;
        }
    }

    private final JdbcClient jdbc;

    public CuttingLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAuthority('cutting.create')")
    public List<CustomerOption> customers() {
        return jdbc.sql("SELECT id, code, name FROM customer WHERE is_active AND NOT is_blocked ORDER BY name")
                .query((rs, n) -> new CustomerOption(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name")))
                .list();
    }

    /** Where glass is cut at a branch: its warehouses, bonded stores and cutting floors. */
    @PreAuthorize("hasAuthority('cutting.create')")
    public List<LocationOption> cutLocations(UUID branchId) {
        return jdbc.sql("""
                SELECT id, code, name, is_bonded FROM location
                 WHERE branch_id = :branch AND is_active AND location_type IN ('WAREHOUSE', 'BONDED', 'CUTTING')
                 ORDER BY (location_type = 'CUTTING') DESC, code
                """)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new LocationOption(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getBoolean("is_bonded")))
                .list();
    }

    /**
     * Every sheet that can be cut, with what is on hand of it at the place (null when the place is being counted,
     * never the book). Those with stock first.
     */
    @PreAuthorize("hasAuthority('cutting.create')")
    public List<SheetOption> sheets(UUID locationId) {
        requireAtPlace(locationId, "cutting.create");
        // Ordered by what is shown, never by the book: a place under a count sorts as one holding nothing.
        return jdbc.sql("""
                SELECT * FROM (
                  SELECT i.id, i.item_code, i.description, i.width_mm, i.height_mm, i.thickness_mm, i.is_remnant,
                         CASE WHEN :location::uuid IS NULL THEN 0
                              WHEN count_freezing(i.id, :location::uuid) IS NOT NULL THEN NULL
                              ELSE COALESCE((SELECT SUM(sb.qty_on_hand) FROM stock_balance sb
                                              WHERE sb.item_id = i.id AND sb.location_id = :location::uuid), 0)
                         END AS on_hand
                    FROM item i JOIN uom u ON u.id = i.base_uom_id
                   WHERE i.is_active AND i.product_type = 'GLASS' AND u.code = 'SHEET'
                     AND i.width_mm IS NOT NULL AND i.height_mm IS NOT NULL) s
                 ORDER BY (COALESCE(s.on_hand, 0) > 0) DESC, s.item_code
                """)
                .param("location", locationId, Types.OTHER)
                .query((rs, n) -> new SheetOption(rs.getObject("id", UUID.class), rs.getString("item_code"),
                        rs.getString("description"), rs.getBigDecimal("width_mm"), rs.getBigDecimal("height_mm"),
                        rs.getBigDecimal("thickness_mm"), rs.getBoolean("is_remnant"), rs.getBigDecimal("on_hand")))
                .list();
    }

    @PreAuthorize("hasAuthority('cutting.view')")
    public List<BinOption> bins(UUID locationId) {
        if (locationId == null) return List.of();
        requireAtPlace(locationId, "cutting.view");
        return jdbc.sql("""
                SELECT id, location_id, bin_code, zone FROM storage_bin
                 WHERE location_id = :location AND is_active ORDER BY bin_code
                """)
                .param("location", locationId, Types.OTHER)
                .query((rs, n) -> new BinOption(rs.getObject("id", UUID.class), rs.getObject("location_id", UUID.class),
                        rs.getString("bin_code"), rs.getString("zone")))
                .list();
    }

    /** What is on hand of an item at a place, per bin, leaving out a place under a live count. */
    @PreAuthorize("hasAuthority('cutting.view')")
    public List<StockAt> stockAt(UUID locationId, UUID itemId) {
        if (locationId == null || itemId == null) return List.of();
        requireAtPlace(locationId, "cutting.view");
        List<StockAt> places = new ArrayList<>();
        jdbc.sql("""
                SELECT sb.item_id, sb.storage_bin_id, b.bin_code, sb.qty_on_hand
                  FROM stock_balance sb LEFT JOIN storage_bin b ON b.id = sb.storage_bin_id
                 WHERE sb.location_id = :location AND sb.item_id = :item AND sb.qty_on_hand > 0
                   AND count_freezing(sb.item_id, sb.location_id) IS NULL
                 ORDER BY b.bin_code NULLS FIRST
                """)
                .param("location", locationId, Types.OTHER)
                .param("item", itemId, Types.OTHER)
                .query((rs, n) -> places.add(new StockAt(rs.getObject("item_id", UUID.class),
                        rs.getObject("storage_bin_id", UUID.class), rs.getString("bin_code"),
                        rs.getBigDecimal("qty_on_hand"))))
                .list();
        return places;
    }

    /** Whether the place, or its branch, is bonded: the customs reference is then required. */
    @PreAuthorize("hasAuthority('cutting.view')")
    public boolean cutsBonded(UUID locationId) {
        if (locationId == null) return false;
        return jdbc.sql("""
                SELECT (l.is_bonded OR b.is_bonded) FROM location l JOIN branch b ON b.id = l.branch_id
                 WHERE l.id = :id
                """)
                .param("id", locationId, Types.OTHER).query(Boolean.class).optional().orElse(false);
    }

    /** Refuses, as a 403, a place at a branch where the reader does not hold the right (a read: not recorded). */
    private void requireAtPlace(UUID locationId, String right) {
        if (locationId == null) return;
        jdbc.sql("SELECT branch_id FROM location WHERE id = :id")
                .param("id", locationId, Types.OTHER)
                .query(UUID.class).optional()
                .ifPresent(branch -> CurrentUser.requireAt(right, branch));
    }

    /** Whether the place is being counted for this item: nothing moves from it until the count is verified. */
    @PreAuthorize("hasAuthority('cutting.view')")
    public String countFreezing(UUID locationId, UUID itemId) {
        if (locationId == null || itemId == null) return null;
        return jdbc.sql("SELECT count_freezing(:item, :location)")
                .param("item", itemId, Types.OTHER)
                .param("location", locationId, Types.OTHER)
                .query(String.class).optional().orElse(null);
    }
}
