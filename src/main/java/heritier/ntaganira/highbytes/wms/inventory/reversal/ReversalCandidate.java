package heritier.ntaganira.highbytes.wms.inventory.reversal;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.reversal
 * - File       : ReversalCandidate.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A posted document that could be reversed, as the reversal form's picker offers it
 * </pre>
 */

import java.time.LocalDateTime;
import java.util.UUID;

/** One posted document of a type a reversal undoes, at the branch, not already being reversed. */
public record ReversalCandidate(
        UUID id,
        String serialNo,
        String typeCode,
        String title,
        LocalDateTime postedAt
) {

    public String label() {
        return serialNo + " · " + title;
    }
}
