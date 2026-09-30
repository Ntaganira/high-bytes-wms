package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : DocumentService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The document engine: open, read the bound chain, submit, sign, cancel, post, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The generic spine and workflow engine every document type runs on.
 *
 * <p>It knows nothing about receipts. It opens a document with a serial,
 * reads the chain the document was bound to on its creation date, takes
 * signatures one step at a time, cancels, and marks a document posted. A
 * module (receiving now; dispatch, transfers and counts later) supplies the
 * content and, on posting, the ledger movements.
 *
 * <p>Who may do what is judged three times, deliberately. {@code @PreAuthorize}
 * on the module's service method authorises the user at the branch they work
 * in. This class then checks the right for the document's own branch
 * ({@link CurrentUser#requireAt}) and the right the step's action needs. And
 * the database, whose triggers (V11) refuse an out-of-order signature, a signer
 * who does not hold the step's role, the creator signing after step 1, a poster
 * who raised or signed the document, and any header or line change after
 * DRAFT. Where this class has no rule of its own, the database's reason is
 * what the user reads.
 *
 * <p>Every method here that changes a document runs inside the caller's
 * transaction and audits after that transaction commits, so the trail never
 * claims a change the commit went on to refuse. A refusal is recorded as
 * REJECT and survives the rollback.
 */
@Service
@Transactional(readOnly = true)
public class DocumentService {

    private static final String HEADER = """
            SELECT d.id, dt.code AS type_code, d.branch_id, b.code AS branch_code, b.name AS branch_name,
                   b.is_bonded, b.branch_type, d.serial_no, d.status, d.workflow_definition_id,
                   d.document_date, d.created_by, d.posted_by, d.version
              FROM document d
              JOIN document_type dt ON dt.id = d.document_type_id
              JOIN branch b         ON b.id = d.branch_id
             WHERE d.id = :id
            """;

    private static final String CHAIN = """
            SELECT ws.id AS step_id, ws.sequence_no, ws.action_label, ws.is_mandatory,
                   r.id AS role_id, r.code AS role_code, r.name AS role_name,
                   da.decision, da.actor_user_id, da.actor_name, da.actor_role_label,
                   da.decided_at, da.comment
              FROM document d
              JOIN workflow_step ws ON ws.workflow_definition_id = d.workflow_definition_id
              JOIN role r           ON r.id = ws.required_role_id
         LEFT JOIN document_approval da ON da.document_id = d.id AND da.workflow_step_id = ws.id
             WHERE d.id = :id
             ORDER BY ws.sequence_no
            """;

    private static final String HOLDS_ROLE = """
            SELECT EXISTS (
                SELECT 1 FROM user_role ur
                  JOIN role r ON r.id = ur.role_id AND r.is_active
                 WHERE ur.user_id = :user AND ur.role_id = :role
                   AND ur.revoked_at IS NULL
                   AND ur.valid_from <= kigali_today()
                   AND (ur.valid_to IS NULL OR ur.valid_to >= kigali_today())
                   AND (ur.branch_id IS NULL OR ur.branch_id = :branch))
            """;

    static final String POSTED_REVERSAL_NOTE =
            "stock has moved. A posted document is never cancelled or edited: what it did is corrected with a "
            + "reversing document, which keeps both the original and the correction in the ledger (not built yet).";

    private final JdbcClient jdbc;
    private final SerialService serials;
    private final AuditService audit;

    public DocumentService(JdbcClient jdbc, SerialService serials, AuditService audit) {
        this.jdbc = jdbc;
        this.serials = serials;
        this.audit = audit;
    }

    // ---- reads -----------------------------------------------------------

    public DocumentHeader header(UUID id) {
        return jdbc.sql(HEADER).param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    /** The header, locking the row until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentHeader lock(UUID id) {
        return jdbc.sql(HEADER + " FOR UPDATE OF d").param("id", id, Types.OTHER)
                .query(this::mapHeader).optional()
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    /**
     * The chain the document is bound to, each step with its state. Bound at
     * creation by the document's date: read from the document's own
     * {@code workflow_definition_id}, never chosen by today's date.
     */
    public List<ChainStep> chain(UUID documentId) {
        String status = jdbc.sql("SELECT status FROM document WHERE id = :id")
                .param("id", documentId, Types.OTHER).query(String.class).optional()
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        boolean open = "DRAFT".equals(status) || "PENDING".equals(status);

        record Raw(UUID stepId, int seq, String action, boolean mandatory, UUID roleId, String roleCode,
                   String roleName, String roleLabel, String decision, UUID actorId, String actorName,
                   java.time.LocalDateTime decidedAt, String comment) {}

        List<Raw> raw = jdbc.sql(CHAIN).param("id", documentId, Types.OTHER)
                .query((rs, n) -> new Raw(
                        rs.getObject("step_id", UUID.class),
                        rs.getInt("sequence_no"),
                        rs.getString("action_label"),
                        rs.getBoolean("is_mandatory"),
                        rs.getObject("role_id", UUID.class),
                        rs.getString("role_code"),
                        rs.getString("role_name"),
                        rs.getString("actor_role_label"),
                        rs.getString("decision"),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_name"),
                        KigaliTime.read(rs, "decided_at"),
                        rs.getString("comment")))
                .list();

        List<ChainStep> steps = new ArrayList<>();
        boolean currentTaken = false;
        for (Raw r : raw) {
            String state;
            if ("APPROVED".equals(r.decision())) {
                state = "signed";
            } else if ("REJECTED".equals(r.decision())) {
                state = "rejected";
            } else if (open && !currentTaken) {
                state = "current";
                currentTaken = true;
            } else {
                state = "pending";
            }
            // A signature keeps the role title it was given under.
            String roleName = r.decision() != null && r.roleLabel() != null ? r.roleLabel() : r.roleName();
            steps.add(new ChainStep(r.stepId(), r.seq(), r.action(), r.mandatory(), r.roleId(), r.roleCode(),
                    roleName, r.decision(), r.actorId(), r.actorName(), r.decidedAt(), r.comment(), state));
        }
        return steps;
    }

    public Optional<ChainInfo> chainInfo(UUID documentId) {
        return jdbc.sql("""
                SELECT wd.version, wd.basis, wd.effective_from, wd.effective_to, wd.notes
                  FROM document d JOIN workflow_definition wd ON wd.id = d.workflow_definition_id
                 WHERE d.id = :id
                """)
                .param("id", documentId, Types.OTHER)
                .query((rs, n) -> new ChainInfo(rs.getInt("version"), rs.getString("basis"),
                        rs.getObject("effective_from", java.time.LocalDate.class),
                        rs.getObject("effective_to", java.time.LocalDate.class),
                        rs.getString("notes")))
                .optional();
    }

    /**
     * Documents of a type at a branch whose next signature is the user's to
     * give: they hold the step's role and its right, did not raise the
     * document (unless it is step 1) and have not signed it. Ids only; the
     * module joins them to its own rows.
     */
    public Set<UUID> awaitingSignatureOf(DocumentKind kind, UUID branchId, UUID userId) {
        record Candidate(UUID id, String action) {}
        List<Candidate> candidates = jdbc.sql("""
                SELECT d.id, nx.action_label
                  FROM document d
                  JOIN document_type dt ON dt.id = d.document_type_id
                  JOIN LATERAL (
                       SELECT ws.sequence_no, ws.action_label, ws.required_role_id
                         FROM workflow_step ws
                        WHERE ws.workflow_definition_id = d.workflow_definition_id
                          AND NOT EXISTS (SELECT 1 FROM document_approval da
                                           WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                        ORDER BY ws.sequence_no LIMIT 1) nx ON TRUE
                 WHERE dt.code = :code AND d.branch_id = :branch AND d.status = 'PENDING'
                   AND EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id = ur.role_id AND r.is_active
                                WHERE ur.user_id = :user AND ur.role_id = nx.required_role_id
                                  AND ur.revoked_at IS NULL
                                  AND ur.valid_from <= kigali_today()
                                  AND (ur.valid_to IS NULL OR ur.valid_to >= kigali_today())
                                  AND (ur.branch_id IS NULL OR ur.branch_id = d.branch_id))
                   AND NOT EXISTS (SELECT 1 FROM document_approval da
                                    WHERE da.document_id = d.id AND da.actor_user_id = :user)
                   AND (d.created_by <> :user
                        OR nx.sequence_no = (SELECT MIN(sequence_no) FROM workflow_step
                                              WHERE workflow_definition_id = d.workflow_definition_id))
                """)
                .param("code", kind.code())
                .param("branch", branchId, Types.OTHER)
                .param("user", userId, Types.OTHER)
                .query((rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getString("action_label")))
                .list();
        return candidates.stream()
                .filter(c -> CurrentUser.holdsAt(kind.rightForStep(c.action()), branchId))
                .map(Candidate::id)
                .collect(Collectors.toSet());
    }

    // ---- "can I, and if not why" -------------------------------------------

    /** Whether the signed-in user may submit the draft, which signs its first step. */
    public StepCheck canSubmit(DocumentHeader d, List<ChainStep> chain) {
        if (!"DRAFT".equals(d.status())) {
            return StepCheck.no(null, d.serialNo() + " is " + d.status()
                    + "; only a draft is submitted.");
        }
        if (chain.isEmpty()) {
            return StepCheck.no(null, d.serialNo() + " has no approval chain, so it cannot be submitted.");
        }
        ChainStep first = chain.get(0);
        UUID me = CurrentUser.id();
        if (!holdsRole(me, first.roleId(), d.branchId())) {
            return StepCheck.no(first, "Submitting signs step 1 (" + first.actionLabel() + ") as "
                    + first.roleName() + ". You do not hold that role at " + d.branchName()
                    + " today, so you cannot submit " + d.serialNo() + ".");
        }
        String right = d.kind().rightForStep(first.actionLabel());
        if (!CurrentUser.holdsAt(right, d.branchId())) {
            return StepCheck.no(first, "Submitting signs step 1 (" + first.actionLabel() + "), which needs the "
                    + right + " right at " + d.branchName() + ". You do not hold it.");
        }
        return StepCheck.yes(first);
    }

    /** Whether the signed-in user may sign the next step of a pending document. */
    public StepCheck canSign(DocumentHeader d, List<ChainStep> chain) {
        if (!"PENDING".equals(d.status())) {
            return StepCheck.no(null, d.serialNo() + " is " + d.status() + ", so it is not awaiting signatures.");
        }
        ChainStep next = chain.stream().filter(ChainStep::unsigned).findFirst().orElse(null);
        if (next == null) {
            return StepCheck.no(null, "Every step of " + d.serialNo() + " has signed.");
        }
        UUID me = CurrentUser.id();
        Optional<ChainStep> mine = chain.stream().filter(s -> me != null && me.equals(s.actorUserId())).findFirst();
        if (mine.isPresent()) {
            return StepCheck.no(next, "You already signed step " + mine.get().sequenceNo() + " of " + d.serialNo()
                    + ". Nobody signs twice on one document, so step " + next.sequenceNo() + " (" + next.roleName()
                    + ") is for someone else.");
        }
        if (me != null && me.equals(d.createdBy()) && next.sequenceNo() > chain.get(0).sequenceNo()) {
            return StepCheck.no(next, "You raised this document, so you cannot sign step " + next.sequenceNo()
                    + ". Whoever raises a document does not also approve it.");
        }
        if (!holdsRole(me, next.roleId(), d.branchId())) {
            return StepCheck.no(next, "Step " + next.sequenceNo() + " (" + next.actionLabel() + ") is signed by "
                    + next.roleName() + ". You do not hold that role at " + d.branchName() + " today.");
        }
        String right = d.kind().rightForStep(next.actionLabel());
        if (!CurrentUser.holdsAt(right, d.branchId())) {
            return StepCheck.no(next, "You hold the role for step " + next.sequenceNo()
                    + " but not the " + right + " right at " + d.branchName() + ".");
        }
        return StepCheck.yes(next);
    }

    /** Whether the signed-in user may post an approved document. */
    public StepCheck canPost(DocumentHeader d, List<ChainStep> chain) {
        if (!"APPROVED".equals(d.status())) {
            return StepCheck.no(null, "POSTED".equals(d.status())
                    ? d.serialNo() + " is already posted."
                    : d.serialNo() + " is " + d.status() + "; only an approved document is posted.");
        }
        String right = d.kind().right("post");
        if (!CurrentUser.holdsAt(right, d.branchId())) {
            return StepCheck.no(null, "Posting needs the " + right + " right at " + d.branchName() + ".");
        }
        UUID me = CurrentUser.id();
        if (me != null && me.equals(d.createdBy())) {
            return StepCheck.no(null, "You raised " + d.serialNo() + ", so you cannot post it. Raising and recording "
                    + "the same transaction is the concentration the Board found.");
        }
        Optional<ChainStep> signed = chain.stream().filter(s -> me != null && me.equals(s.actorUserId())).findFirst();
        if (signed.isPresent()) {
            return StepCheck.no(null, "You signed step " + signed.get().sequenceNo() + " of " + d.serialNo()
                    + ", so you cannot post it. Whoever approved a transaction does not record it in the ledger.");
        }
        return StepCheck.yes(null);
    }

    /** Whether the signed-in user may cancel the document. */
    public StepCheck canCancel(DocumentHeader d) {
        switch (d.status()) {
            case "POSTED" -> {
                return StepCheck.no(null, d.serialNo() + " is posted, so it cannot be cancelled: " + POSTED_REVERSAL_NOTE);
            }
            case "REJECTED", "CANCELLED" -> {
                return StepCheck.no(null, d.serialNo() + " is " + d.status() + " and final.");
            }
            default -> { /* DRAFT, PENDING, APPROVED may be cancelled */ }
        }
        // Everyone, the raiser included, needs the right at the document's own branch: raising a
        // note once does not keep the power to cancel it after the right has gone.
        if (!CurrentUser.holdsAt(d.kind().right("create"), d.branchId())) {
            return StepCheck.no(null, "Cancelling " + d.serialNo() + " needs the " + d.kind().right("create")
                    + " right at " + d.branchName() + ", whoever raised it.");
        }
        return StepCheck.yes(null);
    }

    // ---- writes ----------------------------------------------------------

    /**
     * Opens a draft: takes the next serial, and lets the database date it
     * today (Kigali) and bind the chain in force today. Rights are the calling
     * module's to check; this method needs only a signed-in user to be the
     * document's creator.
     */
    @Transactional
    public OpenedDocument open(DocumentKind kind, UUID branchId, String reference, String notes, UUID supersedesId) {
        UUID user = CurrentUser.get().orElseThrow(() -> new AccessDeniedException("Not signed in")).id();
        String serial = serials.next(kind.code(), branchId);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO document (id, document_type_id, branch_id, serial_no, reference, notes,
                                          created_by, supersedes_document_id)
                    VALUES (:id, (SELECT dt.id FROM document_type dt WHERE dt.code = :code),
                            :branch, :serial, :reference, :notes, :user, :supersedes)
                    """)
                    .param("id", id, Types.OTHER)
                    .param("code", kind.code())
                    .param("branch", branchId, Types.OTHER)
                    .param("serial", serial)
                    .param("reference", blankToNull(reference))
                    .param("notes", blankToNull(notes))
                    .param("user", user, Types.OTHER)
                    .param("supersedes", supersedesId, Types.OTHER)
                    .update();
        } catch (DataAccessException e) {
            throw DbRefusal.asRefusal(e);
        }
        return new OpenedDocument(id, serial);
    }

    /**
     * Changes the header of a document, if nobody changed it since the caller
     * read it (optimistic locking on {@code document.version}). Whether the
     * document is still a draft is the database's to judge: it refuses a
     * header change once the document has left DRAFT.
     */
    @Transactional
    public void updateDraft(DocumentHeader d, int expectedVersion, String reference, String notes) {
        int rows;
        try {
            rows = jdbc.sql("""
                    UPDATE document SET reference = :reference, notes = :notes, version = version + 1
                     WHERE id = :id AND version = :version
                    """)
                    .param("id", d.id(), Types.OTHER)
                    .param("version", expectedVersion)
                    .param("reference", blankToNull(reference))
                    .param("notes", blankToNull(notes))
                    .update();
        } catch (DataAccessException e) {
            throw refusedBy(d, "Edit the draft", e);
        }
        if (rows == 0) {
            throw refused(d, "Edit the draft", "DRAFT".equals(d.status())
                    ? d.serialNo() + " was changed by someone else after you opened it. Reload it and make your changes again."
                    : d.serialNo() + " is " + d.status() + ", so its content cannot change. The approvers signed what it "
                      + "says; correct it by cancelling and raising a new note.");
        }
    }

    /**
     * Submits a draft: it moves to PENDING and the submitter signs step 1 in
     * the same transaction. The submitter must hold step 1's role and its
     * right; the database refuses otherwise.
     */
    @Transactional
    public DocumentHeader submit(UUID id) {
        DocumentHeader d = lock(id);
        List<ChainStep> chain = chain(id);
        StepCheck check = canSubmit(d, chain);
        if (!check.allowed()) {
            throw refused(d, "Submit", check.reason());
        }
        ChainStep first = check.step();
        try {
            setStatus(id, "PENDING", null);
            insertSignature(d, first, "APPROVED", null);
            approveIfComplete(d);
        } catch (DataAccessException e) {
            throw refusedBy(d, "Submit", e);
        }
        DocumentHeader after = header(id);
        audit.recordInTransaction("document", id, d.label(), AuditAction.UPDATE,
                AuditSnapshot.of().field("Status", "DRAFT", "PENDING"), d.branch(), null);
        audit.recordInTransaction("document", id, d.label(), AuditAction.APPROVE,
                signatureSnapshot(first, "Signed by submitting", after.status()), d.branch(), null);
        return after;
    }

    /**
     * Signs the next step, approving or rejecting it. A rejection needs a
     * reason and ends the document: REJECTED is final, and a correction is a
     * new document.
     *
     * <p>The right the step's action needs is checked here; the step's role,
     * the order of signatures, the creator's exclusion after step 1 and the
     * one-signature-per-person rule are the database's, and its reason is
     * what the user reads.
     */
    @Transactional
    public DocumentHeader sign(UUID id, boolean approve, String comment) {
        DocumentHeader d = lock(id);
        List<ChainStep> chain = chain(id);
        ChainStep step = chain.stream().filter(ChainStep::unsigned).findFirst().orElse(null);
        String note = blankToNull(comment);
        String attempt = (approve ? "Approve" : "Reject") + (step == null ? "" : " step " + step.sequenceNo());

        if (!"PENDING".equals(d.status()) || step == null) {
            throw refused(d, attempt, d.serialNo() + " is " + d.status()
                    + ", so it takes no signature. Signatures are taken only while a submitted document awaits approval.");
        }
        CurrentUser.requireAt(d.kind().rightForStep(step.actionLabel()), d.branchId());
        if (!approve && note == null) {
            throw refused(d, attempt, "A rejection needs a reason: say what is wrong so the document can be raised again correctly.");
        }
        try {
            insertSignature(d, step, approve ? "APPROVED" : "REJECTED", note);
            if (approve) {
                approveIfComplete(d);
            } else {
                setStatus(id, "REJECTED", null);
            }
        } catch (DataAccessException e) {
            if (DbRefusal.constraint(e).filter("document_approval_one_signature_per_user"::equals).isPresent()) {
                throw refused(d, attempt, "You already signed " + d.serialNo()
                        + ". Nobody signs twice on one document.");
            }
            throw refusedBy(d, attempt, e);
        }
        DocumentHeader after = header(id);
        audit.recordInTransaction("document", id, d.label(),
                approve ? AuditAction.APPROVE : AuditAction.REJECT,
                signatureSnapshot(step, approve ? "Approved" : "Rejected", after.status()), d.branch(), note);
        return after;
    }

    /**
     * Cancels a draft, pending or approved document with a reason. The serial
     * stays on the register. A posted document is refused: what it did to the
     * ledger is corrected by a reversing document.
     */
    @Transactional
    public DocumentHeader cancel(UUID id, String reason) {
        DocumentHeader d = lock(id);
        CurrentUser.requireAt(d.kind().right("create"), d.branchId());
        String note = blankToNull(reason);
        StepCheck check = canCancel(d);
        if (!check.allowed()) {
            throw refused(d, "Cancel", check.reason());
        }
        if (note == null) {
            throw refused(d, "Cancel", "Cancelling " + d.serialNo() + " needs a reason. The serial stays on the "
                    + "register, and the reason is what explains it.");
        }
        try {
            jdbc.sql("""
                    UPDATE document SET status = 'CANCELLED', cancelled_by = :user, cancel_reason = :reason,
                                        version = version + 1
                     WHERE id = :id
                    """)
                    .param("id", id, Types.OTHER)
                    .param("user", CurrentUser.id(), Types.OTHER)
                    .param("reason", note)
                    .update();
        } catch (DataAccessException e) {
            throw refusedBy(d, "Cancel", e);
        }
        audit.recordInTransaction("document", id, d.label(), AuditAction.CANCEL,
                AuditSnapshot.of().field("Status", d.status(), "CANCELLED"), d.branch(), note);
        return header(id);
    }

    /**
     * Marks an approved document posted, by the signed-in user. The caller
     * then writes the ledger in the same transaction; the database refuses
     * a poster who raised or signed the document, and a document that is not
     * fully approved, and at commit refuses a posted document whose stock did
     * not move.
     */
    @Transactional
    public DocumentHeader beginPost(UUID id) {
        DocumentHeader d = lock(id);
        CurrentUser.requireAt(d.kind().right("post"), d.branchId());
        if ("POSTED".equals(d.status())) {
            throw refused(d, "Post", d.serialNo() + " is already posted. Posting twice would move the stock twice.");
        }
        try {
            setStatus(id, "POSTED", CurrentUser.id());
        } catch (DataAccessException e) {
            throw refusedBy(d, "Post", e);
        }
        return d;
    }

    /**
     * Marks a derived document (a transaction ticket, which has no chain of its
     * own) posted by the signed-in user, once its lines have moved stock. The
     * database allows it only when the document it answers to is fully
     * approved.
     */
    @Transactional
    public void postDerived(DocumentHeader source, UUID ticketId) {
        try {
            int rows = jdbc.sql("""
                    UPDATE document SET status = 'POSTED', posted_by = :user, version = version + 1
                     WHERE id = :id AND status = 'DRAFT'
                    """)
                    .param("id", ticketId, Types.OTHER)
                    .param("user", CurrentUser.id(), Types.OTHER)
                    .update();
            if (rows != 1) {
                throw new IllegalStateException("The ticket " + ticketId + " was not a draft when it was posted.");
            }
        } catch (DataAccessException e) {
            throw refusedBy(source, "Post", e);
        }
    }

    /** Records the posting in the posting's own transaction: both commit, or neither does. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void auditPosted(DocumentHeader d, AuditSnapshot snapshot) {
        audit.recordInTransaction("document", d.id(), d.label(), AuditAction.POST, snapshot, d.branch(), null);
    }

    /** Records a change to a document's content in the change's own transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void auditInTransaction(String label, UUID id, AuditAction action, AuditSnapshot snapshot,
                                   heritier.ntaganira.highbytes.wms.branch.BranchView branch) {
        audit.recordInTransaction("document", id, label, action, snapshot, branch, null);
    }

    /**
     * A refusal that only surfaced when the transaction tried to commit (a
     * deferred trigger). The transaction has rolled back, so the change and
     * its success row are gone; this writes the REJECT row that stays, and
     * returns the refusal for the caller to show. Empty when the failure is
     * not a control speaking. Call it outside any transaction.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<ControlRefusedException> refusedAtCommit(UUID documentId, String attempt, Throwable failure) {
        Optional<String> reason = DbRefusal.reason(failure);
        if (reason.isEmpty()) return Optional.empty();
        DocumentHeader d = jdbc.sql(HEADER).param("id", documentId, Types.OTHER)
                .query(this::mapHeader).optional().orElse(null);
        if (d != null) {
            audit.record("document", d.id(), d.label(), AuditAction.REJECT,
                    AuditSnapshot.of().value("Attempted", attempt), d.branch(), reason.get());
        }
        return Optional.of(new ControlRefusedException(reason.get()));
    }

    // ---- refusals -----------------------------------------------------------

    /**
     * A refusal, recorded as REJECT in the audit trail (which survives the
     * rollback) and returned for the caller to throw.
     */
    public ControlRefusedException refused(DocumentHeader d, String attempt, String reason) {
        audit.recordRefusal("document", d.id(), d.label(),
                AuditSnapshot.of().value("Attempted", attempt), d.branch(), reason);
        return new ControlRefusedException(reason);
    }

    /** A database failure as a refusal when a control spoke; otherwise the failure itself. */
    public RuntimeException refusedBy(DocumentHeader d, String attempt, DataAccessException failure) {
        return DbRefusal.reason(failure)
                .<RuntimeException>map(reason -> refused(d, attempt, reason))
                .orElse(failure);
    }

    // ---- internals ---------------------------------------------------------

    private void setStatus(UUID id, String status, UUID postedBy) {
        jdbc.sql("""
                UPDATE document SET status = :status, posted_by = COALESCE(:postedBy, posted_by),
                                    version = version + 1
                 WHERE id = :id
                """)
                .param("id", id, Types.OTHER)
                .param("status", status)
                .param("postedBy", postedBy, Types.OTHER)
                .update();
    }

    private void insertSignature(DocumentHeader d, ChainStep step, String decision, String comment) {
        var me = CurrentUser.get().orElseThrow(() -> new AccessDeniedException("Not signed in"));
        // The database overwrites the name, role label and time with its own;
        // these are placeholders that happen to be true.
        jdbc.sql("""
                INSERT INTO document_approval (document_id, workflow_step_id, actor_user_id, actor_name,
                                               actor_role_label, decision, comment, client_address)
                VALUES (:doc, :step, :user, :name, :role, :decision, :comment, :address)
                """)
                .param("doc", d.id(), Types.OTHER)
                .param("step", step.stepId(), Types.OTHER)
                .param("user", me.id(), Types.OTHER)
                .param("name", me.fullName())
                .param("role", step.roleName())
                .param("decision", decision)
                .param("comment", comment)
                .param("address", clientAddress())
                .update();
    }

    /** APPROVED once the signatures themselves show every mandatory step signed. */
    private void approveIfComplete(DocumentHeader d) {
        String gap = jdbc.sql("SELECT document_approval_gap(:id)")
                .param("id", d.id(), Types.OTHER).query(String.class).optional().orElse(null);
        if (gap == null) {
            setStatus(d.id(), "APPROVED", null);
        }
    }

    private boolean holdsRole(UUID userId, UUID roleId, UUID branchId) {
        if (userId == null) return false;
        return jdbc.sql(HOLDS_ROLE)
                .param("user", userId, Types.OTHER)
                .param("role", roleId, Types.OTHER)
                .param("branch", branchId, Types.OTHER)
                .query(Boolean.class).single();
    }

    private AuditSnapshot signatureSnapshot(ChainStep step, String what, String resultingStatus) {
        return AuditSnapshot.of()
                .value("Step", step.sequenceNo() + " · " + step.actionLabel() + " · " + step.roleName())
                .value("Decision", what)
                .value("Status after", resultingStatus);
    }

    private DocumentHeader mapHeader(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DocumentHeader(
                rs.getObject("id", UUID.class),
                DocumentKind.of(rs.getString("type_code")),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_code"),
                rs.getString("branch_name"),
                rs.getBoolean("is_bonded"),
                rs.getString("branch_type"),
                rs.getString("serial_no"),
                rs.getString("status"),
                rs.getObject("workflow_definition_id", UUID.class),
                rs.getObject("document_date", java.time.LocalDate.class),
                rs.getObject("created_by", UUID.class),
                rs.getObject("posted_by", UUID.class),
                rs.getInt("version"));
    }

    private static String clientAddress() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) return null;
        HttpServletRequest request = servlet.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        String address = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return address == null ? null : address.substring(0, Math.min(60, address.length()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException(UUID id) {
            super("No document with id " + id);
        }
    }
}
