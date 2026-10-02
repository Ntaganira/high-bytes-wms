package heritier.ntaganira.highbytes.wms.inventory.stock;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.stock
 * - File       : StockController.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Stock screens: balances by item, one item place by place, and the ledger of movements
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.masterdata.item.ProductType;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stock at the branch the reader is working in (FR-IN-10..12), read-only. A right held at one branch reads that
 * branch's stock: the top bar's branch switcher moves between them. Places under a live count show no book
 * (V15); {@link StockService} says how.
 */
@Controller
@RequestMapping("/stock")
@PreAuthorize("hasAuthority('stock.view')")
public class StockController {

    static final int LIST = 500;
    static final int PAGE = 50;

    private final StockService stock;

    public StockController(StockService stock) {
        this.stock = stock;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "stock";
    }

    @GetMapping
    public String balances(@ModelAttribute("currentBranch") BranchView branch,
                           @RequestParam(required = false) String q,
                           @RequestParam(required = false) String type,
                           @RequestParam(required = false) UUID location,
                           @RequestParam(required = false) String filter,
                           Model model) {
        boolean belowReorder = "below-reorder".equals(filter);
        String productType = type != null && List.of(ProductType.values()).stream().anyMatch(t -> t.name().equals(type))
                ? type : null;
        var items = stock.items(branch.id(), q, productType, location, belowReorder, LIST);

        model.addAttribute("items", items);
        model.addAttribute("truncated", items.size() >= LIST);
        model.addAttribute("totalValue", items.stream().map(StockService.ItemStock::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("anyCounted", items.stream().anyMatch(i -> i.placesCounted() > 0));
        model.addAttribute("liveCounts", stock.liveCounts(branch.id()));
        model.addAttribute("locations", stock.locations(branch.id()));
        model.addAttribute("productTypes", ProductType.values());
        model.addAttribute("q", q);
        model.addAttribute("type", productType);
        model.addAttribute("location", location);
        model.addAttribute("belowReorder", belowReorder);
        return "stock/balances";
    }

    @GetMapping("/items/{id}")
    public String item(@ModelAttribute("currentBranch") BranchView branch, @PathVariable UUID id, Model model) {
        var item = stock.item(branch.id(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No item " + id));
        var places = stock.places(branch.id(), id);
        var movements = stock.movements(branch.id(), StockService.MovementQuery.ofItem(id), PAGE + 1);
        model.addAttribute("item", item);
        model.addAttribute("places", places);
        model.addAttribute("movements", movements.size() > PAGE ? movements.subList(0, PAGE) : movements);
        model.addAttribute("moreMovements", movements.size() > PAGE);
        model.addAttribute("liveCounts", stock.liveCounts(branch.id()));
        return "stock/item";
    }

    @GetMapping("/movements")
    public String movements(@ModelAttribute("currentBranch") BranchView branch,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false) UUID item,
                            @RequestParam(required = false) UUID location,
                            @RequestParam(required = false) String direction,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) Long before,
                            Model model) {
        String dir = "IN".equals(direction) || "OUT".equals(direction) ? direction : null;
        var query = new StockService.MovementQuery(from, to, item, location, dir, q, before);
        var found = stock.movements(branch.id(), query, PAGE + 1);
        boolean more = found.size() > PAGE;
        var rows = more ? found.subList(0, PAGE) : found;

        var filters = UriComponentsBuilder.fromPath("/stock/movements")
                .queryParamIfPresent("from", Optional.ofNullable(from))
                .queryParamIfPresent("to", Optional.ofNullable(to))
                .queryParamIfPresent("item", Optional.ofNullable(item))
                .queryParamIfPresent("location", Optional.ofNullable(location))
                .queryParamIfPresent("direction", Optional.ofNullable(dir))
                .queryParamIfPresent("q", Optional.ofNullable(q == null || q.isBlank() ? null : q.trim()));

        model.addAttribute("movements", rows);
        model.addAttribute("liveCounts", stock.liveCounts(branch.id()));
        model.addAttribute("locations", stock.locations(branch.id()));
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("item", item);
        model.addAttribute("itemLabel", item == null ? null
                : stock.item(branch.id(), item).map(i -> i.itemCode() + " · " + i.description()).orElse(null));
        model.addAttribute("location", location);
        model.addAttribute("direction", dir);
        model.addAttribute("q", q);
        model.addAttribute("newestUrl", before != null ? filters.cloneBuilder().encode().toUriString() : null);
        model.addAttribute("olderUrl", more
                ? filters.cloneBuilder().queryParam("before", rows.get(rows.size() - 1).id()).encode().toUriString()
                : null);
        return "stock/movements";
    }
}
