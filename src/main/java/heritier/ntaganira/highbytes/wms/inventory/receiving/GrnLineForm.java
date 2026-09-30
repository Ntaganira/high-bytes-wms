package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : GrnLineForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One line of the goods received form, bound as lines[n].field
 * </pre>
 */

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line as the form holds it. There is no line number: the server numbers
 * the lines by their position when it saves, so adding or removing a row
 * never leaves a gap or a duplicate for the browser to renumber.
 *
 * <p>The base quantity is not here either: it follows from quantity and unit
 * (the item's conversion factor) and is worked out by the service.
 */
public class GrnLineForm {

    @NotNull(message = "Choose an item")
    private UUID itemId;

    @NotNull(message = "Choose a unit")
    private UUID uomId;

    @NotNull(message = "Enter the quantity received")
    @DecimalMin(value = "0.001", message = "The quantity must be more than zero")
    @Digits(integer = 13, fraction = 3, message = "At most 3 decimal places")
    private BigDecimal quantity;

    @NotNull(message = "Enter the unit price")
    @DecimalMin(value = "0", message = "A price cannot be negative")
    @Digits(integer = 14, fraction = 4, message = "At most 4 decimal places")
    private BigDecimal unitPrice;

    private UUID storageBinId;

    @DecimalMin(value = "0.01", message = "The thickness must be more than zero")
    @Digits(integer = 4, fraction = 2, message = "At most 2 decimal places")
    private BigDecimal measuredThicknessMm;

    @DecimalMin(value = "0", message = "Cannot be negative")
    @Digits(integer = 13, fraction = 3, message = "At most 3 decimal places")
    private BigDecimal supplierQuantityBase;

    @Size(max = 240, message = "A note is at most 240 characters")
    private String note;

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public UUID getUomId() { return uomId; }
    public void setUomId(UUID uomId) { this.uomId = uomId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public UUID getStorageBinId() { return storageBinId; }
    public void setStorageBinId(UUID storageBinId) { this.storageBinId = storageBinId; }

    public BigDecimal getMeasuredThicknessMm() { return measuredThicknessMm; }
    public void setMeasuredThicknessMm(BigDecimal measuredThicknessMm) { this.measuredThicknessMm = measuredThicknessMm; }

    public BigDecimal getSupplierQuantityBase() { return supplierQuantityBase; }
    public void setSupplierQuantityBase(BigDecimal supplierQuantityBase) { this.supplierQuantityBase = supplierQuantityBase; }

    public String getNote() { return note; }
    public void setNote(String note) {
        this.note = note == null || note.isBlank() ? null : note.trim();
    }
}
