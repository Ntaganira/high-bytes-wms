package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : VarianceController.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The inventory variance report (VR-010): what counts found against the book
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * The variance report, read from the counts: every line that differed from the
 * book, once its count's verification is signed. A count still being counted or
 * verified is not on it, so the report cannot tell a counter where the book
 * disagrees with them.
 */
@Controller
@RequestMapping("/variances")
@PreAuthorize("hasAuthority('count.view')")
public class VarianceController {

    private static final List<String> STATES = List.of("open", "posted", "all");

    private final CountLookupService lookups;

    public VarianceController(CountLookupService lookups) {
        this.lookups = lookups;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "variances";
    }

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(required = false) String state,
                       Model model) {
        String wanted = state != null && STATES.contains(state) ? state : "open";
        var rows = lookups.variances(branch.id(), wanted);
        BigDecimal shortage = rows.stream().map(CountLookupService.VarianceRow::value)
                .filter(v -> v != null && v.signum() < 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal surplus = rows.stream().map(CountLookupService.VarianceRow::value)
                .filter(v -> v != null && v.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("rows", rows);
        model.addAttribute("state", wanted);
        model.addAttribute("shortage", shortage);
        model.addAttribute("surplus", surplus);
        return "variances/list";
    }
}
