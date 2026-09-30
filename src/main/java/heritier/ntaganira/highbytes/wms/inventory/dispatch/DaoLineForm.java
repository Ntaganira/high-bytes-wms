package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DaoLineForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One line of the delivery authorization form, bound as lines[n].field
 * </pre>
 */

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** A line, numbered by the server from its position; the base quantity follows from quantity and unit. */
public class DaoLineForm {

    @NotNull(message = "Choose an item")
    private UUID itemId;

    @NotNull(message = "Choose a unit")
    private UUID uomId;

    @NotNull(message = "Enter the quantity to authorize")
    @DecimalMin(value = "0.001", message = "The quantity must be more than zero")
    @Digits(integer = 13, fraction = 3, message = "At most 3 decimal places")
    private BigDecimal quantity;

    @Size(max = 240, message = "A note is at most 240 characters")
    private String note;

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public UUID getUomId() { return uomId; }
    public void setUomId(UUID uomId) { this.uomId = uomId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note == null || note.isBlank() ? null : note.trim(); }
}
