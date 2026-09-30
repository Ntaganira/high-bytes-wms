package heritier.ntaganira.highbytes.wms.common.db;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.db
 * - File       : KigaliTime.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Reads timestamps as Kigali wall-clock time for display
 * </pre>
 */

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Timestamps are stored as instants; people read them in Kigali. Reading them
 * here, whatever zone the JVM or the database session runs in, keeps a
 * signature's time the same on every screen.
 */
public final class KigaliTime {

    public static final ZoneId ZONE = ZoneId.of("Africa/Kigali");

    private KigaliTime() {}

    /** The column as Kigali local time; null when the column is null. */
    public static LocalDateTime read(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.atZoneSameInstant(ZONE).toLocalDateTime();
    }
}
