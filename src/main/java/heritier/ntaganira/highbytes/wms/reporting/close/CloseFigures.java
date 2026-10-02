package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : CloseFigures.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One branch's day in values: opening, receipts, dispatches, adjustments, closing
 * </pre>
 */

import java.math.BigDecimal;

/**
 * A day in values, from the ledger ({@code close_figures}, V16): opening + receipts - dispatches + adjustments =
 * closing, in RWF, and the number of movements dated that day. Every movement falls in exactly one bucket, so the
 * sum always holds; what a reconciliation checks is the movements behind it and the exceptions beside it.
 */
public record CloseFigures(
        BigDecimal opening,
        BigDecimal receipts,
        BigDecimal dispatches,
        BigDecimal adjustments,
        BigDecimal closing,
        int movementCount
) {

    /** Whether two readings of a day agree to the franc and the movement. */
    public boolean sameAs(CloseFigures other) {
        return other != null
                && opening.compareTo(other.opening) == 0
                && receipts.compareTo(other.receipts) == 0
                && dispatches.compareTo(other.dispatches) == 0
                && adjustments.compareTo(other.adjustments) == 0
                && closing.compareTo(other.closing) == 0
                && movementCount == other.movementCount;
    }
}
