package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : GrnActions.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the viewer may do to a goods received note, and the reason for each thing they may not
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.ChainStep;

/**
 * The actions the view offers, and for each one that is not available the
 * plain reason, when the viewer has any business with that action.
 *
 * <p>A reason is null when the viewer does not hold the right at all (a
 * signer's reason to the storekeeper is noise) or when nothing is expected of
 * that action in the note's current state.
 */
public record GrnActions(
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
        boolean canCorrect
) {}
