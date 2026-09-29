package heritier.ntaganira.highbytes.wms.common.web;

import heritier.ntaganira.highbytes.wms.branch.BranchService;
import heritier.ntaganira.highbytes.wms.branch.BranchView;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Model attributes every page needs: which branch, which business date,
 * whether that date is locked, and the counts the navigation shows.
 *
 * <p>The close state is on every screen deliberately. When a posting is
 * refused for being backdated, the user has already been looking at the
 * reason.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    public static final String BRANCH_SESSION_KEY = "hb.currentBranchId";

    private static final String CLOSE_STATUS = """
            SELECT status FROM daily_close
             WHERE branch_id = :branchId AND business_date = :date
            """;

    private static final String NAV_COUNTS = """
            SELECT
              COUNT(*) FILTER (WHERE dt.code = 'GRN' AND d.status = 'PENDING')          AS goods_received_pending,
              COUNT(*) FILTER (WHERE dt.code = 'DAO' AND d.status IN ('PENDING','DRAFT')) AS releases_held,
              COUNT(*) FILTER (WHERE dt.code = 'TRF' AND d.status = 'APPROVED')         AS transfers_in_transit,
              COUNT(*) FILTER (WHERE dt.code = 'CNT' AND d.status IN ('DRAFT','PENDING')) AS counts_open,
              COUNT(*) FILTER (WHERE dt.code = 'VR'  AND d.status <> 'POSTED')          AS variances_open,
              0                                                                          AS delivery_notes_overdue
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
        return user == null ? List.of() : branches.findAll();
    }

    @ModelAttribute("currentBranch")
    public BranchView currentBranch(@AuthenticationPrincipal AppUserDetails user, HttpSession session) {
        if (user == null) return null;
        UUID chosen = (UUID) session.getAttribute(BRANCH_SESSION_KEY);
        return branches.findById(chosen)
                .or(() -> branches.defaultFor(user.homeBranchId()))
                .orElse(null);
    }

    @ModelAttribute("businessDate")
    public LocalDate businessDate() {
        return LocalDate.now();
    }

    @ModelAttribute("dayLocked")
    public boolean dayLocked(@AuthenticationPrincipal AppUserDetails user, HttpSession session) {
        BranchView branch = currentBranch(user, session);
        if (branch == null) return false;
        return jdbc.sql(CLOSE_STATUS)
                .param("branchId", branch.id())
                .param("date", LocalDate.now())
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
    public NavCounts navCounts(@AuthenticationPrincipal AppUserDetails user, HttpSession session) {
        BranchView branch = currentBranch(user, session);
        if (branch == null) return NavCounts.empty();
        return jdbc.sql(NAV_COUNTS)
                .param("branchId", branch.id())
                .query((rs, n) -> new NavCounts(
                        rs.getInt("goods_received_pending"),
                        rs.getInt("releases_held"),
                        rs.getInt("transfers_in_transit"),
                        rs.getInt("counts_open"),
                        rs.getInt("variances_open"),
                        rs.getInt("delivery_notes_overdue")))
                .optional()
                .orElseGet(NavCounts::empty);
    }

    public record NavCounts(int goodsReceivedPending,
                            int releasesHeld,
                            int transfersInTransit,
                            int countsOpen,
                            int variancesOpen,
                            int deliveryNotesOverdue) {

        public static NavCounts empty() {
            return new NavCounts(0, 0, 0, 0, 0, 0);
        }

        public int totalPending() {
            return goodsReceivedPending + releasesHeld + variancesOpen + deliveryNotesOverdue;
        }
    }
}
