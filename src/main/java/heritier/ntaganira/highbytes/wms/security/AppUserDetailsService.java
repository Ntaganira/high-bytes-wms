package heritier.ntaganira.highbytes.wms.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Loads a user with the permissions their live role assignments grant.
 *
 * <p>Only assignments valid today count: a delegation covering someone's
 * mandatory leave expires on its own date rather than waiting for an
 * administrator to remember (FR-SEC-18).
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private static final String USER_SQL = """
            SELECT u.id, u.username, u.full_name, u.password_hash, u.home_branch_id,
                   u.is_active, u.must_change_password, u.locked_until
              FROM app_user u
             WHERE lower(u.username) = lower(:username)
            """;

    private static final String PERMISSIONS_SQL = """
            SELECT DISTINCT p.code
              FROM user_role ur
              JOIN role r             ON r.id = ur.role_id AND r.is_active
              JOIN role_permission rp ON rp.role_id = r.id
              JOIN permission p       ON p.id = rp.permission_id
             WHERE ur.user_id = :userId
               AND ur.valid_from <= :today
               AND (ur.valid_to IS NULL OR ur.valid_to >= :today)
            """;

    private static final String ROLES_SQL = """
            SELECT r.id, r.name
              FROM user_role ur
              JOIN role r ON r.id = ur.role_id AND r.is_active
             WHERE ur.user_id = :userId
               AND ur.valid_from <= :today
               AND (ur.valid_to IS NULL OR ur.valid_to >= :today)
             ORDER BY r.name
            """;

    private final JdbcClient jdbc;

    public AppUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public AppUserDetails loadUserByUsername(String username) {
        var row = jdbc.sql(USER_SQL)
                .param("username", username)
                .query((rs, n) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getString("password_hash"),
                        rs.getObject("home_branch_id", UUID.class),
                        rs.getBoolean("is_active"),
                        rs.getBoolean("must_change_password"),
                        rs.getTimestamp("locked_until") == null
                                ? null : rs.getTimestamp("locked_until").toLocalDateTime()
                })
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("No such user"));

        UUID userId = (UUID) row[0];
        LocalDate today = LocalDate.now();

        Set<String> permissions = new HashSet<>(
                jdbc.sql(PERMISSIONS_SQL)
                        .param("userId", userId)
                        .param("today", today)
                        .query(String.class)
                        .list());

        Set<UUID> roleIds = new HashSet<>();
        String primaryRole = null;
        var roles = jdbc.sql(ROLES_SQL)
                .param("userId", userId)
                .param("today", today)
                .query((rs, n) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("name")})
                .list();
        for (var r : roles) {
            roleIds.add((UUID) r[0]);
            if (primaryRole == null) primaryRole = (String) r[1];
        }

        return new AppUserDetails(
                userId,
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (UUID) row[4],
                primaryRole == null ? "No role assigned" : primaryRole,
                (Boolean) row[5],
                (Boolean) row[6],
                (java.time.LocalDateTime) row[7],
                permissions,
                roleIds);
    }
}
