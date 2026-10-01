package heritier.ntaganira.highbytes.wms.inventory.damage;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.damage
 * - File       : DamageTest.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Posts every kind of return and damage report end to end and proves each refusal carries its reason
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DeliveryNoteService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DispatchService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DnForm;
import heritier.ntaganira.highbytes.wms.inventory.ledger.ConsignmentShares;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.inventory.transfer.ReceiptForm;
import heritier.ntaganira.highbytes.wms.inventory.transfer.ReceiptLineForm;
import heritier.ntaganira.highbytes.wms.inventory.transfer.ReceiptService;
import heritier.ntaganira.highbytes.wms.inventory.transfer.TransferService;
import heritier.ntaganira.highbytes.wms.support.DamageFlow;
import heritier.ntaganira.highbytes.wms.support.DispatchFlow;
import heritier.ntaganira.highbytes.wms.support.GrnFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import heritier.ntaganira.highbytes.wms.support.TransferFlow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against a real PostgreSQL 16 with Flyway V1 to V14.
 *
 * <p>The report chain, like every other, is read from whatever definition binds
 * today. Stock is received at Gahanga through a real goods received note first;
 * transfers and deliveries are driven through their own services, so what a loss
 * or a return is written against is what those really posted.
 */
class DamageTest extends IntegrationTest {

    @Autowired ReceivingService receiving;
    @Autowired TransferService transfers;
    @Autowired ReceiptService receipts;
    @Autowired DispatchService dispatch;
    @Autowired DeliveryNoteService notes;
    @Autowired DamageService reports;
    @Autowired PartyCheck parties;
    @Autowired DocumentService documents;
    @Autowired PlatformTransactionManager transactions;

    DamageFlow dmg;

    @BeforeEach
    void cast() {
        dmg = new DamageFlow(fx, reports);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String statusOf(UUID doc) {
        return jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", doc).query(String.class).single();
    }

    private BigDecimal onHand(UUID item, String locationCode) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(sb.qty_on_hand), 0) FROM stock_balance sb JOIN location l ON l.id = sb.location_id
                 WHERE sb.item_id = :item AND l.code = :loc
                """).param("item", item).param("loc", locationCode).query(BigDecimal.class).single();
    }

    private BigDecimal valueAt(UUID item, String locationCode) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(sb.total_value), 0) FROM stock_balance sb JOIN location l ON l.id = sb.location_id
                 WHERE sb.item_id = :item AND l.code = :loc
                """).param("item", item).param("loc", locationCode).query(BigDecimal.class).single();
    }

    /** The cache and the ledger agree, everywhere this item is. */
    private void assertBalanceMatchesLedger(UUID item) {
        List<Map<String, Object>> drift = jdbc.sql("""
                SELECT COALESCE(b.location_id, v.location_id)
                  FROM (SELECT location_id, SUM(qty_on_hand) AS qty, SUM(total_value) AS val
                          FROM stock_balance WHERE item_id = :item GROUP BY location_id) b
                  FULL JOIN (SELECT location_id, qty_on_hand AS qty, total_value AS val
                               FROM stock_balance_from_ledger WHERE item_id = :item) v
                    ON v.location_id = b.location_id
                 WHERE COALESCE(b.qty, 0) <> COALESCE(v.qty, 0) OR COALESCE(b.val, 0) <> COALESCE(v.val, 0)
                """).param("item", item).query().listOfRows();
        assertThat(drift).as("stock_balance differs from stock_balance_from_ledger for the item").isEmpty();
    }

    private record Move(String type, String direction, String location, BigDecimal quantity, BigDecimal value) {}

    /** What a report moved, in ledger order. */
    private List<Move> movesOf(UUID report) {
        return jdbc.sql("""
                SELECT t.movement_type, m.direction, l.code, m.quantity_base_uom, m.value
                  FROM transaction_ticket t
                  JOIN stock_movement m ON m.document_id = t.document_id
                  JOIN location l ON l.id = m.location_id
                 WHERE t.source_document_id = :id ORDER BY m.id
                """).param("id", report)
                .query((rs, n) -> new Move(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5)))
                .list();
    }

    private static List<BigDecimal> quantities(List<DamageLineForm> lines) {
        return lines.stream().map(l -> l.getQuantity().stripTrailingZeros()).toList();
    }

    private TransferFlow transferFlow() {
        return new TransferFlow(fx, receiving, transfers, receipts);
    }

    private DispatchFlow dispatchFlow() {
        return new DispatchFlow(fx, receiving, dispatch, notes);
    }

    /** A loss form for a dispatched transfer, raised by the report chain's first user. */
    private DamageForm lossForm(UUID transfer) {
        fx.actAs(dmg.raiser());
        DamageForm form = reports.prefillLoss(transfer);
        form.setReason("The lorry never reached Rubavu");
        return form;
    }

    private DamageForm returnForm(UUID note) {
        fx.actAs(dmg.raiser());
        DamageForm form = reports.prefillReturn(note);
        form.setReason("The customer brought part of the load back");
        return form;
    }

    private UUID posted(DamageForm form) {
        UUID id = dmg.approved(form);
        dmg.post(id);
        return id;
    }

    // ---- write-off ------------------------------------------------------------------------------

    @Test
    void aWriteOffLeavesAtAverageCostAndTheBalanceMatchesTheLedger() {
        GrnFlow grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());
        assertThat(onHand(grn.glass, "KGL-MAIN")).isEqualByComparingTo("50");

        UUID id = dmg.approved(dmg.writeOff(grn.glass, "SHEET", "10", grn.bin));
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(onHand(grn.glass, "KGL-MAIN")).as("nothing moves before it is posted").isEqualByComparingTo("50");

        String ticket = dmg.post(id);

        assertThat(ticket).matches("TT-KGL-\\d{4}-\\d{4}");
        assertThat(statusOf(id)).isEqualTo("POSTED");
        List<Move> moves = movesOf(id);
        assertThat(moves).hasSize(1);
        assertThat(moves.get(0).type()).isEqualTo("DAMAGE");
        assertThat(moves.get(0).direction()).isEqualTo("OUT");
        assertThat(moves.get(0).quantity()).isEqualByComparingTo("10");
        assertThat(moves.get(0).value()).as("443,478.26 x 10 / 50 at average cost").isEqualByComparingTo("88695.65");
        assertThat(onHand(grn.glass, "KGL-MAIN")).isEqualByComparingTo("40");
        assertThat(valueAt(grn.glass, "KGL-MAIN")).isEqualByComparingTo("354782.61");
        assertBalanceMatchesLedger(grn.glass);

        // The Finance poster is on the record, and every step of the way was audited.
        assertThat(jdbc.sql("SELECT posted_by FROM document WHERE id = :id").param("id", id)
                .query(UUID.class).single()).isEqualTo(dmg.poster);
        List<String> actions = jdbc.sql("SELECT action FROM audit_log WHERE entity_id = :id ORDER BY occurred_at, id")
                .param("id", id).query(String.class).list();
        assertThat(actions).contains("CREATE", "APPROVE", "POST");
        assertThat(actions.stream().filter("APPROVE"::equals).count()).isEqualTo(dmg.chainRoles.size());

        // The report lines carry what the ledger wrote.
        fx.actAs(dmg.poster);
        assertThat(reports.lines(id).get(0).value()).isEqualByComparingTo("88695.65");
    }

    @Test
    void aDraftIsEditedAudited_andASecondEditFromAStaleFormIsRefused() {
        GrnFlow grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());
        UUID id = dmg.draft(dmg.writeOff(grn.glass, "SHEET", "10", grn.bin));

        fx.actAs(dmg.raiser());
        DamageForm form = reports.formFor(id);
        form.getLines().get(0).setQuantity(new BigDecimal("12"));
        form.setReason("Counted again: twelve sheets, not ten");
        reports.update(id, form);

        assertThat(reports.lines(id).get(0).quantity()).isEqualByComparingTo("12");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'UPDATE'")
                .param("id", id).query(Long.class).single()).isEqualTo(1L);

        assertThatThrownBy(() -> reports.update(id, form))      // the same stale version again
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("changed by someone else");
    }

    @Test
    void aWriteOffBeyondTheStockOnHandIsRefusedAtPostingWithItsReasonAndNothingMoves() {
        GrnFlow grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());
        UUID id = dmg.approved(dmg.writeOff(grn.glass, "SHEET", "500", grn.bin));

        assertThatThrownBy(() -> dmg.post(id))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("Not enough stock");

        assertThat(statusOf(id)).as("the posting rolled back").isEqualTo("APPROVED");
        assertThat(onHand(grn.glass, "KGL-MAIN")).isEqualByComparingTo("50");
        assertThat(movesOf(id)).isEmpty();
        assertBalanceMatchesLedger(grn.glass);
    }

    // ---- loss in transit ---------------------------------------------------------------------------

    @Test
    void aWholeConsignmentLostInTransitClearsTransitAtItsOwnCost_andALaterReceiptIsRefused() {
        TransferFlow tf = transferFlow();
        UUID trf = tf.dispatched();
        assertThat(onHand(tf.grn.glass, "KGL-TRAN")).isEqualByComparingTo("20");

        UUID id = posted(lossForm(trf));

        List<Move> moves = movesOf(id);
        assertThat(moves).hasSize(2).allSatisfy(m -> {
            assertThat(m.type()).isEqualTo("DAMAGE");
            assertThat(m.direction()).isEqualTo("OUT");
            assertThat(m.location()).isEqualTo("KGL-TRAN");
        });
        assertThat(moves.get(0).value()).as("the glass consignment's own cost").isEqualByComparingTo("177391.30");
        assertThat(moves.get(1).value()).as("the silicone consignment's own cost").isEqualByComparingTo("16630.44");
        for (UUID item : List.of(tf.grn.glass, tf.grn.silicone)) {
            assertThat(onHand(item, "KGL-TRAN")).as("nothing left in transit").isEqualByComparingTo("0");
            assertThat(valueAt(item, "KGL-TRAN")).as("no value stranded in transit").isEqualByComparingTo("0");
            assertThat(onHand(item, "RBV-BOND")).isEqualByComparingTo("0");
            assertBalanceMatchesLedger(item);
        }
        fx.actAs(tf.raiser());
        assertThat(transfers.find(trf).displayState()).isEqualTo("WRITTEN_OFF");
        assertThat(transfers.lines(trf)).allSatisfy(l -> {
            assertThat(l.inTransitBase()).isEqualByComparingTo("0");
            assertThat(l.writtenOffBase()).isEqualByComparingTo(l.dispatchedBase());
        });

        // Rubavu cannot receive what has been written off: the form says so, and a receipt written by hand
        // is refused when it is posted.
        fx.actAs(tf.receiver, tf.rubavu);
        assertThatThrownBy(() -> receipts.prefill(trf))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("Nothing remains in transit");
        ReceiptForm byHand = new ReceiptForm();
        byHand.setTransferId(trf);
        for (UUID line : jdbc.sql("SELECT id FROM transfer_order_line WHERE document_id = :t ORDER BY line_no")
                .param("t", trf).query(UUID.class).list()) {
            ReceiptLineForm row = new ReceiptLineForm();
            row.setTransferLineId(line);
            row.setQuantity(BigDecimal.ONE);
            byHand.getLines().add(row);
        }
        UUID receipt = tf.raiseReceipt(byHand);
        assertThatThrownBy(() -> tf.postReceipt(receipt))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("already been written off");
        assertThat(onHand(tf.grn.glass, "RBV-BOND")).isEqualByComparingTo("0");
    }

    @Test
    void aReceiptFirstAndThenALossLeaveTransitAtExactlyZeroQuantityAndValue() {
        TransferFlow tf = transferFlow();
        UUID trf = tf.dispatched();
        ReceiptForm form = tf.receiptForm(trf);
        form.getLines().get(0).setQuantity(new BigDecimal("18"));      // glass: 18 of 20
        form.getLines().get(1).setQuantity(new BigDecimal("4"));       // silicone: 4 of 5
        tf.postReceipt(tf.raiseReceipt(form));
        assertThat(onHand(tf.grn.glass, "KGL-TRAN")).isEqualByComparingTo("2");
        assertThat(valueAt(tf.grn.glass, "KGL-TRAN")).isEqualByComparingTo("17739.13");

        DamageForm loss = lossForm(trf);
        assertThat(quantities(loss.getLines())).as("rows prefilled at what remains in transit")
                .containsExactly(BigDecimal.valueOf(2), BigDecimal.valueOf(1));
        UUID id = posted(loss);

        List<Move> moves = movesOf(id);
        assertThat(moves.get(0).value()).as("glass: 177,391.30 less the 159,652.17 received").isEqualByComparingTo("17739.13");
        assertThat(moves.get(1).value()).as("silicone: 16,630.44 less the 13,304.35 received").isEqualByComparingTo("3326.09");
        for (UUID item : List.of(tf.grn.glass, tf.grn.silicone)) {
            assertThat(onHand(item, "KGL-TRAN")).isEqualByComparingTo("0");
            assertThat(valueAt(item, "KGL-TRAN")).isEqualByComparingTo("0");
            assertBalanceMatchesLedger(item);
        }
        assertThat(valueAt(tf.grn.glass, "RBV-BOND")).isEqualByComparingTo("159652.17");
        fx.actAs(tf.raiser());
        assertThat(transfers.find(trf).displayState()).as("received and written off leave nothing in transit")
                .isEqualTo("RECEIVED");
    }

    @Test
    void aLossFirstAndThenTheReceiptOfWhatRemainsLeaveTransitAtExactlyZeroToo() {
        TransferFlow tf = transferFlow();
        UUID trf = tf.dispatched();

        // Two separate losses of a piece of silicone each, and two sheets of glass.
        DamageForm first = lossForm(trf);
        first.getLines().get(0).setQuantity(new BigDecimal("2"));
        first.getLines().get(1).setQuantity(new BigDecimal("1"));
        UUID one = posted(first);
        DamageForm second = lossForm(trf);
        second.getLines().get(0).setQuantity(BigDecimal.ZERO);          // nothing more of the glass
        second.getLines().get(1).setQuantity(new BigDecimal("1"));
        UUID two = posted(second);

        assertThat(movesOf(one).get(1).value()).isEqualByComparingTo("3326.09");
        assertThat(movesOf(two).get(0).value()).as("round(16,630.44 x 2 / 5) less round(16,630.44 x 1 / 5)")
                .isEqualByComparingTo("3326.09");
        assertThat(onHand(tf.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("3");

        // Rubavu now receives what remains: 18 sheets and 3 pieces, prefilled.
        ReceiptForm form = tf.receiptForm(trf);
        assertThat(form.getLines().stream().map(l -> l.getQuantity().stripTrailingZeros()).toList())
                .containsExactly(BigDecimal.valueOf(18), BigDecimal.valueOf(3));
        UUID receipt = tf.raiseReceipt(form);
        tf.postReceipt(receipt);

        for (UUID item : List.of(tf.grn.glass, tf.grn.silicone)) {
            assertThat(onHand(item, "KGL-TRAN")).as("exactly zero quantity").isEqualByComparingTo("0");
            assertThat(valueAt(item, "KGL-TRAN")).as("exactly zero value").isEqualByComparingTo("0");
            assertBalanceMatchesLedger(item);
        }
        // Silicone: 16,630.44 less the 6,652.18 the two losses took leaves 9,978.26 for the receipt.
        assertThat(valueAt(tf.grn.silicone, "RBV-BOND")).isEqualByComparingTo("9978.26");
        assertThat(valueAt(tf.grn.glass, "RBV-BOND")).isEqualByComparingTo("159652.17");
    }

    @Test
    void aLossBeyondWhatRemainsInTransitIsRefusedWithTheFigures_atDraftingAndAtPosting() {
        TransferFlow tf = transferFlow();
        UUID trf = tf.dispatched();
        ReceiptForm received = tf.receiptForm(trf);
        received.getLines().get(0).setQuantity(new BigDecimal("18"));
        tf.postReceipt(tf.raiseReceipt(received));

        DamageForm tooMuch = lossForm(trf);
        tooMuch.getLines().get(0).setQuantity(new BigDecimal("3"));     // only 2 remain
        fx.actAs(dmg.raiser());
        assertThatThrownBy(() -> reports.create(tooMuch))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("dispatched 20")
                .hasMessageContaining("18 has been received")
                .hasMessageContaining("only 2 remains in transit");

        // Two drafts each claim the whole remainder: both are fine as drafts, only one can be posted.
        DamageForm a = lossForm(trf);
        DamageForm b = lossForm(trf);
        UUID first = dmg.approved(a);
        UUID second = dmg.approved(b);
        dmg.post(first);
        assertThatThrownBy(() -> dmg.post(second))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("More cannot be lost than is in transit");
        assertThat(statusOf(second)).isEqualTo("APPROVED");
        assertThat(onHand(tf.grn.glass, "KGL-TRAN")).isEqualByComparingTo("0");
    }

    @Test
    void everyoneBehindATransferIsKeptOutOfItsLossReport_eachWithTheirOwnReason() {
        TransferFlow tf = transferFlow();
        UUID trf = tf.dispatched();
        UUID other = tf.dispatched();
        ReceiptForm form = tf.receiptForm(trf);
        UUID receipt = tf.raiseReceipt(form);
        tf.postReceipt(receipt);
        UUID draftReceipt = tf.raiseReceipt(tf.receiptForm(other));

        // Whoever raised the transfer is refused at the form, and again at the save, with the refusal recorded.
        fx.actAs(tf.raiser());
        assertThatThrownBy(() -> reports.prefillLoss(other))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("You raised transfer");
        DamageForm made = lossForm(other);
        fx.actAs(tf.raiser());
        assertThatThrownBy(() -> reports.create(made))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("You raised transfer");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'REJECT'")
                .param("id", other).query(Long.class).single()).isGreaterThanOrEqualTo(1L);

        // The others are judged by the database's own function, in words.
        assertThat(parties.reason(tf.dispatcher, "TRANSIT_LOSS", other, null)).contains("You dispatched");
        assertThat(parties.reason(tf.stepUsers.get(1), "TRANSIT_LOSS", other, null)).contains("You signed a step of");
        assertThat(parties.reason(tf.receiver, "TRANSIT_LOSS", trf, null)).contains("You posted the receipt of");
        assertThat(parties.reason(tf.receiver, "TRANSIT_LOSS", other, null))
                .as("a draft receipt already makes its author a party").contains("You recorded the arrival of");
        assertThat(parties.reason(dmg.raiser(), "TRANSIT_LOSS", other, null)).isNull();
        assertThat(draftReceipt).isNotNull();

        // Nor may any of them post it, or stand behind a line of it: the post check names the same people.
        UUID id = dmg.approved(lossForm(other));
        fx.actAs(dmg.raiser());
        DamageHeader header = reports.find(id);
        fx.actAs(tf.dispatcher);
        assertThat(reports.postBlocker(header)).contains("You dispatched");
        fx.actAs(dmg.poster);
        assertThat(reports.postBlocker(header)).isNull();

        // And whatever code path tries to write a line in a party's name, the database refuses it.
        UUID draft = dmg.draft(lossForm(other));
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql(
                "UPDATE damage_report_line SET entered_by = :u WHERE document_id = :d")
                .param("u", tf.dispatcher).param("d", draft).update()))
                .hasMessageContaining("cannot be entered by the person who dispatched");
    }

    @Test
    void whoeverWroteOffPartOfATransferCannotThenRecordItsArrival_untilTheLossIsCancelled() {
        TransferFlow tf = transferFlow();
        UUID trf = tf.dispatched();
        UUID loss = dmg.draft(lossForm(trf));               // a bystander to the transfer raises the loss

        // Given the receive right at the destination, the loss's author is still kept from the receipt, in words.
        UUID author = dmg.raiser();
        fx.grantAt(author, "ASST_WH_MANAGER", "RBV");
        fx.actAs(author, tf.rubavu);
        assertThatThrownBy(() -> receipts.prefill(trf))
                .isInstanceOf(ControlRefusedException.class).hasMessageContaining("You wrote off part of");
        assertThat(transfers.detail(trf).actions().receiveReason()).contains("You wrote off part of");
        ReceiptForm prepared = tf.receiptForm(trf);
        fx.actAs(author, tf.rubavu);
        assertThatThrownBy(() -> receipts.create(prepared))
                .isInstanceOf(ControlRefusedException.class).hasMessageContaining("You wrote off part of");

        // Whatever code path tries it, the database says the same.
        TransactionTemplate tx = new TransactionTemplate(transactions);
        UUID receipt = tf.raiseReceipt(tf.receiptForm(trf));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql(
                "UPDATE transfer_receipt_line SET entered_by = :u WHERE document_id = :d")
                .param("u", author).param("d", receipt).update()))
                .hasMessageContaining("cannot be entered by the person who wrote off part of");

        // Once the loss is cancelled, its author is no longer a party to the transfer.
        fx.actAs(author, fx.branch("KGL"));
        reports.cancel(loss, "Raised in error: the consignment turned up");
        assertThat(jdbc.sql("SELECT transfer_loss_author(:u, :t)").param("u", author).param("t", trf)
                .query(Boolean.class).single()).isFalse();
    }

    @Test
    void aLossCarriesTheCustomsReferenceItsConsignmentMovedUnder_andNoOther() {
        TransferFlow tf = transferFlow();
        UUID trf = tf.dispatched();
        String reference = jdbc.sql("SELECT customs_reference FROM transfer_order WHERE document_id = :id")
                .param("id", trf).query(String.class).single();
        assertThat(reference).as("a transfer into bonded Rubavu moves under a customs reference").isNotBlank();

        DamageForm form = lossForm(trf);
        assertThat(form.getCustomsReference()).as("prefilled from the transfer").isEqualTo(reference);

        form.setCustomsReference("C-SOMETHING-ELSE");
        fx.actAs(dmg.raiser());
        assertThatThrownBy(() -> reports.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("moved under customs reference " + reference);

        form.setCustomsReference(null);                     // left out: the database takes the transfer's
        fx.actAs(dmg.raiser());
        UUID id = reports.create(form);
        assertThat(jdbc.sql("SELECT customs_reference FROM damage_report WHERE document_id = :id")
                .param("id", id).query(String.class).single()).isEqualTo(reference);
    }

    // ---- customer return ---------------------------------------------------------------------------

    @Test
    void twoPartialReturnsSumExactlyToTheValueTheGoodsLeftWith() {
        DispatchFlow df = dispatchFlow();
        UUID dao = df.released();
        UUID note = df.raiseNote(dao);
        df.post(note);
        BigDecimal deliveredGlass = jdbc.sql("""
                SELECT m.value FROM transaction_ticket t JOIN stock_movement m ON m.document_id = t.document_id
                 WHERE t.source_document_id = :dn AND m.item_id = :item
                """).param("dn", note).param("item", df.grn.glass).query(BigDecimal.class).single();
        assertThat(deliveredGlass).isEqualByComparingTo("177391.30");

        // The customer sends 7 of 20 sheets and 2 of 5 pieces back first.
        DamageForm first = returnForm(note);
        first.getLines().get(0).setQuantity(new BigDecimal("7"));
        first.getLines().get(1).setQuantity(new BigDecimal("2"));
        UUID one = posted(first);
        List<Move> firstMoves = movesOf(one);
        assertThat(firstMoves).allSatisfy(m -> {
            assertThat(m.type()).isEqualTo("RETURN");
            assertThat(m.direction()).isEqualTo("IN");
            assertThat(m.location()).isEqualTo("KGL-QUAR");
        });
        assertThat(firstMoves.get(0).value()).as("round(177,391.30 x 7 / 20)").isEqualByComparingTo("62086.96");

        // The rest comes back later: the prefill offers what could still come back.
        DamageForm second = returnForm(note);
        assertThat(quantities(second.getLines())).containsExactly(BigDecimal.valueOf(13), BigDecimal.valueOf(3));
        UUID two = posted(second);
        List<Move> secondMoves = movesOf(two);
        assertThat(secondMoves.get(0).value()).as("177,391.30 less the 62,086.96 already back")
                .isEqualByComparingTo("115304.34");
        assertThat(firstMoves.get(0).value().add(secondMoves.get(0).value()))
                .as("the two returns bring back exactly what left").isEqualByComparingTo(deliveredGlass);

        assertThat(onHand(df.grn.glass, "KGL-QUAR")).isEqualByComparingTo("20");
        assertThat(valueAt(df.grn.glass, "KGL-QUAR")).isEqualByComparingTo("177391.30");
        assertThat(onHand(df.grn.glass, "KGL-MAIN")).as("back in quarantine, not on sale").isEqualByComparingTo("30");
        assertBalanceMatchesLedger(df.grn.glass);
        assertBalanceMatchesLedger(df.grn.silicone);

        // Everything delivered has now come back: no more can.
        fx.actAs(dmg.raiser());
        assertThatThrownBy(() -> reports.prefillReturn(note))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("already come back");
        fx.actAs(df.gate);
        assertThat(notes.lines(note)).allSatisfy(l -> assertThat(l.returnedBase()).isEqualByComparingTo(l.quantityBase()));
    }

    @Test
    void aReturnBeyondWhatWasDeliveredLessWhatHasComeBackIsRefusedWithTheFigures() {
        DispatchFlow df = dispatchFlow();
        UUID note = df.raiseNote(df.released());
        df.post(note);

        DamageForm form = returnForm(note);
        form.getLines().get(0).setQuantity(new BigDecimal("21"));      // 20 were delivered
        fx.actAs(dmg.raiser());
        assertThatThrownBy(() -> reports.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("delivered 20")
                .hasMessageContaining("at most 20 can be returned");

        // Two drafts of 15 each are fine as drafts; the second cannot be posted once the first is.
        DamageForm a = returnForm(note);
        a.getLines().get(0).setQuantity(new BigDecimal("15"));
        a.getLines().get(1).setQuantity(BigDecimal.ZERO);
        DamageForm b = returnForm(note);
        b.getLines().get(0).setQuantity(new BigDecimal("15"));
        b.getLines().get(1).setQuantity(BigDecimal.ZERO);
        UUID first = dmg.approved(a);
        UUID second = dmg.approved(b);
        dmg.post(first);
        assertThatThrownBy(() -> dmg.post(second))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("More cannot come back than was delivered");
        assertThat(statusOf(second)).isEqualTo("APPROVED");
    }

    @Test
    void aReturnIsNotRaisedByWhoeverLetTheGoodsOutOrAuthorizedThem() {
        DispatchFlow df = dispatchFlow();
        UUID note = df.raiseNote(df.released());
        df.post(note);
        DamageForm made = returnForm(note);

        // The gate posted the delivery note; a Warehouse Manager, so holds the right to raise a report.
        fx.actAs(df.gate);
        assertThatThrownBy(() -> reports.prefillReturn(note))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("delivery note")
                .hasMessageContaining("at the gate");
        assertThatThrownBy(() -> reports.create(made))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("at the gate");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'REJECT'")
                .param("id", note).query(Long.class).single()).isGreaterThanOrEqualTo(1L);

        // Whoever raised the authorization behind it is refused by the same function.
        assertThat(parties.reason(df.raiser(), "CUSTOMER_RETURN", null, note)).contains("authorization behind");
        assertThat(parties.reason(df.gate, "CUSTOMER_RETURN", null, note)).contains("at the gate");
        assertThat(parties.reason(dmg.raiser(), "CUSTOMER_RETURN", null, note)).isNull();

        // Posting is judged the same way: the gate is refused as poster, everyone else is fine.
        UUID id = dmg.approved(made);
        fx.actAs(dmg.raiser());
        DamageHeader header = reports.find(id);
        fx.actAs(df.gate);
        assertThat(reports.postBlocker(header)).contains("at the gate");
        fx.actAs(dmg.poster);
        assertThat(reports.postBlocker(header)).isNull();
    }

    @Test
    void aReturnIsNotRaisedByWhoeverDrewUpTheNoteOrSignedTheAuthorization() {
        DispatchFlow df = dispatchFlow();
        UUID dao = df.released();
        // One Warehouse Manager draws up the load, the gate posts it.
        UUID loader = fx.user("loader", "WH_MANAGER");
        DnForm load = df.loadForm(dao);
        fx.actAs(loader);
        UUID note = notes.create(load);
        df.post(note);

        fx.actAs(loader);
        assertThatThrownBy(() -> reports.prefillReturn(note))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("You drew up delivery note");

        // Every signer of the authorization, not only its releaser: the raiser signed step 1.
        assertThat(parties.reason(df.raiser(), "CUSTOMER_RETURN", null, note)).contains("raised the authorization behind");
        for (UUID signer : df.stepUsers.subList(1, df.stepUsers.size())) {
            assertThat(parties.reason(signer, "CUSTOMER_RETURN", null, note))
                    .contains("You signed the authorization behind delivery note");
        }
        assertThat(parties.reason(dmg.raiser(), "CUSTOMER_RETURN", null, note)).isNull();

        // Whatever code path tries it, the database says the same.
        UUID draft = dmg.draft(returnForm(note));
        UUID verifier = df.stepUsers.get(df.stepUsers.size() - 2);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql(
                "UPDATE damage_report_line SET entered_by = :u WHERE document_id = :d")
                .param("u", verifier).param("d", draft).update()))
                .hasMessageContaining("cannot be entered by a person who signed the authorization behind delivery note");
    }

    // ---- quarantine release --------------------------------------------------------------------------

    @Test
    void aReleaseMovesQuarantineStockToASellableLocationAtNoChangeOfValue() {
        DispatchFlow df = dispatchFlow();
        UUID note = df.raiseNote(df.released());
        df.post(note);
        UUID back = posted(returnForm(note));
        assertThat(back).isNotNull();
        BigDecimal mainBefore = onHand(df.grn.glass, "KGL-MAIN");
        BigDecimal mainValueBefore = valueAt(df.grn.glass, "KGL-MAIN");
        assertThat(onHand(df.grn.glass, "KGL-QUAR")).isEqualByComparingTo("20");

        UUID id = posted(dmg.release(df.grn.glass, "SHEET", "12"));

        List<Move> moves = movesOf(id);
        assertThat(moves).hasSize(2);
        assertThat(moves.get(0).type()).isEqualTo("TRANSFER_OUT");
        assertThat(moves.get(0).location()).isEqualTo("KGL-QUAR");
        assertThat(moves.get(1).type()).isEqualTo("TRANSFER_IN");
        assertThat(moves.get(1).location()).isEqualTo("KGL-MAIN");
        assertThat(moves.get(0).value()).as("177,391.30 x 12 / 20 at quarantine's average").isEqualByComparingTo("106434.78");
        assertThat(moves.get(1).value()).as("in at exactly what left").isEqualByComparingTo(moves.get(0).value());
        assertThat(onHand(df.grn.glass, "KGL-QUAR")).isEqualByComparingTo("8");
        assertThat(valueAt(df.grn.glass, "KGL-QUAR")).isEqualByComparingTo("70956.52");
        assertThat(onHand(df.grn.glass, "KGL-MAIN")).isEqualByComparingTo(mainBefore.add(new BigDecimal("12")));
        assertThat(valueAt(df.grn.glass, "KGL-MAIN")).isEqualByComparingTo(mainValueBefore.add(new BigDecimal("106434.78")));
        assertBalanceMatchesLedger(df.grn.glass);
    }

    @Test
    void aReleaseIntoALocationThatCannotBeSoldFromIsRefusedWithItsReason() {
        GrnFlow grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());

        for (String notSellable : List.of("KGL-CUT", "KGL-QUAR", "KGL-TRAN")) {
            DamageForm form = dmg.release(grn.glass, "SHEET", "1");
            form.setToLocationId(fx.location(notSellable));
            fx.actAs(dmg.raiser());
            assertThatThrownBy(() -> reports.create(form)).as(notSellable)
                    .isInstanceOf(ControlRefusedException.class)
                    .hasMessageContaining("sellable");
        }
        // And a release is only FROM quarantine.
        DamageForm fromShelf = dmg.release(grn.glass, "SHEET", "1");
        fromShelf.setFromLocationId(fx.location("KGL-MAIN"));
        fx.actAs(dmg.raiser());
        assertThatThrownBy(() -> reports.create(fromShelf))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("released FROM quarantine");
    }

    // ---- bonded stock and customs ------------------------------------------------------------------------

    @Test
    void aReportOnBondedStockNeedsACustomsReference() {
        UUID rubavuManager = fx.userAt("RBV", "rbvmgr", "WH_MANAGER");
        GrnFlow grn = new GrnFlow(fx, receiving);
        DamageForm form = new DamageForm();
        form.setKind(DamageKind.WRITE_OFF);
        form.setReasonCode("EXPIRED");
        form.setReason("Sealant past its shelf life");
        form.setFromLocationId(fx.location("RBV-BOND"));
        form.getLines().add(dmg.itemLine(grn.silicone, "PC", "1", null, null));

        fx.actAs(rubavuManager, fx.branch("RBV"));
        assertThatThrownBy(() -> reports.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("customs reference");

        form.setCustomsReference("C-RBV-2026-0042");
        UUID id = reports.create(form);
        assertThat(statusOf(id)).isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT customs_reference FROM damage_report WHERE document_id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("C-RBV-2026-0042");
    }

    // ---- posting and cancelling ----------------------------------------------------------------------------

    @Test
    void aReportIsNotPostedBeforeItIsFullyApprovedNorByAnyoneWithoutThePostRight() {
        GrnFlow grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());
        UUID pending = dmg.pending(dmg.writeOff(grn.glass, "SHEET", "5", grn.bin));

        assertThatThrownBy(() -> dmg.post(pending))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("only an approved document is posted");
        assertThat(statusOf(pending)).isEqualTo("PENDING");

        dmg.signRemainingSteps(pending);
        // Only Finance holds damage.post: the raiser and every signer are refused before the method runs.
        for (UUID who : dmg.stepUsers) {
            fx.actAs(who);
            assertThatThrownBy(() -> reports.post(pending)).isInstanceOf(AccessDeniedException.class);
        }
        assertThat(statusOf(pending)).isEqualTo("APPROVED");
        assertThat(onHand(grn.glass, "KGL-MAIN")).isEqualByComparingTo("50");
    }

    @Test
    void theDatabaseRefusesAPosterWhoRaisedOrSignedTheReportWhateverCodePathTriesIt() {
        GrnFlow grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());
        UUID id = dmg.approved(dmg.writeOff(grn.glass, "SHEET", "5", grn.bin));
        TransactionTemplate tx = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql(
                "UPDATE document SET status = 'POSTED', posted_by = :u, version = version + 1 WHERE id = :id")
                .param("u", dmg.raiser()).param("id", id).update()))
                .hasMessageContaining("cannot be posted by the person who raised it");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql(
                "UPDATE document SET status = 'POSTED', posted_by = :u, version = version + 1 WHERE id = :id")
                .param("u", dmg.stepUsers.get(1)).param("id", id).update()))
                .hasMessageContaining("cannot also post it");
        assertThat(statusOf(id)).isEqualTo("APPROVED");
    }

    @Test
    void aPostedReportCannotBeCancelledAndCannotBePostedTwice() {
        GrnFlow grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());
        UUID id = dmg.approved(dmg.writeOff(grn.glass, "SHEET", "5", grn.bin));
        dmg.post(id);

        fx.actAs(dmg.raiser());
        assertThatThrownBy(() -> reports.cancel(id, "Changed my mind"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("is posted, so it cannot be cancelled");
        assertThatThrownBy(() -> dmg.post(id))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("already posted");
        assertThat(statusOf(id)).isEqualTo("POSTED");
        assertThat(onHand(grn.glass, "KGL-MAIN")).as("the stock moved once").isEqualByComparingTo("45");
    }

    @Test
    void aCancelledDraftKeepsItsSerialAndItsReason() {
        GrnFlow grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());
        UUID id = dmg.draft(dmg.writeOff(grn.glass, "SHEET", "5", grn.bin));
        String serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", id).query(String.class).single();

        fx.actAs(dmg.raiser());
        assertThatThrownBy(() -> reports.cancel(id, " "))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("needs a reason");
        reports.cancel(id, "Raised against the wrong item");

        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo(serial);
        assertThat(serial).matches("DMG-KGL-\\d{4}-\\d{4}");
    }

    @Test
    void aPendingReportIsInTheQueueOfWhoeverSignsItsNextStepAndOnlyThem() {
        GrnFlow grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());
        UUID pending = dmg.pending(dmg.writeOff(grn.glass, "SHEET", "5", grn.bin));
        UUID verifier = dmg.stepUsers.get(1);

        fx.actAs(verifier);
        assertThat(documents.awaitingSignatureOf(heritier.ntaganira.highbytes.wms.document.DocumentKind.DMG,
                fx.kigali(), verifier)).contains(pending);
        assertThat(reports.list(fx.kigali(), DamageKind.WRITE_OFF, "PENDING", true))
                .extracting(DamageRow::id).contains(pending);
        // The raiser signed step 1 and has nothing more to sign; Finance signs none of it.
        fx.actAs(dmg.raiser());
        assertThat(reports.list(fx.kigali(), null, null, true)).extracting(DamageRow::id).doesNotContain(pending);
        fx.actAs(dmg.poster);
        assertThat(reports.list(fx.kigali(), null, null, true)).extracting(DamageRow::id).doesNotContain(pending);
        assertThat(reports.list(fx.kigali(), null, "PENDING", false)).extracting(DamageRow::id).contains(pending);
    }

    // ---- branches and the audit trail ------------------------------------------------------------------------

    @Test
    void anotherBranchCannotRaiseReadOrPostAReportOfThisOne_andTheRefusedActionIsRecorded() {
        GrnFlow grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());
        UUID id = dmg.approved(dmg.writeOff(grn.glass, "SHEET", "5", grn.bin));
        UUID rubavuManager = fx.userAt("RBV", "rbvmgr", "WH_MANAGER");
        UUID rubavuFinance = fx.userAt("RBV", "rbvfin", "FINANCE");

        // Reading is refused and leaves no trace: a crawled link must not write audit rows in someone's name.
        long before = jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id").param("id", id)
                .query(Long.class).single();
        fx.actAs(rubavuManager, fx.branch("RBV"));
        assertThatThrownBy(() -> reports.find(id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reports.detail(id)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id").param("id", id)
                .query(Long.class).single()).isEqualTo(before);

        // Acting is refused and recorded as a REJECT.
        assertThatThrownBy(() -> reports.create(dmg.writeOff(grn.glass, "SHEET", "1", grn.bin)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_log WHERE action = 'REJECT' AND actor_user_id = :u
                   AND entity_label LIKE 'DMG%'
                """).param("u", rubavuManager).query(Long.class).single()).isEqualTo(1L);
        fx.actAs(rubavuFinance, fx.branch("RBV"));
        assertThatThrownBy(() -> reports.post(id)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'REJECT' AND actor_user_id = :u")
                .param("id", id).param("u", rubavuFinance).query(Long.class).single()).isEqualTo(1L);
        assertThat(statusOf(id)).isEqualTo("APPROVED");
    }

    // ---- the pure rules ----------------------------------------------------------------------------------------

    @Test
    void theSharedShareRuleAddsUpToExactlyTheValueWhateverTheSplit() {
        BigDecimal v = new BigDecimal("16630.44");
        BigDecimal total = new BigDecimal("5");
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal before = BigDecimal.ZERO;
        for (String q : List.of("1", "1", "2", "1")) {
            BigDecimal quantity = new BigDecimal(q);
            sum = sum.add(ConsignmentShares.share(v, total, before, quantity));
            before = before.add(quantity);
        }
        assertThat(sum).isEqualByComparingTo(v);
        assertThat(ConsignmentShares.share(v, total, new BigDecimal("4"), BigDecimal.ONE))
                .as("the last piece takes whatever is left").isEqualByComparingTo("3326.09");
    }

    @Test
    void thePartyWordingNamesWhatThePersonDidAndWhyItMatters() {
        assertThat(PartyCheck.describe("dispatched", "TRANSIT_LOSS", "transfer TRF-KGL-2026-0001"))
                .startsWith("You dispatched transfer TRF-KGL-2026-0001")
                .contains("write off what went missing");
        assertThat(PartyCheck.describe("released", "CUSTOMER_RETURN", "delivery note DN-KGL-2026-0001"))
                .contains("at the gate").contains("take back what came back");
        assertThat(PartyCheck.describe("approved", "CUSTOMER_RETURN", "delivery note DN-KGL-2026-0001"))
                .startsWith("You signed the authorization behind delivery note DN-KGL-2026-0001");
    }
}
