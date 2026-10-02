package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : CloseActions.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the signed-in user may do to a day's close, and why not
 * </pre>
 */

/**
 * The actions the close offers. A reason is null when the viewer holds none of the rights concerned or nothing is
 * expected of that action in the current state; otherwise it says why the action is unavailable, in words.
 */
public record CloseActions(
        boolean canReconcile,
        String reconcileReason,
        boolean noteRequired,
        boolean canCountersign,
        String countersignReason,
        boolean canReturn
) {}
