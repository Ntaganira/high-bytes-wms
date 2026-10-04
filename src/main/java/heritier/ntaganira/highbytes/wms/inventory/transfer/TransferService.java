package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Inter-branch transfers: draft, submit, sign, cancel and dispatch into transit, audited
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
import heritier.ntaganira.highbytes.wms.inventory.UnitConversions;
import heritier.ntaganira.highbytes.wms.inventory.gate.GateSteps;
import heritier.ntaganira.highbytes.wms.inventory.gate.ReleaseGate;
import heritier.ntaganira.highbytes.wms.inventory.lookup.StockAt;
import heritier.ntaganira.highbytes.wms.inventory.damage.PartyCheck;
import heritier.ntaganira.highbytes.wms.inventory.ledger.LedgerService;
import heritier.ntaganira.highbytes.wms.inventory.ledger.MovementRequest;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transfers between branches, on the document engine.
 *
 * <p>A transfer is raised at, and belongs to, its source branch, and signed
 * down the chain it was bound to on the day it was created (read from that
 * definition, never named here). It is APPROVED when the last step has
 * signed, and POSTED when the source warehouse dispatches it: someone who
 * neither raised it nor signed it (the database's rule) records the goods
 * leaving, and the stock moves out of the source location into the source
 * branch's transit location. Four transaction tickets make a transfer (see
 * V13); this class writes the two of the dispatch, {@link ReceiptService} the
 * two of the receipt.
 *
 * <p>Stock in transit keeps its cost: the transit leg is valued at what the
 * out leg took out of the source at its average cost, so a unit that left
 * Gahanga at 8,869.5652 arrives in Rubavu at 8,869.5652.
 */
@Service
@Transactional(readOnly = true)
public class TransferService {

    private static final String DISPLAY_STATE = """
            CASE WHEN d.status = 'POSTED'
                 THEN CASE WHEN NOT EXISTS (SELECT 1 FROM transfer_receipt r JOIN document rd ON rd.id = r.document_id
                                             WHERE r.transfer_id = d.id AND rd.status = 'POSTED')
                           THEN CASE WHEN EXISTS (SELECT 1 FROM transfer_line_position p
                                                   WHERE p.transfer_id = d.id AND p.in_transit_base > 0)
                                     THEN 'DISPATCHED' ELSE 'WRITTEN_OFF' END
                           WHEN EXISTS (SELECT 1 FROM transfer_line_position p
                                         WHERE p.transfer_id = d.id AND p.in_transit_base > 0)
                           THEN 'IN_TRANSIT'
                           ELSE 'RECEIVED' END
                 ELSE d.status END
            """;

    private static final String HEADER = """
            SELECT d.id, d.branch_id, b.name AS branch_name, b.is_bonded AS branch_bonded,
                   d.serial_no, d.status, %s AS display_state,
                   d.document_date, d.reference, d.notes, d.version,
                   d.created_by, cu.full_name AS created_by_name, d.created_at, d.submitted_at, d.approved_at,
                   d.posted_at, d.posted_by, pu.full_name AS posted_by_name,
                   d.cancelled_at, xu.full_name AS cancelled_by_name, d.cancel_reason,
                   d.supersedes_document_id, sd.serial_no AS supersedes_serial,
                   t.from_location_id, fl.code AS from_code, fl.name AS from_name, fl.is_bonded AS from_bonded,
                   t.to_location_id, tl.code AS to_code, tl.name AS to_name, tl.is_bonded AS to_bonded,
                   tl.branch_id AS to_branch_id, tb.name AS to_branch_name, tb.is_bonded AS to_branch_bonded,
                   t.transit_location_id, trl.code AS transit_code, t.customs_reference, t.note
              FROM document d
              JOIN document_type dt     ON dt.id = d.document_type_id AND dt.code = 'TRF'
              JOIN transfer_order t     ON t.document_id = d.id
              JOIN branch b             ON b.id = d.branch_id
              JOIN location fl          ON fl.id = t.from_location_id
              JOIN location tl          ON tl.id = t.to_location_id
              JOIN branch tb            ON tb.id = tl.branch_id
              JOIN location trl         ON trl.id = t.transit_location_id
              JOIN app_user cu          ON cu.id = d.created_by
         LEFT JOIN app_user pu          ON pu.id = d.posted_by
         LEFT JOIN app_user xu          ON xu.id = d.cancelled_by
         LEFT JOIN document sd          ON sd.id = d.supersedes_document_id
             WHERE d.id = :id
            """.formatted(DISPLAY_STATE);

    private static final String LINES = """
            SELECT l.id, l.line_no, l.item_id, i.item_code, i.description, l.uom_id, u.code AS uom_code,
                   bu.code AS base_uom_code, l.quantity, l.qty_base_uom, l.storage_bin_id, sb.bin_code, l.note,
                   p.dispatched_base, p.received_base, p.in_transit_base, p.written_off_base
              FROM transfer_order_line l
              JOIN item i  ON i.id = l.item_id
              JOIN uom u   ON u.id = l.uom_id
              JOIN uom bu  ON bu.id = i.base_uom_id
         LEFT JOIN storage_bin sb ON sb.id = l.storage_bin_id
         LEFT JOIN transfer_line_position p ON p.transfer_line_id = l.id
             WHERE l.document_id = :id
             ORDER BY l.line_no
            """;

    private static final String LIST_COLUMNS = """
            SELECT d.id, d.serial_no, %s AS display_state, d.document_date,
                   sb.name AS from_branch, fl.code AS from_code, tb.name AS to_branch, tl.code AS to_code,
                   (sb.is_bonded OR fl.is_bonded OR tl.is_bonded OR tb.is_bonded) AS bonded,
                   (SELECT COUNT(*) FROM transfer_order_line x WHERE x.document_id = d.id) AS line_count,
                   cu.full_name AS created_by_name, d.posted_at, d.created_at, d.branch_id,
                   nx.role_name AS awaiting_role
              FROM document d
              JOIN document_type dt     ON dt.id = d.document_type_id AND dt.code = 'TRF'
              JOIN transfer_order t     ON t.document_id = d.id
              JOIN branch sb            ON sb.id = d.branch_id
              JOIN location fl          ON fl.id = t.from_location_id
              JOIN location tl          ON tl.id = t.to_location_id
              JOIN branch tb            ON tb.id = tl.branch_id
              JOIN app_user cu          ON cu.id = d.created_by
         LEFT JOIN LATERAL (
                   SELECT r.name AS role_name
                     FROM workflow_step ws JOIN role r ON r.id = ws.required_role_id
                    WHERE ws.workflow_definition_id = d.workflow_definition_id AND d.status = 'PENDING'
                      AND NOT EXISTS (SELECT 1 FROM document_approval da
                                       WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                    ORDER BY ws.sequence_no LIMIT 1) nx ON TRUE
            """.formatted(DISPLAY_STATE);

    /** One receipt raised against a transfer, for the transfer's view. */
    public record ReceiptSummary(UUID id, String serialNo, String status, LocalDateTime postedAt, String postedByName) {}

    /** One loss report written against a transfer, for the transfer's view. */
    public record LossSummary(UUID id, String serialNo, String status, LocalDateTime postedAt, String postedByName) {}

    /** Everything the transfer's view needs, gathered in one read-only pass. */
    public record Detail(TransferHeader header, List<TransferLineRow> lines, Map<UUID, List<StockAt>> stock,
                         List<ChainStep> chain, ChainInfo chainInfo, ReleaseGate gate, TransferActions actions,
                         List<ReceiptSummary> receipts, TransferPosting posting, List<LossSummary> losses) {}

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final TransferLookupService lookups;
    private final LedgerService ledger;
    private final BranchService branches;
    private final GateSteps gateSteps;
    private final UnitConversions units;
    private final PartyCheck parties;

    public TransferService(JdbcClient jdbc, DocumentService documents, TransferLookupService lookups,
                           LedgerService ledger, BranchService branches, GateSteps gateSteps, UnitConversions units,
                           PartyCheck parties) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.lookups = lookups;
        this.ledger = ledger;
        this.branches = branches;
        this.gateSteps = gateSteps;
        this.units = units;
        this.parties = parties;
    }

    // ---- reads -----------------------------------------------------------

    /** Transfers raised at this branch (the source), newest first. */
    @PreAuthorize("hasAuthority('transfer.view')")
    public List<TransferRow> list(UUID branchId, String state, boolean awaitingMe) {
        Set<UUID> mine = documents.awaitingSignatureOf(DocumentKind.TRF, branchId, CurrentUser.id());
        return jdbc.sql("SELECT * FROM (" + LIST_COLUMNS + " WHERE d.branch_id = :branch) x "
                        + " WHERE (:state::text IS NULL OR x.display_state = :state) ORDER BY x.created_at DESC LIMIT 300")
                .param("branch", branchId, Types.OTHER)
                .param("state", state == null || state.isBlank() ? null : state)
                .query(this::mapRow)
                .list().stream()
                .map(row -> row.markAwaitingMe(mine.contains(row.id())))
                .filter(row -> !awaitingMe || row.awaitingMe())
                .toList();
    }

    /** Approved transfers at the source branch, not yet dispatched: what the warehouse may send now. */
    @PreAuthorize("hasAuthority('transfer.dispatch')")
    public List<TransferRow> readyToDispatch(UUID branchId) {
        return jdbc.sql(LIST_COLUMNS + " WHERE d.branch_id = :branch AND d.status = 'APPROVED' ORDER BY d.approved_at")
                .param("branch", branchId, Types.OTHER)
                .query(this::mapRow).list();
    }

    /** Dispatched transfers bound for this branch with no live receipt yet: what the destination awaits. */
    @PreAuthorize("hasAuthority('transfer.receive')")
    public List<TransferRow> awaitingReceipt(UUID branchId) {
        return jdbc.sql(LIST_COLUMNS + """
                 WHERE tl.branch_id = :branch AND d.status = 'POSTED'
                   AND EXISTS (SELECT 1 FROM transfer_line_position p WHERE p.transfer_id = d.id AND p.in_transit_base > 0)
                   AND NOT EXISTS (SELECT 1 FROM transfer_receipt r JOIN document rd ON rd.id = r.document_id
                                    WHERE r.transfer_id = d.id AND rd.status <> 'CANCELLED')
                 ORDER BY d.posted_at
                """)
                .param("branch", branchId, Types.OTHER)
                .query(this::mapRow).list();
    }

    /**
     * The transfer's header. Readable at the source branch, which raised it, and
     * at the destination, which must receive it; a 403 elsewhere.
     */
    @PreAuthorize("hasAuthority('transfer.view')")
    public TransferHeader find(UUID id) {
        TransferHeader header = requireHeader(id);
        if (!CurrentUser.holdsAt("transfer.view", header.toBranchId())) {
            CurrentUser.requireAt("transfer.view", header.branchId());
        }
        return header;
    }

    public List<TransferLineRow> lines(UUID id) {
        return jdbc.sql(LINES).param("id", id, Types.OTHER)
                .query((rs, n) -> new TransferLineRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getObject("uom_id", UUID.class),
                        rs.getString("uom_code"),
                        rs.getString("base_uom_code"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("qty_base_uom"),
                        rs.getObject("storage_bin_id", UUID.class),
                        rs.getString("bin_code"),
                        rs.getString("note"),
                        rs.getBigDecimal("dispatched_base"),
                        rs.getBigDecimal("received_base"),
                        rs.getBigDecimal("in_transit_base"),
                        rs.getBigDecimal("written_off_base")))
                .list();
    }

    @PreAuthorize("hasAuthority('transfer.view')")
    public Detail detail(UUID id) {
        TransferHeader header = find(id);
        List<TransferLineRow> lines = lines(id);
        DocumentHeader document = documents.header(id);
        List<ChainStep> chain = documents.chain(id);
        ReleaseGate gate = gate(header, chain, gateSteps.now());
        Map<UUID, List<StockAt>> stock = "APPROVED".equals(header.status()) || "DRAFT".equals(header.status())
                || "PENDING".equals(header.status())
                ? lookups.stockAt(header.fromLocationId(),
                        lines.stream().map(TransferLineRow::itemId).collect(Collectors.toSet()))
                : Map.of();
        return new Detail(header, lines, stock, chain, documents.chainInfo(id).orElse(null), gate,
                actionsFor(header, document, chain), receiptsOf(id), postingOf(id).orElse(null), lossesOf(id));
    }

    /** The release banner for a transfer, shared with the delivery authorization's. */
    @PreAuthorize("hasAuthority('transfer.view')")
    public ReleaseGate gateOf(UUID id) {
        return gate(find(id), documents.chain(id), gateSteps.now());
    }

    ReleaseGate gate(TransferHeader h, List<ChainStep> chain, LocalDateTime now) {
        String status = h.status();
        if ("REJECTED".equals(status) || "CANCELLED".equals(status)) {
            return new ReleaseGate("VOID", h.serialNo(), List.of(), null, null, null, null, null, null,
                    status.toLowerCase(), "TRF");
        }
        if ("APPROVED".equals(status)) {
            ChainStep last = chain.isEmpty() ? null : chain.get(chain.size() - 1);
            return new ReleaseGate("RELEASED", h.serialNo(), List.of(),
                    last == null ? null : last.actorName(), last == null ? null : last.decidedAt(),
                    null, null, null, null, null, "TRF");
        }
        if ("POSTED".equals(status)) {
            Optional<ReceiptSummary> live = receiptsOf(h.id()).stream()
                    .filter(r -> !"CANCELLED".equals(r.status())).findFirst();
            return new ReleaseGate(h.displayState(), h.serialNo(), List.of(), null, null,
                    live.map(ReceiptSummary::id).orElse(null), live.map(ReceiptSummary::serialNo).orElse(null),
                    h.postedAt(), h.postedByName(), h.toBranchName(), "TRF");
        }
        var waiting = gateSteps.waiting(h.id(), h.branchId(), h.createdBy(), status, h.submittedAt(), chain, now);
        return new ReleaseGate("BLOCKED", h.serialNo(), waiting, null, null, null, null, null, null, null, "TRF");
    }

    /** The transfer's bound chain with each step's decision, for callers that judge independence from it. */
    public List<ChainStep> chainOf(UUID transferId) {
        return documents.chain(transferId);
    }

    public List<ReceiptSummary> receiptsOf(UUID transferId) {
        return jdbc.sql("""
                SELECT d.id, d.serial_no, d.status, d.posted_at, pu.full_name AS posted_by_name
                  FROM transfer_receipt r JOIN document d ON d.id = r.document_id
             LEFT JOIN app_user pu ON pu.id = d.posted_by
                 WHERE r.transfer_id = :id ORDER BY d.created_at
                """)
                .param("id", transferId, Types.OTHER)
                .query((rs, n) -> new ReceiptSummary(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getString("status"), KigaliTime.read(rs, "posted_at"), rs.getString("posted_by_name")))
                .list();
    }

    /** Loss reports raised against the transfer, oldest first. */
    public List<LossSummary> lossesOf(UUID transferId) {
        return jdbc.sql("""
                SELECT d.id, d.serial_no, d.status, d.posted_at, pu.full_name AS posted_by_name
                  FROM damage_report r JOIN document d ON d.id = r.document_id
             LEFT JOIN app_user pu ON pu.id = d.posted_by
                 WHERE r.transfer_id = :id ORDER BY d.created_at
                """)
                .param("id", transferId, Types.OTHER)
                .query((rs, n) -> new LossSummary(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getString("status"), KigaliTime.read(rs, "posted_at"), rs.getString("posted_by_name")))
                .list();
    }

    /** The two dispatch tickets' movements, and any receipt's, for a transfer's view. */
    public Optional<TransferPosting> postingOf(UUID transferId) {
        List<TransferPosting.Movement> movements = jdbc.sql("""
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
                    OR t.source_document_id IN (SELECT r.document_id FROM transfer_receipt r WHERE r.transfer_id = :id)
                 ORDER BY m.id
                """)
                .param("id", transferId, Types.OTHER)
                .query((rs, n) -> new TransferPosting.Movement(rs.getLong("id"), rs.getString("ticket_serial"),
                        rs.getString("movement_type"), rs.getInt("line_no"), rs.getString("item_code"),
                        rs.getString("location_code"), rs.getString("bin_code"), rs.getString("direction"),
                        rs.getBigDecimal("quantity_base_uom"), rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("value"), rs.getObject("business_date", java.time.LocalDate.class)))
                .list();
        return movements.isEmpty() ? Optional.empty() : Optional.of(new TransferPosting(movements));
    }

    /** The transfer as its form holds it, for editing or for raising a corrected copy. */
    @PreAuthorize("hasAuthority('transfer.create')")
    public TransferForm formFor(UUID id) {
        CurrentUser.requireAt("transfer.create", documents.header(id).branchId());   // a read: refused, not recorded
        TransferHeader h = find(id);
        TransferForm form = new TransferForm();
        form.setId(h.id());
        form.setVersion(h.version());
        form.setFromLocationId(h.fromLocationId());
        form.setToLocationId(h.toLocationId());
        form.setCustomsReference(h.customsReference());
        form.setNote(h.note());
        form.setReference(h.reference());
        form.setNotes(h.notes());
        form.setSupersedesDocumentId(h.supersedesId());
        for (TransferLineRow row : lines(id)) {
            TransferLineForm line = new TransferLineForm();
            line.setItemId(row.itemId());
            line.setUomId(row.uomId());
            line.setQuantity(row.quantity());
            line.setStorageBinId(row.storageBinId());
            line.setNote(row.note());
            form.getLines().add(line);
        }
        return form;
    }

    // ---- writes ----------------------------------------------------------

    /** Raises a draft at the source branch. The database dates it today and binds the chain in force today. */
    @Transactional
    @PreAuthorize("hasAuthority('transfer.create')")
    public UUID create(TransferForm form) {
        UUID branchId = locationBranch(form.getFromLocationId());
        documents.requireRightAt(DocumentKind.TRF, branchId, "transfer.create", "Raise a transfer");
        requireCorrectable(form.getSupersedesDocumentId(), branchId);
        try {
            OpenedDocument opened = documents.open(DocumentKind.TRF, branchId, form.getReference(),
                    form.getNotes(), form.getSupersedesDocumentId());
            insertHeader(opened.id(), form);
            insertLines(opened.id(), form.getLines());

            TransferHeader after = requireHeader(opened.id());
            documents.auditInTransaction("TRF · " + after.serialNo(), opened.id(), AuditAction.CREATE,
                    snapshot(null, null, after, lines(opened.id())), branches.findById(branchId).orElse(null));
            return opened.id();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
    }

    /** Edits a draft, provided nobody changed it since the form was opened; whether it is still a draft is the database's to say. */
    @Transactional
    @PreAuthorize("hasAuthority('transfer.create')")
    public void update(UUID id, TransferForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "transfer.create", "Edit");
        if (form.getVersion() == null) {
            throw documents.refused(d, "Edit", "This form did not say which version of " + d.serialNo()
                    + " it was opened from. Reload the transfer and try again.");
        }
        UUID branchId = locationBranch(form.getFromLocationId());
        if (!branchId.equals(d.branchId())) {
            throw documents.refused(d, "Edit", "That source location is at another branch. " + d.serialNo()
                    + " belongs to " + d.branchName() + " and leaves from there.");
        }
        TransferHeader before = requireHeader(id);
        List<TransferLineRow> beforeLines = lines(id);
        try {
            documents.updateDraft(d, form.getVersion(), form.getReference(), form.getNotes());
            jdbc.sql("DELETE FROM transfer_order_line WHERE document_id = :id").param("id", id, Types.OTHER).update();
            jdbc.sql("""
                    UPDATE transfer_order SET from_location_id = :from, to_location_id = :to,
                           customs_reference = :customs, note = :note
                     WHERE document_id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .params(headerParams(form))
                    .update();
            insertLines(id, form.getLines());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Edit", e);
        }
        TransferHeader after = requireHeader(id);
        AuditSnapshot snapshot = snapshot(before, beforeLines, after, lines(id));
        if (!snapshot.unchanged()) {
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, snapshot, d.branch());
        }
    }

    @Transactional
    @PreAuthorize("hasAnyAuthority('transfer.create','transfer.approve','transfer.verify')")
    public String submit(UUID id) {
        return documents.submit(id).status();
    }

    @Transactional
    @PreAuthorize("hasAnyAuthority('transfer.create','transfer.approve','transfer.verify')")
    public String sign(UUID id, boolean approve, String comment) {
        return documents.sign(id, approve, comment).status();
    }

    /** Cancels a draft, pending or approved transfer. A dispatched one is refused: what left is corrected by a reversal. */
    @Transactional
    @PreAuthorize("hasAuthority('transfer.create')")
    public void cancel(UUID id, String reason) {
        documents.cancel(id, reason);
    }

    /**
     * Records the goods leaving the source branch: the transfer goes POSTED,
     * two tickets are raised (out of the source location, into the source
     * branch's transit location), and each line moves out and in through the
     * ledger, the transit leg valued at what the out leg took. One transaction.
     * Returns the serial of the out ticket.
     */
    @Transactional
    @PreAuthorize("hasAuthority('transfer.dispatch')")
    public String dispatch(UUID id) {
        DocumentHeader d = documents.beginPost(id);      // locks; APPROVED -> POSTED by the dispatcher, judged by the database
        TransferHeader h = requireHeader(id);
        List<TransferLineRow> lines = lines(id);
        UUID poster = CurrentUser.id();

        OpenedDocument out;
        OpenedDocument in;
        BigDecimal value = BigDecimal.ZERO;
        int movements = 0;
        try {
            // Every place this dispatch touches is locked up front, in one fixed order.
            List<LedgerService.Place> places = new ArrayList<>();
            for (TransferLineRow l : lines) {
                places.add(new LedgerService.Place(l.itemId(), h.fromLocationId()));
                places.add(new LedgerService.Place(l.itemId(), h.transitLocationId()));
            }
            ledger.lockPlaces(places);

            out = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);
            in = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);
            insertTicket(out.id(), "TRANSFER_OUT", "OUT", h.fromLocationId(), null, id, h.customsReference());
            insertTicket(in.id(), "TRANSFER_IN", "IN", null, h.transitLocationId(), id, h.customsReference());

            List<UUID> outLines = new ArrayList<>();
            List<UUID> inLines = new ArrayList<>();
            for (TransferLineRow l : lines) {
                outLines.add(insertTicketLine(out.id(), l.lineNo(), l.itemId(), l.quantity(), l.uomId(),
                        l.quantityBase(), l.storageBinId()));
                // The transit location has no bins: the goods are in flight, not on a shelf.
                inLines.add(insertTicketLine(in.id(), l.lineNo(), l.itemId(), l.quantity(), l.uomId(),
                        l.quantityBase(), null));
            }
            for (int i = 0; i < lines.size(); i++) {
                TransferLineRow l = lines.get(i);
                var left = ledger.post(MovementRequest.issue(out.id(), outLines.get(i), d.branchId(), l.itemId(),
                        h.fromLocationId(), l.storageBinId(), l.quantityBase()), poster);
                // The transit leg takes the value the out leg took: the stock carries its cost through transit.
                ledger.post(MovementRequest.receipt(in.id(), inLines.get(i), d.branchId(), l.itemId(),
                        h.transitLocationId(), null, l.quantityBase(), left.value()), poster);
                value = value.add(left.value());
                movements += 2;
            }
        } catch (ContentionException e) {
            throw e;        // a lost race, not a refusal: logged by the engine, never audited as REJECT
        } catch (ControlRefusedException e) {
            throw documents.refused(d, "Dispatch", e.getMessage());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Dispatch", e);
        }

        documents.postDerived(d, out.id());
        documents.postDerived(d, in.id());

        documents.auditPosted(d, AuditSnapshot.of()
                .field("Status", "APPROVED", "POSTED")
                .value("From", h.fromCode())
                .value("To", h.toBranchName() + " · " + h.toCode())
                .value("Held in transit at", h.transitCode())
                .value("Tickets", out.serialNo() + ", " + in.serialNo())
                .value("Ledger movements", movements)
                .value("Value in transit (RWF)", value));
        return out.serialNo();
    }

    // ---- helpers ---------------------------------------------------------

    private TransferActions actionsFor(TransferHeader h, DocumentHeader d, List<ChainStep> chain) {
        UUID source = h.branchId();
        boolean create = CurrentUser.holdsAt("transfer.create", source);
        boolean signer = create || CurrentUser.holdsAt("transfer.approve", source)
                         || CurrentUser.holdsAt("transfer.verify", source);
        boolean dispatcher = CurrentUser.holdsAt("transfer.dispatch", source);
        boolean receiver = CurrentUser.holdsAt("transfer.receive", h.toBranchId());

        StepCheck submit = documents.canSubmit(d, chain);
        StepCheck sign = documents.canSign(d, chain);
        StepCheck cancel = documents.canCancel(d);
        StepCheck dispatch = documents.canPost(d, chain);
        String status = h.status();

        boolean remaining = inTransitLeft(h.id());
        String receiveReason = null;
        boolean canReceive = false;
        if ("POSTED".equals(status) && receiver) {
            UUID me = CurrentUser.id();
            boolean live = receiptsOf(h.id()).stream().anyMatch(r -> !"CANCELLED".equals(r.status()));
            String independence = ReceiptService.receiverReason(h.serialNo(), me, h.createdBy(), h.postedBy(), chain);
            if (independence == null && ReceiptService.wroteOffPart(jdbc, me, h.id())) {
                independence = ReceiptService.lossAuthorReason(h.serialNo());
            }
            if (independence != null) {
                receiveReason = independence;
            } else if (live) {
                receiveReason = h.serialNo() + " already has a live receipt. A transfer is received once.";
            } else if (!remaining) {
                receiveReason = "Nothing remains in transit on " + h.serialNo() + ": everything dispatched has been "
                        + "received or written off as lost.";
            } else {
                canReceive = true;
            }
        }
        boolean canRaiseLoss = false;
        String lossReason = null;
        if ("POSTED".equals(status) && CurrentUser.holdsAt("damage.create", h.branchId())) {
            String independence = parties.reason(CurrentUser.id(), "TRANSIT_LOSS", h.id(), null);
            if (!remaining) {
                lossReason = "Nothing remains in transit on " + h.serialNo() + ": everything dispatched has been "
                        + "received or written off as lost.";
            } else if (independence != null) {
                lossReason = independence;
            } else {
                canRaiseLoss = true;
            }
        }
        return new TransferActions(
                "DRAFT".equals(status) && create,
                submit.allowed(),
                "DRAFT".equals(status) && !submit.allowed() && signer ? submit.reason() : null,
                sign.allowed(),
                "PENDING".equals(status) && !sign.allowed() && signer ? sign.reason() : null,
                sign.step(),
                cancel.allowed(),
                "POSTED".equals(status) && (create || dispatcher) ? h.serialNo() + " has been dispatched, so it cannot "
                        + "be cancelled: the stock has left. A wrong transfer is corrected by a reversing document "
                        + "(not built yet)." : null,
                create && ("REJECTED".equals(status) || "CANCELLED".equals(status)),
                dispatch.allowed(),
                "APPROVED".equals(status) && !dispatch.allowed() && dispatcher ? dispatch.reason() : null,
                canReceive,
                receiveReason,
                canRaiseLoss,
                lossReason);
    }

    /** Whether any line of this dispatched transfer still has stock in the source branch's transit location. */
    boolean inTransitLeft(UUID transferId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM transfer_line_position WHERE transfer_id = :id AND in_transit_base > 0)")
                .param("id", transferId, Types.OTHER).query(Boolean.class).single();
    }

    private TransferHeader requireHeader(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new TransferNotFoundException(id));
    }

    private UUID locationBranch(UUID locationId) {
        return jdbc.sql("SELECT branch_id FROM location WHERE id = :id")
                .param("id", locationId, Types.OTHER)
                .query(UUID.class).optional()
                .orElseThrow(() -> new ControlRefusedException("That source location does not exist."));
    }

    /** A corrected copy replaces a rejected or cancelled transfer at the same branch; the form carries the id. */
    private void requireCorrectable(UUID supersedesId, UUID branchId) {
        if (supersedesId == null) return;
        boolean ok = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM document d JOIN document_type dt ON dt.id = d.document_type_id
                                WHERE d.id = :id AND dt.code = 'TRF' AND d.branch_id = :branch
                                  AND d.status IN ('REJECTED', 'CANCELLED'))
                """)
                .param("id", supersedesId, Types.OTHER)
                .param("branch", branchId, Types.OTHER)
                .query(Boolean.class).single();
        if (!ok) {
            throw new ControlRefusedException("A corrected copy replaces a rejected or cancelled transfer at this "
                    + "branch. The one it names is neither.");
        }
    }

    private void insertHeader(UUID id, TransferForm form) {
        jdbc.sql("""
                INSERT INTO transfer_order (document_id, from_location_id, to_location_id, customs_reference, note)
                VALUES (:id, :from, :to, :customs, :note)
                """)
                .param("id", id, Types.OTHER)
                .params(headerParams(form))
                .update();
    }

    private Map<String, Object> headerParams(TransferForm form) {
        var params = new HashMap<String, Object>();
        params.put("from", form.getFromLocationId());
        params.put("to", form.getToLocationId());
        params.put("customs", form.getCustomsReference());
        params.put("note", form.getNote());
        return params;
    }

    /** Numbered by position, so the lines are always 1..n whatever was added or removed on the form. */
    private void insertLines(UUID documentId, List<TransferLineForm> lines) {
        int lineNo = 0;
        for (TransferLineForm line : lines) {
            lineNo++;
            jdbc.sql("""
                    INSERT INTO transfer_order_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom,
                                                     storage_bin_id, note)
                    VALUES (:doc, :lineNo, :item, :uom, :quantity, :base, :bin, :note)
                    """)
                    .param("doc", documentId, Types.OTHER)
                    .param("lineNo", (short) lineNo)
                    .param("item", line.getItemId(), Types.OTHER)
                    .param("uom", line.getUomId(), Types.OTHER)
                    .param("quantity", line.getQuantity())
                    .param("base", units.baseQuantity(line.getItemId(), line.getUomId(), line.getQuantity()))
                    .param("bin", line.getStorageBinId(), Types.OTHER)
                    .param("note", line.getNote())
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

    private UUID insertTicketLine(UUID ticketId, int lineNo, UUID item, BigDecimal quantity, UUID uom,
                                  BigDecimal base, UUID bin) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ticket_line (id, ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
                VALUES (:id, :ticket, :lineNo, :item, :quantity, :uom, :base, :bin)
                """)
                .param("id", id, Types.OTHER)
                .param("ticket", ticketId, Types.OTHER)
                .param("lineNo", (short) lineNo)
                .param("item", item, Types.OTHER)
                .param("quantity", quantity)
                .param("uom", uom, Types.OTHER)
                .param("base", base)
                .param("bin", bin, Types.OTHER)
                .update();
        return id;
    }

    private AuditSnapshot snapshot(TransferHeader before, List<TransferLineRow> beforeLines,
                                   TransferHeader after, List<TransferLineRow> afterLines) {
        AuditSnapshot s = AuditSnapshot.of();
        s.field("From",              before == null ? null : before.fromCode(), after.fromCode());
        s.field("To",                before == null ? null : before.toBranchName() + " · " + before.toCode(),
                                     after.toBranchName() + " · " + after.toCode());
        s.field("Customs reference", before == null ? null : before.customsReference(), after.customsReference());
        s.field("Note",              before == null ? null : before.note(), after.note());
        s.field("Reference",         before == null ? null : before.reference(), after.reference());
        s.field("Notes",             before == null ? null : before.notes(), after.notes());
        s.field("Corrects",          before == null ? null : before.supersedesSerial(), after.supersedesSerial());
        s.field("Lines",             beforeLines == null ? null : describe(beforeLines), describe(afterLines));
        return s;
    }

    private static String describe(List<TransferLineRow> lines) {
        return lines.stream().map(l -> l.lineNo() + ": " + l.itemCode() + " x "
                        + l.quantity().stripTrailingZeros().toPlainString() + " " + l.uomCode()
                        + (l.binCode() == null ? "" : ", bin " + l.binCode()))
                .collect(Collectors.joining("; "));
    }

    private TransferRow mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new TransferRow(
                rs.getObject("id", UUID.class),
                rs.getString("serial_no"),
                rs.getString("display_state"),
                rs.getObject("document_date", java.time.LocalDate.class),
                rs.getString("from_branch"),
                rs.getString("from_code"),
                rs.getString("to_branch"),
                rs.getString("to_code"),
                rs.getBoolean("bonded"),
                rs.getInt("line_count"),
                rs.getString("created_by_name"),
                rs.getString("awaiting_role"),
                false,
                KigaliTime.read(rs, "posted_at"));
    }

    private TransferHeader mapHeader(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new TransferHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"),
                rs.getBoolean("branch_bonded"),
                rs.getString("serial_no"),
                rs.getString("status"),
                rs.getString("display_state"),
                rs.getObject("document_date", java.time.LocalDate.class),
                rs.getString("reference"),
                rs.getString("notes"),
                rs.getInt("version"),
                rs.getObject("created_by", UUID.class),
                rs.getString("created_by_name"),
                KigaliTime.read(rs, "created_at"),
                KigaliTime.read(rs, "submitted_at"),
                KigaliTime.read(rs, "approved_at"),
                KigaliTime.read(rs, "posted_at"),
                rs.getObject("posted_by", UUID.class),
                rs.getString("posted_by_name"),
                KigaliTime.read(rs, "cancelled_at"),
                rs.getString("cancelled_by_name"),
                rs.getString("cancel_reason"),
                rs.getObject("supersedes_document_id", UUID.class),
                rs.getString("supersedes_serial"),
                rs.getObject("from_location_id", UUID.class),
                rs.getString("from_code"),
                rs.getString("from_name"),
                rs.getBoolean("from_bonded"),
                rs.getObject("to_location_id", UUID.class),
                rs.getString("to_code"),
                rs.getString("to_name"),
                rs.getBoolean("to_bonded"),
                rs.getObject("to_branch_id", UUID.class),
                rs.getString("to_branch_name"),
                rs.getBoolean("to_branch_bonded"),
                rs.getObject("transit_location_id", UUID.class),
                rs.getString("transit_code"),
                rs.getString("customs_reference"),
                rs.getString("note"));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class TransferNotFoundException extends RuntimeException {
        public TransferNotFoundException(UUID id) {
            super("No transfer with id " + id);
        }
    }
}
