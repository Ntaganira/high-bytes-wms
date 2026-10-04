package heritier.ntaganira.highbytes.wms.inventory.opening;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.opening
 * - File       : OpeningForm.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The opening stock balance form: the place, the cutover date, what it was taken from, and the lines
 * </pre>
 */

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the sheet says. The document's own date is the day it is keyed and is
 * the database's to set; {@code asAtDate} is the day the figures were struck
 * in QuickBooks, which the person keying it must say.
 *
 * <p>{@code customsReference} is required when the place is bonded. The form
 * cannot know that on its own — the service asks the database — so it is not
 * annotated here and is checked where the location is known.
 */
public class OpeningForm {

    private UUID id;

    /** The version the form was opened from, for optimistic locking on edit. */
    private Integer version;

    @NotNull(message = "Choose the place this stock is in")
    private UUID locationId;

    @NotNull(message = "Enter the date the figures were struck")
    private LocalDate asAtDate;

    @NotBlank(message = "Name the system the figures came from")
    @Size(max = 40, message = "At most 40 characters")
    private String sourceSystem = "QuickBooks";

    @NotBlank(message = "Say what these figures were taken from: the report, when it was run, and who struck it")
    @Size(max = 400, message = "At most 400 characters")
    private String basisNote;

    @Size(max = 80, message = "At most 80 characters")
    private String customsReference;

    @Size(max = 120, message = "At most 120 characters")
    private String reference;

    @Size(max = 2000, message = "At most 2000 characters")
    private String notes;

    @Valid
    private List<OpeningLineForm> lines = new ArrayList<>();

    public boolean isNew() { return id == null; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }

    public LocalDate getAsAtDate() { return asAtDate; }
    public void setAsAtDate(LocalDate asAtDate) { this.asAtDate = asAtDate; }

    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }

    public String getBasisNote() { return basisNote; }
    public void setBasisNote(String basisNote) { this.basisNote = basisNote; }

    public String getCustomsReference() { return customsReference; }
    public void setCustomsReference(String customsReference) { this.customsReference = customsReference; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public List<OpeningLineForm> getLines() { return lines; }
    public void setLines(List<OpeningLineForm> lines) { this.lines = lines; }
}
