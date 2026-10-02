package heritier.ntaganira.highbytes.wms.common.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.common.web
 * - File       : GlobalModelAdvice.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Model attributes every page needs: branch, business date, lock state, nav counts
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.common.db.KigaliTime;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;
import java.util.List;

/**
 * Model attributes every page needs: which branch, which business date,
 * whether that date is locked, and the counts the navigation shows.
 *
 * <p>The close state is on every screen deliberately. When a posting is
 * refused for being backdated, the user has already been looking at the
 * reason.
 *
 * <p>The branch comes from the signed-in user, whose permissions were
 * loaded for it, so the screen and the rights always name the same branch.
 * The switcher offers only the branches the user holds a role at.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    /** The current branch, looked up once per request however many attributes need it. */
    private static final String BRANCH_REQUEST_KEY = GlobalModelAdvice.class.getName() + ".branch";

    private static final String CLOSE_STATUS = """
            SELECT status FROM daily_close
             WHERE branch_id = :branchId AND business_date = :date
            """;

    private static final String NAV_COUNTS = """
            SELECT
              COUNT(*) FILTER (WHERE dt.code = 'GRN' AND d.status = 'PENDING')          AS goods_received_pending,
              COUNT(*) FILTER (WHERE dt.code = 'DAO' AND d.status IN ('PENDING','DRAFT')) AS releases_held,
              (SELECT COUNT(*) FROM document x JOIN document_type xt ON xt.id = x.document_type_id AND xt.code = 'TRF'
                WHERE (x.branch_id = :branchId AND x.status = 'APPROVED')
                   OR (x.status = 'POSTED'
                       AND EXISTS (SELECT 1 FROM transfer_line_position p WHERE p.transfer_id = x.id AND p.in_transit_base > 0)
                       AND EXISTS (SELECT 1 FROM transfer_order o JOIN location l ON l.id = o.to_location_id
                                    WHERE o.document_id = x.id AND l.branch_id = :branchId)
                       AND NOT EXISTS (SELECT 1 FROM transfer_receipt r JOIN document rd ON rd.id = r.document_id
                                        WHERE r.transfer_id = x.id AND rd.status <> 'CANCELLED')))
                                                                                          AS transfers_in_transit,
              COUNT(*) FILTER (WHERE dt.code = 'CNT' AND d.status IN ('DRAFT','PENDING')) AS counts_open,
              -- Lines that differ from the book on counts verified but not yet posted. A count still being counted
              -- or verified adds nothing: even a number would tell a counter where the book disagrees.
              (SELECT COUNT(*) FROM stock_count_line l JOIN document c ON c.id = l.document_id
                WHERE c.branch_id = :branchId AND c.status IN ('PENDING', 'APPROVED')
                  AND l.variance_qty <> 0 AND count_book_visible(c.id))              AS variances_open,
              0                                                                          AS delivery_notes_overdue,
              -- Days prepared or reconciled and not yet locked. Read from the closes the nightly job prepares,
              -- never from the ledger: this runs on every page.
              (SELECT COUNT(*) FROM daily_close c
                WHERE c.branch_id = :branchId AND c.status <> 'LOCKED')                  AS closes_due
              FROM document d
              JOIN document_type dt ON dt.id = d.document_type_id
             WHERE d.branch_id = :branchId
               AND d.status NOT IN ('CANCELLED')
            """;

    private final BranchService branches;
    private final JdbcClient jdbc;

    public GlobalModelAdvice(BranchService branches, JdbcClient jdbc) {
        this.branches = branches;
        this.jdbc = jdbc;
    }

    @ModelAttribute("availableBranches")
    public List<BranchView> availableBranches(@AuthenticationPrincipal AppUserDetails user) {
        if (user == null) return List.of();
        return branches.findAll().stream()
                .filter(b -> user.accessibleBranchIds().contains(b.id()))
                .toList();
    }

    @ModelAttribute("currentBranch")
    public BranchView currentBranch(@AuthenticationPrincipal AppUserDetails user, HttpServletRequest request) {
        if (user == null) return null;
        if (request.getAttribute(BRANCH_REQUEST_KEY) instanceof BranchView cached) return cached;
        BranchView branch = branches.findById(user.branchId()).orElse(null);
        if (branch != null) request.setAttribute(BRANCH_REQUEST_KEY, branch);
        return branch;
    }

    /** Today in Kigali, whatever the server's own clock is set to: the business date is the branch's. */
    @ModelAttribute("businessDate")
    public LocalDate businessDate() {
        return LocalDate.now(KigaliTime.ZONE);
    }

    @ModelAttribute("dayLocked")
    public boolean dayLocked(@AuthenticationPrincipal AppUserDetails user, HttpServletRequest request) {
        BranchView branch = currentBranch(user, request);
        if (branch == null) return false;
        return jdbc.sql(CLOSE_STATUS)
                .param("branchId", branch.id())
                .param("date", LocalDate.now(KigaliTime.ZONE))
                .query(String.class)
                .optional()
                .map("LOCKED"::equals)
                .orElse(false);
    }

    @ModelAttribute("userInitials")
    public String userInitials(@AuthenticationPrincipal AppUserDetails user) {
        return user == null ? null : user.initials();
    }

    @ModelAttribute("primaryRoleName")
    public String primaryRoleName(@AuthenticationPrincipal AppUserDetails user) {
        return user == null ? null : user.primaryRoleName();
    }

    /**
     * The counts on the navigation. This is the warehouse manager's morning
     * triage before they click anything, and the main argument for a sidebar
     * over top navigation.
     */
    @ModelAttribute("navCounts")
    public NavCounts navCounts(@AuthenticationPrincipal AppUserDetails user, HttpServletRequest request) {
        BranchView branch = currentBranch(user, request);
        if (branch == null) return NavCounts.empty();
        return jdbc.sql(NAV_COUNTS)
                .param("branchId", branch.id())
                .query((rs, n) -> new NavCounts(
                        rs.getInt("goods_received_pending"),
                        rs.getInt("releases_held"),
                        rs.getInt("transfers_in_transit"),
                        rs.getInt("counts_open"),
                        rs.getInt("variances_open"),
                        rs.getInt("delivery_notes_overdue"),
                        rs.getInt("closes_due")))
                .optional()
                .orElseGet(NavCounts::empty);
    }

    public record NavCounts(int goodsReceivedPending,
                            int releasesHeld,
                            int transfersInTransit,
                            int countsOpen,
                            int variancesOpen,
                            int deliveryNotesOverdue,
                            int closesDue) {

        public static NavCounts empty() {
            return new NavCounts(0, 0, 0, 0, 0, 0, 0);
        }

        public int totalPending() {
            return goodsReceivedPending + releasesHeld + variancesOpen + deliveryNotesOverdue;
        }
    }
}
