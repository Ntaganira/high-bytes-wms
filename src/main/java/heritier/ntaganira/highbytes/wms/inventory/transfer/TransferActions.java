package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferActions.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the viewer may do to a transfer, and the reason for each thing they may not
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.ChainStep;

/**
 * The actions the view offers. A reason is null when the viewer holds none of
 * the rights concerned or nothing is expected of that action in the current
 * state. {@code canDispatch} means the viewer may record the goods leaving now
 * (approved, and they neither raised nor signed it); {@code canReceive} that
 * they may raise the receipt at the destination.
 */
public record TransferActions(
        boolean canEdit,
        boolean canSubmit,
        String submitReason,
        boolean canSign,
        String signReason,
        ChainStep nextStep,
        boolean canCancel,
        String cancelReason,
        boolean canCorrect,
        boolean canDispatch,
        String dispatchReason,
        boolean canReceive,
        String receiveReason,
        boolean canRaiseLoss,
        String lossReason
) {}
