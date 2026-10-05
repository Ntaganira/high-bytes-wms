package heritier.ntaganira.highbytes.wms.inventory.reversal;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.reversal
 * - File       : ReversalService.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Reversing documents: a posted document undone whole, by the exact mirror of every movement it made
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.common.db.ContentionException;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.document.ChainInfo;
import heritier.ntaganira.highbytes.wms.document.ChainStep;
import heritier.ntaganira.highbytes.wms.document.DocumentHeader;
import heritier.ntaganira.highbytes.wms.document.DocumentKind;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.document.OpenedDocument;
import heritier.ntaganira.highbytes.wms.document.StepCheck;
import heritier.ntaganira.highbytes.wms.inventory.ledger.LedgerService;
import heritier.ntaganira.highbytes.wms.inventory.ledger.MovementRequest;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Undoing a posted document. The ledger is append-only and a posted document
 * that moved stock is never cancelled (invariants 1 and 4), so a mistake is
 * corrected beside the original, never in it: a reversing document records
 * the opposite of every movement the original made, and both stay on record.
 *
 * <p>Nothing about what moves is entered. The reversal names the posted
 * document and says why; what it moves is every movement of that document,
 * mirrored: the same item, place, bin, quantity and value, the other way. So
 * a reversed receipt leaves at the value it came in at, whatever the average
 * has done since, and is refused when the place no longer holds that stock or
 * that value.
 *
 * <p>V21 holds all of it in the database: the mirror, the one live reversal
 * per document, the original's raiser and poster kept out, the types this
 * version reverses, and "every movement or none" at commit. This class asks
 * the same questions first so the person reads the reason before raising or
 * posting, not after; the database remains the authority.
 */
@Service
@Transactional(readOnly = true)
public class ReversalService {

    private static final String HEADER = """
            SELECT d.id, d.branch_id, b.name AS branch_name, b.is_bonded AS branch_bonded,
                   d.serial_no, d.status, d.document_date, d.version,
                   d.created_by, cu.full_name AS created_by_name, d.created_at,
                   d.posted_at, d.posted_by, pu.full_name AS posted_by_name, d.cancel_reason,
                   r.reason,
                   o.id AS original_id, o.serial_no AS original_serial, ot.code AS original_type,
                   ot.name AS original_title, dr.kind AS original_kind, o.posted_at AS original_posted_at,
                   o.created_by AS original_created_by, ocu.full_name AS original_created_by_name,
                   o.posted_by AS original_posted_by, opu.full_name AS original_posted_by_name
              FROM document d
              JOIN document_type dt  ON dt.id = d.document_type_id AND dt.code = 'REV'
              JOIN branch b          ON b.id = d.branch_id
              JOIN document o        ON o.id = d.reverses_document_id
              JOIN document_type ot  ON ot.id = o.document_type_id
         LEFT JOIN damage_report dr  ON dr.document_id = o.id
         LEFT JOIN reversal r        ON r.document_id = d.id
         LEFT JOIN app_user cu       ON cu.id = d.created_by
         LEFT JOIN app_user pu       ON pu.id = d.posted_by
         LEFT JOIN app_user ocu      ON ocu.id = o.created_by
         LEFT JOIN app_user opu      ON opu.id = o.posted_by
             WHERE d.id = :id
            """;

    /** Every movement the original made, with its mirror once there is one. */
    private static final String MOVEMENTS = """
            SELECT m.id AS movement_id, t.document_id AS ticket_id, td.serial_no AS ticket_serial, t.movement_type,
                   tl.id AS ticket_line_id, tl.line_no, m.item_id, i.item_code, i.description,
                   bu.code AS base_uom_code, m.location_id, l.code AS location_code,
                   m.storage_bin_id, sb.bin_code, m.direction, m.quantity_base_uom, m.value,
                   mr.id AS mirror_id, count_freezing(m.item_id, m.location_id) AS counted_by
              FROM transaction_ticket t
              JOIN document td          ON td.id = t.document_id AND td.status = 'POSTED'
              JOIN stock_movement m     ON m.document_id = t.document_id AND m.reverses_movement_id IS NULL
              JOIN ticket_line tl       ON tl.id = m.ticket_line_id
              JOIN item i               ON i.id = m.item_id
              JOIN uom bu               ON bu.id = i.base_uom_id
              JOIN location l           ON l.id = m.location_id
              LEFT JOIN storage_bin sb  ON sb.id = m.storage_bin_id
              LEFT JOIN stock_movement mr ON mr.reverses_movement_id = m.id
             WHERE t.source_document_id = :original
             ORDER BY td.serial_no, tl.line_no, m.id
            """;

    private static final String LIST = """
            SELECT d.id, d.serial_no, d.status, d.document_date,
                   o.serial_no AS original_serial, ot.name AS original_title,
                   (SELECT COUNT(*) FROM transaction_ticket t
                      JOIN stock_movement m ON m.document_id = t.document_id
                     WHERE t.source_document_id = o.id AND m.reverses_movement_id IS NULL) AS movement_count,
                   (SELECT COALESCE(SUM(m.value), 0) FROM transaction_ticket t
                      JOIN stock_movement m ON m.document_id = t.document_id
                     WHERE t.source_document_id = o.id AND m.reverses_movement_id IS NULL) AS value_rwf,
                   cu.full_name AS created_by_name,
                   (SELECT r.name
                      FROM workflow_step ws
                      JOIN role r ON r.id = ws.required_role_id
                     WHERE ws.workflow_definition_id = d.workflow_definition_id
                       AND d.status = 'PENDING'
                       AND NOT EXISTS (SELECT 1 FROM document_approval da
                                        WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                     ORDER BY ws.sequence_no
                     LIMIT 1) AS awaiting_role
              FROM document d
              JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'REV'
              JOIN document o       ON o.id = d.reverses_document_id
              JOIN document_type ot ON ot.id = o.document_type_id
              LEFT JOIN app_user cu ON cu.id = d.created_by
             WHERE d.branch_id = :branchId
               AND (:status::text IS NULL OR d.status = :status)
             ORDER BY d.document_date DESC, d.serial_no DESC
            """;

    /** The document a reversal would undo, as the form and the checks read it. */
    public record Original(UUID id, String serialNo, String typeCode, String title, String kind, String status,
                           UUID branchId, String branchName, LocalDateTime postedAt,
                           UUID createdBy, String createdByName, UUID postedBy, String postedByName) {}

    /** What the new form shows: the document, every movement it made, and why it cannot be reversed if not. */
    public record Preview(Original original, List<ReversalLine> lines, String obstacle) {

        public BigDecimal valueRwf() {
            return lines.stream().map(ReversalLine::valueRwf).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public boolean anyProblem() {
            return lines.stream().anyMatch(l -> l.problem() != null);
        }
    }

    /** Everything one reversal's page needs, in one read. */
    public record Detail(ReversalHeader header, List<ReversalLine> lines, List<ChainStep> chain,
                         ChainInfo chainInfo, ReversalActions actions) {

        public BigDecimal valueRwf() {
            return lines.stream().map(ReversalLine::valueRwf).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final LedgerService ledger;
    private final BranchService branches;

    public ReversalService(JdbcClient jdbc, DocumentService documents, LedgerService ledger,
                           BranchService branches) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.ledger = ledger;
        this.branches = branches;
    }

    // ---- reads -----------------------------------------------------------

    @PreAuthorize("hasAuthority('reversal.view')")
    public List<ReversalRow> list(UUID branchId, String status, boolean awaitingMe) {
        List<ReversalRow> rows = jdbc.sql(LIST)
                .param("branchId", branchId, Types.OTHER)
                .param("status", status)
                .query((rs, n) -> new ReversalRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("status"),
                        rs.getObject("document_date", LocalDate.class),
                        rs.getString("original_serial"),
                        rs.getString("original_title"),
                        rs.getInt("movement_count"),
                        rs.getBigDecimal("value_rwf"),
                        rs.getString("created_by_name"),
                        rs.getString("awaiting_role"),
                        false))
                .list();

        Set<UUID> mine = documents.awaitingSignatureOf(DocumentKind.REV, branchId, CurrentUser.id());
        List<ReversalRow> marked = rows.stream().map(r -> r.markAwaitingMe(mine.contains(r.id()))).toList();
        return awaitingMe ? marked.stream().filter(ReversalRow::awaitingMe).toList() : marked;
    }

    /** The reversal's header; a 404 for none, a 403 unless the viewer may read reversals at its branch. */
    @PreAuthorize("hasAuthority('reversal.view')")
    public ReversalHeader find(UUID id) {
        ReversalHeader header = requireHeader(id);
        CurrentUser.requireAt("reversal.view", header.branchId());
        return header;
    }

    @PreAuthorize("hasAuthority('reversal.view')")
    public Detail detail(UUID id) {
        ReversalHeader header = find(id);
        DocumentHeader d = documents.header(id);
        List<ChainStep> chain = documents.chain(id);
        // Once posted, every line carries its mirror and there is nothing left to judge.
        List<ReversalLine> lines = movements(header.originalId(), !"POSTED".equals(header.status()));
        return new Detail(header, lines, chain, documents.chainInfo(id).orElse(null),
                actionsFor(header, d, chain));
    }

    /**
     * What reversing a document would do, for the form before anything is
     * raised: every movement, whether each place can take its mirror today,
     * and the reason it cannot be reversed at all, if there is one.
     */
    @PreAuthorize("hasAuthority('reversal.create')")
    public Preview preview(UUID originalId) {
        Original o = original(originalId);
        CurrentUser.requireAt("reversal.create", o.branchId());   // a read: refused, not recorded
        return new Preview(o, movements(originalId, true), obstacleTo(o));
    }

    // ---- writes ----------------------------------------------------------

    /**
     * Raises a draft against a posted document. The reversal takes the
     * original's branch, and is dated, and its chain bound, by the database,
     * which judges the pairing again (V21).
     */
    @Transactional
    @PreAuthorize("hasAuthority('reversal.create')")
    public UUID create(ReversalForm form) {
        Original o = original(form.getOriginalId());
        documents.requireRightAt(DocumentKind.REV, o.branchId(), "reversal.create", "Raise a reversal");
        String obstacle = obstacleTo(o);
        if (obstacle != null) {
            throw new ControlRefusedException(obstacle);
        }
        try {
            OpenedDocument opened = documents.open(DocumentKind.REV, o.branchId(), null, null, null, o.id());
            jdbc.sql("INSERT INTO reversal (document_id, reason) VALUES (:id, :reason)")
                    .param("id", opened.id(), Types.OTHER)
                    .param("reason", form.getReason())
                    .update();

            List<ReversalLine> lines = movements(o.id(), false);
            documents.auditInTransaction("REV · " + opened.serialNo(), opened.id(), AuditAction.CREATE,
                    AuditSnapshot.of()
                            .field("Reverses", null, o.serialNo() + " (" + o.title() + ")")
                            .field("Reason", null, form.getReason())
                            .value("Movements to undo", lines.size())
                            .value("Value (RWF)", lines.stream().map(ReversalLine::valueRwf)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)),
                    branches.findById(o.branchId()).orElse(null));
            return opened.id();
        } catch (DataAccessException e) {
            if (DbRefusal.constraint(e).filter("document_one_live_reversal"::equals).isPresent()) {
                throw new ControlRefusedException(o.serialNo() + " is already being reversed by "
                        + documents.reversedBy(o.id()).map(r -> r.serialNo()).orElse("another reversal")
                        + ". A document is reversed once; cancel that reversal first to raise another.");
            }
            throw DbRefusal.asRefusal(e);
        }
    }

    /** Edits the reason of a draft, provided nobody changed it since the form was opened. */
    @Transactional
    @PreAuthorize("hasAuthority('reversal.create')")
    public void update(UUID id, ReversalForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "reversal.create", "Edit");
        if (form.getVersion() == null) {
            throw documents.refused(d, "Edit", "This form did not say which version of " + d.serialNo()
                    + " it was opened from. Reload the reversal and try again.");
        }
        ReversalHeader before = requireHeader(id);
        // V21 refuses the original's raiser and poster as raiser, signer, poster and canceller; an edit records no
        // actor in the database, so this is where it is refused.
        String hands = originalHands(before);
        if (hands != null) {
            throw documents.refused(d, "Edit", hands);
        }
        if (form.getOriginalId() != null && !form.getOriginalId().equals(before.originalId())) {
            throw documents.refused(d, "Edit", d.serialNo() + " reverses " + before.originalSerial()
                    + ". To reverse another document, cancel this draft and raise a new one.");
        }
        try {
            documents.updateDraft(d, form.getVersion(), null, null);
            jdbc.sql("UPDATE reversal SET reason = :reason WHERE document_id = :id")
                    .param("id", id, Types.OTHER)
                    .param("reason", form.getReason())
                    .update();
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Edit", e);
        }
        AuditSnapshot snapshot = AuditSnapshot.of().field("Reason", before.reason(), form.getReason());
        if (!snapshot.unchanged()) {
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, snapshot, d.branch());
        }
    }

    /** Submits the draft; the submitter signs step 1. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('reversal.create','reversal.verify','reversal.approve')")
    public String submit(UUID id) {
        return documents.submit(id).status();
    }

    /** Approves or rejects the next step of the chain. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('reversal.create','reversal.verify','reversal.approve')")
    public String sign(UUID id, boolean approve, String comment) {
        return documents.sign(id, approve, comment).status();
    }

    @Transactional
    @PreAuthorize("hasAuthority('reversal.create')")
    public void cancel(UUID id, String reason) {
        documents.cancel(id, reason);
    }

    /**
     * Posts an approved reversal, in one transaction: one mirror ticket for
     * each ticket of the original, line for line, and one mirror movement for
     * each movement it made. Returns the serials of the tickets it raised.
     *
     * <p>What comes back in is written before what goes out, so a place that
     * takes both (a count that found some lines short and others over) never
     * dips below zero on the way. Nothing here decides whether the posting is
     * allowed: the ledger and V21 judge that, and their refusals are shown to
     * the user as their own reason.
     */
    @Transactional
    @PreAuthorize("hasAuthority('reversal.post')")
    public String post(UUID id) {
        DocumentHeader d = documents.beginPost(id);      // locks, APPROVED -> POSTED by the poster
        ReversalHeader r = requireHeader(id);
        List<ReversalLine> lines = movements(r.originalId(), false);
        UUID poster = CurrentUser.id();

        Map<UUID, UUID> mirrorTicket = new LinkedHashMap<>();            // original ticket -> its mirror
        Map<UUID, Map<Integer, UUID>> mirrorLine = new LinkedHashMap<>(); // original ticket -> line no -> mirror line
        List<String> serials = new ArrayList<>();
        BigDecimal valueIn = BigDecimal.ZERO;
        BigDecimal valueOut = BigDecimal.ZERO;
        try {
            // Every place this posting moves is locked up front, in one fixed order, whatever the line order.
            ledger.lockPlaces(lines.stream()
                    .map(l -> new LedgerService.Place(l.itemId(), l.locationId())).distinct().toList());

            for (UUID original : lines.stream().map(ReversalLine::ticketId).distinct().toList()) {
                OpenedDocument ticket = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);
                jdbc.sql("""
                        INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id,
                                                        to_location_id, source_document_id, customs_reference,
                                                        total_value, reverses_ticket_id)
                        SELECT :id, 'REVERSAL', CASE t.direction WHEN 'IN' THEN 'OUT' ELSE 'IN' END,
                               t.to_location_id, t.from_location_id, :source, t.customs_reference,
                               t.total_value, t.document_id
                          FROM transaction_ticket t
                         WHERE t.document_id = :original
                        """)
                        .param("id", ticket.id(), Types.OTHER)
                        .param("source", id, Types.OTHER)
                        .param("original", original, Types.OTHER)
                        .update();
                jdbc.sql("""
                        INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom,
                                                 unit_value, total_value, storage_bin_id)
                        SELECT :id, l.line_no, l.item_id, l.quantity, l.uom_id, l.qty_base_uom,
                               l.unit_value, l.total_value, l.storage_bin_id
                          FROM ticket_line l
                         WHERE l.ticket_id = :original
                         ORDER BY l.line_no
                        """)
                        .param("id", ticket.id(), Types.OTHER)
                        .param("original", original, Types.OTHER)
                        .update();
                Map<Integer, UUID> byLine = new LinkedHashMap<>();
                jdbc.sql("SELECT line_no, id FROM ticket_line WHERE ticket_id = :id")
                        .param("id", ticket.id(), Types.OTHER)
                        .query((rs, n) -> byLine.put(rs.getInt("line_no"), rs.getObject("id", UUID.class)))
                        .list();
                mirrorTicket.put(original, ticket.id());
                mirrorLine.put(original, byLine);
                serials.add(ticket.serialNo());
            }

            List<ReversalLine> ordered = lines.stream()
                    .sorted(Comparator.comparing((ReversalLine l) -> "IN".equals(l.mirrorDirection()) ? 0 : 1))
                    .toList();
            for (ReversalLine l : ordered) {
                MovementRequest.Direction direction = MovementRequest.Direction.valueOf(l.mirrorDirection());
                ledger.post(MovementRequest.mirror(mirrorTicket.get(l.ticketId()),
                        mirrorLine.get(l.ticketId()).get(l.lineNo()), d.branchId(), l.itemId(), l.locationId(),
                        l.storageBinId(), direction, l.quantityBase(), l.valueRwf(), l.movementId()), poster);
                if (direction == MovementRequest.Direction.IN) {
                    valueIn = valueIn.add(l.valueRwf());
                } else {
                    valueOut = valueOut.add(l.valueRwf());
                }
            }
        } catch (ContentionException e) {
            throw e;        // a lost race, not a refusal: logged by the engine, never audited as REJECT
        } catch (ControlRefusedException e) {
            throw documents.refused(d, "Post", e.getMessage());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Post", e);
        }

        for (UUID ticket : mirrorTicket.values()) {
            documents.postDerived(d, ticket);
        }

        documents.auditPosted(d, AuditSnapshot.of()
                .field("Status", "APPROVED", "POSTED")
                .value("Reverses", r.originalSerial())
                .value("Transaction tickets", String.join(", ", serials))
                .value("Ledger movements", lines.size())
                .value("Value back in (RWF)", valueIn)
                .value("Value back out (RWF)", valueOut));
        return String.join(", ", serials);
    }

    // ---- what the original did, and whether it can be undone today ----------

    /**
     * Every movement of the original. With {@code judge}, each place is asked
     * whether it can take its mirrors today: what goes back out must still be
     * there, in quantity and in value, and must not leave value behind with no
     * stock under it. A place under a live count is named and not read: the
     * book is not shown there (CLAUDE.md), and the count refuses the movement
     * anyway.
     */
    List<ReversalLine> movements(UUID originalId, boolean judge) {
        List<ReversalLine> lines = jdbc.sql(MOVEMENTS).param("original", originalId, Types.OTHER)
                .query((rs, n) -> new ReversalLine(
                        rs.getLong("movement_id"),
                        rs.getObject("ticket_id", UUID.class),
                        rs.getString("ticket_serial"),
                        rs.getString("movement_type"),
                        rs.getObject("ticket_line_id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getString("base_uom_code"),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("location_code"),
                        rs.getObject("storage_bin_id", UUID.class),
                        rs.getString("bin_code"),
                        rs.getString("direction"),
                        rs.getBigDecimal("quantity_base_uom"),
                        rs.getBigDecimal("value"),
                        (Long) rs.getObject("mirror_id", Long.class),
                        rs.getString("counted_by"),
                        null))
                .list();
        return judge ? judged(lines) : lines;
    }

    private List<ReversalLine> judged(List<ReversalLine> lines) {
        record Place(UUID item, UUID location, UUID bin) {}
        Map<Place, List<ReversalLine>> byPlace = lines.stream().collect(Collectors.groupingBy(
                l -> new Place(l.itemId(), l.locationId(), l.storageBinId()), LinkedHashMap::new, Collectors.toList()));

        Map<Long, String> problems = new LinkedHashMap<>();
        for (var entry : byPlace.entrySet()) {
            Place place = entry.getKey();
            List<ReversalLine> at = entry.getValue();
            ReversalLine first = at.get(0);
            String where = first.locationCode() + (first.binCode() == null ? "" : " bin " + first.binCode());

            String counted = at.stream().map(ReversalLine::countedBy).filter(Objects::nonNull).findFirst().orElse(null);
            if (counted != null) {
                String why = first.itemCode() + " at " + where + " is being counted (" + counted + "): nothing moves"
                        + " there until the count's verification is signed, or the count is cancelled.";
                at.forEach(l -> problems.put(l.movementId(), why));
                continue;
            }

            BigDecimal backIn = sum(at, "IN", true);
            BigDecimal backInValue = sum(at, "IN", false);
            BigDecimal goingOut = sum(at, "OUT", true);
            BigDecimal goingOutValue = sum(at, "OUT", false);
            if (goingOut.signum() == 0) continue;

            record Balance(BigDecimal quantity, BigDecimal value) {}
            Balance now = jdbc.sql("""
                    SELECT qty_on_hand, total_value FROM stock_balance
                     WHERE item_id = :item AND location_id = :location
                       AND storage_bin_id IS NOT DISTINCT FROM :bin::uuid
                    """)
                    .param("item", place.item(), Types.OTHER)
                    .param("location", place.location(), Types.OTHER)
                    .param("bin", place.bin(), Types.OTHER)
                    .query((rs, n) -> new Balance(rs.getBigDecimal("qty_on_hand"), rs.getBigDecimal("total_value")))
                    .optional().orElse(new Balance(BigDecimal.ZERO, BigDecimal.ZERO));
            BigDecimal quantity = now.quantity().add(backIn);
            BigDecimal value = now.value().add(backInValue);

            String why = null;
            if (quantity.compareTo(goingOut) < 0) {
                why = where + " holds " + plain(quantity) + " " + first.baseUomCode() + " of " + first.itemCode()
                        + " now; undoing this takes " + plain(goingOut) + ". Stock that arrived here has since"
                        + " left, so it cannot be taken off the books again.";
            } else if (value.compareTo(goingOutValue) < 0) {
                why = first.itemCode() + " at " + where + " carries " + plain(value) + " RWF now; undoing this takes "
                        + plain(goingOutValue) + " RWF at the value it came in at.";
            } else if (quantity.compareTo(goingOut) == 0 && value.compareTo(goingOutValue) != 0) {
                why = "Undoing this would empty " + first.itemCode() + " at " + where + " but leave "
                        + plain(value.subtract(goingOutValue)) + " RWF on the books with no stock under it: stock"
                        + " there has been issued at the average cost since.";
            }
            if (why != null) {
                String reason = why;
                at.stream().filter(l -> "OUT".equals(l.mirrorDirection()))
                        .forEach(l -> problems.put(l.movementId(), reason));
            }
        }
        return lines.stream().map(l -> problems.containsKey(l.movementId()) ? l.withProblem(problems.get(l.movementId())) : l)
                .toList();
    }

    private static BigDecimal sum(List<ReversalLine> lines, String mirrorDirection, boolean quantity) {
        return lines.stream().filter(l -> mirrorDirection.equals(l.mirrorDirection()))
                .map(l -> quantity ? l.quantityBase() : l.valueRwf())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    /**
     * Why a document cannot be reversed by the signed-in user, or null when it
     * can. V21's own questions, asked here so the form says so first.
     */
    String obstacleTo(Original o) {
        if (!"POSTED".equals(o.status())) {
            return o.serialNo() + " is " + o.status() + ". Only a posted document is reversed: one that has not"
                    + " posted has moved no stock, and is cancelled instead.";
        }
        if (!Set.of("GRN", "OPB", "CNT", "DMG").contains(o.typeCode())) {
            return o.serialNo() + " cannot be reversed yet. Reversing documents undo goods received notes, opening"
                    + " balances, count adjustments, write-offs and quarantine releases.";
        }
        if ("DMG".equals(o.typeCode()) && !Set.of("WRITE_OFF", "QUARANTINE_RELEASE").contains(o.kind())) {
            return o.serialNo() + " is a " + ("TRANSIT_LOSS".equals(o.kind()) ? "transit loss" : "customer return")
                    + ", which changes what its " + ("TRANSIT_LOSS".equals(o.kind()) ? "transfer" : "delivery note")
                    + " may still do, so its reversal is not built yet.";
        }
        var live = documents.reversedBy(o.id());
        if (live.isPresent()) {
            return o.serialNo() + " is already " + (live.get().posted() ? "reversed" : "being reversed") + " by "
                    + live.get().serialNo() + ". A document is reversed once.";
        }
        UUID me = CurrentUser.id();
        if (me != null && (me.equals(o.createdBy()) || me.equals(o.postedBy()))) {
            return "You " + (me.equals(o.createdBy()) ? "raised " : "posted ") + o.serialNo()
                    + ", so you cannot raise its reversal. Whoever raised or posted a document takes no part in"
                    + " undoing it.";
        }
        if (movements(o.id(), false).isEmpty()) {
            return o.serialNo() + " moved no stock, so there is nothing to reverse.";
        }
        return null;
    }

    Original original(UUID id) {
        if (id == null) {
            throw new ControlRefusedException("Choose the posted document to reverse.");
        }
        return jdbc.sql("""
                SELECT d.id, d.serial_no, dt.code, dt.name, dr.kind, d.status, d.branch_id, b.name AS branch_name,
                       d.posted_at, d.created_by, cu.full_name AS created_by_name,
                       d.posted_by, pu.full_name AS posted_by_name
                  FROM document d
                  JOIN document_type dt ON dt.id = d.document_type_id
                  JOIN branch b         ON b.id = d.branch_id
             LEFT JOIN damage_report dr ON dr.document_id = d.id
             LEFT JOIN app_user cu      ON cu.id = d.created_by
             LEFT JOIN app_user pu      ON pu.id = d.posted_by
                 WHERE d.id = :id
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> new Original(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("kind"),
                        rs.getString("status"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("branch_name"),
                        KigaliTime.read(rs, "posted_at"),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("created_by_name"),
                        rs.getObject("posted_by", UUID.class),
                        rs.getString("posted_by_name")))
                .optional()
                .orElseThrow(() -> new DocumentService.DocumentNotFoundException(id));
    }

    // ---- helpers ---------------------------------------------------------

    private ReversalActions actionsFor(ReversalHeader r, DocumentHeader d, List<ChainStep> chain) {
        UUID branch = r.branchId();
        boolean create = CurrentUser.holdsAt("reversal.create", branch);
        boolean signer = create || CurrentUser.holdsAt("reversal.verify", branch)
                         || CurrentUser.holdsAt("reversal.approve", branch);
        boolean poster = CurrentUser.holdsAt("reversal.post", branch);

        // V21 keeps the original's raiser and poster out of every step; say so instead of offering a
        // button the database will refuse.
        String originalHands = originalHands(r);

        StepCheck submit = documents.canSubmit(d, chain);
        StepCheck sign = documents.canSign(d, chain);
        StepCheck post = documents.canPost(d, chain);
        StepCheck cancel = documents.canCancel(d);
        String status = r.status();

        boolean canSign = sign.allowed() && originalHands == null;
        boolean canPost = post.allowed() && originalHands == null;
        boolean canCancel = cancel.allowed() && originalHands == null;
        return new ReversalActions(
                "DRAFT".equals(status) && create && originalHands == null,
                submit.allowed(),
                "DRAFT".equals(status) && !submit.allowed() && signer ? submit.reason() : null,
                canSign,
                "PENDING".equals(status) && !canSign && signer
                        ? (originalHands != null && sign.allowed() ? originalHands : sign.reason()) : null,
                sign.step(),
                canPost,
                "APPROVED".equals(status) && !canPost && poster
                        ? (originalHands != null && post.allowed() ? originalHands : post.reason()) : null,
                canCancel,
                "POSTED".equals(status) && (create || poster) ? r.serialNo() + " is posted and cannot be"
                        + " cancelled: it moved stock too. To restore what it undid, raise the original document"
                        + " again." : null,
                originalHands);
    }

    /** Why the signed-in user takes no part in this reversal, or null: they raised or posted what it undoes. */
    private static String originalHands(ReversalHeader r) {
        UUID me = CurrentUser.id();
        if (me == null) return null;
        String did = me.equals(r.originalCreatedBy()) ? "raised" : me.equals(r.originalPostedBy()) ? "posted" : null;
        return did == null ? null : "You " + did + " " + r.originalSerial() + ", so you take no part in its"
                + " reversal: you neither edit, sign, post nor cancel it.";
    }

    private ReversalHeader requireHeader(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query((rs, n) -> new ReversalHeader(
                        rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("branch_name"),
                        rs.getBoolean("branch_bonded"),
                        rs.getString("serial_no"),
                        rs.getString("status"),
                        rs.getObject("document_date", LocalDate.class),
                        rs.getInt("version"),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("created_by_name"),
                        KigaliTime.read(rs, "created_at"),
                        KigaliTime.read(rs, "posted_at"),
                        rs.getObject("posted_by", UUID.class),
                        rs.getString("posted_by_name"),
                        rs.getString("cancel_reason"),
                        rs.getString("reason"),
                        rs.getObject("original_id", UUID.class),
                        rs.getString("original_serial"),
                        rs.getString("original_type"),
                        rs.getString("original_title"),
                        rs.getString("original_kind"),
                        KigaliTime.read(rs, "original_posted_at"),
                        rs.getObject("original_created_by", UUID.class),
                        rs.getString("original_created_by_name"),
                        rs.getObject("original_posted_by", UUID.class),
                        rs.getString("original_posted_by_name")))
                .optional()
                .orElseThrow(() -> new DocumentService.DocumentNotFoundException(id));
    }
}
