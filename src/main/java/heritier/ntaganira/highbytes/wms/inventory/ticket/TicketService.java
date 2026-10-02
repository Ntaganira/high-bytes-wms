package heritier.ntaganira.highbytes.wms.inventory.ticket;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.ticket
 * - File       : TicketService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Transaction tickets read: the register at a branch, and one ticket with its lines and movements
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.document.ChainStep;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
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
 * Transaction tickets (form F-05), read-only. A ticket is never raised by hand: each stock-moving document writes
 * its own when it is posted (V11 to V15), one per direction, and the ledger's movements answer to it. This is
 * where a ticket is read as itself: what moved, from where to where, who posted it, the document that authorised
 * it and that document's signatures, so the people who authorised, executed and recorded one transaction are on
 * one page.
 *
 * <p>A place under a live count shows no book here (V15), as on the stock screens: a line or movement there
 * shows no quantity, value or balance, and the register's value leaves it out, never subtracts it. The register
 * lists every ticket of every type, so read without that, its tickets would add up to the counted place's book.
 * Which places are counted is the count's own (count_freezing), never the book's.
 */
@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('ticket.view')")
public class TicketService {

    /**
     * One ticket in the register. {@code value} is the sum of its movements' values, the ledger's, left out where
     * a place is under a live count ({@code countedMovements} of them).
     */
    public record TicketRow(UUID id, String serialNo, String status, LocalDate documentDate, LocalDateTime postedAt,
                            String postedByName, TicketMovement movement, String direction, String fromCode,
                            String toCode, UUID sourceId, String sourceSerial, String sourceTypeName, int lineCount,
                            BigDecimal value, int countedMovements) {}

    /** The register's filters; {@code before} is the last ticket of the page already shown. */
    public record TicketQuery(LocalDate from, LocalDate to, TicketMovement movement, String direction, String text,
                              UUID before) {

        public static TicketQuery all() {
            return new TicketQuery(null, null, null, null, null, null);
        }
    }

    public record TicketHeader(UUID id, String serialNo, String status, UUID branchId, String branchName,
                               LocalDate documentDate, String raisedByName, LocalDateTime postedAt,
                               String postedByName, TicketMovement movement, String direction, String fromCode,
                               String fromName, String toCode, String toName, String customsReference, UUID sourceId,
                               String sourceSerial, String sourceStatus, String sourceTypeName) {}

    /** A line of the ticket. Its quantities are null while its place is under a live count. */
    public record TicketLine(int lineNo, UUID itemId, String itemCode, String description, BigDecimal quantity,
                             String uomCode, BigDecimal quantityBase, String baseUomCode, String binCode,
                             boolean counted) {}

    /** A ledger row the ticket wrote. Quantity, cost, value and balance are null while its place is counted. */
    public record Movement(long id, int lineNo, String itemCode, String locationCode, String binCode,
                           String direction, BigDecimal quantityBase, BigDecimal unitCost, BigDecimal value,
                           BigDecimal runningBalance, LocalDate businessDate, Long reversesId, boolean counted) {}

    public record TicketDetail(TicketHeader header, List<TicketLine> lines, List<Movement> movements,
                               List<ChainStep> sourceChain) {

        /** The value of what may be read: movements at a place under a live count are left out, never subtracted. */
        public BigDecimal value() {
            return movements.stream().filter(m -> !m.counted()).map(Movement::value)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public long countedMovements() {
            return movements.stream().filter(Movement::counted).count();
        }
    }

    private static final String LIST = """
            SELECT d.id, d.serial_no, d.status, d.document_date, d.posted_at, pu.full_name AS posted_by_name,
                   t.movement_type, t.direction, fl.code AS from_code, tl.code AS to_code,
                   s.id AS source_id, s.serial_no AS source_serial, sdt.name AS source_type_name,
                   (SELECT COUNT(*) FROM ticket_line x WHERE x.ticket_id = d.id)                    AS line_count,
                   -- Left out, never subtracted: a total must not give a blind count's book away (V15).
                   (SELECT COALESCE(SUM(m.value), 0) FROM stock_movement m
                     WHERE m.document_id = d.id AND count_freezing(m.item_id, m.location_id) IS NULL) AS value,
                   (SELECT COUNT(*) FROM stock_movement m
                     WHERE m.document_id = d.id AND count_freezing(m.item_id, m.location_id) IS NOT NULL) AS counted
              FROM transaction_ticket t
              JOIN document d             ON d.id = t.document_id
         LEFT JOIN app_user pu            ON pu.id = d.posted_by
         LEFT JOIN location fl            ON fl.id = t.from_location_id
         LEFT JOIN location tl            ON tl.id = t.to_location_id
         LEFT JOIN document s             ON s.id = t.source_document_id
         LEFT JOIN document_type sdt      ON sdt.id = s.document_type_id
             WHERE d.branch_id = :branch
               AND (:movement::text IS NULL OR t.movement_type = :movement::text)
               AND (:direction::text IS NULL OR t.direction = :direction::text)
               AND (:from::date IS NULL OR d.document_date >= :from::date)
               AND (:to::date IS NULL OR d.document_date <= :to::date)
               AND (:text::text IS NULL OR d.serial_no ILIKE :text::text ESCAPE '\\'
                                        OR s.serial_no ILIKE :text::text ESCAPE '\\'
                                        OR t.customs_reference ILIKE :text::text ESCAPE '\\')
               AND (:before::uuid IS NULL
                    OR (d.created_at, d.id) < (SELECT b.created_at, b.id FROM document b WHERE b.id = :before::uuid))
             ORDER BY d.created_at DESC, d.id DESC
             LIMIT :limit
            """;

    private static final String HEADER = """
            SELECT d.id, d.serial_no, d.status, d.branch_id, b.name AS branch_name, d.document_date,
                   cu.full_name AS raised_by_name, d.posted_at, pu.full_name AS posted_by_name,
                   t.movement_type, t.direction, t.customs_reference,
                   fl.code AS from_code, fl.name AS from_name, tl.code AS to_code, tl.name AS to_name,
                   s.id AS source_id, s.serial_no AS source_serial, s.status AS source_status,
                   sdt.name AS source_type_name
              FROM transaction_ticket t
              JOIN document d             ON d.id = t.document_id
              JOIN branch b               ON b.id = d.branch_id
              JOIN app_user cu            ON cu.id = d.created_by
         LEFT JOIN app_user pu            ON pu.id = d.posted_by
         LEFT JOIN location fl            ON fl.id = t.from_location_id
         LEFT JOIN location tl            ON tl.id = t.to_location_id
         LEFT JOIN document s             ON s.id = t.source_document_id
         LEFT JOIN document_type sdt      ON sdt.id = s.document_type_id
             WHERE t.document_id = :id
            """;

    // A ticket moves stock one way, at one place: out of its from-location, or into its to-location. The line is
    // also counted when any movement it wrote is, so it can never show what its movement hides.
    private static final String LINES = """
            SELECT l.line_no, l.item_id, i.item_code, i.description, u.code AS uom_code, bu.code AS base_uom_code,
                   sb.bin_code, f.counted,
                   CASE WHEN NOT f.counted THEN l.quantity END     AS quantity,
                   CASE WHEN NOT f.counted THEN l.qty_base_uom END AS qty_base_uom
              FROM ticket_line l
              JOIN transaction_ticket t ON t.document_id = l.ticket_id
              JOIN LATERAL (SELECT count_freezing(l.item_id, CASE t.direction WHEN 'IN' THEN t.to_location_id
                                                                              ELSE t.from_location_id END) IS NOT NULL
                                   OR EXISTS (SELECT 1 FROM stock_movement m
                                               WHERE m.ticket_line_id = l.id
                                                 AND count_freezing(m.item_id, m.location_id) IS NOT NULL)
                                   AS counted) f ON TRUE
              JOIN item i              ON i.id = l.item_id
              JOIN uom u               ON u.id = l.uom_id
              JOIN uom bu              ON bu.id = i.base_uom_id
         LEFT JOIN storage_bin sb      ON sb.id = l.storage_bin_id
             WHERE l.ticket_id = :id
             ORDER BY l.line_no
            """;

    private static final String MOVEMENTS = """
            SELECT m.id, tl.line_no, i.item_code, l.code AS location_code, sb.bin_code, m.direction,
                   m.business_date, m.reverses_movement_id, f.counted,
                   -- No quantity, value or balance of a place under a live count (V15).
                   CASE WHEN NOT f.counted THEN m.quantity_base_uom END AS quantity_base_uom,
                   CASE WHEN NOT f.counted THEN m.unit_cost END         AS unit_cost,
                   CASE WHEN NOT f.counted THEN m.value END             AS value,
                   CASE WHEN NOT f.counted THEN m.running_balance END   AS running_balance
              FROM stock_movement m
              JOIN LATERAL (SELECT count_freezing(m.item_id, m.location_id) IS NOT NULL AS counted) f ON TRUE
              JOIN item i              ON i.id = m.item_id
              JOIN location l          ON l.id = m.location_id
         LEFT JOIN ticket_line tl      ON tl.id = m.ticket_line_id
         LEFT JOIN storage_bin sb      ON sb.id = m.storage_bin_id
             WHERE m.document_id = :id
             ORDER BY tl.line_no NULLS LAST, m.id
            """;

    private final JdbcClient jdbc;
    private final DocumentService documents;

    public TicketService(JdbcClient jdbc, DocumentService documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    /** The tickets of a branch, newest first. */
    public List<TicketRow> list(UUID branchId, TicketQuery q, int limit) {
        return jdbc.sql(LIST)
                .param("branch", branchId, Types.OTHER)
                .param("movement", q.movement() == null ? null : q.movement().name(), Types.VARCHAR)
                .param("direction", "IN".equals(q.direction()) || "OUT".equals(q.direction()) ? q.direction() : null,
                        Types.VARCHAR)
                .param("from", q.from(), Types.DATE)
                .param("to", q.to(), Types.DATE)
                .param("text", like(q.text()), Types.VARCHAR)
                .param("before", q.before(), Types.OTHER)
                .param("limit", Math.max(1, limit))
                .query((rs, n) -> new TicketRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("status"),
                        rs.getObject("document_date", LocalDate.class),
                        KigaliTime.read(rs, "posted_at"),
                        rs.getString("posted_by_name"),
                        TicketMovement.of(rs.getString("movement_type")),
                        rs.getString("direction"),
                        rs.getString("from_code"),
                        rs.getString("to_code"),
                        rs.getObject("source_id", UUID.class),
                        rs.getString("source_serial"),
                        rs.getString("source_type_name"),
                        rs.getInt("line_count"),
                        rs.getBigDecimal("value"),
                        rs.getInt("counted")))
                .list();
    }

    /**
     * One ticket, read at its own branch: the right is checked there, whichever branch the reader works in. Empty
     * when no ticket has that id.
     */
    public Optional<TicketDetail> find(UUID id) {
        Optional<TicketHeader> header = jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional();
        if (header.isEmpty()) return Optional.empty();
        CurrentUser.requireAt("ticket.view", header.get().branchId());   // a read: refused, not recorded

        List<TicketLine> lines = jdbc.sql(LINES).param("id", id, Types.OTHER)
                .query((rs, n) -> new TicketLine(
                        rs.getInt("line_no"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getBigDecimal("quantity"),
                        rs.getString("uom_code"),
                        rs.getBigDecimal("qty_base_uom"),
                        rs.getString("base_uom_code"),
                        rs.getString("bin_code"),
                        rs.getBoolean("counted")))
                .list();
        List<Movement> movements = jdbc.sql(MOVEMENTS).param("id", id, Types.OTHER)
                .query((rs, n) -> new Movement(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getString("item_code"),
                        rs.getString("location_code"),
                        rs.getString("bin_code"),
                        rs.getString("direction"),
                        rs.getBigDecimal("quantity_base_uom"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("value"),
                        rs.getBigDecimal("running_balance"),
                        rs.getObject("business_date", LocalDate.class),
                        rs.getObject("reverses_movement_id", Long.class),
                        rs.getBoolean("counted")))
                .list();
        UUID source = header.get().sourceId();
        List<ChainStep> chain = source == null ? List.of() : documents.chain(source);
        return Optional.of(new TicketDetail(header.get(), lines, movements, chain));
    }

    private TicketHeader mapHeader(ResultSet rs, int n) throws SQLException {
        return new TicketHeader(
                rs.getObject("id", UUID.class),
                rs.getString("serial_no"),
                rs.getString("status"),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"),
                rs.getObject("document_date", LocalDate.class),
                rs.getString("raised_by_name"),
                KigaliTime.read(rs, "posted_at"),
                rs.getString("posted_by_name"),
                TicketMovement.of(rs.getString("movement_type")),
                rs.getString("direction"),
                rs.getString("from_code"),
                rs.getString("from_name"),
                rs.getString("to_code"),
                rs.getString("to_name"),
                rs.getString("customs_reference"),
                rs.getObject("source_id", UUID.class),
                rs.getString("source_serial"),
                rs.getString("source_status"),
                rs.getString("source_type_name"));
    }

    private static String like(String text) {
        if (text == null || text.isBlank()) return null;
        return "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
