package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : DayMovement.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One ledger movement of the day being closed, with the documents behind it
 * </pre>
 */

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** One movement dated the day being closed: what moved, where, which ticket and document it answers to, who posted it. */
public record DayMovement(
        long id,
        String ticketSerial,
        String movementType,
        UUID sourceId,
        String sourceSerial,
        String itemCode,
        String itemDescription,
        String locationCode,
        String binCode,
        String direction,
        BigDecimal quantity,
        String uomCode,
        BigDecimal value,
        String postedByName,
        UUID postedBy,
        LocalDateTime movementAt
) {

    /** The value as it counts in the day: IN adds, OUT takes away. */
    public BigDecimal signedValue() {
        return "OUT".equals(direction) ? value.negate() : value;
    }
}
