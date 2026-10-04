package heritier.ntaganira.highbytes.wms.inventory.opening;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.opening
 * - File       : OpeningLineForm.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One line of the opening balance form
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
 * (the item's conversion factor) and is worked out by the service, which is
 * what the database then checks it against.
 */
public class OpeningLineForm {

    @NotNull(message = "Choose an item")
    private UUID itemId;

    @NotNull(message = "Choose a unit")
    private UUID uomId;

    @NotNull(message = "Enter the quantity counted")
    @DecimalMin(value = "0.001", message = "The quantity must be more than zero")
    @Digits(integer = 13, fraction = 3, message = "At most 3 decimal places")
    private BigDecimal quantity;

    /**
     * The carrying cost per base unit from the old books. Zero is allowed:
     * stock written down to nothing is still stock on the floor, and refusing
     * zero would push people into inventing a token value.
     */
    @NotNull(message = "Enter the carrying cost from the old books")
    @DecimalMin(value = "0", message = "A cost cannot be negative")
    @Digits(integer = 14, fraction = 4, message = "At most 4 decimal places")
    private BigDecimal unitCost;

    private UUID storageBinId;

    @DecimalMin(value = "0.01", message = "The thickness must be more than zero")
    @Digits(integer = 4, fraction = 2, message = "At most 2 decimal places")
    private BigDecimal measuredThicknessMm;

    @Size(max = 240, message = "A note is at most 240 characters")
    private String note;

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }

    public UUID getUomId() { return uomId; }
    public void setUomId(UUID uomId) { this.uomId = uomId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }

    public UUID getStorageBinId() { return storageBinId; }
    public void setStorageBinId(UUID storageBinId) { this.storageBinId = storageBinId; }

    public BigDecimal getMeasuredThicknessMm() { return measuredThicknessMm; }
    public void setMeasuredThicknessMm(BigDecimal measuredThicknessMm) {
        this.measuredThicknessMm = measuredThicknessMm;
    }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
