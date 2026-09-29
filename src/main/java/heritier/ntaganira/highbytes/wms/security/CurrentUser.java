package heritier.ntaganira.highbytes.wms.security;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.security
 * - File       : CurrentUser.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The signed-in user, and the check for a right at a named branch
 * </pre>
 */

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * The signed-in user, for services.
 *
 * <p>{@code @PreAuthorize} checks the permissions that apply at the branch
 * the user is working in. A record that belongs to another branch needs
 * {@link #requireAt}: a location manager at Gahanga, looking at Gahanga, must
 * still be refused an edit to a Rubavu location reached by its address.
 */
public final class CurrentUser {

    /**
     * Request attribute naming the branch a {@link #requireAt} refusal was
     * for, so the error page names that branch rather than the one the user
     * is working in.
     */
    public static final String DENIED_BRANCH = CurrentUser.class.getName() + ".deniedBranch";

    private CurrentUser() {}

    public static Optional<AppUserDetails> get() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppUserDetails user
                ? Optional.of(user) : Optional.empty();
    }

    /** The signed-in user's id, or null when nobody is signed in. */
    public static UUID id() {
        return get().map(AppUserDetails::id).orElse(null);
    }

    /** Whether the signed-in user holds the permission at that branch: for showing what they can do. */
    public static boolean holdsAt(String permission, UUID branchId) {
        return get().map(user -> user.hasAt(permission, branchId)).orElse(false);
    }

    /** Refuses, as a 403, unless the signed-in user holds the permission at that branch. */
    public static void requireAt(String permission, UUID branchId) {
        var user = get().orElseThrow(() -> new AccessDeniedException("Not signed in"));
        if (!user.hasAt(permission, branchId)) {
            var request = RequestContextHolder.getRequestAttributes();
            if (request != null) {
                request.setAttribute(DENIED_BRANCH, branchId, RequestAttributes.SCOPE_REQUEST);
            }
            throw new AccessDeniedException(
                    "The " + permission + " right does not extend to branch " + branchId);
        }
    }
}
