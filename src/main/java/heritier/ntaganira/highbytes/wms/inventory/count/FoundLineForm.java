package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : FoundLineForm.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A place found holding stock the sheet does not list, added while counting
 * </pre>
 */

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stock found where the sheet lists nothing: an item, the bin it was found in
 * (or none) and what was counted there. The database weighs it against its own
 * book, so stock found in the wrong bin shows as a surplus there and a shortage
 * where the book has it.
 */
public class FoundLineForm {

    @NotNull(message = "Choose the item found")
    private UUID itemId;

    private UUID binId;

    @NotNull(message = "Enter what was counted")
    @DecimalMin(value = "0", message = "A count cannot be below zero")
    private BigDecimal quantity;

    @Size(max = 240, message = "At most 240 characters")
    private String note;

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public UUID getBinId() { return binId; }
    public void setBinId(UUID binId) { this.binId = binId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
