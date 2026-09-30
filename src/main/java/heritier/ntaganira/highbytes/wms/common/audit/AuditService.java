package heritier.ntaganira.highbytes.wms.common.audit;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.audit
 * - File       : AuditService.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Writes and reads the append-only audit trail
 * </pre>
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes and reads the audit trail (FR-SEC-05, FR-SEC-22..29).
 *
 * <p>The table refuses UPDATE and DELETE by trigger, so this class only ever
 * inserts. Actor name, username, role and branch are captured as text at
 * write time rather than joined at read time — a role renamed next year must
 * not rewrite what happened this year.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final String INSERT = """
            INSERT INTO audit_log (entity_name, entity_id, entity_label, document_id, action,
                                   before_state, after_state,
                                   actor_user_id, actor_name, actor_username, actor_role,
                                   branch_id, branch_name, client_address, user_agent, reason)
            VALUES (:entityName, :entityId, :entityLabel, :documentId, :action,
                    CAST(:beforeState AS jsonb), CAST(:afterState AS jsonb),
                    :actorUserId, :actorName, :actorUsername, :actorRole,
                    :branchId, :branchName, :clientAddress, :userAgent, :reason)
            """;

    private static final String BY_ENTITY = """
            SELECT id, entity_name, entity_id, entity_label, document_id, action,
                   before_state::text AS before_state, after_state::text AS after_state,
                   actor_name, actor_username, actor_role, branch_name,
                   occurred_at, client_address, reason
              FROM audit_log
             WHERE entity_name = :entityName AND entity_id = :entityId
             ORDER BY occurred_at DESC, id DESC
             LIMIT :limit
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate separately;

    public AuditService(JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.separately = new TransactionTemplate(transactions);
        this.separately.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Log a change.
     *
     * <p>Runs in its own transaction: the audit record of an attempt survives
     * the attempt being rolled back, which is the whole point of having one.
     * A failure here is logged and swallowed — losing an audit row is bad,
     * but failing the user's work because the audit write failed is worse,
     * and the application log keeps the evidence either way. Text longer than
     * its column is cut to fit, so no value a user types can cause that loss.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String entityName,
                       UUID entityId,
                       String entityLabel,
                       AuditAction action,
                       AuditSnapshot snapshot,
                       BranchView branch,
                       String reason) {
        try {
            insert(entityName, entityId, entityLabel, action, snapshot, branch, reason);
        } catch (Exception e) {
            log.error("Audit write failed for {} {} action {} — the change itself was not rolled back",
                    entityName, entityId, action, e);
        }
    }

    /**
     * Log a change to who may do what, as part of the change itself.
     *
     * <p>Runs in the caller's transaction and lets a failure through, so the
     * change and its record commit together or not at all. For access the
     * audit trail is the only account of who granted, revoked, reset or
     * deactivated: an access change that cannot be recorded does not happen.
     * A refused attempt is logged with {@link #record}, which survives the
     * refusal's rollback.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAccessChange(String entityName,
                                   UUID entityId,
                                   String entityLabel,
                                   AuditAction action,
                                   AuditSnapshot snapshot,
                                   BranchView branch,
                                   String reason) {
        insert(entityName, entityId, entityLabel, action, snapshot, branch, reason);
    }

    /**
     * Log a change to a document or its content as part of the change itself.
     *
     * <p>Same semantics as {@link #recordAccessChange}: runs in the caller's
     * transaction and lets a failure through, so a change and its record
     * commit together or not at all. A refusal at commit rolls both back; the
     * refusal is then recorded with {@link #record} or {@link #recordRefusal}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInTransaction(String entityName,
                                    UUID entityId,
                                    String entityLabel,
                                    AuditAction action,
                                    AuditSnapshot snapshot,
                                    BranchView branch,
                                    String reason) {
        insert(entityName, entityId, entityLabel, action, snapshot, branch, reason);
    }

    /**
     * Log a refused access change. The refusal rolls its transaction back,
     * so the record is written once that is done, in a transaction of its
     * own: the attempt stays on file, and nothing holds the access lock while
     * the record waits for a connection. Outside a transaction it is written
     * at once. A failure is logged and swallowed, as for {@link #record}.
     */
    public void recordRefusal(String entityName, UUID entityId, String entityLabel,
                              AuditSnapshot snapshot, BranchView branch, String reason) {
        Runnable write = () -> {
            try {
                separately.executeWithoutResult(status ->
                        insert(entityName, entityId, entityLabel, AuditAction.REJECT, snapshot, branch, reason));
            } catch (Exception e) {
                log.error("Audit write failed for a refused change to {} {}", entityName, entityId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }

    private void insert(String entityName, UUID entityId, String entityLabel, AuditAction action,
                        AuditSnapshot snapshot, BranchView branch, String reason) {
        var user = currentUser();
        var request = currentRequest();

        // Every text value cut to its column (V1 audit_log).
        jdbc.sql(INSERT)
            .param("entityName", entityName)
            .param("entityId", entityId, Types.OTHER)
            .param("entityLabel", truncate(entityLabel, 200))
            .param("documentId", null, Types.OTHER)
            .param("action", action.name())
            .param("beforeState", snapshot == null || snapshot.before().isEmpty()
                    ? null : write(snapshot.before()))
            .param("afterState", snapshot == null ? null : write(snapshot.after()))
            .param("actorUserId", user == null ? null : user.id(), Types.OTHER)
            .param("actorName", truncate(user == null ? "System" : user.fullName(), 160))
            .param("actorUsername", truncate(user == null ? "system" : user.username(), 60))
            .param("actorRole", truncate(user == null ? "Installation" : user.rolesHeldHere(), 120))
            .param("branchId", branch == null ? null : branch.id(), Types.OTHER)
            .param("branchName", branch == null ? null : truncate(branch.name(), 120))
            .param("clientAddress", request == null ? null : clientAddress(request))
            .param("userAgent", request == null ? null : truncate(request.getHeader("User-Agent"), 300))
            .param("reason", truncate(reason, 400))
            .update();
    }

    /**
     * Log a sign-in, a failed sign-in or a sign-out.
     *
     * <p>A failed attempt happens before anyone is signed in, so the actor
     * is named here rather than read from the session: the account tried,
     * when there is one, and always the username typed. These rows sit
     * under their own entity name, {@code sign_in}, so a user's change
     * history is not buried under their sign-ins.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSignIn(UUID userId, String username, String fullName,
                             AuditAction action, String reason) {
        try {
            var request = currentRequest();
            String typed = truncate(username, 60);

            jdbc.sql(INSERT)
                .param("entityName", "sign_in")
                .param("entityId", userId, Types.OTHER)
                .param("entityLabel", "Sign-in · " + typed)
                .param("documentId", null, Types.OTHER)
                .param("action", action.name())
                .param("beforeState", null)
                .param("afterState", null)
                .param("actorUserId", userId, Types.OTHER)
                .param("actorName", fullName == null ? "Unknown account" : truncate(fullName, 160))
                .param("actorUsername", typed)
                .param("actorRole", null)
                .param("branchId", null, Types.OTHER)
                .param("branchName", null)
                .param("clientAddress", request == null ? null : clientAddress(request))
                .param("userAgent", request == null ? null : truncate(request.getHeader("User-Agent"), 300))
                .param("reason", truncate(reason, 400))
                .update();

        } catch (Exception e) {
            log.error("Audit write failed for sign-in event {} by {}", action, username, e);
        }
    }

    /** Convenience for a document-scoped entry. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordForDocument(UUID documentId, String serialNo, AuditAction action,
                                  AuditSnapshot snapshot, BranchView branch, String reason) {
        record("document", documentId, "Document · " + serialNo, action, snapshot, branch, reason);
    }

    /** The history of one record, newest first, as the UI renders it. */
    @Transactional(readOnly = true)
    public List<AuditEntry> historyOf(String entityName, UUID entityId, int limit) {
        return jdbc.sql(BY_ENTITY)
                .param("entityName", entityName)
                .param("entityId", entityId, Types.OTHER)
                .param("limit", limit)
                .query((rs, n) -> {
                    Map<String, Object> before = read(rs.getString("before_state"));
                    Map<String, Object> after  = read(rs.getString("after_state"));
                    return new AuditEntry(
                            rs.getLong("id"),
                            rs.getString("entity_name"),
                            rs.getObject("entity_id", UUID.class),
                            rs.getString("entity_label"),
                            rs.getObject("document_id", UUID.class),
                            rs.getString("action"),
                            diff(before, after),
                            rs.getString("actor_name"),
                            rs.getString("actor_username"),
                            rs.getString("actor_role"),
                            rs.getString("branch_name"),
                            rs.getTimestamp("occurred_at").toLocalDateTime(),
                            rs.getString("client_address"),
                            rs.getString("reason"));
                })
                .list();
    }

    /**
     * The diff, computed at display time from the two snapshots.
     *
     * <p>Only fields that actually changed appear. A creation has no before
     * state, so every field of the after state is listed as new.
     */
    static List<AuditEntry.FieldChange> diff(Map<String, Object> before, Map<String, Object> after) {
        List<AuditEntry.FieldChange> changes = new ArrayList<>();
        if (after == null) return changes;

        var fields = new LinkedHashSet<String>();
        if (before != null) fields.addAll(before.keySet());
        fields.addAll(after.keySet());

        for (String field : fields) {
            Object oldValue = before == null ? null : before.get(field);
            Object newValue = after.get(field);
            if (java.util.Objects.equals(oldValue, newValue)) continue;   // unchanged: not listed
            changes.add(new AuditEntry.FieldChange(
                    field,
                    oldValue == null ? null : oldValue.toString(),
                    newValue == null ? null : newValue.toString()));
        }
        return changes;
    }

    // ---- helpers ---------------------------------------------------------

    private String write(Map<String, Object> map) {
        try {
            return json.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> read(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private AppUserDetails currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails u)) return null;
        return u;
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim(), 60);
        }
        return truncate(request.getRemoteAddr(), 60);
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Never used for a timestamp comparison; present for readability in tests. */
    static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
