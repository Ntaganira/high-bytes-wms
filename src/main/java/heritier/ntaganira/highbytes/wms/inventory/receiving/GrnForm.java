package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : GrnForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The goods received create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The form. A mutable bean because Thymeleaf binds indexed fields
 * ({@code lines[0].quantity}) through setters.
 *
 * <p>There is no document date: a receipt is dated the day it is created, in
 * Kigali, by the database, because that date decides which approval chain
 * applies and so cannot be chosen.
 *
 * <p>{@code version} is the document's optimistic-lock version as it stood
 * when the form was opened; a save from a stale form is refused.
 */
public class GrnForm {

    private UUID id;
    private Integer version;
    private UUID supersedesDocumentId;

    @NotNull(message = "Choose the supplier")
    private UUID supplierId;

    @NotNull(message = "Choose where the goods are put")
    private UUID locationId;

    @Size(max = 60, message = "At most 60 characters")
    private String supplierDeliveryNoteNo;

    @Size(max = 60, message = "At most 60 characters")
    private String supplierInvoiceNo;

    @Size(max = 60, message = "At most 60 characters")
    private String purchaseOrderNo;

    @Size(max = 80, message = "At most 80 characters")
    private String customsReference;

    @NotBlank(message = "Enter the invoice currency")
    @Pattern(regexp = "^$|^[A-Za-z]{3}$", message = "Use a three-letter currency code, such as RWF or USD")
    private String currencyCode = "RWF";

    @NotNull(message = "Enter the exchange rate")
    @DecimalMin(value = "0.000001", message = "The rate must be more than zero")
    @Digits(integer = 12, fraction = 6, message = "At most 6 decimal places")
    private BigDecimal exchangeRate = BigDecimal.ONE;

    @DecimalMin(value = "0", message = "Cannot be negative")
    @Digits(integer = 16, fraction = 2, message = "At most 2 decimal places")
    private BigDecimal freightRwf = BigDecimal.ZERO;

    @DecimalMin(value = "0", message = "Cannot be negative")
    @Digits(integer = 16, fraction = 2, message = "At most 2 decimal places")
    private BigDecimal dutyRwf = BigDecimal.ZERO;

    @DecimalMin(value = "0", message = "Cannot be negative")
    @Digits(integer = 16, fraction = 2, message = "At most 2 decimal places")
    private BigDecimal clearingRwf = BigDecimal.ZERO;

    @DecimalMin(value = "0", message = "Cannot be negative")
    @Digits(integer = 16, fraction = 2, message = "At most 2 decimal places")
    private BigDecimal demurrageRwf = BigDecimal.ZERO;

    @Size(max = 120, message = "At most 120 characters")
    private String reference;

    @Size(max = 1000, message = "At most 1000 characters")
    private String notes;

    @Valid
    @Size(min = 1, message = "A receipt needs at least one line")
    private List<GrnLineForm> lines = new ArrayList<>();

    public GrnForm() {}

    public boolean isNew() { return id == null; }

    public boolean isForeignCurrency() {
        return currencyCode != null && !"RWF".equalsIgnoreCase(currencyCode);
    }

    // ---- accessors -------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public UUID getSupersedesDocumentId() { return supersedesDocumentId; }
    public void setSupersedesDocumentId(UUID supersedesDocumentId) { this.supersedesDocumentId = supersedesDocumentId; }

    public UUID getSupplierId() { return supplierId; }
    public void setSupplierId(UUID supplierId) { this.supplierId = supplierId; }

    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }

    public String getSupplierDeliveryNoteNo() { return supplierDeliveryNoteNo; }
    public void setSupplierDeliveryNoteNo(String value) { this.supplierDeliveryNoteNo = blankToNull(value); }

    public String getSupplierInvoiceNo() { return supplierInvoiceNo; }
    public void setSupplierInvoiceNo(String value) { this.supplierInvoiceNo = blankToNull(value); }

    public String getPurchaseOrderNo() { return purchaseOrderNo; }
    public void setPurchaseOrderNo(String value) { this.purchaseOrderNo = blankToNull(value); }

    public String getCustomsReference() { return customsReference; }
    /** A blank customs reference is no reference: the database refuses one. */
    public void setCustomsReference(String value) { this.customsReference = blankToNull(value); }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String value) {
        this.currencyCode = value == null ? null : value.trim().toUpperCase();
    }

    public BigDecimal getExchangeRate() { return exchangeRate; }
    public void setExchangeRate(BigDecimal exchangeRate) { this.exchangeRate = exchangeRate; }

    public BigDecimal getFreightRwf() { return freightRwf; }
    public void setFreightRwf(BigDecimal value) { this.freightRwf = zeroIfNull(value); }

    public BigDecimal getDutyRwf() { return dutyRwf; }
    public void setDutyRwf(BigDecimal value) { this.dutyRwf = zeroIfNull(value); }

    public BigDecimal getClearingRwf() { return clearingRwf; }
    public void setClearingRwf(BigDecimal value) { this.clearingRwf = zeroIfNull(value); }

    public BigDecimal getDemurrageRwf() { return demurrageRwf; }
    public void setDemurrageRwf(BigDecimal value) { this.demurrageRwf = zeroIfNull(value); }

    public String getReference() { return reference; }
    public void setReference(String value) { this.reference = blankToNull(value); }

    public String getNotes() { return notes; }
    public void setNotes(String value) { this.notes = blankToNull(value); }

    public List<GrnLineForm> getLines() { return lines; }
    public void setLines(List<GrnLineForm> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
