package heritier.ntaganira.highbytes.wms.approval;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.approval
 * - File       : ApprovalQueueService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Every document, of every type and at every branch, whose next signature is the reader's to give
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.document.DocumentKind;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The approval queue: what is waiting on the signed-in user's signature, wherever they hold the role for it.
 *
 * <p>A document is listed when it is PENDING and its next unsigned step is one the reader could sign now, by the
 * rules the database applies when they try ({@code document_approval_guard}, V11, and {@code cnt_signature_guard},
 * V15): they hold the step's role at the document's branch today, and the right the step's action needs there
 * ({@link DocumentKind#rightForStep}); they have not signed it already; they did not raise it, unless the step is
 * the first; and on a count, they took no part in the first count unless the step is the first, and took no
 * verification count unless the step is the verification. They must also hold the type's view right there, or
 * the link would lead to a refusal.
 *
 * <p>Read-only. Signing stays on each document's page, where the reader sees what they sign. A row names the
 * document and its step, never its content, so the queue shows a blind count's book to nobody.
 *
 * <p>Whose queue it is comes from the signed-in user, never from a parameter: nobody reads another's queue. The
 * query itself judges each row by the reader's rights at the row's branch, so the method needs no right of its
 * own beyond being signed in, and neither does the page: someone working where they read no document still sees
 * what waits on them elsewhere.
 */
@Service
@Transactional(readOnly = true)
public class ApprovalQueueService {

    /**
     * Every step action a chain uses, mapped through {@link DocumentKind#rightForStep}. A step with any other
     * label would join no row and wait in nobody's queue; ApprovalQueueTest fails first.
     */
    static final List<String> ACTIONS = List.of("PREPARE", "VERIFY", "COUNTERSIGN", "APPROVE", "RELEASE", "POST");

    /**
     * The right each type's step action needs, and the type's view right, as a constant table built from
     * {@link DocumentKind}: one mapping, so the queue cannot drift from what signing checks.
     */
    private static final String STEP_RIGHTS = Arrays.stream(DocumentKind.values())
            .flatMap(k -> ACTIONS.stream().map(a -> "('%s', '%s', '%s', '%s')"
                    .formatted(k.code(), a, k.rightForStep(a), k.right("view"))))
            .collect(Collectors.joining(",\n                       "));

    private static final String QUEUE = """
            WITH step_right (type_code, action_label, step_right, view_right) AS (
                VALUES %s
            ),
            waiting AS (
                SELECT d.id, d.serial_no, dt.code AS type_code, dt.name AS type_name,
                       d.branch_id, b.code AS branch_code, b.name AS branch_name,
                       d.document_date, d.reference, d.created_by, cu.full_name AS raised_by,
                       nx.sequence_no, nx.action_label, nx.escalate_after_hours, nx.required_role_id,
                       r.name AS role_name, sr.step_right, sr.view_right,
                       (SELECT COUNT(*) FROM workflow_step s
                         WHERE s.workflow_definition_id = d.workflow_definition_id)        AS steps,
                       (SELECT MIN(s.sequence_no) FROM workflow_step s
                         WHERE s.workflow_definition_id = d.workflow_definition_id)        AS first_step,
                       -- Submitting signs step 1, so the last signature is when it reached this step.
                       COALESCE((SELECT MAX(da.decided_at) FROM document_approval da
                                  WHERE da.document_id = d.id), d.created_at)              AS waiting_since
                  FROM document d
                  JOIN document_type dt ON dt.id = d.document_type_id
                  JOIN branch b         ON b.id = d.branch_id
                  JOIN app_user cu      ON cu.id = d.created_by
                  JOIN LATERAL (
                       SELECT ws.sequence_no, ws.action_label, ws.escalate_after_hours, ws.required_role_id
                         FROM workflow_step ws
                        WHERE ws.workflow_definition_id = d.workflow_definition_id
                          AND NOT EXISTS (SELECT 1 FROM document_approval da
                                           WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                        ORDER BY ws.sequence_no
                        LIMIT 1) nx ON TRUE
                  JOIN role r           ON r.id = nx.required_role_id AND r.is_active
                  JOIN step_right sr    ON sr.type_code = dt.code AND sr.action_label = nx.action_label
                 WHERE d.status = 'PENDING'
                   AND (:branch::uuid IS NULL OR d.branch_id = :branch::uuid)
                   AND (:type::text IS NULL OR dt.code = :type::text)
            )
            SELECT w.*, floor(extract(epoch FROM now() - w.waiting_since) / 3600)::bigint AS hours_waiting
              FROM waiting w
             -- The step's role, held at the document's branch today.
             WHERE EXISTS (SELECT 1 FROM user_role ur
                            WHERE ur.user_id = :user AND ur.role_id = w.required_role_id
                              AND ur.revoked_at IS NULL
                              AND ur.valid_from <= kigali_today()
                              AND (ur.valid_to IS NULL OR ur.valid_to >= kigali_today())
                              AND (ur.branch_id IS NULL OR ur.branch_id = w.branch_id))
               -- The right the step's action needs, and the right to read the document, there.
               AND user_holds_right_at(:user, w.step_right, w.branch_id)
               AND user_holds_right_at(:user, w.view_right, w.branch_id)
               -- Nobody signs twice on one document.
               AND NOT EXISTS (SELECT 1 FROM document_approval da
                                WHERE da.document_id = w.id AND da.actor_user_id = :user)
               -- Whoever raised it signs the first step only.
               AND (w.created_by <> :user OR w.sequence_no = w.first_step)
               -- A count is judged by those who did not take it (V15).
               AND NOT (w.type_code = 'CNT' AND (
                        (w.sequence_no <> w.first_step
                         AND EXISTS (SELECT 1 FROM stock_count_line l
                                      WHERE l.document_id = w.id AND l.counted_by = :user))
                     OR (w.action_label <> 'VERIFY'
                         AND EXISTS (SELECT 1 FROM stock_count_line l
                                      WHERE l.document_id = w.id AND l.verified_by = :user))))
            """.formatted(STEP_RIGHTS);

    private static final String LIST = QUEUE + """
             ORDER BY w.waiting_since, w.serial_no
             LIMIT :limit
            """;

    private static final String COUNT = "SELECT COUNT(*) FROM (" + QUEUE + ") q";

    private final JdbcClient jdbc;

    public ApprovalQueueService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What waits on the signed-in user's signature, longest waiting first, optionally at one branch or of one
     * type. Empty when nobody is signed in.
     */
    @PreAuthorize("isAuthenticated()")
    public List<AwaitingSignature> awaiting(UUID branchId, DocumentKind kind, int limit) {
        AppUserDetails user = CurrentUser.get().orElse(null);
        if (user == null) return List.of();
        return jdbc.sql(LIST)
                .param("user", user.id(), Types.OTHER)
                .param("branch", branchId, Types.OTHER)
                .param("type", kind == null ? null : kind.code())
                .param("limit", Math.max(1, limit))
                .query((rs, n) -> map(rs, user))
                .list();
    }

    /** How many documents wait on the signed-in user's signature, at every branch. */
    @PreAuthorize("isAuthenticated()")
    public int count() {
        UUID user = CurrentUser.id();
        if (user == null) return 0;
        return jdbc.sql(COUNT)
                .param("user", user, Types.OTHER)
                .param("branch", null, Types.OTHER)
                .param("type", null)
                .query(Integer.class).single();
    }

    private static AwaitingSignature map(ResultSet rs, AppUserDetails user) throws SQLException {
        UUID branchId = rs.getObject("branch_id", UUID.class);
        String viewRight = rs.getString("view_right");
        String stepRight = rs.getString("step_right");
        // Opening and signing go through the screens of the branch the reader works in, whose rights are the
        // ones loaded for it; holding both here as well, they need not switch.
        boolean openHere = branchId.equals(user.branchId()) || (user.has(viewRight) && user.has(stepRight));
        return new AwaitingSignature(
                rs.getObject("id", UUID.class),
                rs.getString("serial_no"),
                rs.getString("type_code"),
                rs.getString("type_name"),
                branchId,
                rs.getString("branch_code"),
                rs.getString("branch_name"),
                rs.getObject("document_date", java.time.LocalDate.class),
                rs.getString("reference"),
                rs.getString("raised_by"),
                rs.getInt("sequence_no"),
                rs.getInt("steps"),
                rs.getString("action_label"),
                rs.getString("role_name"),
                KigaliTime.read(rs, "waiting_since"),
                Math.max(0, rs.getLong("hours_waiting")),
                rs.getObject("escalate_after_hours", Integer.class),
                openHere);
    }
}
