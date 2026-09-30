package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DaoForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The delivery authorization create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The form. No document date: an authorization is dated the day it is
 * created, in Kigali, by the database, because that date decides which
 * approval chain applies. {@code version} is the optimistic-lock version the
 * form was opened at.
 */
public class DaoForm {

    private UUID id;
    private Integer version;
    private UUID supersedesDocumentId;

    @NotNull(message = "Choose the customer")
    private UUID customerId;

    @NotNull(message = "Choose the location the goods leave from")
    private UUID locationId;

    @Size(max = 60, message = "At most 60 characters")
    private String customerReference;

    @Size(max = 300, message = "At most 300 characters")
    private String deliveryAddress;

    @Size(max = 80, message = "At most 80 characters")
    private String customsReference;

    @Size(max = 120, message = "At most 120 characters")
    private String reference;

    @Size(max = 1000, message = "At most 1000 characters")
    private String notes;

    @Valid
    @Size(min = 1, message = "An authorization needs at least one line")
    private List<DaoLineForm> lines = new ArrayList<>();

    public boolean isNew() { return id == null; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public UUID getSupersedesDocumentId() { return supersedesDocumentId; }
    public void setSupersedesDocumentId(UUID id) { this.supersedesDocumentId = id; }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }

    public String getCustomerReference() { return customerReference; }
    public void setCustomerReference(String v) { this.customerReference = blankToNull(v); }

    public String getDeliveryAddress() { return deliveryAddress; }
    public void setDeliveryAddress(String v) { this.deliveryAddress = blankToNull(v); }

    public String getCustomsReference() { return customsReference; }
    /** A blank customs reference is no reference: the database refuses one. */
    public void setCustomsReference(String v) { this.customsReference = blankToNull(v); }

    public String getReference() { return reference; }
    public void setReference(String v) { this.reference = blankToNull(v); }

    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = blankToNull(v); }

    public List<DaoLineForm> getLines() { return lines; }
    public void setLines(List<DaoLineForm> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
