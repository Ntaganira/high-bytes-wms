package heritier.ntaganira.highbytes.wms.reporting.close;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.reporting.close
 * - File       : DailyClose.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : One branch's business day as its close records it: state, signed figures, signatures
 * </pre>
 */

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * The close of one branch's business day. Before anyone prepares it there is no row: it reads OPEN and
 * {@code prepared} is false. The signed figures are those the database wrote when Finance reconciled it; until then
 * they are null and the day is read live.
 */
public record DailyClose(
        UUID id,
        UUID branchId,
        String branchCode,
        String branchName,
        LocalDate businessDate,
        String status,
        boolean prepared,
        CloseFigures signed,
        Integer exceptionCount,
        String exceptionsNote,
        UUID reconciledBy,
        String reconciledByName,
        LocalDateTime reconciledAt,
        String controllerName,
        LocalDateTime controllerSignedAt,
        LocalDateTime lockedAt,
        String returnedByName,
        LocalDateTime returnedAt,
        String returnReason
) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    public boolean open()       { return "OPEN".equals(status); }
    public boolean reconciled() { return "RECONCILED".equals(status); }
    public boolean locked()     { return "LOCKED".equals(status); }

    /** Returned by the Internal Controller and not reconciled since. */
    public boolean returned()   { return open() && returnReason != null; }

    public String day() {
        return day(businessDate);
    }

    /** A business date as the screens and the trail write it: 30 Sep 2026. */
    public static String day(LocalDate date) {
        return date.format(DAY);
    }

    /** How the audit trail names it. */
    public String label() {
        return label(branchCode, businessDate);
    }

    static String label(String branchCode, LocalDate date) {
        return "Daily close · " + branchCode + " · " + date.format(DAY);
    }
}
