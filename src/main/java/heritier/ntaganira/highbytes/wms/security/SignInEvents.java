package heritier.ntaganira.highbytes.wms.security;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.security
 * - File       : SignInEvents.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Records sign-ins and sign-outs, counts failures and locks an account after too many
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureDisabledEvent;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * Sign-in accounting (FR-SEC-05).
 *
 * <p>Every attempt is audited, successful or not. After
 * {@code highbytes.security.max-failed-logins} wrong passwords in a row the
 * account locks for {@code lockout-minutes}; a locked account is refused
 * before its password is even checked, so guessing cannot continue behind
 * the lock. The count stays at the limit while the lock lasts, so the
 * account's page shows why it locked, and starts again once the lock has
 * run out. A successful sign-in clears it.
 *
 * <p>A wrong current password typed into the password change counts too:
 * otherwise anyone at an unattended screen could guess at it without limit.
 */
@Component
public class SignInEvents {

    /**
     * One more failure for one account. Each value is computed from the row
     * as it stands, in one statement, so two failures at the same moment
     * both count.
     */
    private static final String COUNT_FAILURE = """
            UPDATE app_user
               SET failed_login_count = CASE WHEN locked_until IS NULL  THEN failed_login_count + 1
                                             WHEN locked_until > now()  THEN failed_login_count
                                             ELSE 1 END,
                   locked_until = CASE WHEN locked_until > now() THEN locked_until
                                       WHEN (CASE WHEN locked_until IS NULL THEN failed_login_count + 1 ELSE 1 END) >= :max
                                            THEN now() + make_interval(mins => :minutes)
                                  END
             WHERE %s
         RETURNING id, username, full_name, failed_login_count,
                   COALESCE(locked_until > now(), FALSE) AS locked
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final int maxFailures;
    private final int lockoutMinutes;

    public SignInEvents(JdbcClient jdbc, AuditService audit,
                        @Value("${highbytes.security.max-failed-logins:5}") int maxFailures,
                        @Value("${highbytes.security.lockout-minutes:15}") int lockoutMinutes) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.maxFailures = maxFailures;
        this.lockoutMinutes = lockoutMinutes;
    }

    @EventListener
    public void signedIn(InteractiveAuthenticationSuccessEvent event) {
        if (!(event.getAuthentication().getPrincipal() instanceof AppUserDetails user)) return;
        jdbc.sql("""
                UPDATE app_user
                   SET failed_login_count = 0, locked_until = NULL, last_login_at = now()
                 WHERE id = :id
                """)
                .param("id", user.id(), Types.OTHER)
                .update();
        audit.recordSignIn(user.id(), user.username(), user.fullName(), AuditAction.LOGIN, null);
    }

    /** A wrong password, or a username that does not exist: Spring reports both alike. */
    @EventListener
    public void wrongPassword(AuthenticationFailureBadCredentialsEvent event) {
        String typed = typedUsername(event);
        var outcome = jdbc.sql(COUNT_FAILURE.formatted("lower(username) = lower(:key)"))
                .param("max", maxFailures)
                .param("minutes", lockoutMinutes)
                .param("key", typed)
                .query(SignInEvents::outcome)
                .optional();

        if (outcome.isEmpty()) {
            audit.recordSignIn(null, typed, null, AuditAction.LOGIN_FAILED, "Unknown username");
            return;
        }
        var o = outcome.get();
        String reason = o.locked()
                ? "Wrong password. Account locked for " + lockoutMinutes + " minutes after "
                  + maxFailures + " failures in a row."
                : "Wrong password (" + o.failures() + " of " + maxFailures + " before the account locks)";
        audit.recordSignIn(o.id(), o.username(), o.fullName(), AuditAction.LOGIN_FAILED, reason);
    }

    /**
     * A wrong current password on the password change. Counts like a wrong
     * password at sign-in; at the limit the account locks and every open
     * session of it ends, this one included, because whoever is at the
     * screen does not know the password. Returns true when it locked.
     * Runs in the caller's transaction.
     */
    public boolean wrongCurrentPassword(UUID userId) {
        var o = jdbc.sql(COUNT_FAILURE.formatted("id = :key"))
                .param("max", maxFailures)
                .param("minutes", lockoutMinutes)
                .param("key", userId, Types.OTHER)
                .query(SignInEvents::outcome)
                .single();

        if (o.locked()) {
            jdbc.sql("UPDATE app_user SET session_epoch = session_epoch + 1 WHERE id = :id")
                    .param("id", userId, Types.OTHER)
                    .update();
        }
        String reason = o.locked()
                ? "Wrong current password when changing it. Account locked for " + lockoutMinutes
                  + " minutes after " + maxFailures + " failures in a row, and its sessions ended."
                : "Wrong current password when changing it (" + o.failures() + " of " + maxFailures
                  + " before the account locks)";
        audit.recordSignIn(o.id(), o.username(), o.fullName(), AuditAction.LOGIN_FAILED, reason);
        return o.locked();
    }

    @EventListener
    public void locked(AuthenticationFailureLockedEvent event) {
        refused(event, "Account locked after repeated failures");
    }

    @EventListener
    public void deactivated(AuthenticationFailureDisabledEvent event) {
        refused(event, "Account deactivated");
    }

    @EventListener
    public void signedOut(LogoutSuccessEvent event) {
        if (!(event.getAuthentication().getPrincipal() instanceof AppUserDetails user)) return;
        audit.recordSignIn(user.id(), user.username(), user.fullName(), AuditAction.LOGOUT, null);
    }

    private void refused(AbstractAuthenticationFailureEvent event, String reason) {
        String typed = typedUsername(event);
        var account = jdbc.sql("SELECT id, username, full_name FROM app_user WHERE lower(username) = lower(:username)")
                .param("username", typed)
                .query((rs, n) -> new Outcome(
                        rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("full_name"), 0, false))
                .optional();
        audit.recordSignIn(account.map(Outcome::id).orElse(null),
                account.map(Outcome::username).orElse(typed),
                account.map(Outcome::fullName).orElse(null),
                AuditAction.LOGIN_FAILED, reason);
    }

    private static Outcome outcome(ResultSet rs, int rowNum) throws SQLException {
        return new Outcome(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getInt("failed_login_count"),
                rs.getBoolean("locked"));
    }

    private static String typedUsername(AbstractAuthenticationFailureEvent event) {
        Object principal = event.getAuthentication().getPrincipal();
        return principal == null ? "" : principal.toString().trim();
    }

    private record Outcome(UUID id, String username, String fullName, int failures, boolean locked) {}
}
