package heritier.ntaganira.highbytes.wms.security;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.security
 * - File       : AppUserDetails.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The signed-in user: identity, permissions and account state
 * </pre>
 */

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The signed-in user.
 *
 * <p>Authorities are permission codes ({@code dispatch.release}), not role
 * names. Roles group permissions; the check at the point of action asks
 * whether the user may do the thing, never who they are. That keeps a new
 * role — created at runtime by an administrator — working immediately
 * without a code change.
 *
 * <p>A role can be granted at one branch only. The authorities are the
 * permissions that apply at {@link #branchId()}, the branch the user is
 * working in, so a Warehouse Manager at Rubavu is not one at Gahanga. For a
 * record that belongs to another branch, ask {@link #hasAt}.
 *
 * <p>{@link #securityStamp()} and {@link #sessionEpoch()} are what the
 * account held when this was loaded. {@code AccountStateFilter} compares
 * them with the database on every request, so a revoked right or a
 * deactivated account takes effect at once, not at the next sign-in.
 */
public record AppUserDetails(
        UUID id,
        String username,
        String fullName,
        String passwordHash,
        UUID homeBranchId,
        String primaryRoleName,
        boolean active,
        boolean mustChangePassword,
        LocalDateTime lockedUntil,
        Set<String> permissions,
        Set<UUID> roleIds,
        UUID branchId,
        List<UUID> accessibleBranchIds,
        List<Grant> grants,
        UUID securityStamp,
        int sessionEpoch,
        LocalDate loadedOn
) implements UserDetails {

    /** One live role assignment: a role, where it applies, and what it permits. */
    public record Grant(UUID roleId, String roleName, UUID branchId, Set<String> permissions) {

        /** A grant with no branch applies at every branch. */
        public boolean appliesAt(UUID branch) {
            return branchId == null || branchId.equals(branch);
        }
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername()  { return username; }

    @Override
    public boolean isAccountNonLocked() {
        return lockedUntil == null || lockedUntil.isBefore(LocalDateTime.now());
    }

    @Override public boolean isEnabled() { return active; }

    /** Initials for the avatar: "Heritier Ntaganira" becomes "HN". */
    public String initials() {
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    /** Whether the user may do this at the branch they are working in. */
    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    /** Whether the user may do this at the given branch, whichever one they are working in. */
    public boolean hasAt(String permission, UUID branch) {
        return grants.stream().anyMatch(g -> g.appliesAt(branch) && g.permissions().contains(permission));
    }

    /** Every permission the user holds here, for a debug view. */
    public List<String> sortedPermissions() {
        return permissions.stream().sorted().toList();
    }

    /**
     * The roles in force at the branch the user is working in, as the audit
     * trail records them. Someone holding two roles acted as both, and the
     * Board's question is which hats one person wore.
     */
    public String rolesHeldHere() {
        var names = grants.stream()
                .filter(g -> g.appliesAt(branchId))
                .map(Grant::roleName)
                .distinct()
                .sorted()
                .toList();
        return names.isEmpty() ? primaryRoleName : String.join(" + ", names);
    }

    // The session registry counts a user's sessions by comparing principals.
    // Two loads of the same account are the same user, whatever changed
    // between them, so equality is the id alone.

    @Override
    public boolean equals(Object other) {
        return other instanceof AppUserDetails that && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "AppUserDetails[" + username + "]";
    }
}
