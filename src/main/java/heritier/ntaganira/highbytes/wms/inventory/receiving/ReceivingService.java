package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : ReceivingService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Goods received notes: draft, submit, sign, cancel and post to the ledger, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.ContentionException;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Goods received notes, on the document engine.
 *
 * <p>The note is a draft the storekeeping side edits freely. Submitting it
 * signs step 1 of the chain it was bound to on the day it was created; each
 * later step signs in turn; and once every step has signed it is APPROVED and
 * a person outside the chain (Finance, {@code receiving.post}) posts it. Posting
 * is one transaction: the note goes POSTED, a transaction ticket is raised
 * with a line per receipt line, one ledger movement is written per ticket line,
 * {@code stock_balance} moves with it, and the ticket goes POSTED. If any of
 * it is refused, all of it is.
 *
 * <p>Nothing here decides who signs: the chain is read from the document's
 * bound definition (the 2026 policy chain or the 2027 restructured one), and
 * the database judges the order, the roles, the creator's exclusion and the
 * poster's independence.
 */
@Service
@Transactional(readOnly = true)
public class ReceivingService {

    private static final String HEADER = """
            SELECT d.id, d.branch_id, b.name AS branch_name, b.is_bonded AS branch_bonded,
                   d.serial_no, d.status, d.document_date, d.reference, d.notes, d.version,
                   d.created_by, cu.full_name AS created_by_name, d.created_at, d.submitted_at,
                   d.approved_at, d.posted_at, d.posted_by, pu.full_name AS posted_by_name,
                   d.cancelled_at, xu.full_name AS cancelled_by_name, d.cancel_reason,
                   d.supersedes_document_id, sd.serial_no AS supersedes_serial,
                   g.supplier_id, s.name AS supplier_name,
                   g.location_id, l.code AS location_code, l.name AS location_name, l.is_bonded AS location_bonded,
                   g.supplier_delivery_note_no, g.supplier_invoice_no, g.purchase_order_no, g.customs_reference,
                   g.currency_code, g.exchange_rate,
                   g.freight_rwf, g.duty_rwf, g.clearing_rwf, g.demurrage_rwf
              FROM document d
              JOIN document_type dt        ON dt.id = d.document_type_id AND dt.code = 'GRN'
              JOIN goods_received_note g   ON g.document_id = d.id
              JOIN branch b                ON b.id = d.branch_id
              JOIN supplier s              ON s.id = g.supplier_id
              JOIN location l              ON l.id = g.location_id
              JOIN app_user cu             ON cu.id = d.created_by
         LEFT JOIN app_user pu             ON pu.id = d.posted_by
         LEFT JOIN app_user xu             ON xu.id = d.cancelled_by
         LEFT JOIN document sd             ON sd.id = d.supersedes_document_id
             WHERE d.id = :id
            """;

    private static final String LINES = """
            SELECT gl.id, gl.line_no, gl.item_id, i.item_code, i.description, i.product_type,
                   gl.uom_id, u.code AS uom_code, bu.code AS base_uom_code,
                   gl.quantity, gl.qty_base_uom, gl.unit_price, gl.storage_bin_id, sb.bin_code,
                   gl.measured_thickness_mm, gl.supplier_quantity_base, gl.note
              FROM goods_received_line gl
              JOIN item i   ON i.id = gl.item_id
              JOIN uom u    ON u.id = gl.uom_id
              JOIN uom bu   ON bu.id = i.base_uom_id
         LEFT JOIN storage_bin sb ON sb.id = gl.storage_bin_id
             WHERE gl.document_id = :id
             ORDER BY gl.line_no
            """;

    private static final String LIST = """
            SELECT d.id, d.serial_no, d.status, d.document_date, s.name AS supplier_name,
                   l.code AS location_code, (b.is_bonded OR l.is_bonded) AS bonded,
                   (SELECT COUNT(*) FROM goods_received_line gl WHERE gl.document_id = d.id) AS line_count,
                   (SELECT COALESCE(SUM(round(gl.quantity * gl.unit_price * g.exchange_rate, 2)), 0)
                      FROM goods_received_line gl WHERE gl.document_id = d.id) AS invoice_rwf,
                   cu.full_name AS created_by_name, nx.role_name AS awaiting_role
              FROM document d
              JOIN document_type dt      ON dt.id = d.document_type_id AND dt.code = 'GRN'
              JOIN branch b              ON b.id = d.branch_id
              JOIN goods_received_note g ON g.document_id = d.id
              JOIN supplier s            ON s.id = g.supplier_id
              JOIN location l            ON l.id = g.location_id
              JOIN app_user cu           ON cu.id = d.created_by
         LEFT JOIN LATERAL (
                   SELECT r.name AS role_name
                     FROM workflow_step ws JOIN role r ON r.id = ws.required_role_id
                    WHERE ws.workflow_definition_id = d.workflow_definition_id
                      AND d.status = 'PENDING'
                      AND NOT EXISTS (SELECT 1 FROM document_approval da
                                       WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                    ORDER BY ws.sequence_no LIMIT 1) nx ON TRUE
             WHERE d.branch_id = :branch
               AND (:status::text IS NULL OR d.status = :status)
             ORDER BY d.created_at DESC
             LIMIT 300
            """;

    /** Everything the view needs, gathered in one read-only pass. */
    public record Detail(GrnHeader header, List<GrnLineRow> lines, List<ChainStep> chain, ChainInfo chainInfo,
                         LandedCost.Result landed, GrnActions actions, GrnPosting posting) {}

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final LedgerService ledger;
    private final AuditService audit;
    private final BranchService branches;

    public ReceivingService(JdbcClient jdbc, DocumentService documents, LedgerService ledger,
                            AuditService audit, BranchService branches) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.ledger = ledger;
        this.audit = audit;
        this.branches = branches;
    }

    // ---- reads -----------------------------------------------------------

    @PreAuthorize("hasAuthority('receiving.view')")
    public List<GrnRow> list(UUID branchId, String status, boolean awaitingMe) {
        UUID me = CurrentUser.id();
        Set<UUID> mine = documents.awaitingSignatureOf(DocumentKind.GRN, branchId, me);
        return jdbc.sql(LIST)
                .param("branch", branchId, Types.OTHER)
                .param("status", status == null || status.isBlank() ? null : status)
                .query((rs, n) -> new GrnRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("status"),
                        rs.getObject("document_date", java.time.LocalDate.class),
                        rs.getString("supplier_name"),
                        rs.getString("location_code"),
                        rs.getBoolean("bonded"),
                        rs.getInt("line_count"),
                        rs.getBigDecimal("invoice_rwf"),
                        rs.getString("created_by_name"),
                        rs.getString("awaiting_role"),
                        false))
                .list().stream()
                .map(row -> row.markAwaitingMe(mine.contains(row.id())))
                .filter(row -> !awaitingMe || row.awaitingMe())
                .toList();
    }

    /** The note's header; a 404 for none, a 403 unless the viewer may read receipts at its branch. */
    @PreAuthorize("hasAuthority('receiving.view')")
    public GrnHeader find(UUID id) {
        GrnHeader header = jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new GrnNotFoundException(id));
        CurrentUser.requireAt("receiving.view", header.branchId());
        return header;
    }

    public List<GrnLineRow> lines(UUID id) {
        return jdbc.sql(LINES).param("id", id, Types.OTHER)
                .query((rs, n) -> new GrnLineRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getString("product_type"),
                        rs.getObject("uom_id", UUID.class),
                        rs.getString("uom_code"),
                        rs.getString("base_uom_code"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("qty_base_uom"),
                        rs.getBigDecimal("unit_price"),
                        rs.getObject("storage_bin_id", UUID.class),
                        rs.getString("bin_code"),
                        rs.getBigDecimal("measured_thickness_mm"),
                        rs.getBigDecimal("supplier_quantity_base"),
                        rs.getString("note")))
                .list();
    }

    /** Everything the view shows, with the actions the viewer may take and why not for the rest. */
    @PreAuthorize("hasAuthority('receiving.view')")
    public Detail detail(UUID id) {
        GrnHeader header = find(id);
        List<GrnLineRow> lines = lines(id);
        DocumentHeader document = documents.header(id);
        List<ChainStep> chain = documents.chain(id);
        return new Detail(header, lines, chain, documents.chainInfo(id).orElse(null),
                allocation(header, lines), actionsFor(header, document, chain), postingOf(id).orElse(null));
    }

    /** The landed-cost allocation: drawn before posting, and what posting writes. */
    public LandedCost.Result allocation(GrnHeader header, List<GrnLineRow> lines) {
        return LandedCost.allocate(
                lines.stream().map(l -> new LandedCost.Input(l.lineNo(), l.quantity(), l.unitPrice(), l.quantityBase())).toList(),
                header.exchangeRate(), header.landedTotalRwf());
    }

    public Optional<GrnPosting> postingOf(UUID id) {
        Optional<UUID[]> ticket = jdbc.sql("SELECT t.document_id FROM transaction_ticket t WHERE t.source_document_id = :id AND t.movement_type = 'RECEIPT'")
                .param("id", id, Types.OTHER)
                .query((rs, n) -> new UUID[]{rs.getObject("document_id", UUID.class)}).optional();
        if (ticket.isEmpty()) return Optional.empty();
        UUID ticketId = ticket.get()[0];
        String serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id")
                .param("id", ticketId, Types.OTHER).query(String.class).single();
        List<GrnPosting.Movement> movements = jdbc.sql("""
                SELECT m.id, tl.line_no, i.item_code, m.quantity_base_uom, m.unit_cost, m.value,
                       -- The balance after is the place's book while nothing has moved since: none while counted (V15).
                       CASE WHEN count_freezing(m.item_id, m.location_id) IS NULL THEN m.running_balance END AS running_balance,
                       m.business_date
                  FROM stock_movement m
                  JOIN ticket_line tl ON tl.id = m.ticket_line_id
                  JOIN item i ON i.id = m.item_id
                 WHERE m.document_id = :ticket
                 ORDER BY tl.line_no
                """)
                .param("ticket", ticketId, Types.OTHER)
                .query((rs, n) -> new GrnPosting.Movement(rs.getLong("id"), rs.getInt("line_no"),
                        rs.getString("item_code"), rs.getBigDecimal("quantity_base_uom"),
                        rs.getBigDecimal("unit_cost"), rs.getBigDecimal("value"),
                        rs.getBigDecimal("running_balance"),
                        rs.getObject("business_date", java.time.LocalDate.class)))
                .list();
        return Optional.of(new GrnPosting(ticketId, serial, movements));
    }

    /** The note as its form holds it, for editing or for raising a corrected copy. */
    @PreAuthorize("hasAuthority('receiving.create')")
    public GrnForm formFor(UUID id) {
        CurrentUser.requireAt("receiving.create", documents.header(id).branchId());   // a read: refused, not recorded
        GrnHeader g = find(id);
        GrnForm form = new GrnForm();
        form.setId(g.id());
        form.setVersion(g.version());
        form.setSupplierId(g.supplierId());
        form.setLocationId(g.locationId());
        form.setSupplierDeliveryNoteNo(g.deliveryNoteNo());
        form.setSupplierInvoiceNo(g.invoiceNo());
        form.setPurchaseOrderNo(g.purchaseOrderNo());
        form.setCustomsReference(g.customsReference());
        form.setCurrencyCode(g.currencyCode());
        form.setExchangeRate(g.exchangeRate());
        form.setFreightRwf(g.freightRwf());
        form.setDutyRwf(g.dutyRwf());
        form.setClearingRwf(g.clearingRwf());
        form.setDemurrageRwf(g.demurrageRwf());
        form.setReference(g.reference());
        form.setNotes(g.notes());
        form.setSupersedesDocumentId(g.supersedesId());
        for (GrnLineRow row : lines(id)) {
            GrnLineForm line = new GrnLineForm();
            line.setItemId(row.itemId());
            line.setUomId(row.uomId());
            line.setQuantity(row.quantity());
            line.setUnitPrice(row.unitPrice());
            line.setStorageBinId(row.storageBinId());
            line.setMeasuredThicknessMm(row.measuredThicknessMm());
            line.setSupplierQuantityBase(row.supplierQuantityBase());
            line.setNote(row.note());
            form.getLines().add(line);
        }
        return form;
    }

    /** Whether the location, or the branch it is at, is bonded: the customs reference is then required. */
    public boolean receivesBonded(UUID locationId) {
        return jdbc.sql("""
                SELECT (l.is_bonded OR b.is_bonded) FROM location l JOIN branch b ON b.id = l.branch_id
                 WHERE l.id = :id
                """)
                .param("id", locationId, Types.OTHER).query(Boolean.class).optional().orElse(false);
    }

    // ---- writes ----------------------------------------------------------

    /**
     * Raises a draft. The document takes the branch of the receiving location
     * and is dated, and its chain bound, by the database.
     */
    @Transactional
    @PreAuthorize("hasAuthority('receiving.create')")
    public UUID create(GrnForm form) {
        UUID branchId = locationBranch(form.getLocationId());
        documents.requireRightAt(DocumentKind.GRN, branchId, "receiving.create", "Raise a goods received note");
        requireCorrectable(form.getSupersedesDocumentId(), branchId);

        try {
            OpenedDocument opened = documents.open(DocumentKind.GRN, branchId, form.getReference(),
                    form.getNotes(), form.getSupersedesDocumentId());
            insertHeader(opened.id(), form);
            insertLines(opened.id(), form.getLines());

            GrnHeader after = requireHeader(opened.id());
            List<GrnLineRow> afterLines = lines(opened.id());
            AuditSnapshot snapshot = snapshot(null, null, after, afterLines);
            documents.auditInTransaction("GRN · " + after.serialNo(), opened.id(), AuditAction.CREATE, snapshot,
                    branches.findById(branchId).orElse(null));
            return opened.id();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
    }

    /**
     * Edits a draft, provided nobody changed it since the form was opened.
     * Whether the note is still a draft is the database's to say: it refuses a
     * change to the header or the lines once the note has left DRAFT, and its
     * reason is what the user reads.
     */
    @Transactional
    @PreAuthorize("hasAuthority('receiving.create')")
    public void update(UUID id, GrnForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "receiving.create", "Edit");
        if (form.getVersion() == null) {
            throw documents.refused(d, "Edit", "This form did not say which version of " + d.serialNo()
                    + " it was opened from. Reload the note and try again.");
        }
        UUID branchId = locationBranch(form.getLocationId());
        if (!branchId.equals(d.branchId())) {
            throw documents.refused(d, "Edit", "That location is at another branch. " + d.serialNo()
                    + " belongs to " + d.branchName() + " and receives goods there.");
        }

        GrnHeader before = requireHeader(id);
        List<GrnLineRow> beforeLines = lines(id);
        try {
            documents.updateDraft(d, form.getVersion(), form.getReference(), form.getNotes());
            // Lines go first: a bin in the previous location must not outlive a change of location.
            jdbc.sql("DELETE FROM goods_received_line WHERE document_id = :id")
                    .param("id", id, Types.OTHER).update();
            jdbc.sql("""
                    UPDATE goods_received_note
                       SET supplier_id = :supplier, location_id = :location,
                           supplier_delivery_note_no = :deliveryNote, supplier_invoice_no = :invoice,
                           purchase_order_no = :purchaseOrder, customs_reference = :customs,
                           currency_code = :currency, exchange_rate = :rate,
                           freight_rwf = :freight, duty_rwf = :duty, clearing_rwf = :clearing,
                           demurrage_rwf = :demurrage
                     WHERE document_id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .params(headerParams(form))
                    .update();
            insertLines(id, form.getLines());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Edit", e);
        }

        GrnHeader after = requireHeader(id);
        List<GrnLineRow> afterLines = lines(id);
        AuditSnapshot snapshot = snapshot(before, beforeLines, after, afterLines);
        if (!snapshot.unchanged()) {
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, snapshot, d.branch());
        }
    }

    /** Submits the draft; the submitter signs step 1. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('receiving.create','receiving.verify','receiving.approve')")
    public String submit(UUID id) {
        return documents.submit(id).status();
    }

    /** Approves or rejects the next step of the chain. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('receiving.create','receiving.verify','receiving.approve')")
    public String sign(UUID id, boolean approve, String comment) {
        return documents.sign(id, approve, comment).status();
    }

    @Transactional
    @PreAuthorize("hasAuthority('receiving.create')")
    public void cancel(UUID id, String reason) {
        documents.cancel(id, reason);
    }

    /**
     * Posts an approved note to the ledger, in one transaction. Returns the
     * serial of the transaction ticket it raised.
     */
    @Transactional
    @PreAuthorize("hasAuthority('receiving.post')")
    public String post(UUID id) {
        DocumentHeader d = documents.beginPost(id);      // locks, APPROVED -> POSTED by the poster
        GrnHeader g = requireHeader(id);
        List<GrnLineRow> lines = lines(id);
        LandedCost.Result landed = allocation(g, lines);
        UUID poster = CurrentUser.id();

        OpenedDocument ticket;
        List<Long> movementIds = new ArrayList<>();
        try {
            // Every place this posting moves is locked up front, in one fixed order, whatever the line order.
            ledger.lockPlaces(lines.stream()
                    .map(l -> new LedgerService.Place(l.itemId(), g.locationId())).toList());
            ticket = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);

            jdbc.sql("""
                    INSERT INTO transaction_ticket (document_id, movement_type, direction, to_location_id,
                                                    source_document_id, customs_reference, total_value)
                    VALUES (:id, 'RECEIPT', 'IN', :location, :source, :customs, :total)
                    """)
                    .param("id", ticket.id(), Types.OTHER)
                    .param("location", g.locationId(), Types.OTHER)
                    .param("source", id, Types.OTHER)
                    .param("customs", g.customsReference())
                    .param("total", landed.totalRwf())
                    .update();

            List<UUID> ticketLineIds = new ArrayList<>();
            for (GrnLineRow line : lines) {
                LandedCost.Line cost = landed.forLine(line.lineNo());
                UUID lineId = UUID.randomUUID();
                ticketLineIds.add(lineId);
                jdbc.sql("""
                        INSERT INTO ticket_line (id, ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom,
                                                 unit_value, total_value, storage_bin_id)
                        VALUES (:id, :ticket, :lineNo, :item, :quantity, :uom, :base, :unitValue, :total, :bin)
                        """)
                        .param("id", lineId, Types.OTHER)
                        .param("ticket", ticket.id(), Types.OTHER)
                        .param("lineNo", (short) line.lineNo())
                        .param("item", line.itemId(), Types.OTHER)
                        .param("quantity", line.quantity())
                        .param("uom", line.uomId(), Types.OTHER)
                        .param("base", line.quantityBase())
                        .param("unitValue", cost.totalRwf().divide(line.quantity(), 4, RoundingMode.HALF_UP))
                        .param("total", cost.totalRwf())
                        .param("bin", line.storageBinId(), Types.OTHER)
                        .update();
            }

            for (int i = 0; i < lines.size(); i++) {
                GrnLineRow line = lines.get(i);
                var entry = ledger.post(MovementRequest.receipt(ticket.id(), ticketLineIds.get(i), d.branchId(),
                        line.itemId(), g.locationId(), line.storageBinId(), line.quantityBase(),
                        landed.forLine(line.lineNo()).totalRwf()), poster);
                movementIds.add(entry.movementId());
            }
        } catch (ContentionException e) {
            throw e;        // a lost race, not a refusal: logged by the engine, never audited as REJECT
        } catch (ControlRefusedException e) {
            throw documents.refused(d, "Post", e.getMessage());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Post", e);
        }

        documents.postDerived(d, ticket.id());

        AuditSnapshot snapshot = AuditSnapshot.of()
                .field("Status", "APPROVED", "POSTED")
                .value("Transaction ticket", ticket.serialNo())
                .value("Ledger movements", movementIds.size())
                .value("Value posted (RWF)", landed.totalRwf())
                .value("Landed cost allocated (RWF)", landed.landedRwf());
        documents.auditPosted(d, snapshot);
        return ticket.serialNo();
    }

    // ---- helpers ---------------------------------------------------------

    private GrnActions actionsFor(GrnHeader g, DocumentHeader d, List<ChainStep> chain) {
        UUID branch = g.branchId();
        boolean create = CurrentUser.holdsAt("receiving.create", branch);
        boolean signer = create || CurrentUser.holdsAt("receiving.verify", branch)
                         || CurrentUser.holdsAt("receiving.approve", branch);
        boolean poster = CurrentUser.holdsAt("receiving.post", branch);

        StepCheck submit = documents.canSubmit(d, chain);
        StepCheck sign = documents.canSign(d, chain);
        StepCheck post = documents.canPost(d, chain);
        StepCheck cancel = documents.canCancel(d);

        String status = g.status();
        return new GrnActions(
                "DRAFT".equals(status) && create,
                submit.allowed(),
                "DRAFT".equals(status) && !submit.allowed() && signer ? submit.reason() : null,
                sign.allowed(),
                "PENDING".equals(status) && !sign.allowed() && signer ? sign.reason() : null,
                sign.step(),
                post.allowed(),
                "APPROVED".equals(status) && !post.allowed() && poster ? post.reason() : null,
                cancel.allowed(),
                "POSTED".equals(status) && (create || poster) ? cancel.reason() : null,
                create && ("REJECTED".equals(status) || "CANCELLED".equals(status)));
    }

    /**
     * A corrected copy names the note it replaces, and that note must be a
     * rejected or cancelled receipt at the same branch: the form carries the
     * id, so a request could name anything else.
     */
    private void requireCorrectable(UUID supersedesId, UUID branchId) {
        if (supersedesId == null) return;
        boolean ok = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM document d JOIN document_type dt ON dt.id = d.document_type_id
                                WHERE d.id = :id AND dt.code = 'GRN' AND d.branch_id = :branch
                                  AND d.status IN ('REJECTED', 'CANCELLED'))
                """)
                .param("id", supersedesId, Types.OTHER)
                .param("branch", branchId, Types.OTHER)
                .query(Boolean.class).single();
        if (!ok) {
            throw new ControlRefusedException("A corrected copy replaces a rejected or cancelled goods received note "
                    + "at this branch. The note it names is neither.");
        }
    }

    private UUID locationBranch(UUID locationId) {
        return jdbc.sql("SELECT branch_id FROM location WHERE id = :id")
                .param("id", locationId, Types.OTHER)
                .query(UUID.class).optional()
                .orElseThrow(() -> new ControlRefusedException("That receiving location does not exist."));
    }

    private GrnHeader requireHeader(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new GrnNotFoundException(id));
    }

    private void insertHeader(UUID id, GrnForm form) {
        jdbc.sql("""
                INSERT INTO goods_received_note (document_id, supplier_id, location_id,
                       supplier_delivery_note_no, supplier_invoice_no, purchase_order_no, customs_reference,
                       currency_code, exchange_rate, freight_rwf, duty_rwf, clearing_rwf, demurrage_rwf)
                VALUES (:id, :supplier, :location, :deliveryNote, :invoice, :purchaseOrder, :customs,
                        :currency, :rate, :freight, :duty, :clearing, :demurrage)
                """)
                .param("id", id, Types.OTHER)
                .params(headerParams(form))
                .update();
    }

    private java.util.Map<String, Object> headerParams(GrnForm form) {
        var params = new java.util.HashMap<String, Object>();
        params.put("supplier", form.getSupplierId());
        params.put("location", form.getLocationId());
        params.put("deliveryNote", form.getSupplierDeliveryNoteNo());
        params.put("invoice", form.getSupplierInvoiceNo());
        params.put("purchaseOrder", form.getPurchaseOrderNo());
        params.put("customs", form.getCustomsReference());
        params.put("currency", form.getCurrencyCode());
        params.put("rate", form.getExchangeRate());
        params.put("freight", form.getFreightRwf());
        params.put("duty", form.getDutyRwf());
        params.put("clearing", form.getClearingRwf());
        params.put("demurrage", form.getDemurrageRwf());
        return params;
    }

    /** Numbered by position, so the lines are always 1..n whatever was added or removed on the form. */
    private void insertLines(UUID documentId, List<GrnLineForm> lines) {
        int lineNo = 0;
        for (GrnLineForm line : lines) {
            lineNo++;
            BigDecimal base = baseQuantity(line);
            jdbc.sql("""
                    INSERT INTO goods_received_line (document_id, line_no, item_id, uom_id, quantity, qty_base_uom,
                           unit_price, storage_bin_id, measured_thickness_mm, supplier_quantity_base, note)
                    VALUES (:doc, :lineNo, :item, :uom, :quantity, :base, :price, :bin, :thickness, :supplierQty, :note)
                    """)
                    .param("doc", documentId, Types.OTHER)
                    .param("lineNo", (short) lineNo)
                    .param("item", line.getItemId(), Types.OTHER)
                    .param("uom", line.getUomId(), Types.OTHER)
                    .param("quantity", line.getQuantity())
                    .param("base", base)
                    .param("price", line.getUnitPrice())
                    .param("bin", line.getStorageBinId(), Types.OTHER)
                    .param("thickness", line.getMeasuredThicknessMm())
                    .param("supplierQty", line.getSupplierQuantityBase())
                    .param("note", line.getNote())
                    .update();
        }
    }

    /**
     * Quantity in the item's base unit: the quantity times the conversion
     * factor (1 for the base unit itself), rounded half up to three places,
     * which is exactly the rounding the database checks it against.
     */
    private BigDecimal baseQuantity(GrnLineForm line) {
        record Conversion(String item, String unit, String base, BigDecimal factor) {}
        Conversion c = jdbc.sql("""
                SELECT i.item_code, u.code AS uom_code, bu.code AS base_code,
                       CASE WHEN i.base_uom_id = u.id THEN 1 ELSE c.factor_to_base END AS factor
                  FROM item i
                  JOIN uom u  ON u.id = :uom
                  JOIN uom bu ON bu.id = i.base_uom_id
             LEFT JOIN item_uom_conversion c ON c.item_id = i.id AND c.uom_id = u.id
                 WHERE i.id = :item
                """)
                .param("item", line.getItemId(), Types.OTHER)
                .param("uom", line.getUomId(), Types.OTHER)
                .query((rs, n) -> new Conversion(rs.getString("item_code"), rs.getString("uom_code"),
                        rs.getString("base_code"), rs.getBigDecimal("factor")))
                .optional()
                .orElseThrow(() -> new ControlRefusedException("A line names an item or unit that does not exist."));
        if (c.factor() == null) {
            throw new ControlRefusedException("Item " + c.item() + " has no conversion from " + c.unit()
                    + " to its base unit " + c.base() + ", so the stock quantity cannot be worked out. "
                    + "Receive it in " + c.base() + ", or have a conversion added to the item.");
        }
        return line.getQuantity().multiply(c.factor()).setScale(3, RoundingMode.HALF_UP);
    }

    /** What a reviewer reads in the trail: names and figures, one string per group of lines. */
    private AuditSnapshot snapshot(GrnHeader before, List<GrnLineRow> beforeLines,
                                   GrnHeader after, List<GrnLineRow> afterLines) {
        AuditSnapshot s = AuditSnapshot.of();
        s.field("Supplier",        before == null ? null : before.supplierName(), after.supplierName());
        s.field("Receiving location", before == null ? null : before.locationCode(), after.locationCode());
        s.field("Supplier delivery note", before == null ? null : before.deliveryNoteNo(), after.deliveryNoteNo());
        s.field("Supplier invoice", before == null ? null : before.invoiceNo(), after.invoiceNo());
        s.field("Purchase order",  before == null ? null : before.purchaseOrderNo(), after.purchaseOrderNo());
        s.field("Customs reference", before == null ? null : before.customsReference(), after.customsReference());
        s.field("Currency",        before == null ? null : before.currencyCode(), after.currencyCode());
        s.field("Exchange rate",   before == null ? null : before.exchangeRate(), after.exchangeRate());
        s.field("Freight (RWF)",   before == null ? null : before.freightRwf(), after.freightRwf());
        s.field("Duty (RWF)",      before == null ? null : before.dutyRwf(), after.dutyRwf());
        s.field("Clearing (RWF)",  before == null ? null : before.clearingRwf(), after.clearingRwf());
        s.field("Demurrage (RWF)", before == null ? null : before.demurrageRwf(), after.demurrageRwf());
        s.field("Reference",       before == null ? null : before.reference(), after.reference());
        s.field("Notes",           before == null ? null : before.notes(), after.notes());
        s.field("Corrects",        before == null ? null : before.supersedesSerial(), after.supersedesSerial());
        s.field("Lines",           beforeLines == null ? null : describe(beforeLines), describe(afterLines));
        return s;
    }

    private static String describe(List<GrnLineRow> lines) {
        return lines.stream().map(l -> l.lineNo() + ": " + l.itemCode() + " x "
                        + l.quantity().stripTrailingZeros().toPlainString() + " " + l.uomCode()
                        + " @ " + l.unitPrice().stripTrailingZeros().toPlainString()
                        + (l.binCode() == null ? "" : ", bin " + l.binCode())
                        + (l.measuredThicknessMm() == null ? "" : ", " + l.measuredThicknessMm().stripTrailingZeros().toPlainString() + " mm"))
                .collect(Collectors.joining("; "));
    }

    private GrnHeader mapHeader(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new GrnHeader(
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
                KigaliTime.read(rs, "approved_at"),
                KigaliTime.read(rs, "posted_at"),
                rs.getObject("posted_by", UUID.class),
                rs.getString("posted_by_name"),
                KigaliTime.read(rs, "cancelled_at"),
                rs.getString("cancelled_by_name"),
                rs.getString("cancel_reason"),
                rs.getObject("supersedes_document_id", UUID.class),
                rs.getString("supersedes_serial"),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getObject("location_id", UUID.class),
                rs.getString("location_code"),
                rs.getString("location_name"),
                rs.getBoolean("location_bonded"),
                rs.getString("supplier_delivery_note_no"),
                rs.getString("supplier_invoice_no"),
                rs.getString("purchase_order_no"),
                rs.getString("customs_reference"),
                rs.getString("currency_code"),
                rs.getBigDecimal("exchange_rate"),
                rs.getBigDecimal("freight_rwf"),
                rs.getBigDecimal("duty_rwf"),
                rs.getBigDecimal("clearing_rwf"),
                rs.getBigDecimal("demurrage_rwf"));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class GrnNotFoundException extends RuntimeException {
        public GrnNotFoundException(UUID id) {
            super("No goods received note with id " + id);
        }
    }
}
