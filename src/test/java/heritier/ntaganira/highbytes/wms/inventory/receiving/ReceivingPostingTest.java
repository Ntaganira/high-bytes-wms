package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : ReceivingPostingTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Posts goods received notes end to end and proves the ledger moved by exactly the right amount
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.support.GrnFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against a real PostgreSQL 16 with Flyway V1 to V11.
 *
 * <p>The chain is read from whatever definition binds today, so the test
 * follows the 2026 policy chain now and the 2027 chain after 1 January, with
 * no change here.
 */
class ReceivingPostingTest extends IntegrationTest {

    @Autowired ReceivingService receiving;

    GrnFlow flow;

    @BeforeEach
    void cast() {
        flow = new GrnFlow(fx, receiving);
    }

    private BigDecimal sum(String sql, UUID item) {
        return jdbc.sql(sql).param("item", item).query(BigDecimal.class).single();
    }

    private String statusOf(UUID doc) {
        return jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", doc).query(String.class).single();
    }

    private long movementsFor(UUID doc) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM stock_movement m JOIN transaction_ticket t ON t.document_id = m.document_id
                 WHERE t.source_document_id = :doc
                """).param("doc", doc).query(Long.class).single();
    }

    @Test
    void aFullySignedReceiptPostsAndTheLedgerMovesByExactlyWhatWasSigned() {
        UUID grn = flow.approved();
        assertThat(statusOf(grn)).isEqualTo("APPROVED");
        assertThat(movementsFor(grn)).isZero();

        String ticket = flow.post(grn);

        assertThat(statusOf(grn)).isEqualTo("POSTED");
        assertThat(ticket).matches("TT-KGL-\\d{4}-\\d{4}");
        String serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", grn)
                .query(String.class).single();
        assertThat(serial).matches("GRN-KGL-\\d{4}-\\d{4}");

        // 4 boxes x 12.5 = 50.000 sheets, in the bin; 20 pieces, no bin.
        assertThat(sum("SELECT SUM(signed_quantity) FROM stock_movement WHERE item_id = :item", flow.glass))
                .isEqualByComparingTo("50.000");
        assertThat(sum("SELECT SUM(signed_quantity) FROM stock_movement WHERE item_id = :item", flow.silicone))
                .isEqualByComparingTo("20.000");

        // Invoice 400,000 + 60,000 = 460,000; landed 50,000 by value:
        // glass 50,000 x 400,000/460,000 = 43,478.26; silicone takes the remainder 6,521.74.
        assertThat(sum("SELECT SUM(value) FROM stock_movement WHERE item_id = :item", flow.glass))
                .isEqualByComparingTo("443478.26");
        assertThat(sum("SELECT SUM(value) FROM stock_movement WHERE item_id = :item", flow.silicone))
                .isEqualByComparingTo("66521.74");
        assertThat(sum("SELECT unit_cost FROM stock_movement WHERE item_id = :item", flow.glass))
                .isEqualByComparingTo("8869.5652");

        // The ticket carries the same total and is posted by Finance, who signed nothing.
        assertThat(jdbc.sql("""
                SELECT t.total_value FROM transaction_ticket t WHERE t.source_document_id = :doc
                """).param("doc", grn).query(BigDecimal.class).single()).isEqualByComparingTo("510000.00");
        Map<String, Object> ticketDoc = jdbc.sql("""
                SELECT d.status, d.posted_by FROM document d WHERE d.serial_no = :serial
                """).param("serial", ticket).query().singleRow();
        assertThat(ticketDoc.get("status")).isEqualTo("POSTED");
        assertThat(ticketDoc.get("posted_by")).isEqualTo(flow.poster);

        // stock_balance agrees with the ledger, place by place.
        for (UUID item : List.of(flow.glass, flow.silicone)) {
            Map<String, Object> balance = jdbc.sql("""
                    SELECT SUM(qty_on_hand) AS qty, SUM(total_value) AS value FROM stock_balance
                     WHERE item_id = :item AND location_id = :loc
                    """).param("item", item).param("loc", flow.location).query().singleRow();
            Map<String, Object> ledger = jdbc.sql("""
                    SELECT qty_on_hand AS qty, total_value AS value FROM stock_balance_from_ledger
                     WHERE item_id = :item AND location_id = :loc
                    """).param("item", item).param("loc", flow.location).query().singleRow();
            assertThat((BigDecimal) balance.get("qty")).isEqualByComparingTo((BigDecimal) ledger.get("qty"));
            assertThat((BigDecimal) balance.get("value")).isEqualByComparingTo((BigDecimal) ledger.get("value"));
        }
        // Glass sits in its bin, silicone in the place with no bin.
        assertThat(jdbc.sql("SELECT storage_bin_id FROM stock_balance WHERE item_id = :item")
                .param("item", flow.glass).query(UUID.class).single()).isEqualTo(flow.bin);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stock_balance WHERE item_id = :item AND storage_bin_id IS NULL")
                .param("item", flow.silicone).query(Long.class).single()).isEqualTo(1L);

        // The stock card's running balance after the movement is the balance itself.
        assertThat(sum("SELECT running_balance FROM stock_movement WHERE item_id = :item", flow.glass))
                .isEqualByComparingTo("50.000");

        // Posting is on the record, under the poster's own name.
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_log WHERE entity_id = :doc AND action = 'POST' AND actor_user_id = :poster
                """).param("doc", grn).param("poster", flow.poster).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void aSecondReceiptOfTheSameItemAddsToTheOneBalanceRowAndAveragesTheCost() {
        flow.post(flow.approved());                                   // silicone: 20 pcs, 66,521.74
        UUID second = flow.approved(flow.siliconeOnlyForm());         // 10 pcs at 4,000 = 40,000
        flow.post(second);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM stock_balance WHERE item_id = :item")
                .param("item", flow.silicone).query(Long.class).single())
                .as("one balance row per place, even with no bin").isEqualTo(1L);
        Map<String, Object> row = jdbc.sql("""
                SELECT qty_on_hand, total_value, average_unit_cost FROM stock_balance WHERE item_id = :item
                """).param("item", flow.silicone).query().singleRow();
        assertThat((BigDecimal) row.get("qty_on_hand")).isEqualByComparingTo("30.000");
        assertThat((BigDecimal) row.get("total_value")).isEqualByComparingTo("106521.74");
        assertThat((BigDecimal) row.get("average_unit_cost")).isEqualByComparingTo("3550.7247");
        assertThat(jdbc.sql("""
                SELECT running_balance FROM stock_movement WHERE item_id = :item ORDER BY id DESC LIMIT 1
                """).param("item", flow.silicone).query(BigDecimal.class).single()).isEqualByComparingTo("30.000");
    }

    @Test
    void theCreatorCannotSignAStepAfterTheFirst() {
        if (flow.chainRoles.size() < 2) return;
        // A raiser who also holds step 2's role: the role is not the obstacle, the rule is.
        UUID both = fx.user("both", flow.chainRoles.get(0), flow.chainRoles.get(1));
        fx.actAs(both);
        UUID id = receiving.create(flow.standardForm());
        receiving.submit(id);

        assertThatThrownBy(() -> receiving.sign(id, true, null))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("raised")
                .hasMessageContaining("may not sign a step after the first");
        assertThat(statusOf(id)).isEqualTo("PENDING");
        // The refusal is on the record, and survives the rollback.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :doc AND action = 'REJECT'")
                .param("doc", id).query(Long.class).single()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void aSignerCannotPostWhatTheySigned() {
        if (flow.chainRoles.size() < 2) return;
        // Someone who signs step 2 and also holds Finance's posting right.
        UUID signerAndFinance = fx.user("signfin", flow.chainRoles.get(1), "FINANCE");
        UUID id = flow.pending();
        fx.actAs(signerAndFinance);
        receiving.sign(id, true, null);
        for (int i = 2; i < flow.stepUsers.size(); i++) {
            fx.actAs(flow.stepUsers.get(i));
            receiving.sign(id, true, null);
        }
        assertThat(statusOf(id)).isEqualTo("APPROVED");

        fx.actAs(signerAndFinance);
        assertThatThrownBy(() -> receiving.post(id))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("signed")
                .hasMessageContaining("cannot also post it");
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(movementsFor(id)).isZero();
    }

    @Test
    void theRaiserCannotPostEither() {
        // A Finance user who also holds step 1's role and raises the receipt.
        UUID raiserAndFinance = fx.user("raisefin", flow.chainRoles.get(0), "FINANCE");
        fx.actAs(raiserAndFinance);
        UUID id = receiving.create(flow.standardForm());
        // Step 1 is signed by the raiser, so they have signed as well; either way the rule holds.
        receiving.submit(id);
        flow.signRemainingSteps(id);

        fx.actAs(raiserAndFinance);
        assertThatThrownBy(() -> receiving.post(id))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageMatching("(?s).*(raised|signed).*");
        assertThat(statusOf(id)).isEqualTo("APPROVED");
    }

    @Test
    void anEditAfterSubmitIsRefusedWithTheDatabasesReason() {
        UUID id = flow.pending();
        fx.actAs(flow.raiser());
        GrnForm form = receiving.formFor(id);
        form.getLines().get(0).setQuantity(new BigDecimal("400"));

        assertThatThrownBy(() -> receiving.update(id, form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("cannot change")
                .hasMessageContaining("approvers signed what it says");
        // Nothing changed.
        assertThat(jdbc.sql("SELECT quantity FROM goods_received_line WHERE document_id = :doc AND line_no = 1")
                .param("doc", id).query(BigDecimal.class).single()).isEqualByComparingTo("4");
    }

    @Test
    void aDraftCanBeEditedAndAStaleFormIsRefused() {
        UUID id = flow.draft();
        fx.actAs(flow.raiser());
        GrnForm first = receiving.formFor(id);
        GrnForm second = receiving.formFor(id);

        first.getLines().get(1).setQuantity(new BigDecimal("25"));
        receiving.update(id, first);
        assertThat(jdbc.sql("SELECT quantity FROM goods_received_line WHERE document_id = :doc AND line_no = 2")
                .param("doc", id).query(BigDecimal.class).single()).isEqualByComparingTo("25");
        assertThat(jdbc.sql("SELECT qty_base_uom FROM goods_received_line WHERE document_id = :doc AND line_no = 2")
                .param("doc", id).query(BigDecimal.class).single()).isEqualByComparingTo("25");

        second.getLines().get(1).setQuantity(new BigDecimal("99"));
        assertThatThrownBy(() -> receiving.update(id, second))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("changed by someone else");
    }

    @Test
    void anUnapprovedReceiptCannotBePosted() {
        UUID id = flow.pending();
        fx.actAs(flow.poster);
        assertThatThrownBy(() -> receiving.post(id))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("cannot move from PENDING to POSTED");
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(movementsFor(id)).isZero();
    }

    @Test
    void aDraftCannotBePostedEither() {
        UUID id = flow.draft();
        fx.actAs(flow.poster);
        assertThatThrownBy(() -> receiving.post(id)).isInstanceOf(ControlRefusedException.class);
        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void aPostedReceiptCannotBePostedTwiceOrCancelled() {
        UUID id = flow.approved();
        flow.post(id);
        fx.actAs(flow.poster);
        assertThatThrownBy(() -> receiving.post(id))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("already posted");
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> receiving.cancel(id, "changed my mind"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("posted")
                .hasMessageContaining("reversing document");
        assertThat(movementsFor(id)).isEqualTo(2L);
    }

    @Test
    void onlyThePostingRightPosts() {
        UUID id = flow.approved();
        fx.actAs(flow.stepUsers.get(flow.stepUsers.size() - 1));   // the last signer: no receiving.post
        assertThatThrownBy(() -> receiving.post(id)).isInstanceOf(AccessDeniedException.class);
        assertThat(statusOf(id)).isEqualTo("APPROVED");
    }

    @Test
    void aLockedBusinessDateRefusesThePostingAndNothingMoves() {
        UUID id = flow.approved();
        UUID kigali = fx.kigali();
        // Today is never locked by the rules (V16: a day closes once it is over), and the ledger dates every
        // movement today, so the lock is reached only by setting the close guard aside for this test's own row.
        // What is proved is the service's side: the ledger's refusal surfaces, and nothing is left half done.
        jdbc.sql("ALTER TABLE daily_close DISABLE TRIGGER daily_close_guard").update();
        try {
            jdbc.sql("""
                    INSERT INTO daily_close (branch_id, business_date, status, opening_value, receipts_value,
                                             dispatches_value, adjustments_value, closing_value, movement_count,
                                             exception_count, reconciled_by, reconciled_at, internal_controller_id,
                                             controller_signed_at, locked_at)
                    VALUES (:branch, kigali_today(), 'LOCKED', 0, 0, 0, 0, 0, 0, 0, :who, now(), :who, now(), now())
                    """).param("branch", kigali).param("who", flow.poster).update();
        } finally {
            jdbc.sql("ALTER TABLE daily_close ENABLE TRIGGER daily_close_guard").update();
        }
        try {
            fx.actAs(flow.poster);
            assertThatThrownBy(() -> receiving.post(id))
                    .isInstanceOf(ControlRefusedException.class)
                    .hasMessageContaining("closed")
                    .hasMessageContaining("backdated");
            assertThat(statusOf(id)).isEqualTo("APPROVED");
            assertThat(movementsFor(id)).isZero();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM stock_balance WHERE item_id = :item")
                    .param("item", flow.glass).query(Long.class).single())
                    .as("the balance rolled back with the refused movement").isZero();
        } finally {
            jdbc.sql("ALTER TABLE daily_close DISABLE TRIGGER daily_close_guard").update();
            try {
                jdbc.sql("DELETE FROM daily_close WHERE branch_id = :branch AND business_date = kigali_today()")
                        .param("branch", kigali).update();
            } finally {
                jdbc.sql("ALTER TABLE daily_close ENABLE TRIGGER daily_close_guard").update();
            }
        }
    }

    @Test
    void rejectionIsFinalAndKeepsTheReasonAndACorrectionIsANewNote() {
        if (flow.chainRoles.size() < 2) return;
        UUID id = flow.pending();
        fx.actAs(flow.stepUsers.get(1));
        assertThatThrownBy(() -> receiving.sign(id, false, "  "))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("needs a reason");
        receiving.sign(id, false, "Quantity on the delivery note does not match the count");
        assertThat(statusOf(id)).isEqualTo("REJECTED");

        // Nobody signs a rejected note, and it cannot be cancelled: it is final.
        fx.actAs(flow.stepUsers.get(flow.stepUsers.size() - 1));
        assertThatThrownBy(() -> receiving.sign(id, true, null)).isInstanceOf(ControlRefusedException.class);
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> receiving.cancel(id, "x")).isInstanceOf(ControlRefusedException.class);

        GrnForm copy = receiving.formFor(id);
        copy.setId(null);
        copy.setVersion(null);
        copy.setSupersedesDocumentId(id);
        UUID corrected = receiving.create(copy);
        assertThat(jdbc.sql("SELECT supersedes_document_id FROM document WHERE id = :id")
                .param("id", corrected).query(UUID.class).single()).isEqualTo(id);
    }

    @Test
    void aCorrectedCopyMustReplaceARejectedOrCancelledNote() {
        UUID live = flow.pending();
        fx.actAs(flow.raiser());
        GrnForm copy = flow.standardForm();
        copy.setSupersedesDocumentId(live);
        assertThatThrownBy(() -> receiving.create(copy))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("rejected or cancelled");
    }

    @Test
    void aCreatorWhoNoLongerHoldsTheCreateRightCannotCancel() {
        UUID id = flow.draft();
        // The raiser leaves the store: the role is revoked (by someone else, with a reason).
        jdbc.sql("""
                UPDATE user_role SET revoked_at = now(), revoke_reason = 'moved to another department',
                       revoked_by = (SELECT id FROM app_user WHERE username = 'admin')
                 WHERE user_id = :user
                """).param("user", flow.raiser()).update();
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> receiving.cancel(id, "changed my mind"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void anActionRefusedForTheWrongBranchIsRecordedAsRejectWithItsReason() {
        UUID id = flow.draft();
        UUID outsider = fx.userAt("RBV", "rbvout", flow.chainRoles.get(0));
        fx.actAs(outsider, fx.branch("RBV"));

        assertThatThrownBy(() -> receiving.cancel(id, "not mine")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> receiving.update(id, flow.standardForm())).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'REJECT' "
                        + "AND reason LIKE '%receiving.create%not held%'")
                .param("id", id).query(Long.class).single()).isEqualTo(2L);
        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void postingsThatTouchTheSameItemsInOppositeOrderNeverDeadlock() throws Exception {
        // Ten approved receipts of the same two items, half listing them glass-first and half silicone-first.
        List<UUID> notes = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            GrnForm form = flow.standardForm();
            if (i % 2 == 1) java.util.Collections.reverse(form.getLines());
            notes.add(flow.approved(form));
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(10);
        var go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
        for (UUID note : notes) {
            results.add(pool.submit(() -> {
                go.await();
                fx.actAs(flow.poster);
                try {
                    return receiving.post(note);
                } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
            }));
        }
        go.countDown();
        for (var result : results) result.get();     // any deadlock would surface here as a failure
        pool.shutdown();

        for (UUID note : notes) assertThat(statusOf(note)).isEqualTo("POSTED");
        assertThat(sum("SELECT SUM(signed_quantity) FROM stock_movement WHERE item_id = :item", flow.silicone))
                .isEqualByComparingTo("200.000");
        // Ticket lines keep their receipt line numbers, whichever way round the items were listed.
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM ticket_line tl JOIN transaction_ticket t ON t.document_id = tl.ticket_id
                  JOIN goods_received_line gl ON gl.document_id = t.source_document_id AND gl.line_no = tl.line_no
                 WHERE t.source_document_id IN (:notes) AND gl.item_id <> tl.item_id
                """).param("notes", notes).query(Long.class).single()).isZero();
    }

    @Test
    void aCancelledNoteKeepsItsSerialAndItsReason() {
        UUID id = flow.pending();
        String serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", id)
                .query(String.class).single();
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> receiving.cancel(id, " ")).isInstanceOf(ControlRefusedException.class);
        receiving.cancel(id, "Supplier sent the wrong consignment");

        Map<String, Object> row = jdbc.sql("SELECT serial_no, status, cancel_reason, cancelled_by FROM document WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(row.get("serial_no")).isEqualTo(serial);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("cancel_reason")).isEqualTo("Supplier sent the wrong consignment");
        assertThat(row.get("cancelled_by")).isEqualTo(flow.raiser());
    }

    @Test
    void aBondedReceiptNeedsItsCustomsReferenceAndGlassNeedsItsThickness() {
        // The database refuses; the service passes its reason on. (The form shows the same as a field error.)
        UUID rubavu = fx.branch("RBV");
        UUID bonded = fx.userAt("RBV", "rbvraiser", flow.chainRoles.get(0));
        fx.actAs(bonded, rubavu);
        GrnForm form = flow.standardForm();
        form.setLocationId(fx.location("RBV-BOND"));
        form.getLines().get(0).setStorageBinId(null);
        assertThatThrownBy(() -> receiving.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("customs reference");

        form.setCustomsReference("C-2026-0001");
        form.getLines().get(0).setMeasuredThicknessMm(null);
        assertThatThrownBy(() -> receiving.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("thickness");

        form.getLines().get(0).setMeasuredThicknessMm(new BigDecimal("6.00"));
        UUID id = receiving.create(form);
        assertThat(jdbc.sql("SELECT customs_reference FROM goods_received_note WHERE document_id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("C-2026-0001");
    }

    @Test
    void aUnitWithNoConversionIsRefusedWithAReasonNotAStackTrace() {
        GrnForm form = flow.standardForm();
        form.getLines().get(1).setUomId(flow.box);      // silicone has no BOX conversion
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> receiving.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("no conversion");
    }

    @Test
    void theReceiverCannotSignAStepThatIsNotTheirsAndTheChainIsTheOneBoundAtCreation() {
        UUID id = flow.pending();
        // The chain shown is the definition bound when the note was created.
        Integer boundVersion = jdbc.sql("""
                SELECT wd.version FROM document d JOIN workflow_definition wd ON wd.id = d.workflow_definition_id
                 WHERE d.id = :id
                """).param("id", id).query(Integer.class).single();
        fx.actAs(flow.raiser());
        var detail = receiving.detail(id);
        assertThat(detail.chainInfo().version()).isEqualTo(boundVersion);
        assertThat(detail.chain()).hasSize(flow.chainRoles.size());
        assertThat(detail.chain().get(0).state()).isEqualTo("signed");
        if (flow.chainRoles.size() > 1) {
            assertThat(detail.chain().get(1).state()).isEqualTo("current");
            // The raiser is told why, in words.
            assertThat(detail.actions().canSign()).isFalse();
            assertThat(detail.actions().signReason()).isNotBlank();
        }
    }
}
