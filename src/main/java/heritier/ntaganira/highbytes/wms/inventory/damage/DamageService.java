package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageService.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Return and damage reports: write-offs, transit losses, customer returns and quarantine releases, audited
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
import heritier.ntaganira.highbytes.wms.inventory.dispatch.GateSteps;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.ReleaseGate;
import heritier.ntaganira.highbytes.wms.inventory.ledger.ConsignmentShares;
import heritier.ntaganira.highbytes.wms.inventory.ledger.LedgerService;
import heritier.ntaganira.highbytes.wms.inventory.ledger.MovementRequest;
import heritier.ntaganira.highbytes.wms.inventory.transfer.TransferPosting;
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
 * Returns and damage, on the document engine.
 *
 * <p>One document type, four kinds of report (see {@link DamageKind}); the kind is
 * chosen when the report is raised and never changes. Every report goes down the
 * same chain (Warehouse Manager prepares, the Internal Controller verifies, the
 * Managing Director approves: the write-off threshold is an open question for the
 * client, so until it is answered every report needs all three) and is posted by
 * Finance, who is in no step of it.
 *
 * <p>Posting is one transaction: the report goes POSTED, its ticket or tickets are
 * raised, every line moves through the ledger and the tickets go POSTED. A write-off
 * leaves at the ledger's average cost; a loss in transit leaves transit at the cost
 * its own consignment was dispatched at, by the one rule receipts share
 * ({@link ConsignmentShares}); a customer return comes back at the cost the goods
 * left with; a release changes where stock is, never what it is worth.
 *
 * <p>The rules are the database's (V14). This class asks first where a plain reason
 * helps and otherwise passes the database's own through, at the statement or, for
 * the deferred checks, at commit.
 */
@Service
@Transactional(readOnly = true)
public class DamageService {

    private static final String HEADER = """
            SELECT d.id, d.branch_id, b.name AS branch_name, b.is_bonded AS branch_bonded,
                   d.serial_no, d.status, d.document_date, d.reference, d.notes, d.version,
                   d.created_by, cu.full_name AS created_by_name, d.created_at, d.submitted_at,
                   d.posted_at, d.posted_by, pu.full_name AS posted_by_name,
                   d.cancelled_at, xu.full_name AS cancelled_by_name, d.cancel_reason,
                   r.kind, r.reason_code, r.reason,
                   r.from_location_id, fl.code AS from_code, fl.name AS from_name, fl.is_bonded AS from_bonded,
                   r.to_location_id, tl.code AS to_code, tl.name AS to_name, tl.is_bonded AS to_bonded,
                   r.transfer_id, td.serial_no AS transfer_serial,
                   r.delivery_note_id, nd.serial_no AS note_serial, r.customs_reference
              FROM document d
              JOIN document_type dt     ON dt.id = d.document_type_id AND dt.code = 'DMG'
              JOIN damage_report r      ON r.document_id = d.id
              JOIN branch b             ON b.id = d.branch_id
              JOIN app_user cu          ON cu.id = d.created_by
         LEFT JOIN location fl          ON fl.id = r.from_location_id
         LEFT JOIN location tl          ON tl.id = r.to_location_id
         LEFT JOIN document td          ON td.id = r.transfer_id
         LEFT JOIN document nd          ON nd.id = r.delivery_note_id
         LEFT JOIN app_user pu          ON pu.id = d.posted_by
         LEFT JOIN app_user xu          ON xu.id = d.cancelled_by
             WHERE d.id = :id
            """;

    private static final String LINES = """
            SELECT dl.id, dl.line_no, dl.item_id, i.item_code, i.description, dl.uom_id, u.code AS uom_code,
                   bu.code AS base_uom_code, dl.quantity, dl.qty_base_uom,
                   dl.storage_bin_id, sb.bin_code, dl.to_storage_bin_id, tb.bin_code AS to_bin_code,
                   dl.transfer_line_id, xl.line_no AS transfer_line_no,
                   dl.delivery_note_line_id, nl.line_no AS note_line_no, dl.note,
                   (SELECT m.value FROM transaction_ticket t
                      JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = dl.line_no
                      JOIN stock_movement m ON m.ticket_line_id = l.id AND m.reverses_movement_id IS NULL
                     WHERE t.source_document_id = dl.document_id
                       AND t.movement_type IN ('DAMAGE', 'TRANSFER_OUT')) AS value_out,
                   (SELECT m.value FROM transaction_ticket t
                      JOIN ticket_line l ON l.ticket_id = t.document_id AND l.line_no = dl.line_no
                      JOIN stock_movement m ON m.ticket_line_id = l.id AND m.reverses_movement_id IS NULL
                     WHERE t.source_document_id = dl.document_id
                       AND t.movement_type IN ('RETURN', 'TRANSFER_IN')) AS value_in
              FROM damage_report_line dl
              JOIN item i  ON i.id = dl.item_id
              JOIN uom u   ON u.id = dl.uom_id
              JOIN uom bu  ON bu.id = i.base_uom_id
         LEFT JOIN storage_bin sb ON sb.id = dl.storage_bin_id
         LEFT JOIN storage_bin tb ON tb.id = dl.to_storage_bin_id
         LEFT JOIN transfer_order_line xl ON xl.id = dl.transfer_line_id
         LEFT JOIN delivery_note_line nl  ON nl.id = dl.delivery_note_line_id
             WHERE dl.document_id = :id
             ORDER BY dl.line_no
            """;

    private static final String LIST = """
            SELECT d.id, d.serial_no, d.status, r.kind, r.reason_code, d.document_date,
                   COALESCE(fl.code, tl.code) AS location_code,
                   COALESCE(td.serial_no, nd.serial_no) AS about,
                   (SELECT COUNT(*) FROM damage_report_line x WHERE x.document_id = d.id) AS line_count,
                   cu.full_name AS created_by_name, d.posted_at, d.created_at,
                   nx.role_name AS awaiting_role
              FROM document d
              JOIN document_type dt     ON dt.id = d.document_type_id AND dt.code = 'DMG'
              JOIN damage_report r      ON r.document_id = d.id
              JOIN app_user cu          ON cu.id = d.created_by
         LEFT JOIN location fl          ON fl.id = r.from_location_id
         LEFT JOIN location tl          ON tl.id = r.to_location_id
         LEFT JOIN document td          ON td.id = r.transfer_id
         LEFT JOIN document nd          ON nd.id = r.delivery_note_id
         LEFT JOIN LATERAL (
                   SELECT ro.name AS role_name
                     FROM workflow_step ws JOIN role ro ON ro.id = ws.required_role_id
                    WHERE ws.workflow_definition_id = d.workflow_definition_id AND d.status = 'PENDING'
                      AND NOT EXISTS (SELECT 1 FROM document_approval da
                                       WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                    ORDER BY ws.sequence_no LIMIT 1) nx ON TRUE
             WHERE d.branch_id = :branch
               AND (:kind::text IS NULL OR r.kind = :kind)
               AND (:status::text IS NULL OR d.status = :status)
             ORDER BY d.created_at DESC
             LIMIT 300
            """;

    /** The transfer or the delivery note a loss or a return is about, as the form opens it. */
    public record Subject(UUID id, String serialNo, UUID branchId, String branchName, String status, String detail) {}

    /** Everything the report's own view needs, gathered in one read-only pass. */
    public record Detail(DamageHeader header, List<DamageLineRow> lines, List<ChainStep> chain, ChainInfo chainInfo,
                         ReleaseGate gate, DamageActions actions, TransferPosting posting,
                         List<DamageLookupService.StockRow> stock) {}

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final LedgerService ledger;
    private final BranchService branches;
    private final GateSteps gateSteps;
    private final UnitConversions units;
    private final ConsignmentShares shares;
    private final PartyCheck parties;
    private final DamageLookupService lookups;

    public DamageService(JdbcClient jdbc, DocumentService documents, LedgerService ledger, BranchService branches,
                         GateSteps gateSteps, UnitConversions units, ConsignmentShares shares, PartyCheck parties,
                         DamageLookupService lookups) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.ledger = ledger;
        this.branches = branches;
        this.gateSteps = gateSteps;
        this.units = units;
        this.shares = shares;
        this.parties = parties;
        this.lookups = lookups;
    }

    // ---- reads -----------------------------------------------------------------

    /** Reports raised at this branch, newest first, optionally of one kind or one state. */
    @PreAuthorize("hasAuthority('damage.view')")
    public List<DamageRow> list(UUID branchId, DamageKind kind, String status, boolean awaitingMe) {
        Set<UUID> mine = documents.awaitingSignatureOf(DocumentKind.DMG, branchId, CurrentUser.id());
        return jdbc.sql(LIST)
                .param("branch", branchId, Types.OTHER)
                .param("kind", kind == null ? null : kind.name())
                .param("status", status == null || status.isBlank() ? null : status)
                .query((rs, n) -> new DamageRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("status"),
                        DamageKind.valueOf(rs.getString("kind")),
                        rs.getString("reason_code"),
                        rs.getObject("document_date", java.time.LocalDate.class),
                        rs.getString("location_code"),
                        rs.getString("about"),
                        rs.getInt("line_count"),
                        rs.getString("created_by_name"),
                        rs.getString("awaiting_role"),
                        false,
                        KigaliTime.read(rs, "posted_at")))
                .list().stream()
                .map(row -> row.markAwaitingMe(mine.contains(row.id())))
                .filter(row -> !awaitingMe || row.awaitingMe())
                .toList();
    }

    /** The report's header. Readable at its own branch only; a 403 elsewhere, unrecorded (a read). */
    @PreAuthorize("hasAuthority('damage.view')")
    public DamageHeader find(UUID id) {
        DamageHeader header = requireHeader(id);
        CurrentUser.requireAt("damage.view", header.branchId());
        return header;
    }

    public List<DamageLineRow> lines(UUID id) {
        return jdbc.sql(LINES).param("id", id, Types.OTHER)
                .query((rs, n) -> new DamageLineRow(
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
                        rs.getObject("to_storage_bin_id", UUID.class),
                        rs.getString("to_bin_code"),
                        rs.getObject("transfer_line_id", UUID.class),
                        (Integer) rs.getObject("transfer_line_no"),
                        rs.getObject("delivery_note_line_id", UUID.class),
                        (Integer) rs.getObject("note_line_no"),
                        rs.getString("note"),
                        rs.getBigDecimal("value_out"),
                        rs.getBigDecimal("value_in")))
                .list();
    }

    @PreAuthorize("hasAuthority('damage.view')")
    public Detail detail(UUID id) {
        DamageHeader header = find(id);
        List<DamageLineRow> lines = lines(id);
        DocumentHeader document = documents.header(id);
        List<ChainStep> chain = documents.chain(id);
        ReleaseGate gate = gate(header, chain, gateSteps.now());
        boolean open = "DRAFT".equals(header.status()) || "PENDING".equals(header.status())
                || "APPROVED".equals(header.status());
        var stock = open && header.fromLocationId() != null && header.kind() != DamageKind.TRANSIT_LOSS
                ? lookups.stockAt(header.fromLocationId(), header.branchId()) : List.<DamageLookupService.StockRow>of();
        return new Detail(header, lines, chain, documents.chainInfo(id).orElse(null), gate,
                actionsFor(header, document, chain), postingOf(id).orElse(null), stock);
    }

    ReleaseGate gate(DamageHeader h, List<ChainStep> chain, LocalDateTime now) {
        String status = h.status();
        if ("REJECTED".equals(status) || "CANCELLED".equals(status)) {
            return new ReleaseGate("VOID", h.serialNo(), List.of(), null, null, null, null, null, null,
                    status.toLowerCase(), "DMG");
        }
        if ("APPROVED".equals(status)) {
            ChainStep last = chain.isEmpty() ? null : chain.get(chain.size() - 1);
            return new ReleaseGate("RELEASED", h.serialNo(), List.of(),
                    last == null ? null : last.actorName(), last == null ? null : last.decidedAt(),
                    null, null, null, null, null, "DMG");
        }
        if ("POSTED".equals(status)) {
            return new ReleaseGate("POSTED", h.serialNo(), List.of(), null, null, null, null,
                    h.postedAt(), h.postedByName(), null, "DMG");
        }
        var waiting = gateSteps.waiting(h.id(), h.branchId(), h.createdBy(), status, h.submittedAt(), chain, now);
        return new ReleaseGate("BLOCKED", h.serialNo(), waiting, null, null, null, null, null, null, null, "DMG");
    }

    /** The movements of a posted report, every leg, in ledger order. */
    public Optional<TransferPosting> postingOf(UUID id) {
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
                 ORDER BY m.id
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> new TransferPosting.Movement(rs.getLong("id"), rs.getString("ticket_serial"),
                        rs.getString("movement_type"), rs.getInt("line_no"), rs.getString("item_code"),
                        rs.getString("location_code"), rs.getString("bin_code"), rs.getString("direction"),
                        rs.getBigDecimal("quantity_base_uom"), rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("value"), rs.getObject("business_date", java.time.LocalDate.class)))
                .list();
        return movements.isEmpty() ? Optional.empty() : Optional.of(new TransferPosting(movements));
    }

    /** The transfer a loss is raised against, for its form. A read: refused at another branch, not recorded. */
    @PreAuthorize("hasAuthority('damage.create')")
    public Subject transferSubject(UUID transferId) {
        Subject s = jdbc.sql("""
                SELECT d.id, d.serial_no, d.branch_id, b.name AS branch_name, d.status,
                       tb.name || ' · ' || tl.code AS detail
                  FROM document d
                  JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'TRF'
                  JOIN transfer_order t ON t.document_id = d.id
                  JOIN branch b         ON b.id = d.branch_id
                  JOIN location tl      ON tl.id = t.to_location_id
                  JOIN branch tb        ON tb.id = tl.branch_id
                 WHERE d.id = :id
                """)
                .param("id", transferId, Types.OTHER)
                .query((rs, n) -> new Subject(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getObject("branch_id", UUID.class), rs.getString("branch_name"), rs.getString("status"),
                        rs.getString("detail")))
                .optional().orElseThrow(() -> new DamageNotFoundException(transferId));
        CurrentUser.requireAt("damage.create", s.branchId());
        return s;
    }

    /** The delivery note a return is raised against, for its form. A read: refused at another branch, not recorded. */
    @PreAuthorize("hasAuthority('damage.create')")
    public Subject noteSubject(UUID noteId) {
        Subject s = jdbc.sql("""
                SELECT d.id, d.serial_no, d.branch_id, b.name AS branch_name, d.status, c.name AS detail
                  FROM document d
                  JOIN document_type dt         ON dt.id = d.document_type_id AND dt.code = 'DN'
                  JOIN delivery_note n            ON n.document_id = d.id
                  JOIN delivery_note_authority na ON na.note_id = n.document_id
                  JOIN customer c                 ON c.id = na.customer_id
                  JOIN branch b                 ON b.id = d.branch_id
                 WHERE d.id = :id
                """)
                .param("id", noteId, Types.OTHER)
                .query((rs, n) -> new Subject(rs.getObject("id", UUID.class), rs.getString("serial_no"),
                        rs.getObject("branch_id", UUID.class), rs.getString("branch_name"), rs.getString("status"),
                        rs.getString("detail")))
                .optional().orElseThrow(() -> new DamageNotFoundException(noteId));
        CurrentUser.requireAt("damage.create", s.branchId());
        return s;
    }

    /**
     * The customs reference the goods of a loss or a return moved under (the
     * transfer's, or the authorization's behind the delivery note), or null.
     * Customs follows the goods: the report carries that same reference, and
     * the database refuses another.
     */
    @PreAuthorize("hasAuthority('damage.view')")
    public String inheritedCustoms(DamageKind kind, UUID transferId, UUID noteId) {
        if (kind == DamageKind.TRANSIT_LOSS && transferId != null) {
            return jdbc.sql("SELECT customs_reference FROM transfer_order WHERE document_id = :id")
                    .param("id", transferId, Types.OTHER)
                    .query(String.class).optional().orElse(null);
        }
        if (kind == DamageKind.CUSTOMER_RETURN && noteId != null) {
            return jdbc.sql("""
                    SELECT na.customs_reference FROM delivery_note_authority na WHERE na.note_id = :id
                    """)
                    .param("id", noteId, Types.OTHER)
                    .query(String.class).optional().orElse(null);
        }
        return null;
    }

    /** A new report's form for a kind, with the rows that kind starts with. */
    @PreAuthorize("hasAuthority('damage.create')")
    public DamageForm blank(DamageKind kind, UUID branchId) {
        DamageForm form = new DamageForm();
        form.setKind(kind);
        form.setReasonCode(kind.reasonCodes().get(0));
        if (kind == DamageKind.WRITE_OFF || kind == DamageKind.QUARANTINE_RELEASE) {
            form.getLines().add(new DamageLineForm());
        }
        return form;
    }

    /**
     * A loss in transit against this transfer: a row per transfer line still in transit, prefilled at what
     * remains there. Refused, with the reason, when the signed-in user is a party to the transfer or nothing
     * remains. A read: nothing is recorded.
     */
    @PreAuthorize("hasAuthority('damage.create')")
    public DamageForm prefillLoss(UUID transferId) {
        Subject transfer = transferSubject(transferId);
        if (!"POSTED".equals(transfer.status())) {
            throw new ControlRefusedException(transfer.serialNo() + " is " + transfer.status()
                    + ", not dispatched. Only goods that left the source branch can be lost in transit.");
        }
        String party = parties.reason(CurrentUser.id(), DamageKind.TRANSIT_LOSS.name(), transferId, null);
        if (party != null) throw new ControlRefusedException(party);
        var lossLines = lookups.lossLines(transferId);
        if (lossLines.stream().noneMatch(l -> l.remainingBase().signum() > 0)) {
            throw new ControlRefusedException("Nothing remains in transit on " + transfer.serialNo()
                    + ": everything dispatched has been received or written off as lost.");
        }
        DamageForm form = blank(DamageKind.TRANSIT_LOSS, transfer.branchId());
        form.setTransferId(transferId);
        form.setReasonCode("LOST_IN_TRANSIT");
        form.setCustomsReference(inheritedCustoms(DamageKind.TRANSIT_LOSS, transferId, null));
        for (var line : lossLines) {
            DamageLineForm row = new DamageLineForm();
            row.setTransferLineId(line.id());
            row.setQuantity(line.remaining());
            form.getLines().add(row);
        }
        return form;
    }

    /** A customer return against this delivery note: a row per line, prefilled at what could still come back. */
    @PreAuthorize("hasAuthority('damage.create')")
    public DamageForm prefillReturn(UUID noteId) {
        Subject note = noteSubject(noteId);
        if (!"POSTED".equals(note.status())) {
            throw new ControlRefusedException("Delivery note " + note.serialNo() + " is " + note.status()
                    + ", so nothing has been delivered to bring back.");
        }
        String party = parties.reason(CurrentUser.id(), DamageKind.CUSTOMER_RETURN.name(), null, noteId);
        if (party != null) throw new ControlRefusedException(party);
        var returnLines = lookups.returnLines(noteId);
        if (returnLines.stream().noneMatch(l -> l.returnableBase().signum() > 0)) {
            throw new ControlRefusedException("Everything delivered on " + note.serialNo() + " has already come back.");
        }
        DamageForm form = blank(DamageKind.CUSTOMER_RETURN, note.branchId());
        form.setDeliveryNoteId(noteId);
        form.setReasonCode("CUSTOMER_RETURN");
        form.setCustomsReference(inheritedCustoms(DamageKind.CUSTOMER_RETURN, null, noteId));
        for (var line : returnLines) {
            DamageLineForm row = new DamageLineForm();
            row.setDeliveryNoteLineId(line.id());
            row.setQuantity(line.returnable());
            form.getLines().add(row);
        }
        return form;
    }

    /** The report as its form holds it, for editing. Reference kinds get a row per transfer or delivery line. */
    @PreAuthorize("hasAuthority('damage.create')")
    public DamageForm formFor(UUID id) {
        CurrentUser.requireAt("damage.create", documents.header(id).branchId());   // a read: refused, not recorded
        DamageHeader h = find(id);
        String party = parties.reason(CurrentUser.id(), h.kind().name(), h.transferId(), h.deliveryNoteId());
        if (party != null) throw new ControlRefusedException(party);
        List<DamageLineRow> rows = lines(id);
        String split = splitReason(h.serialNo(), h.kind(), rows);
        if (split != null) throw new ControlRefusedException(split);

        DamageForm form = new DamageForm();
        form.setId(h.id());
        form.setVersion(h.version());
        form.setKind(h.kind());
        form.setReasonCode(h.reasonCode());
        form.setReason(h.reason());
        form.setFromLocationId(h.kind() == DamageKind.TRANSIT_LOSS ? null : h.fromLocationId());
        form.setToLocationId(h.kind() == DamageKind.QUARANTINE_RELEASE ? h.toLocationId() : null);
        form.setTransferId(h.transferId());
        form.setDeliveryNoteId(h.deliveryNoteId());
        form.setCustomsReference(h.customsReference());
        form.setReference(h.reference());
        form.setNotes(h.notes());
        if (h.kind() == DamageKind.TRANSIT_LOSS) {
            Map<UUID, DamageLineRow> byLine = rows.stream().collect(Collectors.toMap(DamageLineRow::transferLineId, l -> l));
            for (var line : lookups.lossLines(h.transferId())) {
                DamageLineForm row = new DamageLineForm();
                row.setTransferLineId(line.id());
                DamageLineRow own = byLine.get(line.id());
                row.setQuantity(own == null ? BigDecimal.ZERO : own.quantity());
                row.setNote(own == null ? null : own.note());
                form.getLines().add(row);
            }
        } else if (h.kind() == DamageKind.CUSTOMER_RETURN) {
            Map<UUID, DamageLineRow> byLine = rows.stream()
                    .collect(Collectors.toMap(DamageLineRow::deliveryNoteLineId, l -> l));
            for (var line : lookups.returnLines(h.deliveryNoteId())) {
                DamageLineForm row = new DamageLineForm();
                row.setDeliveryNoteLineId(line.id());
                DamageLineRow own = byLine.get(line.id());
                row.setQuantity(own == null ? BigDecimal.ZERO : own.quantity());
                row.setToStorageBinId(own == null ? null : own.toStorageBinId());
                row.setNote(own == null ? null : own.note());
                form.getLines().add(row);
            }
        } else {
            for (DamageLineRow row : rows) {
                DamageLineForm line = new DamageLineForm();
                line.setItemId(row.itemId());
                line.setUomId(row.uomId());
                line.setQuantity(row.quantity());
                line.setStorageBinId(row.storageBinId());
                line.setToStorageBinId(row.toStorageBinId());
                line.setNote(row.note());
                form.getLines().add(line);
            }
        }
        return form;
    }

    /**
     * Why a draft cannot be edited through the form, or null: the form of a loss or a return holds one row per
     * transfer or delivery line, so a report with two lines against one would come back as its last and saving it
     * would drop the other.
     */
    static String splitReason(String serial, DamageKind kind, List<DamageLineRow> rows) {
        java.util.function.Function<DamageLineRow, Object> ref = switch (kind) {
            case TRANSIT_LOSS -> DamageLineRow::transferLineId;
            case CUSTOMER_RETURN -> DamageLineRow::deliveryNoteLineId;
            default -> null;
        };
        if (ref == null) return null;
        long distinct = rows.stream().map(ref).distinct().count();
        if (distinct == rows.size()) return null;
        return serial + " has more than one line against the same line, which this form cannot show. Saving it here "
                + "would drop the others. Post it as it stands, or cancel it and raise a new report.";
    }

    // ---- writes ----------------------------------------------------------------------

    /** Raises a draft. The database dates it today, binds the chain in force today and sets the locations it owns. */
    @Transactional
    @PreAuthorize("hasAuthority('damage.create')")
    public UUID create(DamageForm form) {
        DamageKind kind = requireKind(form);
        UUID branchId = branchOf(form);
        documents.requireRightAt(DocumentKind.DMG, branchId, "damage.create", "Raise a " + kind.title().toLowerCase());
        requireIndependent(form, "Raise a " + kind.title().toLowerCase());
        try {
            OpenedDocument opened = documents.open(DocumentKind.DMG, branchId, form.getReference(), form.getNotes(), null);
            jdbc.sql("""
                    INSERT INTO damage_report (document_id, kind, reason_code, reason, from_location_id, to_location_id,
                                               transfer_id, delivery_note_id, customs_reference)
                    VALUES (:id, :kind, :code, :reason, :from, :to, :transfer, :note, :customs)
                    """)
                    .param("id", opened.id(), Types.OTHER)
                    .param("kind", kind.name())
                    .params(headerParams(form))
                    .param("transfer", form.getTransferId(), Types.OTHER)
                    .param("note", form.getDeliveryNoteId(), Types.OTHER)
                    .update();
            insertLines(opened.id(), form);

            DamageHeader after = requireHeader(opened.id());
            documents.auditInTransaction("DMG · " + after.serialNo(), opened.id(), AuditAction.CREATE,
                    snapshot(null, null, after, lines(opened.id())), branches.findById(branchId).orElse(null));
            return opened.id();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
    }

    /** Edits a draft, provided nobody changed it since the form was opened; whether it is still a draft is the database's. */
    @Transactional
    @PreAuthorize("hasAuthority('damage.create')")
    public void update(UUID id, DamageForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "damage.create", "Edit");
        if (form.getVersion() == null) {
            throw documents.refused(d, "Edit", "This form did not say which version of " + d.serialNo()
                    + " it was opened from. Reload the report and try again.");
        }
        DamageHeader before = requireHeader(id);
        if (form.getKind() != null && form.getKind() != before.kind()) {
            throw documents.refused(d, "Edit", d.serialNo() + " is a " + before.kind().title().toLowerCase()
                    + ". The kind of a report is fixed when it is raised: cancel it and raise another.");
        }
        form.setKind(before.kind());
        form.setTransferId(before.transferId());
        form.setDeliveryNoteId(before.deliveryNoteId());
        String party = parties.reason(CurrentUser.id(), before.kind().name(), before.transferId(), before.deliveryNoteId());
        if (party != null) throw documents.refused(d, "Edit", party);
        List<DamageLineRow> beforeLines = lines(id);
        String split = splitReason(d.serialNo(), before.kind(), beforeLines);
        if (split != null) throw documents.refused(d, "Edit", split);
        try {
            documents.updateDraft(d, form.getVersion(), form.getReference(), form.getNotes());
            jdbc.sql("DELETE FROM damage_report_line WHERE document_id = :id").param("id", id, Types.OTHER).update();
            jdbc.sql("""
                    UPDATE damage_report SET reason_code = :code, reason = :reason,
                           from_location_id = COALESCE(:from, from_location_id),
                           to_location_id = COALESCE(:to, to_location_id), customs_reference = :customs
                     WHERE document_id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .params(headerParams(form))
                    .update();
            insertLines(id, form);
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Edit", e);
        } catch (ControlRefusedException e) {
            throw documents.refused(d, "Edit", e.getMessage());
        }
        DamageHeader after = requireHeader(id);
        AuditSnapshot snapshot = snapshot(before, beforeLines, after, lines(id));
        if (!snapshot.unchanged()) {
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, snapshot, d.branch());
        }
    }

    @Transactional
    @PreAuthorize("hasAnyAuthority('damage.create','damage.verify','damage.approve')")
    public String submit(UUID id) {
        return documents.submit(id).status();
    }

    @Transactional
    @PreAuthorize("hasAnyAuthority('damage.create','damage.verify','damage.approve')")
    public String sign(UUID id, boolean approve, String comment) {
        return documents.sign(id, approve, comment).status();
    }

    /** Cancels a draft, pending or approved report. A posted one is refused: what moved is corrected by a reversal. */
    @Transactional
    @PreAuthorize("hasAuthority('damage.create')")
    public void cancel(UUID id, String reason) {
        documents.cancel(id, reason);
    }

    /**
     * Posts an approved report: it goes POSTED, its ticket or tickets are raised and every line moves through
     * the ledger, the tickets go POSTED. One transaction. Returns the serial of the first ticket.
     */
    @Transactional
    @PreAuthorize("hasAuthority('damage.post')")
    public String post(UUID id) {
        DocumentHeader early = documents.header(id);
        documents.requireRight(early, "damage.post", "Post");
        StepCheck check = documents.canPost(early, documents.chain(id));
        if (!check.allowed()) throw documents.refused(early, "Post", check.reason());
        DamageHeader before = requireHeader(id);
        String notIndependent = postBlocker(before);
        if (notIndependent != null) throw documents.refused(early, "Post", notIndependent);

        DocumentHeader d = documents.beginPost(id);      // locks; APPROVED -> POSTED by Finance, judged by the database
        DamageHeader h = requireHeader(id);
        List<DamageLineRow> lines = lines(id);
        UUID poster = CurrentUser.id();
        DamageKind kind = h.kind();

        List<OpenedDocument> tickets = new ArrayList<>();
        BigDecimal value = BigDecimal.ZERO;
        int movements = 0;
        try {
            List<LedgerService.Place> places = new ArrayList<>();
            for (DamageLineRow l : lines) {
                if (h.fromLocationId() != null) places.add(new LedgerService.Place(l.itemId(), h.fromLocationId()));
                if (h.toLocationId() != null) places.add(new LedgerService.Place(l.itemId(), h.toLocationId()));
            }
            ledger.lockPlaces(places);

            switch (kind) {
                case WRITE_OFF, TRANSIT_LOSS -> {
                    OpenedDocument out = openTicket(d, "DAMAGE", "OUT", h.fromLocationId(), null, h);
                    tickets.add(out);
                    List<UUID> ticketLines = new ArrayList<>();
                    for (DamageLineRow l : lines) ticketLines.add(insertTicketLine(out.id(), l, l.storageBinId()));
                    for (int i = 0; i < lines.size(); i++) {
                        DamageLineRow l = lines.get(i);
                        var left = kind == DamageKind.TRANSIT_LOSS
                                // The consignment leaves transit at its own cost, by the rule receipts share.
                                ? ledger.post(MovementRequest.issueAt(out.id(), ticketLines.get(i), d.branchId(),
                                        l.itemId(), h.fromLocationId(), null, l.quantityBase(),
                                        shares.transitShare(l.transferLineId(), l.quantityBase())), poster)
                                // A write-off leaves at the ledger's average cost.
                                : ledger.post(MovementRequest.issue(out.id(), ticketLines.get(i), d.branchId(),
                                        l.itemId(), h.fromLocationId(), l.storageBinId(), l.quantityBase()), poster);
                        value = value.add(left.value());
                        movements++;
                    }
                }
                case CUSTOMER_RETURN -> {
                    OpenedDocument in = openTicket(d, "RETURN", "IN", null, h.toLocationId(), h);
                    tickets.add(in);
                    List<UUID> ticketLines = new ArrayList<>();
                    for (DamageLineRow l : lines) ticketLines.add(insertTicketLine(in.id(), l, l.toStorageBinId()));
                    for (int i = 0; i < lines.size(); i++) {
                        DamageLineRow l = lines.get(i);
                        // Back at the cost it left with: the same cumulative rule, per delivery note line.
                        var back = ledger.post(MovementRequest.receipt(in.id(), ticketLines.get(i), d.branchId(),
                                l.itemId(), h.toLocationId(), l.toStorageBinId(), l.quantityBase(),
                                shares.returnShare(l.deliveryNoteLineId(), l.quantityBase())), poster);
                        value = value.add(back.value());
                        movements++;
                    }
                }
                case QUARANTINE_RELEASE -> {
                    OpenedDocument out = openTicket(d, "TRANSFER_OUT", "OUT", h.fromLocationId(), null, h);
                    OpenedDocument in = openTicket(d, "TRANSFER_IN", "IN", null, h.toLocationId(), h);
                    tickets.add(out);
                    tickets.add(in);
                    List<UUID> outLines = new ArrayList<>();
                    List<UUID> inLines = new ArrayList<>();
                    for (DamageLineRow l : lines) {
                        outLines.add(insertTicketLine(out.id(), l, l.storageBinId()));
                        inLines.add(insertTicketLine(in.id(), l, l.toStorageBinId()));
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        DamageLineRow l = lines.get(i);
                        var left = ledger.post(MovementRequest.issue(out.id(), outLines.get(i), d.branchId(),
                                l.itemId(), h.fromLocationId(), l.storageBinId(), l.quantityBase()), poster);
                        // A release changes where stock is, never what it is worth: in at exactly what left.
                        ledger.post(MovementRequest.receipt(in.id(), inLines.get(i), d.branchId(), l.itemId(),
                                h.toLocationId(), l.toStorageBinId(), l.quantityBase(), left.value()), poster);
                        value = value.add(left.value());
                        movements += 2;
                    }
                }
            }
        } catch (ContentionException e) {
            throw e;        // a lost race, not a refusal: logged by the engine, never audited as REJECT
        } catch (ControlRefusedException e) {
            throw documents.refused(d, "Post", e.getMessage());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Post", e);
        }

        for (OpenedDocument ticket : tickets) documents.postDerived(d, ticket.id());

        documents.auditPosted(d, AuditSnapshot.of()
                .field("Status", "APPROVED", "POSTED")
                .value("Kind", kind.title())
                .value("From", h.fromCode())
                .value("To", h.toCode())
                .value("About", h.transferSerial() != null ? h.transferSerial() : h.deliveryNoteSerial())
                .value("Tickets", tickets.stream().map(OpenedDocument::serialNo).collect(Collectors.joining(", ")))
                .value("Ledger movements", movements)
                .value(kind == DamageKind.CUSTOMER_RETURN ? "Value brought back (RWF)"
                        : kind == DamageKind.QUARANTINE_RELEASE ? "Value released (RWF)" : "Value written off (RWF)", value));
        return tickets.get(0).serialNo();
    }

    // ---- helpers -----------------------------------------------------------------------

    private DamageActions actionsFor(DamageHeader h, DocumentHeader d, List<ChainStep> chain) {
        UUID branch = h.branchId();
        boolean create = CurrentUser.holdsAt("damage.create", branch);
        boolean signer = create || CurrentUser.holdsAt("damage.verify", branch) || CurrentUser.holdsAt("damage.approve", branch);
        boolean poster = CurrentUser.holdsAt("damage.post", branch);
        String status = h.status();

        StepCheck submit = documents.canSubmit(d, chain);
        StepCheck sign = documents.canSign(d, chain);
        StepCheck cancel = documents.canCancel(d);
        StepCheck post = documents.canPost(d, chain);

        String party = parties.reason(CurrentUser.id(), h.kind().name(), h.transferId(), h.deliveryNoteId());
        boolean draft = "DRAFT".equals(status);
        // Editing a draft makes the editor the author of its lines: a party to the transfer or the delivery may not.
        boolean canEdit = draft && create && party == null;
        String editReason = draft && create ? party : null;

        String postReason = null;
        boolean canPost = false;
        if ("APPROVED".equals(status) && poster) {
            String blocker = post.allowed() ? postBlocker(h) : post.reason();
            canPost = blocker == null;
            postReason = blocker;
        }
        return new DamageActions(
                canEdit,
                editReason,
                submit.allowed(),
                draft && !submit.allowed() && signer ? submit.reason() : null,
                sign.allowed(),
                "PENDING".equals(status) && !sign.allowed() && signer ? sign.reason() : null,
                sign.step(),
                cancel.allowed(),
                "POSTED".equals(status) && (create || poster) ? h.serialNo() + " has been posted, so it cannot be "
                        + "cancelled: the stock has moved. A wrong report is corrected by a reversing document "
                        + "(not built yet)." : null,
                canPost,
                postReason);
    }

    /**
     * Why the signed-in user may not post this report for want of independence of what it is about, or null:
     * neither the poster nor anyone who entered a line of it may be a party to the transfer or the delivery.
     * (Raised or signed by the poster is {@link DocumentService#canPost}'s.)
     */
    String postBlocker(DamageHeader h) {
        if (h.kind() != DamageKind.TRANSIT_LOSS && h.kind() != DamageKind.CUSTOMER_RETURN) return null;
        String party = parties.reason(CurrentUser.id(), h.kind().name(), h.transferId(), h.deliveryNoteId());
        if (party != null) return party;
        record Author(int line, String name) {}
        return jdbc.sql("""
                SELECT dl.line_no, u.full_name
                  FROM damage_report_line dl JOIN app_user u ON u.id = dl.entered_by
                 WHERE dl.document_id = :id
                   AND dmg_party_of(dl.entered_by, :kind::text, :transfer::uuid, :note::uuid) IS NOT NULL
                 ORDER BY dl.line_no LIMIT 1
                """)
                .param("id", h.id(), Types.OTHER)
                .param("kind", h.kind().name())
                .param("transfer", h.transferId(), Types.OTHER)
                .param("note", h.deliveryNoteId(), Types.OTHER)
                .query((rs, n) -> new Author(rs.getInt("line_no"), rs.getString("full_name")))
                .optional()
                .map(a -> "Line " + a.line() + " of " + h.serialNo() + " was entered by " + a.name()
                        + ", who is behind " + (h.transferSerial() != null ? "transfer " + h.transferSerial()
                                : "delivery note " + h.deliveryNoteSerial())
                        + ". A report is written by an independent person, so it cannot be posted as it stands: "
                        + "cancel it and have it raised again.")
                .orElse(null);
    }

    private DamageKind requireKind(DamageForm form) {
        if (form.getKind() == null) throw new ControlRefusedException("Choose what kind of report this is.");
        return form.getKind();
    }

    /** The branch a report belongs to: where the stock sits, or the branch of the transfer or delivery it is about. */
    private UUID branchOf(DamageForm form) {
        return switch (form.getKind()) {
            case WRITE_OFF, QUARANTINE_RELEASE -> locationBranch(form.getFromLocationId(),
                    "Choose the location the stock comes from.");
            case TRANSIT_LOSS -> documentBranch(form.getTransferId(), "Choose the transfer the loss is about.");
            case CUSTOMER_RETURN -> documentBranch(form.getDeliveryNoteId(), "Choose the delivery note the goods came back against.");
        };
    }

    private UUID locationBranch(UUID locationId, String missing) {
        if (locationId == null) throw new ControlRefusedException(missing);
        return jdbc.sql("SELECT branch_id FROM location WHERE id = :id")
                .param("id", locationId, Types.OTHER).query(UUID.class).optional()
                .orElseThrow(() -> new ControlRefusedException("That location does not exist."));
    }

    private UUID documentBranch(UUID documentId, String missing) {
        if (documentId == null) throw new ControlRefusedException(missing);
        return jdbc.sql("SELECT branch_id FROM document WHERE id = :id")
                .param("id", documentId, Types.OTHER).query(UUID.class).optional()
                .orElseThrow(() -> new ControlRefusedException("That document does not exist."));
    }

    /** A loss or a return is not raised, or its lines entered, by anyone behind the transfer or the delivery. */
    private void requireIndependent(DamageForm form, String attempt) {
        if (form.getKind() != DamageKind.TRANSIT_LOSS && form.getKind() != DamageKind.CUSTOMER_RETURN) return;
        String party = parties.reason(CurrentUser.id(), form.getKind().name(), form.getTransferId(), form.getDeliveryNoteId());
        if (party != null) {
            UUID about = form.getKind() == DamageKind.TRANSIT_LOSS ? form.getTransferId() : form.getDeliveryNoteId();
            throw documents.refused(documents.header(about), attempt, party);
        }
    }

    private Map<String, Object> headerParams(DamageForm form) {
        var params = new HashMap<String, Object>();
        // A loss and a return take no location from the form: the database sets the transit or quarantine location.
        boolean own = form.getKind() == DamageKind.WRITE_OFF || form.getKind() == DamageKind.QUARANTINE_RELEASE;
        params.put("code", form.getReasonCode());
        params.put("reason", form.getReason());
        params.put("from", own ? form.getFromLocationId() : null);
        params.put("to", form.getKind() == DamageKind.QUARANTINE_RELEASE ? form.getToLocationId() : null);
        params.put("customs", form.getCustomsReference());
        return params;
    }

    private DamageHeader requireHeader(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new DamageNotFoundException(id));
    }

    /**
     * Lines numbered by position. A reference row (a transit loss or a return) whose quantity is zero is left
     * off; its item and unit are the referenced line's. The quantity asked for is checked against what remains
     * in transit, or could still come back, so the reason is plain before anyone signs: the database judges it
     * again at posting, counting what has been posted by then.
     */
    private void insertLines(UUID documentId, DamageForm form) {
        DamageKind kind = form.getKind();
        Map<UUID, BigDecimal> asked = new HashMap<>();
        int lineNo = 0;
        for (DamageLineForm row : form.getLines()) {
            UUID item;
            UUID uom;
            UUID tline = null;
            UUID nline = null;
            if (kind == DamageKind.TRANSIT_LOSS) {
                if (row.isBlank()) continue;
                record Serves(UUID item, UUID uom) {}
                Serves s = jdbc.sql("SELECT item_id, uom_id FROM transfer_order_line WHERE id = :id AND document_id = :transfer")
                        .param("id", row.getTransferLineId(), Types.OTHER)
                        .param("transfer", form.getTransferId(), Types.OTHER)
                        .query((rs, n) -> new Serves(rs.getObject("item_id", UUID.class), rs.getObject("uom_id", UUID.class)))
                        .optional().orElseThrow(() -> new ControlRefusedException(
                                "A row names a line that is not one of this transfer's."));
                item = s.item();
                uom = s.uom();
                tline = row.getTransferLineId();
            } else if (kind == DamageKind.CUSTOMER_RETURN) {
                if (row.isBlank()) continue;
                record Serves(UUID item, UUID uom) {}
                Serves s = jdbc.sql("SELECT item_id, uom_id FROM delivery_note_line WHERE id = :id AND document_id = :note")
                        .param("id", row.getDeliveryNoteLineId(), Types.OTHER)
                        .param("note", form.getDeliveryNoteId(), Types.OTHER)
                        .query((rs, n) -> new Serves(rs.getObject("item_id", UUID.class), rs.getObject("uom_id", UUID.class)))
                        .optional().orElseThrow(() -> new ControlRefusedException(
                                "A row names a line that is not one of this delivery note's."));
                item = s.item();
                uom = s.uom();
                nline = row.getDeliveryNoteLineId();
            } else {
                if (row.getItemId() == null && row.isBlank()) continue;      // an empty row is no line
                if (row.getItemId() == null || row.getUomId() == null || row.getQuantity() == null
                        || row.getQuantity().signum() <= 0) {
                    throw new ControlRefusedException("Line " + (lineNo + 1) + " needs an item, a unit and a quantity "
                            + "above zero, or must be removed.");
                }
                item = row.getItemId();
                uom = row.getUomId();
            }
            BigDecimal base = units.baseQuantity(item, uom, row.getQuantity());
            if (tline != null || nline != null) {
                UUID key = tline != null ? tline : nline;
                BigDecimal total = asked.merge(key, base, BigDecimal::add);
                String over = tline != null ? lossOver(tline, total) : returnOver(nline, total);
                if (over != null) throw new ControlRefusedException(over);
            }
            lineNo++;
            jdbc.sql("""
                    INSERT INTO damage_report_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom,
                                                    storage_bin_id, to_storage_bin_id, transfer_line_id,
                                                    delivery_note_line_id, note, entered_by)
                    VALUES (:doc, :lineNo, :item, :uom, :quantity, :base, :bin, :toBin, :tline, :nline, :note, :enteredBy)
                    """)
                    .param("doc", documentId, Types.OTHER)
                    .param("lineNo", (short) lineNo)
                    .param("item", item, Types.OTHER)
                    .param("uom", uom, Types.OTHER)
                    .param("quantity", row.getQuantity())
                    .param("base", base)
                    .param("bin", row.getStorageBinId(), Types.OTHER)
                    .param("toBin", row.getToStorageBinId(), Types.OTHER)
                    .param("tline", tline, Types.OTHER)
                    .param("nline", nline, Types.OTHER)
                    .param("note", row.getNote())
                    .param("enteredBy", CurrentUser.id(), Types.OTHER)
                    .update();
        }
    }

    /** A plain reason when {@code wanted} base units of a transfer line exceed what remains in transit, else null. */
    private String lossOver(UUID transferLineId, BigDecimal wanted) {
        record Position(String transfer, int line, BigDecimal sent, BigDecimal received, BigDecimal lost, BigDecimal left) {}
        Position p = jdbc.sql("""
                SELECT td.serial_no, l.line_no, p.dispatched_base, p.received_base, p.written_off_base, p.in_transit_base
                  FROM transfer_order_line l
                  JOIN transfer_line_position p ON p.transfer_line_id = l.id
                  JOIN document td ON td.id = l.document_id
                 WHERE l.id = :id
                """)
                .param("id", transferLineId, Types.OTHER)
                .query((rs, n) -> new Position(rs.getString("serial_no"), rs.getInt("line_no"),
                        rs.getBigDecimal("dispatched_base"), rs.getBigDecimal("received_base"),
                        rs.getBigDecimal("written_off_base"), rs.getBigDecimal("in_transit_base")))
                .optional().orElse(null);
        if (p == null) {
            return "The transfer has not been dispatched, so nothing of it is in transit.";
        }
        if (wanted.compareTo(p.left()) <= 0) return null;
        return "Line " + p.line() + " of " + p.transfer() + " dispatched " + plain(p.sent()) + "; "
                + plain(p.received()) + " has been received and " + plain(p.lost()) + " already written off, so only "
                + plain(p.left()) + " remains in transit, and this report writes off " + plain(wanted)
                + ". More cannot be lost than is in transit.";
    }

    /** A plain reason when {@code wanted} base units of a delivery line exceed what could still come back, else null. */
    private String returnOver(UUID noteLineId, BigDecimal wanted) {
        record Position(String note, int line, BigDecimal sent, BigDecimal back) {}
        Position p = jdbc.sql("""
                SELECT nd.serial_no, l.line_no, l.qty_base_uom, delivery_line_returned(l.id) AS back
                  FROM delivery_note_line l JOIN document nd ON nd.id = l.document_id
                 WHERE l.id = :id
                """)
                .param("id", noteLineId, Types.OTHER)
                .query((rs, n) -> new Position(rs.getString("serial_no"), rs.getInt("line_no"),
                        rs.getBigDecimal("qty_base_uom"), rs.getBigDecimal("back")))
                .single();
        BigDecimal left = p.sent().subtract(p.back());
        if (wanted.compareTo(left) <= 0) return null;
        return "Line " + p.line() + " of delivery note " + p.note() + " delivered " + plain(p.sent()) + " and "
                + plain(p.back()) + " has already come back, so at most " + plain(left) + " can be returned, and this "
                + "report returns " + plain(wanted) + ". More cannot come back than was delivered.";
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private OpenedDocument openTicket(DocumentHeader d, String type, String direction, UUID from, UUID to,
                                      DamageHeader h) {
        OpenedDocument ticket = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);
        jdbc.sql("""
                INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id,
                                                to_location_id, source_document_id, customs_reference)
                VALUES (:id, :type, :direction, :from, :to, :source, :customs)
                """)
                .param("id", ticket.id(), Types.OTHER)
                .param("type", type)
                .param("direction", direction)
                .param("from", from, Types.OTHER)
                .param("to", to, Types.OTHER)
                .param("source", h.id(), Types.OTHER)
                .param("customs", h.customsReference())
                .update();
        return ticket;
    }

    private UUID insertTicketLine(UUID ticketId, DamageLineRow l, UUID bin) {
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

    private AuditSnapshot snapshot(DamageHeader before, List<DamageLineRow> beforeLines,
                                   DamageHeader after, List<DamageLineRow> afterLines) {
        AuditSnapshot s = AuditSnapshot.of();
        s.field("Kind",              before == null ? null : before.kind().title(), after.kind().title());
        s.field("Reason code",       before == null ? null : DamageKind.reasonLabel(before.reasonCode()),
                                     DamageKind.reasonLabel(after.reasonCode()));
        s.field("Reason",            before == null ? null : before.reason(), after.reason());
        s.field("From",              before == null ? null : before.fromCode(), after.fromCode());
        s.field("To",                before == null ? null : before.toCode(), after.toCode());
        s.field("Transfer",          before == null ? null : before.transferSerial(), after.transferSerial());
        s.field("Delivery note",     before == null ? null : before.deliveryNoteSerial(), after.deliveryNoteSerial());
        s.field("Customs reference", before == null ? null : before.customsReference(), after.customsReference());
        s.field("Reference",         before == null ? null : before.reference(), after.reference());
        s.field("Notes",             before == null ? null : before.notes(), after.notes());
        s.field("Lines",             beforeLines == null ? null : describe(beforeLines), describe(afterLines));
        return s;
    }

    private static String describe(List<DamageLineRow> lines) {
        return lines.stream().map(l -> l.lineNo() + ": " + l.itemCode() + " x "
                        + l.quantity().stripTrailingZeros().toPlainString() + " " + l.uomCode()
                        + (l.binCode() == null ? "" : ", from bin " + l.binCode())
                        + (l.toBinCode() == null ? "" : ", to bin " + l.toBinCode()))
                .collect(Collectors.joining("; "));
    }

    private DamageHeader mapHeader(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DamageHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"),
                rs.getBoolean("branch_bonded"),
                rs.getString("serial_no"),
                rs.getString("status"),
                rs.getObject("document_date", java.time.LocalDate.class),
                rs.getString("reference"),
                rs.getString("notes"),
                rs.getInt("version"),
                rs.getObject("created_by", UUID.class),
                rs.getString("created_by_name"),
                KigaliTime.read(rs, "created_at"),
                KigaliTime.read(rs, "submitted_at"),
                KigaliTime.read(rs, "posted_at"),
                rs.getObject("posted_by", UUID.class),
                rs.getString("posted_by_name"),
                KigaliTime.read(rs, "cancelled_at"),
                rs.getString("cancelled_by_name"),
                rs.getString("cancel_reason"),
                DamageKind.valueOf(rs.getString("kind")),
                rs.getString("reason_code"),
                rs.getString("reason"),
                rs.getObject("from_location_id", UUID.class),
                rs.getString("from_code"),
                rs.getString("from_name"),
                rs.getBoolean("from_bonded"),
                rs.getObject("to_location_id", UUID.class),
                rs.getString("to_code"),
                rs.getString("to_name"),
                rs.getBoolean("to_bonded"),
                rs.getObject("transfer_id", UUID.class),
                rs.getString("transfer_serial"),
                rs.getObject("delivery_note_id", UUID.class),
                rs.getString("note_serial"),
                rs.getString("customs_reference"));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class DamageNotFoundException extends RuntimeException {
        public DamageNotFoundException(UUID id) {
            super("No return or damage report with id " + id);
        }
    }
}
