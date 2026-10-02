package heritier.ntaganira.highbytes.wms.approval;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.approval
 * - File       : AwaitingSignature.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One document whose next signature is the reader's to give, and how long it has waited
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A row of the approval queue. It names the document and the step, never its
 * content: the document's own page shows that, to whoever may read it there.
 *
 * @param waitingSince    when the last signature was given (submission signs step 1), Kigali time
 * @param escalateAfter   the step's own limit in hours, when the chain sets one
 * @param openHere        whether the reader can open and sign it from the branch they are working in; otherwise
 *                        they switch to the document's branch first
 */
public record AwaitingSignature(UUID documentId,
                                String serialNo,
                                String typeCode,
                                String typeName,
                                UUID branchId,
                                String branchCode,
                                String branchName,
                                LocalDate documentDate,
                                String reference,
                                String raisedBy,
                                int step,
                                int steps,
                                String action,
                                String roleName,
                                LocalDateTime waitingSince,
                                long hoursWaiting,
                                Integer escalateAfter,
                                boolean openHere) {

    /** Waiting longer than its step allows. A step with no limit is never overdue. */
    public boolean overdue() {
        return escalateAfter != null && hoursWaiting >= escalateAfter;
    }

    /** "3 h", "2 d 5 h": how long it has waited, read at a glance. */
    public String waited() {
        if (hoursWaiting < 24) return hoursWaiting + " h";
        long days = hoursWaiting / 24;
        long hours = hoursWaiting % 24;
        return hours == 0 ? days + " d" : days + " d " + hours + " h";
    }
}
