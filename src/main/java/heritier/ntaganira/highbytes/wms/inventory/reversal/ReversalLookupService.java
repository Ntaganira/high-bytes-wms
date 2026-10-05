package heritier.ntaganira.highbytes.wms.inventory.reversal;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.reversal
 * - File       : ReversalLookupService.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : What the reversal form offers to reverse, behind the reversal module's own right
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

/**
 * The documents a reversal may be raised against, asked under
 * {@code reversal.view}: each module asks its own pickers under its own right,
 * so the reversal form never depends on holding a receiving or count right.
 *
 * <p>Offered: posted documents of the types V21 reverses (receipts, opening
 * balances, counts, and write-offs or quarantine releases), at the branch,
 * that moved stock and are not already being reversed. Left out: those the
 * viewer raised or posted, since V21 refuses them as the reversal's raiser.
 * The database judges every one again when the reversal is written.
 */
@Service
@Transactional(readOnly = true)
public class ReversalLookupService {

    /** Enough to find a recent mistake; an older one is reached from its own page. */
    static final int LIMIT = 200;

    private final JdbcClient jdbc;

    public ReversalLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasAuthority('reversal.view')")
    public List<ReversalCandidate> candidates(UUID branchId, UUID viewer) {
        return jdbc.sql("""
                SELECT d.id, d.serial_no, dt.code, dt.name, d.posted_at
                  FROM document d
                  JOIN document_type dt ON dt.id = d.document_type_id
             LEFT JOIN damage_report r  ON r.document_id = d.id
                 WHERE d.branch_id = :branch
                   AND d.status = 'POSTED'
                   AND dt.code IN ('GRN', 'OPB', 'CNT', 'DMG')
                   AND (dt.code <> 'DMG' OR r.kind IN ('WRITE_OFF', 'QUARANTINE_RELEASE'))
                   AND d.created_by <> :viewer AND d.posted_by <> :viewer
                   AND EXISTS (SELECT 1 FROM transaction_ticket t
                                 JOIN stock_movement m ON m.document_id = t.document_id
                                WHERE t.source_document_id = d.id AND m.reverses_movement_id IS NULL)
                   AND NOT EXISTS (SELECT 1 FROM document x
                                    WHERE x.reverses_document_id = d.id
                                      AND x.status NOT IN ('CANCELLED', 'REJECTED'))
                 ORDER BY d.posted_at DESC
                 LIMIT :limit
                """)
                .param("branch", branchId, Types.OTHER)
                .param("viewer", viewer, Types.OTHER)
                .param("limit", LIMIT)
                .query((rs, n) -> new ReversalCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("serial_no"),
                        rs.getString("code"),
                        rs.getString("name"),
                        KigaliTime.read(rs, "posted_at")))
                .list();
    }
}
