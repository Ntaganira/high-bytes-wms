package heritier.ntaganira.highbytes.wms.inventory.opening;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.opening
 * - File       : OpeningService.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Opening stock balances: the cutover from QuickBooks, raised, signed four times, and posted once per place
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
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The cutover. When the system replaces QuickBooks every warehouse already
 * holds stock, and invariant 2 means that stock cannot be loaded by writing
 * ledger rows: it needs a document, and the document needs signatures.
 *
 * <p>So an opening balance behaves like every other document — draft, four
 * mandatory signatures, posted by Finance — with two rules of its own that
 * V19 enforces structurally:
 *
 * <ul>
 *   <li><strong>Once per location, ever.</strong> A partial unique index, not
 *       a count: two concurrent postings would each find the other
 *       uncommitted and both pass a check.</li>
 *   <li><strong>Before anything else has moved stock at the branch.</strong>
 *       Other opening balances are excluded, so a branch with a main store
 *       and a bonded store loads each; a branch that has started trading
 *       loads nothing, because stock arriving underneath months of trading
 *       would leave every figure above it measuring from nothing.</li>
 * </ul>
 *
 * <p>Both are checked again here, before the form is opened and before the
 * post, so the person reads the reason on a screen instead of keying three
 * hundred lines and being refused at the end. The database remains the
 * authority: these reads are a courtesy, not the control.
 */
@Service
@Transactional(readOnly = true)
public class OpeningService {

    private static final String HEADER = """
            SELECT d.id, d.branch_id, b.name AS branch_name, b.is_bonded AS branch_bonded,
                   d.serial_no, d.status, d.document_date, d.reference, d.notes, d.version,
                   d.created_by, cu.full_name AS created_by_name, d.created_at,
                   d.submitted_at, d.approved_at, d.posted_at, d.posted_by,
                   pu.full_name AS posted_by_name, d.cancelled_at,
                   xu.full_name AS cancelled_by_name, d.cancel_reason,
                   o.location_id, l.code AS location_code, l.name AS location_name,
                   l.is_bonded AS location_bonded,
                   o.as_at_date, o.source_system, o.basis_note, o.customs_reference, o.is_posted
              FROM document d
              JOIN opening_balance o ON o.document_id = d.id
              JOIN branch b          ON b.id = d.branch_id
              JOIN location l        ON l.id = o.location_id
              LEFT JOIN app_user cu  ON cu.id = d.created_by
              LEFT JOIN app_user pu  ON pu.id = d.posted_by
              LEFT JOIN app_user xu  ON xu.id = d.cancelled_by
             WHERE d.id = :id
            """;

    private static final String LINES = """
            SELECT ol.id, ol.line_no, ol.item_id, i.item_code, i.description, i.product_type,
                   ol.uom_id, u.code AS uom_code, ol.quantity, ol.qty_base_uom,
                   bu.code AS base_uom_code, ol.unit_cost,
                   ol.storage_bin_id, sb.bin_code, ol.measured_thickness_mm, ol.note
              FROM opening_balance_line ol
              JOIN item i   ON i.id = ol.item_id
              JOIN uom u    ON u.id = ol.uom_id
              JOIN uom bu   ON bu.id = i.base_uom_id
              LEFT JOIN storage_bin sb ON sb.id = ol.storage_bin_id
             WHERE ol.document_id = :id
             ORDER BY ol.line_no
            """;

    private static final String LIST = """
            SELECT d.id, d.serial_no, d.status, d.document_date, o.as_at_date,
                   l.code AS location_code, (l.is_bonded OR b.is_bonded) AS bonded,
                   (SELECT COUNT(*) FROM opening_balance_line x WHERE x.document_id = d.id) AS line_count,
                   (SELECT COALESCE(SUM(x.qty_base_uom * x.unit_cost), 0)
                      FROM opening_balance_line x WHERE x.document_id = d.id) AS value_rwf,
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
              JOIN opening_balance o ON o.document_id = d.id
              JOIN location l        ON l.id = o.location_id
              JOIN branch b          ON b.id = d.branch_id
              LEFT JOIN app_user cu  ON cu.id = d.created_by
             WHERE d.branch_id = :branchId
               AND (:status::text IS NULL OR d.status = :status)
             ORDER BY d.document_date DESC, d.serial_no DESC
            """;

    /** Everything one sheet's page needs, in one read. */
    public record Detail(OpeningHeader header, List<OpeningLineRow> lines, List<ChainStep> chain,
                         ChainInfo chainInfo, OpeningActions actions, BigDecimal valueRwf) {}

    /** Why a place cannot be loaded, or null when it can. */
    public record Obstacle(boolean alreadyLoaded, boolean branchHasTraded, String reason) {

        static final Obstacle NONE = new Obstacle(false, false, null);

        public boolean blocked() {
            return reason != null;
        }
    }

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final LedgerService ledger;
    private final BranchService branches;

    public OpeningService(JdbcClient jdbc, DocumentService documents, LedgerService ledger,
                          BranchService branches) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.ledger = ledger;
        this.branches = branches;
    }

    // ---- reads -----------------------------------------------------------

    @PreAuthorize("hasAuthority('opening.view')")
    public List<OpeningRow> list(UUID branchId, String status, boolean awaitingMe) {
        List<OpeningRow> rows = jdbc.sql(LIST)
                .param("branchId", branchId, Types.OTHER)
                .param("status", status)
                .query((rs, n) -> new OpeningRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("status"),
                        rs.getObject("document_date", LocalDate.class),
                        rs.getObject("as_at_date", LocalDate.class),
                        rs.getString("location_code"),
                        rs.getBoolean("bonded"),
                        rs.getInt("line_count"),
                        rs.getBigDecimal("value_rwf"),
                        rs.getString("created_by_name"),
                        rs.getString("awaiting_role"),
                        false))
                .list();

        Set<UUID> mine = documents.awaitingSignatureOf(DocumentKind.OPB, branchId, CurrentUser.id());
        List<OpeningRow> marked = rows.stream().map(r -> r.markAwaitingMe(mine.contains(r.id()))).toList();
        return awaitingMe ? marked.stream().filter(OpeningRow::awaitingMe).toList() : marked;
    }

    @PreAuthorize("hasAuthority('opening.view')")
    public OpeningHeader find(UUID id) {
        return requireHeader(id);
    }

    public List<OpeningLineRow> lines(UUID id) {
        return jdbc.sql(LINES).param("id", id, Types.OTHER)
                .query((rs, n) -> new OpeningLineRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("item_code"),
                        rs.getString("description"),
                        rs.getString("product_type"),
                        rs.getObject("uom_id", UUID.class),
                        rs.getString("uom_code"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("qty_base_uom"),
                        rs.getString("base_uom_code"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getObject("storage_bin_id", UUID.class),
                        rs.getString("bin_code"),
                        rs.getBigDecimal("measured_thickness_mm"),
                        rs.getString("note")))
                .list();
    }

    @PreAuthorize("hasAuthority('opening.view')")
    public Detail detail(UUID id) {
        OpeningHeader header = requireHeader(id);
        DocumentHeader d = documents.header(id);
        List<ChainStep> chain = documents.chain(id);
        List<OpeningLineRow> lines = lines(id);
        BigDecimal value = lines.stream().map(OpeningLineRow::valueRwf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Detail(header, lines, chain, documents.chainInfo(id).orElse(null),
                actionsFor(header, d, chain), value);
    }

    /**
     * Whether a place may still be loaded, and why not when it may not. Both
     * reasons are V19's, asked here so the form can say so before anything is
     * keyed rather than after.
     */
    public Obstacle obstacleAt(UUID locationId) {
        if (locationId == null) return Obstacle.NONE;

        record Place(UUID branchId, String locationCode, String branchName) {}
        Place place = jdbc.sql("""
                SELECT l.branch_id, l.code AS location_code, b.name AS branch_name
                  FROM location l JOIN branch b ON b.id = l.branch_id
                 WHERE l.id = :id
                """)
                .param("id", locationId, Types.OTHER)
                .query((rs, n) -> new Place(rs.getObject("branch_id", UUID.class),
                        rs.getString("location_code"), rs.getString("branch_name")))
                .optional().orElse(null);
        if (place == null) return Obstacle.NONE;

        String loaded = jdbc.sql("""
                SELECT d.serial_no
                  FROM opening_balance o JOIN document d ON d.id = o.document_id
                 WHERE o.location_id = :id AND o.is_posted
                 LIMIT 1
                """)
                .param("id", locationId, Types.OTHER)
                .query(String.class).optional().orElse(null);
        if (loaded != null) {
            return new Obstacle(true, false, place.locationCode()
                    + " already has its opening balance: " + loaded
                    + ". A place is loaded once, and what is there now is corrected by a stock count.");
        }

        record Traded(String serialNo, LocalDate on) {}
        Traded traded = jdbc.sql("""
                SELECT d.serial_no, m.business_date
                  FROM stock_movement m
                  JOIN document d ON d.id = m.document_id
                  LEFT JOIN transaction_ticket t ON t.document_id = m.document_id
                  LEFT JOIN document src         ON src.id = t.source_document_id
                  LEFT JOIN document_type st     ON st.id = src.document_type_id
                 WHERE m.branch_id = :branchId AND COALESCE(st.code, '') <> 'OPB'
                 ORDER BY m.id
                 LIMIT 1
                """)
                .param("branchId", place.branchId(), Types.OTHER)
                .query((rs, n) -> new Traded(rs.getString("serial_no"),
                        rs.getObject("business_date", LocalDate.class)))
                .optional().orElse(null);
        if (traded != null) {
            return new Obstacle(false, true, "Stock has already moved at " + place.branchName()
                    + " (" + traded.serialNo() + ", on " + traded.on()
                    + "), so no opening balance can be posted there. An opening balance is the first entry in the"
                    + " ledger at a branch; stock found later is brought in by a count adjustment.");
        }
        return Obstacle.NONE;
    }

    /** Whether the place, or the branch it is at, is bonded: the customs reference is then required. */
    public boolean loadsBonded(UUID locationId) {
        if (locationId == null) return false;
        return jdbc.sql("""
                SELECT (l.is_bonded OR b.is_bonded) FROM location l JOIN branch b ON b.id = l.branch_id
                 WHERE l.id = :id
                """)
                .param("id", locationId, Types.OTHER).query(Boolean.class).optional().orElse(false);
    }

    /** The sheet as its form holds it, for editing. */
    @PreAuthorize("hasAuthority('opening.create')")
    public OpeningForm formFor(UUID id) {
        CurrentUser.requireAt("opening.create", documents.header(id).branchId());   // a read: refused, not recorded
        OpeningHeader o = requireHeader(id);
        OpeningForm form = new OpeningForm();
        form.setId(o.id());
        form.setVersion(o.version());
        form.setLocationId(o.locationId());
        form.setAsAtDate(o.asAtDate());
        form.setSourceSystem(o.sourceSystem());
        form.setBasisNote(o.basisNote());
        form.setCustomsReference(o.customsReference());
        form.setReference(o.reference());
        form.setNotes(o.notes());
        for (OpeningLineRow row : lines(id)) {
            OpeningLineForm line = new OpeningLineForm();
            line.setItemId(row.itemId());
            line.setUomId(row.uomId());
            line.setQuantity(row.quantity());
            line.setUnitCost(row.unitCost());
            line.setStorageBinId(row.storageBinId());
            line.setMeasuredThicknessMm(row.measuredThicknessMm());
            line.setNote(row.note());
            form.getLines().add(line);
        }
        return form;
    }

    // ---- writes ----------------------------------------------------------

    /**
     * Raises a draft. The document takes the branch of the place being
     * loaded, and is dated, and its chain bound, by the database.
     */
    @Transactional
    @PreAuthorize("hasAuthority('opening.create')")
    public UUID create(OpeningForm form) {
        UUID branchId = locationBranch(form.getLocationId());
        documents.requireRightAt(DocumentKind.OPB, branchId, "opening.create", "Raise an opening stock balance");

        Obstacle obstacle = obstacleAt(form.getLocationId());
        if (obstacle.blocked()) {
            throw new ControlRefusedException(obstacle.reason());
        }

        try {
            OpenedDocument opened = documents.open(DocumentKind.OPB, branchId, form.getReference(),
                    form.getNotes(), null);
            insertHeader(opened.id(), form, branchId);
            insertLines(opened.id(), form.getLines());

            OpeningHeader after = requireHeader(opened.id());
            List<OpeningLineRow> afterLines = lines(opened.id());
            documents.auditInTransaction("OPB · " + after.serialNo(), opened.id(), AuditAction.CREATE,
                    snapshot(null, null, after, afterLines),
                    branches.findById(branchId).orElse(null));
            return opened.id();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
    }

    /**
     * Edits a draft, provided nobody changed it since the form was opened.
     * Whether the sheet is still a draft is the database's to say: it refuses
     * a change to the header or the lines once the sheet has left DRAFT, and
     * its reason is what the user reads.
     */
    @Transactional
    @PreAuthorize("hasAuthority('opening.create')")
    public void update(UUID id, OpeningForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "opening.create", "Edit");
        if (form.getVersion() == null) {
            throw documents.refused(d, "Edit", "This form did not say which version of " + d.serialNo()
                    + " it was opened from. Reload the sheet and try again.");
        }
        UUID branchId = locationBranch(form.getLocationId());
        if (!branchId.equals(d.branchId())) {
            throw documents.refused(d, "Edit", "That place is at another branch. " + d.serialNo()
                    + " belongs to " + d.branchName() + " and loads stock there.");
        }

        OpeningHeader before = requireHeader(id);
        List<OpeningLineRow> beforeLines = lines(id);
        try {
            documents.updateDraft(d, form.getVersion(), form.getReference(), form.getNotes());
            // Lines go first: a bin in the previous location must not outlive a change of place.
            jdbc.sql("DELETE FROM opening_balance_line WHERE document_id = :id")
                    .param("id", id, Types.OTHER).update();
            jdbc.sql("""
                    UPDATE opening_balance
                       SET location_id = :location, as_at_date = :asAt, source_system = :source,
                           basis_note = :basis, customs_reference = :customs
                     WHERE document_id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .param("location", form.getLocationId(), Types.OTHER)
                    .param("asAt", form.getAsAtDate())
                    .param("source", form.getSourceSystem())
                    .param("basis", form.getBasisNote())
                    .param("customs", blankToNull(form.getCustomsReference()))
                    .update();
            insertLines(id, form.getLines());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Edit", e);
        }

        OpeningHeader after = requireHeader(id);
        List<OpeningLineRow> afterLines = lines(id);
        AuditSnapshot snapshot = snapshot(before, beforeLines, after, afterLines);
        if (!snapshot.unchanged()) {
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, snapshot, d.branch());
        }
    }

    /** Submits the draft; the submitter signs step 1. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('opening.create','opening.verify','opening.approve')")
    public String submit(UUID id) {
        return documents.submit(id).status();
    }

    /** Approves or rejects the next step of the chain. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('opening.create','opening.verify','opening.approve')")
    public String sign(UUID id, boolean approve, String comment) {
        return documents.sign(id, approve, comment).status();
    }

    @Transactional
    @PreAuthorize("hasAuthority('opening.create')")
    public void cancel(UUID id, String reason) {
        documents.cancel(id, reason);
    }

    /**
     * Posts an approved sheet to the ledger, in one transaction: the
     * transaction ticket, its lines, and one IN movement per line. Returns
     * the serial of the ticket it raised.
     *
     * <p>The movements carry the line's own carrying cost, so the place's
     * moving average starts at exactly what the old books held. Nothing here
     * decides whether the posting is allowed: V19's two controls judge that,
     * and their refusals are shown to the user as their own reason.
     */
    @Transactional
    @PreAuthorize("hasAuthority('opening.post')")
    public String post(UUID id) {
        DocumentHeader d = documents.beginPost(id);      // locks, APPROVED -> POSTED by the poster
        OpeningHeader o = requireHeader(id);
        List<OpeningLineRow> lines = lines(id);
        UUID poster = CurrentUser.id();

        OpenedDocument ticket;
        List<Long> movementIds = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        try {
            // Every place this posting moves is locked up front, in one fixed order, whatever the line order.
            ledger.lockPlaces(lines.stream()
                    .map(l -> new LedgerService.Place(l.itemId(), o.locationId())).toList());
            ticket = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);

            jdbc.sql("""
                    INSERT INTO transaction_ticket (document_id, movement_type, direction, to_location_id,
                                                    source_document_id, customs_reference, total_value)
                    VALUES (:id, 'OPENING', 'IN', :location, :source, :customs, :total)
                    """)
                    .param("id", ticket.id(), Types.OTHER)
                    .param("location", o.locationId(), Types.OTHER)
                    .param("source", id, Types.OTHER)
                    .param("customs", o.customsReference())
                    .param("total", lines.stream().map(OpeningLineRow::valueRwf)
                            .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .update();

            List<UUID> ticketLineIds = new ArrayList<>();
            for (OpeningLineRow line : lines) {
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
                        .param("unitValue", line.valueRwf().divide(line.quantity(), 4, RoundingMode.HALF_UP))
                        .param("total", line.valueRwf())
                        .param("bin", line.storageBinId(), Types.OTHER)
                        .update();
            }

            for (int i = 0; i < lines.size(); i++) {
                OpeningLineRow line = lines.get(i);
                var entry = ledger.post(MovementRequest.receipt(ticket.id(), ticketLineIds.get(i), d.branchId(),
                        line.itemId(), o.locationId(), line.storageBinId(), line.quantityBase(),
                        line.valueRwf()), poster);
                movementIds.add(entry.movementId());
                total = total.add(line.valueRwf());
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
                .value("Place loaded", o.locationCode())
                .value("Figures as at", o.asAtDate())
                .value("Taken from", o.sourceSystem())
                .value("Ledger movements", movementIds.size())
                .value("Opening value (RWF)", total);
        documents.auditPosted(d, snapshot);
        return ticket.serialNo();
    }

    // ---- helpers ---------------------------------------------------------

    private OpeningActions actionsFor(OpeningHeader o, DocumentHeader d, List<ChainStep> chain) {
        UUID branch = o.branchId();
        boolean create = CurrentUser.holdsAt("opening.create", branch);
        boolean signer = create || CurrentUser.holdsAt("opening.verify", branch)
                         || CurrentUser.holdsAt("opening.approve", branch);
        boolean poster = CurrentUser.holdsAt("opening.post", branch);

        StepCheck submit = documents.canSubmit(d, chain);
        StepCheck sign = documents.canSign(d, chain);
        StepCheck post = documents.canPost(d, chain);
        StepCheck cancel = documents.canCancel(d);

        // Only worth asking while it could still post: once posted, the place
        // having an opening balance is this very sheet.
        Obstacle obstacle = "POSTED".equals(o.status()) || "CANCELLED".equals(o.status())
                ? Obstacle.NONE : obstacleAt(o.locationId());

        String status = o.status();
        String postReason = "APPROVED".equals(status) && poster
                ? (obstacle.blocked() ? obstacle.reason() : (post.allowed() ? null : post.reason()))
                : null;

        return new OpeningActions(
                "DRAFT".equals(status) && create,
                // The obstacle bears on posting, not on submitting: a sheet
                // raised before the branch traded may still be signed, and
                // will then be refused at the post with the reason shown here.
                submit.allowed(),
                "DRAFT".equals(status) && !submit.allowed() && signer ? submit.reason() : null,
                sign.allowed(),
                "PENDING".equals(status) && !sign.allowed() && signer ? sign.reason() : null,
                sign.step(),
                post.allowed() && !obstacle.blocked(),
                postReason,
                cancel.allowed(),
                "POSTED".equals(status) && (create || poster) ? cancel.reason() : null,
                obstacle.alreadyLoaded(),
                obstacle.branchHasTraded());
    }

    private void insertHeader(UUID id, OpeningForm form, UUID branchId) {
        jdbc.sql("""
                INSERT INTO opening_balance (document_id, branch_id, location_id, as_at_date,
                                             source_system, basis_note, customs_reference)
                VALUES (:id, :branch, :location, :asAt, :source, :basis, :customs)
                """)
                .param("id", id, Types.OTHER)
                .param("branch", branchId, Types.OTHER)
                .param("location", form.getLocationId(), Types.OTHER)
                .param("asAt", form.getAsAtDate())
                .param("source", form.getSourceSystem())
                .param("basis", form.getBasisNote())
                .param("customs", blankToNull(form.getCustomsReference()))
                .update();
    }

    private void insertLines(UUID documentId, List<OpeningLineForm> lines) {
        int lineNo = 0;
        for (OpeningLineForm line : lines) {
            lineNo++;
            jdbc.sql("""
                    INSERT INTO opening_balance_line (document_id, line_no, item_id, uom_id, quantity,
                           qty_base_uom, unit_cost, storage_bin_id, measured_thickness_mm, note)
                    VALUES (:doc, :lineNo, :item, :uom, :quantity, :base, :cost, :bin, :thickness, :note)
                    """)
                    .param("doc", documentId, Types.OTHER)
                    .param("lineNo", (short) lineNo)
                    .param("item", line.getItemId(), Types.OTHER)
                    .param("uom", line.getUomId(), Types.OTHER)
                    .param("quantity", line.getQuantity())
                    .param("base", baseQuantity(line))
                    .param("cost", line.getUnitCost())
                    .param("bin", line.getStorageBinId(), Types.OTHER)
                    .param("thickness", line.getMeasuredThicknessMm())
                    .param("note", blankToNull(line.getNote()))
                    .update();
        }
    }

    /**
     * Quantity in the item's base unit: the quantity times the conversion
     * factor (1 for the base unit itself), rounded half up to three places,
     * which is exactly the rounding the database checks it against.
     */
    private BigDecimal baseQuantity(OpeningLineForm line) {
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
                    + "Load it in " + c.base() + ", or have a conversion added to the item.");
        }
        return line.getQuantity().multiply(c.factor()).setScale(3, RoundingMode.HALF_UP);
    }

    private AuditSnapshot snapshot(OpeningHeader before, List<OpeningLineRow> beforeLines,
                                   OpeningHeader after, List<OpeningLineRow> afterLines) {
        AuditSnapshot s = AuditSnapshot.of();
        s.field("Place loaded",      before == null ? null : before.locationCode(), after.locationCode());
        s.field("Figures as at",     before == null ? null : before.asAtDate(), after.asAtDate());
        s.field("Taken from",        before == null ? null : before.sourceSystem(), after.sourceSystem());
        s.field("Basis",             before == null ? null : before.basisNote(), after.basisNote());
        s.field("Customs reference", before == null ? null : before.customsReference(), after.customsReference());
        s.field("Reference",         before == null ? null : before.reference(), after.reference());
        s.field("Notes",             before == null ? null : before.notes(), after.notes());
        s.field("Lines",             beforeLines == null ? null : describe(beforeLines), describe(afterLines));
        return s;
    }

    private static String describe(List<OpeningLineRow> lines) {
        return lines.stream().map(l -> l.lineNo() + ": " + l.itemCode() + " x "
                        + l.quantity().stripTrailingZeros().toPlainString() + " " + l.uomCode()
                        + " @ " + l.unitCost().stripTrailingZeros().toPlainString()
                        + (l.binCode() == null ? "" : " in " + l.binCode())
                        + (l.measuredThicknessMm() == null ? ""
                                : " (" + l.measuredThicknessMm().stripTrailingZeros().toPlainString() + " mm)"))
                .reduce((a, b) -> a + "; " + b).orElse("none");
    }

    private UUID locationBranch(UUID locationId) {
        return jdbc.sql("SELECT branch_id FROM location WHERE id = :id")
                .param("id", locationId, Types.OTHER).query(UUID.class).optional()
                .orElseThrow(() -> new ControlRefusedException("That place does not exist."));
    }

    private OpeningHeader requireHeader(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query((rs, n) -> new OpeningHeader(
                        rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("branch_name"),
                        rs.getBoolean("branch_bonded"),
                        rs.getString("serial_no"),
                        rs.getString("status"),
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
                        rs.getObject("location_id", UUID.class),
                        rs.getString("location_code"),
                        rs.getString("location_name"),
                        rs.getBoolean("location_bonded"),
                        rs.getObject("as_at_date", LocalDate.class),
                        rs.getString("source_system"),
                        rs.getString("basis_note"),
                        rs.getString("customs_reference"),
                        rs.getBoolean("is_posted")))
                .optional()
                .orElseThrow(() -> new DocumentService.DocumentNotFoundException(id));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
