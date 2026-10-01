package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageLineForm.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One line of the return and damage form, bound as lines[n].field
 * </pre>
 */

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line, numbered by the server from its position. What a row names depends on the
 * report's kind: a write-off or a release names an item, a unit and the bin it
 * leaves (a release also the bin it arrives in); a transit loss names the transfer
 * line it writes off, a customer return the delivery note line it brings back, and
 * the item and unit are then that line's own. A reference row whose quantity is
 * zero is left off, as on a receipt: nothing of that line is being reported.
 */
public class DamageLineForm {

    private UUID itemId;
    private UUID uomId;

    @DecimalMin(value = "0", message = "The quantity cannot be negative")
    @Digits(integer = 13, fraction = 3, message = "At most 3 decimal places")
    private BigDecimal quantity;

    private UUID storageBinId;
    private UUID toStorageBinId;
    private UUID transferLineId;
    private UUID deliveryNoteLineId;

    @Size(max = 240, message = "A note is at most 240 characters")
    private String note;

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public UUID getUomId() { return uomId; }
    public void setUomId(UUID uomId) { this.uomId = uomId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public UUID getStorageBinId() { return storageBinId; }
    public void setStorageBinId(UUID storageBinId) { this.storageBinId = storageBinId; }

    public UUID getToStorageBinId() { return toStorageBinId; }
    public void setToStorageBinId(UUID toStorageBinId) { this.toStorageBinId = toStorageBinId; }

    public UUID getTransferLineId() { return transferLineId; }
    public void setTransferLineId(UUID transferLineId) { this.transferLineId = transferLineId; }

    public UUID getDeliveryNoteLineId() { return deliveryNoteLineId; }
    public void setDeliveryNoteLineId(UUID deliveryNoteLineId) { this.deliveryNoteLineId = deliveryNoteLineId; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note == null || note.isBlank() ? null : note.trim(); }

    /** Whether this row reports anything: a reference row at zero is left off. */
    public boolean isBlank() {
        return quantity == null || quantity.signum() == 0;
    }
}
