package heritier.ntaganira.highbytes.wms.security;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.security
 * - File       : AppUserDetailsService.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Loads a user with the permissions their live role assignments grant
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Loads a user with the permissions their live role assignments grant.
 *
 * <p>Only assignments valid today and not revoked count: a delegation
 * covering someone's mandatory leave expires on its own date rather than
 * waiting for an administrator to remember (FR-SEC-18).
 *
 * <p>An assignment scoped to a branch grants its permissions only there.
 * The user may work at the branches their assignments name, or at every
 * branch if one assignment has no branch. Someone with no role yet still
 * lands on their home branch, with nothing to do there.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private static final String USER_COLUMNS = """
            SELECT u.id, u.username, u.full_name, u.password_hash, u.home_branch_id,
                   u.is_active, u.must_change_password, u.locked_until,
                   u.security_stamp, u.session_epoch
              FROM app_user u
            """;

    private static final String GRANTS_SQL = """
            SELECT ur.role_id, r.name AS role_name, ur.branch_id, p.code
              FROM user_role ur
              JOIN role r             ON r.id = ur.role_id AND r.is_active
         LEFT JOIN role_permission rp ON rp.role_id = r.id
         LEFT JOIN permission p       ON p.id = rp.permission_id
             WHERE ur.user_id = :userId
               AND ur.revoked_at IS NULL
               AND ur.valid_from <= :today
               AND (ur.valid_to IS NULL OR ur.valid_to >= :today)
             ORDER BY r.name, ur.branch_id NULLS FIRST
            """;

    private final JdbcClient jdbc;
    private final BranchService branches;

    public AppUserDetailsService(JdbcClient jdbc, BranchService branches) {
        this.jdbc = jdbc;
        this.branches = branches;
    }

    /** At sign-in: the user lands on their home branch when they may work there. */
    @Override
    @Transactional(readOnly = true)
    public AppUserDetails loadUserByUsername(String username) {
        var account = jdbc.sql(USER_COLUMNS + " WHERE lower(u.username) = lower(:username)")
                .param("username", username)
                .query(this::account)
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("No such user"));
        return withAccess(account, null);
    }

    /**
     * The same user loaded again, for a session that must catch up with a
     * change, or that has switched branch. Empty when the account is gone.
     */
    @Transactional(readOnly = true)
    public Optional<AppUserDetails> reload(UUID userId, UUID requestedBranch) {
        return jdbc.sql(USER_COLUMNS + " WHERE u.id = :id")
                .param("id", userId, Types.OTHER)
                .query(this::account)
                .optional()
                .map(account -> withAccess(account, requestedBranch));
    }

    private AppUserDetails withAccess(Account account, UUID requestedBranch) {
        LocalDate today = LocalDate.now();
        List<AppUserDetails.Grant> grants = grantsOf(account.id(), today);

        List<UUID> accessible = accessibleBranches(grants, account.homeBranchId());
        UUID branch = requestedBranch != null && accessible.contains(requestedBranch) ? requestedBranch
                : account.homeBranchId() != null && accessible.contains(account.homeBranchId()) ? account.homeBranchId()
                : accessible.stream().findFirst().orElse(null);

        Set<String> permissions = new HashSet<>();
        Set<UUID> roleIds = new HashSet<>();
        String primaryRole = null;
        for (var grant : grants) {
            if (!grant.appliesAt(branch)) continue;
            permissions.addAll(grant.permissions());
            roleIds.add(grant.roleId());
            if (primaryRole == null) primaryRole = grant.roleName();
        }

        return new AppUserDetails(
                account.id(),
                account.username(),
                account.fullName(),
                account.passwordHash(),
                account.homeBranchId(),
                primaryRole == null ? "No role assigned" : primaryRole,
                account.active(),
                account.mustChangePassword(),
                account.lockedUntil(),
                Set.copyOf(permissions),
                Set.copyOf(roleIds),
                branch,
                List.copyOf(accessible),
                List.copyOf(grants),
                account.securityStamp(),
                account.sessionEpoch(),
                today);
    }

    /** Live assignments, one grant per role and scope, in role-name order. */
    private List<AppUserDetails.Grant> grantsOf(UUID userId, LocalDate today) {
        record Row(UUID roleId, String roleName, UUID branchId, String permission) {}
        record Scope(UUID roleId, UUID branchId) {}

        var rows = jdbc.sql(GRANTS_SQL)
                .param("userId", userId, Types.OTHER)
                .param("today", today)
                .query((rs, n) -> new Row(
                        rs.getObject("role_id", UUID.class),
                        rs.getString("role_name"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("code")))
                .list();

        Map<Scope, List<Row>> byAssignment = new LinkedHashMap<>();
        for (var row : rows) {
            byAssignment.computeIfAbsent(new Scope(row.roleId(), row.branchId()),
                    k -> new ArrayList<>()).add(row);
        }

        List<AppUserDetails.Grant> grants = new ArrayList<>();
        for (var assignment : byAssignment.values()) {
            var first = assignment.get(0);
            Set<String> codes = new HashSet<>();
            for (var row : assignment) {
                if (row.permission() != null) codes.add(row.permission());
            }
            grants.add(new AppUserDetails.Grant(first.roleId(), first.roleName(), first.branchId(),
                    Set.copyOf(codes)));
        }
        return grants;
    }

    /**
     * Where the user may work, main branch first. An assignment with no
     * branch opens every branch; otherwise only the branches named.
     */
    private List<UUID> accessibleBranches(List<AppUserDetails.Grant> grants, UUID homeBranchId) {
        List<BranchView> all = branches.findAll().stream()
                .sorted(Comparator.comparing((BranchView b) -> !"MAIN".equals(b.branchType())))
                .toList();

        boolean everywhere = grants.stream().anyMatch(g -> g.branchId() == null);
        Set<UUID> named = new HashSet<>();
        grants.stream().map(AppUserDetails.Grant::branchId).filter(Objects::nonNull).forEach(named::add);

        List<UUID> accessible = all.stream()
                .map(BranchView::id)
                .filter(id -> everywhere || named.contains(id))
                .toList();

        if (accessible.isEmpty()) {
            return branches.defaultFor(homeBranchId).map(b -> List.of(b.id())).orElse(List.of());
        }
        return accessible;
    }

    private Account account(ResultSet rs, int rowNum) throws SQLException {
        var locked = rs.getTimestamp("locked_until");
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                rs.getObject("home_branch_id", UUID.class),
                rs.getBoolean("is_active"),
                rs.getBoolean("must_change_password"),
                locked == null ? null : locked.toLocalDateTime(),
                rs.getObject("security_stamp", UUID.class),
                rs.getInt("session_epoch"));
    }

    private record Account(UUID id, String username, String fullName, String passwordHash,
                           UUID homeBranchId, boolean active, boolean mustChangePassword,
                           LocalDateTime lockedUntil, UUID securityStamp, int sessionEpoch) {}
}
