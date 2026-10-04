package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : DocumentController.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The register of every document, and a document address sent to the screen that owns it
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.sql.Types;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * {@code /documents/{id}} is the address the dashboard, the approval queue and
 * the search box use for any document. Each document type has its own screen;
 * this sends the visitor there. A transaction ticket opens on its own page
 * for whoever reads tickets here, and otherwise through the document it
 * answers to. A type whose screen is not built yet stays a 404, which
 * {@code ErrorPages} words as "not built yet".
 *
 * <p>Whether the visitor may see the document is decided by the screen it
 * lands on, for the document's own branch.
 *
 * <p>{@code /documents} is the register of every document the reader may
 * read, at every branch ({@link DocumentListService}), read-only.
 */
@Controller
@RequestMapping("/documents")
public class DocumentController {

    static final int PAGE = 50;

    private final JdbcClient jdbc;
    private final DocumentListService register;

    public DocumentController(JdbcClient jdbc, DocumentListService register) {
        this.jdbc = jdbc;
        this.register = register;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "documents";
    }

    @GetMapping
    public String list(@RequestParam(required = false) String type,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) UUID branch,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) boolean mine,
                       @RequestParam(required = false) UUID before,
                       Model model) {
        DocumentKind kind = Arrays.stream(DocumentKind.values()).filter(k -> k.code().equals(type))
                .findFirst().orElse(null);
        // List.of(...).contains(null) throws, so an empty filter is checked first.
        String state = status != null && DocumentListService.STATUSES.contains(status) ? status : null;
        String text = q == null || q.isBlank() ? null : q.trim();
        var found = register.list(new DocumentListService.Query(kind, state, branch, from, to, text, mine, before),
                PAGE + 1);
        boolean more = found.size() > PAGE;
        var rows = more ? found.subList(0, PAGE) : found;

        var filters = UriComponentsBuilder.fromPath("/documents")
                .queryParamIfPresent("type", Optional.ofNullable(kind == null ? null : kind.code()))
                .queryParamIfPresent("status", Optional.ofNullable(state))
                .queryParamIfPresent("branch", Optional.ofNullable(branch))
                .queryParamIfPresent("from", Optional.ofNullable(from))
                .queryParamIfPresent("to", Optional.ofNullable(to))
                .queryParamIfPresent("q", Optional.ofNullable(text))
                .queryParamIfPresent("mine", Optional.ofNullable(mine ? "true" : null));

        model.addAttribute("documents", rows);
        model.addAttribute("kinds", DocumentKind.values());
        model.addAttribute("statuses", DocumentListService.STATUSES);
        model.addAttribute("type", kind == null ? null : kind.code());
        model.addAttribute("status", state);
        model.addAttribute("branch", branch);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("q", text);
        model.addAttribute("mine", mine);
        model.addAttribute("filtered", kind != null || state != null || branch != null || from != null
                || to != null || text != null || mine);
        model.addAttribute("newestUrl", before != null ? filters.cloneBuilder().encode().toUriString() : null);
        model.addAttribute("olderUrl", more
                ? filters.cloneBuilder().queryParam("before", rows.get(rows.size() - 1).id()).encode().toUriString()
                : null);
        return "documents/list";
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String open(@PathVariable UUID id) {
        var target = jdbc.sql("""
                SELECT COALESCE(src.id, d.id) AS id, COALESCE(sdt.code, dt.code) AS code, dt.code AS own_code,
                       d.branch_id
                  FROM document d
                  JOIN document_type dt ON dt.id = d.document_type_id
             LEFT JOIN transaction_ticket t ON t.document_id = d.id AND dt.code = 'TT'
             LEFT JOIN document src         ON src.id = t.source_document_id
             LEFT JOIN document_type sdt    ON sdt.id = src.document_type_id
                 WHERE d.id = :id
                """)
                .param("id", id, Types.OTHER)
                .query((rs, n) -> new String[]{rs.getObject("id", UUID.class).toString(), rs.getString("code"),
                        rs.getString("own_code"), rs.getString("branch_id")})
                .optional()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        // The ticket page needs ticket.view where the reader works (its URL rule) and at the ticket's own branch.
        boolean readsTickets = CurrentUser.get().map(u -> u.has("ticket.view")).orElse(false)
                && CurrentUser.holdsAt("ticket.view", UUID.fromString(target[3]));
        if ("TT".equals(target[2]) && readsTickets) {
            return "redirect:/tickets/" + id;
        }

        switch (target[1]) {
            case "OPB" -> { return "redirect:/opening/" + target[0]; }
            case "GRN" -> { return "redirect:/receiving/" + target[0]; }
            case "DAO" -> { return "redirect:/dispatch/" + target[0]; }
            case "DN"  -> { return "redirect:/delivery-notes/" + target[0]; }
            case "TRF" -> { return "redirect:/transfers/" + target[0]; }
            case "TRR" -> { return "redirect:/transfers/receipts/" + target[0]; }
            case "DMG" -> { return "redirect:/damage/" + target[0]; }
            case "CNT" -> { return "redirect:/counts/" + target[0]; }
            case "CUT" -> { return "redirect:/cutting/" + target[0]; }
            default -> { /* a type whose screen is not built yet stays a 404 */ }
        }
        throw new ResponseStatusException(NOT_FOUND);
    }
}
