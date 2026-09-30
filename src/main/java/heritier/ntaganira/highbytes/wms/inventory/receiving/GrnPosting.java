package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : GrnPosting.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of the ticket and ledger rows a posted receipt produced
 * </pre>
 */

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The transaction ticket a posted receipt raised and the movements it wrote. */
public record GrnPosting(UUID ticketId, String ticketSerial, List<Movement> movements) {

    public record Movement(long id, int lineNo, String itemCode, BigDecimal quantityBase, BigDecimal unitCost,
                           BigDecimal value, BigDecimal runningBalance, LocalDate businessDate) {}

    public BigDecimal totalValue() {
        return movements.stream().map(Movement::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
