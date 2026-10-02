package heritier.ntaganira.highbytes.wms.approval;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.approval
 * - File       : ApprovalQueueTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The approval queue lists exactly what the reader could sign now, by the rules signing applies
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.DocumentKind;
import heritier.ntaganira.highbytes.wms.inventory.count.CountScope;
import heritier.ntaganira.highbytes.wms.inventory.count.CountService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.support.CountFlow;
import heritier.ntaganira.highbytes.wms.support.GrnFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real PostgreSQL 16 with Flyway V1 to V17. The shared test database holds every other test's pending
 * documents too, so each assertion asks about the test's own document, never about the queue's size.
 */
class ApprovalQueueTest extends IntegrationTest {

    /** Large enough that a document of this test's is never cut off by the others waiting. */
    static final int ALL = 100_000;

    @Autowired ApprovalQueueService queue;
    @Autowired ReceivingService receiving;
    @Autowired CountService counts;

    GrnFlow flow;

    @BeforeEach
    void cast() {
        flow = new GrnFlow(fx, receiving);
    }

    private List<UUID> queueOf(UUID user) {
        fx.actAs(user);
        return queue.awaiting(null, null, ALL).stream().map(AwaitingSignature::documentId).toList();
    }

    private AwaitingSignature rowFor(UUID user, UUID document) {
        fx.actAs(user);
        return queue.awaiting(null, null, ALL).stream()
                .filter(r -> r.documentId().equals(document)).findFirst().orElseThrow();
    }

    @Test
    void aSubmittedReceiptWaitsOnItsNextSignerAndNobodyElse() {
        UUID draft = flow.draft();
        UUID pending = flow.pending();
        UUID nextSigner = flow.stepUsers.get(1);

        var row = rowFor(nextSigner, pending);
        assertThat(row.serialNo()).startsWith("GRN-KGL-");
        assertThat(row.typeCode()).isEqualTo("GRN");
        assertThat(row.step()).isEqualTo(2);
        assertThat(row.steps()).isEqualTo(flow.chainRoles.size());
        assertThat(row.openHere()).isTrue();
        assertThat(row.overdue()).isFalse();
        assertThat(row.hoursWaiting()).isZero();
        assertThat(row.waitingSince()).isNotNull();
        // A draft awaits its raiser's submission, not anyone's signature.
        assertThat(queueOf(nextSigner)).doesNotContain(draft);

        // The raiser signed step 1; the third signer's turn has not come; a salesperson signs no receipt.
        assertThat(queueOf(flow.raiser())).doesNotContain(pending, draft);
        assertThat(queueOf(flow.stepUsers.get(2))).doesNotContain(pending);
        assertThat(queueOf(fx.user("sales", "SALES"))).doesNotContain(pending);

        // Signed, it moves on to the next step and leaves the signer's queue.
        fx.actAs(nextSigner);
        receiving.sign(pending, true, null);
        assertThat(queueOf(nextSigner)).doesNotContain(pending);
        assertThat(rowFor(flow.stepUsers.get(2), pending).step()).isEqualTo(3);
    }

    @Test
    void aRejectedOrApprovedDocumentWaitsOnNobody() {
        UUID approved = flow.approved();
        UUID rejected = flow.pending();
        fx.actAs(flow.stepUsers.get(1));
        receiving.sign(rejected, false, "Wrong supplier");
        for (UUID signer : flow.stepUsers) {
            assertThat(queueOf(signer)).doesNotContain(approved, rejected);
        }
    }

    @Test
    void aCountIsNotQueuedForSomeoneWhoTookPartInIt() {
        CountFlow cf = new CountFlow(fx, counts, receiving);
        cf.stock("10", true, "4");
        // No segregation rule pairs the Warehouse Manager with Finance, so one person may hold both (as CountTest).
        UUID both = fx.user("qboth", "WH_MANAGER", "FINANCE");
        UUID id = cf.open(CountScope.FULL);
        fx.actAs(both);
        var sheet = counts.countingSheet(id);
        for (int i = 0; i < sheet.lines().size(); i++) {
            if (sheet.lines().get(i).itemId().equals(cf.grn.glass)) {
                sheet.form().getLines().get(i).setQuantity(new BigDecimal("10"));
            }
        }
        counts.saveCounts(id, sheet.form());
        cf.count(id, l -> l.itemId().equals(cf.grn.glass) ? null : "4");
        cf.submit(id);

        // The verification is the Internal Controller's, who took no part.
        assertThat(rowFor(cf.verifier(), id).action()).isEqualTo("VERIFY");
        cf.verify(id, l -> l.itemId().equals(cf.grn.glass) ? "10" : "4");
        fx.actAs(cf.verifier());
        counts.sign(id, true, null);

        // Finance approves next: not the Finance officer who counted a line, and not the verifier.
        assertThat(queueOf(both)).doesNotContain(id);
        assertThat(queueOf(cf.verifier())).doesNotContain(id);
        assertThat(rowFor(cf.approver(), id).action()).isEqualTo("APPROVE");
    }

    @Test
    void aDocumentAtAnotherBranchIsQueuedAndAsksForTheSwitch() {
        UUID pending = flow.pending();
        UUID nextSigner = flow.stepUsers.get(1);
        // A second role at Rubavu, where nothing they hold reaches a Gahanga receipt's screens.
        fx.grantAt(nextSigner, "SALES", "RBV");
        fx.actAs(nextSigner, fx.branch("RBV"));

        var row = queue.awaiting(null, null, ALL).stream()
                .filter(r -> r.documentId().equals(pending)).findFirst().orElseThrow();
        assertThat(row.branchCode()).isEqualTo("KGL");
        assertThat(row.openHere()).isFalse();

        // Filters narrow it; they never widen what is the reader's.
        assertThat(queue.awaiting(fx.branch("RBV"), null, ALL)).extracting(AwaitingSignature::documentId)
                .doesNotContain(pending);
        assertThat(queue.awaiting(null, DocumentKind.DAO, ALL)).extracting(AwaitingSignature::documentId)
                .doesNotContain(pending);
        assertThat(queue.awaiting(fx.kigali(), DocumentKind.GRN, ALL)).extracting(AwaitingSignature::documentId)
                .contains(pending);
    }

    @Test
    void everyStepActionAChainUsesIsOneTheQueueMapsToARight() {
        // A label the queue does not know would leave that step waiting in nobody's queue, silently.
        assertThat(ApprovalQueueService.ACTIONS).containsAll(
                jdbc.sql("SELECT DISTINCT action_label FROM workflow_step").query(String.class).list());
    }

    @Test
    void theBadgeCountsTheWholeQueue() {
        flow.pending();
        fx.actAs(flow.stepUsers.get(1));
        assertThat(queue.count()).isEqualTo(queue.awaiting(null, null, ALL).size()).isPositive();
    }

    @Test
    void aRevokedRoleTakesTheDocumentOutOfTheQueue() {
        UUID pending = flow.pending();
        UUID nextSigner = flow.stepUsers.get(1);
        assertThat(queueOf(nextSigner)).contains(pending);
        jdbc.sql("""
                UPDATE user_role SET revoked_at = now(), revoked_by = (SELECT id FROM app_user WHERE username = 'admin'),
                                     revoke_reason = 'Moved to another post'
                 WHERE user_id = :user AND revoked_at IS NULL
                """).param("user", nextSigner).update();
        assertThat(queueOf(nextSigner)).doesNotContain(pending);
    }
}
