package heritier.ntaganira.highbytes.wms.inventory.reversal;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.reversal
 * - File       : ReversalActions.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the reversal's page offers the viewer, and why not when it does not
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.ChainStep;

/**
 * The actions the view offers, and for each one that is not available the
 * plain reason, when the viewer has any business with that action. A reason
 * is null when the viewer does not hold the right at all, or when nothing is
 * expected of that action in the reversal's current state.
 *
 * <p>{@code originalHands} is set when the viewer raised or posted the
 * original: V21 keeps them out of its reversal, and the page says so rather
 * than offering a button the database will refuse.
 */
public record ReversalActions(
        boolean canEdit,
        boolean canSubmit,
        String submitReason,
        boolean canSign,
        String signReason,
        ChainStep nextStep,
        boolean canPost,
        String postReason,
        boolean canCancel,
        String cancelReason,
        String originalHands
) {}
