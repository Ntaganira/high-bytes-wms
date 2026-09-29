package heritier.ntaganira.highbytes.wms.admin.role;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.role
 * - File       : RoleRow.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A role as the screens read it, with how many permissions, holders and chain steps it has
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A role as the screens read it.
 *
 * <p>{@code stepCount} counts the steps it signs in approval chains in force
 * or scheduled; {@code administers} is true when it carries any
 * administration permission, and so can never carry an operational one.
 * {@code policyDefined} is true when the policy defines the role — protected,
 * named by a segregation rule, or signing a chain step — so its permissions
 * change by migration only.
 */
public record RoleRow(
        UUID id,
        String code,
        String name,
        String description,
        RoleStructure structure,
        boolean protectedRole,
        boolean active,
        LocalDateTime createdAt,
        int permissionCount,
        int holderCount,
        int stepCount,
        boolean administers,
        boolean policyDefined
) {

    public String status() {
        return active ? "ACTIVE" : "INACTIVE";
    }
}
