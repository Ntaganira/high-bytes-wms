package heritier.ntaganira.highbytes.wms.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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
        Set<UUID> roleIds
) implements UserDetails {

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

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    /** Every permission the user holds, for a debug view. */
    public List<String> sortedPermissions() {
        return permissions.stream().sorted().toList();
    }
}
