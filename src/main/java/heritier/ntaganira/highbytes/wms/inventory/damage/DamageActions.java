package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageActions.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the viewer may do to a report, and the reason for each thing they may not
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.ChainStep;

/**
 * The actions the view offers. A reason is null when the viewer holds none of the
 * rights concerned or nothing is expected of that action in the current state;
 * otherwise it says why the action is unavailable, in words.
 */
public record DamageActions(
        boolean canEdit,
        String editReason,
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
