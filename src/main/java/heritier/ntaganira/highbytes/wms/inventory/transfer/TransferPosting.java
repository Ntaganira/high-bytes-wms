package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferPosting.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Read model of the tickets and ledger rows a posted transfer or receipt produced
 * </pre>
 */

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The two tickets of a posting (out and in) and the four-leg movements they carry. */
public record TransferPosting(List<Movement> movements) {

    public record Movement(long id, String ticketSerial, String movementType, int lineNo, String itemCode,
                           String locationCode, String binCode, String direction, BigDecimal quantityBase,
                           BigDecimal unitCost, BigDecimal value, LocalDate businessDate) {}
}
