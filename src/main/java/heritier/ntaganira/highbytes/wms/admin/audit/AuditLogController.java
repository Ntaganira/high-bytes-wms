package heritier.ntaganira.highbytes.wms.admin.audit;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.admin.audit
 * - File       : AuditLogController.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The audit log: every recorded change, searched and read, as far as the reader's right reaches
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditAction;
import heritier.ntaganira.highbytes.wms.common.audit.AuditEntry;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The audit log (FR-SEC-22..29): every change the system recorded, newest first, searched by day, action, kind of
 * record, who, branch and words, and read one entry at a time.
 *
 * <p>Read-only: the trail is append-only in the database (V1), and nothing here writes. A right held at one branch
 * reads the entries recorded there; entries recorded at no branch (sign-ins, the system's own work) are read only
 * by whoever holds the right at every branch. An entry outside the reader's reach is a 404, not a 403: its
 * existence is itself something the reader may not see.
 */
@Controller
@RequestMapping("/admin/audit")
@PreAuthorize("hasAuthority('audit.view')")
public class AuditLogController {

    static final String RIGHT = "audit.view";
    static final int PAGE = 50;

    private final AuditService audit;
    private final BranchService branches;

    public AuditLogController(AuditService audit, BranchService branches) {
        this.audit = audit;
        this.branches = branches;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserDetails user,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String action,
                       @RequestParam(required = false) String subject,
                       @RequestParam(required = false) String actor,
                       @RequestParam(required = false) UUID branch,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) Long before,
                       Model model) {

        var scope = AuditService.Scope.of(user, RIGHT);
        // Only names the screen offers: anything else would search for nothing anyway.
        String entity = AuditSubject.of(subject).map(AuditSubject::entityName).orElse(null);
        String act = action != null && List.of(AuditAction.values()).stream().anyMatch(a -> a.name().equals(action))
                ? action : null;

        var query = new AuditService.Query(from, to, act, entity, null, actor, branch, q, before);
        List<AuditEntry> found = audit.search(query, scope, PAGE + 1);
        boolean more = found.size() > PAGE;
        List<AuditEntry> entries = more ? found.subList(0, PAGE) : found;

        model.addAttribute("entries", entries);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("action", act);
        model.addAttribute("subject", entity);
        model.addAttribute("actor", actor);
        model.addAttribute("branch", branch);
        model.addAttribute("q", q);
        model.addAttribute("actions", AuditAction.values());
        model.addAttribute("subjects", AuditSubject.values());
        model.addAttribute("branches", branchesInScope(scope));
        model.addAttribute("everywhere", scope.everywhere());
        var filters = UriComponentsBuilder.fromPath("/admin/audit")
                .queryParamIfPresent("from", Optional.ofNullable(from))
                .queryParamIfPresent("to", Optional.ofNullable(to))
                .queryParamIfPresent("action", Optional.ofNullable(act))
                .queryParamIfPresent("subject", Optional.ofNullable(entity))
                .queryParamIfPresent("actor", Optional.ofNullable(blankToNull(actor)))
                .queryParamIfPresent("branch", Optional.ofNullable(branch))
                .queryParamIfPresent("q", Optional.ofNullable(blankToNull(q)));
        model.addAttribute("newestUrl", before != null ? filters.cloneBuilder().encode().toUriString() : null);
        model.addAttribute("olderUrl", more
                ? filters.cloneBuilder().queryParam("before", entries.get(entries.size() - 1).id()).encode().toUriString()
                : null);
        return "admin/audit/list";
    }

    @GetMapping("/{id}")
    public String view(@AuthenticationPrincipal AppUserDetails user, @PathVariable long id, Model model) {
        var scope = AuditService.Scope.of(user, RIGHT);
        AuditEntry entry = audit.find(id, scope).orElseThrow(() -> new EntryNotFoundException(id));
        model.addAttribute("entry", entry);
        model.addAttribute("subjectLabel", AuditSubject.labelOf(entry.entityName()));
        model.addAttribute("recordPath", AuditSubject.pathOf(entry.entityName(), entry.entityId()));
        model.addAttribute("related", entry.entityId() == null ? List.of()
                : audit.search(AuditService.Query.entity(entry.entityName(), entry.entityId()), scope, PAGE));
        return "admin/audit/view";
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "audit";
    }

    /** The branches the reader's right reaches, for the filter: every branch there has been, or the ones named. */
    private List<BranchView> branchesInScope(AuditService.Scope scope) {
        return branches.everyBranch().stream()
                .filter(b -> scope.everywhere() || scope.branchIds().contains(b.id()))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class EntryNotFoundException extends RuntimeException {
        EntryNotFoundException(long id) {
            super("No audit entry " + id + " the reader may see");
        }
    }
}
