package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : DailyCloseRow.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One day on the register of closes
 * </pre>
 */

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One day on the register: a close that exists, or a day on which stock moved that still needs one. The closing
 * value is the signed one, so it is null until the day is reconciled. {@code drifted} marks a signed day whose
 * ledger no longer reads as it was signed.
 */
public record DailyCloseRow(
        LocalDate businessDate,
        String status,
        boolean prepared,
        boolean returned,
        int movementCount,
        BigDecimal closingValue,
        Integer exceptionCount,
        String reconciledByName,
        String controllerName,
        LocalDateTime lockedAt,
        boolean drifted
) {}
