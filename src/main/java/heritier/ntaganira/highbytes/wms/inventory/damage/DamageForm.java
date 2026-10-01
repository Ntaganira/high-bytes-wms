package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageForm.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The return and damage report create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The form. No document date (the database dates the report today, Kigali, because
 * that date decides the chain), and for a transit loss no location (the transfer's
 * transit location) nor for a customer return (the delivering branch's quarantine
 * location): the database sets both. The kind is chosen first and never changes.
 */
public class DamageForm {

    private UUID id;
    private Integer version;

    @NotNull(message = "Choose what kind of report this is")
    private DamageKind kind;

    @NotBlank(message = "Choose a reason code")
    private String reasonCode;

    @NotBlank(message = "Say what happened")
    @Size(max = 400, message = "At most 400 characters")
    private String reason;

    private UUID fromLocationId;
    private UUID toLocationId;
    private UUID transferId;
    private UUID deliveryNoteId;

    @Size(max = 80, message = "At most 80 characters")
    private String customsReference;

    @Size(max = 120, message = "At most 120 characters")
    private String reference;

    @Size(max = 1000, message = "At most 1000 characters")
    private String notes;

    @Valid
    @Size(min = 1, message = "A report needs at least one line")
    private List<DamageLineForm> lines = new ArrayList<>();

    public boolean isNew() { return id == null; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public DamageKind getKind() { return kind; }
    public void setKind(DamageKind kind) { this.kind = kind; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = blankToNull(reasonCode); }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = blankToNull(reason); }

    public UUID getFromLocationId() { return fromLocationId; }
    public void setFromLocationId(UUID id) { this.fromLocationId = id; }

    public UUID getToLocationId() { return toLocationId; }
    public void setToLocationId(UUID id) { this.toLocationId = id; }

    public UUID getTransferId() { return transferId; }
    public void setTransferId(UUID id) { this.transferId = id; }

    public UUID getDeliveryNoteId() { return deliveryNoteId; }
    public void setDeliveryNoteId(UUID id) { this.deliveryNoteId = id; }

    public String getCustomsReference() { return customsReference; }
    /** A blank customs reference is no reference: the database refuses one. */
    public void setCustomsReference(String v) { this.customsReference = blankToNull(v); }

    public String getReference() { return reference; }
    public void setReference(String v) { this.reference = blankToNull(v); }

    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = blankToNull(v); }

    public List<DamageLineForm> getLines() { return lines; }
    public void setLines(List<DamageLineForm> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
