package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountLineRow.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One place on a count sheet, carrying only what its reader may see
 * </pre>
 */

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One line of a count sheet. Built by {@link CountService} for one reader in
 * one phase: a quantity the reader may not see is null in the record itself,
 * so no template can show it by mistake. While counting, the counter sees their
 * own first count and never the book; while verifying, the verifier sees their
 * own verification count and neither the book nor the first count; once the
 * verification is signed everyone with {@code count.view} sees everything.
 *
 * <p>{@code counted} and {@code verified} say whether a count exists, without
 * saying what it is: the view shows progress while the quantities are hidden.
 */
public record CountLineRow(
        UUID id,
        int lineNo,
        UUID itemId,
        String itemCode,
        String itemDescription,
        String baseUomCode,
        int uomDecimals,
        UUID binId,
        String binCode,
        boolean onSheet,
        boolean counted,
        boolean verifyRequired,
        boolean verified,
        BigDecimal bookQty,
        BigDecimal bookValue,
        BigDecimal unitCost,
        BigDecimal countedQty,
        String countedByName,
        LocalDateTime countedAt,
        BigDecimal verifiedQty,
        String verifiedByName,
        LocalDateTime verifiedAt,
        BigDecimal finalQty,
        BigDecimal varianceQty,
        String note,
        BigDecimal postedValue
) {

    public String place() {
        return binCode == null ? "no bin" : binCode;
    }

    public boolean hasVariance() {
        return varianceQty != null && varianceQty.signum() != 0;
    }

    /**
     * What the variance is worth before posting: a surplus at the line's unit cost (the value it will enter at,
     * which the database pins), a shortage at the book's average (the ledger takes its average at posting, so
     * this is an estimate). Null when the book is not readable.
     */
    public BigDecimal varianceValue() {
        if (varianceQty == null || unitCost == null || bookQty == null) return null;
        if (varianceQty.signum() > 0) return varianceQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
        if (varianceQty.signum() == 0) return BigDecimal.ZERO.setScale(2);
        BigDecimal average = bookQty.signum() > 0 ? bookValue.divide(bookQty, 4, RoundingMode.HALF_UP) : unitCost;
        return varianceQty.multiply(average).setScale(2, RoundingMode.HALF_UP);
    }

    /** Whether the first count and the verification count disagree: a count discrepancy for the report. */
    public boolean recountDiffers() {
        return countedQty != null && verifiedQty != null && countedQty.compareTo(verifiedQty) != 0;
    }
}
