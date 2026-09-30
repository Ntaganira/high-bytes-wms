package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : GateSteps.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The unsigned steps a release banner names, for any document that gates stock leaving
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.document.ChainStep;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Works out, from a document's bound chain, what a release banner must say
 * about the steps still unsigned: each one's role, who at the branch could
 * sign it, how long it has waited, and whether that is past its escalation
 * time. Shared by every document whose approval gates stock leaving (a
 * delivery authorization, a transfer), so the banner says the same thing in
 * the same way wherever it appears.
 */
@Service
@Transactional(readOnly = true)
public class GateSteps {

    private final JdbcClient jdbc;

    public GateSteps(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param documentId  the document
     * @param branchId    the branch whose holders of each role are listed
     * @param createdBy   who raised it: listed for step 1 only, since they cannot sign a later one
     * @param status      DRAFT or PENDING; only a pending document has a step anyone is waiting on
     * @param submittedAt when it was submitted, the start of the first wait
     * @param chain       the document's bound chain with each step's decision
     * @param now         the moment the waits are measured to
     */
    public List<ReleaseGate.Waiting> waiting(UUID documentId, UUID branchId, UUID createdBy, String status,
                                             LocalDateTime submittedAt, List<ChainStep> chain, LocalDateTime now) {
        Map<Integer, Integer> escalation = new HashMap<>();
        jdbc.sql("""
                SELECT ws.sequence_no, ws.escalate_after_hours FROM workflow_step ws
                  JOIN document d ON d.workflow_definition_id = ws.workflow_definition_id WHERE d.id = :id
                """).param("id", documentId, Types.OTHER)
                .query((rs, n) -> {
                    int hours = rs.getInt("escalate_after_hours");
                    escalation.put(rs.getInt("sequence_no"), rs.wasNull() ? null : hours);
                    return null;
                }).list();

        Set<UUID> signers = chain.stream().map(ChainStep::actorUserId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        LocalDateTime since = submittedAt;
        List<ReleaseGate.Waiting> waiting = new ArrayList<>();
        boolean currentTaken = false;
        for (ChainStep step : chain) {
            if (step.decision() != null) {
                if (step.decidedAt() != null) since = step.decidedAt();
                continue;
            }
            boolean current = !currentTaken && "PENDING".equals(status);
            if (current) currentTaken = true;
            boolean first = step.sequenceNo() == chain.get(0).sequenceNo();
            List<String> holders = holdersOf(step, branchId, createdBy, signers, first);
            Long hours = current && since != null ? Duration.between(since, now).toHours() : null;
            Integer limit = escalation.get(step.sequenceNo());
            waiting.add(new ReleaseGate.Waiting(step.sequenceNo(), step.actionLabel(), step.roleName(), holders,
                    hours, limit, hours != null && limit != null && hours > limit, current));
        }
        return waiting;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(KigaliTime.ZONE);
    }

    /** Who at the branch could sign the step today: holds its role, has not signed, and did not raise it unless it is step 1. */
    private List<String> holdersOf(ChainStep step, UUID branchId, UUID createdBy, Set<UUID> signers, boolean first) {
        record Person(UUID id, String name) {}
        return jdbc.sql("""
                SELECT DISTINCT u.id, u.full_name
                  FROM user_role ur
                  JOIN app_user u ON u.id = ur.user_id AND u.is_active
                  JOIN role r     ON r.id = ur.role_id AND r.is_active
                 WHERE ur.role_id = :role AND ur.revoked_at IS NULL
                   AND ur.valid_from <= kigali_today()
                   AND (ur.valid_to IS NULL OR ur.valid_to >= kigali_today())
                   AND (ur.branch_id IS NULL OR ur.branch_id = :branch)
                 ORDER BY u.full_name
                """)
                .param("role", step.roleId(), Types.OTHER)
                .param("branch", branchId, Types.OTHER)
                .query((rs, n) -> new Person(rs.getObject("id", UUID.class), rs.getString("full_name")))
                .list().stream()
                .filter(p -> !signers.contains(p.id()))
                .filter(p -> first || !p.id().equals(createdBy))
                .map(Person::name)
                .toList();
    }
}
