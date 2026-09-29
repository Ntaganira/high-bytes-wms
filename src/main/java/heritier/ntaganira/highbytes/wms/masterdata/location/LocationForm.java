package heritier.ntaganira.highbytes.wms.masterdata.location;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.location
 * - File       : LocationForm.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The location create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class LocationForm {

    private UUID id;

    @NotNull(message = "Choose a branch")
    private UUID branchId;

    @NotBlank(message = "A code is required")
    @Size(max = 32, message = "A code is at most 32 characters")
    // The pattern accepts an empty value so a blank code reports only that it is required.
    @Pattern(regexp = "^$|^[A-Za-z0-9][A-Za-z0-9._-]*$",
             message = "Use letters, digits and . _ - only")
    private String code;

    @NotBlank(message = "A name is required")
    @Size(max = 120)
    private String name;

    @NotNull(message = "Choose a location type")
    private LocationType locationType;

    private boolean bonded;
    private boolean sellable = true;
    private boolean active = true;

    /** Set when editing, so the screen can explain what is locked and why. */
    private boolean holdsStock;

    public LocationForm() {}

    public boolean isNew() { return id == null; }

    /**
     * A bonded location must sit at a bonded branch, and a location at a
     * bonded branch that holds saleable stock must itself be bonded — duty
     * suspension is a property of where the goods physically are.
     */
    public boolean isBondedConsistentWithType() {
        return locationType != LocationType.BONDED || bonded;
    }

    // ---- accessors -------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getBranchId() { return branchId; }
    public void setBranchId(UUID branchId) { this.branchId = branchId; }

    public String getCode() { return code; }
    public void setCode(String code) {
        this.code = code == null ? null : code.trim().toUpperCase();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.trim(); }

    public LocationType getLocationType() { return locationType; }
    public void setLocationType(LocationType locationType) {
        this.locationType = locationType;
        if (locationType != null) {
            // Sensible defaults the user can still override.
            if (locationType == LocationType.BONDED) this.bonded = true;
            if (!locationType.sellable()) this.sellable = false;
        }
    }

    public boolean isBonded() { return bonded; }
    public void setBonded(boolean bonded) { this.bonded = bonded; }

    public boolean isSellable() { return sellable; }
    public void setSellable(boolean sellable) { this.sellable = sellable; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isHoldsStock() { return holdsStock; }
    public void setHoldsStock(boolean holdsStock) { this.holdsStock = holdsStock; }
}
