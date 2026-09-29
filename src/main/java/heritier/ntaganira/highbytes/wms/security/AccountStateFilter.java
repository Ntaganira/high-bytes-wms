package heritier.ntaganira.highbytes.wms.security;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.security
 * - File       : AccountStateFilter.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Applies access changes on the next request and holds users to a password change
 * </pre>
 */

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Checks the signed-in account on every request, before authorization.
 *
 * <ul>
 *   <li>A deactivated account, or one whose sessions were ended by a
 *       password reset or change, is signed out here.</li>
 *   <li>When what the user may do has changed, or the day has turned so a
 *       dated grant has started or expired, or the user switched branch,
 *       their permissions are reloaded before this request is authorised.</li>
 *   <li>A user who must change their password can do nothing else first.</li>
 * </ul>
 *
 * <p>Without this, a right revoked from someone mid-shift stayed usable
 * until they signed out, which can be never. The cost is one primary-key
 * read per page.
 *
 * <p>Not a bean: Spring Boot would register a bean filter a second time,
 * outside the security chain. {@code SecurityConfig} places it.
 */
public class AccountStateFilter extends OncePerRequestFilter {

    private static final String STATE = """
            SELECT is_active, security_stamp, session_epoch FROM app_user WHERE id = :id
            """;

    /** The pages a user who must change their password can still reach. */
    private static final Set<String> BEFORE_PASSWORD_CHANGE = Set.of(
            "/profile/password", "/logout", "/login", "/error");

    private final JdbcClient jdbc;
    private final SessionAccess sessions;

    public AccountStateFilter(JdbcClient jdbc, SessionAccess sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathOf(request);
        return path.startsWith("/css/") || path.startsWith("/js/")
               || path.startsWith("/webjars/") || path.startsWith("/favicon.");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails user)) {
            chain.doFilter(request, response);
            return;
        }

        var state = jdbc.sql(STATE)
                .param("id", user.id(), Types.OTHER)
                .query((rs, n) -> new State(
                        rs.getBoolean("is_active"),
                        rs.getObject("security_stamp", UUID.class),
                        rs.getInt("session_epoch")))
                .optional()
                .orElse(null);

        if (state == null || !state.active() || state.sessionEpoch() != user.sessionEpoch()) {
            signOut(request, response);
            return;
        }

        UUID requested = sessions.requestedBranch(request);
        boolean stale = !state.securityStamp().equals(user.securityStamp())
                        || !LocalDate.now().equals(user.loadedOn())
                        || (requested != null && !requested.equals(user.branchId()));
        if (stale) {
            var fresh = sessions.refresh(request, response, requested != null ? requested : user.branchId());
            if (fresh.isEmpty()) {
                signOut(request, response);
                return;
            }
            user = fresh.get();
        }

        if (user.mustChangePassword() && !BEFORE_PASSWORD_CHANGE.contains(pathOf(request))) {
            response.sendRedirect(request.getContextPath() + "/profile/password");
            return;
        }

        chain.doFilter(request, response);
    }

    private void signOut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        sessions.end(request);
        response.sendRedirect(request.getContextPath() + "/login?ended");
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private record State(boolean active, UUID securityStamp, int sessionEpoch) {}
}
