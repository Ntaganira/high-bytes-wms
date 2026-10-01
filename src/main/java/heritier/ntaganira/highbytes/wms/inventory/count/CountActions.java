package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountActions.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the count view offers the person looking at it, and why not when it does not
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.ChainStep;

/**
 * The actions the view offers. A reason is null when the viewer holds none of
 * the rights concerned or nothing is expected of that action in the current
 * state; otherwise it says why the action is unavailable, in words.
 */
public record CountActions(
        boolean canCount,
        String countReason,
        boolean canVerify,
        String verifyReason,
        boolean canSubmit,
        String submitReason,
        boolean canSign,
        String signReason,
        ChainStep nextStep,
        boolean canCancel,
        String cancelReason,
        boolean canPost,
        String postReason
) {}
