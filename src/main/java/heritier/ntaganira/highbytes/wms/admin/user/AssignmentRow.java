package heritier.ntaganira.highbytes.wms.admin.user;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.user
 * - File       : AssignmentRow.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One role assignment on a user, live or historical, with who granted and revoked it
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * One role assignment, live or historical. Assignments are never deleted,
 * so this list is also the answer to "what could this person do on a given
 * day".
 */
public record AssignmentRow(
        UUID id,
        UUID roleId,
        String roleCode,
        String roleName,
        boolean roleActive,
        UUID branchId,
        String branchName,
        LocalDate validFrom,
        LocalDate validTo,
        boolean delegation,
        String delegatedForName,
        String assignedByName,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        String revokedByName,
        String revokeReason
) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    /** ACTIVE today, SCHEDULED to start, EXPIRED on its end date, or REVOKED. */
    public String state() {
        if (revokedAt != null) return "REVOKED";
        LocalDate today = LocalDate.now();
        if (validFrom.isAfter(today)) return "SCHEDULED";
        if (validTo != null && validTo.isBefore(today)) return "EXPIRED";
        return "ACTIVE";
    }

    /** Still to be reckoned with: in force today, or due to start. */
    public boolean revocable() {
        String state = state();
        return state.equals("ACTIVE") || state.equals("SCHEDULED");
    }

    public String scope() {
        return branchName == null ? "All branches" : branchName;
    }

    /** How the audit trail and the table describe the assignment's terms. */
    public String terms() {
        return termsOf(validFrom, validTo, delegation ? delegatedForName : null);
    }

    /** "From 1 Oct 2026 to 14 Oct 2026, covering for Jane Uwase". */
    public static String termsOf(LocalDate from, LocalDate to, String coveringFor) {
        var out = new StringBuilder("From ").append(DAY.format(from));
        if (to != null) out.append(" to ").append(DAY.format(to));
        if (coveringFor != null) out.append(", covering for ").append(coveringFor);
        return out.toString();
    }
}
