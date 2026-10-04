package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DispatchService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Delivery authorizations: draft, submit, sign, cancel, and the release gate they feed, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
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
import heritier.ntaganira.highbytes.wms.inventory.gate.GateSteps;
import heritier.ntaganira.highbytes.wms.inventory.gate.ReleaseGate;
import heritier.ntaganira.highbytes.wms.inventory.lookup.StockAt;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Delivery authorizations, on the document engine.
 *
 * <p>An authorization is a permission for goods to leave. It is signed down
 * the chain it was bound to on the day it was created (the 2026 policy chain
 * or the 2027 restructured one, read from its bound definition, never named
 * here), and it is APPROVED only when the last step, the Internal Controller's
 * release, has signed. It moves no stock and is never POSTED: what it
 * permitted having happened is a posted delivery note.
 *
 * <p>The release gate ({@link ReleaseGate}) is computed here from the chain:
 * which steps are unsigned, who could sign each, how long each has waited.
 * That is what the banner shows on every screen that lets someone decide
 * whether to load.
 */
@Service
@Transactional(readOnly = true)
public class DispatchService implements DeliveryAuthority {

    private static final String HEADER = """
            SELECT d.id, d.branch_id, b.name AS branch_name, b.is_bonded AS branch_bonded,
                   d.serial_no, d.status,
                   CASE WHEN d.status = 'APPROVED'
                        THEN CASE WHEN EXISTS (SELECT 1 FROM delivery_note n JOIN document dn ON dn.id = n.document_id
                                                WHERE n.authorization_id = d.id AND dn.status = 'POSTED')
                                  THEN 'DELIVERED' ELSE 'RELEASED' END
                        ELSE d.status END AS display_state,
                   d.document_date, d.reference, d.notes, d.version,
                   d.created_by, cu.full_name AS created_by_name, d.created_at, d.submitted_at, d.approved_at,
                   d.cancelled_at, xu.full_name AS cancelled_by_name, d.cancel_reason,
                   d.supersedes_document_id, sd.serial_no AS supersedes_serial,
                   a.customer_id, c.name AS customer_name, c.is_blocked AS customer_blocked,
                   a.location_id, l.code AS location_code, l.name AS location_name, l.is_bonded AS location_bonded,
                   a.customer_reference, a.delivery_address, a.customs_reference
              FROM document d
              JOIN document_type dt             ON dt.id = d.document_type_id AND dt.code = 'DAO'
              JOIN delivery_authorization a     ON a.document_id = d.id
              JOIN branch b                     ON b.id = d.branch_id
              JOIN customer c                   ON c.id = a.customer_id
              JOIN location l                   ON l.id = a.location_id
              JOIN app_user cu                  ON cu.id = d.created_by
         LEFT JOIN app_user xu                  ON xu.id = d.cancelled_by
         LEFT JOIN document sd                  ON sd.id = d.supersedes_document_id
             WHERE d.id = :id
            """;

    private static final String LINES = """
            SELECT dl.id, dl.line_no, dl.item_id, i.item_code, i.description, i.product_type, i.thickness_mm,
                   dl.uom_id, u.code AS uom_code, bu.code AS base_uom_code, dl.quantity, dl.qty_base_uom, dl.note
              FROM delivery_authorization_line dl
              JOIN item i ON i.id = dl.item_id
              JOIN uom u  ON u.id = dl.uom_id
              JOIN uom bu ON bu.id = i.base_uom_id
             WHERE dl.document_id = :id
             ORDER BY dl.line_no
            """;

    private static final String LIST = """
            SELECT * FROM (
              SELECT d.id, d.serial_no,
                     CASE WHEN d.status = 'APPROVED'
                          THEN CASE WHEN EXISTS (SELECT 1 FROM delivery_note n JOIN document dn ON dn.id = n.document_id
                                                  WHERE n.authorization_id = d.id AND dn.status = 'POSTED')
                                    THEN 'DELIVERED' ELSE 'RELEASED' END
                          ELSE d.status END AS display_state,
                     d.document_date, c.name AS customer_name, l.code AS location_code,
                     (b.is_bonded OR l.is_bonded) AS bonded,
                     (SELECT COUNT(*) FROM delivery_authorization_line dl WHERE dl.document_id = d.id) AS line_count,
                     cu.full_name AS created_by_name, nx.role_name AS awaiting_role, d.created_at
                FROM document d
                JOIN document_type dt         ON dt.id = d.document_type_id AND dt.code = 'DAO'
                JOIN branch b                 ON b.id = d.branch_id
                JOIN delivery_authorization a ON a.document_id = d.id
                JOIN customer c               ON c.id = a.customer_id
                JOIN location l               ON l.id = a.location_id
                JOIN app_user cu              ON cu.id = d.created_by
           LEFT JOIN LATERAL (
                     SELECT r.name AS role_name
                       FROM workflow_step ws JOIN role r ON r.id = ws.required_role_id
                      WHERE ws.workflow_definition_id = d.workflow_definition_id
                        AND d.status = 'PENDING'
                        AND NOT EXISTS (SELECT 1 FROM document_approval da
                                         WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                      ORDER BY ws.sequence_no LIMIT 1) nx ON TRUE
               WHERE d.branch_id = :branch) x
             WHERE (:status::text IS NULL OR x.display_state = :status)
             ORDER BY x.created_at DESC
             LIMIT 300
            """;

    /** Everything the view needs, gathered in one read-only pass. */
    public record Detail(DaoHeader header, List<DaoLineRow> lines, Map<UUID, List<StockAt>> stock,
                         List<ChainStep> chain, ChainInfo chainInfo, ReleaseGate gate, DaoActions actions) {}

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final DispatchLookupService lookups;
    private final BranchService branches;
    private final GateSteps gateSteps;

    public DispatchService(JdbcClient jdbc, DocumentService documents, DispatchLookupService lookups,
                           BranchService branches, GateSteps gateSteps) {
        this.gateSteps = gateSteps;
        this.jdbc = jdbc;
        this.documents = documents;
        this.lookups = lookups;
        this.branches = branches;
    }

    // ---- reads -----------------------------------------------------------

    @PreAuthorize("hasAuthority('dispatch.view')")
    public List<DaoRow> list(UUID branchId, String state, boolean awaitingMe) {
        Set<UUID> mine = documents.awaitingSignatureOf(DocumentKind.DAO, branchId, CurrentUser.id());
        return jdbc.sql(LIST)
                .param("branch", branchId, Types.OTHER)
                .param("status", state == null || state.isBlank() ? null : state)
                .query((rs, n) -> new DaoRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("display_state"),
                        rs.getObject("document_date", java.time.LocalDate.class),
                        rs.getString("customer_name"),
                        rs.getString("location_code"),
                        rs.getBoolean("bonded"),
                        rs.getInt("line_count"),
                        rs.getString("created_by_name"),
                        rs.getString("awaiting_role"),
                        false))
                .list().stream()
                .map(row -> row.markAwaitingMe(mine.contains(row.id())))
                .filter(row -> !awaitingMe || row.awaitingMe())
                .toList();
    }

    /** The authorization's header; a 404 for none, a 403 unless the viewer may read dispatch documents at its branch. */
    @PreAuthorize("hasAuthority('dispatch.view')")
    public DaoHeader find(UUID id) {
        DaoHeader header = requireHeader(id);
        CurrentUser.requireAt("dispatch.view", header.branchId());
        return header;
    }

    public List<DaoLineRow> lines(UUID id) {
        return jdbc.sql(LINES).param("id", id, Types.OTHER)
                .query((rs, n) -> new DaoLineRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getString("product_type"),
                        rs.getBigDecimal("thickness_mm"),
                        rs.getObject("uom_id", UUID.class),
                        rs.getString("uom_code"),
                        rs.getString("base_uom_code"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("qty_base_uom"),
                        rs.getString("note")))
                .list();
    }

    @PreAuthorize("hasAuthority('dispatch.view')")
    public Detail detail(UUID id) {
        DaoHeader header = find(id);
        List<DaoLineRow> lines = lines(id);
        DocumentHeader document = documents.header(id);
        List<ChainStep> chain = documents.chain(id);
        ReleaseGate gate = gate(header, document, chain);
        Map<UUID, List<StockAt>> stock = lookups.stockAt(header.locationId(),
                lines.stream().map(DaoLineRow::itemId).collect(Collectors.toSet()));
        return new Detail(header, lines, stock, chain, documents.chainInfo(id).orElse(null), gate,
                actionsFor(header, document, chain, gate));
    }

    /**
     * The release gate of an authorization, for any screen that must say
     * whether goods may leave against it. Readable by anyone who may read
     * dispatch documents at the branch.
     */
    @Override
    @PreAuthorize("hasAuthority('dispatch.view')")
    public ReleaseGate gateOf(UUID id) {
        DaoHeader header = find(id);
        return gate(header, documents.header(id), documents.chain(id));
    }

    @Override
    public String typeCode() {
        return DocumentKind.DAO.code();
    }

    @Override
    @PreAuthorize("hasAuthority('dispatch.view')")
    public DaoHeader asAuthorization(UUID id) {
        return find(id);
    }

    @Override
    @PreAuthorize("hasAuthority('dispatch.view')")
    public List<DaoLineRow> loadableLines(UUID id) {
        find(id);       // the right at the authorization's own branch
        return lines(id);
    }

    ReleaseGate gate(DaoHeader h, DocumentHeader d, List<ChainStep> chain) {
        return gate(h, d, chain, LocalDateTime.now(KigaliTime.ZONE));
    }

    /** The gate as it stands at {@code now}: how long each step has waited is measured to it. */
    ReleaseGate gate(DaoHeader h, DocumentHeader d, List<ChainStep> chain, LocalDateTime now) {
        record Dn(UUID id, String serial, String status, LocalDateTime postedAt, String postedBy) {}
        Dn dn = jdbc.sql("""
                SELECT n.document_id, dn.serial_no, dn.status, dn.posted_at, pu.full_name AS posted_by_name
                  FROM delivery_note n JOIN document dn ON dn.id = n.document_id
             LEFT JOIN app_user pu ON pu.id = dn.posted_by
                 WHERE n.authorization_id = :id AND dn.status <> 'CANCELLED'
                 ORDER BY dn.created_at DESC LIMIT 1
                """)
                .param("id", h.id(), Types.OTHER)
                .query((rs, n) -> new Dn(rs.getObject("document_id", UUID.class), rs.getString("serial_no"),
                        rs.getString("status"), KigaliTime.read(rs, "posted_at"), rs.getString("posted_by_name")))
                .optional().orElse(null);

        String status = h.status();
        if ("REJECTED".equals(status) || "CANCELLED".equals(status)) {
            return new ReleaseGate("VOID", h.serialNo(), List.of(), null, null, null, null, null, null,
                    status.toLowerCase());
        }
        if ("APPROVED".equals(status)) {
            if (dn != null && "POSTED".equals(dn.status())) {
                return new ReleaseGate("DELIVERED", h.serialNo(), List.of(), null, null, dn.id(), dn.serial(),
                        dn.postedAt(), dn.postedBy(), null);
            }
            ChainStep last = chain.isEmpty() ? null : chain.get(chain.size() - 1);
            return new ReleaseGate("RELEASED", h.serialNo(), List.of(),
                    last == null ? null : last.actorName(), last == null ? null : last.decidedAt(),
                    dn == null ? null : dn.id(), dn == null ? null : dn.serial(), null, null, null);
        }

        // DRAFT or PENDING: every unsigned step is named, with who can sign it and how long it has waited.
        var waiting = gateSteps.waiting(h.id(), h.branchId(), h.createdBy(), status, h.submittedAt(), chain, now);
        return new ReleaseGate("BLOCKED", h.serialNo(), waiting, null, null, null, null, null, null, null);
    }

    /** The authorization as its form holds it, for editing or for raising a corrected copy. */
    @PreAuthorize("hasAuthority('dispatch.create')")
    public DaoForm formFor(UUID id) {
        CurrentUser.requireAt("dispatch.create", documents.header(id).branchId());   // a read: refused, not recorded
        DaoHeader h = find(id);
        DaoForm form = new DaoForm();
        form.setId(h.id());
        form.setVersion(h.version());
        form.setCustomerId(h.customerId());
        form.setLocationId(h.locationId());
        form.setCustomerReference(h.customerReference());
        form.setDeliveryAddress(h.deliveryAddress());
        form.setCustomsReference(h.customsReference());
        form.setReference(h.reference());
        form.setNotes(h.notes());
        form.setSupersedesDocumentId(h.supersedesId());
        for (DaoLineRow row : lines(id)) {
            DaoLineForm line = new DaoLineForm();
            line.setItemId(row.itemId());
            line.setUomId(row.uomId());
            line.setQuantity(row.quantity());
            line.setNote(row.note());
            form.getLines().add(line);
        }
        return form;
    }

    // ---- writes ----------------------------------------------------------

    /** Raises a draft. It takes the branch of the dispatch location and is dated, and its chain bound, by the database. */
    @Transactional
    @PreAuthorize("hasAuthority('dispatch.create')")
    public UUID create(DaoForm form) {
        UUID branchId = locationBranch(form.getLocationId());
        documents.requireRightAt(DocumentKind.DAO, branchId, "dispatch.create", "Raise a delivery authorization");
        requireCorrectable(form.getSupersedesDocumentId(), branchId);
        try {
            OpenedDocument opened = documents.open(DocumentKind.DAO, branchId, form.getReference(),
                    form.getNotes(), form.getSupersedesDocumentId());
            insertHeader(opened.id(), form);
            insertLines(opened.id(), form.getLines());

            DaoHeader after = requireHeader(opened.id());
            documents.auditInTransaction("DAO · " + after.serialNo(), opened.id(), AuditAction.CREATE,
                    snapshot(null, null, after, lines(opened.id())), branches.findById(branchId).orElse(null));
            return opened.id();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
    }

    /**
     * Edits a draft, provided nobody changed it since the form was opened.
     * Whether it is still a draft is the database's to say, and its reason is
     * what the user reads.
     */
    @Transactional
    @PreAuthorize("hasAuthority('dispatch.create')")
    public void update(UUID id, DaoForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "dispatch.create", "Edit");
        if (form.getVersion() == null) {
            throw documents.refused(d, "Edit", "This form did not say which version of " + d.serialNo()
                    + " it was opened from. Reload the authorization and try again.");
        }
        UUID branchId = locationBranch(form.getLocationId());
        if (!branchId.equals(d.branchId())) {
            throw documents.refused(d, "Edit", "That location is at another branch. " + d.serialNo()
                    + " belongs to " + d.branchName() + " and releases goods from there.");
        }

        DaoHeader before = requireHeader(id);
        List<DaoLineRow> beforeLines = lines(id);
        try {
            documents.updateDraft(d, form.getVersion(), form.getReference(), form.getNotes());
            jdbc.sql("DELETE FROM delivery_authorization_line WHERE document_id = :id")
                    .param("id", id, Types.OTHER).update();
            jdbc.sql("""
                    UPDATE delivery_authorization
                       SET customer_id = :customer, location_id = :location, customer_reference = :customerRef,
                           delivery_address = :address, customs_reference = :customs
                     WHERE document_id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .params(headerParams(form))
                    .update();
            insertLines(id, form.getLines());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Edit", e);
        }

        DaoHeader after = requireHeader(id);
        AuditSnapshot snapshot = snapshot(before, beforeLines, after, lines(id));
        if (!snapshot.unchanged()) {
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, snapshot, d.branch());
        }
    }

    /** Submits the draft; the submitter signs step 1. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('dispatch.create','dispatch.verify','dispatch.countersign','dispatch.release')")
    public String submit(UUID id) {
        return documents.submit(id).status();
    }

    /** Approves or rejects the next step of the chain. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('dispatch.create','dispatch.verify','dispatch.countersign','dispatch.release')")
    public String sign(UUID id, boolean approve, String comment) {
        return documents.sign(id, approve, comment).status();
    }

    /**
     * Cancels a draft, pending or released authorization with a reason. The
     * database refuses one whose delivery has posted, and one under which a
     * delivery note is still live.
     */
    @Transactional
    @PreAuthorize("hasAuthority('dispatch.create')")
    public void cancel(UUID id, String reason) {
        documents.cancel(id, reason);
    }

    // ---- helpers ---------------------------------------------------------

    private DaoActions actionsFor(DaoHeader h, DocumentHeader d, List<ChainStep> chain, ReleaseGate gate) {
        UUID branch = h.branchId();
        boolean create = CurrentUser.holdsAt("dispatch.create", branch);
        boolean signer = create || CurrentUser.holdsAt("dispatch.verify", branch)
                         || CurrentUser.holdsAt("dispatch.countersign", branch)
                         || CurrentUser.holdsAt("dispatch.release", branch);

        StepCheck submit = documents.canSubmit(d, chain);
        StepCheck sign = documents.canSign(d, chain);
        StepCheck cancel = documents.canCancel(d);
        String status = h.status();
        boolean delivered = "DELIVERED".equals(h.displayState());
        boolean loadable = "RELEASED".equals(gate.state()) && gate.dnId() == null
                           && CurrentUser.holdsAt("dispatch.post", branch);
        return new DaoActions(
                "DRAFT".equals(status) && create,
                submit.allowed(),
                "DRAFT".equals(status) && !submit.allowed() && signer ? submit.reason() : null,
                sign.allowed(),
                "PENDING".equals(status) && !sign.allowed() && signer ? sign.reason() : null,
                sign.step(),
                cancel.allowed() && !delivered,
                delivered && create ? h.serialNo() + " has been delivered, so it cannot be cancelled: the posted delivery "
                        + "note is what left the premises. A wrong delivery is corrected by a reversing document."
                        : (!cancel.allowed() && create && ("APPROVED".equals(status) || "PENDING".equals(status)
                                || "DRAFT".equals(status)) ? cancel.reason() : null),
                create && ("REJECTED".equals(status) || "CANCELLED".equals(status)),
                loadable);
    }

    private DaoHeader requireHeader(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new DaoNotFoundException(id));
    }

    private UUID locationBranch(UUID locationId) {
        return jdbc.sql("SELECT branch_id FROM location WHERE id = :id")
                .param("id", locationId, Types.OTHER)
                .query(UUID.class).optional()
                .orElseThrow(() -> new ControlRefusedException("That dispatch location does not exist."));
    }

    /** A corrected copy replaces a rejected or cancelled authorization at the same branch; the form carries the id. */
    private void requireCorrectable(UUID supersedesId, UUID branchId) {
        if (supersedesId == null) return;
        boolean ok = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM document d JOIN document_type dt ON dt.id = d.document_type_id
                                WHERE d.id = :id AND dt.code = 'DAO' AND d.branch_id = :branch
                                  AND d.status IN ('REJECTED', 'CANCELLED'))
                """)
                .param("id", supersedesId, Types.OTHER)
                .param("branch", branchId, Types.OTHER)
                .query(Boolean.class).single();
        if (!ok) {
            throw new ControlRefusedException("A corrected copy replaces a rejected or cancelled delivery authorization "
                    + "at this branch. The one it names is neither.");
        }
    }

    private void insertHeader(UUID id, DaoForm form) {
        jdbc.sql("""
                INSERT INTO delivery_authorization (document_id, customer_id, location_id, customer_reference,
                                                    delivery_address, customs_reference)
                VALUES (:id, :customer, :location, :customerRef, :address, :customs)
                """)
                .param("id", id, Types.OTHER)
                .params(headerParams(form))
                .update();
    }

    private Map<String, Object> headerParams(DaoForm form) {
        var params = new HashMap<String, Object>();
        params.put("customer", form.getCustomerId());
        params.put("location", form.getLocationId());
        params.put("customerRef", form.getCustomerReference());
        params.put("address", form.getDeliveryAddress());
        params.put("customs", form.getCustomsReference());
        return params;
    }

    /** Numbered by position, so the lines are always 1..n whatever was added or removed on the form. */
    private void insertLines(UUID documentId, List<DaoLineForm> lines) {
        int lineNo = 0;
        for (DaoLineForm line : lines) {
            lineNo++;
            jdbc.sql("""
                    INSERT INTO delivery_authorization_line (document_id, line_no, item_id, uom_id, quantity,
                                                             qty_base_uom, note)
                    VALUES (:doc, :lineNo, :item, :uom, :quantity, :base, :note)
                    """)
                    .param("doc", documentId, Types.OTHER)
                    .param("lineNo", (short) lineNo)
                    .param("item", line.getItemId(), Types.OTHER)
                    .param("uom", line.getUomId(), Types.OTHER)
                    .param("quantity", line.getQuantity())
                    .param("base", baseQuantity(line))
                    .param("note", line.getNote())
                    .update();
        }
    }

    /**
     * Quantity in the item's base unit: quantity times the conversion factor,
     * rounded half up to three places, which is exactly the rounding the
     * database checks it against.
     */
    private BigDecimal baseQuantity(DaoLineForm line) {
        record Conversion(String item, String unit, String base, BigDecimal factor) {}
        Conversion c = jdbc.sql("""
                SELECT i.item_code, u.code AS uom_code, bu.code AS base_code,
                       uom_factor_to_base(i.id, u.id) AS factor
                  FROM item i JOIN uom u ON u.id = :uom JOIN uom bu ON bu.id = i.base_uom_id
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
                    + "Authorize it in " + c.base() + ", or have a conversion added to the item.");
        }
        return line.getQuantity().multiply(c.factor()).setScale(3, RoundingMode.HALF_UP);
    }

    private AuditSnapshot snapshot(DaoHeader before, List<DaoLineRow> beforeLines,
                                   DaoHeader after, List<DaoLineRow> afterLines) {
        AuditSnapshot s = AuditSnapshot.of();
        s.field("Customer",          before == null ? null : before.customerName(), after.customerName());
        s.field("Dispatch location", before == null ? null : before.locationCode(), after.locationCode());
        s.field("Customer reference", before == null ? null : before.customerReference(), after.customerReference());
        s.field("Delivery address",  before == null ? null : before.deliveryAddress(), after.deliveryAddress());
        s.field("Customs reference", before == null ? null : before.customsReference(), after.customsReference());
        s.field("Reference",         before == null ? null : before.reference(), after.reference());
        s.field("Notes",             before == null ? null : before.notes(), after.notes());
        s.field("Corrects",          before == null ? null : before.supersedesSerial(), after.supersedesSerial());
        s.field("Lines",             beforeLines == null ? null : describe(beforeLines), describe(afterLines));
        return s;
    }

    private static String describe(List<DaoLineRow> lines) {
        return lines.stream().map(l -> l.lineNo() + ": " + l.itemCode() + " x "
                        + l.quantity().stripTrailingZeros().toPlainString() + " " + l.uomCode())
                .collect(Collectors.joining("; "));
    }

    private DaoHeader mapHeader(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DaoHeader(
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
                KigaliTime.read(rs, "cancelled_at"),
                rs.getString("cancelled_by_name"),
                rs.getString("cancel_reason"),
                rs.getObject("supersedes_document_id", UUID.class),
                rs.getString("supersedes_serial"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"),
                rs.getBoolean("customer_blocked"),
                rs.getObject("location_id", UUID.class),
                rs.getString("location_code"),
                rs.getString("location_name"),
                rs.getBoolean("location_bonded"),
                rs.getString("customer_reference"),
                rs.getString("delivery_address"),
                rs.getString("customs_reference"));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class DaoNotFoundException extends RuntimeException {
        public DaoNotFoundException(UUID id) {
            super("No delivery authorization with id " + id);
        }
    }
}
