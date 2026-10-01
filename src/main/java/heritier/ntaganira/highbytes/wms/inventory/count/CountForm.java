package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountForm.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Opening a count: the location, the scope, and for a cycle count the items
 * </pre>
 */

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What opening a count asks for. No lines: the database writes the sheet from
 * the ledger (every place at the location for a full count, every place of each
 * chosen item for a cycle count), and no quantities, which nobody enters until
 * the count is open.
 */
public class CountForm {

    @NotNull(message = "Choose the location to count")
    private UUID locationId;

    @NotNull(message = "Choose a full count or a cycle count")
    private CountScope scope = CountScope.FULL;

    /** A cycle count's items. Ignored for a full count. */
    private List<UUID> itemIds = new ArrayList<>();

    @Size(max = 80, message = "At most 80 characters")
    private String customsReference;

    @Size(max = 120, message = "At most 120 characters")
    private String reference;

    @Size(max = 1000, message = "At most 1000 characters")
    private String notes;

    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }

    public CountScope getScope() { return scope; }
    public void setScope(CountScope scope) { this.scope = scope; }

    public List<UUID> getItemIds() { return itemIds; }
    public void setItemIds(List<UUID> itemIds) { this.itemIds = itemIds == null ? new ArrayList<>() : itemIds; }

    public String getCustomsReference() { return customsReference; }
    public void setCustomsReference(String customsReference) {
        this.customsReference = customsReference == null || customsReference.isBlank() ? null : customsReference.trim();
    }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
