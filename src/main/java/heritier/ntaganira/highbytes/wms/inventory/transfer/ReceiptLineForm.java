package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : ReceiptLineForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One line of the receipt form, bound as lines[n].field
 * </pre>
 */

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line of the receipt: how much of a transfer line actually arrived, and
 * where it is put away. The form carries a row for every transfer line,
 * prefilled at the dispatched quantity; a quantity of zero means nothing of
 * that line arrived, and the line is left off the saved receipt, so its whole
 * quantity stays in transit.
 */
public class ReceiptLineForm {

    @NotNull(message = "This row must serve a transfer line")
    private UUID transferLineId;

    @NotNull(message = "Enter the quantity that arrived, or 0 if none did")
    @DecimalMin(value = "0", message = "The quantity cannot be negative")
    @Digits(integer = 13, fraction = 3, message = "At most 3 decimal places")
    private BigDecimal quantity;

    private UUID storageBinId;

    @Size(max = 240, message = "A note is at most 240 characters")
    private String note;

    public UUID getTransferLineId() { return transferLineId; }
    public void setTransferLineId(UUID id) { this.transferLineId = id; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public UUID getStorageBinId() { return storageBinId; }
    public void setStorageBinId(UUID storageBinId) { this.storageBinId = storageBinId; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note == null || note.isBlank() ? null : note.trim(); }
}
