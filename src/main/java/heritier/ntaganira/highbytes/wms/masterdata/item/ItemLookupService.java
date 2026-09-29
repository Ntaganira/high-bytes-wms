package heritier.ntaganira.highbytes.wms.masterdata.item;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.item
 * - File       : ItemLookupService.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Lookups the item form needs: units of measure and categories
 * </pre>
 */

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Lookups the item form needs: units and categories. */
@Service
@Transactional(readOnly = true)
public class ItemLookupService {

    private final JdbcClient jdbc;

    public ItemLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Option> units() {
        return jdbc.sql("SELECT id, code, name FROM uom WHERE is_active ORDER BY code")
                .query((rs, n) -> new Option(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name")))
                .list();
    }

    public List<Option> categories() {
        return jdbc.sql("SELECT id, code, name FROM item_category WHERE is_active ORDER BY name")
                .query((rs, n) -> new Option(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name")))
                .list();
    }

    /** The sheet unit, pre-selected when the product type is glass. */
    public UUID defaultUomForGlass() {
        return jdbc.sql("SELECT id FROM uom WHERE code = 'SHEET'")
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    public record Option(UUID id, String code, String name) {
        public String label() {
            return code + " — " + name;
        }
    }
}
