package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : LandedCostTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The landed-cost allocation adds up exactly, whatever the rounding
 * </pre>
 */

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LandedCostTest {

    private static LandedCost.Input in(int no, String qty, String price, String base) {
        return new LandedCost.Input(no, new BigDecimal(qty), new BigDecimal(price), new BigDecimal(base));
    }

    @Test
    void sharesFollowValueAndTheLastLineTakesTheRemainder() {
        var result = LandedCost.allocate(
                List.of(in(1, "4", "100000", "50"), in(2, "20", "3000", "20")),
                BigDecimal.ONE, new BigDecimal("50000"));

        assertThat(result.invoiceRwf()).isEqualByComparingTo("460000.00");
        assertThat(result.forLine(1).share()).isEqualByComparingTo("43478.26");
        assertThat(result.forLine(2).share()).isEqualByComparingTo("6521.74");
        assertThat(result.totalRwf()).isEqualByComparingTo("510000.00");
        assertThat(result.forLine(1).unitCostBase()).isEqualByComparingTo("8869.5652");
        assertThat(result.byQuantity()).isFalse();
    }

    @Test
    void theSharesAlwaysSumToTheLandedCostExactly() {
        // Three equal lines and an amount that does not divide by three.
        var result = LandedCost.allocate(
                List.of(in(1, "1", "100", "1"), in(2, "1", "100", "1"), in(3, "1", "100", "1")),
                BigDecimal.ONE, new BigDecimal("100.00"));

        BigDecimal shares = result.lines().stream().map(LandedCost.Line::share)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(shares).isEqualByComparingTo("100.00");
        assertThat(result.forLine(1).share()).isEqualByComparingTo("33.33");
        assertThat(result.forLine(3).share()).isEqualByComparingTo("33.34");
    }

    @Test
    void aForeignInvoiceIsValuedInRwfAtTheRate() {
        var result = LandedCost.allocate(
                List.of(in(1, "10", "12.5", "10")), new BigDecimal("1350.5"), new BigDecimal("1000"));

        // 10 x 12.5 x 1350.5 = 168,812.50
        assertThat(result.forLine(1).valueRwf()).isEqualByComparingTo("168812.50");
        assertThat(result.forLine(1).totalRwf()).isEqualByComparingTo("169812.50");
        assertThat(result.forLine(1).unitCostBase()).isEqualByComparingTo("16981.2500");
    }

    @Test
    void withNoLandedCostEachLineIsItsInvoiceValue() {
        var result = LandedCost.allocate(
                List.of(in(1, "3", "10.10", "3")), BigDecimal.ONE, BigDecimal.ZERO);
        assertThat(result.forLine(1).share()).isEqualByComparingTo("0.00");
        assertThat(result.totalRwf()).isEqualByComparingTo("30.30");
    }

    @Test
    void unpricedLinesShareTheCostByBaseQuantityAndSayso() {
        var result = LandedCost.allocate(
                List.of(in(1, "1", "0", "10"), in(2, "1", "0", "30")), BigDecimal.ONE, new BigDecimal("400"));
        assertThat(result.byQuantity()).isTrue();
        assertThat(result.forLine(1).share()).isEqualByComparingTo("100.00");
        assertThat(result.forLine(2).share()).isEqualByComparingTo("300.00");
    }
}
