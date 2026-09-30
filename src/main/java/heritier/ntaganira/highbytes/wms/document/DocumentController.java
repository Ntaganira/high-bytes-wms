package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : DocumentController.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Sends a document address to the screen of the module that owns it
 * </pre>
 */

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Types;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * {@code /documents/{id}} is the address the dashboard, the approval queue and
 * the search box use for any document. Each document type has its own screen;
 * this sends the visitor there. A transaction ticket is shown through the
 * document it answers to. A type whose screen is not built yet stays a 404,
 * which {@code ErrorPages} words as "not built yet".
 *
 * <p>Whether the visitor may see the document is decided by the screen it
 * lands on, for the document's own branch.
 */
@Controller
@RequestMapping("/documents")
public class DocumentController {

    private final JdbcClient jdbc;

    public DocumentController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String open(@PathVariable UUID id) {
        var target = jdbc.sql("""
                SELECT COALESCE(src.id, d.id) AS id, COALESCE(sdt.code, dt.code) AS code
                  FROM document d
                  JOIN document_type dt ON dt.id = d.document_type_id
             LEFT JOIN transaction_ticket t ON t.document_id = d.id AND dt.code = 'TT'
             LEFT JOIN document src         ON src.id = t.source_document_id
             LEFT JOIN document_type sdt    ON sdt.id = src.document_type_id
                 WHERE d.id = :id
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> new String[]{rs.getObject("id", UUID.class).toString(), rs.getString("code")})
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        if ("GRN".equals(target[1])) {
            return "redirect:/receiving/" + target[0];
        }
        throw new ResponseStatusException(NOT_FOUND);
    }
}
