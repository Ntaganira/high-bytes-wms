package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : ReceiptActions.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the viewer may do to a transfer receipt, and the reason for each thing they may not
 * </pre>
 */

public record ReceiptActions(
        boolean canEdit,
        boolean canPost,
        String postReason,
        boolean canCancel,
        String cancelReason
) {}
