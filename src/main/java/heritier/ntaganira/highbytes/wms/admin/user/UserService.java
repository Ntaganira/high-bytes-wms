package heritier.ntaganira.highbytes.wms.admin.user;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.user
 * - File       : UserService.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : User accounts and their role assignments: create, amend, deactivate, grant, revoke, audited
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.common.audit.AuditSnapshot;
import heritier.ntaganira.highbytes.wms.security.AccessChangeRefusedException;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import heritier.ntaganira.highbytes.wms.security.PasswordRules;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * User accounts and what they may do.
 *
 * <p>The rules, in the order the Board would ask about them:
 * <ul>
 *   <li>Nobody changes their own account here — not their roles, not their
 *       state, not their password. An administrator who could grant
 *       themselves a right, use it and remove it again is the Board's
 *       finding in another form. The database refuses a self-grant too.</li>
 *   <li>A grant that breaks a segregation rule, or gives one person
 *       administration and a transactional right at the same time, is
 *       refused with the rule's own reason (V9 {@code access_conflict}).</li>
 *   <li>Assignments are history. A grant is revoked, never edited or
 *       deleted, and never backdated.</li>
 *   <li>Accounts are deactivated, never deleted: their signatures stay
 *       attributable.</li>
 * </ul>
 * Every change takes effect on the user's next request, through the
 * security stamp V9 maintains, and is audited under the user's account.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private static final String LIST = """
            SELECT u.id, u.username, u.full_name, u.email, b.name AS home_branch_name,
                   u.is_active, u.locked_until, u.must_change_password, u.last_login_at,
                   (SELECT string_agg(r.name || COALESCE(' · ' || rb.code, ''), ', '
                                      ORDER BY r.name, rb.code)
                      FROM live_user_role ur
                      JOIN role r     ON r.id = ur.role_id AND r.is_active
                 LEFT JOIN branch rb  ON rb.id = ur.branch_id
                     WHERE ur.user_id = u.id) AS roles
              FROM app_user u
         LEFT JOIN branch b ON b.id = u.home_branch_id
             WHERE (:query::text IS NULL
                    OR u.username ILIKE :like OR u.full_name ILIKE :like OR u.email ILIKE :like)
               AND (:roleId::uuid IS NULL
                    OR EXISTS (SELECT 1 FROM live_user_role ur
                                WHERE ur.user_id = u.id AND ur.role_id = :roleId::uuid))
               AND CASE :status::text
                     WHEN 'LOCKED'   THEN u.is_active AND u.locked_until > now()
                     WHEN 'INACTIVE' THEN NOT u.is_active
                     WHEN 'ALL'      THEN TRUE
                     ELSE u.is_active
                   END
             ORDER BY u.is_active DESC, lower(u.full_name)
             LIMIT 300
            """;

    private static final String DETAIL = """
            SELECT u.id, u.username, u.full_name, u.email, u.phone,
                   u.home_branch_id, b.name AS home_branch_name,
                   u.is_active, u.must_change_password, u.failed_login_count, u.locked_until,
                   u.last_login_at, u.last_leave_start, u.last_leave_end,
                   u.deactivated_at, u.deactivated_reason, u.created_at, u.password_changed_at
              FROM app_user u
         LEFT JOIN branch b ON b.id = u.home_branch_id
             WHERE u.id = :id
            """;

    private static final String ASSIGNMENTS = """
            SELECT ur.id, ur.role_id, r.code AS role_code, r.name AS role_name, r.is_active AS role_active,
                   ur.branch_id, b.name AS branch_name, ur.valid_from, ur.valid_to,
                   ur.is_delegation, df.full_name AS delegated_for_name,
                   ab.full_name AS assigned_by_name, ur.created_at,
                   ur.revoked_at, rb.full_name AS revoked_by_name, ur.revoke_reason
              FROM user_role ur
              JOIN role r          ON r.id = ur.role_id
         LEFT JOIN branch b        ON b.id = ur.branch_id
         LEFT JOIN app_user df     ON df.id = ur.delegated_for
         LEFT JOIN app_user ab     ON ab.id = ur.assigned_by
         LEFT JOIN app_user rb     ON rb.id = ur.revoked_by
             WHERE ur.user_id = :userId
             ORDER BY (ur.revoked_at IS NULL AND (ur.valid_to IS NULL OR ur.valid_to >= CURRENT_DATE)) DESC,
                      ur.valid_from DESC, r.name
            """;

    private static final String HELD_PERMISSIONS = """
            SELECT p.module, p.code, p.description, p.duty,
                   bool_or(ur.branch_id IS NULL)         AS everywhere,
                   string_agg(DISTINCT b.code, ', ')     AS branch_codes
              FROM live_user_role ur
              JOIN role r             ON r.id = ur.role_id AND r.is_active
              JOIN role_permission rp ON rp.role_id = r.id
              JOIN permission p       ON p.id = rp.permission_id
         LEFT JOIN branch b           ON b.id = ur.branch_id
             WHERE ur.user_id = :userId
             GROUP BY p.module, p.code, p.description, p.duty
             ORDER BY p.module, p.code
            """;

    /** Warn-only segregation rules the user's assignments meet, naming the new assignment. */
    private static final String WARNINGS = """
            SELECT format('%s and %s: %s (%s)', ra.name, rb.name, s.rationale, s.source_ref)
              FROM user_role a
              JOIN user_role b ON b.user_id = a.user_id AND b.id <> a.id
              JOIN sod_rule s  ON s.is_active AND s.enforcement = 'WARN'
                              AND s.role_a_id = a.role_id AND s.role_b_id = b.role_id
              JOIN role ra     ON ra.id = a.role_id
              JOIN role rb     ON rb.id = b.role_id
             WHERE a.user_id = :userId
               AND (a.id = :assignmentId OR b.id = :assignmentId)
               AND a.revoked_at IS NULL AND b.revoked_at IS NULL
               AND daterange(a.valid_from, a.valid_to, '[]') && daterange(b.valid_from, b.valid_to, '[]')
            """;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm");

    /** A reason is kept whole, with the change it explains: V9 stores 240 characters. */
    static final int REASON_MAX = 240;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final BranchService branches;
    private final PasswordEncoder passwords;

    public UserService(JdbcClient jdbc, AuditService audit, BranchService branches,
                       PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.branches = branches;
        this.passwords = passwords;
    }

    // ---- reads -----------------------------------------------------------

    public List<UserRow> search(String query, UUID roleId, AccountFilter filter) {
        String q = blankToNull(query);
        return jdbc.sql(LIST)
                .param("query", q)
                .param("like", q == null ? null : "%" + q + "%")
                .param("roleId", roleId, Types.OTHER)
                .param("status", (filter == null ? AccountFilter.CURRENT : filter).name())
                .query((rs, n) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("home_branch_name"),
                        rs.getBoolean("is_active"),
                        time(rs, "locked_until"),
                        rs.getBoolean("must_change_password"),
                        time(rs, "last_login_at"),
                        rs.getString("roles")))
                .list();
    }

    public Optional<UserDetail> detail(UUID id) {
        return jdbc.sql(DETAIL)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> new UserDetail(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getObject("home_branch_id", UUID.class),
                        rs.getString("home_branch_name"),
                        rs.getBoolean("is_active"),
                        rs.getBoolean("must_change_password"),
                        rs.getInt("failed_login_count"),
                        time(rs, "locked_until"),
                        time(rs, "last_login_at"),
                        rs.getObject("last_leave_start", LocalDate.class),
                        rs.getObject("last_leave_end", LocalDate.class),
                        time(rs, "deactivated_at"),
                        rs.getString("deactivated_reason"),
                        time(rs, "created_at"),
                        time(rs, "password_changed_at")))
                .optional();
    }

    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :id)")
                .param("id", id, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    /** The account as its form holds it; a 404 when no user has that id. */
    public UserForm formFor(UUID id) {
        return jdbc.sql("""
                SELECT id, username, full_name, email, phone, home_branch_id,
                       last_leave_start, last_leave_end
                  FROM app_user WHERE id = :id
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> {
                    var f = new UserForm();
                    f.setId(rs.getObject("id", UUID.class));
                    f.setUsername(rs.getString("username"));
                    f.setFullName(rs.getString("full_name"));
                    f.setEmail(rs.getString("email"));
                    f.setPhone(rs.getString("phone"));
                    f.setHomeBranchId(rs.getObject("home_branch_id", UUID.class));
                    f.setLastLeaveStart(rs.getObject("last_leave_start", LocalDate.class));
                    f.setLastLeaveEnd(rs.getObject("last_leave_end", LocalDate.class));
                    return f;
                })
                .optional()
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    /** Every assignment the user has ever held, live ones first. */
    public List<AssignmentRow> assignments(UUID userId) {
        return jdbc.sql(ASSIGNMENTS)
                .param("userId", userId, Types.OTHER)
                .query((rs, n) -> new AssignmentRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("role_id", UUID.class),
                        rs.getString("role_code"),
                        rs.getString("role_name"),
                        rs.getBoolean("role_active"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("branch_name"),
                        rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class),
                        rs.getBoolean("is_delegation"),
                        rs.getString("delegated_for_name"),
                        rs.getString("assigned_by_name"),
                        time(rs, "created_at"),
                        time(rs, "revoked_at"),
                        rs.getString("revoked_by_name"),
                        rs.getString("revoke_reason")))
                .list();
    }

    /** What the user may do today, and where, across all their roles. */
    public List<HeldPermission> heldPermissions(UUID userId) {
        return jdbc.sql(HELD_PERMISSIONS)
                .param("userId", userId, Types.OTHER)
                .query((rs, n) -> new HeldPermission(
                        rs.getString("module"),
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("duty"),
                        rs.getBoolean("everywhere"),
                        rs.getString("branch_codes")))
                .list();
    }

    /** The roles a grant can name: active ones only. */
    public List<RoleOption> grantableRoles() {
        return jdbc.sql("SELECT id, code, name, structure FROM role WHERE is_active ORDER BY name")
                .query((rs, n) -> new RoleOption(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("structure")))
                .list();
    }

    /** Active colleagues a grant can cover for, excluding the user themselves. */
    public List<PersonOption> colleaguesOf(UUID userId) {
        return jdbc.sql("""
                SELECT id, full_name, username FROM app_user
                 WHERE is_active AND id <> :userId
                 ORDER BY lower(full_name)
                """)
                .param("userId", userId, Types.OTHER)
                .query((rs, n) -> new PersonOption(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("username")))
                .list();
    }

    // ---- the account -----------------------------------------------------

    /** Creates the account with a temporary password, returned once and never stored in clear. */
    @Transactional
    @PreAuthorize("hasAuthority('admin.users')")
    public Issued create(UserForm form, BranchView branch) {
        requireHomeBranch(form);
        UUID id = UUID.randomUUID();
        String temporary = PasswordRules.temporary();
        try {
            jdbc.sql("""
                    INSERT INTO app_user (id, username, full_name, email, phone, home_branch_id,
                                          password_hash, password_changed_at,
                                          is_active, must_change_password,
                                          last_leave_start, last_leave_end)
                    VALUES (:id, :username, :fullName, :email, :phone, :homeBranchId,
                            :hash, now(), TRUE, TRUE, :leaveStart, :leaveEnd)
                    """)
                    .param("id", id, Types.OTHER)
                    .param("username", form.getUsername())
                    .param("fullName", form.getFullName())
                    .param("email", form.getEmail())
                    .param("phone", form.getPhone())
                    .param("homeBranchId", form.getHomeBranchId(), Types.OTHER)
                    .param("hash", passwords.encode(temporary))
                    .param("leaveStart", form.getLastLeaveStart())
                    .param("leaveEnd", form.getLastLeaveEnd())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new UsernameTakenException(form.getUsername());
        }

        var snapshot = snapshotOf(null, form)
                .value("Password", "Temporary, to be changed at first sign-in");
        audit.recordAccessChange("app_user", id, label(form.getUsername()), AuditAction.CREATE,
                snapshot, branch, null);
        return new Issued(id, temporary);
    }

    @Transactional
    @PreAuthorize("hasAuthority('admin.users')")
    public void update(UUID id, UserForm form, BranchView branch) {
        refuseOwnAccount(id, "Change own account", "You cannot change your own account here.", branch);
        UserForm before = formFor(id);
        requireHomeBranch(form);

        jdbc.sql("""
                UPDATE app_user
                   SET full_name = :fullName, email = :email, phone = :phone,
                       home_branch_id = :homeBranchId,
                       last_leave_start = :leaveStart, last_leave_end = :leaveEnd,
                       updated_at = now()
                 WHERE id = :id
                """)
                .param("id", id, Types.OTHER)
                .param("fullName", form.getFullName())
                .param("email", form.getEmail())
                .param("phone", form.getPhone())
                .param("homeBranchId", form.getHomeBranchId(), Types.OTHER)
                .param("leaveStart", form.getLastLeaveStart())
                .param("leaveEnd", form.getLastLeaveEnd())
                .update();

        var snapshot = snapshotOf(before, form);
        if (!snapshot.unchanged()) {
            audit.recordAccessChange("app_user", id, label(before.getUsername()), AuditAction.UPDATE,
                    snapshot, branch, null);
        }
    }

    /**
     * Deactivation, never deletion. The account's open sessions end on
     * their next request; its roles stay on record and apply again only
     * if it is reactivated.
     */
    @Transactional
    @PreAuthorize("hasAuthority('admin.users')")
    public void deactivate(UUID id, String reason, BranchView branch) {
        refuseOwnAccount(id, "Deactivate own account", "You cannot deactivate your own account.", branch);
        var user = detail(id).orElseThrow(() -> new UserNotFoundException(id));
        if (!user.active()) return;
        String why = reasonFor(reason, "Give a reason for deactivating the account. It is kept with the account.");

        jdbc.sql("""
                UPDATE app_user
                   SET is_active = FALSE, deactivated_at = now(), deactivated_reason = :reason,
                       session_epoch = session_epoch + 1, updated_at = now()
                 WHERE id = :id
                """)
                .param("id", id, Types.OTHER)
                .param("reason", why)
                .update();

        audit.recordAccessChange("app_user", id, label(user.username()), AuditAction.DEACTIVATE,
                AuditSnapshot.of().field("Active", true, false), branch, why);
    }

    @Transactional
    @PreAuthorize("hasAuthority('admin.users')")
    public void reactivate(UUID id, BranchView branch) {
        refuseOwnAccount(id, "Reactivate own account", "You cannot reactivate your own account.", branch);
        var user = detail(id).orElseThrow(() -> new UserNotFoundException(id));
        if (user.active()) return;

        jdbc.sql("""
                UPDATE app_user
                   SET is_active = TRUE, deactivated_at = NULL, deactivated_reason = NULL,
                       failed_login_count = 0, locked_until = NULL, updated_at = now()
                 WHERE id = :id
                """)
                .param("id", id, Types.OTHER)
                .update();

        audit.recordAccessChange("app_user", id, label(user.username()), AuditAction.UPDATE,
                AuditSnapshot.of().field("Active", false, true), branch, null);
    }

    /**
     * Issues a new temporary password. Every open session of the account
     * ends, and the user must choose their own password at next sign-in.
     */
    @Transactional
    @PreAuthorize("hasAuthority('admin.users')")
    public String resetPassword(UUID id, BranchView branch) {
        refuseOwnAccount(id, "Reset own password", "Change your own password under My profile, not here.", branch);
        var user = detail(id).orElseThrow(() -> new UserNotFoundException(id));
        if (!user.active()) {
            throw new AccessChangeRefusedException("Reactivate the account before issuing it a password.");
        }

        String temporary = PasswordRules.temporary();
        jdbc.sql("""
                UPDATE app_user
                   SET password_hash = :hash, password_changed_at = now(), must_change_password = TRUE,
                       failed_login_count = 0, locked_until = NULL,
                       session_epoch = session_epoch + 1, updated_at = now()
                 WHERE id = :id
                """)
                .param("id", id, Types.OTHER)
                .param("hash", passwords.encode(temporary))
                .update();

        audit.recordAccessChange("app_user", id, label(user.username()), AuditAction.UPDATE,
                AuditSnapshot.of().field("Password",
                        user.mustChangePassword() ? "Temporary, not yet changed" : "Chosen by the user",
                        "Temporary, issued " + DAY.format(LocalDate.now()) + ", to be changed at next sign-in"),
                branch, "Password reset by an administrator");
        return temporary;
    }

    /** Lifts a lock-out before it runs out on its own. */
    @Transactional
    @PreAuthorize("hasAuthority('admin.users')")
    public void unlock(UUID id, BranchView branch) {
        refuseOwnAccount(id, "Unlock own account", "You cannot unlock your own account.", branch);
        var user = detail(id).orElseThrow(() -> new UserNotFoundException(id));
        if (!user.locked()) return;

        jdbc.sql("UPDATE app_user SET locked_until = NULL, failed_login_count = 0 WHERE id = :id")
                .param("id", id, Types.OTHER)
                .update();

        audit.recordAccessChange("app_user", id, label(user.username()), AuditAction.UPDATE,
                AuditSnapshot.of().field("Locked until", MINUTE.format(user.lockedUntil()), null),
                branch, "Unlocked by an administrator");
    }

    // ---- roles -----------------------------------------------------------

    /**
     * Grants a role. Refused, with the rule's own reason, when it would break
     * a segregation rule or give one person administration and transactions
     * at the same time. Returns any warn-only segregation rules it meets.
     */
    @Transactional
    @PreAuthorize("hasAuthority('admin.users')")
    public List<String> grant(UUID userId, GrantForm form, BranchView branch) {
        refuseOwnAccount(userId, "Grant own account a role", "You cannot grant yourself a role.", branch);
        var user = detail(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (!user.active()) {
            throw new AccessChangeRefusedException("Reactivate the account before granting it a role.");
        }

        var role = grantableRoles().stream()
                .filter(r -> r.id().equals(form.getRoleId()))
                .findFirst()
                .orElseThrow(() -> new AccessChangeRefusedException("That role does not exist or is inactive."));

        String scope = "All branches";
        if (form.getBranchId() != null) {
            scope = branches.findById(form.getBranchId())
                    .map(BranchView::name)
                    .orElseThrow(() -> new AccessChangeRefusedException("That branch does not exist or is inactive."));
        }

        LocalDate today = LocalDate.now();
        if (form.getValidFrom().isBefore(today)) {
            throw new AccessChangeRefusedException(
                    "A role cannot be granted from a past date: that would claim access nobody had at the time.");
        }
        if (form.getValidTo() != null && form.getValidTo().isBefore(form.getValidFrom())) {
            throw new AccessChangeRefusedException("The end date is before the start date.");
        }

        String coveringFor = null;
        if (form.isDelegation()) {
            coveringFor = delegationHolder(userId, role, form);
        }
        String terms = AssignmentRow.termsOf(form.getValidFrom(), form.getValidTo(), coveringFor);
        String grantLabel = "Role · " + role.name() + " · " + scope;

        UUID assignmentId = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO user_role (id, user_id, role_id, branch_id, valid_from, valid_to,
                                           is_delegation, delegated_for, assigned_by)
                    VALUES (:id, :userId, :roleId, :branchId, :from, :to,
                            :delegation, :delegatedFor, :assignedBy)
                    """)
                    .param("id", assignmentId, Types.OTHER)
                    .param("userId", userId, Types.OTHER)
                    .param("roleId", role.id(), Types.OTHER)
                    .param("branchId", form.getBranchId(), Types.OTHER)
                    .param("from", form.getValidFrom())
                    .param("to", form.getValidTo())
                    .param("delegation", form.isDelegation())
                    .param("delegatedFor", form.isDelegation() ? form.getDelegatedFor() : null, Types.OTHER)
                    .param("assignedBy", CurrentUser.id(), Types.OTHER)
                    .update();
        } catch (DataIntegrityViolationException e) {
            if (sqlState(e).equals("23P01")) {
                throw new AccessChangeRefusedException(user.fullName() + " already holds " + role.name()
                        + " at " + scope + " for part of that period.");
            }
            throw e;
        }

        // The same check the database runs at commit, asked first so the
        // refusal carries its reason. The grant is undone; the attempt stays
        // on record.
        String conflict = jdbc.sql("SELECT access_conflict(:userId)")
                .param("userId", userId, Types.OTHER)
                .query(String.class)
                .optional()
                .orElse(null);
        if (conflict != null) {
            throw refused(userId, user.username(), "Grant " + grantLabel + " · " + terms, conflict, branch);
        }

        List<String> warnings = jdbc.sql(WARNINGS)
                .param("userId", userId, Types.OTHER)
                .param("assignmentId", assignmentId, Types.OTHER)
                .query(String.class)
                .list();

        String reason = form.getReason();
        if (!warnings.isEmpty()) {
            reason = "Granted despite: " + String.join("; ", warnings) + (reason == null ? "" : ". " + reason);
        }
        audit.recordAccessChange("app_user", userId, label(user.username()), AuditAction.UPDATE,
                AuditSnapshot.of().field(grantLabel, null, terms), branch, reason);
        return warnings;
    }

    /** Revokes an assignment that is in force or yet to start. The record stays. */
    @Transactional
    @PreAuthorize("hasAuthority('admin.users')")
    public void revoke(UUID userId, UUID assignmentId, String reason, BranchView branch) {
        refuseOwnAccount(userId, "Revoke own role", "You cannot revoke your own roles.", branch);
        var user = detail(userId).orElseThrow(() -> new UserNotFoundException(userId));
        var assignment = assignments(userId).stream()
                .filter(a -> a.id().equals(assignmentId))
                .findFirst()
                .orElseThrow(() -> new AssignmentNotFoundException(userId, assignmentId));

        if (!assignment.revocable()) {
            throw new AccessChangeRefusedException("That assignment has already ended or been revoked.");
        }
        String why = reasonFor(reason, "Give a reason for revoking the role. It is kept with the assignment.");

        int revoked = jdbc.sql("""
                UPDATE user_role
                   SET revoked_at = now(), revoked_by = :actor, revoke_reason = :reason
                 WHERE id = :id AND user_id = :userId AND revoked_at IS NULL
                """)
                .param("id", assignmentId, Types.OTHER)
                .param("userId", userId, Types.OTHER)
                .param("actor", CurrentUser.id(), Types.OTHER)
                .param("reason", why)
                .update();
        // Another administrator got there first: theirs is the revocation on record.
        if (revoked == 0) {
            throw new AccessChangeRefusedException("That assignment has already been revoked.");
        }

        audit.recordAccessChange("app_user", userId, label(user.username()), AuditAction.UPDATE,
                AuditSnapshot.of().field("Role · " + assignment.roleName() + " · " + assignment.scope(),
                        assignment.terms(), "Revoked"),
                branch, why);
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Leave cover names the person covered, who must hold the role during
     * the cover, and it must end. Returns their name for the record.
     */
    private String delegationHolder(UUID userId, RoleOption role, GrantForm form) {
        if (form.getValidTo() == null) {
            throw new AccessChangeRefusedException("Cover for someone's leave must have an end date.");
        }
        if (form.getDelegatedFor() == null || form.getDelegatedFor().equals(userId)) {
            throw new AccessChangeRefusedException("Choose the colleague whose leave this covers.");
        }
        var holder = detail(form.getDelegatedFor())
                .orElseThrow(() -> new AccessChangeRefusedException("Choose the colleague whose leave this covers."));

        boolean holdsRole = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM user_role
                     WHERE user_id = :holder AND role_id = :roleId AND revoked_at IS NULL
                       AND daterange(valid_from, valid_to, '[]') && daterange(:from, :to, '[]'))
                """)
                .param("holder", holder.id(), Types.OTHER)
                .param("roleId", role.id(), Types.OTHER)
                .param("from", form.getValidFrom())
                .param("to", form.getValidTo())
                .query(Boolean.class)
                .single();
        if (!holdsRole) {
            throw new AccessChangeRefusedException(holder.fullName() + " does not hold " + role.name()
                    + " during that period, so there is no leave to cover.");
        }
        return holder.fullName();
    }

    /** Nobody administers their own access (FR-SEC-13). The attempt is recorded. */
    private void refuseOwnAccount(UUID userId, String attempt, String refusal, BranchView branch) {
        if (!userId.equals(CurrentUser.id())) return;
        String username = CurrentUser.get().map(AppUserDetails::username).orElse(null);
        throw refused(userId, username, attempt,
                refusal + " Another administrator must do it: nobody administers their own access.", branch);
    }

    /**
     * Records a refused access change and returns the exception that tells
     * the administrator why. The record is written once the refusal has
     * rolled back, in a transaction of its own: probing for what is refused
     * leaves a trail.
     */
    private AccessChangeRefusedException refused(UUID userId, String username, String attempt,
                                                 String why, BranchView branch) {
        audit.recordRefusal("app_user", userId, label(username),
                AuditSnapshot.of().value("Attempted", attempt), branch, "Refused: " + why);
        return new AccessChangeRefusedException(why);
    }

    /** A required reason, trimmed, and short enough to be kept whole. */
    private static String reasonFor(String reason, String whenMissing) {
        if (reason == null || reason.isBlank()) {
            throw new AccessChangeRefusedException(whenMissing);
        }
        String trimmed = reason.trim();
        if (trimmed.length() > REASON_MAX) {
            throw new AccessChangeRefusedException("Keep the reason to " + REASON_MAX
                    + " characters: it is kept whole, with the change it explains.");
        }
        return trimmed;
    }

    /** The home branch must be a branch in use, or the account has nowhere to work. */
    private void requireHomeBranch(UserForm form) {
        if (form.getHomeBranchId() == null || branches.findById(form.getHomeBranchId()).isEmpty()) {
            throw new UnknownBranchException();
        }
    }

    /**
     * The fields worth auditing, with the branch by name: the audit trail
     * records what the reviewer would have read at the time, not an id.
     */
    private AuditSnapshot snapshotOf(UserForm before, UserForm after) {
        var snap = AuditSnapshot.of();
        if (before == null) snap.value("Username", after.getUsername());
        return snap
                .field("Full name",   before == null ? null : before.getFullName(),   after.getFullName())
                .field("Email",       before == null ? null : before.getEmail(),      after.getEmail())
                .field("Phone",       before == null ? null : before.getPhone(),      after.getPhone())
                .field("Home branch", before == null ? null : branchName(before.getHomeBranchId()),
                                      branchName(after.getHomeBranchId()))
                .field("Last leave",  before == null ? null : leaveOf(before),        leaveOf(after));
    }

    private String branchName(UUID id) {
        return branches.findById(id).map(BranchView::name).orElse(null);
    }

    private static String leaveOf(UserForm form) {
        if (form.getLastLeaveStart() == null) return null;
        return DAY.format(form.getLastLeaveStart()) + " to " + DAY.format(form.getLastLeaveEnd());
    }

    private static String label(String username) {
        return "User account · " + username;
    }

    private static String sqlState(DataIntegrityViolationException e) {
        return e.getMostSpecificCause() instanceof SQLException sql && sql.getSQLState() != null
                ? sql.getSQLState() : "";
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    // ---- small views -------------------------------------------------------

    /** A new account's id and its one-time temporary password. */
    public record Issued(UUID id, String temporaryPassword) {}

    public record RoleOption(UUID id, String code, String name, String structure) {
        /** Roles from one structure only say so: a 2027 role granted today is dormant until then. */
        public String label() {
            return switch (structure) {
                case "POLICY_2026" -> name + " (2026 policy)";
                case "RESTRUCTURE_2027" -> name + " (2027 structure)";
                default -> name;
            };
        }
    }

    public record PersonOption(UUID id, String fullName, String username) {}

    /** A permission the user holds today, and at which branches. */
    public record HeldPermission(String module, String code, String description, String duty,
                                 boolean everywhere, String branchCodes) {
        public String scope() {
            return everywhere ? "All branches" : branchCodes;
        }
    }

    // ---- failures the user should see, not a stack trace -------------------

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(UUID id) {
            super("No user with id " + id);
        }
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class AssignmentNotFoundException extends RuntimeException {
        public AssignmentNotFoundException(UUID userId, UUID assignmentId) {
            super("No role assignment " + assignmentId + " on user " + userId);
        }
    }

    /** A home branch that does not exist or is not in use: a field error, not a failure. */
    public static class UnknownBranchException extends RuntimeException {
        public UnknownBranchException() {
            super("Choose a home branch from the list.");
        }
    }

    public static class UsernameTakenException extends RuntimeException {
        public UsernameTakenException(String username) {
            super("Username " + username + " is already in use, by a current or a deactivated account.");
        }
    }
}
