package heritier.ntaganira.highbytes.wms.masterdata.item;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.item
 * - File       : ItemForm.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The item create/edit form and its validation rules
 * </pre>
 */

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The create/edit form.
 *
 * <p>A mutable bean rather than a record because Thymeleaf form binding needs
 * setters and a no-argument constructor.
 */
public class ItemForm {

    private UUID id;

    @NotBlank(message = "An item code is required")
    @Size(max = 40, message = "An item code is at most 40 characters")
    // The pattern accepts an empty value so a blank code reports only that it is required.
    @Pattern(regexp = "^$|^[A-Za-z0-9][A-Za-z0-9._/-]*$",
             message = "Use letters, digits and . _ / - only, starting with a letter or digit")
    private String itemCode;

    @NotBlank(message = "A description is required")
    @Size(max = 240, message = "A description is at most 240 characters")
    private String description;

    private UUID categoryId;

    @NotNull(message = "Choose a product type")
    private ProductType productType;

    @Size(max = 40)
    private String colour;

    @DecimalMin(value = "0.01", message = "Thickness must be greater than zero")
    private BigDecimal thicknessMm;

    @DecimalMin(value = "0.1", message = "Width must be greater than zero")
    private BigDecimal widthMm;

    @DecimalMin(value = "0.1", message = "Height must be greater than zero")
    private BigDecimal heightMm;

    @NotNull(message = "Choose a base unit of measure")
    private UUID baseUomId;

    @DecimalMin(value = "0", message = "A reorder level cannot be negative")
    private BigDecimal reorderLevel;

    @DecimalMin(value = "0", message = "A maximum stock level cannot be negative")
    private BigDecimal maxStockLevel;

    private boolean active = true;

    /** Set when editing, so the screen can say what changing this will affect. */
    private boolean transacted;

    public ItemForm() {}

    /** Glass must carry a thickness: it is verified at the gate on every movement. */
    public boolean isThicknessPresentWhenGlass() {
        return productType != ProductType.GLASS || thicknessMm != null;
    }

    /** A maximum below the reorder level would order stock the bin cannot hold. */
    public boolean isStockLevelsConsistent() {
        return reorderLevel == null || maxStockLevel == null
                || maxStockLevel.compareTo(reorderLevel) >= 0;
    }

    public boolean isNew() { return id == null; }

    // ---- accessors -------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) {
        this.itemCode = itemCode == null ? null : itemCode.trim().toUpperCase();
    }

    public String getDescription() { return description; }
    public void setDescription(String description) {
        this.description = description == null ? null : description.trim();
    }

    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }

    public ProductType getProductType() { return productType; }
    public void setProductType(ProductType productType) { this.productType = productType; }

    public String getColour() { return colour; }
    public void setColour(String colour) {
        this.colour = (colour == null || colour.isBlank()) ? null : colour.trim();
    }

    public BigDecimal getThicknessMm() { return thicknessMm; }
    public void setThicknessMm(BigDecimal thicknessMm) { this.thicknessMm = thicknessMm; }

    public BigDecimal getWidthMm() { return widthMm; }
    public void setWidthMm(BigDecimal widthMm) { this.widthMm = widthMm; }

    public BigDecimal getHeightMm() { return heightMm; }
    public void setHeightMm(BigDecimal heightMm) { this.heightMm = heightMm; }

    public UUID getBaseUomId() { return baseUomId; }
    public void setBaseUomId(UUID baseUomId) { this.baseUomId = baseUomId; }

    public BigDecimal getReorderLevel() { return reorderLevel; }
    public void setReorderLevel(BigDecimal reorderLevel) { this.reorderLevel = reorderLevel; }

    public BigDecimal getMaxStockLevel() { return maxStockLevel; }
    public void setMaxStockLevel(BigDecimal maxStockLevel) { this.maxStockLevel = maxStockLevel; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isTransacted() { return transacted; }
    public void setTransacted(boolean transacted) { this.transacted = transacted; }
}
