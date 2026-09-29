package heritier.ntaganira.highbytes.wms.masterdata.item;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.item
 * - File       : ItemRow.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : An item as the screens read it, with its total on hand
 * </pre>
 */

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * An item as the screens read it.
 *
 * <p>A read model rather than a JPA entity: the item master is queried far
 * more than it is written, almost always with its category and unit joined,
 * and this keeps those reads as one statement.
 */
public record ItemRow(
        UUID id,
        String itemCode,
        String description,
        String categoryName,
        ProductType productType,
        String colour,
        BigDecimal thicknessMm,
        BigDecimal widthMm,
        BigDecimal heightMm,
        String baseUomCode,
        boolean remnant,
        String cutFromItemCode,
        BigDecimal reorderLevel,
        BigDecimal maxStockLevel,
        boolean active,
        BigDecimal totalOnHand
) {

    /**
     * The specification a warehouse officer reads at a glance:
     * "Clear float · 6mm · 2140×3300".
     */
    public String specification() {
        if (productType != ProductType.GLASS) {
            return colour == null ? "" : colour;
        }
        var parts = new StringBuilder();
        if (colour != null && !colour.isBlank()) parts.append(colour);
        if (thicknessMm != null) {
            if (!parts.isEmpty()) parts.append(" · ");
            parts.append(trim(thicknessMm)).append("mm");
        }
        if (widthMm != null && heightMm != null) {
            if (!parts.isEmpty()) parts.append(" · ");
            parts.append(trim(widthMm)).append("×").append(trim(heightMm));
        }
        return parts.toString();
    }

    /** Area of one sheet in square metres, for the cutting module's yield maths. */
    public BigDecimal sheetAreaSqm() {
        if (widthMm == null || heightMm == null) return null;
        return widthMm.multiply(heightMm)
                .divide(new BigDecimal("1000000"), 4, RoundingMode.HALF_UP);
    }

    public boolean belowReorder() {
        return reorderLevel != null && totalOnHand != null
                && totalOnHand.compareTo(reorderLevel) < 0;
    }

    private static String trim(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
