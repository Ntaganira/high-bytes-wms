package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : DocumentListService.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The register of every document the reader may read, of every type, at every branch
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every document, of every type the engine serves, that the reader may read: a document is listed only where the
 * reader holds its type's view right at the document's own branch ({@code user_holds_right_at}, V16), so the
 * register reaches as far as the rights do and no further. A serial cancelled stays listed: a gap in a sequence
 * is an incident, and its reason is the explanation.
 *
 * <p>Whose register it is comes from the signed-in user, never from a parameter. A row carries what the document
 * is and where it stands, never its content, so a blind count shows nothing of its book here.
 */
@Service
@Transactional(readOnly = true)
public class DocumentListService {

    /** The statuses a document passes through (V3). */
    public static final List<String> STATUSES = List.of("DRAFT", "PENDING", "APPROVED", "POSTED", "REJECTED", "CANCELLED");

    /** Each type the engine serves and the right that reads it, built from {@link DocumentKind}. */
    private static final String VIEW_RIGHTS = Arrays.stream(DocumentKind.values())
            .map(k -> "('%s', '%s')".formatted(k.code(), k.right("view")))
            .collect(Collectors.joining(", "));

    /** The register's filters; {@code before} is the last document of the page already shown. */
    public record Query(DocumentKind kind, String status, UUID branchId, LocalDate from, LocalDate to, String text,
                        boolean mine, UUID before) {

        public static Query all() {
            return new Query(null, null, null, null, null, null, false, null);
        }

        public static Query text(String text) {
            return new Query(null, null, null, null, null, text, false, null);
        }
    }

    private static final String LIST = """
            WITH view_right (type_code, view_right) AS (VALUES %s)
            SELECT d.id, d.serial_no, dt.code AS type_code, dt.name AS type_name, d.status,
                   d.branch_id, b.code AS branch_code, b.name AS branch_name, d.document_date, d.reference,
                   cu.full_name AS raised_by, d.posted_at, pu.full_name AS posted_by, d.cancel_reason,
                   vr.view_right, nx.sequence_no AS step, r.name AS awaiting_role,
                   (SELECT COUNT(*) FROM workflow_step s WHERE s.workflow_definition_id = d.workflow_definition_id) AS steps
              FROM document d
              JOIN document_type dt   ON dt.id = d.document_type_id
              JOIN view_right vr      ON vr.type_code = dt.code
              JOIN branch b           ON b.id = d.branch_id
              JOIN app_user cu        ON cu.id = d.created_by
         LEFT JOIN app_user pu        ON pu.id = d.posted_by
         LEFT JOIN LATERAL (
                   SELECT ws.sequence_no, ws.required_role_id
                     FROM workflow_step ws
                    WHERE ws.workflow_definition_id = d.workflow_definition_id
                      AND d.status = 'PENDING'
                      AND NOT EXISTS (SELECT 1 FROM document_approval da
                                       WHERE da.document_id = d.id AND da.workflow_step_id = ws.id)
                    ORDER BY ws.sequence_no
                    LIMIT 1) nx ON TRUE
         LEFT JOIN role r             ON r.id = nx.required_role_id
             WHERE user_holds_right_at(:user, vr.view_right, d.branch_id)
               AND (:type::text IS NULL OR dt.code = :type::text)
               AND (:status::text IS NULL OR d.status = :status::text)
               AND (:branch::uuid IS NULL OR d.branch_id = :branch::uuid)
               AND (:from::date IS NULL OR d.document_date >= :from::date)
               AND (:to::date IS NULL OR d.document_date <= :to::date)
               AND (:text::text IS NULL OR d.serial_no ILIKE :text::text ESCAPE '\\'
                                        OR d.reference ILIKE :text::text ESCAPE '\\')
               AND (NOT :mine OR d.created_by = :user)
               AND (:serial::text IS NULL OR lower(d.serial_no) = lower(:serial::text))
               AND (:before::uuid IS NULL
                    OR (d.created_at, d.id) < (SELECT x.created_at, x.id FROM document x WHERE x.id = :before::uuid))
             ORDER BY d.created_at DESC, d.id DESC
             LIMIT :limit
            """.formatted(VIEW_RIGHTS);

    private final JdbcClient jdbc;

    public DocumentListService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The documents the signed-in user may read, newest first. Empty when nobody is signed in. */
    @PreAuthorize("isAuthenticated()")
    public List<DocumentRow> list(Query q, int limit) {
        AppUserDetails user = CurrentUser.get().orElse(null);
        if (user == null) return List.of();
        String status = q.status() != null && STATUSES.contains(q.status()) ? q.status() : null;
        return jdbc.sql(LIST)
                .param("user", user.id(), Types.OTHER)
                .param("type", q.kind() == null ? null : q.kind().code(), Types.VARCHAR)
                .param("status", status, Types.VARCHAR)
                .param("branch", q.branchId(), Types.OTHER)
                .param("from", q.from(), Types.DATE)
                .param("to", q.to(), Types.DATE)
                .param("text", like(q.text()), Types.VARCHAR)
                .param("mine", q.mine())
                .param("serial", null, Types.VARCHAR)
                .param("before", q.before(), Types.OTHER)
                .param("limit", Math.max(1, limit))
                .query((rs, n) -> map(rs, user.permissions()))
                .list();
    }

    /** The one document with exactly this serial, ignoring case, when the signed-in user may read it. */
    @PreAuthorize("isAuthenticated()")
    public java.util.Optional<DocumentRow> bySerial(String serial) {
        AppUserDetails user = CurrentUser.get().orElse(null);
        if (user == null || serial == null || serial.isBlank()) return java.util.Optional.empty();
        return jdbc.sql(LIST)
                .param("user", user.id(), Types.OTHER)
                .param("type", null, Types.VARCHAR)
                .param("status", null, Types.VARCHAR)
                .param("branch", null, Types.OTHER)
                .param("from", null, Types.DATE)
                .param("to", null, Types.DATE)
                .param("text", null, Types.VARCHAR)
                .param("mine", false)
                .param("serial", serial.trim(), Types.VARCHAR)
                .param("before", null, Types.OTHER)
                .param("limit", 1)
                .query((rs, n) -> map(rs, user.permissions()))
                .optional();
    }

    private static DocumentRow map(ResultSet rs, Set<String> rightsHere) throws SQLException {
        return new DocumentRow(
                rs.getObject("id", UUID.class),
                rs.getString("serial_no"),
                rs.getString("type_code"),
                rs.getString("type_name"),
                rs.getString("status"),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_code"),
                rs.getString("branch_name"),
                rs.getObject("document_date", LocalDate.class),
                rs.getString("reference"),
                rs.getString("raised_by"),
                KigaliTime.read(rs, "posted_at"),
                rs.getString("posted_by"),
                rs.getString("cancel_reason"),
                rs.getObject("step", Integer.class),
                rs.getInt("steps"),
                rs.getString("awaiting_role"),
                // A document's page is behind its type's view right where the reader works (SecurityConfig).
                rightsHere.contains(rs.getString("view_right")));
    }

    private static String like(String text) {
        if (text == null || text.isBlank()) return null;
        return "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
