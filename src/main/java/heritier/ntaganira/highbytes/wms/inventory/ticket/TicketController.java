package heritier.ntaganira.highbytes.wms.inventory.ticket;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.ticket
 * - File       : TicketController.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The transaction ticket register at the working branch, and one ticket, read-only
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /tickets}: every ticket the branch's documents wrote, newest first, filtered and paged, and
 * {@code /tickets/{id}}. Nothing here raises, changes or posts a ticket: a ticket exists only because a document
 * was posted, and its page sends the reader to that document.
 */
@Controller
@RequestMapping("/tickets")
@PreAuthorize("hasAuthority('ticket.view')")
public class TicketController {

    static final int PAGE = 50;

    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "tickets";
    }

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String movement,
                       @RequestParam(required = false) String direction,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) UUID before,
                       Model model) {
        TicketMovement kind = TicketMovement.of(movement);
        String dir = "IN".equals(direction) || "OUT".equals(direction) ? direction : null;
        String text = q == null || q.isBlank() ? null : q.trim();
        var found = tickets.list(branch.id(), new TicketService.TicketQuery(from, to, kind, dir, text, before), PAGE + 1);
        boolean more = found.size() > PAGE;
        var rows = more ? found.subList(0, PAGE) : found;

        var filters = UriComponentsBuilder.fromPath("/tickets")
                .queryParamIfPresent("from", Optional.ofNullable(from))
                .queryParamIfPresent("to", Optional.ofNullable(to))
                .queryParamIfPresent("movement", Optional.ofNullable(kind))
                .queryParamIfPresent("direction", Optional.ofNullable(dir))
                .queryParamIfPresent("q", Optional.ofNullable(text));

        model.addAttribute("tickets", rows);
        model.addAttribute("movements", TicketMovement.values());
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("movement", kind == null ? null : kind.name());
        model.addAttribute("direction", dir);
        model.addAttribute("q", text);
        model.addAttribute("filtered", from != null || to != null || kind != null || dir != null || text != null);
        model.addAttribute("newestUrl", before != null ? filters.cloneBuilder().encode().toUriString() : null);
        model.addAttribute("olderUrl", more
                ? filters.cloneBuilder().queryParam("before", rows.get(rows.size() - 1).id()).encode().toUriString()
                : null);
        return "tickets/list";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var ticket = tickets.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No ticket " + id));
        model.addAttribute("ticket", ticket);
        model.addAttribute("t", ticket.header());
        return "tickets/view";
    }
}
