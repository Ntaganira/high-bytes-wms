package heritier.ntaganira.highbytes.wms.inventory.receiving;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.receiving
 * - File       : ChainSwitchTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A receipt finishes under the chain it began with, and the 2027 chain posts too
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.support.GrnFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Board Paper HB/BD/2026/09-05 replaces the 2026 chain from 1 January 2027.
 * The switch is a date on a row, so it can be brought forward here: move the
 * 2027 chain to today, and prove that a receipt raised before the switch is
 * still signed and posted by the 2026 roles, while one raised after is
 * signed by the 2027 roles, with no change to any code.
 *
 * <p>The dates are put back afterwards.
 */
class ChainSwitchTest extends IntegrationTest {

    @Autowired ReceivingService receiving;
    @Autowired heritier.ntaganira.highbytes.wms.inventory.dispatch.DispatchService dispatch;
    @Autowired heritier.ntaganira.highbytes.wms.inventory.dispatch.DeliveryNoteService notes;
    @Autowired heritier.ntaganira.highbytes.wms.inventory.transfer.TransferService transfers;
    @Autowired heritier.ntaganira.highbytes.wms.inventory.transfer.ReceiptService receipts;

    private UUID definition(String basis) {
        return jdbc.sql("""
                SELECT wd.id FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
                 WHERE dt.code = 'GRN' AND wd.basis = :basis
                """).param("basis", basis).query(UUID.class).single();
    }

    @Test
    void aReceiptFinishesUnderTheChainItBeganWithAndTheNewChainPostsToo() {
        assumeTrue(jdbc.sql("SELECT kigali_today() < DATE '2027-01-01'").query(Boolean.class).single(),
                "the 2027 chain is already in force; there is no switch to bring forward");
        UUID old2026 = definition("POLICY_2026");
        UUID new2027 = definition("RESTRUCTURE_2027");

        // Raised and submitted under the 2026 chain, before the switch.
        GrnFlow before = new GrnFlow(fx, receiving);
        assertThat(before.chainRoles).hasSize(3);
        UUID open = before.pending();

        jdbc.sql("UPDATE workflow_definition SET effective_to = kigali_today() WHERE id = :id")
                .param("id", old2026).update();
        jdbc.sql("UPDATE workflow_definition SET effective_from = kigali_today() WHERE id = :id")
                .param("id", new2027).update();
        try {
            // Raised after: bound to the 2027 chain, four steps, signed by the 2027 roles.
            GrnFlow after = new GrnFlow(fx, receiving);
            assertThat(after.chainRoles).hasSize(4).isNotEqualTo(before.chainRoles);
            UUID fresh = after.approved();
            assertThat(boundTo(fresh)).isEqualTo(new2027);
            after.post(fresh);
            assertThat(statusOf(fresh)).isEqualTo("POSTED");
            assertThat(jdbc.sql("""
                    SELECT SUM(signed_quantity) FROM stock_movement WHERE item_id = :item
                    """).param("item", after.silicone).query(BigDecimal.class).single()).isEqualByComparingTo("20.000");

            // The one raised before the switch is still bound to the 2026 chain and finishes under it.
            assertThat(boundTo(open)).isEqualTo(old2026);
            before.signRemainingSteps(open);
            assertThat(statusOf(open)).isEqualTo("APPROVED");
            before.post(open);
            assertThat(statusOf(open)).isEqualTo("POSTED");
        } finally {
            // Put the switch back where the Board put it: shrink the new chain first so the two never overlap.
            jdbc.sql("UPDATE workflow_definition SET effective_from = DATE '2027-01-01' WHERE id = :id")
                    .param("id", new2027).update();
            jdbc.sql("UPDATE workflow_definition SET effective_to = DATE '2027-01-01' WHERE id = :id")
                    .param("id", old2026).update();
        }
    }

    @Test
    void theRestructuredDeliveryChainReleasesAndDeliversToo() {
        assumeTrue(jdbc.sql("SELECT kigali_today() < DATE '2027-01-01'").query(Boolean.class).single(),
                "the 2027 chain is already in force; there is no switch to bring forward");
        UUID old2026 = definition("DAO", "POLICY_2026");
        UUID new2027 = definition("DAO", "RESTRUCTURE_2027");

        jdbc.sql("UPDATE workflow_definition SET effective_to = kigali_today() WHERE id = :id").param("id", old2026).update();
        jdbc.sql("UPDATE workflow_definition SET effective_from = kigali_today() WHERE id = :id").param("id", new2027).update();
        try {
            var flow = new heritier.ntaganira.highbytes.wms.support.DispatchFlow(fx, receiving, dispatch, notes);
            // Inventory Transactions Officer, Director of Supply Chain, Director of Commercial (countersign), Internal Controller (release).
            assertThat(flow.chainRoles).containsExactly("INV_TX_OFFICER", "DIR_SUPPLY_CHAIN", "DIR_COMMERCIAL", "INTERNAL_CTRL");
            UUID dao = flow.released();
            assertThat(boundTo(dao)).isEqualTo(new2027);
            UUID note = flow.raiseNote(dao);
            flow.post(note);
            assertThat(statusOf(note)).isEqualTo("POSTED");
            assertThat(jdbc.sql("SELECT SUM(signed_quantity) FROM stock_movement WHERE item_id = :item AND direction = 'OUT'")
                    .param("item", flow.grn.glass).query(BigDecimal.class).single()).isEqualByComparingTo("-20.000");
        } finally {
            jdbc.sql("UPDATE workflow_definition SET effective_from = DATE '2027-01-01' WHERE id = :id").param("id", new2027).update();
            jdbc.sql("UPDATE workflow_definition SET effective_to = DATE '2027-01-01' WHERE id = :id").param("id", old2026).update();
        }
    }

    @Test
    void theRestructuredTransferChainHasTheManagingDirectorApproveAndTheTransferArrives() {
        assumeTrue(jdbc.sql("SELECT kigali_today() < DATE '2027-01-01'").query(Boolean.class).single(),
                "the 2027 chain is already in force; there is no switch to bring forward");
        UUID old2026 = definition("TRF", "POLICY_2026");
        UUID new2027 = definition("TRF", "RESTRUCTURE_2027");

        jdbc.sql("UPDATE workflow_definition SET effective_to = kigali_today() WHERE id = :id").param("id", old2026).update();
        jdbc.sql("UPDATE workflow_definition SET effective_from = kigali_today() WHERE id = :id").param("id", new2027).update();
        try {
            var flow = new heritier.ntaganira.highbytes.wms.support.TransferFlow(fx, receiving, transfers, receipts);
            // Warehouse Manager prepares, the Managing Director approves, the Internal Controller verifies.
            assertThat(flow.chainRoles).containsExactly("WH_MANAGER", "MANAGING_DIR", "INTERNAL_CTRL");
            UUID trf = flow.approved();
            assertThat(boundTo(trf)).isEqualTo(new2027);
            flow.dispatch(trf);
            flow.postReceipt(flow.raiseReceipt(flow.receiptForm(trf)));
            assertThat(statusOf(trf)).isEqualTo("POSTED");
            assertThat(jdbc.sql("SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock_balance sb JOIN location l ON l.id = sb.location_id "
                    + "WHERE sb.item_id = :item AND l.code = 'RBV-BOND'")
                    .param("item", flow.grn.glass).query(BigDecimal.class).single()).isEqualByComparingTo("20");
        } finally {
            jdbc.sql("UPDATE workflow_definition SET effective_from = DATE '2027-01-01' WHERE id = :id").param("id", new2027).update();
            jdbc.sql("UPDATE workflow_definition SET effective_to = DATE '2027-01-01' WHERE id = :id").param("id", old2026).update();
        }
    }

    private UUID definition(String type, String basis) {
        return jdbc.sql("""
                SELECT wd.id FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
                 WHERE dt.code = :type AND wd.basis = :basis
                """).param("type", type).param("basis", basis).query(UUID.class).single();
    }

    private UUID boundTo(UUID doc) {
        return jdbc.sql("SELECT workflow_definition_id FROM document WHERE id = :id").param("id", doc)
                .query(UUID.class).single();
    }

    private String statusOf(UUID doc) {
        return jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", doc).query(String.class).single();
    }
}
