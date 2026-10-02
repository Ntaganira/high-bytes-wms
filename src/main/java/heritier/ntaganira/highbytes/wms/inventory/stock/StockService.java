package heritier.ntaganira.highbytes.wms.inventory.stock;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.stock
 * - File       : StockService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Stock at a branch, read: balances by item and by place, and the ledger behind them
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stock at the branch the reader works in, read and never written: what each item holds, where, at what cost, and
 * every ledger movement behind it.
 *
 * <p><b>A place under a live count shows no book.</b> From the moment a count opens until its verification is
 * signed (V15), the quantity, value and movements of every place it counts ({@code count_freezing}) reach no
 * page: they are left out of every total, never subtracted from one, so a total cannot be used to work the book
 * out. Which places are being counted is read from the counts' own sheets, never from the book, so a place the
 * book says is empty reads exactly like one it says is full. The place is still listed, as being counted, and
 * nothing can move there anyway.
 *
 * <p>Balances read the {@code stock_balance} cache, which the ledger keeps; one item's places are also read from
 * the ledger itself, the authority, and a difference is flagged (the daily close reports it too).
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('stock.view')")
public class StockService {

    /** One item at the branch: totals of the places that may be read. */
    public record ItemStock(UUID itemId, String itemCode, String description, String productType, String uom,
                            BigDecimal onHand, BigDecimal value, int places, int placesCounted,
                            BigDecimal reorderLevel, LocalDateTime lastMovementAt) {

        /** Below its reorder level on what may be read; never judged while a place of it is being counted. */
        public boolean belowReorder() {
            return reorderLevel != null && placesCounted == 0 && onHand.compareTo(reorderLevel) < 0;
        }
    }

    /** One place (an item at a location, in a bin or unbinned). Quantities are null while it is being counted. */
    public record PlaceStock(UUID locationId, String locationCode, String locationName, String locationType,
                             String binCode, BigDecimal onHand, BigDecimal ledgerOnHand, BigDecimal averageCost,
                             BigDecimal value, LocalDateTime lastMovementAt, LocalDateTime lastCountedAt,
                             String countingSerial) {

        public boolean counted() {
            return countingSerial != null;
        }

        /** The cache and the ledger disagree: the daily close reports it as an exception. */
        public boolean cacheDiffers() {
            return !counted() && onHand != null && ledgerOnHand != null && onHand.compareTo(ledgerOnHand) != 0;
        }
    }

    /** One ledger movement, with the document it answers to. */
    public record Movement(long id, LocalDate businessDate, LocalDateTime movementAt, String movementType,
                           String direction, UUID documentId, String serialNo, String documentType,
                           String ticketSerial, UUID itemId, String itemCode, String itemDescription, String uom,
                           String locationCode, String binCode, BigDecimal quantity, BigDecimal unitCost,
                           BigDecimal value, BigDecimal runningBalance, String postedBy, Long reversesMovementId) {}

    /** A count under way at the branch: what it hides, and until when. */
    public record LiveCount(UUID documentId, String serialNo, String locationCode, String scope) {}

    /** What the movements list looks for; every field optional. Dates are business dates, inclusive. */
    public record MovementQuery(LocalDate from, LocalDate to, UUID itemId, UUID locationId, String direction,
                                String text, Long before) {

        public static MovementQuery ofItem(UUID itemId) {
            return new MovementQuery(null, null, itemId, null, null, null, null);
        }
    }

    /** The places on the sheets of the counts at the branch still blind: what is being counted, book unread. */
    private static final String COUNTED = """
            SELECT DISTINCT cl.item_id, c.location_id, cl.storage_bin_id, d.serial_no
              FROM stock_count_line cl
              JOIN stock_count c ON c.document_id = cl.document_id
              JOIN document d    ON d.id = c.document_id
              JOIN location cloc ON cloc.id = c.location_id
             WHERE cloc.branch_id = :branch
               AND d.status IN ('DRAFT', 'PENDING')
               AND count_is_blind(d.id)
            """;

    private static final String ITEMS = """
            WITH places AS (
                SELECT sb.item_id, sb.qty_on_hand, sb.total_value, sb.last_movement_at
                  FROM stock_balance sb
                  JOIN location l ON l.id = sb.location_id
                 WHERE l.branch_id = :branch
                   AND sb.qty_on_hand <> 0
                   AND count_freezing(sb.item_id, sb.location_id) IS NULL
                   AND (:location::uuid IS NULL OR sb.location_id = :location::uuid)
            ),
            counted AS (
                SELECT k.item_id, COUNT(*) AS n
                  FROM (
            """ + COUNTED + """
                       ) k
                 WHERE (:location::uuid IS NULL OR k.location_id = :location::uuid)
                 GROUP BY k.item_id
            )
            SELECT i.id, i.item_code, i.description, i.product_type, u.code AS uom, i.reorder_level,
                   COALESCE(SUM(p.qty_on_hand), 0)  AS on_hand,
                   COALESCE(SUM(p.total_value), 0)  AS value,
                   COUNT(p.item_id)                 AS places,
                   COALESCE(MAX(k.n), 0)            AS places_counted,
                   MAX(p.last_movement_at)          AS last_movement_at
              FROM item i
              JOIN uom u ON u.id = i.base_uom_id
         LEFT JOIN places p  ON p.item_id = i.id
         LEFT JOIN counted k ON k.item_id = i.id
             WHERE (:item::uuid IS NULL OR i.id = :item::uuid)
               AND (:q::text IS NULL OR i.item_code ILIKE :q::text ESCAPE '\\' OR i.description ILIKE :q::text ESCAPE '\\')
               AND (:type::text IS NULL OR i.product_type = :type::text)
             GROUP BY i.id, u.code
            HAVING COUNT(p.item_id) > 0
                OR MAX(k.n) > 0
                OR :item::uuid IS NOT NULL
                OR (:belowReorder AND :location::uuid IS NULL AND i.is_active AND i.reorder_level IS NOT NULL)
             ORDER BY i.item_code
             LIMIT :limit
            """;

    private static final String PLACES = """
            SELECT l.id AS location_id, l.code AS location_code, l.name AS location_name, l.location_type,
                   b.bin_code, sb.qty_on_hand, sb.average_unit_cost, sb.total_value,
                   sb.last_movement_at, sb.last_counted_at, NULL AS counting,
                   (SELECT COALESCE(SUM(m.signed_quantity), 0)
                      FROM stock_movement m
                     WHERE m.item_id = sb.item_id AND m.location_id = sb.location_id
                       AND m.storage_bin_id IS NOT DISTINCT FROM sb.storage_bin_id) AS ledger_qty
              FROM stock_balance sb
              JOIN location l         ON l.id = sb.location_id
         LEFT JOIN storage_bin b      ON b.id = sb.storage_bin_id
             WHERE sb.item_id = :item AND l.branch_id = :branch
               AND count_freezing(sb.item_id, sb.location_id) IS NULL
            UNION ALL
            SELECT l.id, l.code, l.name, l.location_type, b.bin_code, NULL, NULL, NULL, NULL,
                   (SELECT x.last_counted_at FROM stock_balance x
                     WHERE x.item_id = k.item_id AND x.location_id = k.location_id
                       AND x.storage_bin_id IS NOT DISTINCT FROM k.storage_bin_id),
                   k.serial_no, NULL
              FROM (
            """ + COUNTED + """
                   ) k
              JOIN location l         ON l.id = k.location_id
         LEFT JOIN storage_bin b      ON b.id = k.storage_bin_id
             WHERE k.item_id = :item
             ORDER BY 2, 5 NULLS FIRST
            """;

    private static final String MOVEMENTS = """
            SELECT m.id, m.business_date, m.movement_at, m.direction, m.signed_quantity, m.unit_cost, m.value,
                   m.running_balance, m.reverses_movement_id, t.movement_type,
                   COALESCE(src.id, d.id)            AS doc_id,
                   COALESCE(src.serial_no, d.serial_no) AS serial_no,
                   COALESCE(sdt.name, dt.name)       AS doc_type,
                   d.serial_no                       AS ticket_serial,
                   i.id AS item_id, i.item_code, i.description, u.code AS uom,
                   l.code AS location_code, b.bin_code, p.full_name AS posted_by
              FROM stock_movement m
              JOIN location l               ON l.id = m.location_id
              JOIN document d               ON d.id = m.document_id
              JOIN document_type dt         ON dt.id = d.document_type_id
         LEFT JOIN transaction_ticket t     ON t.document_id = d.id
         LEFT JOIN document src             ON src.id = t.source_document_id
         LEFT JOIN document_type sdt        ON sdt.id = src.document_type_id
              JOIN item i                   ON i.id = m.item_id
              JOIN uom u                    ON u.id = i.base_uom_id
         LEFT JOIN storage_bin b            ON b.id = m.storage_bin_id
              JOIN app_user p               ON p.id = m.posted_by
             WHERE l.branch_id = :branch
               AND count_freezing(m.item_id, m.location_id) IS NULL
               AND (:before::bigint IS NULL OR m.id < :before::bigint)
               AND (:fromDate::date IS NULL OR m.business_date >= :fromDate::date)
               AND (:toDate::date IS NULL OR m.business_date <= :toDate::date)
               AND (:item::uuid IS NULL OR m.item_id = :item::uuid)
               AND (:location::uuid IS NULL OR m.location_id = :location::uuid)
               AND (:direction::text IS NULL OR m.direction = :direction::text)
               AND (:text::text IS NULL
                    OR i.item_code ILIKE :text::text ESCAPE '\\' OR i.description ILIKE :text::text ESCAPE '\\'
                    OR d.serial_no ILIKE :text::text ESCAPE '\\' OR src.serial_no ILIKE :text::text ESCAPE '\\')
             ORDER BY m.id DESC
             LIMIT :limit
            """;

    private final JdbcClient jdbc;

    public StockService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- balances --------------------------------------------------------

    /**
     * Every item holding stock at the branch (or at one location of it), its places under a live count left out of
     * every figure. With {@code belowReorder}, only the items under their reorder level, including those holding
     * nothing at all.
     */
    public List<ItemStock> items(UUID branchId, String text, String productType, UUID locationId,
                                 boolean belowReorder, int limit) {
        List<ItemStock> rows = jdbc.sql(ITEMS)
                .param("branch", branchId, Types.OTHER)
                .param("location", locationId, Types.OTHER)
                .param("item", null, Types.OTHER)
                .param("q", like(text), Types.VARCHAR)
                .param("type", blankToNull(productType), Types.VARCHAR)
                .param("belowReorder", belowReorder)
                .param("limit", belowReorder ? 5000 : limit)
                .query(this::itemStock)
                .list();
        if (!belowReorder) return rows;
        return rows.stream().filter(ItemStock::belowReorder).limit(limit).toList();
    }

    /** One item at the branch: the item's own figures, from the same rule as the list. */
    public Optional<ItemStock> item(UUID branchId, UUID itemId) {
        return jdbc.sql(ITEMS)
                .param("branch", branchId, Types.OTHER)
                .param("location", null, Types.OTHER)
                .param("item", itemId, Types.OTHER)
                .param("q", null, Types.VARCHAR)
                .param("type", null, Types.VARCHAR)
                .param("belowReorder", false)
                .param("limit", 1)
                .query(this::itemStock)
                .optional();
    }

    /** Where an item is held at the branch, place by place; a place being counted shows no quantity. */
    public List<PlaceStock> places(UUID branchId, UUID itemId) {
        return jdbc.sql(PLACES)
                .param("branch", branchId, Types.OTHER)
                .param("item", itemId, Types.OTHER)
                .query((rs, n) -> {
                    String counting = rs.getString("counting");
                    boolean hide = counting != null;
                    return new PlaceStock(
                            rs.getObject("location_id", UUID.class),
                            rs.getString("location_code"),
                            rs.getString("location_name"),
                            rs.getString("location_type"),
                            rs.getString("bin_code"),
                            hide ? null : rs.getBigDecimal("qty_on_hand"),
                            hide ? null : rs.getBigDecimal("ledger_qty"),
                            hide ? null : rs.getBigDecimal("average_unit_cost"),
                            hide ? null : rs.getBigDecimal("total_value"),
                            hide ? null : KigaliTime.read(rs, "last_movement_at"),
                            KigaliTime.read(rs, "last_counted_at"),
                            counting);
                })
                .list()
                .stream()
                // A place emptied long ago is history; one being counted, or disagreeing with the ledger, is not.
                .filter(p -> p.counted() || p.onHand().signum() != 0 || p.cacheDiffers())
                .toList();
    }

    // ---- the ledger ------------------------------------------------------

    /** Movements at the branch's places, newest first, none at a place under a live count. */
    public List<Movement> movements(UUID branchId, MovementQuery q, int limit) {
        String direction = q.direction() != null && List.of("IN", "OUT").contains(q.direction()) ? q.direction() : null;
        return jdbc.sql(MOVEMENTS)
                .param("branch", branchId, Types.OTHER)
                .param("before", q.before(), Types.BIGINT)
                .param("fromDate", q.from(), Types.DATE)
                .param("toDate", q.to(), Types.DATE)
                .param("item", q.itemId(), Types.OTHER)
                .param("location", q.locationId(), Types.OTHER)
                .param("direction", direction, Types.VARCHAR)
                .param("text", like(q.text()), Types.VARCHAR)
                .param("limit", limit)
                .query(this::movement)
                .list();
    }

    /** A location at the branch, for the filters. */
    public record Place(UUID id, String code, String name) {}

    public List<Place> locations(UUID branchId) {
        return jdbc.sql("SELECT id, code, name FROM location WHERE branch_id = :branch ORDER BY is_active DESC, code")
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new Place(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")))
                .list();
    }

    // ---- counts under way ------------------------------------------------

    /** The counts at the branch still blind: each hides the places it counts until its verification is signed. */
    public List<LiveCount> liveCounts(UUID branchId) {
        return jdbc.sql("""
                SELECT d.id, d.serial_no, l.code AS location_code, c.scope
                  FROM stock_count c
                  JOIN document d ON d.id = c.document_id
                  JOIN location l ON l.id = c.location_id
                 WHERE l.branch_id = :branch
                   AND d.status IN ('DRAFT', 'PENDING')
                   AND count_is_blind(d.id)
                 ORDER BY d.created_at
                """)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new LiveCount(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getString("location_code"), rs.getString("scope")))
                .list();
    }

    // ---- helpers ---------------------------------------------------------

    private ItemStock itemStock(ResultSet rs, int rowNum) throws SQLException {
        return new ItemStock(
                rs.getObject("id", UUID.class),
                rs.getString("item_code"),
                rs.getString("description"),
                rs.getString("product_type"),
                rs.getString("uom"),
                rs.getBigDecimal("on_hand"),
                rs.getBigDecimal("value"),
                rs.getInt("places"),
                rs.getInt("places_counted"),
                rs.getBigDecimal("reorder_level"),
                KigaliTime.read(rs, "last_movement_at"));
    }

    private Movement movement(ResultSet rs, int rowNum) throws SQLException {
        return new Movement(
                rs.getLong("id"),
                rs.getObject("business_date", LocalDate.class),
                KigaliTime.read(rs, "movement_at"),
                rs.getString("movement_type"),
                rs.getString("direction"),
                rs.getObject("doc_id", UUID.class),
                rs.getString("serial_no"),
                rs.getString("doc_type"),
                rs.getString("ticket_serial"),
                rs.getObject("item_id", UUID.class),
                rs.getString("item_code"),
                rs.getString("description"),
                rs.getString("uom"),
                rs.getString("location_code"),
                rs.getString("bin_code"),
                rs.getBigDecimal("signed_quantity"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("value"),
                rs.getBigDecimal("running_balance"),
                rs.getString("posted_by"),
                rs.getObject("reverses_movement_id", Long.class));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** A contains-pattern for ILIKE, with the reader's own % and _ taken literally. */
    private static String like(String text) {
        String t = blankToNull(text);
        if (t == null) return null;
        return "%" + t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
