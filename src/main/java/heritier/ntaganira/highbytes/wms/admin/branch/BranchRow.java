package heritier.ntaganira.highbytes.wms.admin.branch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.branch
 * - File       : BranchRow.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A branch as the administration screens read it, with what hangs on it
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A branch as the administration screens read it: its configuration, how many active locations and people it has,
 * and whether it has a past (a document raised there or stock moved there), which fixes its type.
 */
public record BranchRow(
        UUID id,
        String code,
        String name,
        BranchType branchType,
        String city,
        String countryCode,
        boolean bonded,
        String customsRegime,
        boolean active,
        LocalDateTime createdAt,
        int locationCount,
        int peopleCount,
        boolean hasHistory
) {

    public String status() {
        return active ? "ACTIVE" : "INACTIVE";
    }

    public boolean main() {
        return branchType == BranchType.MAIN;
    }

    /** As the trail names it. */
    public String label() {
        return "Branch · " + code;
    }
}
