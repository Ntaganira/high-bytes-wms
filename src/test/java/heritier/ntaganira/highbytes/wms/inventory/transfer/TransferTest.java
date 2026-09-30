package heritier.ntaganira.highbytes.wms.inventory.transfer;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.transfer
 * - File       : TransferTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Moves stock from Gahanga to Rubavu through transit and proves every refusal carries its reason
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.DbRefusal;
import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.document.DocumentKind;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import heritier.ntaganira.highbytes.wms.support.TransferFlow;
import heritier.ntaganira.highbytes.wms.inventory.ledger.MovementRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against a real PostgreSQL 16 with Flyway V1 to V13.
 *
 * <p>The transfer chain is read from whatever definition binds today, so the
 * test follows the 2026 chain now and the 2027 chain after 1 January, with no
 * change here. Stock is received at Gahanga through a real goods received note
 * first; Rubavu is a bonded branch, so every transfer here carries a customs
 * reference.
 */
class TransferTest extends IntegrationTest {

    @Autowired ReceivingService receiving;
    @Autowired TransferService transfers;
    @Autowired ReceiptService receipts;
    @Autowired DocumentService documents;
    @Autowired PlatformTransactionManager transactions;
    @Autowired heritier.ntaganira.highbytes.wms.inventory.ledger.LedgerService ledger;

    TransferFlow flow;

    @BeforeEach
    void cast() {
        flow = new TransferFlow(fx, receiving, transfers, receipts);
    }

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

    private Map<String, Object> position(UUID transfer, UUID item) {
        return jdbc.sql("SELECT dispatched_base, received_base, in_transit_base FROM transfer_line_position "
                        + "WHERE transfer_id = :t AND item_id = :i")
                .param("t", transfer).param("i", item).query().singleRow();
    }

    private String displayStateOf(UUID transfer) {
        fx.actAs(flow.raiser());
        return transfers.find(transfer).displayState();
    }

    // ---- the whole journey ---------------------------------------------------------------------

    @Test
    void aTransferLeavesGahangaIntoTransitAndArrivesShortAtRubavuAtCostWithTheShortfallLeftInTransit() {
        UUID trf = flow.approved();
        assertThat(statusOf(trf)).isEqualTo("APPROVED");
        assertThat(displayStateOf(trf)).isEqualTo("APPROVED");

        String outTicket = flow.dispatch(trf);

        assertThat(outTicket).matches("TT-KGL-\\d{4}-\\d{4}");
        assertThat(statusOf(trf)).isEqualTo("POSTED");
        assertThat(displayStateOf(trf)).isEqualTo("DISPATCHED");

        // Source down, transit up, both at cost: 443,478.26 x 20 / 50 and 66,521.74 x 5 / 20.
        assertThat(onHand(flow.grn.glass, "KGL-MAIN")).isEqualByComparingTo("30.000");
        assertThat(onHand(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo("20.000");
        assertThat(valueAt(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo("177391.30");
        assertThat(valueAt(flow.grn.glass, "KGL-MAIN")).isEqualByComparingTo("266086.96");
        assertThat(onHand(flow.grn.silicone, "KGL-MAIN")).isEqualByComparingTo("15.000");
        assertThat(onHand(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("5.000");
        assertThat(valueAt(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("16630.44");
        assertThat((BigDecimal) position(trf, flow.grn.glass).get("in_transit_base")).isEqualByComparingTo("20");

        // The dispatcher signed nothing and raised nothing, and the dispatch is on the record under their name.
        assertThat(jdbc.sql("SELECT posted_by FROM document WHERE id = :id").param("id", trf)
                .query(UUID.class).single()).isEqualTo(flow.dispatcher);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'POST' AND actor_user_id = :u")
                .param("id", trf).param("u", flow.dispatcher).query(Long.class).single()).isEqualTo(1L);

        // Rubavu receives short: 18 of 20 sheets; all 5 pieces.
        var form = flow.receiptForm(trf);
        form.getLines().get(0).setQuantity(new BigDecimal("18"));
        UUID receipt = flow.raiseReceipt(form);
        String inTicket = flow.postReceipt(receipt);

        assertThat(inTicket).matches("TT-RBV-\\d{4}-\\d{4}");
        assertThat(statusOf(receipt)).isEqualTo("POSTED");
        assertThat(onHand(flow.grn.glass, "RBV-BOND")).isEqualByComparingTo("18.000");
        assertThat(valueAt(flow.grn.glass, "RBV-BOND")).as("177,391.30 x 18 / 20").isEqualByComparingTo("159652.17");
        assertThat(jdbc.sql("SELECT unit_cost FROM stock_movement WHERE item_id = :i AND direction = 'IN' "
                        + "AND location_id = :l").param("i", flow.grn.glass).param("l", flow.destination)
                .query(BigDecimal.class).single()).isEqualByComparingTo("8869.5650");
        assertThat(onHand(flow.grn.silicone, "RBV-BOND")).isEqualByComparingTo("5.000");
        assertThat(valueAt(flow.grn.silicone, "RBV-BOND")).isEqualByComparingTo("16630.44");

        // The shortfall stays in transit at cost, and shows on the transfer.
        assertThat(onHand(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo("2.000");
        assertThat(valueAt(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo("17739.13");
        assertThat(onHand(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("0.000");
        var glass = position(trf, flow.grn.glass);
        assertThat((BigDecimal) glass.get("dispatched_base")).isEqualByComparingTo("20");
        assertThat((BigDecimal) glass.get("received_base")).isEqualByComparingTo("18");
        assertThat((BigDecimal) glass.get("in_transit_base")).isEqualByComparingTo("2");
        assertThat((BigDecimal) position(trf, flow.grn.silicone).get("in_transit_base")).isEqualByComparingTo("0");
        assertThat(displayStateOf(trf)).isEqualTo("IN_TRANSIT");
        fx.actAs(flow.raiser());
        assertThat(transfers.gateOf(trf).state()).isEqualTo("IN_TRANSIT");
        assertThat(transfers.lines(trf).get(0).leftInTransit()).isTrue();

        // Four tickets, each on the register of the branch whose location it moves.
        List<Map<String, Object>> tickets = jdbc.sql("""
                SELECT t.movement_type, t.direction, d.branch_id, d.status
                  FROM transaction_ticket t JOIN document d ON d.id = t.document_id
                 WHERE t.source_document_id IN (:docs) ORDER BY d.serial_no
                """).param("docs", List.of(trf, receipt)).query().listOfRows();
        assertThat(tickets).hasSize(4).allMatch(t -> "POSTED".equals(t.get("status")));
        assertThat(jdbc.sql("""
                SELECT d.branch_id FROM transaction_ticket t JOIN document d ON d.id = t.document_id
                 WHERE t.source_document_id = :r AND t.movement_type = 'TRANSFER_OUT'
                """).param("r", receipt).query(UUID.class).single())
                .as("the receipt's out leg moves the source branch's transit, so it is on the source register")
                .isEqualTo(fx.kigali());
        assertThat(jdbc.sql("""
                SELECT d.branch_id FROM transaction_ticket t JOIN document d ON d.id = t.document_id
                 WHERE t.source_document_id = :r AND t.movement_type = 'TRANSFER_IN'
                """).param("r", receipt).query(UUID.class).single()).isEqualTo(flow.rubavu);

        // stock_balance agrees with the ledger, place by place, everywhere the stock went.
        for (UUID item : List.of(flow.grn.glass, flow.grn.silicone)) {
            for (String code : List.of("KGL-MAIN", "KGL-TRAN", "RBV-BOND")) {
                Map<String, Object> balance = jdbc.sql("""
                        SELECT COALESCE(SUM(sb.qty_on_hand), 0) AS qty, COALESCE(SUM(sb.total_value), 0) AS value
                          FROM stock_balance sb JOIN location l ON l.id = sb.location_id
                         WHERE sb.item_id = :item AND l.code = :loc
                        """).param("item", item).param("loc", code).query().singleRow();
                Map<String, Object> ledger = jdbc.sql("""
                        SELECT COALESCE(SUM(v.qty_on_hand), 0) AS qty, COALESCE(SUM(v.total_value), 0) AS value
                          FROM stock_balance_from_ledger v JOIN location l ON l.id = v.location_id
                         WHERE v.item_id = :item AND l.code = :loc
                        """).param("item", item).param("loc", code).query().singleRow();
                assertThat((BigDecimal) balance.get("qty")).isEqualByComparingTo((BigDecimal) ledger.get("qty"));
                assertThat((BigDecimal) balance.get("value")).isEqualByComparingTo((BigDecimal) ledger.get("value"));
            }
        }
    }

    @Test
    void aLineThatReceivedNothingIsAbsentAndItsWholeQuantityStaysInTransit() {
        UUID trf = flow.dispatched();
        var form = flow.receiptForm(trf);
        form.getLines().get(1).setQuantity(BigDecimal.ZERO);          // none of the silicone arrived
        UUID receipt = flow.raiseReceipt(form);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transfer_receipt_line WHERE document_id = :id")
                .param("id", receipt).query(Long.class).single()).isEqualTo(1L);
        flow.postReceipt(receipt);

        assertThat(onHand(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("5.000");
        assertThat(onHand(flow.grn.silicone, "RBV-BOND")).isEqualByComparingTo("0");
        assertThat((BigDecimal) position(trf, flow.grn.silicone).get("in_transit_base")).isEqualByComparingTo("5");
        assertThat(displayStateOf(trf)).isEqualTo("IN_TRANSIT");

        // A full receipt (every line, in full) is RECEIVED, with nothing left in transit.
        UUID whole = flow.dispatched();
        flow.postReceipt(flow.raiseReceipt(flow.receiptForm(whole)));
        assertThat(displayStateOf(whole)).isEqualTo("RECEIVED");
    }

    // ---- dispatch refusals ----------------------------------------------------------------------

    @Test
    void dispatchIsRefusedBeforeTheTransferIsFullyApproved() {
        UUID pending = flow.pending();
        fx.actAs(flow.dispatcher);
        assertThatThrownBy(() -> transfers.dispatch(pending))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("PENDING");
        assertThat(statusOf(pending)).isEqualTo("PENDING");

        UUID draft = flow.draft(flow.standardForm());
        fx.actAs(flow.dispatcher);
        assertThatThrownBy(() -> transfers.dispatch(draft)).isInstanceOf(ControlRefusedException.class);
        assertThat(onHand(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo("0");
    }

    @Test
    void theCreatorAndAnyChainSignerCannotDispatch() {
        UUID trf = flow.approved();
        // The raiser is a Warehouse Manager, who holds the dispatch right: the right is not the obstacle.
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> transfers.dispatch(trf))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("cannot be posted by the person who raised it");

        // A signer who also loads at the gate.
        UUID signerAndLoader = fx.user("signload", flow.chainRoles.get(1), "ASST_WH_MANAGER");
        UUID second = flow.pending();
        fx.actAs(signerAndLoader);
        transfers.sign(second, true, null);
        for (int i = 2; i < flow.stepUsers.size(); i++) {
            fx.actAs(flow.stepUsers.get(i));
            transfers.sign(second, true, null);
        }
        fx.actAs(signerAndLoader);
        assertThatThrownBy(() -> transfers.dispatch(second))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("signed")
                .hasMessageContaining("cannot also post it");
        assertThat(statusOf(second)).isEqualTo("APPROVED");
        // The view says so in words, before anyone tries.
        assertThat(transfers.detail(second).actions().canDispatch()).isFalse();
        assertThat(transfers.detail(second).actions().dispatchReason()).contains("signed");
    }

    @Test
    void onlyTheDispatchRightDispatches() {
        UUID trf = flow.approved();
        fx.actAs(flow.stepUsers.get(flow.stepUsers.size() - 1));   // the last signer holds no transfer.dispatch
        assertThatThrownBy(() -> transfers.dispatch(trf)).isInstanceOf(AccessDeniedException.class);
        assertThat(statusOf(trf)).isEqualTo("APPROVED");
    }

    @Test
    void aShortDispatchIsRefusedByTheDatabaseBecauseTheTicketMustEqualTheApprovedLine() {
        UUID trf = flow.approved();
        fx.actAs(flow.dispatcher);
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            var d = documents.beginPost(trf);
            var out = documents.open(DocumentKind.TT, d.branchId(), d.serialNo(), null, null);
            jdbc.sql("""
                    INSERT INTO transaction_ticket (document_id, movement_type, direction, from_location_id,
                                                    source_document_id, customs_reference)
                    VALUES (:id, 'TRANSFER_OUT', 'OUT', :from, :src, 'C-TRF-0001')
                    """).param("id", out.id(), Types.OTHER).param("from", flow.grn.location, Types.OTHER)
                    .param("src", trf, Types.OTHER).update();
            // 10 sheets against an approved 20: a short load means cancelling and approving again.
            jdbc.sql("""
                    INSERT INTO ticket_line (ticket_id, line_no, item_id, quantity, uom_id, qty_base_uom, storage_bin_id)
                    VALUES (:t, 1, :item, 10, :uom, 10, :bin)
                    """).param("t", out.id(), Types.OTHER).param("item", flow.grn.glass, Types.OTHER)
                    .param("uom", fx.uom("SHEET"), Types.OTHER).param("bin", flow.grn.bin, Types.OTHER).update();
        })).satisfies(e -> assertThat(DbRefusal.reason(e)).hasValueSatisfying(m -> assertThat(m)
                .contains("Dispatch is exactly the approved quantity")));
        assertThat(statusOf(trf)).isEqualTo("APPROVED");
    }

    @Test
    void insufficientSourceStockIsRefusedWithItsReasonAndNoPartialEffect() {
        var form = flow.standardForm();
        form.getLines().clear();
        form.getLines().add(flow.line(flow.grn.silicone, "PC", "5", null));
        form.getLines().add(flow.line(flow.grn.glass, "SHEET", "60", flow.grn.bin));     // the bin holds 50
        UUID trf = flow.approved(form);

        assertThatThrownBy(() -> flow.dispatch(trf))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("Not enough stock")
                .hasMessageContaining("50")
                .hasMessageContaining("60");
        assertThat(statusOf(trf)).isEqualTo("APPROVED");
        assertThat(onHand(flow.grn.silicone, "KGL-MAIN")).as("the silicone line rolled back too").isEqualByComparingTo("20");
        assertThat(onHand(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'REJECT'")
                .param("id", trf).query(Long.class).single()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void aDispatchedTransferCannotBeCancelled() {
        UUID trf = flow.dispatched();
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> transfers.cancel(trf, "changed my mind"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("cannot be cancelled");
        assertThat(statusOf(trf)).isEqualTo("POSTED");
        assertThat(transfers.detail(trf).actions().cancelReason()).contains("dispatched").contains("reversing");
    }

    // ---- creation refusals -----------------------------------------------------------------------

    @Test
    void aTransferWithinOneBranchIsRefusedAndBondedNeedsItsCustomsReference() {
        String code = "KGL-W" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        UUID secondStore = UUID.randomUUID();
        jdbc.sql("INSERT INTO location (id, branch_id, code, name, location_type) VALUES (:id, :branch, :code, 'Second store', 'WAREHOUSE')")
                .param("id", secondStore, Types.OTHER).param("branch", fx.kigali(), Types.OTHER).param("code", code).update();
        var form = flow.standardForm();
        form.setToLocationId(secondStore);
        fx.actAs(flow.raiser());
        assertThatThrownBy(() -> transfers.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("same branch");

        var bonded = flow.standardForm();
        bonded.setCustomsReference(null);
        assertThatThrownBy(() -> transfers.create(bonded))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("customs reference");
    }

    // ---- receipt refusals -------------------------------------------------------------------------

    @Test
    void aReceiptBeforeDispatchIsRefused() {
        UUID approved = flow.approved();
        var form = flow.receiptForm(approved);
        assertThatThrownBy(() -> flow.raiseReceipt(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("not dispatched");
    }

    private long rejectRows(UUID doc) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'REJECT'")
                .param("id", doc).query(Long.class).single();
    }

    @Test
    void theRaiserTheDispatcherAndAnySignerCannotRecordTheArrivalButAnIndependentReceiverCan() {
        UUID trf = flow.dispatched();
        var legitForm = flow.receiptForm(trf);
        // A draft receipt raised by an independent receiver, for the post-time checks below.
        UUID draft = flow.raiseReceipt(flow.receiptForm(trf));

        // Each of them holds the right to receive at Rubavu; the right is not the obstacle, the rule is.
        fx.grantAt(flow.dispatcher, "ASST_WH_MANAGER", "RBV");
        fx.grantAt(flow.raiser(), "WH_MANAGER", "RBV");
        fx.grantAt(flow.stepUsers.get(1), "ASST_WH_MANAGER", "RBV");
        Map<UUID, String> who = Map.of(
                flow.raiser(), "You raised",
                flow.dispatcher, "You dispatched",
                flow.stepUsers.get(1), "You signed step 2");

        for (var entry : who.entrySet()) {
            UUID user = entry.getKey();
            String reason = entry.getValue();
            // Before the form opens: a read, refused with its reason and not recorded.
            long before = rejectRows(trf);
            fx.actAs(user, flow.rubavu);
            assertThatThrownBy(() -> receipts.prefill(trf))
                    .isInstanceOf(ControlRefusedException.class)
                    .hasMessageContaining(reason).hasMessageContaining("cannot record its arrival");
            assertThat(rejectRows(trf)).isEqualTo(before);
            // At create: refused, and recorded.
            assertThatThrownBy(() -> receipts.create(legitForm))
                    .isInstanceOf(ControlRefusedException.class)
                    .hasMessageContaining(reason);
            assertThat(rejectRows(trf)).isEqualTo(before + 1);
            // The transfer's view and the receipt's view say so in words, and offer nothing.
            assertThat(transfers.detail(trf).actions().canReceive()).isFalse();
            assertThat(transfers.detail(trf).actions().receiveReason()).contains(reason);
            assertThat(receipts.detail(draft).actions().canPost()).isFalse();
            assertThat(receipts.detail(draft).actions().postReason()).contains(reason);
            // At post, a POST action: refused, and recorded against the receipt.
            long postBefore = rejectRows(draft);
            assertThatThrownBy(() -> receipts.post(draft))
                    .isInstanceOf(ControlRefusedException.class)
                    .hasMessageContaining(reason);
            assertThat(rejectRows(draft)).isEqualTo(postBefore + 1);
            assertThat(statusOf(draft)).isEqualTo("DRAFT");
        }
        assertThat(onHand(flow.grn.glass, "RBV-BOND")).isEqualByComparingTo("0");

        // An independent receiver is accepted, and the receipt posts.
        fx.actAs(flow.receiver, flow.rubavu);
        assertThat(receipts.prefill(trf)).isNotNull();
        flow.postReceipt(draft);
        assertThat(statusOf(draft)).isEqualTo("POSTED");
        assertThat(onHand(flow.grn.glass, "RBV-BOND")).isEqualByComparingTo("20");
    }

    @Test
    void theReceiverReasonNamesTheRoleTheyPlayedInWords() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var signed = new heritier.ntaganira.highbytes.wms.document.ChainStep(UUID.randomUUID(), 3, "VERIFY", true,
                UUID.randomUUID(), "INTERNAL_CTRL", "Internal Controller", "APPROVED", me, "Ines", null, null, "signed");
        assertThat(ReceiptService.receiverReason("TRF-1", me, me, other, List.of()))
                .contains("You raised TRF-1").contains("cannot record its arrival");
        assertThat(ReceiptService.receiverReason("TRF-1", me, other, me, List.of()))
                .contains("You dispatched TRF-1");
        assertThat(ReceiptService.receiverReason("TRF-1", me, other, other, List.of(signed)))
                .contains("You signed step 3 of TRF-1");
        assertThat(ReceiptService.receiverReason("TRF-1", me, other, other, List.of())).isNull();
    }

    @Test
    void eachConsignmentKeepsItsOwnCostThroughTransitWhateverElseSitsThereWithIt() {
        // Transfer A leaves at the source's first average cost.
        UUID a = flow.dispatched();
        // Then dearer stock arrives, so transfer B leaves at a different average.
        var more = flow.grn.siliconeOnlyForm();
        var glassLine = more.getLines().get(0);
        glassLine.setItemId(flow.grn.glass);
        glassLine.setUomId(fx.uom("SHEET"));
        glassLine.setQuantity(new BigDecimal("100"));
        glassLine.setUnitPrice(new BigDecimal("20000"));
        glassLine.setStorageBinId(flow.grn.bin);
        glassLine.setMeasuredThicknessMm(new BigDecimal("6.00"));
        flow.grn.post(flow.grn.approved(more));
        UUID b = flow.dispatched();

        BigDecimal valueA = transitIn(a);
        BigDecimal valueB = transitIn(b);
        assertThat(valueA).isNotEqualByComparingTo(valueB);
        assertThat(onHand(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo("40");
        assertThat(valueAt(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo(valueA.add(valueB));

        // Receiving A books exactly A's own dispatch cost at Rubavu, and leaves B's value in transit untouched.
        flow.postReceipt(flow.raiseReceipt(flow.receiptForm(a)));
        assertThat(valueAt(flow.grn.glass, "RBV-BOND")).isEqualByComparingTo(valueA);
        assertThat(onHand(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo("20");
        assertThat(valueAt(flow.grn.glass, "KGL-TRAN")).as("B's value in transit is unchanged").isEqualByComparingTo(valueB);

        // B arrives short, 15 of 20: v x q / Q, and the residual stays at B's own cost.
        var shortForm = flow.receiptForm(b);
        shortForm.getLines().get(0).setQuantity(new BigDecimal("15"));
        flow.postReceipt(flow.raiseReceipt(shortForm));
        BigDecimal received = valueB.multiply(new BigDecimal("15")).divide(new BigDecimal("20"), 2, java.math.RoundingMode.HALF_UP);
        assertThat(valueAt(flow.grn.glass, "RBV-BOND")).isEqualByComparingTo(valueA.add(received));
        assertThat(onHand(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo("5");
        assertThat(valueAt(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo(valueB.subtract(received));

        // And the cache still agrees with the ledger, place by place.
        for (String code : List.of("KGL-MAIN", "KGL-TRAN", "RBV-BOND")) {
            Map<String, Object> balance = jdbc.sql("""
                    SELECT COALESCE(SUM(sb.qty_on_hand), 0) AS qty, COALESCE(SUM(sb.total_value), 0) AS value
                      FROM stock_balance sb JOIN location l ON l.id = sb.location_id
                     WHERE sb.item_id = :item AND l.code = :loc
                    """).param("item", flow.grn.glass).param("loc", code).query().singleRow();
            Map<String, Object> ledger = jdbc.sql("""
                    SELECT COALESCE(SUM(v.qty_on_hand), 0) AS qty, COALESCE(SUM(v.total_value), 0) AS value
                      FROM stock_balance_from_ledger v JOIN location l ON l.id = v.location_id
                     WHERE v.item_id = :item AND l.code = :loc
                    """).param("item", flow.grn.glass).param("loc", code).query().singleRow();
            assertThat((BigDecimal) balance.get("qty")).isEqualByComparingTo((BigDecimal) ledger.get("qty"));
            assertThat((BigDecimal) balance.get("value")).isEqualByComparingTo((BigDecimal) ledger.get("value"));
        }
    }

    // ---- a split receipt takes exactly what was dispatched, by cumulative allocation -------------------

    /** A transfer of {@code pieces} silicone, dispatched; returns the transfer. */
    private UUID dispatchedSilicone(String pieces) {
        var form = flow.standardForm();
        form.getLines().clear();
        form.getLines().add(flow.line(flow.grn.silicone, "PC", pieces, null));
        UUID trf = flow.approved(form);
        flow.dispatch(trf);
        return trf;
    }

    /** A receipt of {@code parts} rows of one piece each against the transfer's single line. */
    private UUID receiptInParts(UUID trf, int parts) {
        var form = flow.receiptForm(trf);
        UUID line = form.getLines().get(0).getTransferLineId();
        form.getLines().clear();
        for (int i = 0; i < parts; i++) {
            var row = new ReceiptLineForm();
            row.setTransferLineId(line);
            row.setQuantity(BigDecimal.ONE);
            form.getLines().add(row);
        }
        return flow.raiseReceipt(form);
    }

    private BigDecimal transitInOf(UUID transfer, UUID item) {
        return jdbc.sql("""
                SELECT m.value FROM stock_movement m JOIN transaction_ticket t ON t.document_id = m.document_id
                 WHERE t.source_document_id = :t AND t.movement_type = 'TRANSFER_IN' AND m.item_id = :item
                """).param("t", transfer).param("item", item).query(BigDecimal.class).single();
    }

    private static BigDecimal round2(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Test
    void aThreeWaySplitReceiptTakesExactlyWhatWasDispatchedAndLeavesTransitAtZero() {
        UUID trf = dispatchedSilicone("3");
        BigDecimal v = transitInOf(trf, flow.grn.silicone);      // 9,978.26: not divisible by 3
        assertThat(onHand(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("3");

        UUID receipt = receiptInParts(trf, 3);
        flow.postReceipt(receipt);

        assertThat(statusOf(receipt)).isEqualTo("POSTED");
        // Rounding each part on its own would take 3 x 3,326.09 = 9,978.27: a cent too many.
        assertThat(valueAt(flow.grn.silicone, "RBV-BOND")).isEqualByComparingTo(v);
        assertThat(onHand(flow.grn.silicone, "RBV-BOND")).isEqualByComparingTo("3");
        assertThat(onHand(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("0");
        assertThat(valueAt(flow.grn.silicone, "KGL-TRAN")).as("nothing stranded in transit").isEqualByComparingTo("0");
        // The shares, in line order, are the differences of the cumulative roundings and sum to v.
        List<BigDecimal> shares = jdbc.sql("""
                SELECT m.value FROM stock_movement m
                  JOIN ticket_line tl ON tl.id = m.ticket_line_id
                  JOIN transaction_ticket t ON t.document_id = tl.ticket_id
                 WHERE t.source_document_id = :r AND t.movement_type = 'TRANSFER_OUT' ORDER BY tl.line_no
                """).param("r", receipt).query(BigDecimal.class).list();
        assertThat(shares).hasSize(3);
        assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(v);
        assertThat(shares.get(0)).isEqualByComparingTo(round2(v.divide(new BigDecimal("3"), 10, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void aSixWaySplitReceiptTakesExactlyWhatWasDispatched() {
        UUID trf = dispatchedSilicone("6");
        BigDecimal v = transitInOf(trf, flow.grn.silicone);
        flow.postReceipt(receiptInParts(trf, 6));

        assertThat(valueAt(flow.grn.silicone, "RBV-BOND")).isEqualByComparingTo(v);
        assertThat(onHand(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("0");
        assertThat(valueAt(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("0");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM stock_movement m JOIN transaction_ticket t ON t.document_id = m.document_id
                 WHERE t.movement_type = 'TRANSFER_OUT' AND m.item_id = :item AND m.location_id IN
                       (SELECT id FROM location WHERE code = 'KGL-TRAN')
                """).param("item", flow.grn.silicone).query(Long.class).single()).isEqualTo(6L);
    }

    @Test
    void aShortSplitReceiptLeavesTheRightResidueAtTheConsignmentsOwnCost() {
        UUID trf = dispatchedSilicone("6");
        BigDecimal v = transitInOf(trf, flow.grn.silicone);
        flow.postReceipt(receiptInParts(trf, 2));                 // 2 of the 6 arrived, in two parts

        BigDecimal two = round2(v.multiply(new BigDecimal("2")).divide(new BigDecimal("6"), 10, java.math.RoundingMode.HALF_UP));
        assertThat(valueAt(flow.grn.silicone, "RBV-BOND")).isEqualByComparingTo(two);
        assertThat(onHand(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo("4");
        assertThat(valueAt(flow.grn.silicone, "KGL-TRAN")).isEqualByComparingTo(v.subtract(two));
        assertThat((BigDecimal) position(trf, flow.grn.silicone).get("in_transit_base")).isEqualByComparingTo("4");
    }

    // ---- editing a draft receipt ------------------------------------------------------------------------

    @Test
    void aPartyToTheTransferCannotEditABystandersDraftReceiptButTheBystanderCan() {
        UUID trf = flow.dispatched();
        UUID draft = flow.raiseReceipt(flow.receiptForm(trf));
        fx.actAs(flow.receiver, flow.rubavu);
        var bystandersForm = receipts.formFor(draft);
        assertThat(jdbc.sql("SELECT DISTINCT entered_by FROM transfer_receipt_line WHERE document_id = :id")
                .param("id", draft).query(UUID.class).list()).containsExactly(flow.receiver);

        // The dispatcher holds the receive right at Rubavu too, but is a party to the transfer.
        fx.grantAt(flow.dispatcher, "ASST_WH_MANAGER", "RBV");
        long before = rejectRows(draft);
        fx.actAs(flow.dispatcher, flow.rubavu);
        assertThatThrownBy(() -> receipts.formFor(draft))
                .isInstanceOf(ControlRefusedException.class).hasMessageContaining("You dispatched");
        assertThat(rejectRows(draft)).as("opening the form is a read: not recorded").isEqualTo(before);
        assertThatThrownBy(() -> receipts.update(draft, bystandersForm))
                .isInstanceOf(ControlRefusedException.class).hasMessageContaining("You dispatched");
        assertThat(rejectRows(draft)).as("saving is an action: recorded").isEqualTo(before + 1);
        assertThat(receipts.detail(draft).actions().canEdit()).isFalse();

        // The bystander edits their own draft, and each line it writes names them.
        fx.actAs(flow.receiver, flow.rubavu);
        bystandersForm.getLines().get(0).setQuantity(new BigDecimal("17"));
        receipts.update(draft, bystandersForm);
        assertThat(jdbc.sql("SELECT quantity FROM transfer_receipt_line WHERE document_id = :id AND line_no = 1")
                .param("id", draft).query(BigDecimal.class).single()).isEqualByComparingTo("17");
        assertThat(jdbc.sql("SELECT DISTINCT entered_by FROM transfer_receipt_line WHERE document_id = :id")
                .param("id", draft).query(UUID.class).list()).containsExactly(flow.receiver);
    }

    @Test
    void aReceiptRecordedInPartsIsNotEditedThroughTheFormWhichWouldDropTheOtherParts() {
        UUID trf = dispatchedSilicone("3");
        UUID receipt = receiptInParts(trf, 3);
        fx.actAs(flow.receiver, flow.rubavu);

        long before = rejectRows(receipt);
        assertThatThrownBy(() -> receipts.formFor(receipt))
                .isInstanceOf(ControlRefusedException.class).hasMessageContaining("recorded in parts");
        assertThat(rejectRows(receipt)).as("opening the form is a read: not recorded").isEqualTo(before);

        var collapsed = flow.receiptForm(trf);                      // one row per transfer line, as the form holds it
        collapsed.setId(receipt);
        collapsed.setVersion(receipts.detail(receipt).header().version());
        assertThatThrownBy(() -> receipts.update(receipt, collapsed))
                .isInstanceOf(ControlRefusedException.class).hasMessageContaining("drop the other parts");
        assertThat(rejectRows(receipt)).as("saving is an action: recorded").isEqualTo(before + 1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transfer_receipt_line WHERE document_id = :id")
                .param("id", receipt).query(Long.class).single()).as("all three parts kept").isEqualTo(3L);

        flow.postReceipt(receipt);                                  // posting it as it stands still works
        assertThat(statusOf(receipt)).isEqualTo("POSTED");
    }

    // ---- a stated-value issue ---------------------------------------------------------------------------

    @Test
    void aNegativeStatedValueIsRefusedBeforeAnythingMoves() {
        fx.actAs(flow.dispatcher);
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> ledger.post(
                MovementRequest.issueAt(UUID.randomUUID(), UUID.randomUUID(), fx.kigali(), flow.grn.glass,
                        flow.grn.location, null, BigDecimal.ONE, new BigDecimal("-0.01")), flow.dispatcher)))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("negative value");
    }

    /** The value a transfer's glass line put into transit. */
    private BigDecimal transitIn(UUID transfer) {
        return jdbc.sql("""
                SELECT m.value FROM stock_movement m JOIN transaction_ticket t ON t.document_id = m.document_id
                 WHERE t.source_document_id = :t AND t.movement_type = 'TRANSFER_IN' AND m.item_id = :item
                """).param("t", transfer).param("item", flow.grn.glass).query(BigDecimal.class).single();
    }

    @Test
    void aReceiptAtTheWrongBranchIsRefusedAndRecorded() {
        UUID trf = flow.dispatched();
        var form = flow.receiptForm(trf);
        // Someone who receives at Gahanga, not at Rubavu, where the transfer is going.
        UUID wrong = fx.user("wrongrecv", "ASST_WH_MANAGER");
        fx.actAs(wrong);
        assertThatThrownBy(() -> receipts.create(form)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'REJECT' AND actor_user_id = :u "
                        + "AND entity_label LIKE 'TRR · Raise%'")
                .param("u", wrong).query(Long.class).single()).isEqualTo(1L);
        // Opening the form at the wrong branch is refused too, but only a document action is recorded.
        assertThatThrownBy(() -> receipts.prefill(trf)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void moreCannotArriveThanWasSent() {
        UUID trf = flow.dispatched();
        var form = flow.receiptForm(trf);
        form.getLines().get(0).setQuantity(new BigDecimal("25"));     // 20 were sent
        UUID receipt = flow.raiseReceipt(form);
        assertThatThrownBy(() -> flow.postReceipt(receipt))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("More cannot arrive than was sent");
        assertThat(statusOf(receipt)).isEqualTo("DRAFT");
        assertThat(onHand(flow.grn.glass, "RBV-BOND")).isEqualByComparingTo("0");
        assertThat(onHand(flow.grn.glass, "KGL-TRAN")).isEqualByComparingTo("20");
    }

    @Test
    void aTransferIsReceivedOnceButADraftReceiptCanBeCancelledAndReplaced() {
        UUID trf = flow.dispatched();
        UUID first = flow.raiseReceipt(flow.receiptForm(trf));
        var again = flow.receiptForm(trf);
        assertThatThrownBy(() -> flow.raiseReceipt(again))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("already has a live receipt");

        fx.actAs(flow.receiver, flow.rubavu);
        receipts.cancel(first, "counted again, redo");
        assertThat(statusOf(first)).isEqualTo("CANCELLED");
        UUID replacement = flow.raiseReceipt(flow.receiptForm(trf));
        flow.postReceipt(replacement);

        // Posted, it is received: neither cancelled nor received again.
        fx.actAs(flow.receiver, flow.rubavu);
        assertThatThrownBy(() -> receipts.cancel(replacement, "x"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("posted");
        assertThatThrownBy(() -> flow.raiseReceipt(flow.receiptForm(trf)))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("already has a live receipt");
    }
}
