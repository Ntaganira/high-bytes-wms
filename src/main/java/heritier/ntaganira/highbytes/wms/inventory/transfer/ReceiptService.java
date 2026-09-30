package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : ReceiptService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Transfer receipts at the destination: record what arrived, cancel, and post out of transit
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.common.db.ContentionException;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.document.DocumentHeader;
import heritier.ntaganira.highbytes.wms.document.DocumentKind;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.document.OpenedDocument;
import heritier.ntaganira.highbytes.wms.document.StepCheck;
import heritier.ntaganira.highbytes.wms.inventory.UnitConversions;
import heritier.ntaganira.highbytes.wms.inventory.ledger.LedgerService;
import heritier.ntaganira.highbytes.wms.inventory.ledger.MovementRequest;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingLookupService.BinOption;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transfer receipts.
 *
 * <p>A receipt is raised at the destination branch against a dispatched
 * transfer. The receiver records, per line, what actually arrived (never more
 * than was dispatched; a line that received nothing is simply absent) and
 * where it is put away. Posting moves that quantity out of the source branch's
 * transit location and into the destination location, in one transaction and
 * through two tickets, one on each branch's register. Whatever did not arrive
 * stays in transit, visible on the transfer, until a loss or damage document
 * clears it.
 *
 * <p>The receiver is not the dispatcher, and a transfer is received once: both
 * are the database's rules (V13), asked first here for the reason.
 */
@Service
@Transactional(readOnly = true)
public class ReceiptService {

    private static final String HEADER = """
            SELECT d.id, d.branch_id, b.name AS branch_name, b.is_bonded AS branch_bonded,
                   d.serial_no, d.status, d.version,
                   d.created_by, cu.full_name AS created_by_name, d.created_at,
                   d.posted_at, d.posted_by, pu.full_name AS posted_by_name,
                   d.cancelled_at, xu.full_name AS cancelled_by_name, d.cancel_reason, r.note,
                   r.transfer_id, td.serial_no AS transfer_serial, td.status AS transfer_status,
                   td.branch_id AS transfer_branch_id, tb.name AS transfer_branch_name,
                   td.posted_by AS dispatched_by, du.full_name AS dispatched_by_name, td.posted_at AS dispatched_at,
                   t.to_location_id, tl.code AS to_code, tl.name AS to_name, trl.code AS transit_code,
                   t.customs_reference
              FROM document d
              JOIN document_type dt     ON dt.id = d.document_type_id AND dt.code = 'TRR'
              JOIN transfer_receipt r   ON r.document_id = d.id
              JOIN transfer_order t     ON t.document_id = r.transfer_id
              JOIN document td          ON td.id = t.document_id
              JOIN branch tb            ON tb.id = td.branch_id
              JOIN branch b             ON b.id = d.branch_id
              JOIN location tl          ON tl.id = t.to_location_id
              JOIN location trl         ON trl.id = t.transit_location_id
              JOIN app_user cu          ON cu.id = d.created_by
         LEFT JOIN app_user pu          ON pu.id = d.posted_by
         LEFT JOIN app_user xu          ON xu.id = d.cancelled_by
         LEFT JOIN app_user du          ON du.id = td.posted_by
             WHERE d.id = :id
            """;

    private static final String LINES = """
            SELECT rl.id, rl.line_no, rl.transfer_line_id, tl.line_no AS transfer_line_no, rl.item_id,
                   i.item_code, i.description, rl.uom_id, u.code AS uom_code, bu.code AS base_uom_code,
                   rl.quantity, rl.qty_base_uom, tl.quantity AS dispatched_quantity,
                   rl.storage_bin_id, sb.bin_code, rl.note
              FROM transfer_receipt_line rl
              JOIN transfer_order_line tl ON tl.id = rl.transfer_line_id
              JOIN item i  ON i.id = rl.item_id
              JOIN uom u   ON u.id = rl.uom_id
              JOIN uom bu  ON bu.id = i.base_uom_id
         LEFT JOIN storage_bin sb ON sb.id = rl.storage_bin_id
             WHERE rl.document_id = :id
             ORDER BY rl.line_no
            """;

    /** What the receipt form draws beside the rows: the transfer, its lines, the banner, the bins. */
    public record Context(TransferHeader transfer, List<TransferLineRow> transferLines,
                          heritier.ntaganira.highbytes.wms.inventory.dispatch.ReleaseGate gate, List<BinOption> bins) {}

    /** Everything the receipt's own view needs. */
    public record Detail(ReceiptHeader header, List<ReceiptLineRow> lines, ReceiptActions actions,
                         TransferPosting posting) {}

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final TransferService transfers;
    private final TransferLookupService lookups;
    private final LedgerService ledger;
    private final BranchService branches;
    private final UnitConversions units;

    public ReceiptService(JdbcClient jdbc, DocumentService documents, TransferService transfers,
                          TransferLookupService lookups, LedgerService ledger, BranchService branches,
                          UnitConversions units) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.transfers = transfers;
        this.lookups = lookups;
        this.ledger = ledger;
        this.branches = branches;
        this.units = units;
    }

    // ---- reads -----------------------------------------------------------

    @PreAuthorize("hasAuthority('transfer.view')")
    public ReceiptHeader find(UUID id) {
        ReceiptHeader header = requireHeader(id);
        if (!CurrentUser.holdsAt("transfer.view", header.transferBranchId())) {
            CurrentUser.requireAt("transfer.view", header.branchId());
        }
        return header;
    }

    public List<ReceiptLineRow> lines(UUID id) {
        return jdbc.sql(LINES).param("id", id, Types.OTHER)
                .query((rs, n) -> new ReceiptLineRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("transfer_line_id", UUID.class),
                        rs.getInt("transfer_line_no"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getObject("uom_id", UUID.class),
                        rs.getString("uom_code"),
                        rs.getString("base_uom_code"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("qty_base_uom"),
                        rs.getBigDecimal("dispatched_quantity"),
                        rs.getObject("storage_bin_id", UUID.class),
                        rs.getString("bin_code"),
                        rs.getString("note")))
                .list();
    }

    /** The transfer the form is for, with the banner and the destination's bins beside it. */
    @PreAuthorize("hasAuthority('transfer.receive')")
    public Context contextFor(UUID transferId) {
        TransferHeader transfer = transfers.find(transferId);
        CurrentUser.requireAt("transfer.receive", transfer.toBranchId());   // a read: refused, not recorded
        return new Context(transfer, transfers.lines(transferId), transfers.gateOf(transferId),
                lookups.binsOf(transfer.toLocationId()));
    }

    /** A new receipt's form: a row per transfer line, prefilled at the dispatched quantity. */
    @PreAuthorize("hasAuthority('transfer.receive')")
    public ReceiptForm prefill(UUID transferId) {
        Context context = contextFor(transferId);
        // A read: refused with its reason, not recorded. The form is not even opened for someone who may not receive.
        String reason = independenceReason(transferId);
        if (reason != null) throw new ControlRefusedException(reason);
        ReceiptForm form = new ReceiptForm();
        form.setTransferId(transferId);
        for (TransferLineRow line : context.transferLines()) {
            ReceiptLineForm row = new ReceiptLineForm();
            row.setTransferLineId(line.id());
            row.setQuantity(line.quantity());
            form.getLines().add(row);
        }
        return form;
    }

    /** The receipt as its form holds it, for editing: rows for lines that received nothing come back as zero. */
    @PreAuthorize("hasAuthority('transfer.receive')")
    public ReceiptForm formFor(UUID id) {
        CurrentUser.requireAt("transfer.receive", documents.header(id).branchId());   // a read: refused, not recorded
        ReceiptHeader h = find(id);
        // A read: refused with its reason, not recorded. Nobody who is a party to the transfer opens the form to rewrite
        // what a bystander entered.
        String notIndependent = independenceReason(h.transferId());
        if (notIndependent != null) throw new ControlRefusedException(notIndependent);
        List<ReceiptLineRow> rows = lines(id);
        String split = splitReason(h.serialNo(), rows);
        if (split != null) throw new ControlRefusedException(split);
        Map<UUID, ReceiptLineRow> received = rows.stream()
                .collect(Collectors.toMap(ReceiptLineRow::transferLineId, l -> l));
        ReceiptForm form = new ReceiptForm();
        form.setId(h.id());
        form.setVersion(h.version());
        form.setTransferId(h.transferId());
        form.setNote(h.note());
        for (TransferLineRow line : transfers.lines(h.transferId())) {
            ReceiptLineForm row = new ReceiptLineForm();
            row.setTransferLineId(line.id());
            ReceiptLineRow got = received.get(line.id());
            row.setQuantity(got == null ? BigDecimal.ZERO : got.quantity());
            row.setStorageBinId(got == null ? null : got.storageBinId());
            row.setNote(got == null ? null : got.note());
            form.getLines().add(row);
        }
        return form;
    }

    @PreAuthorize("hasAuthority('transfer.view')")
    public Detail detail(UUID id) {
        ReceiptHeader header = find(id);
        return new Detail(header, lines(id), actionsFor(header), postingOf(id));
    }

    private TransferPosting postingOf(UUID receiptId) {
        var movements = jdbc.sql("""
                SELECT m.id, td.serial_no AS ticket_serial, t.movement_type, tl.line_no, i.item_code,
                       l.code AS location_code, sb.bin_code, m.direction, m.quantity_base_uom, m.unit_cost, m.value,
                       m.business_date
                  FROM transaction_ticket t
                  JOIN document td        ON td.id = t.document_id
                  JOIN ticket_line tl     ON tl.ticket_id = t.document_id
                  JOIN stock_movement m   ON m.ticket_line_id = tl.id
                  JOIN item i             ON i.id = m.item_id
                  JOIN location l         ON l.id = m.location_id
             LEFT JOIN storage_bin sb     ON sb.id = m.storage_bin_id
                 WHERE t.source_document_id = :id
                 ORDER BY m.id
                """)
                .param("id", receiptId, Types.OTHER)
                .query((rs, n) -> new TransferPosting.Movement(rs.getLong("id"), rs.getString("ticket_serial"),
                        rs.getString("movement_type"), rs.getInt("line_no"), rs.getString("item_code"),
                        rs.getString("location_code"), rs.getString("bin_code"), rs.getString("direction"),
                        rs.getBigDecimal("quantity_base_uom"), rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("value"), rs.getObject("business_date", java.time.LocalDate.class)))
                .list();
        return movements.isEmpty() ? null : new TransferPosting(movements);
    }

    // ---- writes ----------------------------------------------------------

    /**
     * Raises a draft receipt at the destination branch. The database refuses a
     * transfer not yet dispatched, a branch that is not the destination's, and
     * a second live receipt. Rows that received nothing are left off.
     */
    @Transactional
    @PreAuthorize("hasAuthority('transfer.receive')")
    public UUID create(ReceiptForm form) {
        TransferHeader transfer = transfers.find(form.getTransferId());
        documents.requireRightAt(DocumentKind.TRR, transfer.toBranchId(), "transfer.receive", "Raise a transfer receipt");
        String notIndependent = independenceReason(transfer.id());
        if (notIndependent != null) {
            throw documents.refused(documents.header(transfer.id()), "Raise a transfer receipt", notIndependent);
        }
        try {
            OpenedDocument opened = documents.open(DocumentKind.TRR, transfer.toBranchId(), null, null, null);
            jdbc.sql("INSERT INTO transfer_receipt (document_id, transfer_id, note) VALUES (:id, :transfer, :note)")
                    .param("id", opened.id(), Types.OTHER)
                    .param("transfer", transfer.id(), Types.OTHER)
                    .param("note", form.getNote())
                    .update();
            insertLines(opened.id(), form.getLines(), transfer.id());

            ReceiptHeader after = requireHeader(opened.id());
            documents.auditInTransaction("TRR · " + after.serialNo(), opened.id(), AuditAction.CREATE,
                    snapshot(null, null, after, lines(opened.id())),
                    branches.findById(transfer.toBranchId()).orElse(null));
            return opened.id();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
    }

    /**
     * Why a draft cannot be edited through the form, or null. The form holds
     * one row per transfer line, so a receipt recorded in parts (one line split
     * across bins) would come back as its first part and saving it would drop
     * the rest: what arrived would shrink with nobody meaning it to.
     */
    static String splitReason(String serial, List<ReceiptLineRow> rows) {
        long distinct = rows.stream().map(ReceiptLineRow::transferLineId).distinct().count();
        if (distinct == rows.size()) return null;
        return serial + " was recorded in parts, with a transfer line split across bins, which this form cannot show. "
                + "Saving it here would drop the other parts. Post it as it stands, or cancel it and raise a new receipt.";
    }

    /** Edits a draft receipt, provided nobody changed it since the form was opened. */
    @Transactional
    @PreAuthorize("hasAuthority('transfer.receive')")
    public void update(UUID id, ReceiptForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "transfer.receive", "Edit");
        if (form.getVersion() == null) {
            throw documents.refused(d, "Edit", "This form did not say which version of " + d.serialNo()
                    + " it was opened from. Reload the receipt and try again.");
        }
        ReceiptHeader before = requireHeader(id);
        String notIndependent = independenceReason(before.transferId());
        if (notIndependent != null) {
            throw documents.refused(d, "Edit", notIndependent);
        }
        List<ReceiptLineRow> beforeLines = lines(id);
        String split = splitReason(d.serialNo(), beforeLines);
        if (split != null) {
            throw documents.refused(d, "Edit", split);
        }
        try {
            documents.updateDraft(d, form.getVersion(), null, null);
            jdbc.sql("DELETE FROM transfer_receipt_line WHERE document_id = :id").param("id", id, Types.OTHER).update();
            jdbc.sql("UPDATE transfer_receipt SET note = :note WHERE document_id = :id")
                    .param("id", id, Types.OTHER).param("note", form.getNote()).update();
            insertLines(id, form.getLines(), before.transferId());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Edit", e);
        }
        ReceiptHeader after = requireHeader(id);
        AuditSnapshot snapshot = snapshot(before, beforeLines, after, lines(id));
        if (!snapshot.unchanged()) {
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, snapshot, d.branch());
        }
    }

    /** Cancels a draft receipt, so a new one can replace it. A posted receipt is refused. */
    @Transactional
    @PreAuthorize("hasAuthority('transfer.receive')")
    public void cancel(UUID id, String reason) {
        documents.cancel(id, reason);
    }

    /**
     * Confirms what arrived: the receipt goes POSTED, two tickets are raised
     * (out of the source branch's transit location, on the source register;
     * into the destination location, on the destination register), and each
     * received line moves out and in through the ledger, the destination leg
     * valued at what left transit. One transaction. Returns the serial of the
     * ticket into the destination.
     */
    @Transactional
    @PreAuthorize("hasAuthority('transfer.receive')")
    public String post(UUID id) {
        ReceiptHeader early = requireHeader(id);
        String notIndependent = independenceReason(early.transferId());
        if (notIndependent != null) {
            throw documents.refused(documents.header(id), "Post", notIndependent);
        }
        DocumentHeader d = documents.beginPost(id);      // locks; DRAFT -> POSTED by the receiver, judged by the database
        ReceiptHeader h = requireHeader(id);
        TransferHeader transfer = transfers.find(h.transferId());
        List<ReceiptLineRow> lines = lines(id);
        UUID poster = CurrentUser.id();

        OpenedDocument out;
        OpenedDocument in;
        BigDecimal value = BigDecimal.ZERO;
        int movements = 0;
        Map<UUID, BigDecimal> received = new java.util.HashMap<>();     // running quantity per transfer line, in line order
        try {
            List<LedgerService.Place> places = new ArrayList<>();
            for (ReceiptLineRow l : lines) {
                places.add(new LedgerService.Place(l.itemId(), transfer.transitLocationId()));
                places.add(new LedgerService.Place(l.itemId(), transfer.toLocationId()));
            }
            ledger.lockPlaces(places);

            // Each ticket sits on the register of the branch whose location it moves.
            out = documents.open(DocumentKind.TT, transfer.branchId(), d.serialNo(), null, null);
            in = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);
            insertTicket(out.id(), "TRANSFER_OUT", "OUT", transfer.transitLocationId(), null, id,
                    transfer.customsReference());
            insertTicket(in.id(), "TRANSFER_IN", "IN", null, transfer.toLocationId(), id, transfer.customsReference());

            List<UUID> outLines = new ArrayList<>();
            List<UUID> inLines = new ArrayList<>();
            for (ReceiptLineRow l : lines) {
                outLines.add(insertTicketLine(out.id(), l, null));
                inLines.add(insertTicketLine(in.id(), l, l.storageBinId()));
            }
            for (int i = 0; i < lines.size(); i++) {
                ReceiptLineRow l = lines.get(i);
                // The consignment leaves transit at its own cost, allocated cumulatively across this transfer line's
                // receipt lines so the shares of a split add up to exactly what was dispatched.
                BigDecimal before = received.getOrDefault(l.transferLineId(), BigDecimal.ZERO);
                BigDecimal own = consignmentShare(l, before);
                received.put(l.transferLineId(), before.add(l.quantityBase()));
                var left = ledger.post(MovementRequest.issueAt(out.id(), outLines.get(i), transfer.branchId(),
                        l.itemId(), transfer.transitLocationId(), null, l.quantityBase(), own), poster);
                ledger.post(MovementRequest.receipt(in.id(), inLines.get(i), d.branchId(), l.itemId(),
                        transfer.toLocationId(), l.storageBinId(), l.quantityBase(), left.value()), poster);
                value = value.add(left.value());
                movements += 2;
            }
        } catch (ContentionException e) {
            throw e;        // a lost race, not a refusal: logged by the engine, never audited as REJECT
        } catch (ControlRefusedException e) {
            throw documents.refused(d, "Post", e.getMessage());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Post", e);
        }

        documents.postDerived(d, out.id());
        documents.postDerived(d, in.id());

        documents.auditPosted(d, AuditSnapshot.of()
                .field("Status", "DRAFT", "POSTED")
                .value("Transfer", h.transferSerial())
                .value("Received at", transfer.toBranchName() + " · " + transfer.toCode())
                .value("Tickets", out.serialNo() + ", " + in.serialNo())
                .value("Ledger movements", movements)
                .value("Value received (RWF)", value));
        return in.serialNo();
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * The value of the stock this receipt line takes out of transit: what the
     * dispatch of its own transfer line put into transit, allocated
     * cumulatively over the receipt lines of that transfer line in line order:
     * {@code share_i = round(v x cum_i / Q, 2) - round(v x cum_(i-1) / Q, 2)},
     * half up, where v is the value of the transit-in movement of that transfer
     * line, cum the running quantity received and Q the quantity dispatched.
     * A line split across bins therefore takes exactly v when received in full,
     * with no cent stranded or over-taken. The database checks the same formula. So each consignment carries its own cost through
     * transit, whatever else sits in the pooled transit location.
     */
    private BigDecimal consignmentShare(ReceiptLineRow line, BigDecimal receivedBefore) {
        record Dispatched(BigDecimal value, BigDecimal quantity) {}
        Dispatched d = jdbc.sql("""
                SELECT m.value AS v, tl.qty_base_uom AS q_total
                  FROM transfer_order_line tl
                  JOIN transaction_ticket t ON t.source_document_id = tl.document_id AND t.movement_type = 'TRANSFER_IN'
                  JOIN ticket_line x        ON x.ticket_id = t.document_id AND x.line_no = tl.line_no
                  JOIN stock_movement m     ON m.ticket_line_id = x.id AND m.reverses_movement_id IS NULL
                 WHERE tl.id = :line
                """)
                .param("line", line.transferLineId(), Types.OTHER)
                .query((rs, n) -> new Dispatched(rs.getBigDecimal("v"), rs.getBigDecimal("q_total")))
                .optional()
                .orElseThrow(() -> new ControlRefusedException("Line " + line.transferLineNo()
                        + " of the transfer has no dispatch in the ledger, so nothing of it can be received."));
        BigDecimal cumulative = receivedBefore.add(line.quantityBase());
        return d.value().multiply(cumulative).divide(d.quantity(), 2, RoundingMode.HALF_UP)
                .subtract(d.value().multiply(receivedBefore).divide(d.quantity(), 2, RoundingMode.HALF_UP));
    }

    /**
     * Why the signed-in user may not record this transfer's arrival, or null
     * when they may: the receiver is independent of everyone who raised,
     * signed or dispatched it (the database refuses the same).
     */
    String independenceReason(UUID transferId) {
        TransferHeader t = transfers.find(transferId);
        return receiverReason(t.serialNo(), CurrentUser.id(), t.createdBy(), t.postedBy(),
                transfers.chainOf(transferId));
    }

    /** The plain reason {@code me} cannot record the arrival of a transfer, or null. Pure, so it is tested alone. */
    static String receiverReason(String transferSerial, UUID me, UUID creator, UUID dispatcher,
                                 List<heritier.ntaganira.highbytes.wms.document.ChainStep> chain) {
        if (me == null) return null;
        if (me.equals(creator)) {
            return "You raised " + transferSerial + ", so you cannot record its arrival. "
                    + "Whoever raises a transfer does not also confirm it arrived.";
        }
        if (me.equals(dispatcher)) {
            return "You dispatched " + transferSerial + ", so you cannot record its arrival. "
                    + "Whoever sends goods does not also confirm they arrived.";
        }
        return chain.stream().filter(s -> me.equals(s.actorUserId())).findFirst()
                .map(s -> "You signed step " + s.sequenceNo() + " of " + transferSerial
                        + ", so you cannot record its arrival. Whoever approved a transfer does not also confirm it arrived.")
                .orElse(null);
    }

    private ReceiptActions actionsFor(ReceiptHeader h) {
        boolean receiver = CurrentUser.holdsAt("transfer.receive", h.branchId());
        DocumentHeader d = documents.header(h.id());
        StepCheck cancel = documents.canCancel(d);

        boolean draft = "DRAFT".equals(h.status());
        String postReason = null;
        boolean canPost = false;
        if (draft && receiver) {
            postReason = independenceReason(h.transferId());
            canPost = postReason == null;
        }
        return new ReceiptActions(draft && receiver && postReason == null, canPost, postReason, cancel.allowed(),
                "POSTED".equals(h.status()) && receiver ? cancel.reason() : null);
    }

    private ReceiptHeader requireHeader(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new ReceiptNotFoundException(id));
    }

    /**
     * Receipt lines numbered by position. A row that received nothing (zero)
     * is left off: the line is simply absent and its whole quantity stays in
     * transit. Item and unit are the transfer line's.
     */
    private void insertLines(UUID documentId, List<ReceiptLineForm> rows, UUID transferId) {
        int lineNo = 0;
        for (ReceiptLineForm row : rows) {
            if (row.getQuantity() == null || row.getQuantity().signum() == 0) continue;
            record Serves(UUID item, UUID uom) {}
            Serves serves = jdbc.sql("""
                    SELECT item_id, uom_id FROM transfer_order_line WHERE id = :id AND document_id = :transfer
                    """)
                    .param("id", row.getTransferLineId(), Types.OTHER)
                    .param("transfer", transferId, Types.OTHER)
                    .query((rs, n) -> new Serves(rs.getObject("item_id", UUID.class), rs.getObject("uom_id", UUID.class)))
                    .optional()
                    .orElseThrow(() -> new ControlRefusedException(
                            "A row names a line that is not one of this transfer's."));
            lineNo++;
            jdbc.sql("""
                    INSERT INTO transfer_receipt_line (document_id, line_no, transfer_line_id, item_id, uom_id,
                                                       quantity, qty_base_uom, storage_bin_id, note, entered_by)
                    VALUES (:doc, :lineNo, :tline, :item, :uom, :quantity, :base, :bin, :note, :enteredBy)
                    """)
                    .param("doc", documentId, Types.OTHER)
                    .param("lineNo", (short) lineNo)
                    .param("tline", row.getTransferLineId(), Types.OTHER)
                    .param("item", serves.item(), Types.OTHER)
                    .param("uom", serves.uom(), Types.OTHER)
                    .param("quantity", row.getQuantity())
                    .param("base", units.baseQuantity(serves.item(), serves.uom(), row.getQuantity()))
                    .param("bin", row.getStorageBinId(), Types.OTHER)
                    .param("note", row.getNote())
                    .param("enteredBy", CurrentUser.id(), Types.OTHER)
                    .update();
        }
    }

    private void insertTicket(UUID id, String type, String direction, UUID from, UUID to, UUID source, String customs) {
        jdbc.sql("""
                INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id,
                                                to_location_id, source_document_id, customs_reference)
                VALUES (:id, :type, :direction, :from, :to, :source, :customs)
                """)
                .param("id", id, Types.OTHER)
                .param("type", type)
                .param("direction", direction)
                .param("from", from, Types.OTHER)
                .param("to", to, Types.OTHER)
                .param("source", source, Types.OTHER)
                .param("customs", customs)
                .update();
    }

    private UUID insertTicketLine(UUID ticketId, ReceiptLineRow l, UUID bin) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ticket_line (id, ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
                VALUES (:id, :ticket, :lineNo, :item, :quantity, :uom, :base, :bin)
                """)
                .param("id", id, Types.OTHER)
                .param("ticket", ticketId, Types.OTHER)
                .param("lineNo", (short) l.lineNo())
                .param("item", l.itemId(), Types.OTHER)
                .param("quantity", l.quantity())
                .param("uom", l.uomId(), Types.OTHER)
                .param("base", l.quantityBase())
                .param("bin", bin, Types.OTHER)
                .update();
        return id;
    }

    private AuditSnapshot snapshot(ReceiptHeader before, List<ReceiptLineRow> beforeLines,
                                   ReceiptHeader after, List<ReceiptLineRow> afterLines) {
        AuditSnapshot s = AuditSnapshot.of();
        s.field("Transfer", before == null ? null : before.transferSerial(), after.transferSerial());
        s.field("Note",     before == null ? null : before.note(), after.note());
        s.field("Lines",    beforeLines == null ? null : describe(beforeLines), describe(afterLines));
        return s;
    }

    private static String describe(List<ReceiptLineRow> lines) {
        return lines.stream().map(l -> l.lineNo() + ": " + l.itemCode() + " x "
                        + l.quantity().stripTrailingZeros().toPlainString() + " of "
                        + l.dispatchedQuantity().stripTrailingZeros().toPlainString() + " " + l.uomCode()
                        + (l.binCode() == null ? "" : ", bin " + l.binCode()))
                .collect(Collectors.joining("; "));
    }

    private ReceiptHeader mapHeader(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ReceiptHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"),
                rs.getBoolean("branch_bonded"),
                rs.getString("serial_no"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getObject("created_by", UUID.class),
                rs.getString("created_by_name"),
                KigaliTime.read(rs, "created_at"),
                KigaliTime.read(rs, "posted_at"),
                rs.getObject("posted_by", UUID.class),
                rs.getString("posted_by_name"),
                KigaliTime.read(rs, "cancelled_at"),
                rs.getString("cancelled_by_name"),
                rs.getString("cancel_reason"),
                rs.getString("note"),
                rs.getObject("transfer_id", UUID.class),
                rs.getString("transfer_serial"),
                rs.getString("transfer_status"),
                rs.getObject("transfer_branch_id", UUID.class),
                rs.getString("transfer_branch_name"),
                rs.getObject("dispatched_by", UUID.class),
                rs.getString("dispatched_by_name"),
                KigaliTime.read(rs, "dispatched_at"),
                rs.getObject("to_location_id", UUID.class),
                rs.getString("to_code"),
                rs.getString("to_name"),
                rs.getString("transit_code"),
                rs.getString("customs_reference"));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class ReceiptNotFoundException extends RuntimeException {
        public ReceiptNotFoundException(UUID id) {
            super("No transfer receipt with id " + id);
        }
    }
}
