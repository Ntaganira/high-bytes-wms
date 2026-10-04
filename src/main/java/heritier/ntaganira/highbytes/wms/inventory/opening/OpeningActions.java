package heritier.ntaganira.highbytes.wms.inventory.opening;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.opening
 * - File       : OpeningActions.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The actions the opening balance view offers, and why each unavailable one is unavailable
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.ChainStep;

/**
 * The actions the view offers, and for each one that is not available the
 * plain reason, when the viewer has any business with that action.
 *
 * <p>A reason is null when the viewer does not hold the right at all (a
 * signer's reason to the storekeeper is noise) or when nothing is expected of
 * that action in the sheet's current state.
 *
 * <p>{@code alreadyLoaded} and {@code branchHasTraded} are the two reasons
 * particular to the cutover, and they are shown on the form rather than left
 * for the database to refuse at the end: a person should not key three
 * hundred lines before being told the place was loaded last week.
 */
public record OpeningActions(
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
        boolean alreadyLoaded,
        boolean branchHasTraded
) {}
