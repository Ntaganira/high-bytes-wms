package heritier.ntaganira.highbytes.wms.admin.user;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.user
 * - File       : UserRow.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A user account as the users list shows it, with its roles in force today
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.UUID;

/** A user account as the list shows it. {@code roles} is the roles in force today, with their branch. */
public record UserRow(
        UUID id,
        String username,
        String fullName,
        String email,
        String homeBranchName,
        boolean active,
        LocalDateTime lockedUntil,
        boolean mustChangePassword,
        LocalDateTime lastLoginAt,
        String roles
) {

    /** Locked out after failed sign-ins, until the lock runs out or an administrator lifts it. */
    public boolean locked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    /** The chip: a deactivated account first, then a locked one. */
    public String status() {
        if (!active) return "INACTIVE";
        return locked() ? "LOCKED" : "ACTIVE";
    }
}
