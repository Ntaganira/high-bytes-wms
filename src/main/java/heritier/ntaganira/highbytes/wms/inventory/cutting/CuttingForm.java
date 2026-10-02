package heritier.ntaganira.highbytes.wms.inventory.cutting;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.cutting
 * - File       : CuttingForm.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The cutting order form: customer, place, the sheet cut, and the pieces and off-cuts cut from it
 * </pre>
 */

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The form. No document date: an order is dated the day it is created, in Kigali, by the database. One sheet item
 * per order, taken from one bin or unbinned: the database allows several sheet lines of one item, the form asks for
 * one. {@code version} is the optimistic-lock version the form was opened at.
 */
public class CuttingForm {

    private UUID id;
    private Integer version;

    @NotNull(message = "Choose the customer")
    private UUID customerId;

    @NotNull(message = "Choose where the glass is cut")
    private UUID locationId;

    @Size(max = 60, message = "At most 60 characters")
    private String customerReference;

    @Size(max = 80, message = "At most 80 characters")
    private String customsReference;

    @Size(max = 120, message = "At most 120 characters")
    private String reference;

    @Size(max = 1000, message = "At most 1000 characters")
    private String notes;

    @NotNull(message = "Choose the sheet being cut")
    private UUID sheetItemId;

    private UUID sheetBinId;

    @NotNull(message = "Enter how many sheets are cut")
    @DecimalMin(value = "1", message = "At least one sheet")
    @Digits(integer = 13, fraction = 0, message = "Whole sheets")
    private BigDecimal sheets;

    /** At most 50 sizes: each size cut for the first time is an item made for good. */
    @Valid
    @Size(min = 1, max = 50, message = "A cutting order cuts between 1 and 50 sizes")
    private List<CuttingOutputForm> outputs = new ArrayList<>();

    public boolean isNew() { return id == null; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }
    public String getCustomerReference() { return customerReference; }
    public void setCustomerReference(String v) { this.customerReference = blankToNull(v); }
    public String getCustomsReference() { return customsReference; }
    /** A blank customs reference is no reference: the database refuses one. */
    public void setCustomsReference(String v) { this.customsReference = blankToNull(v); }
    public String getReference() { return reference; }
    public void setReference(String v) { this.reference = blankToNull(v); }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = blankToNull(v); }
    public UUID getSheetItemId() { return sheetItemId; }
    public void setSheetItemId(UUID sheetItemId) { this.sheetItemId = sheetItemId; }
    public UUID getSheetBinId() { return sheetBinId; }
    public void setSheetBinId(UUID sheetBinId) { this.sheetBinId = sheetBinId; }
    public BigDecimal getSheets() { return sheets; }
    public void setSheets(BigDecimal sheets) { this.sheets = sheets; }
    public List<CuttingOutputForm> getOutputs() { return outputs; }
    public void setOutputs(List<CuttingOutputForm> outputs) { this.outputs = outputs == null ? new ArrayList<>() : outputs; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
