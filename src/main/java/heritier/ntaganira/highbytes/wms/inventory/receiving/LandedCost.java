package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : LandedCost.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Allocates freight, duty, clearing and demurrage across a receipt's lines
 * </pre>
 */

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Landed cost: what a receipt cost by the time it sat in the warehouse.
 *
 * <p>The costs that come with the goods (freight, duty, clearing, demurrage,
 * all in RWF) are spread across the lines in proportion to each line's value
 * in RWF (quantity x unit price x exchange rate). Each share is rounded to the
 * cent; the last line takes whatever the rounding left, so the shares add up
 * to the landed total exactly and nothing is lost or invented. The unit cost
 * the ledger carries is (line value + share) over the base quantity, to four
 * places.
 *
 * <p>A receipt whose lines are all priced at zero has no value to divide by;
 * the costs are then spread by base quantity, and {@link Result#byQuantity()}
 * says so, so the screen can.
 *
 * <p>Pure arithmetic, no database: the same function draws the allocation on
 * the view before posting and produces the values posted.
 */
public final class LandedCost {

    private LandedCost() {}

    public record Input(int lineNo, BigDecimal quantity, BigDecimal unitPrice, BigDecimal quantityBase) {}

    /**
     * One line's cost. {@code valueRwf} is its invoice value in RWF,
     * {@code share} the landed cost allocated to it, {@code totalRwf} the two
     * together (what the ledger will hold), {@code unitCostBase} that over the
     * base quantity.
     */
    public record Line(int lineNo, BigDecimal valueRwf, BigDecimal share, BigDecimal totalRwf,
                       BigDecimal unitCostBase) {}

    public record Result(List<Line> lines, BigDecimal invoiceRwf, BigDecimal landedRwf, BigDecimal totalRwf,
                         boolean byQuantity) {

        public Line forLine(int lineNo) {
            return lines.stream().filter(l -> l.lineNo() == lineNo).findFirst().orElseThrow();
        }
    }

    public static Result allocate(List<Input> inputs, BigDecimal exchangeRate, BigDecimal landedRwf) {
        List<BigDecimal> values = new ArrayList<>();
        BigDecimal invoice = BigDecimal.ZERO;
        for (Input in : inputs) {
            BigDecimal value = in.quantity().multiply(in.unitPrice()).multiply(exchangeRate)
                    .setScale(2, RoundingMode.HALF_UP);
            values.add(value);
            invoice = invoice.add(value);
        }
        landedRwf = landedRwf.setScale(2, RoundingMode.HALF_UP);

        boolean byQuantity = invoice.signum() == 0 && landedRwf.signum() > 0;
        List<BigDecimal> weights = new ArrayList<>();
        BigDecimal weightSum = BigDecimal.ZERO;
        for (int i = 0; i < inputs.size(); i++) {
            BigDecimal weight = byQuantity ? inputs.get(i).quantityBase() : values.get(i);
            weights.add(weight);
            weightSum = weightSum.add(weight);
        }

        List<Line> lines = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < inputs.size(); i++) {
            BigDecimal share;
            if (i == inputs.size() - 1) {
                share = landedRwf.subtract(allocated);
            } else if (weightSum.signum() == 0) {
                share = BigDecimal.ZERO.setScale(2);
            } else {
                share = landedRwf.multiply(weights.get(i)).divide(weightSum, 2, RoundingMode.HALF_UP);
            }
            allocated = allocated.add(share);
            BigDecimal total = values.get(i).add(share);
            BigDecimal unitCost = total.divide(inputs.get(i).quantityBase(), 4, RoundingMode.HALF_UP);
            lines.add(new Line(inputs.get(i).lineNo(), values.get(i), share, total, unitCost));
        }
        return new Result(lines, invoice, landedRwf, invoice.add(landedRwf), byQuantity);
    }
}
