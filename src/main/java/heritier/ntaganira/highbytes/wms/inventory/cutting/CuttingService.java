package heritier.ntaganira.highbytes.wms.inventory.cutting;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.cutting
 * - File       : CuttingService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Cutting orders: draft, submit, sign, cancel, post (sheets out, pieces and off-cuts in), audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
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
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DaoHeader;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DaoLineRow;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DeliveryAuthority;
import heritier.ntaganira.highbytes.wms.inventory.gate.GateSteps;
import heritier.ntaganira.highbytes.wms.inventory.gate.ReleaseGate;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cutting orders (CUT-006), on the document engine.
 *
 * <p>Finance raises an order for a customer: the sheets of one glass item to be cut at one place, and the sizes cut
 * from them, the customer's pieces and the off-cuts kept. Each size is an item of its own, one per parent sheet and
 * exact size, found or made by the database ({@code cut_item_for}, V18); a size made for the first time is audited
 * as an item created by the order. The Warehouse Manager verifies the sizes and the Internal Controller releases the
 * order. A second Finance officer posts it: the sheets go out at the ledger's average cost and come back in as the
 * pieces and off-cuts, their value split by area ({@code cut_output_shares}); what is neither is waste, written
 * off with the order. The pieces then leave on a delivery note raised against the posted order, at the gate.
 *
 * <p>The rules are the database's (V18); this class asks first where a friendlier reason helps.
 */
@Service
@Transactional(readOnly = true)
public class CuttingService implements DeliveryAuthority {

    private static final String DISPLAY_STATE = """
            CASE d.status
                 WHEN 'APPROVED' THEN 'RELEASED'
                 WHEN 'POSTED' THEN CASE WHEN EXISTS (SELECT 1 FROM delivery_note n JOIN document dn ON dn.id = n.document_id
                                                       WHERE n.cutting_order_id = d.id AND dn.status = 'POSTED')
                                         THEN 'DELIVERED' ELSE 'POSTED' END
                 ELSE d.status END""";

    private static final String HEADER = """
            SELECT d.id, d.branch_id, b.name AS branch_name, b.is_bonded AS branch_bonded,
                   d.serial_no, d.status, %s AS display_state,
                   d.document_date, d.reference, d.notes, d.version,
                   d.created_by, cu.full_name AS created_by_name, d.created_at, d.submitted_at, d.approved_at,
                   d.posted_at, d.posted_by, pu.full_name AS posted_by_name,
                   d.cancelled_at, xu.full_name AS cancelled_by_name, d.cancel_reason,
                   c.customer_id, cs.name AS customer_name, cs.is_blocked AS customer_blocked,
                   c.location_id, l.code AS location_code, l.name AS location_name, l.is_bonded AS location_bonded,
                   c.customer_reference, c.customs_reference
              FROM document d
              JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'CUT'
              JOIN cutting_order c  ON c.document_id = d.id
              JOIN branch b         ON b.id = d.branch_id
              JOIN customer cs      ON cs.id = c.customer_id
              JOIN location l       ON l.id = c.location_id
              JOIN app_user cu      ON cu.id = d.created_by
         LEFT JOIN app_user pu      ON pu.id = d.posted_by
         LEFT JOIN app_user xu      ON xu.id = d.cancelled_by
             WHERE d.id = :id
            """.formatted(DISPLAY_STATE);

    private static final String SHEETS = """
            SELECT s.id, s.line_no, s.item_id, i.item_code, i.description, i.width_mm, i.height_mm, i.thickness_mm,
                   s.storage_bin_id, sb.bin_code, s.quantity, i.base_uom_id
              FROM cutting_order_sheet s
              JOIN item i              ON i.id = s.item_id
         LEFT JOIN storage_bin sb      ON sb.id = s.storage_bin_id
             WHERE s.document_id = :id
             ORDER BY s.line_no
            """;

    private static final String OUTPUTS = """
            SELECT o.id, o.line_no, o.kind, o.width_mm, o.height_mm, o.quantity, o.item_id, i.item_code,
                   i.description, i.thickness_mm, i.base_uom_id, u.code AS base_uom_code
              FROM cutting_order_output o
              JOIN item i ON i.id = o.item_id
              JOIN uom u  ON u.id = i.base_uom_id
             WHERE o.document_id = :id
             ORDER BY o.line_no
            """;

    private static final String LIST = """
            SELECT * FROM (
              SELECT d.id, d.serial_no, %s AS display_state, d.document_date,
                     cs.name AS customer_name, l.code AS location_code,
                     (SELECT i.item_code FROM cutting_order_sheet s JOIN item i ON i.id = s.item_id
                       WHERE s.document_id = d.id ORDER BY s.line_no LIMIT 1)                       AS sheet_code,
                     (SELECT COALESCE(SUM(s.quantity), 0) FROM cutting_order_sheet s
                       WHERE s.document_id = d.id)                                                 AS sheets,
                     (SELECT COALESCE(SUM(o.quantity), 0) FROM cutting_order_output o
                       WHERE o.document_id = d.id AND o.kind = 'PIECE')::int                        AS pieces,
                     (SELECT COALESCE(SUM(o.quantity), 0) FROM cutting_order_output o
                       WHERE o.document_id = d.id AND o.kind = 'OFFCUT')::int                       AS offcuts,
                     cu.full_name AS created_by_name, nx.role_name AS awaiting_role, d.created_at
                FROM document d
                JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'CUT'
                JOIN cutting_order c  ON c.document_id = d.id
                JOIN customer cs      ON cs.id = c.customer_id
                JOIN location l       ON l.id = c.location_id
                JOIN app_user cu      ON cu.id = d.created_by
           LEFT JOIN LATERAL (
                     SELECT r.name AS role_name
                       FROM workflow_step ws JOIN role r ON r.id = ws.required_role_id
                      WHERE ws.workflow_definition_id = d.workflow_definition_id
                        AND d.status = 'PENDING'
                        AND NOT EXISTS (SELECT 1 FROM document_approval da
                                         WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                      ORDER BY ws.sequence_no LIMIT 1) nx ON TRUE
               WHERE d.branch_id = :branch) x
             WHERE (:status::text IS NULL OR x.display_state = :status::text)
             ORDER BY x.created_at DESC
             LIMIT 300
            """.formatted(DISPLAY_STATE);

    /** What the order wrote to the ledger: its two tickets and their movements, none shown of a counted place. */
    public record Posting(UUID consumeTicketId, String consumeSerial, UUID outputTicketId, String outputSerial,
                          List<Movement> movements) {

        public BigDecimal valueOut() {
            return sum("OUT");
        }

        public BigDecimal valueIn() {
            return sum("IN");
        }

        private BigDecimal sum(String direction) {
            return movements.stream().filter(m -> direction.equals(m.direction()) && m.value() != null)
                    .map(Movement::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** A movement of the order. Quantity, cost, value and balance are null while its place is counted (V15). */
    public record Movement(long id, String ticketSerial, String movementType, int lineNo, String itemCode,
                           String binCode, String direction, BigDecimal quantityBase, BigDecimal unitCost,
                           BigDecimal value, BigDecimal runningBalance, LocalDate businessDate, boolean counted) {}

    /** Everything the view needs, gathered in one read-only pass. */
    public record Detail(CuttingHeader header, List<CuttingLines.Sheet> sheets, List<CuttingLines.Output> outputs,
                         CuttingLines.Areas areas, List<ChainStep> chain, ChainInfo chainInfo, ReleaseGate gate,
                         CuttingActions actions, Posting posting, String countFreezing) {}

    /** The most sizes one order cuts: each size cut for the first time is an item made for good. */
    static final int MAX_SIZES = 50;

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final LedgerService ledger;
    private final BranchService branches;
    private final GateSteps gateSteps;
    private final AuditService audit;

    public CuttingService(JdbcClient jdbc, DocumentService documents, LedgerService ledger, BranchService branches,
                          GateSteps gateSteps, AuditService audit) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.ledger = ledger;
        this.branches = branches;
        this.gateSteps = gateSteps;
        this.audit = audit;
    }

    // ---- reads -----------------------------------------------------------

    @PreAuthorize("hasAuthority('cutting.view')")
    public List<CuttingRow> list(UUID branchId, String state, boolean awaitingMe) {
        Set<UUID> mine = documents.awaitingSignatureOf(DocumentKind.CUT, branchId, CurrentUser.id());
        return jdbc.sql(LIST)
                .param("branch", branchId, Types.OTHER)
                .param("status", state == null || state.isBlank() ? null : state, Types.VARCHAR)
                .query((rs, n) -> new CuttingRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("display_state"),
                        rs.getObject("document_date", LocalDate.class),
                        rs.getString("customer_name"),
                        rs.getString("location_code"),
                        rs.getString("sheet_code"),
                        rs.getBigDecimal("sheets"),
                        rs.getInt("pieces"),
                        rs.getInt("offcuts"),
                        rs.getString("created_by_name"),
                        rs.getString("awaiting_role"),
                        false))
                .list().stream()
                .map(row -> row.markAwaitingMe(mine.contains(row.id())))
                .filter(row -> !awaitingMe || row.awaitingMe())
                .toList();
    }

    /** The order's header; a 404 for none, a 403 unless the viewer may read cutting orders at its branch. */
    @PreAuthorize("hasAuthority('cutting.view')")
    public CuttingHeader find(UUID id) {
        CuttingHeader header = requireHeader(id);
        CurrentUser.requireAt("cutting.view", header.branchId());
        return header;
    }

    public List<CuttingLines.Sheet> sheets(UUID id) {
        return jdbc.sql(SHEETS).param("id", id, Types.OTHER)
                .query((rs, n) -> new CuttingLines.Sheet(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getBigDecimal("width_mm"),
                        rs.getBigDecimal("height_mm"),
                        rs.getBigDecimal("thickness_mm"),
                        rs.getObject("storage_bin_id", UUID.class),
                        rs.getString("bin_code"),
                        rs.getBigDecimal("quantity"),
                        rs.getObject("base_uom_id", UUID.class)))
                .list();
    }

    public List<CuttingLines.Output> outputs(UUID id) {
        return jdbc.sql(OUTPUTS).param("id", id, Types.OTHER)
                .query((rs, n) -> new CuttingLines.Output(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getString("kind"),
                        rs.getBigDecimal("width_mm"),
                        rs.getBigDecimal("height_mm"),
                        rs.getBigDecimal("quantity"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getBigDecimal("thickness_mm"),
                        rs.getObject("base_uom_id", UUID.class),
                        rs.getString("base_uom_code")))
                .list();
    }

    /** The order's areas, the database's own sum (cut_areas). */
    public CuttingLines.Areas areas(UUID id) {
        return jdbc.sql("SELECT sheet_area, output_area FROM cut_areas(:id)")
                .param("id", id, Types.OTHER)
                .query((rs, n) -> CuttingLines.Areas.ofMm2(rs.getBigDecimal("sheet_area"), rs.getBigDecimal("output_area")))
                .single();
    }

    @PreAuthorize("hasAuthority('cutting.view')")
    public Detail detail(UUID id) {
        CuttingHeader header = find(id);
        List<CuttingLines.Sheet> sheets = sheets(id);
        List<CuttingLines.Output> outputs = outputs(id);
        DocumentHeader document = documents.header(id);
        List<ChainStep> chain = documents.chain(id);
        ReleaseGate gate = gate(header, chain);
        String freezing = "APPROVED".equals(header.status()) ? freezingOf(header.locationId(), sheets, outputs) : null;
        return new Detail(header, sheets, outputs, areas(id), chain, documents.chainInfo(id).orElse(null), gate,
                actionsFor(header, document, chain, gate, freezing), postingOf(id).orElse(null), freezing);
    }

    /** The order as its form holds it, for editing. */
    @PreAuthorize("hasAuthority('cutting.create')")
    public CuttingForm formFor(UUID id) {
        CurrentUser.requireAt("cutting.create", documents.header(id).branchId());   // a read: refused, not recorded
        CuttingHeader h = find(id);
        CuttingForm form = new CuttingForm();
        form.setId(h.id());
        form.setVersion(h.version());
        form.setCustomerId(h.customerId());
        form.setLocationId(h.locationId());
        form.setCustomerReference(h.customerReference());
        form.setCustomsReference(h.customsReference());
        form.setReference(h.reference());
        form.setNotes(h.notes());
        List<CuttingLines.Sheet> sheets = sheets(id);
        if (!sheets.isEmpty()) {
            form.setSheetItemId(sheets.get(0).itemId());
            form.setSheetBinId(sheets.get(0).binId());
            form.setSheets(sheets.stream().map(CuttingLines.Sheet::quantity).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        for (CuttingLines.Output o : outputs(id)) {
            CuttingOutputForm line = new CuttingOutputForm();
            line.setKind(o.kind());
            line.setWidthMm(o.widthMm());
            line.setHeightMm(o.heightMm());
            line.setQuantity(o.quantity());
            form.getOutputs().add(line);
        }
        return form;
    }

    public Optional<Posting> postingOf(UUID id) {
        record Tk(UUID id, String serial, String type) {}
        List<Tk> tickets = jdbc.sql("""
                SELECT t.document_id, d.serial_no, t.movement_type
                  FROM transaction_ticket t JOIN document d ON d.id = t.document_id
                 WHERE t.source_document_id = :id AND t.movement_type IN ('CUT_CONSUME', 'CUT_OUTPUT')
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> new Tk(rs.getObject("document_id", UUID.class), rs.getString("serial_no"),
                        rs.getString("movement_type")))
                .list();
        if (tickets.isEmpty()) return Optional.empty();
        Tk out = tickets.stream().filter(t -> "CUT_CONSUME".equals(t.type())).findFirst().orElse(null);
        Tk in = tickets.stream().filter(t -> "CUT_OUTPUT".equals(t.type())).findFirst().orElse(null);
        List<Movement> movements = jdbc.sql("""
                SELECT m.id, td.serial_no AS ticket_serial, t.movement_type, tl.line_no, i.item_code, sb.bin_code,
                       m.direction, m.business_date, f.counted,
                       -- No quantity, value or balance of a place under a live count (V15).
                       CASE WHEN NOT f.counted THEN m.quantity_base_uom END AS quantity_base_uom,
                       CASE WHEN NOT f.counted THEN m.unit_cost END         AS unit_cost,
                       CASE WHEN NOT f.counted THEN m.value END             AS value,
                       CASE WHEN NOT f.counted THEN m.running_balance END   AS running_balance
                  FROM transaction_ticket t
                  JOIN document td       ON td.id = t.document_id
                  JOIN stock_movement m  ON m.document_id = t.document_id
                  JOIN LATERAL (SELECT count_freezing(m.item_id, m.location_id) IS NOT NULL AS counted) f ON TRUE
                  JOIN ticket_line tl    ON tl.id = m.ticket_line_id
                  JOIN item i            ON i.id = m.item_id
             LEFT JOIN storage_bin sb    ON sb.id = m.storage_bin_id
                 WHERE t.source_document_id = :id AND t.movement_type IN ('CUT_CONSUME', 'CUT_OUTPUT')
                 ORDER BY (t.movement_type = 'CUT_OUTPUT'), tl.line_no
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> new Movement(rs.getLong("id"), rs.getString("ticket_serial"),
                        rs.getString("movement_type"), rs.getInt("line_no"), rs.getString("item_code"),
                        rs.getString("bin_code"), rs.getString("direction"), rs.getBigDecimal("quantity_base_uom"),
                        rs.getBigDecimal("unit_cost"), rs.getBigDecimal("value"), rs.getBigDecimal("running_balance"),
                        rs.getObject("business_date", LocalDate.class), rs.getBoolean("counted")))
                .list();
        return Optional.of(new Posting(out == null ? null : out.id(), out == null ? null : out.serial(),
                in == null ? null : in.id(), in == null ? null : in.serial(), movements));
    }

    // ---- the gate's view of a cutting order (DeliveryAuthority) ------------------------

    @Override
    public String typeCode() {
        return DocumentKind.CUT.code();
    }

    /**
     * The order read as an authorization, for the delivery note raised against it. Whoever loads at the gate reads
     * it: dispatch.view or cutting.view at its branch.
     */
    @Override
    @PreAuthorize("hasAnyAuthority('cutting.view','dispatch.view')")
    public DaoHeader asAuthorization(UUID id) {
        CuttingHeader h = requireHeader(id);
        requireGateReader(h.branchId());
        return new DaoHeader(h.id(), h.branchId(), h.branchName(), h.branchBonded(), h.serialNo(), h.status(),
                h.displayState(), h.documentDate(), h.reference(), h.notes(), h.version(), h.createdBy(),
                h.createdByName(), h.createdAt(), h.submittedAt(), h.approvedAt(), h.cancelledAt(),
                h.cancelledByName(), h.cancelReason(), null, null, h.customerId(), h.customerName(),
                h.customerBlocked(), h.locationId(), h.locationCode(), h.locationName(), h.locationBonded(),
                h.customerReference(), null, h.customsReference());
    }

    /** The customer's pieces, the only lines a note against the order loads. Off-cuts stay in stock. */
    @Override
    @PreAuthorize("hasAnyAuthority('cutting.view','dispatch.view')")
    public List<DaoLineRow> loadableLines(UUID id) {
        requireGateReader(requireHeader(id).branchId());
        return outputs(id).stream().filter(CuttingLines.Output::piece)
                .map(o -> new DaoLineRow(o.id(), o.lineNo(), o.itemId(), o.itemCode(), o.description(), "GLASS",
                        o.thicknessMm(), o.baseUomId(), o.baseUomCode(), o.baseUomCode(), o.quantity(), o.quantity(),
                        size(o.widthMm(), o.heightMm()) + " mm"))
                .toList();
    }

    @Override
    @PreAuthorize("hasAnyAuthority('cutting.view','dispatch.view')")
    public ReleaseGate gateOf(UUID id) {
        CuttingHeader h = requireHeader(id);
        requireGateReader(h.branchId());
        return gate(h, documents.chain(id));
    }

    /**
     * The order's gate. DRAFT or PENDING: blocked, each unsigned step named. APPROVED: released by the Internal
     * Controller, to be cut and posted. POSTED: its pieces are in stock and may load (RELEASED for the gate), until
     * a delivery note against it posts (DELIVERED). Rejected or cancelled: void.
     */
    ReleaseGate gate(CuttingHeader h, List<ChainStep> chain) {
        record Dn(UUID id, String serial, String status, LocalDateTime postedAt, String postedBy) {}
        Dn dn = jdbc.sql("""
                SELECT n.document_id, dn.serial_no, dn.status, dn.posted_at, pu.full_name AS posted_by_name
                  FROM delivery_note n JOIN document dn ON dn.id = n.document_id
             LEFT JOIN app_user pu ON pu.id = dn.posted_by
                 WHERE n.cutting_order_id = :id AND dn.status <> 'CANCELLED'
                 ORDER BY dn.created_at DESC LIMIT 1
                """)
                .param("id", h.id(), Types.OTHER)
                .query((rs, n) -> new Dn(rs.getObject("document_id", UUID.class), rs.getString("serial_no"),
                        rs.getString("status"), KigaliTime.read(rs, "posted_at"), rs.getString("posted_by_name")))
                .optional().orElse(null);

        String status = h.status();
        if ("REJECTED".equals(status) || "CANCELLED".equals(status)) {
            return new ReleaseGate("VOID", h.serialNo(), List.of(), null, null, null, null, null, null,
                    status.toLowerCase(), "CUT");
        }
        if ("POSTED".equals(status)) {
            if (dn != null && "POSTED".equals(dn.status())) {
                return new ReleaseGate("DELIVERED", h.serialNo(), List.of(), null, null, dn.id(), dn.serial(),
                        dn.postedAt(), dn.postedBy(), null, "CUT");
            }
            return new ReleaseGate("RELEASED", h.serialNo(), List.of(), h.postedByName(), h.postedAt(),
                    dn == null ? null : dn.id(), dn == null ? null : dn.serial(), null, null, null, "CUT");
        }
        if ("APPROVED".equals(status)) {
            ChainStep last = chain.isEmpty() ? null : chain.get(chain.size() - 1);
            return new ReleaseGate("APPROVED", h.serialNo(), List.of(),
                    last == null ? null : last.actorName(), last == null ? null : last.decidedAt(),
                    null, null, null, null, null, "CUT");
        }
        var waiting = gateSteps.waiting(h.id(), h.branchId(), h.createdBy(), status, h.submittedAt(), chain,
                gateSteps.now());
        return new ReleaseGate("BLOCKED", h.serialNo(), waiting, null, null, null, null, null, null, null, "CUT");
    }

    // ---- writes ----------------------------------------------------------

    /** Raises a draft. It takes the branch of the place it cuts at; the database dates it and binds its chain. */
    @Transactional
    @PreAuthorize("hasAuthority('cutting.create')")
    public UUID create(CuttingForm form) {
        UUID branchId = locationBranch(form.getLocationId());
        documents.requireRightAt(DocumentKind.CUT, branchId, "cutting.create", "Raise a cutting order");
        BranchView branch = branches.findById(branchId).orElse(null);
        try {
            OpenedDocument opened = documents.open(DocumentKind.CUT, branchId, form.getReference(), form.getNotes(), null);
            jdbc.sql("""
                    INSERT INTO cutting_order (document_id, customer_id, location_id, customer_reference, customs_reference)
                    VALUES (:id, :customer, :location, :customerRef, :customs)
                    """)
                    .param("id", opened.id(), Types.OTHER)
                    .params(headerParams(form))
                    .update();
            List<UUID> made = insertLines(opened.id(), form);

            CuttingHeader after = requireHeader(opened.id());
            documents.auditInTransaction("CUT · " + after.serialNo(), opened.id(), AuditAction.CREATE,
                    snapshot(null, null, null, after, sheets(opened.id()), outputs(opened.id())), branch);
            auditMade(made, after.serialNo(), branch);
            return opened.id();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
    }

    /**
     * Edits a draft, provided nobody changed it since the form was opened. Whether it is still a draft is the
     * database's to say, and its reason is what the user reads.
     */
    @Transactional
    @PreAuthorize("hasAuthority('cutting.create')")
    public void update(UUID id, CuttingForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "cutting.create", "Edit");
        if (form.getVersion() == null) {
            throw documents.refused(d, "Edit", "This form did not say which version of " + d.serialNo()
                    + " it was opened from. Reload the order and try again.");
        }
        UUID branchId = locationBranch(form.getLocationId());
        if (!branchId.equals(d.branchId())) {
            throw documents.refused(d, "Edit", "That place is at another branch. " + d.serialNo()
                    + " belongs to " + d.branchName() + " and cuts there.");
        }
        CuttingHeader before = requireHeader(id);
        List<CuttingLines.Sheet> beforeSheets = sheets(id);
        List<CuttingLines.Output> beforeOutputs = outputs(id);
        List<UUID> made;
        try {
            documents.updateDraft(d, form.getVersion(), form.getReference(), form.getNotes());
            jdbc.sql("DELETE FROM cutting_order_output WHERE document_id = :id").param("id", id, Types.OTHER).update();
            jdbc.sql("DELETE FROM cutting_order_sheet WHERE document_id = :id").param("id", id, Types.OTHER).update();
            jdbc.sql("""
                    UPDATE cutting_order
                       SET customer_id = :customer, location_id = :location, customer_reference = :customerRef,
                           customs_reference = :customs
                     WHERE document_id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .params(headerParams(form))
                    .update();
            made = insertLines(id, form);
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Edit", e);
        }
        CuttingHeader after = requireHeader(id);
        AuditSnapshot snapshot = snapshot(before, beforeSheets, beforeOutputs, after, sheets(id), outputs(id));
        if (!snapshot.unchanged()) {
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, snapshot, d.branch());
        }
        auditMade(made, d.serialNo(), d.branch());
    }

    /** Submits the draft; the submitter signs step 1. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('cutting.create','cutting.verify','cutting.release')")
    public String submit(UUID id) {
        return documents.submit(id).status();
    }

    /** Approves or rejects the next step of the chain. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('cutting.create','cutting.verify','cutting.release')")
    public String sign(UUID id, boolean approve, String comment) {
        return documents.sign(id, approve, comment).status();
    }

    /**
     * Cancels a draft, pending or released order with a reason. A posted order is refused: its sheets have been
     * cut and its stock has moved.
     */
    @Transactional
    @PreAuthorize("hasAuthority('cutting.create')")
    public void cancel(UUID id, String reason) {
        documents.cancel(id, reason);
    }

    /**
     * Posts a released order, by a Finance officer who neither raised nor signed it, in one transaction: the order
     * goes POSTED; a CUT_CONSUME ticket takes the sheets OUT at the ledger's average cost; a CUT_OUTPUT ticket brings
     * the pieces and off-cuts IN, each line at its share by area of that value (cut_output_shares, which the
     * database recomputes at commit); both tickets go POSTED. If any of it is refused, all of it is. Returns the two
     * tickets' serials.
     */
    @Transactional
    @PreAuthorize("hasAuthority('cutting.post')")
    public String post(UUID id) {
        DocumentHeader d = documents.beginPost(id);      // locks; APPROVED -> POSTED by the poster, judged by the database
        CuttingHeader h = requireHeader(id);
        List<CuttingLines.Sheet> sheets = sheets(id);
        List<CuttingLines.Output> outputs = outputs(id);
        UUID poster = CurrentUser.id();

        // A place under a live count moves nothing (V15), and is refused here before the ledger is asked:
        // the ledger's own shortage message would read the place's book back.
        String freezing = freezingOf(h.locationId(), sheets, outputs);
        if (freezing != null) {
            throw documents.refused(d, "Post", freezing + " is counting what this order cuts or makes at "
                    + h.locationCode() + ", so nothing moves there until the count's verification is signed.");
        }

        OpenedDocument out;
        OpenedDocument in;
        BigDecimal valueOut = BigDecimal.ZERO;
        BigDecimal valueIn = BigDecimal.ZERO;
        int movements = 0;
        try {
            // Every place the order touches is locked up front, in one fixed order, whatever the line order.
            ledger.lockPlaces(Stream.concat(
                            sheets.stream().map(s -> new LedgerService.Place(s.itemId(), h.locationId())),
                            outputs.stream().map(o -> new LedgerService.Place(o.itemId(), h.locationId())))
                    .distinct().toList());

            // A ticket takes no line once stock has moved against it (V11): every line first, then the movements.
            out = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);
            insertTicket(out.id(), "CUT_CONSUME", "OUT", h.locationId(), null, id, h.customsReference());
            List<UUID> outLines = new ArrayList<>();
            for (CuttingLines.Sheet s : sheets) {
                outLines.add(insertTicketLine(out.id(), s.lineNo(), s.itemId(), s.baseUomId(), s.quantity(), s.binId()));
            }
            for (int i = 0; i < sheets.size(); i++) {
                CuttingLines.Sheet s = sheets.get(i);
                var entry = ledger.post(MovementRequest.issue(out.id(), outLines.get(i), d.branchId(), s.itemId(),
                        h.locationId(), s.binId(), s.quantity()), poster);
                valueOut = valueOut.add(entry.value());
                movements++;
            }

            // The database's own split of what just left, so the posting and the commit check cannot differ.
            Map<Integer, BigDecimal> shares = new HashMap<>();
            jdbc.sql("SELECT line_no, expected FROM cut_output_shares(:id)")
                    .param("id", id, Types.OTHER)
                    .query((rs, n) -> shares.put(rs.getInt("line_no"), rs.getBigDecimal("expected")))
                    .list();

            in = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);
            insertTicket(in.id(), "CUT_OUTPUT", "IN", null, h.locationId(), id, h.customsReference());
            List<UUID> inLines = new ArrayList<>();
            for (CuttingLines.Output o : outputs) {
                inLines.add(insertTicketLine(in.id(), o.lineNo(), o.itemId(), o.baseUomId(), o.quantity(), null));
            }
            for (int i = 0; i < outputs.size(); i++) {
                CuttingLines.Output o = outputs.get(i);
                var entry = ledger.post(MovementRequest.receipt(in.id(), inLines.get(i), d.branchId(), o.itemId(),
                        h.locationId(), null, o.quantity(), shares.get(o.lineNo())), poster);
                valueIn = valueIn.add(entry.value());
                movements++;
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

        CuttingLines.Areas areas = areas(id);
        documents.auditPosted(d, AuditSnapshot.of()
                .field("Status", "APPROVED", "POSTED")
                .value("Sheets out", out.serialNo())
                .value("Pieces and off-cuts in", in.serialNo())
                .value("Ledger movements", movements)
                .value("Value of the sheets (RWF)", valueOut)
                .value("Value of what was cut (RWF)", valueIn)
                .value("Waste (m²)", areas.wasteM2()));
        return out.serialNo() + " and " + in.serialNo();
    }

    // ---- helpers ---------------------------------------------------------

    private CuttingActions actionsFor(CuttingHeader h, DocumentHeader d, List<ChainStep> chain, ReleaseGate gate,
                                      String freezing) {
        UUID branch = h.branchId();
        boolean create = CurrentUser.holdsAt("cutting.create", branch);
        boolean signer = create || CurrentUser.holdsAt("cutting.verify", branch)
                         || CurrentUser.holdsAt("cutting.release", branch);
        boolean poster = CurrentUser.holdsAt("cutting.post", branch);
        String status = h.status();

        StepCheck submit = documents.canSubmit(d, chain);
        StepCheck sign = documents.canSign(d, chain);
        StepCheck cancel = documents.canCancel(d);
        StepCheck post = documents.canPost(d, chain);

        boolean canPost = "APPROVED".equals(status) && poster && post.allowed() && freezing == null;
        String postReason = null;
        if ("APPROVED".equals(status) && poster && !canPost) {
            postReason = !post.allowed() ? post.reason()
                    : freezing + " is counting what this order cuts or makes at " + h.locationCode()
                      + ", so nothing moves there until the count's verification is signed.";
        }
        boolean loadable = "RELEASED".equals(gate.state()) && gate.dnId() == null
                           && CurrentUser.holdsAt("dispatch.post", branch);
        return new CuttingActions(
                "DRAFT".equals(status) && create,
                submit.allowed(),
                "DRAFT".equals(status) && !submit.allowed() && signer ? submit.reason() : null,
                sign.allowed(),
                "PENDING".equals(status) && !sign.allowed() && signer ? sign.reason() : null,
                sign.step(),
                canPost, postReason,
                cancel.allowed(),
                !cancel.allowed() && create && List.of("DRAFT", "PENDING", "APPROVED", "POSTED").contains(status)
                        ? cancel.reason() : null,
                loadable);
    }

    /** The count, if any, freezing a place this order takes from or brings into (V15). */
    private String freezingOf(UUID locationId, List<CuttingLines.Sheet> sheets, List<CuttingLines.Output> outputs) {
        for (UUID item : Stream.concat(sheets.stream().map(CuttingLines.Sheet::itemId),
                outputs.stream().map(CuttingLines.Output::itemId)).distinct().toList()) {
            String serial = jdbc.sql("SELECT count_freezing(:item, :location)")
                    .param("item", item, Types.OTHER)
                    .param("location", locationId, Types.OTHER)
                    .query(String.class).optional().orElse(null);
            if (serial != null) return serial;
        }
        return null;
    }

    private void requireGateReader(UUID branchId) {
        if (!CurrentUser.holdsAt("cutting.view", branchId) && !CurrentUser.holdsAt("dispatch.view", branchId)) {
            CurrentUser.requireAt("dispatch.view", branchId);   // throws, naming the branch
        }
    }

    private CuttingHeader requireHeader(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new CuttingNotFoundException(id));
    }

    private UUID locationBranch(UUID locationId) {
        return jdbc.sql("SELECT branch_id FROM location WHERE id = :id")
                .param("id", locationId, Types.OTHER)
                .query(UUID.class).optional()
                .orElseThrow(() -> new ControlRefusedException("That place does not exist."));
    }

    private Map<String, Object> headerParams(CuttingForm form) {
        var params = new HashMap<String, Object>();
        params.put("customer", form.getCustomerId());
        params.put("location", form.getLocationId());
        params.put("customerRef", form.getCustomerReference());
        params.put("customs", form.getCustomsReference());
        return params;
    }

    /**
     * The sheet first (what is cut is checked against it), then the outputs numbered by position, each the item the
     * database finds or makes for its size. Returns the items made for the first time.
     */
    private List<UUID> insertLines(UUID documentId, CuttingForm form) {
        if (form.getOutputs().isEmpty() || form.getOutputs().size() > MAX_SIZES) {
            throw new ControlRefusedException("A cutting order cuts between 1 and " + MAX_SIZES + " sizes. Each size "
                    + "cut for the first time is an item made for good, so a larger job is split across orders.");
        }
        jdbc.sql("""
                INSERT INTO cutting_order_sheet (document_id, line_no, item_id, storage_bin_id, quantity)
                VALUES (:doc, 1, :item, :bin, :quantity)
                """)
                .param("doc", documentId, Types.OTHER)
                .param("item", form.getSheetItemId(), Types.OTHER)
                .param("bin", form.getSheetBinId(), Types.OTHER)
                .param("quantity", form.getSheets())
                .update();

        List<UUID> made = new ArrayList<>();
        int lineNo = 0;
        for (CuttingOutputForm line : form.getOutputs()) {
            lineNo++;
            boolean existed = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM item WHERE item_code = cut_item_code(cut_parent_of(:sheet), :w, :h))
                    """)
                    .param("sheet", form.getSheetItemId(), Types.OTHER)
                    .param("w", line.getWidthMm())
                    .param("h", line.getHeightMm())
                    .query(Boolean.class).single();
            UUID item = jdbc.sql("SELECT cut_item_for(cut_parent_of(:sheet), :w, :h)")
                    .param("sheet", form.getSheetItemId(), Types.OTHER)
                    .param("w", line.getWidthMm())
                    .param("h", line.getHeightMm())
                    .query(UUID.class).single();
            if (!existed) made.add(item);
            jdbc.sql("""
                    INSERT INTO cutting_order_output (document_id, line_no, kind, width_mm, height_mm, quantity, item_id)
                    VALUES (:doc, :lineNo, :kind, :w, :h, :quantity, :item)
                    """)
                    .param("doc", documentId, Types.OTHER)
                    .param("lineNo", (short) lineNo)
                    .param("kind", line.getKind())
                    .param("w", line.getWidthMm())
                    .param("h", line.getHeightMm())
                    .param("quantity", line.getQuantity())
                    .param("item", item, Types.OTHER)
                    .update();
        }
        return made;
    }

    /** A cut size made for the first time is an item created: audited as such, naming the order that made it. */
    private void auditMade(List<UUID> made, String serial, BranchView branch) {
        for (UUID item : made) {
            record Made(String code, String description, String parent, BigDecimal w, BigDecimal h, BigDecimal t) {}
            Made m = jdbc.sql("""
                    SELECT i.item_code, i.description, p.item_code AS parent, i.width_mm, i.height_mm, i.thickness_mm
                      FROM item i JOIN item p ON p.id = i.cut_from_item_id WHERE i.id = :id
                    """)
                    .param("id", item, Types.OTHER)
                    .query((rs, n) -> new Made(rs.getString("item_code"), rs.getString("description"),
                            rs.getString("parent"), rs.getBigDecimal("width_mm"), rs.getBigDecimal("height_mm"),
                            rs.getBigDecimal("thickness_mm")))
                    .single();
            audit.recordInTransaction("item", item, m.code() + " — " + m.description(), AuditAction.CREATE,
                    AuditSnapshot.of()
                            .value("Code", m.code())
                            .value("Description", m.description())
                            .value("Cut from", m.parent())
                            .value("Size (mm)", size(m.w(), m.h()))
                            .value("Thickness (mm)", m.t()),
                    branch, "A cut size made for cutting order " + serial
                            + ": one item per parent sheet and exact size, reused whenever that size is cut again.");
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

    /** One ticket line per order line, numbered as it, in the item's base unit: whole sheets, whole pieces. */
    private UUID insertTicketLine(UUID ticketId, int lineNo, UUID item, UUID uom, BigDecimal quantity, UUID bin) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ticket_line (id, ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
                VALUES (:id, :ticket, :lineNo, :item, :quantity, :uom, :quantity, :bin)
                """)
                .param("id", id, Types.OTHER)
                .param("ticket", ticketId, Types.OTHER)
                .param("lineNo", (short) lineNo)
                .param("item", item, Types.OTHER)
                .param("quantity", quantity)
                .param("uom", uom, Types.OTHER)
                .param("bin", bin, Types.OTHER)
                .update();
        return id;
    }

    private AuditSnapshot snapshot(CuttingHeader before, List<CuttingLines.Sheet> beforeSheets,
                                   List<CuttingLines.Output> beforeOutputs, CuttingHeader after,
                                   List<CuttingLines.Sheet> afterSheets, List<CuttingLines.Output> afterOutputs) {
        AuditSnapshot s = AuditSnapshot.of();
        s.field("Customer",           before == null ? null : before.customerName(), after.customerName());
        s.field("Cut at",             before == null ? null : before.locationCode(), after.locationCode());
        s.field("Customer reference", before == null ? null : before.customerReference(), after.customerReference());
        s.field("Customs reference",  before == null ? null : before.customsReference(), after.customsReference());
        s.field("Reference",          before == null ? null : before.reference(), after.reference());
        s.field("Notes",              before == null ? null : before.notes(), after.notes());
        s.field("Sheets",             beforeSheets == null ? null : describeSheets(beforeSheets), describeSheets(afterSheets));
        s.field("Cut",                beforeOutputs == null ? null : describeOutputs(beforeOutputs), describeOutputs(afterOutputs));
        return s;
    }

    private static String describeSheets(List<CuttingLines.Sheet> sheets) {
        return sheets.stream().map(l -> l.quantity().stripTrailingZeros().toPlainString() + " x " + l.itemCode()
                        + (l.binCode() == null ? "" : ", bin " + l.binCode()))
                .collect(Collectors.joining("; "));
    }

    private static String describeOutputs(List<CuttingLines.Output> outputs) {
        return outputs.stream().map(l -> l.lineNo() + ": " + l.quantity().stripTrailingZeros().toPlainString() + " x "
                        + size(l.widthMm(), l.heightMm()) + (l.piece() ? " piece" : " off-cut") + " (" + l.itemCode() + ")")
                .collect(Collectors.joining("; "));
    }

    static String size(BigDecimal w, BigDecimal h) {
        return w.stripTrailingZeros().toPlainString() + " × " + h.stripTrailingZeros().toPlainString();
    }

    private CuttingHeader mapHeader(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new CuttingHeader(
                rs.getObject("id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"),
                rs.getBoolean("branch_bonded"),
                rs.getString("serial_no"),
                rs.getString("status"),
                rs.getString("display_state"),
                rs.getObject("document_date", LocalDate.class),
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
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"),
                rs.getBoolean("customer_blocked"),
                rs.getObject("location_id", UUID.class),
                rs.getString("location_code"),
                rs.getString("location_name"),
                rs.getBoolean("location_bonded"),
                rs.getString("customer_reference"),
                rs.getString("customs_reference"));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class CuttingNotFoundException extends RuntimeException {
        public CuttingNotFoundException(UUID id) {
            super("No cutting order with id " + id);
        }
    }
}
