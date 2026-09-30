package heritier.ntaganira.highbytes.wms.inventory.dispatch;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.dispatch
 * - File       : DispatchTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Delivers stock through the gate end to end and proves every refusal carries its reason
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ContentionException;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.support.DispatchFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against a real PostgreSQL 16 with Flyway V1 to V12.
 *
 * <p>The authorization chain is read from whatever definition binds today, so
 * the test follows the 2026 chain now and the 2027 chain after 1 January, with
 * no change here. Stock is received through a real goods received note first.
 */
class DispatchTest extends IntegrationTest {

    @Autowired ReceivingService receiving;
    @Autowired DispatchService dispatch;
    @Autowired DeliveryNoteService notes;
    @Autowired DocumentService documents;
    @Autowired org.thymeleaf.spring6.SpringTemplateEngine templates;

    DispatchFlow flow;

    @BeforeEach
    void cast() {
        flow = new DispatchFlow(fx, receiving, dispatch, notes);
    }

    private String statusOf(UUID doc) {
        return jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", doc).query(String.class).single();
    }

    private BigDecimal onHand(UUID item) {
        return jdbc.sql("SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock_balance WHERE item_id = :item")
                .param("item", item).query(BigDecimal.class).single();
    }

    private long movementsOut(UUID item) {
        return jdbc.sql("SELECT COUNT(*) FROM stock_movement WHERE item_id = :item AND direction = 'OUT'")
                .param("item", item).query(Long.class).single();
    }

    private long rejectRows(UUID doc) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'REJECT'")
                .param("id", doc).query(Long.class).single();
    }

    @Test
    void aReleasedAuthorizationIsDeliveredAtTheGateAndTheLedgerMovesOutExactly() {
        UUID dao = flow.released();
        assertThat(statusOf(dao)).isEqualTo("APPROVED");
        UUID note = flow.raiseNote(dao);
        assertThat(onHand(flow.grn.glass)).isEqualByComparingTo("50.000");

        String ticket = flow.post(note);

        assertThat(ticket).matches("TT-KGL-\\d{4}-\\d{4}");
        assertThat(statusOf(note)).isEqualTo("POSTED");
        // The authorization is never posted: its delivered state is the posted note.
        assertThat(statusOf(dao)).isEqualTo("APPROVED");
        fx.actAs(flow.raiser());
        assertThat(dispatch.detail(dao).header().displayState()).isEqualTo("DELIVERED");
        assertThat(dispatch.detail(dao).gate().state()).isEqualTo("DELIVERED");

        // 20 sheets left the bin, 5 pieces left the unbinned row, both at average cost.
        assertThat(jdbc.sql("SELECT SUM(signed_quantity) FROM stock_movement WHERE item_id = :item AND direction = 'OUT'")
                .param("item", flow.grn.glass).query(BigDecimal.class).single()).isEqualByComparingTo("-20.000");
        assertThat(jdbc.sql("SELECT value FROM stock_movement WHERE item_id = :item AND direction = 'OUT'")
                .param("item", flow.grn.glass).query(BigDecimal.class).single())
                .as("443,478.26 x 20 / 50").isEqualByComparingTo("177391.30");
        assertThat(jdbc.sql("SELECT unit_cost FROM stock_movement WHERE item_id = :item AND direction = 'OUT'")
                .param("item", flow.grn.glass).query(BigDecimal.class).single()).isEqualByComparingTo("8869.5650");
        assertThat(jdbc.sql("SELECT value FROM stock_movement WHERE item_id = :item AND direction = 'OUT'")
                .param("item", flow.grn.silicone).query(BigDecimal.class).single())
                .as("66,521.74 x 5 / 20").isEqualByComparingTo("16630.44");

        assertThat(onHand(flow.grn.glass)).isEqualByComparingTo("30.000");
        assertThat(onHand(flow.grn.silicone)).isEqualByComparingTo("15.000");
        Map<String, Object> glass = jdbc.sql("""
                SELECT qty_on_hand, total_value FROM stock_balance WHERE item_id = :item
                """).param("item", flow.grn.glass).query().singleRow();
        assertThat((BigDecimal) glass.get("total_value")).isEqualByComparingTo("266086.96");

        // stock_balance agrees with the ledger, place by place.
        for (UUID item : List.of(flow.grn.glass, flow.grn.silicone)) {
            Map<String, Object> balance = jdbc.sql("""
                    SELECT SUM(qty_on_hand) AS qty, SUM(total_value) AS value FROM stock_balance
                     WHERE item_id = :item AND location_id = :loc
                    """).param("item", item).param("loc", flow.grn.location).query().singleRow();
            Map<String, Object> ledger = jdbc.sql("""
                    SELECT qty_on_hand AS qty, total_value AS value FROM stock_balance_from_ledger
                     WHERE item_id = :item AND location_id = :loc
                    """).param("item", item).param("loc", flow.grn.location).query().singleRow();
            assertThat((BigDecimal) balance.get("qty")).isEqualByComparingTo((BigDecimal) ledger.get("qty"));
            assertThat((BigDecimal) balance.get("value")).isEqualByComparingTo((BigDecimal) ledger.get("value"));
        }

        // The ticket is a DELIVERY out of the location, posted by the gate user, who signed nothing.
        Map<String, Object> tt = jdbc.sql("""
                SELECT t.movement_type, t.direction, d.status, d.posted_by
                  FROM transaction_ticket t JOIN document d ON d.id = t.document_id WHERE d.serial_no = :serial
                """).param("serial", ticket).query().singleRow();
        assertThat(tt.get("movement_type")).isEqualTo("DELIVERY");
        assertThat(tt.get("direction")).isEqualTo("OUT");
        assertThat(tt.get("status")).isEqualTo("POSTED");
        assertThat(tt.get("posted_by")).isEqualTo(flow.gate);

        // The posting is on the record, under the gate user's own name.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :doc AND action = 'POST' AND actor_user_id = :who")
                .param("doc", note).param("who", flow.gate).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void aLineSplitAcrossBinsPostsWhenTheBinsAddUpExactly() {
        // A second bin with 30 more sheets, received through a real note.
        UUID bin2 = fx.bin("KGL-MAIN", "B2");
        var form = flow.grn.siliconeOnlyForm();
        form.getLines().get(0).setItemId(flow.grn.glass);
        form.getLines().get(0).setUomId(fx.uom("SHEET"));
        form.getLines().get(0).setQuantity(new BigDecimal("30"));
        form.getLines().get(0).setStorageBinId(bin2);
        form.getLines().get(0).setMeasuredThicknessMm(new BigDecimal("6.00"));
        flow.grn.post(flow.grn.approved(form));
        assertThat(onHand(flow.grn.glass)).isEqualByComparingTo("80.000");

        // Authorize 60 sheets: 40 from the first bin (holds 50) and 20 from the second (holds 30).
        var dao = flow.standardForm();
        dao.getLines().remove(1);
        dao.getLines().get(0).setQuantity(new BigDecimal("60"));
        UUID authorization = flow.released(dao);

        var load = flow.loadForm(authorization);
        var first = load.getLines().get(0);
        first.setQuantity(new BigDecimal("40"));
        var second = new DnLineForm();
        second.setAuthorizationLineId(first.getAuthorizationLineId());
        second.setQuantity(new BigDecimal("20"));
        second.setStorageBinId(bin2);
        second.setMeasuredThicknessMm(new BigDecimal("6.01"));
        load.getLines().add(second);
        fx.actAs(flow.gate);
        UUID note = notes.create(load);
        flow.post(note);

        assertThat(statusOf(note)).isEqualTo("POSTED");
        assertThat(movementsOut(flow.grn.glass)).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT qty_on_hand FROM stock_balance WHERE item_id = :i AND storage_bin_id = :b")
                .param("i", flow.grn.glass).param("b", flow.grn.bin).query(BigDecimal.class).single())
                .isEqualByComparingTo("10.000");
        assertThat(jdbc.sql("SELECT qty_on_hand FROM stock_balance WHERE item_id = :i AND storage_bin_id = :b")
                .param("i", flow.grn.glass).param("b", bin2).query(BigDecimal.class).single())
                .isEqualByComparingTo("10.000");
    }

    // ---- refusals, each with its reason ---------------------------------------------------

    @Test
    void aNoteIsRefusedWhileTheAuthorizationIsPendingAndBeforeTheReleaseSignature() {
        UUID pending = flow.pending();
        fx.actAs(flow.gate);
        var form = notes.prefill(pending);
        form.setVehicleRegistration("RAD 1 A");
        form.setDriverName("Driver");
        assertThatThrownBy(() -> notes.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("is PENDING")
                .hasMessageContaining("fully signed, including the Internal Controller");

        // Everything but the last signature (the Internal Controller's release) is still not enough.
        for (int i = 1; i < flow.stepUsers.size() - 1; i++) {
            fx.actAs(flow.stepUsers.get(i));
            dispatch.sign(pending, true, null);
        }
        fx.actAs(flow.gate);
        assertThatThrownBy(() -> notes.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("is PENDING");
        assertThat(dispatch.gateOf(pending).state()).isEqualTo("BLOCKED");
        // The banner names the one step still to sign: the release.
        assertThat(dispatch.gateOf(pending).waiting()).hasSize(1);
        assertThat(dispatch.gateOf(pending).waiting().get(0).actionLabel()).isEqualTo("RELEASE");
    }

    @Test
    void theRaiserOfTheAuthorizationCannotPostItsNote() {
        // Someone who raises authorizations and is also a warehouse manager (holds dispatch.post).
        UUID both = fx.user("both", flow.chainRoles.get(0), "WH_MANAGER");
        fx.actAs(both);
        UUID dao = dispatch.create(flow.standardForm());
        dispatch.submit(dao);
        flow.signRemainingSteps(dao);

        var form = flow.loadForm(dao);
        fx.actAs(both);
        UUID note = notes.create(form);
        assertThatThrownBy(() -> notes.post(note))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("cannot be posted by the person who raised authorization");
        assertThat(statusOf(note)).isEqualTo("DRAFT");
        assertThat(onHand(flow.grn.glass)).isEqualByComparingTo("50.000");
        // The note view says so in words, before anyone tries.
        assertThat(notes.detail(note).actions().canPost()).isFalse();
        assertThat(notes.detail(note).actions().postReason()).contains("you cannot post its delivery note");
    }

    @Test
    void aShortOrOverLoadIsRefusedAtThePostAndNothingMoves() {
        UUID dao = flow.released();
        for (String quantity : List.of("15", "25")) {
            var form = flow.loadForm(dao);
            form.getLines().get(0).setQuantity(new BigDecimal(quantity));
            fx.actAs(flow.gate);
            UUID note = notes.create(form);
            assertThatThrownBy(() -> notes.post(note))
                    .isInstanceOf(ControlRefusedException.class)
                    .hasMessageContaining("allows 20.000")
                    .hasMessageContaining("must equal the authorization exactly");
            assertThat(statusOf(note)).isEqualTo("DRAFT");
            fx.actAs(flow.gate);
            notes.cancel(note, "wrong load, redo");
        }
        assertThat(movementsOut(flow.grn.glass)).isZero();
    }

    @Test
    void aSecondLiveNoteForTheSameAuthorizationIsRefusedUntilTheFirstIsCancelled() {
        UUID dao = flow.released();
        UUID first = flow.raiseNote(dao);
        var again = flow.loadForm(dao);
        fx.actAs(flow.gate);
        assertThatThrownBy(() -> notes.create(again))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("already has a live delivery note");

        notes.cancel(first, "vehicle changed");
        assertThat(statusOf(first)).isEqualTo("CANCELLED");
        UUID second = notes.create(again);
        assertThat(statusOf(second)).isEqualTo("DRAFT");
    }

    @Test
    void insufficientStockIsRefusedWithItsReasonAndNoPartialEffect() {
        // Silicone (5 of 20) is fine and goes first; glass (60 of 50) is not. Nothing may move.
        var form = flow.standardForm();
        form.getLines().clear();
        form.getLines().add(flow.line(flow.grn.silicone, "PC", "5"));
        form.getLines().add(flow.line(flow.grn.glass, "SHEET", "60"));
        UUID dao = flow.released(form);
        UUID note = flow.raiseNote(dao);

        assertThatThrownBy(() -> flow.post(note))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("Not enough stock")
                .hasMessageContaining("50")
                .hasMessageContaining("60");
        assertThat(statusOf(note)).isEqualTo("DRAFT");
        assertThat(onHand(flow.grn.silicone)).as("the silicone line rolled back too").isEqualByComparingTo("20.000");
        assertThat(onHand(flow.grn.glass)).isEqualByComparingTo("50.000");
        assertThat(movementsOut(flow.grn.silicone)).isZero();
        assertThat(rejectRows(note)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void aDeliveredAuthorizationCannotBeCancelledNorItsPostedNote() {
        UUID dao = flow.released();
        UUID note = flow.raiseNote(dao);
        flow.post(note);

        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> dispatch.cancel(dao, "changed my mind"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("stock has already moved against it");
        assertThat(statusOf(dao)).isEqualTo("APPROVED");

        fx.actAs(flow.gate);
        assertThatThrownBy(() -> notes.cancel(note, "oops"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("posted");
        assertThat(statusOf(note)).isEqualTo("POSTED");
    }

    @Test
    void anAuthorizationWithALiveNoteCannotBeCancelledUnderIt() {
        UUID dao = flow.released();
        flow.raiseNote(dao);
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> dispatch.cancel(dao, "no longer needed"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("still answers to it");
    }

    @Test
    void glassNeedsItsThicknessMeasuredAtTheGate() {
        UUID dao = flow.released();
        var form = flow.loadForm(dao);
        form.getLines().get(0).setMeasuredThicknessMm(null);
        fx.actAs(flow.gate);
        assertThatThrownBy(() -> notes.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("needs its thickness measured at the gate");
    }

    @Test
    void aBlockedCustomerCannotBeAuthorized() {
        var form = flow.standardForm();
        form.setCustomerId(flow.blockedCustomer);
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> dispatch.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("is blocked");
    }

    @Test
    void aBondedAuthorizationNeedsItsCustomsReference() {
        UUID rubavu = fx.branch("RBV");
        UUID raiser = fx.userAt("RBV", "rbvdao", flow.chainRoles.get(0));
        fx.actAs(raiser, rubavu);
        var form = flow.standardForm();
        form.setLocationId(fx.location("RBV-BOND"));
        assertThatThrownBy(() -> dispatch.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("customs reference");
    }

    // ---- wrong branch, recorded ---------------------------------------------------------------

    @Test
    void anActionRefusedForTheWrongBranchIsRecordedAsRejectWithItsReason() {
        UUID dao = flow.pending();
        // Full dispatch rights, but at Rubavu: the Gahanga authorization is out of reach by its address.
        UUID rubavu = fx.branch("RBV");
        UUID outsider = fx.userAt("RBV", "rbvout", flow.chainRoles.get(0));
        fx.actAs(outsider, rubavu);

        long before = rejectRows(dao);
        assertThatThrownBy(() -> dispatch.cancel(dao, "not mine")).isInstanceOf(AccessDeniedException.class);
        assertThat(rejectRows(dao)).isEqualTo(before + 1);
        assertThat(jdbc.sql("""
                SELECT reason FROM audit_log WHERE entity_id = :id AND action = 'REJECT' ORDER BY id DESC LIMIT 1
                """).param("id", dao).query(String.class).single())
                .contains("dispatch.create").contains("not held");
        assertThat(statusOf(dao)).isEqualTo("PENDING");
    }

    @Test
    void aWrongBranchAuthorizationCreateIsRecordedAsRejectAndRefused() {
        UUID rubavu = fx.branch("RBV");
        UUID outsider = fx.userAt("RBV", "rbvnew", flow.chainRoles.get(0));
        fx.actAs(outsider, rubavu);
        long before = jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'REJECT' AND entity_label LIKE 'DAO · Raise%' AND actor_user_id = :u")
                .param("u", outsider).query(Long.class).single();
        var form = flow.standardForm();            // a Gahanga location, which a Rubavu right does not reach
        assertThatThrownBy(() -> dispatch.create(form)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'REJECT' AND entity_label LIKE 'DAO · Raise%' AND actor_user_id = :u")
                .param("u", outsider).query(Long.class).single()).isEqualTo(before + 1);
        assertThat(jdbc.sql("SELECT reason FROM audit_log WHERE action = 'REJECT' AND actor_user_id = :u ORDER BY id DESC LIMIT 1")
                .param("u", outsider).query(String.class).single()).contains("dispatch.create").contains("not held");

        // Opening another branch's form is refused but not recorded: it is a
        // read, and a prefetched or crawled link must not write audit rows in
        // someone's name. Only an action that would change something is.
        UUID dao = flow.pending();
        fx.actAs(outsider, rubavu);
        long rowsBefore = rejectRows(dao);
        assertThatThrownBy(() -> dispatch.formFor(dao)).isInstanceOf(AccessDeniedException.class);
        assertThat(rejectRows(dao)).isEqualTo(rowsBefore);
    }

    @Test
    void whoeverSignedTheReleaseCannotPostTheNoteAndIsToldWhy() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var release = new heritier.ntaganira.highbytes.wms.document.ChainStep(UUID.randomUUID(), 4, "RELEASE", true,
                UUID.randomUUID(), "INTERNAL_CTRL", "Internal Controller", "APPROVED", me, "Ines", null, null, "signed");
        var verify = new heritier.ntaganira.highbytes.wms.document.ChainStep(UUID.randomUUID(), 2, "VERIFY", true,
                UUID.randomUUID(), "WH_MANAGER", "Warehouse Manager", "APPROVED", other, "Bob", null, null, "signed");
        assertThat(DeliveryNoteService.releaseSignerReason("DAO-KGL-2026-0001", me, List.of(verify, release)))
                .contains("signed the release").contains("cannot post its delivery note").contains("DAO-KGL-2026-0001");
        assertThat(DeliveryNoteService.releaseSignerReason("DAO-KGL-2026-0001", other, List.of(verify, release)))
                .as("a verifier who is not the releaser may post, per the policy").isNull();
        assertThat(DeliveryNoteService.releaseSignerReason("DAO-KGL-2026-0001", UUID.randomUUID(), List.of(verify, release))).isNull();
    }

    // ---- a lost race is an error, not a refusal ----------------------------------------------

    @Test
    void aDeadlockIsAskedToTryAgainAndIsNotRecordedAsAControlRefusal() {
        UUID dao = flow.pending();
        var header = documents.header(dao);
        long before = rejectRows(dao);

        RuntimeException result = documents.refusedBy(header, "Post", new DeadlockLoserDataAccessException(
                "deadlock", new PSQLException("deadlock detected", PSQLState.DEADLOCK_DETECTED)));

        assertThat(result).isInstanceOf(ContentionException.class)
                .hasMessageContaining("Try again")
                .hasMessageContaining("same stock at the same moment");
        assertThat(rejectRows(dao)).as("no REJECT: no control refused anything").isEqualTo(before);

        // The same holds for a failure that only surfaced at commit.
        var atCommit = documents.refusedAtCommit(dao, "Post", new org.springframework.transaction.TransactionSystemException(
                "commit", new PSQLException("could not serialize access", PSQLState.SERIALIZATION_FAILURE)));
        assertThat(atCommit).isPresent();
        assertThat(atCommit.get()).isInstanceOf(ContentionException.class);
        assertThat(rejectRows(dao)).isEqualTo(before);
    }

    // ---- the banner: waiting and overdue ---------------------------------------------------------

    @Test
    void theBlockedBannerFlagsAStepOverduePastItsEscalationTimeAndOnlyThen() {
        UUID dao = flow.pending();
        fx.actAs(flow.raiser());
        var header = dispatch.find(dao);
        var doc = documents.header(dao);
        var chain = documents.chain(dao);
        var submitted = header.submittedAt();

        // Now: the first unsigned step has waited no time and is not overdue.
        var fresh = dispatch.gate(header, doc, chain, submitted.plusMinutes(5));
        assertThat(fresh.waiting().get(0).current()).isTrue();
        assertThat(fresh.waiting().get(0).overdue()).isFalse();

        // Three days on, it is overdue; a step not yet reached has waited on no one, so it never is.
        var late = dispatch.gate(header, doc, chain, submitted.plusHours(72));
        var current = late.waiting().get(0);
        assertThat(current.hoursWaiting()).isGreaterThanOrEqualTo(72L);
        assertThat(current.escalateAfterHours()).isNotNull();
        assertThat(current.overdue()).isTrue();
        assertThat(late.waiting().get(1).hoursWaiting()).isNull();
        assertThat(late.waiting().get(1).overdue()).isFalse();
        // Every unsigned step is named, with who at this branch could sign it.
        assertThat(late.waiting()).hasSize(flow.chainRoles.size() - 1);
        assertThat(current.holders()).isNotEmpty();
        assertThat(late.waiting().get(late.waiting().size() - 1).actionLabel()).isEqualTo("RELEASE");
    }

    @Test
    void theBannerFragmentRendersEachStateInWords() {
        var engine = templates;
        var waiting = new ReleaseGate.Waiting(2, "VERIFY", "Warehouse Manager", List.of("Alice"), 30L, 8, true, true);
        var blocked = new ReleaseGate("BLOCKED", "DAO-KGL-2026-0009", List.of(waiting), null, null, null, null,
                null, null, null);
        String html = render(blocked);
        assertThat(html).contains("Do not load").contains("DAO-KGL-2026-0009")
                .contains("Step 2 · VERIFY · Warehouse Manager").contains("can sign: Alice")
                .contains("waiting 30 h").contains("overdue (escalates after 8 h)").contains("gate-blocked");

        var released = new ReleaseGate("RELEASED", "DAO-KGL-2026-0009", List.of(), "Ines Controller",
                java.time.LocalDateTime.of(2026, 9, 30, 10, 5), null, null, null, null, null);
        assertThat(render(released)).contains("Released: ready to load").contains("Ines Controller")
                .contains("30 Sep 2026 10:05").contains("gate-released").doesNotContain("Do not load");

        var delivered = new ReleaseGate("DELIVERED", "DAO-KGL-2026-0009", List.of(), null, null, null,
                "DN-KGL-2026-0003", java.time.LocalDateTime.of(2026, 9, 30, 11, 0), "Gate User", null);
        assertThat(render(delivered)).contains("Delivered").contains("DN-KGL-2026-0003").contains("Gate User")
                .contains("gate-delivered");

        var voided = new ReleaseGate("VOID", "DAO-KGL-2026-0009", List.of(), null, null, null, null, null, null,
                "cancelled");
        assertThat(render(voided)).contains("Do not load").contains("cancelled").contains("gate-void");
        assertThat(engine).isNotNull();
    }

    private String render(ReleaseGate gate) {
        var context = new org.thymeleaf.context.Context();
        context.setVariable("gate", gate);
        return templates.process("fragments/ui", java.util.Set.of("releaseGate"), context);
    }

    @Test
    void thePendingQueueShowsADraftNoteToNoOne() {
        // A draft authorization awaits its raiser, not a signature; only a submitted one is queued.
        UUID draft = flow.draft(flow.standardForm());
        fx.actAs(flow.stepUsers.get(1));
        assertThat(dispatch.list(fx.kigali(), "DRAFT", true)).noneMatch(r -> r.id().equals(draft));
    }
}
