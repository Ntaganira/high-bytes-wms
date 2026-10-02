package heritier.ntaganira.highbytes.wms.common.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.web
 * - File       : ErrorPages.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Picks the browser error page and names linked screens that are not built yet
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.security.CurrentUser;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Error pages for browsers. Machine clients still get Spring Boot's JSON body.
 *
 * <p>A signed-in user sees the error inside the application layout, with the
 * navigation still there. Anyone else gets a plain page: the layout reads the
 * signed-in user, and the usual anonymous error is an expired sign-in form.
 *
 * <p>The navigation already links to screens Phase 1 has not built. Those
 * addresses are listed in {@link #PLANNED}, so their 404 says "not built yet"
 * rather than "not found". Remove a screen from the list when it is built.
 *
 * <p>A page that fails after it has started to reach the browser cannot be
 * replaced, only added to. Its error becomes a one-line notice, never a
 * second whole page inside the first.
 *
 * <p>A 403 for a record at another branch names that branch: the user holds
 * the right where they are working, just not where the record is.
 */
@Component
public class ErrorPages implements ErrorViewResolver {

    /**
     * Request attribute set when a 403 is a missing or stale CSRF token
     * rather than a missing right, so the page can say the form expired.
     */
    public static final String FORM_EXPIRED = ErrorPages.class.getName() + ".formExpired";

    /** A linked address with nothing behind it yet. */
    public record PlannedScreen(String path, String title, String icon, String activeNav) {

        boolean matches(String requestPath) {
            return requestPath.equals(path) || requestPath.startsWith(path + "/");
        }
    }

    /** Checked in order, so a longer path must come before its prefix. */
    static final List<PlannedScreen> PLANNED = List.of(
            new PlannedScreen("/cutting",          "Cutting Orders",          "bi-scissors",           "cutting"),
            new PlannedScreen("/tickets",          "Transaction Tickets",     "bi-ticket-perforated",  "tickets"),
            new PlannedScreen("/stock/movements",  "Stock Movements",         "bi-box",                "stock"),
            new PlannedScreen("/stock",            "Stock Balances",          "bi-box",                "stock"),
            new PlannedScreen("/reports",          "Reports & KPIs",          "bi-bar-chart",          "reports"),
            new PlannedScreen("/approvals",        "Approval Queue",          "bi-check2-square",      null),
            new PlannedScreen("/documents",        "Documents",               "bi-file-earmark-text",  null),
            new PlannedScreen("/search",           "Search",                  "bi-search",             null));

    private final BranchService branches;

    public ErrorPages(BranchService branches) {
        this.branches = branches;
    }

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status,
                                         Map<String, Object> model) {

        // Included into a response already on its way: a notice, not a page.
        // The status Tomcat reports then is the one already sent, often 200.
        if (request.getDispatcherType() == DispatcherType.INCLUDE || status.value() < 400) {
            return new ModelAndView("error/inline", model, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // /error asked for directly has no error behind it: Spring reports
        // status 999 and no path. There is nothing here, so it is a 404.
        if (Integer.valueOf(999).equals(model.get("status"))) {
            var notFound = new LinkedHashMap<>(model);
            notFound.put("status", 404);
            notFound.put("error", "Not Found");
            notFound.put("path", "/error");
            model = notFound;
            status = HttpStatus.NOT_FOUND;
        }

        var view = new ModelAndView(signedIn() ? "error/page" : "error/plain", model, status);
        if (status == HttpStatus.NOT_FOUND) {
            plannedScreenFor(pathOf(request, model)).ifPresent(screen -> {
                view.addObject("plannedScreen", screen);
                view.addObject("activeNav", screen.activeNav());
            });
        }
        if (Boolean.TRUE.equals(request.getAttribute(FORM_EXPIRED))) {
            view.addObject("formExpired", true);
        }
        if (request.getAttribute(CurrentUser.DENIED_BRANCH) instanceof UUID branchId) {
            branches.findById(branchId).ifPresent(b -> view.addObject("deniedBranch", b));
        }
        return view;
    }

    static Optional<PlannedScreen> plannedScreenFor(String path) {
        if (path == null) return Optional.empty();
        return PLANNED.stream().filter(screen -> screen.matches(path)).findFirst();
    }

    private static String pathOf(HttpServletRequest request, Map<String, Object> model) {
        if (!(model.get("path") instanceof String path)) return null;
        String context = request.getContextPath();
        return path.startsWith(context) ? path.substring(context.length()) : path;
    }

    private static boolean signedIn() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
               && !(auth instanceof AnonymousAuthenticationToken);
    }
}
