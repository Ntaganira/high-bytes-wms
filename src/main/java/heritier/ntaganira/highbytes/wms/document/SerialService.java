package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : SerialService.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Issues the next document serial per type, branch and Kigali year, without collision
 * </pre>
 */

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.UUID;

/**
 * Serial numbers: {@code <TYPE>-<BRANCH>-<YEAR>-<NNNN>}, e.g.
 * {@code GRN-KGL-2026-0412} (invariant 6: a serial is never reused).
 *
 * <p>The first document of a year creates its sequence row
 * ({@code ON CONFLICT DO NOTHING}, so two requests racing for it both
 * succeed); every document then takes its number with a single
 * {@code UPDATE ... RETURNING}, which holds the row lock until the caller's
 * transaction ends. Concurrent requests queue for the number instead of
 * colliding on it.
 *
 * <p>The number is issued inside the transaction that opens the document. If
 * that transaction rolls back, the increment rolls back with it, so a serial
 * is consumed only by a document that exists, and a gap on the register means
 * something was removed, which is reportable.
 *
 * <p>The year is the Kigali year, from the database's own {@code kigali_today()}.
 */
@Service
public class SerialService {

    private final JdbcClient jdbc;

    public SerialService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String typeCode, UUID branchId) {
        int year = jdbc.sql("SELECT EXTRACT(YEAR FROM kigali_today())::int")
                .query(Integer.class).single();

        jdbc.sql("""
                INSERT INTO serial_sequence (document_type_id, branch_id, year, prefix)
                SELECT dt.id, b.id, :year, dt.code || '-' || b.code || '-' || :year::text
                  FROM document_type dt, branch b
                 WHERE dt.code = :type AND b.id = :branch
                ON CONFLICT (document_type_id, branch_id, year) DO NOTHING
                """)
                .param("year", (short) year)
                .param("type", typeCode)
                .param("branch", branchId, Types.OTHER)
                .update();

        var issued = jdbc.sql("""
                UPDATE serial_sequence ss
                   SET next_number = ss.next_number + 1
                  FROM document_type dt
                 WHERE dt.id = ss.document_type_id AND dt.code = :type
                   AND ss.branch_id = :branch AND ss.year = :year
             RETURNING ss.prefix, ss.next_number - 1 AS issued
                """)
                .param("type", typeCode)
                .param("branch", branchId, Types.OTHER)
                .param("year", (short) year)
                .query((rs, n) -> String.format("%s-%04d", rs.getString("prefix"), rs.getLong("issued")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "No serial sequence could be created for " + typeCode + " at branch " + branchId));
        return issued;
    }
}
