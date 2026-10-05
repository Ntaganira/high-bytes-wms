package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountService.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Stock counts: open, blind first count, blind verification count, sign, cancel and post the adjustment, audited
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
import heritier.ntaganira.highbytes.wms.inventory.gate.GateSteps;
import heritier.ntaganira.highbytes.wms.inventory.gate.ReleaseGate;
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
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Physical stock counts, on the document engine (V15).
 *
 * <p>A count opens at one location, the database writing its sheet and each line's
 * book from the ledger. Counters enter what they find without ever seeing the book;
 * submitting closes the first count, and the database then chooses the lines the
 * Internal Controller recounts, blind to the book and to the first count. Once the
 * verification is signed the book is read, Finance approves the variance, and a
 * second Finance officer posts it as ADJUSTMENT tickets: shortages leave at the
 * ledger's average cost, surpluses enter at the unit cost their line's book carried.
 * From the moment the count opens until the verification is signed, the ledger
 * refuses movements of what is being counted.
 *
 * <p>Who may read what is decided here, once, in {@link #mapLine}: a quantity the
 * reader may not see is not in the record the page is built from. Nor is which lines
 * were chosen for the verification count, nor how many, for anyone but the verifier
 * until it is signed: the choice is every line that differs from the book, so it
 * would read the book line by line. The audit trail names the lines counted while
 * the count is blind, never their quantities (the Internal Controller reads the
 * trail), and never the lines recounted or a refusal naming one (the counters read
 * it); the posting writes them all.
 *
 * <p>Who judges a count took no part in it: a counter signs no step but the first, a
 * verifier none but the verification, and once the verification is signed the count
 * is never cancelled ({@link #signBlocker}, {@link #cancelBlocker}).
 *
 * <p>The rules are the database's. This class asks first where a plain reason helps
 * and otherwise passes the database's own through, at the statement or, for the
 * deferred checks, at commit.
 */
@Service
@Transactional(readOnly = true)
public class CountService {

    private static final String HEADER = """
            SELECT d.id, d.branch_id, b.name AS branch_name, b.is_bonded AS branch_bonded,
                   d.serial_no, d.status, d.document_date, d.reference, d.notes, d.version,
                   d.created_by, cu.full_name AS created_by_name, d.created_at, d.submitted_at,
                   d.posted_at, d.posted_by, pu.full_name AS posted_by_name,
                   d.cancelled_at, xu.full_name AS cancelled_by_name, d.cancel_reason,
                   c.location_id, l.code AS location_code, l.name AS location_name, l.is_bonded AS location_bonded,
                   c.scope, c.customs_reference,
                   count_verification_signed(d.id) AS verification_signed,
                   count_book_visible(d.id) AS book_visible
              FROM document d
              JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'CNT'
              JOIN stock_count c    ON c.document_id = d.id
              JOIN location l       ON l.id = c.location_id
              JOIN branch b         ON b.id = d.branch_id
              JOIN app_user cu      ON cu.id = d.created_by
         LEFT JOIN app_user pu      ON pu.id = d.posted_by
         LEFT JOIN app_user xu      ON xu.id = d.cancelled_by
             WHERE d.id = :id
            """;

    private static final String LINES = """
            SELECT l.id, l.line_no, l.item_id, i.item_code, i.description, u.code AS base_uom, u.decimal_places,
                   l.storage_bin_id, sb.bin_code, l.on_sheet,
                   l.book_qty, l.book_value, l.unit_cost,
                   l.counted_qty, cu.full_name AS counted_by_name, l.counted_at,
                   l.verify_required, l.verified_qty, vu.full_name AS verified_by_name, l.verified_at,
                   l.final_qty, l.variance_qty, l.note,
                   (SELECT CASE m.direction WHEN 'IN' THEN m.value ELSE -m.value END
                      FROM transaction_ticket t
                      JOIN ticket_line tl   ON tl.ticket_id = t.document_id AND tl.line_no = l.line_no
                      JOIN stock_movement m ON m.ticket_line_id = tl.id AND m.reverses_movement_id IS NULL
                     WHERE t.source_document_id = l.document_id AND t.movement_type = 'ADJUSTMENT') AS posted_value
              FROM stock_count_line l
              JOIN item i ON i.id = l.item_id
              JOIN uom u  ON u.id = i.base_uom_id
         LEFT JOIN storage_bin sb ON sb.id = l.storage_bin_id
         LEFT JOIN app_user cu    ON cu.id = l.counted_by
         LEFT JOIN app_user vu    ON vu.id = l.verified_by
             WHERE l.document_id = :id
             ORDER BY l.line_no
            """;

    private static final String LIST = """
            SELECT d.id, d.serial_no, d.status, d.document_date, lo.code AS location_code, c.scope,
                   (SELECT COUNT(*) FROM stock_count_line x WHERE x.document_id = d.id) AS line_count,
                   (SELECT COUNT(*) FROM stock_count_line x
                     WHERE x.document_id = d.id AND x.counted_qty IS NOT NULL) AS counted_lines,
                   CASE WHEN count_book_visible(d.id)
                        THEN (SELECT COUNT(*) FROM stock_count_line x
                               WHERE x.document_id = d.id AND x.variance_qty <> 0) END AS variance_lines,
                   COALESCE(count_is_blind(d.id), FALSE) AS frozen,
                   cu.full_name AS created_by_name, d.posted_at, d.created_at,
                   nx.role_name AS awaiting_role
              FROM document d
              JOIN document_type dt ON dt.id = d.document_type_id AND dt.code = 'CNT'
              JOIN stock_count c    ON c.document_id = d.id
              JOIN location lo      ON lo.id = c.location_id
              JOIN app_user cu      ON cu.id = d.created_by
         LEFT JOIN LATERAL (
                   SELECT ro.name AS role_name
                     FROM workflow_step ws JOIN role ro ON ro.id = ws.required_role_id
                    WHERE ws.workflow_definition_id = d.workflow_definition_id AND d.status = 'PENDING'
                      AND NOT EXISTS (SELECT 1 FROM document_approval da
                                       WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                    ORDER BY ws.sequence_no LIMIT 1) nx ON TRUE
             WHERE d.branch_id = :branch
               AND (:status::text IS NULL OR d.status = :status)
             ORDER BY d.created_at DESC
             LIMIT 300
            """;

    /** Who a sheet is read for, which decides the quantities in it while the count is blind. */
    enum Reader {
        /** The view, for anyone with count.view: progress only, until the verification is signed. */
        VIEW,
        /** A counter, while counting: their sheet's first count, never the book. */
        COUNTER,
        /** The verifier, while verifying: their own recount, never the book nor the first count. */
        VERIFIER
    }

    /**
     * How far a count has got, without a single quantity. The recount figures are null for a reader who may not
     * have them: how many lines were chosen says how many differ from the book.
     */
    public record Progress(int lines, int counted, Integer toVerify, Integer verified) {
        public int uncounted()      { return lines - counted; }
        public int unverified()     { return toVerify == null ? 0 : toVerify - verified; }
        public boolean recountShown() { return toVerify != null; }
        Progress withoutRecount()   { return new Progress(lines, counted, null, null); }
    }

    /**
     * What adding a found place did: the line its count went on, whether that place turned out to hold the item
     * on the book (so the count went on a sheet line), and the lines the item's places on the book added with it.
     */
    public record Found(int lineNo, boolean onBook, List<Integer> placesAdded) {}

    /** Everything the count's own view needs, gathered in one read-only pass. */
    public record Detail(CountHeader header, List<CountLineRow> lines, Progress progress, List<ChainStep> chain,
                         ChainInfo chainInfo, ReleaseGate gate, CountActions actions, TransferPosting posting,
                         List<CountLookupService.InFlight> inFlight) {}

    /** A sheet as one person enters it: the header, the lines as that person may see them, and the form. */
    public record Sheet(CountHeader header, List<CountLineRow> lines, Progress progress, CountEntryForm form) {}

    private record LineMeta(UUID id, int lineNo, String itemCode, String uomCode, int decimals, boolean onSheet,
                            String binCode, BigDecimal countedQty, String note) {}

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final LedgerService ledger;
    private final BranchService branches;
    private final GateSteps gateSteps;
    private final CountLookupService lookups;

    public CountService(JdbcClient jdbc, DocumentService documents, LedgerService ledger, BranchService branches,
                        GateSteps gateSteps, CountLookupService lookups) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.ledger = ledger;
        this.branches = branches;
        this.gateSteps = gateSteps;
        this.lookups = lookups;
    }

    // ---- reads -------------------------------------------------------------------------

    /** Counts opened at this branch, newest first, optionally in one state or awaiting the user's signature. */
    @PreAuthorize("hasAuthority('count.view')")
    public List<CountRow> list(UUID branchId, String status, boolean awaitingMe) {
        Set<UUID> mine = documents.awaitingSignatureOf(DocumentKind.CNT, branchId, CurrentUser.id());
        return jdbc.sql(LIST)
                .param("branch", branchId, Types.OTHER)
                .param("status", status == null || status.isBlank() ? null : status)
                .query((rs, n) -> new CountRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("status"),
                        rs.getObject("document_date", java.time.LocalDate.class),
                        rs.getString("location_code"),
                        CountScope.valueOf(rs.getString("scope")),
                        rs.getInt("line_count"),
                        rs.getInt("counted_lines"),
                        rs.getObject("variance_lines") == null ? null : rs.getInt("variance_lines"),
                        rs.getString("created_by_name"),
                        rs.getString("awaiting_role"),
                        false,
                        rs.getBoolean("frozen"),
                        KigaliTime.read(rs, "posted_at")))
                .list().stream()
                .map(row -> row.markAwaitingMe(mine.contains(row.id())))
                .filter(row -> !awaitingMe || row.awaitingMe())
                .toList();
    }

    /** The count's header. Readable at its own branch only; a 403 elsewhere, unrecorded (a read). */
    @PreAuthorize("hasAuthority('count.view')")
    public CountHeader find(UUID id) {
        CountHeader header = requireHeader(id);
        CurrentUser.requireAt("count.view", header.branchId());
        return header;
    }

    @PreAuthorize("hasAuthority('count.view')")
    public Detail detail(UUID id) {
        CountHeader header = find(id);
        List<CountLineRow> lines = lines(id, Reader.VIEW, header.bookVisible());
        DocumentHeader document = documents.header(id);
        List<ChainStep> chain = documents.chain(id);
        Progress progress = progressOf(id);
        // The recount figures are the verifier's until the book is read; the actions are judged on the whole.
        boolean recount = header.bookVisible() || CurrentUser.holdsAt("count.verify", header.branchId());
        ReleaseGate gate = gate(header, chain, gateSteps.now());
        var inFlight = header.frozen() ? lookups.inFlight(header.locationId()) : List.<CountLookupService.InFlight>of();
        return new Detail(header, lines, recount ? progress : progress.withoutRecount(), chain,
                documents.chainInfo(id).orElse(null), gate, actionsFor(header, document, chain, progress),
                postingOf(id).orElse(null), inFlight);
    }

    /**
     * The sheet a counter fills in: the first count so far and the notes, never the book. Only while counting,
     * and only for whoever holds count.enter at the count's branch. A read: refused, not recorded.
     */
    @PreAuthorize("hasAuthority('count.enter')")
    public Sheet countingSheet(UUID id) {
        CountHeader h = requireHeader(id);
        CurrentUser.requireAt("count.enter", h.branchId());
        if (!h.counting()) {
            throw new ControlRefusedException(h.serialNo() + " is " + h.status() + ": the first count closed when it "
                    + "was submitted, so nothing more is entered on it.");
        }
        List<CountLineRow> lines = lines(id, Reader.COUNTER, false);
        CountEntryForm form = new CountEntryForm();
        lines.forEach(l -> form.getLines().add(new CountEntryForm.Line(l.id(), l.countedQty(), l.note())));
        return new Sheet(h, lines, progressOf(id).withoutRecount(), form);
    }

    /**
     * The sheet the verifier fills in: which lines are to be recounted and the verifier's own recount so far,
     * neither the book nor the first count. Only while the count awaits its verification, and only for whoever
     * may sign that step. A read: refused, not recorded.
     */
    @PreAuthorize("hasAuthority('count.verify')")
    public Sheet verificationSheet(UUID id) {
        CountHeader h = requireHeader(id);
        CurrentUser.requireAt("count.verify", h.branchId());
        String blocked = verificationBlocker(h, documents.header(id), documents.chain(id));
        if (blocked != null) throw new ControlRefusedException(blocked);
        List<CountLineRow> lines = lines(id, Reader.VERIFIER, false);
        CountEntryForm form = new CountEntryForm();
        lines.forEach(l -> form.getLines().add(new CountEntryForm.Line(l.id(), l.verifiedQty(), null)));
        return new Sheet(h, lines, progressOf(id), form);
    }

    /** The movements of a posted count, every leg, in ledger order. */
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

    // ---- writes ------------------------------------------------------------------------

    /**
     * Opens a count: the database dates it today, binds the chain in force today, refuses a second live count of
     * the location, writes a full count's sheet, and from then on refuses movements of what it counts. A cycle
     * count's sheet is written here, item by item, by the same database function.
     */
    @Transactional
    @PreAuthorize("hasAuthority('count.create')")
    public UUID create(CountForm form) {
        if (form.getLocationId() == null) throw new ControlRefusedException("Choose the location to count.");
        UUID branchId = jdbc.sql("SELECT branch_id FROM location WHERE id = :id")
                .param("id", form.getLocationId(), Types.OTHER).query(UUID.class).optional()
                .orElseThrow(() -> new ControlRefusedException("That location does not exist."));
        documents.requireRightAt(DocumentKind.CNT, branchId, "count.create", "Open a count");
        CountScope scope = form.getScope() == null ? CountScope.FULL : form.getScope();
        List<UUID> items = scope == CountScope.PARTIAL
                ? form.getItemIds().stream().filter(java.util.Objects::nonNull).distinct().toList() : List.of();
        if (scope == CountScope.PARTIAL && items.isEmpty()) {
            throw new ControlRefusedException("A cycle count needs at least one item to count.");
        }
        try {
            OpenedDocument opened = documents.open(DocumentKind.CNT, branchId, form.getReference(), form.getNotes(), null);
            jdbc.sql("""
                    INSERT INTO stock_count (document_id, location_id, scope, customs_reference)
                    VALUES (:id, :location, :scope, :customs)
                    """)
                    .param("id", opened.id(), Types.OTHER)
                    .param("location", form.getLocationId(), Types.OTHER)
                    .param("scope", scope.name())
                    .param("customs", form.getCustomsReference())
                    .update();
            for (UUID item : items) {
                jdbc.sql("SELECT count_add_places(:doc, :item)")
                        .param("doc", opened.id(), Types.OTHER)
                        .param("item", item, Types.OTHER)
                        .query(Integer.class).single();
            }
            CountHeader after = requireHeader(opened.id());
            List<LineMeta> lines = metas(opened.id());
            documents.auditInTransaction("CNT · " + after.serialNo(), opened.id(), AuditAction.CREATE,
                    AuditSnapshot.of()
                            .field("Location", null, after.locationCode())
                            .field("Scope", null, after.scope().title())
                            .field("Customs reference", null, after.customsReference())
                            .field("Reference", null, after.reference())
                            .field("Notes", null, after.notes())
                            .field("Sheet", null, sheetOf(lines)),
                    branches.findById(branchId).orElse(null));
            return opened.id();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
    }

    /**
     * Saves what counters entered: only the lines whose quantity or note changed since the page was opened, each
     * stamped with the person who entered it. While counting only. Returns how many lines were written.
     */
    @Transactional
    @PreAuthorize("hasAuthority('count.enter')")
    public int saveCounts(UUID id, CountEntryForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "count.enter", "Enter counts");
        if (!"DRAFT".equals(d.status())) {
            throw documents.refused(d, "Enter counts", d.serialNo() + " is " + d.status() + ": the first count closed "
                    + "when it was submitted, so it no longer changes. A line counted wrong is put right by the "
                    + "verification count.");
        }
        Map<UUID, LineMeta> meta = metas(id).stream().collect(Collectors.toMap(LineMeta::id, Function.identity()));
        Set<Integer> saved = new LinkedHashSet<>();
        UUID me = CurrentUser.id();
        try {
            for (CountEntryForm.Line line : form.getLines()) {
                if (line.getLineId() == null || !line.changed()) continue;
                LineMeta m = meta.get(line.getLineId());
                if (m == null) throw new ControlRefusedException("A row names a line that is not on " + d.serialNo() + ".");
                boolean setQty = line.getQuantity() != null
                        && (line.getWas() == null || line.getQuantity().compareTo(line.getWas()) != 0);
                if (setQty) requireCountable(m, line.getQuantity());
                String note = line.getNote() == null || line.getNote().isBlank() ? null : line.getNote().trim();
                if (note != null && note.length() > 240) {
                    throw new ControlRefusedException("Line " + m.lineNo() + ": a note is at most 240 characters.");
                }
                jdbc.sql("""
                        UPDATE stock_count_line
                           SET counted_qty = CASE WHEN :setQty THEN :qty::numeric ELSE counted_qty END,
                               counted_by  = CASE WHEN :setQty THEN :me::uuid ELSE counted_by END,
                               note        = CASE WHEN :setNote THEN :note::text ELSE note END
                         WHERE id = :line AND document_id = :doc
                        """)
                        .param("setQty", setQty)
                        .param("qty", setQty ? line.getQuantity() : null)
                        .param("me", me, Types.OTHER)
                        .param("setNote", line.noteChanged())
                        .param("note", note)
                        .param("line", m.id(), Types.OTHER)
                        .param("doc", id, Types.OTHER)
                        .update();
                saved.add(m.lineNo());
            }
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Enter counts", e);
        } catch (ControlRefusedException e) {
            throw documents.refused(d, "Enter counts", e.getMessage());
        }
        if (!saved.isEmpty()) {
            // The trail names the lines, never what was counted: the Internal Controller reads it before recounting.
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, AuditSnapshot.of()
                    .value("First count entered on lines", join(saved)), d.branch());
        }
        return saved.size();
    }

    /**
     * Records stock found at a place the sheet does not list, with what was counted there. While counting only.
     *
     * <p>First every place the book holds the item at in the counted location goes on the sheet: a count weighs
     * every place of what it counts, and the sheet, not a refusal at submission, is what says where they are. If
     * the found place is one of them, what was counted there is that line's count. Otherwise the place is added as
     * a found line, its book nothing, so a counter may remove it again: a found line is never one the book holds
     * stock at, and removing one never tells a counter anything about the book.
     */
    @Transactional
    @PreAuthorize("hasAuthority('count.enter')")
    public Found addFoundLine(UUID id, FoundLineForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "count.enter", "Add a line");
        if (!"DRAFT".equals(d.status())) {
            throw documents.refused(d, "Add a line", d.serialNo() + " is " + d.status()
                    + ": lines are added only while counting.");
        }
        record Item(String code, String uom, int decimals) {}
        Item item = jdbc.sql("""
                SELECT i.item_code, u.code, u.decimal_places FROM item i JOIN uom u ON u.id = i.base_uom_id WHERE i.id = :id
                """)
                .param("id", form.getItemId(), Types.OTHER)
                .query((rs, n) -> new Item(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .optional().orElseThrow(() -> documents.refused(d, "Add a line", "That item does not exist."));
        String bin = form.getBinId() == null ? null : jdbc.sql("SELECT bin_code FROM storage_bin WHERE id = :id")
                .param("id", form.getBinId(), Types.OTHER).query(String.class).optional().orElse(null);
        String place = item.code() + (bin == null ? " unbinned" : " in bin " + bin);
        String note = form.getNote() == null || form.getNote().isBlank() ? null : form.getNote().trim();
        int lineNo;
        Integer onBook;
        List<Integer> added;
        try {
            requireCountable(new LineMeta(null, 0, item.code(), item.uom(), item.decimals(), false, bin, null, null),
                    form.getQuantity());
            Integer listed = lineAt(id, form.getItemId(), form.getBinId());
            if (listed != null) {
                throw new ControlRefusedException(place + " is already on the sheet as line " + listed
                        + ": enter what you counted on that line.");
            }
            int last = jdbc.sql("SELECT COALESCE(MAX(line_no), 0) FROM stock_count_line WHERE document_id = :doc")
                    .param("doc", id, Types.OTHER).query(Integer.class).single();
            jdbc.sql("SELECT count_add_places(:doc, :item, FALSE)")
                    .param("doc", id, Types.OTHER)
                    .param("item", form.getItemId(), Types.OTHER)
                    .query(Integer.class).single();
            added = jdbc.sql("SELECT line_no FROM stock_count_line WHERE document_id = :doc AND line_no > :last ORDER BY line_no")
                    .param("doc", id, Types.OTHER).param("last", last).query(Integer.class).list();
            onBook = lineAt(id, form.getItemId(), form.getBinId());
            if (onBook != null) {
                jdbc.sql("""
                        UPDATE stock_count_line SET counted_qty = :qty, counted_by = :me, note = COALESCE(:note::text, note)
                         WHERE document_id = :doc AND line_no = :line
                        """)
                        .param("qty", form.getQuantity())
                        .param("me", CurrentUser.id(), Types.OTHER)
                        .param("note", note)
                        .param("doc", id, Types.OTHER)
                        .param("line", onBook)
                        .update();
                lineNo = onBook;
            } else {
                lineNo = jdbc.sql("""
                        INSERT INTO stock_count_line (document_id, line_no, item_id, storage_bin_id, counted_qty, counted_by, note)
                        VALUES (:doc, (SELECT COALESCE(MAX(line_no), 0) + 1 FROM stock_count_line WHERE document_id = :doc),
                                :item, :bin, :qty, :me, :note)
                        RETURNING line_no
                        """)
                        .param("doc", id, Types.OTHER)
                        .param("item", form.getItemId(), Types.OTHER)
                        .param("bin", form.getBinId(), Types.OTHER)
                        .param("qty", form.getQuantity())
                        .param("me", CurrentUser.id(), Types.OTHER)
                        .param("note", note)
                        .query(Integer.class).single();
            }
        } catch (ControlRefusedException e) {
            throw documents.refused(d, "Add a line", e.getMessage());
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Add a line", e);
        }
        final int line = lineNo;
        final List<Integer> withIt = added;
        AuditSnapshot snapshot = AuditSnapshot.of();
        if (!withIt.isEmpty()) {
            snapshot.value("Lines added: " + item.code() + "'s places on the book", sheetOf(metas(id).stream()
                    .filter(m -> withIt.contains(m.lineNo())).toList()));
        }
        snapshot.value(onBook != null ? "First count entered on lines" : "Line added as found",
                onBook != null ? String.valueOf(line) : line + ": " + item.code() + (bin == null ? ", no bin" : ", bin " + bin));
        documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, snapshot, d.branch());
        return new Found(line, onBook != null, withIt.stream().filter(n -> n != line).toList());
    }

    /** The line counting this place (an item, in a bin or unbinned), or null when the sheet does not list it. */
    private Integer lineAt(UUID id, UUID item, UUID bin) {
        return jdbc.sql("""
                SELECT line_no FROM stock_count_line
                 WHERE document_id = :doc AND item_id = :item AND storage_bin_id IS NOT DISTINCT FROM :bin::uuid
                """)
                .param("doc", id, Types.OTHER)
                .param("item", item, Types.OTHER)
                .param("bin", bin, Types.OTHER)
                .query(Integer.class).optional().orElse(null);
    }

    /** Removes a line a counter added. The sheet's own lines stay: what holds nothing is counted 0. */
    @Transactional
    @PreAuthorize("hasAuthority('count.enter')")
    public void removeLine(UUID id, UUID lineId) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "count.enter", "Remove a line");
        LineMeta m = metas(id).stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> documents.refused(d, "Remove a line", "That line is not on " + d.serialNo() + "."));
        if (m.onSheet()) {
            throw documents.refused(d, "Remove a line", "Line " + m.lineNo() + " was on the sheet when the count "
                    + "opened, so it is counted, not removed. Enter 0 if nothing is there.");
        }
        try {
            jdbc.sql("DELETE FROM stock_count_line WHERE id = :line AND document_id = :doc")
                    .param("line", lineId, Types.OTHER)
                    .param("doc", id, Types.OTHER)
                    .update();
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Remove a line", e);
        }
        documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, AuditSnapshot.of()
                .value("Line removed", m.lineNo() + ": " + m.itemCode()
                        + (m.binCode() == null ? ", no bin" : ", bin " + m.binCode())), d.branch());
    }

    /**
     * Saves the verification count: only the lines whose recount changed since the page was opened, each stamped
     * with the verifier. Only while the count awaits its verification, by whoever may sign that step.
     */
    @Transactional
    @PreAuthorize("hasAuthority('count.verify')")
    public int saveVerification(UUID id, CountEntryForm form) {
        DocumentHeader d = documents.lock(id);
        documents.requireRight(d, "count.verify", "Enter the verification count");
        String blocked = verificationBlocker(requireHeader(id), d, documents.chain(id));
        if (blocked != null) throw documents.refused(d, "Enter the verification count", blocked);
        Map<UUID, LineMeta> meta = metas(id).stream().collect(Collectors.toMap(LineMeta::id, Function.identity()));
        Set<Integer> saved = new LinkedHashSet<>();
        try {
            for (CountEntryForm.Line line : form.getLines()) {
                if (line.getLineId() == null || line.getQuantity() == null) continue;
                if (line.getWas() != null && line.getQuantity().compareTo(line.getWas()) == 0) continue;
                LineMeta m = meta.get(line.getLineId());
                if (m == null) throw new ControlRefusedException("A row names a line that is not on " + d.serialNo() + ".");
                requireCountable(m, line.getQuantity());
                jdbc.sql("""
                        UPDATE stock_count_line SET verified_qty = :qty, verified_by = :me
                         WHERE id = :line AND document_id = :doc
                        """)
                        .param("qty", line.getQuantity())
                        .param("me", CurrentUser.id(), Types.OTHER)
                        .param("line", m.id(), Types.OTHER)
                        .param("doc", id, Types.OTHER)
                        .update();
                saved.add(m.lineNo());
            }
        } catch (DataAccessException e) {
            throw documents.refusedBy(d, "Enter the verification count", e);
        } catch (ControlRefusedException e) {
            throw documents.refused(d, "Enter the verification count", e.getMessage());
        }
        if (!saved.isEmpty()) {
            // Neither the lines nor how many: the counters read the trail, and the lines recounted are the lines
            // that differ from the book. Each line names its own verifier once the verification is signed.
            documents.auditInTransaction(d.label(), id, AuditAction.UPDATE, AuditSnapshot.of()
                    .value("Verification count", "entered"), d.branch());
        }
        return saved.size();
    }

    /** Submits the counted sheet: step 1 signs, the first count closes, and the database chooses what is recounted. */
    @Transactional
    @PreAuthorize("hasAnyAuthority('count.create','count.verify','count.approve')")
    public String submit(UUID id) {
        DocumentHeader d = documents.header(id);
        Progress p = progressOf(id);
        if ("DRAFT".equals(d.status()) && p.uncounted() > 0) {
            throw documents.refused(d, "Submit", uncountedReason(d.serialNo(), p));
        }
        return documents.submit(id).status();
    }

    @Transactional
    @PreAuthorize("hasAnyAuthority('count.create','count.verify','count.approve')")
    public String sign(UUID id, boolean approve, String comment) {
        DocumentHeader d = documents.header(id);
        List<ChainStep> chain = documents.chain(id);
        StepCheck check = documents.canSign(d, chain);
        if (check.allowed()) {
            ChainStep next = check.step();
            String attempt = (approve ? "Approve" : "Reject") + " step " + next.sequenceNo();
            String notIndependent = signBlocker(id, d.serialNo(), chain, next);
            if (notIndependent != null) throw documents.refused(d, attempt, notIndependent);
            if (approve && "VERIFY".equals(next.actionLabel()) && progressOf(id).unverified() > 0) {
                throw documents.refused(d, attempt, unverifiedReason(d.serialNo()));
            }
        }
        return documents.sign(id, approve, comment).status();
    }

    /**
     * Cancels a count that is still counted or awaits its verification: the freeze lifts, and its book is never
     * shown. Once the verification is signed it is refused ({@link #cancelBlocker}).
     */
    @Transactional
    @PreAuthorize("hasAuthority('count.create')")
    public void cancel(UUID id, String reason) {
        DocumentHeader d = documents.header(id);
        documents.requireRight(d, d.kind().cancelRight(), "Cancel");
        String blocked = cancelBlocker(requireHeader(id));
        if (blocked != null) throw documents.refused(d, "Cancel", blocked);
        documents.cancel(id, reason);
    }

    /**
     * Posts an approved count: it goes POSTED, an ADJUSTMENT OUT ticket takes every shortage out at the ledger's
     * average cost, an ADJUSTMENT IN ticket brings every surplus in at its line's unit cost, the tickets go
     * POSTED. A count that agrees with the book everywhere posts with no ticket. One transaction. Returns the serials
     * of the tickets raised, empty when there were none.
     */
    @Transactional
    @PreAuthorize("hasAuthority('count.post')")
    public List<String> post(UUID id) {
        DocumentHeader early = documents.header(id);
        documents.requireRight(early, "count.post", "Post");
        StepCheck check = documents.canPost(early, documents.chain(id));
        if (!check.allowed()) throw documents.refused(early, "Post", check.reason());
        String notIndependent = postBlocker(id, early.serialNo());
        if (notIndependent != null) throw documents.refused(early, "Post", notIndependent);

        DocumentHeader d = documents.beginPost(id);     // locks; APPROVED -> POSTED, judged by the database
        CountHeader h = requireHeader(id);
        List<CountLineRow> lines = lines(id, Reader.VIEW, true);
        List<CountLineRow> shortages = lines.stream().filter(l -> l.varianceQty().signum() < 0).toList();
        List<CountLineRow> surpluses = lines.stream().filter(l -> l.varianceQty().signum() > 0).toList();
        UUID poster = CurrentUser.id();

        List<OpenedDocument> tickets = new ArrayList<>();
        BigDecimal out = BigDecimal.ZERO;
        BigDecimal in = BigDecimal.ZERO;
        int movements = 0;
        try {
            ledger.lockPlaces(lines.stream().filter(CountLineRow::hasVariance)
                    .map(l -> new LedgerService.Place(l.itemId(), h.locationId())).toList());
            if (!shortages.isEmpty()) {
                OpenedDocument ticket = openTicket(d, "OUT", h.locationId(), null, h);
                tickets.add(ticket);
                for (CountLineRow l : shortages) {
                    UUID line = insertTicketLine(ticket.id(), l);
                    // A shortage leaves at the ledger's average cost, as any issue.
                    var left = ledger.post(MovementRequest.issue(ticket.id(), line, d.branchId(), l.itemId(),
                            h.locationId(), l.binId(), l.varianceQty().negate()), poster);
                    out = out.add(left.value());
                    movements++;
                }
            }
            if (!surpluses.isEmpty()) {
                OpenedDocument ticket = openTicket(d, "IN", null, h.locationId(), h);
                tickets.add(ticket);
                for (CountLineRow l : surpluses) {
                    UUID line = insertTicketLine(ticket.id(), l);
                    // A surplus enters at the unit cost its line's book carried when counted; the database checks it.
                    BigDecimal value = l.varianceQty().multiply(l.unitCost()).setScale(2, RoundingMode.HALF_UP);
                    var came = ledger.post(MovementRequest.receipt(ticket.id(), line, d.branchId(), l.itemId(),
                            h.locationId(), l.binId(), l.varianceQty(), value), poster);
                    in = in.add(came.value());
                    movements++;
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
                .value("Location", h.locationCode())
                .value("Lines", describe(lines))
                .value("Tickets", tickets.isEmpty() ? "none: every line agreed with the book"
                        : tickets.stream().map(OpenedDocument::serialNo).collect(Collectors.joining(", ")))
                .value("Ledger movements", movements)
                .value("Shortages written down (RWF)", out)
                .value("Surpluses brought in (RWF)", in));
        return tickets.stream().map(OpenedDocument::serialNo).toList();
    }

    // ---- helpers -----------------------------------------------------------------------

    ReleaseGate gate(CountHeader h, List<ChainStep> chain, LocalDateTime now) {
        String status = h.status();
        if ("REJECTED".equals(status) || "CANCELLED".equals(status)) {
            return new ReleaseGate("VOID", h.serialNo(), List.of(), null, null, null, null, null, null,
                    status.toLowerCase(), "CNT");
        }
        if ("APPROVED".equals(status)) {
            ChainStep last = chain.isEmpty() ? null : chain.get(chain.size() - 1);
            return new ReleaseGate("RELEASED", h.serialNo(), List.of(),
                    last == null ? null : last.actorName(), last == null ? null : last.decidedAt(),
                    null, null, null, null, null, "CNT");
        }
        if ("POSTED".equals(status)) {
            return new ReleaseGate("POSTED", h.serialNo(), List.of(), null, null, null, null,
                    h.postedAt(), h.postedByName(), null, "CNT");
        }
        var waiting = gateSteps.waiting(h.id(), h.branchId(), h.createdBy(), status, h.submittedAt(), chain, now);
        return new ReleaseGate("BLOCKED", h.serialNo(), waiting, null, null, null, null, null, null, null, "CNT");
    }

    private CountActions actionsFor(CountHeader h, DocumentHeader d, List<ChainStep> chain, Progress p) {
        UUID branch = h.branchId();
        boolean enter = CurrentUser.holdsAt("count.enter", branch);
        boolean create = CurrentUser.holdsAt("count.create", branch);
        boolean verify = CurrentUser.holdsAt("count.verify", branch);
        boolean signer = create || verify || CurrentUser.holdsAt("count.approve", branch);
        boolean poster = CurrentUser.holdsAt("count.post", branch);
        String status = h.status();

        StepCheck submit = documents.canSubmit(d, chain);
        StepCheck sign = documents.canSign(d, chain);
        StepCheck cancel = documents.canCancel(d);
        StepCheck post = documents.canPost(d, chain);

        boolean canCount = h.counting() && enter;

        boolean canVerify = false;
        String verifyReason = null;
        if (h.awaitingVerification() && verify) {
            verifyReason = verificationBlocker(h, d, chain);
            canVerify = verifyReason == null;
        }

        boolean canSubmit = submit.allowed() && p.uncounted() == 0;
        String submitReason = null;
        if (h.counting() && signer) {
            submitReason = !submit.allowed() ? submit.reason() : p.uncounted() > 0 ? uncountedReason(h.serialNo(), p) : null;
        }

        boolean canSign = sign.allowed();
        String signReason = "PENDING".equals(status) && !sign.allowed() && signer ? sign.reason() : null;
        if (canSign && sign.step() != null) {
            String notIndependent = signBlocker(h.id(), h.serialNo(), chain, sign.step());
            if (notIndependent != null) {
                canSign = false;
                signReason = notIndependent;
            } else if ("VERIFY".equals(sign.step().actionLabel()) && p.unverified() > 0) {
                canSign = false;
                signReason = unverifiedReason(h.serialNo());
            }
        }

        String cancelBlocked = cancelBlocker(h);
        boolean canCancel = cancel.allowed() && cancelBlocked == null;
        String cancelReason = "POSTED".equals(status) && (create || poster)
                ? h.serialNo() + " has been posted, so it cannot be cancelled: the stock has been adjusted. A wrong "
                        + "count is corrected by a reversing document, raised from this page, or by the next count."
                : cancelBlocked != null && create ? cancelBlocked : null;

        String postReason = null;
        boolean canPost = false;
        if ("APPROVED".equals(status) && poster) {
            String blocker = post.allowed() ? postBlocker(h.id(), h.serialNo()) : post.reason();
            canPost = blocker == null;
            postReason = blocker;
        }
        return new CountActions(
                canCount,
                h.counting() && !enter && create ? "Entering counted quantities needs the count.enter right at "
                        + h.branchName() + "." : null,
                canVerify,
                verifyReason,
                canSubmit,
                submitReason,
                canSign,
                signReason,
                sign.step(),
                canCancel,
                cancelReason,
                canPost,
                postReason);
    }

    /**
     * Why the signed-in user may not sign {@code step} of this count for the part they took in it, or null.
     * Whoever took part in the first count signs no step but the chain's first, and whoever took the verification
     * count signs no step but the verification, approving or rejecting. The database judges the same.
     */
    private String signBlocker(UUID id, String serial, List<ChainStep> chain, ChainStep step) {
        if (step == null || chain.isEmpty()) return null;
        record Part(boolean counted, boolean verified) {}
        Part part = jdbc.sql("""
                SELECT COALESCE(bool_or(counted_by = :me), FALSE) AS counted,
                       COALESCE(bool_or(verified_by = :me), FALSE) AS verified
                  FROM stock_count_line WHERE document_id = :id
                """)
                .param("id", id, Types.OTHER)
                .param("me", CurrentUser.id(), Types.OTHER)
                .query((rs, n) -> new Part(rs.getBoolean("counted"), rs.getBoolean("verified")))
                .single();
        int first = chain.stream().mapToInt(ChainStep::sequenceNo).min().orElse(step.sequenceNo());
        if (part.counted() && step.sequenceNo() != first) {
            return "You took part in the first count of " + serial + ", so you cannot sign its "
                    + step.actionLabel().toLowerCase() + " step. A count is prepared by those who took it and judged "
                    + "by those who did not.";
        }
        if (part.verified() && !"VERIFY".equals(step.actionLabel())) {
            return "You took the verification count of " + serial + ", so the only step of it you sign is the "
                    + "verification.";
        }
        return null;
    }

    /**
     * Why this count may no longer be cancelled although it is not posted, or null: its verification is signed.
     * From then on its variances are known and confirmed by an independent recount; withdrawn, they would leave
     * the books and the report as though never found. The database refuses the same.
     */
    private static String cancelBlocker(CountHeader h) {
        if (!h.verificationSigned()) return null;
        return switch (h.status()) {
            case "PENDING" -> "The verification count of " + h.serialNo() + " is signed, so it can no longer be "
                    + "cancelled: what it found stands until the remaining signers approve or reject it. Withdrawing "
                    + "it would drop differences an independent recount confirmed.";
            case "APPROVED" -> h.serialNo() + " is approved with what its verification count confirmed, so it can no "
                    + "longer be cancelled: a second Finance officer posts it.";
            default -> null;
        };
    }

    /**
     * Why the signed-in user may not take this count's verification count, or null: it must await its
     * verification, and they must be able to sign that step (its role and right, not its raiser, not already a
     * signer, no part in the first count). The database judges the same.
     */
    private String verificationBlocker(CountHeader h, DocumentHeader d, List<ChainStep> chain) {
        if (!h.awaitingVerification()) {
            return h.serialNo() + " is " + (h.counting() ? "still being counted" : h.verificationSigned()
                    ? "verified" : h.status()) + ": the verification count is taken after the count is submitted "
                    + "and before its verification is signed.";
        }
        StepCheck sign = documents.canSign(d, chain);
        if (!sign.allowed()) return sign.reason();
        if (sign.step() == null || !"VERIFY".equals(sign.step().actionLabel())) {
            return "The next step of " + h.serialNo() + " is not its verification, so no verification count is "
                    + "taken now.";
        }
        return signBlocker(h.id(), h.serialNo(), chain, sign.step());
    }

    /**
     * Why the signed-in user may not post this count for having counted or verified a line of it, or null. (Raised
     * or signed by the poster is {@link DocumentService#canPost}'s.)
     */
    String postBlocker(UUID id, String serial) {
        record Hand(int line, String what) {}
        return jdbc.sql("""
                SELECT l.line_no, CASE WHEN l.counted_by = :me THEN 'counted' ELSE 'verified' END AS what
                  FROM stock_count_line l
                 WHERE l.document_id = :id AND :me IN (l.counted_by, l.verified_by)
                 ORDER BY l.line_no LIMIT 1
                """)
                .param("id", id, Types.OTHER)
                .param("me", CurrentUser.id(), Types.OTHER)
                .query((rs, n) -> new Hand(rs.getInt("line_no"), rs.getString("what")))
                .optional()
                .map(hand -> "You " + hand.what() + " line " + hand.line() + " of " + serial + ", so you cannot post "
                        + "it. Whoever counted the stock does not also record the adjustment.")
                .orElse(null);
    }

    private static String uncountedReason(String serial, Progress p) {
        return p.uncounted() + (p.uncounted() == 1 ? " line of " : " lines of ") + serial + " "
                + (p.uncounted() == 1 ? "has" : "have") + " not been counted. Every line is counted before the count "
                + "is submitted; enter 0 where nothing was found.";
    }

    /** Says neither which lines nor how many: a refusal is on the trail the counters read. */
    private static String unverifiedReason(String serial) {
        return "Not every line of " + serial + " chosen for the verification count has been recounted. Enter the "
                + "verification count first; the verification is signed once every chosen line is recounted.";
    }

    /** A quantity a person may enter: not below zero, and in the precision of the item's base unit. */
    private static void requireCountable(LineMeta m, BigDecimal quantity) {
        if (quantity == null) throw new ControlRefusedException("Enter what was counted.");
        String what = (m.lineNo() > 0 ? "Line " + m.lineNo() + " (" + m.itemCode() + ")" : m.itemCode());
        if (quantity.signum() < 0) {
            throw new ControlRefusedException(what + ": a count cannot be below zero.");
        }
        int places = Math.min(3, m.decimals());
        if (quantity.stripTrailingZeros().scale() > places) {
            throw new ControlRefusedException(what + " is counted in " + m.uomCode() + ", "
                    + (places == 0 ? "in whole numbers." : "to at most " + places + " decimal places."));
        }
    }

    private Progress progressOf(UUID id) {
        return jdbc.sql("""
                SELECT COUNT(*) AS lines,
                       COUNT(*) FILTER (WHERE counted_qty IS NOT NULL) AS counted,
                       COUNT(*) FILTER (WHERE verify_required) AS to_verify,
                       COUNT(*) FILTER (WHERE verify_required AND verified_qty IS NOT NULL) AS verified
                  FROM stock_count_line WHERE document_id = :id
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> new Progress(rs.getInt("lines"), rs.getInt("counted"), rs.getInt("to_verify"),
                        rs.getInt("verified")))
                .single();
    }

    private List<LineMeta> metas(UUID id) {
        return jdbc.sql("""
                SELECT l.id, l.line_no, i.item_code, u.code AS uom, u.decimal_places, l.on_sheet, sb.bin_code,
                       l.counted_qty, l.note
                  FROM stock_count_line l
                  JOIN item i ON i.id = l.item_id
                  JOIN uom u  ON u.id = i.base_uom_id
             LEFT JOIN storage_bin sb ON sb.id = l.storage_bin_id
                 WHERE l.document_id = :id ORDER BY l.line_no
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> new LineMeta(rs.getObject("id", UUID.class), rs.getInt("line_no"),
                        rs.getString("item_code"), rs.getString("uom"), rs.getInt("decimal_places"),
                        rs.getBoolean("on_sheet"), rs.getString("bin_code"), rs.getBigDecimal("counted_qty"),
                        rs.getString("note")))
                .list();
    }

    /** The sheet's lines as {@code reader} may read them; the book only when {@code bookVisible}. */
    List<CountLineRow> lines(UUID id, Reader reader, boolean bookVisible) {
        return jdbc.sql(LINES).param("id", id, Types.OTHER)
                .query((rs, n) -> mapLine(rs, reader, bookVisible))
                .list();
    }

    /**
     * The one place a sheet is masked. Once the book is readable every reader sees everything. Before that: the
     * view sees which lines are counted, nothing more; a counter sees the first count and the notes; the verifier
     * sees their own recount and which lines are chosen for it. Nobody sees the book, and nobody but the verifier
     * which lines were chosen or recounted: the choice is every line that differs from the book and a sample of
     * the rest, so to a counter it would read the book line by line.
     */
    private CountLineRow mapLine(java.sql.ResultSet rs, Reader reader, boolean bookVisible) throws java.sql.SQLException {
        boolean all = bookVisible;
        boolean first = all || reader == Reader.COUNTER;
        boolean second = all || reader == Reader.VERIFIER;
        return new CountLineRow(
                rs.getObject("id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("item_id", UUID.class),
                rs.getString("item_code"),
                rs.getString("description"),
                rs.getString("base_uom"),
                rs.getInt("decimal_places"),
                rs.getObject("storage_bin_id", UUID.class),
                rs.getString("bin_code"),
                rs.getBoolean("on_sheet"),
                rs.getObject("counted_qty") != null,
                second && rs.getBoolean("verify_required"),
                second && rs.getObject("verified_qty") != null,
                all ? rs.getBigDecimal("book_qty") : null,
                all ? rs.getBigDecimal("book_value") : null,
                all ? rs.getBigDecimal("unit_cost") : null,
                first ? rs.getBigDecimal("counted_qty") : null,
                first ? rs.getString("counted_by_name") : null,
                first ? KigaliTime.read(rs, "counted_at") : null,
                second ? rs.getBigDecimal("verified_qty") : null,
                second ? rs.getString("verified_by_name") : null,
                second ? KigaliTime.read(rs, "verified_at") : null,
                all ? rs.getBigDecimal("final_qty") : null,
                all ? rs.getBigDecimal("variance_qty") : null,
                first ? rs.getString("note") : null,
                all ? rs.getBigDecimal("posted_value") : null);
    }

    private OpenedDocument openTicket(DocumentHeader d, String direction, UUID from, UUID to, CountHeader h) {
        OpenedDocument ticket = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);
        jdbc.sql("""
                INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id,
                                                to_location_id, source_document_id, customs_reference)
                VALUES (:id, 'ADJUSTMENT', :direction, :from, :to, :source, :customs)
                """)
                .param("id", ticket.id(), Types.OTHER)
                .param("direction", direction)
                .param("from", from, Types.OTHER)
                .param("to", to, Types.OTHER)
                .param("source", h.id(), Types.OTHER)
                .param("customs", h.customsReference())
                .update();
        return ticket;
    }

    /** One ticket line per count line with a variance, numbered as the count's line, in the item's base unit. */
    private UUID insertTicketLine(UUID ticketId, CountLineRow l) {
        UUID id = UUID.randomUUID();
        BigDecimal quantity = l.varianceQty().abs();
        jdbc.sql("""
                INSERT INTO ticket_line (id, ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
                VALUES (:id, :ticket, :lineNo, :item, :quantity, (SELECT base_uom_id FROM item WHERE id = :item),
                        :quantity, :bin)
                """)
                .param("id", id, Types.OTHER)
                .param("ticket", ticketId, Types.OTHER)
                .param("lineNo", (short) l.lineNo())
                .param("item", l.itemId(), Types.OTHER)
                .param("quantity", quantity)
                .param("bin", l.binId(), Types.OTHER)
                .update();
        return id;
    }

    private static String sheetOf(List<LineMeta> lines) {
        if (lines.isEmpty()) return "no lines";
        return lines.size() + (lines.size() == 1 ? " line: " : " lines: ") + lines.stream()
                .map(l -> l.lineNo() + " " + l.itemCode() + (l.binCode() == null ? "" : " (" + l.binCode() + ")"))
                .collect(Collectors.joining(", "));
    }

    private static String describe(List<CountLineRow> lines) {
        return lines.stream().map(l -> l.lineNo() + ": " + l.itemCode() + " " + l.place()
                        + ": book " + plain(l.bookQty()) + ", found " + plain(l.finalQty())
                        + (l.verifiedQty() != null ? " (recounted" + (l.recountDiffers()
                                ? "; first count " + plain(l.countedQty()) : "") + ")" : "")
                        + ", variance " + plain(l.varianceQty()))
                .collect(Collectors.joining("; "));
    }

    private static String join(Set<Integer> lines) {
        return lines.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private CountHeader requireHeader(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new CountNotFoundException(id));
    }

    private CountHeader mapHeader(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new CountHeader(
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
                rs.getObject("location_id", UUID.class),
                rs.getString("location_code"),
                rs.getString("location_name"),
                rs.getBoolean("location_bonded"),
                CountScope.valueOf(rs.getString("scope")),
                rs.getString("customs_reference"),
                rs.getBoolean("verification_signed"),
                rs.getBoolean("book_visible"));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class CountNotFoundException extends RuntimeException {
        public CountNotFoundException(UUID id) {
            super("No stock count with id " + id);
        }
    }
}
