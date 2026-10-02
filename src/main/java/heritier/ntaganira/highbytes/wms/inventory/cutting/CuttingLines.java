package heritier.ntaganira.highbytes.wms.inventory.cutting;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.cutting
 * - File       : CuttingLines.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A cutting order's lines as read: the sheets cut, what is cut from them, and the areas between
 * </pre>
 */

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** The read shapes of a cutting order's lines. */
public final class CuttingLines {

    private CuttingLines() {}

    /** A sheet line: whole sheets of one glass item, from a bin or unbinned. */
    public record Sheet(UUID id, int lineNo, UUID itemId, String itemCode, String description,
                        BigDecimal widthMm, BigDecimal heightMm, BigDecimal thicknessMm,
                        UUID binId, String binCode, BigDecimal quantity, UUID baseUomId) {

        /** One sheet's area in square metres. */
        public BigDecimal sheetAreaM2() {
            return widthMm.multiply(heightMm).divide(MM2_PER_M2, 3, RoundingMode.HALF_UP);
        }
    }

    /** A piece for the customer or an off-cut kept, and the parent-and-size item it is. */
    public record Output(UUID id, int lineNo, String kind, BigDecimal widthMm, BigDecimal heightMm,
                         BigDecimal quantity, UUID itemId, String itemCode, String description,
                         BigDecimal thicknessMm, UUID baseUomId, String baseUomCode) {

        public boolean piece() { return "PIECE".equals(kind); }

        /** The line's area in square metres: width by height by how many. */
        public BigDecimal areaM2() {
            return widthMm.multiply(heightMm).multiply(quantity).divide(MM2_PER_M2, 3, RoundingMode.HALF_UP);
        }
    }

    /** The order's areas in square metres: the sheets', what is cut from them, and the waste between. */
    public record Areas(BigDecimal sheetM2, BigDecimal outputM2, BigDecimal wasteM2) {

        static Areas ofMm2(BigDecimal sheet, BigDecimal output) {
            return new Areas(m2(sheet), m2(output), m2(sheet.subtract(output)));
        }

        /** The share of the sheets that is waste, in percent, or null with no sheet. */
        public BigDecimal wastePercent() {
            if (sheetM2.signum() == 0) return null;
            return wasteM2.multiply(BigDecimal.valueOf(100)).divide(sheetM2, 1, RoundingMode.HALF_UP);
        }

        public boolean overCut() { return wasteM2.signum() < 0; }
    }

    static final BigDecimal MM2_PER_M2 = BigDecimal.valueOf(1_000_000);

    static BigDecimal m2(BigDecimal mm2) {
        return mm2.divide(MM2_PER_M2, 3, RoundingMode.HALF_UP);
    }
}
