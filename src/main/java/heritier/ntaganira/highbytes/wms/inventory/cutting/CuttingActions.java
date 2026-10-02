package heritier.ntaganira.highbytes.wms.inventory.cutting;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.cutting
 * - File       : CuttingActions.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the viewer of a cutting order may do now, and why not when they may not
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.ChainStep;

/**
 * Each action with the reason it is not offered, when the viewer holds the right that would offer it: a disabled
 * action teaches the rule, a hidden one makes people hunt.
 */
public record CuttingActions(boolean canEdit,
                             boolean canSubmit, String submitReason,
                             boolean canSign, String signReason, ChainStep nextStep,
                             boolean canPost, String postReason,
                             boolean canCancel, String cancelReason,
                             boolean canLoad) {}
