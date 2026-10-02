package heritier.ntaganira.highbytes.wms.search;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.search
 * - File       : SearchService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The search box's master-data reads: items, locations, suppliers, customers, each behind its right
 * </pre>
 */

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * What the search box finds besides documents (those are {@code DocumentListService}'s, which judges each by its
 * type's view right at its branch). Each read here is behind the right its own list screen asks for, so search
 * shows nobody a record whose list they could not open: items and locations behind {@code item.view}, suppliers and
 * customers behind {@code partner.manage}. A hit names the record and links to its page; it carries no stock
 * figure, so no count's book reaches it.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    /** One record found: its code, its name, a line of detail, and its page. */
    public record Hit(UUID id, String code, String name, String detail, String href, boolean active) {}

    private final JdbcClient jdbc;

    public SearchService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAuthority('item.view')")
    public List<Hit> items(String text, int limit) {
        return jdbc.sql("""
                SELECT i.id, i.item_code, i.description, i.product_type, i.is_active
                  FROM item i
                 WHERE i.item_code ILIKE :like ESCAPE '\\' OR i.description ILIKE :like ESCAPE '\\'
                 ORDER BY i.is_active DESC, (lower(i.item_code) = lower(:exact)) DESC, i.item_code
                 LIMIT :limit
                """)
                .param("like", like(text))
                .param("exact", text.trim())
                .param("limit", limit)
                .query((rs, n) -> new Hit(rs.getObject("id", UUID.class), rs.getString("item_code"),
                        rs.getString("description"), product(rs.getString("product_type")),
                        "/items/" + rs.getObject("id", UUID.class), rs.getBoolean("is_active")))
                .list();
    }

    @PreAuthorize("hasAuthority('item.view')")
    public List<Hit> locations(String text, int limit) {
        return jdbc.sql("""
                SELECT l.id, l.code, l.name, l.location_type, b.name AS branch_name, l.is_active
                  FROM location l
                  JOIN branch b ON b.id = l.branch_id
                 WHERE l.code ILIKE :like ESCAPE '\\' OR l.name ILIKE :like ESCAPE '\\'
                 ORDER BY l.is_active DESC, l.code
                 LIMIT :limit
                """)
                .param("like", like(text))
                .param("limit", limit)
                .query((rs, n) -> new Hit(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("branch_name") + " · " + product(rs.getString("location_type")),
                        "/locations/" + rs.getObject("id", UUID.class), rs.getBoolean("is_active")))
                .list();
    }

    @PreAuthorize("hasAuthority('partner.manage')")
    public List<Hit> suppliers(String text, int limit) {
        return jdbc.sql("""
                SELECT s.id, s.code, s.name, s.tin, s.is_active
                  FROM supplier s
                 WHERE s.code ILIKE :like ESCAPE '\\' OR s.name ILIKE :like ESCAPE '\\' OR s.tin ILIKE :like ESCAPE '\\'
                 ORDER BY s.is_active DESC, s.name
                 LIMIT :limit
                """)
                .param("like", like(text))
                .param("limit", limit)
                .query((rs, n) -> new Hit(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("tin") == null ? "Supplier" : "Supplier · TIN " + rs.getString("tin"),
                        "/suppliers/" + rs.getObject("id", UUID.class), rs.getBoolean("is_active")))
                .list();
    }

    @PreAuthorize("hasAuthority('partner.manage')")
    public List<Hit> customers(String text, int limit) {
        return jdbc.sql("""
                SELECT c.id, c.code, c.name, c.tin, c.is_active, c.is_blocked
                  FROM customer c
                 WHERE c.code ILIKE :like ESCAPE '\\' OR c.name ILIKE :like ESCAPE '\\' OR c.tin ILIKE :like ESCAPE '\\'
                 ORDER BY c.is_active DESC, c.name
                 LIMIT :limit
                """)
                .param("like", like(text))
                .param("limit", limit)
                .query((rs, n) -> new Hit(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        (rs.getBoolean("is_blocked") ? "Customer · blocked" : "Customer")
                                + (rs.getString("tin") == null ? "" : " · TIN " + rs.getString("tin")),
                        "/customers/" + rs.getObject("id", UUID.class), rs.getBoolean("is_active")))
                .list();
    }

    /** "GLASS" → "Glass", "BONDED" → "Bonded". */
    private static String product(String code) {
        if (code == null || code.isEmpty()) return "";
        String lower = code.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** The reader's words as a LIKE pattern: their % and _ are characters, not wildcards. */
    static String like(String text) {
        return "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
