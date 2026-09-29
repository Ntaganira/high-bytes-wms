package heritier.ntaganira.highbytes.wms.admin.user;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.user
 * - File       : GrantForm.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Granting one role to a user: where, from when, until when, and whether it is leave cover
 * </pre>
 */

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Granting one role (FR-SEC-18): at every branch or one, from a date, until
 * a date or open-ended. Cover for someone's leave names them and must end.
 */
public class GrantForm {

    @NotNull(message = "Choose a role")
    private UUID roleId;

    /** Empty means every branch. */
    private UUID branchId;

    @NotNull(message = "Enter the date the role starts")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validFrom = LocalDate.now();

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validTo;

    private boolean delegation;

    private UUID delegatedFor;

    @Size(max = 400, message = "A note is at most 400 characters")
    private String reason;

    public GrantForm() {}

    // ---- accessors -------------------------------------------------------

    public UUID getRoleId() { return roleId; }
    public void setRoleId(UUID roleId) { this.roleId = roleId; }

    public UUID getBranchId() { return branchId; }
    public void setBranchId(UUID branchId) { this.branchId = branchId; }

    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }

    public LocalDate getValidTo() { return validTo; }
    public void setValidTo(LocalDate validTo) { this.validTo = validTo; }

    public boolean isDelegation() { return delegation; }
    public void setDelegation(boolean delegation) { this.delegation = delegation; }

    public UUID getDelegatedFor() { return delegatedFor; }
    public void setDelegatedFor(UUID delegatedFor) { this.delegatedFor = delegatedFor; }

    public String getReason() { return reason; }
    public void setReason(String reason) {
        this.reason = (reason == null || reason.isBlank()) ? null : reason.trim();
    }
}
