package heritier.ntaganira.highbytes.wms.search;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.search
 * - File       : SearchController.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The top bar's search: documents, items, locations, suppliers and customers, as far as the rights reach
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.DocumentListService;
import heritier.ntaganira.highbytes.wms.document.DocumentRow;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * {@code /search?q=}. Signed in is enough to ask (SecurityConfig's last rule); each section is searched only for a
 * reader who could open its list, and documents only where the reader holds the type's view right at the
 * document's branch. A section the reader may not read is not searched at all, so it shows neither hits nor a
 * count of them.
 *
 * <p>A serial typed whole opens its document at once, when the reader can open it from where they work: someone
 * holding a delivery note in their hand wants the note, not a list.
 */
@Controller
public class SearchController {

    static final int MIN_LENGTH = 2;
    static final int PER_SECTION = 10;

    private final DocumentListService documents;
    private final SearchService search;

    public SearchController(DocumentListService documents, SearchService search) {
        this.documents = documents;
        this.search = search;
    }

    @GetMapping("/search")
    public String search(@RequestParam(required = false) String q,
                         @AuthenticationPrincipal AppUserDetails user,
                         Model model) {
        String text = q == null ? "" : q.trim();
        model.addAttribute("q", text);
        model.addAttribute("tooShort", text.length() < MIN_LENGTH);
        model.addAttribute("nothing", false);
        if (text.length() < MIN_LENGTH) {
            return "search/results";
        }

        // Serials are unique, so a whole one names one document, however many others contain it.
        var exact = documents.bySerial(text);
        if (exact.isPresent() && exact.get().openHere()) {
            return "redirect:/documents/" + exact.get().id();
        }
        List<DocumentRow> found = documents.list(DocumentListService.Query.text(text), PER_SECTION + 1);

        boolean readsItems = user.has("item.view");
        boolean readsPartners = user.has("partner.manage");
        var items = readsItems ? search.items(text, PER_SECTION + 1) : List.<SearchService.Hit>of();
        var locations = readsItems ? search.locations(text, PER_SECTION + 1) : List.<SearchService.Hit>of();
        var suppliers = readsPartners ? search.suppliers(text, PER_SECTION + 1) : List.<SearchService.Hit>of();
        var customers = readsPartners ? search.customers(text, PER_SECTION + 1) : List.<SearchService.Hit>of();

        model.addAttribute("documents", first(found));
        model.addAttribute("moreDocuments", found.size() > PER_SECTION);
        model.addAttribute("items", first(items));
        model.addAttribute("moreItems", items.size() > PER_SECTION);
        model.addAttribute("locations", first(locations));
        model.addAttribute("suppliers", first(suppliers));
        model.addAttribute("customers", first(customers));
        model.addAttribute("readsItems", readsItems);
        model.addAttribute("readsPartners", readsPartners);
        model.addAttribute("nothing", found.isEmpty() && items.isEmpty() && locations.isEmpty()
                && suppliers.isEmpty() && customers.isEmpty());
        return "search/results";
    }

    private static <T> List<T> first(List<T> rows) {
        return rows.size() > PER_SECTION ? rows.subList(0, PER_SECTION) : rows;
    }
}
