package heritier.ntaganira.highbytes.wms.masterdata.location;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.masterdata.location
 * - File       : LocationController.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Location screens: list, create, view, edit, and add or toggle bins
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.audit.AuditService;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/locations")
@PreAuthorize("hasAuthority('item.view')")
public class LocationController {

    private final LocationService locations;
    private final BranchService branches;
    private final AuditService audit;

    public LocationController(LocationService locations, BranchService branches, AuditService audit) {
        this.locations = locations;
        this.branches = branches;
        this.audit = audit;
    }

    @ModelAttribute("activeNav")
    public String activeNav() {
        return "locations";
    }

    @ModelAttribute("locationTypes")
    public LocationType[] locationTypes() {
        return LocationType.values();
    }

    @GetMapping
    public String list(@ModelAttribute("currentBranch") BranchView branch,
                       @RequestParam(defaultValue = "false") boolean allBranches,
                       @RequestParam(defaultValue = "false") boolean includeInactive,
                       Model model) {

        UUID scope = allBranches || branch == null ? null : branch.id();
        model.addAttribute("locations", locations.forBranch(scope, includeInactive));
        model.addAttribute("allBranches", allBranches);
        model.addAttribute("includeInactive", includeInactive);
        return "masterdata/locations/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('location.manage')")
    public String newForm(@ModelAttribute("currentBranch") BranchView branch, Model model) {
        var form = new LocationForm();
        form.setLocationType(LocationType.WAREHOUSE);
        if (branch != null) form.setBranchId(branch.id());
        model.addAttribute("form", form);
        model.addAttribute("branches", manageableBranches());
        return "masterdata/locations/form";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('location.manage')")
    public String create(@Valid @ModelAttribute("form") LocationForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {

        validateCrossFields(form, binding);
        if (binding.hasErrors()) {
            model.addAttribute("branches", manageableBranches());
            return "masterdata/locations/form";
        }
        try {
            UUID id = locations.create(form, branch);
            redirect.addFlashAttribute("flashSuccess", "Location " + form.getCode() + " created.");
            return "redirect:/locations/" + id;
        } catch (LocationService.LocationCodeTakenException e) {
            binding.rejectValue("code", "duplicate", e.getMessage());
            model.addAttribute("branches", manageableBranches());
            return "masterdata/locations/form";
        }
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var location = locations.findById(id)
                .orElseThrow(() -> new LocationService.LocationNotFoundException(id));
        model.addAttribute("location", location);
        // A right held at one branch does not reach a location at another.
        model.addAttribute("canManage", CurrentUser.holdsAt("location.manage", location.branchId()));
        model.addAttribute("bins", locations.binsIn(id));
        model.addAttribute("history", audit.historyOf("location", id, 20));
        return "masterdata/locations/view";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('location.manage')")
    public String editForm(@PathVariable UUID id, Model model) {
        var form = locations.formFor(id);
        // The save would be refused; so is the form.
        CurrentUser.requireAt("location.manage", form.getBranchId());
        form.setHoldsStock(locations.holdsStock(id));
        model.addAttribute("form", form);
        model.addAttribute("storedCode", form.getCode());
        model.addAttribute("branches", manageableBranches());
        return "masterdata/locations/form";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('location.manage')")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") LocationForm form,
                         BindingResult binding,
                         @ModelAttribute("currentBranch") BranchView branch,
                         Model model,
                         RedirectAttributes redirect) {

        // Before validation, so an invalid form for a missing location is a 404 too.
        // A re-rendered form is headed with the stored code, not the one typed.
        LocationForm stored = locations.formFor(id);
        model.addAttribute("storedCode", stored.getCode());

        validateCrossFields(form, binding);
        if (!binding.hasErrors()) {
            try {
                locations.update(id, form, branch);
                redirect.addFlashAttribute("flashSuccess", "Location " + form.getCode() + " updated.");
                return "redirect:/locations/" + id;
            } catch (LocationService.LocationCodeTakenException e) {
                binding.rejectValue("code", "duplicate", e.getMessage());
            } catch (LocationService.LocationHoldsStockException e) {
                binding.reject("holdsStock", e.getMessage());
                // The branch and type are locked on the page; show the stored ones,
                // or every re-submit would carry the refused values again.
                form.setBranchId(stored.getBranchId());
                form.setLocationType(stored.getLocationType());
            }
        }
        form.setHoldsStock(locations.holdsStock(id));
        model.addAttribute("branches", manageableBranches());
        return "masterdata/locations/form";
    }

    @PostMapping("/{id}/bins")
    @PreAuthorize("hasAuthority('location.manage')")
    public String addBin(@PathVariable UUID id,
                         @RequestParam String binCode,
                         @RequestParam(required = false) String zone,
                         @ModelAttribute("currentBranch") BranchView branch,
                         RedirectAttributes redirect) {
        try {
            locations.addBin(id, binCode, zone, branch);
            redirect.addFlashAttribute("flashSuccess", "Bin " + binCode.trim().toUpperCase() + " added.");
        } catch (LocationService.BinCodeTakenException | LocationService.InvalidBinCodeException
                 | LocationService.InvalidBinZoneException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/locations/" + id;
    }

    @PostMapping("/{id}/bins/{binId}/toggle")
    @PreAuthorize("hasAuthority('location.manage')")
    public String toggleBin(@PathVariable UUID id,
                            @PathVariable UUID binId,
                            @RequestParam boolean active,
                            @ModelAttribute("currentBranch") BranchView branch,
                            RedirectAttributes redirect) {
        try {
            locations.setBinActive(id, binId, active, branch);
        } catch (LocationService.BinHoldsStockException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/locations/" + id;
    }

    /** The branches the user may place a location at: those where they hold the right. */
    private List<BranchView> manageableBranches() {
        return branches.findAll().stream()
                .filter(b -> CurrentUser.holdsAt("location.manage", b.id()))
                .toList();
    }

    private void validateCrossFields(LocationForm form, BindingResult binding) {
        if (!form.isBondedConsistentWithType()) {
            binding.addError(new FieldError("form", "bonded",
                    "A bonded location must be marked bonded: duty suspension follows the goods."));
        }
    }
}
