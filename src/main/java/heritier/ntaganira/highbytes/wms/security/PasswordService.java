package heritier.ntaganira.highbytes.wms.security;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.security
 * - File       : PasswordService.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Changes the signed-in user's own password, ending their other sessions
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A user changing their own password.
 *
 * <p>The current password is asked for even when a temporary one was just
 * issued: a session left open on a shared counter PC must not be enough to
 * take the account over. Wrong guesses at it count towards the lock-out
 * like wrong passwords at sign-in, and the lock ends every session. A
 * change ends every other session of the account; the caller refreshes the
 * one making the change.
 */
@Service
public class PasswordService {

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final SignInEvents signIns;

    public PasswordService(JdbcClient jdbc, PasswordEncoder encoder, AuditService audit, SignInEvents signIns) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.signIns = signIns;
    }

    /** Changes the password, or says what is wrong instead. */
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public Outcome changeOwn(String current, String chosen, String confirmation, BranchView branch) {
        UUID me = CurrentUser.id();
        if (me == null) throw new AccessDeniedException("Not signed in");

        // No row lock here: the audit write below references this account
        // from a second transaction, and a lock held by this one would make
        // it wait for itself.
        var account = jdbc.sql("""
                SELECT username, password_hash, must_change_password FROM app_user WHERE id = :id
                """)
                .param("id", me, Types.OTHER)
                .query((rs, n) -> new Account(
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getBoolean("must_change_password")))
                .single();

        if (current == null || account.hash() == null || !encoder.matches(current, account.hash())) {
            return signIns.wrongCurrentPassword(me)
                    ? Outcome.LOCKED_OUT
                    : Outcome.refused(List.of("Your current password is not correct."));
        }

        List<String> problems = new ArrayList<>(PasswordRules.problems(chosen, account.username()));
        if (chosen != null && !chosen.equals(confirmation)) {
            problems.add("The new password and its confirmation are not the same.");
        }
        if (chosen != null && encoder.matches(chosen, account.hash())) {
            problems.add("Choose a password different from the current one.");
        }
        if (!problems.isEmpty()) return Outcome.refused(problems);

        jdbc.sql("""
                UPDATE app_user
                   SET password_hash = :hash, password_changed_at = now(), must_change_password = FALSE,
                       session_epoch = session_epoch + 1, updated_at = now()
                 WHERE id = :id
                """)
                .param("id", me, Types.OTHER)
                .param("hash", encoder.encode(chosen))
                .update();

        audit.recordAccessChange("app_user", me, "User account · " + account.username(), AuditAction.UPDATE,
                AuditSnapshot.of().field("Password",
                        account.mustChangePassword() ? "Temporary, issued by an administrator" : "Chosen by the user",
                        "Changed by the account holder"),
                branch, "Password changed by the account holder. Other open sessions end on their next page.");
        return Outcome.CHANGED;
    }

    /**
     * How a change went: done, refused for the reasons listed, or refused
     * because a wrong current password locked the account.
     */
    public record Outcome(List<String> problems, boolean lockedOut) {

        static final Outcome CHANGED = new Outcome(List.of(), false);
        static final Outcome LOCKED_OUT = new Outcome(List.of(), true);

        static Outcome refused(List<String> problems) {
            return new Outcome(List.copyOf(problems), false);
        }

        public boolean changed() {
            return problems.isEmpty() && !lockedOut;
        }
    }

    private record Account(String username, String hash, boolean mustChangePassword) {}
}
