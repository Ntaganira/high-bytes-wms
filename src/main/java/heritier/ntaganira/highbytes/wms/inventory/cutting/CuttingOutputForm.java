package heritier.ntaganira.highbytes.wms.inventory.cutting;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.cutting
 * - File       : CuttingOutputForm.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One size cut from the sheet: a customer's piece or an off-cut kept, how many, width by height
 * </pre>
 */

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** A line, numbered by the server from its position. Its item is the parent-and-size item, found or made for it. */
public class CuttingOutputForm {

    @NotNull(message = "Say whether this is a piece or an off-cut")
    @Pattern(regexp = "PIECE|OFFCUT", message = "A piece or an off-cut")
    private String kind = "PIECE";

    @NotNull(message = "Enter the width")
    @DecimalMin(value = "1", message = "At least 1 mm")
    @Digits(integer = 7, fraction = 1, message = "To the tenth of a millimetre")
    private BigDecimal widthMm;

    @NotNull(message = "Enter the height")
    @DecimalMin(value = "1", message = "At least 1 mm")
    @Digits(integer = 7, fraction = 1, message = "To the tenth of a millimetre")
    private BigDecimal heightMm;

    @NotNull(message = "Enter how many")
    @DecimalMin(value = "1", message = "At least one")
    @Digits(integer = 13, fraction = 0, message = "Whole pieces")
    private BigDecimal quantity;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public BigDecimal getWidthMm() { return widthMm; }
    public void setWidthMm(BigDecimal widthMm) { this.widthMm = widthMm; }
    public BigDecimal getHeightMm() { return heightMm; }
    public void setHeightMm(BigDecimal heightMm) { this.heightMm = heightMm; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public boolean isPiece() { return "PIECE".equals(kind); }
}
