package heritier.ntaganira.highbytes.wms.security;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.security
 * - File       : SessionAccess.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Keeps the signed-in user in the session up to date, or ends the session
 * </pre>
 */

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * What the session knows about the signed-in user.
 *
 * <p>The session holds a copy of the user taken at sign-in. This replaces
 * that copy when the account changes — a role granted or revoked, a role's
 * permissions edited, a branch switched, the user's own password changed —
 * and ends the session when the account may no longer be used at all.
 */
@Component
public class SessionAccess {

    /** The branch the user chose to work in. The user's permissions follow it. */
    public static final String BRANCH_SESSION_KEY = "hb.currentBranchId";

    private final AppUserDetailsService users;
    private final SecurityContextRepository contexts;

    public SessionAccess(AppUserDetailsService users, SecurityContextRepository contexts) {
        this.users = users;
        this.contexts = contexts;
    }

    /** The branch this session asked to work in, if it has chosen one. */
    public UUID requestedBranch(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (UUID) session.getAttribute(BRANCH_SESSION_KEY);
    }

    /**
     * Load the signed-in user again and keep the result in the session.
     * Empty when nobody is signed in or the account no longer exists.
     */
    public Optional<AppUserDetails> refresh(HttpServletRequest request, HttpServletResponse response,
                                            UUID branch) {
        var current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null || !(current.getPrincipal() instanceof AppUserDetails user)) {
            return Optional.empty();
        }
        var fresh = users.reload(user.id(), branch);
        if (fresh.isEmpty()) return Optional.empty();

        var token = UsernamePasswordAuthenticationToken.authenticated(
                fresh.get(), null, fresh.get().getAuthorities());
        token.setDetails(current.getDetails());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);

        // Keep the chosen branch in step with where the user may actually work.
        HttpSession session = request.getSession(false);
        if (session != null) {
            if (fresh.get().branchId() == null) session.removeAttribute(BRANCH_SESSION_KEY);
            else session.setAttribute(BRANCH_SESSION_KEY, fresh.get().branchId());
        }
        return fresh;
    }

    /** Sign the user out of this session. */
    public void end(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
