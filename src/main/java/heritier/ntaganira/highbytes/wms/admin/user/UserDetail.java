package heritier.ntaganira.highbytes.wms.admin.user;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.user
 * - File       : UserDetail.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One user account in full, as the user page and My profile show it
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One user account in full. */
public record UserDetail(
        UUID id,
        String username,
        String fullName,
        String email,
        String phone,
        UUID homeBranchId,
        String homeBranchName,
        boolean active,
        boolean mustChangePassword,
        int failedLoginCount,
        LocalDateTime lockedUntil,
        LocalDateTime lastLoginAt,
        LocalDate lastLeaveStart,
        LocalDate lastLeaveEnd,
        LocalDateTime deactivatedAt,
        String deactivatedReason,
        LocalDateTime createdAt,
        LocalDateTime passwordChangedAt
) {

    public boolean locked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    public String status() {
        if (!active) return "INACTIVE";
        return locked() ? "LOCKED" : "ACTIVE";
    }

    /**
     * Board paper §5.1: ten consecutive working days' leave a year is a
     * control, because fraud that needs its author present every day
     * surfaces when they are away. An account a year old with no leave
     * recorded in the last twelve months is reportable.
     */
    public boolean leaveOverdue() {
        LocalDate yearAgo = LocalDate.now().minusYears(1);
        return active
               && createdAt != null && createdAt.toLocalDate().isBefore(yearAgo)
               && (lastLeaveEnd == null || lastLeaveEnd.isBefore(yearAgo));
    }
}
