package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountLookupService.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the count screens choose from, the cut-off list, and the variance report
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.BinOption;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.ItemOption;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.LocationOption;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reads for the count screens, each for one branch, the one the user is working
 * in. None of them reads a book quantity for a count still being counted or
 * verified: the variance report reads only counts whose verification is signed
 * ({@code count_book_visible}), and the pickers read no quantities at all.
 */
@Service
@Transactional(readOnly = true)
public class CountLookupService {

    /** A document not yet posted whose goods are at, or bound for, the counted location. */
    public record InFlight(UUID id, String serialNo, String typeName, String status, String how) {}

    /** A line whose count differed from the book, once the verification has been signed. */
    public record VarianceRow(UUID countId, String serialNo, String status, LocalDate documentDate,
                              String locationCode, int lineNo, String itemCode, String itemDescription,
                              String baseUomCode, String binCode, BigDecimal bookQty, BigDecimal countedQty,
                              BigDecimal verifiedQty, BigDecimal finalQty, BigDecimal varianceQty,
                              BigDecimal value, boolean valuePosted, LocalDateTime postedAt) {

        public boolean recountDiffers() {
            return countedQty != null && verifiedQty != null && countedQty.compareTo(verifiedQty) != 0;
        }
    }

    private final JdbcClient jdbc;

    public CountLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Where stock is kept at the branch, so where it is counted: not transit, not a van. */
    @PreAuthorize("hasAuthority('count.view')")
    public List<LocationOption> countableLocations(UUID branchId) {
        return jdbc.sql("""
                SELECT l.id, l.code, l.name, l.is_bonded FROM location l
                 WHERE l.branch_id = :branch AND l.is_active
                   AND l.location_type IN ('WAREHOUSE', 'BONDED', 'QUARANTINE', 'CUTTING')
                 ORDER BY l.code
                """)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new LocationOption(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getBoolean("is_bonded")))
                .list();
    }

    /** Whether a location at this branch, or the branch itself, is bonded: a count of it needs a customs reference. */
    @PreAuthorize("hasAuthority('count.view')")
    public boolean bonded(UUID branchId, UUID locationId) {
        return jdbc.sql("""
                SELECT b.is_bonded OR COALESCE((SELECT l.is_bonded FROM location l
                                                 WHERE l.id = :location AND l.branch_id = b.id), FALSE)
                  FROM branch b WHERE b.id = :branch
                """)
                .param("branch", branchId, Types.OTHER)
                .param("location", locationId, Types.OTHER)
                .query(Boolean.class).optional().orElse(false);
    }

    /** Every item that may be counted, active or not: stock of a deactivated item is still counted. */
    @PreAuthorize("hasAuthority('count.view')")
    public List<ItemOption> items() {
        return jdbc.sql("""
                SELECT i.id, i.item_code, i.description, i.product_type, u.code AS base_uom
                  FROM item i JOIN uom u ON u.id = i.base_uom_id
                 ORDER BY i.is_active DESC, i.item_code
                """)
                .query((rs, n) -> new ItemOption(rs.getObject("id", UUID.class), rs.getString("item_code"),
                        rs.getString("description"), rs.getString("product_type"), rs.getString("base_uom")))
                .list();
    }

    /** The bins of a location at this branch; one elsewhere yields nothing. */
    @PreAuthorize("hasAuthority('count.view')")
    public List<BinOption> binsOf(UUID locationId, UUID branchId) {
        if (locationId == null) return List.of();
        return jdbc.sql("""
                SELECT b.id, b.location_id, b.bin_code, b.zone FROM storage_bin b
                  JOIN location l ON l.id = b.location_id
                 WHERE b.location_id = :location AND l.branch_id = :branch
                 ORDER BY b.bin_code
                """)
                .param("location", locationId, Types.OTHER)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new BinOption(rs.getObject("id", UUID.class),
                        rs.getObject("location_id", UUID.class), rs.getString("bin_code"), rs.getString("zone")))
                .list();
    }

    /**
     * The cut-off list: documents not yet posted whose goods are at the location, or on their way into or out of
     * it. Their stock is not on the book yet (or still is), so the goods are kept apart and left out of the count;
     * counted, they would be adjusted once by the count and again when the document posts. A list for the
     * counters, not a refusal: the ledger itself refuses their posting while the count is open.
     */
    @PreAuthorize("hasAuthority('count.view')")
    public List<InFlight> inFlight(UUID locationId) {
        return jdbc.sql("""
                SELECT d.id, d.serial_no, dt.name AS type_name, d.status, x.how
                  FROM (
                        SELECT g.document_id AS id, 'received into it, not yet on the book' AS how
                          FROM goods_received_note g WHERE g.location_id = :location
                        UNION ALL
                        SELECT a.document_id, 'authorized to leave it, not yet loaded'
                          FROM delivery_authorization a WHERE a.location_id = :location
                        UNION ALL
                        SELECT n.document_id, 'loaded from it, not yet through the gate'
                          FROM delivery_note n JOIN delivery_note_authority na ON na.note_id = n.document_id
                         WHERE na.location_id = :location
                        UNION ALL
                        SELECT c.document_id, 'to be cut at it'
                          FROM cutting_order c WHERE c.location_id = :location
                        UNION ALL
                        SELECT t.document_id, 'to be dispatched from it'
                          FROM transfer_order t WHERE t.from_location_id = :location
                        UNION ALL
                        SELECT r.document_id, 'received into it, not yet on the book'
                          FROM transfer_receipt r JOIN transfer_order t ON t.document_id = r.transfer_id
                         WHERE t.to_location_id = :location
                        UNION ALL
                        SELECT r.document_id, CASE WHEN r.from_location_id = :location
                                                   THEN 'to be written off or released from it'
                                                   ELSE 'to be brought into it' END
                          FROM damage_report r WHERE :location IN (r.from_location_id, r.to_location_id)
                       ) x
                  JOIN document d       ON d.id = x.id
                  JOIN document_type dt ON dt.id = d.document_type_id
                 WHERE d.status IN ('DRAFT', 'PENDING', 'APPROVED')
                 ORDER BY d.created_at
                """)
                .param("location", locationId, Types.OTHER)
                .query((rs, n) -> new InFlight(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getString("type_name"), rs.getString("status"), rs.getString("how")))
                .list();
    }

    /**
     * The variance report: every line that differed from the book on a count at this branch whose verification is
     * signed, newest first. Posted lines carry the value the ledger moved; others the value they will move (a
     * surplus) or are estimated at (a shortage, at the book's average).
     */
    @PreAuthorize("hasAuthority('count.view')")
    public List<VarianceRow> variances(UUID branchId, String state) {
        String filter = switch (state == null ? "" : state) {
            case "posted" -> "d.status = 'POSTED'";
            case "all"    -> "d.status IN ('PENDING', 'APPROVED', 'POSTED', 'REJECTED', 'CANCELLED')";
            default       -> "d.status IN ('PENDING', 'APPROVED')";
        };
        return jdbc.sql("""
                SELECT d.id, d.serial_no, d.status, d.document_date, lo.code AS location_code, l.line_no,
                       i.item_code, i.description, u.code AS base_uom, sb.bin_code,
                       l.book_qty, l.counted_qty, l.verified_qty, l.final_qty, l.variance_qty,
                       (SELECT CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END
                          FROM transaction_ticket t
                          JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = l.line_no
                          JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                         WHERE t.source_document_id = d.id AND t.movement_type = 'ADJUSTMENT') AS posted_value,
                       CASE WHEN l.variance_qty > 0 THEN round(l.variance_qty * l.unit_cost, 2)
                            WHEN l.book_qty > 0 THEN round(l.variance_qty * l.book_value / l.book_qty, 2)
                            ELSE round(l.variance_qty * l.unit_cost, 2) END AS estimate,
                       d.posted_at
                  FROM stock_count_line l
                  JOIN document d     ON d.id = l.document_id
                  JOIN stock_count c  ON c.document_id = d.id
                  JOIN location lo    ON lo.id = c.location_id
                  JOIN item i         ON i.id = l.item_id
                  JOIN uom u          ON u.id = i.base_uom_id
             LEFT JOIN storage_bin sb ON sb.id = l.storage_bin_id
                 WHERE d.branch_id = :branch AND l.variance_qty <> 0 AND count_book_visible(d.id)
                   AND %s
                 ORDER BY d.created_at DESC, l.line_no
                 LIMIT 500
                """.formatted(filter))
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> {
                    BigDecimal posted = rs.getBigDecimal("posted_value");
                    return new VarianceRow(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                            rs.getString("status"), rs.getObject("document_date", LocalDate.class),
                            rs.getString("location_code"), rs.getInt("line_no"), rs.getString("item_code"),
                            rs.getString("description"), rs.getString("base_uom"), rs.getString("bin_code"),
                            rs.getBigDecimal("book_qty"), rs.getBigDecimal("counted_qty"),
                            rs.getBigDecimal("verified_qty"), rs.getBigDecimal("final_qty"),
                            rs.getBigDecimal("variance_qty"), posted != null ? posted : rs.getBigDecimal("estimate"),
                            posted != null, KigaliTime.read(rs, "posted_at"));
                })
                .list();
    }
}
