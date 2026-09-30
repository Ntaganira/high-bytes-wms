package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DaoActions.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the viewer may do to a delivery authorization, and the reason for each thing they may not
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.ChainStep;

/**
 * The actions the view offers. A reason is null when the viewer holds none of
 * the rights concerned or nothing is expected of that action in the current
 * state. {@code canLoad} means the viewer may raise the delivery note now:
 * the authorization is released, has no live note, and they hold
 * {@code dispatch.post}.
 */
public record DaoActions(
        boolean canEdit,
        boolean canSubmit,
        String submitReason,
        boolean canSign,
        String signReason,
        ChainStep nextStep,
        boolean canCancel,
        String cancelReason,
        boolean canCorrect,
        boolean canLoad
) {}
