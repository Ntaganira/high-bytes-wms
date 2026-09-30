package heritier.ntaganira.highbytes.wms.inventory.ledger;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.ledger
 * - File       : LedgerEntry.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the ledger recorded for one movement
 * </pre>
 */

import java.math.BigDecimal;
import java.time.LocalDate;

public record LedgerEntry(
        long movementId,
        BigDecimal unitCost,
        BigDecimal value,
        BigDecimal runningBalance,
        LocalDate businessDate
) {}
