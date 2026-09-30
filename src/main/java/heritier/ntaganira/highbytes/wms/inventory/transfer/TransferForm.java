package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferForm.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The transfer create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The form. No document date (the database dates the transfer today, Kigali,
 * because that date decides the chain) and no transit location (the database
 * picks the source branch's one).
 */
public class TransferForm {

    private UUID id;
    private Integer version;
    private UUID supersedesDocumentId;

    @NotNull(message = "Choose the location the goods leave from")
    private UUID fromLocationId;

    @NotNull(message = "Choose where the goods are going")
    private UUID toLocationId;

    @Size(max = 80, message = "At most 80 characters")
    private String customsReference;

    @Size(max = 400, message = "At most 400 characters")
    private String note;

    @Size(max = 120, message = "At most 120 characters")
    private String reference;

    @Size(max = 1000, message = "At most 1000 characters")
    private String notes;

    @Valid
    @Size(min = 1, message = "A transfer needs at least one line")
    private List<TransferLineForm> lines = new ArrayList<>();

    public boolean isNew() { return id == null; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public UUID getSupersedesDocumentId() { return supersedesDocumentId; }
    public void setSupersedesDocumentId(UUID id) { this.supersedesDocumentId = id; }

    public UUID getFromLocationId() { return fromLocationId; }
    public void setFromLocationId(UUID id) { this.fromLocationId = id; }

    public UUID getToLocationId() { return toLocationId; }
    public void setToLocationId(UUID id) { this.toLocationId = id; }

    public String getCustomsReference() { return customsReference; }
    /** A blank customs reference is no reference: the database refuses one. */
    public void setCustomsReference(String v) { this.customsReference = blankToNull(v); }

    public String getNote() { return note; }
    public void setNote(String v) { this.note = blankToNull(v); }

    public String getReference() { return reference; }
    public void setReference(String v) { this.reference = blankToNull(v); }

    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = blankToNull(v); }

    public List<TransferLineForm> getLines() { return lines; }
    public void setLines(List<TransferLineForm> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
