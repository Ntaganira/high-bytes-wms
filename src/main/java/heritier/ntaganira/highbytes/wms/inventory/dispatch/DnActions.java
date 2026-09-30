package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DnActions.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the viewer may do to a delivery note, and the reason for each thing they may not
 * </pre>
 */

/**
 * The actions the view offers. A reason is null when the viewer does not hold
 * {@code dispatch.post} or nothing is expected of that action in the note's
 * state.
 */
public record DnActions(
        boolean canEdit,
        boolean canPost,
        String postReason,
        boolean canCancel,
        String cancelReason
) {}
