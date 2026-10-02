package heritier.ntaganira.highbytes.wms.inventory.cutting;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.cutting
 * - File       : CuttingTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A cutting order through its chain, its posting by area, its sizes as items, and its pieces at the gate
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.inventory.count.CountForm;
import heritier.ntaganira.highbytes.wms.inventory.count.CountScope;
import heritier.ntaganira.highbytes.wms.inventory.count.CountService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DeliveryNoteService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.support.CuttingFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a real PostgreSQL 16 with Flyway V1 to V18, at a place of the test's own. */
class CuttingTest extends IntegrationTest {

    @Autowired CuttingService cutting;
    @Autowired DeliveryNoteService notes;
    @Autowired ReceivingService receiving;
    @Autowired CountService counts;

    CuttingFlow cf;

    @BeforeEach
    void cast() {
        cf = new CuttingFlow(fx, cutting, notes, receiving);
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", id).query(String.class).single();
    }

    @Test
    void anOrderIsSignedPostedAndItsSheetsComeBackAsPiecesAndOffcutsAtTheirShareByArea() {
        UUID id = cf.released(cf.standardForm());
        assertThat(status(id)).isEqualTo("APPROVED");
        String tickets = cf.post(id);
        assertThat(tickets).contains("TT-KGL-");
        assertThat(status(id)).isEqualTo("POSTED");

        UUID piece = cf.itemOf(cf.sheetCode + "-R-1200x800");
        UUID offcut = cf.itemOf(cf.sheetCode + "-R-1800x600");
        assertThat(cf.onHand(cf.sheet)).isEqualByComparingTo("3");
        assertThat(cf.onHand(piece)).isEqualByComparingTo("6");
        assertThat(cf.onHand(offcut)).isEqualByComparingTo("1");

        // Two sheets at 100,000 left; their 200,000 came back split by area: 5.76 m² of pieces, 1.08 of off-cut.
        fx.actAs(cf.raiser());
        var posting = cutting.postingOf(id).orElseThrow();
        assertThat(posting.valueOut()).isEqualByComparingTo("200000");
        assertThat(posting.valueIn()).isEqualByComparingTo("200000");
        assertThat(posting.movements()).filteredOn(m -> m.itemCode().endsWith("-R-1800x600")).singleElement()
                .satisfies(m -> assertThat(m.value()).isEqualByComparingTo("31578.95"));
        assertThat(posting.movements()).filteredOn(m -> m.itemCode().endsWith("-R-1200x800")).singleElement()
                .satisfies(m -> assertThat(m.value()).isEqualByComparingTo("168421.05"));

        // The cut sizes are items in their own right: filed under the sheet, the longer side first.
        var made = jdbc.sql("""
                SELECT is_remnant, cut_from_item_id, width_mm, height_mm, thickness_mm FROM item WHERE id = :id
                """).param("id", piece).query((rs, n) -> List.of(rs.getBoolean(1), rs.getObject(2, UUID.class),
                rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5))).single();
        assertThat(made).containsExactly(true, cf.sheet, new BigDecimal("1200.0"), new BigDecimal("800.0"),
                new BigDecimal("6.00"));
        var detail = cutting.detail(id);
        assertThat(detail.header().displayState()).isEqualTo("POSTED");
        assertThat(detail.areas().wasteM2()).isEqualByComparingTo("5.160");
    }

    @Test
    void aSizeCutAgainIsTheSameItemWhicheverWayRound() {
        UUID first = cf.draft(cf.standardForm());
        var form = cf.standardForm();
        form.getOutputs().clear();
        form.getOutputs().add(CuttingFlow.output("PIECE", "800", "1200", "2"));
        UUID second = cf.draft(form);
        fx.actAs(cf.raiser());
        assertThat(cutting.outputs(second).get(0).itemId()).isEqualTo(cutting.outputs(first).get(0).itemId());
        // Made once, and audited as made by the order that first cut it.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_name = 'item' AND entity_id = :id AND action = 'CREATE'")
                .param("id", cutting.outputs(first).get(0).itemId()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void whatCannotBeCutIsRefusedWithItsReason() {
        // An off-cut under 300 mm on a side is waste, not stock.
        var small = cf.standardForm();
        small.getOutputs().add(CuttingFlow.output("OFFCUT", "1000", "250", "1"));
        assertThatThrownBy(() -> cf.draft(small)).isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("is waste");
        // A piece larger than the sheet.
        var big = cf.standardForm();
        big.getOutputs().add(CuttingFlow.output("PIECE", "3500", "100", "1"));
        assertThatThrownBy(() -> cf.draft(big)).isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("does not fit");
        // More glass than the sheets hold: a draft may say so, but it is not submitted.
        var over = cf.standardForm();
        over.getOutputs().add(CuttingFlow.output("PIECE", "3000", "2000", "2"));
        UUID id = cf.draft(over);
        fx.actAs(cf.raiser());
        assertThat(cutting.detail(id).areas().overCut()).isTrue();
        assertThatThrownBy(() -> cutting.submit(id)).isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("No more glass can be cut than the sheets hold");
        assertThat(status(id)).isEqualTo("DRAFT");
    }

    @Test
    void nobodyPostsWhatTheyPreparedOrSigned() {
        UUID id = cf.released(cf.standardForm());
        for (UUID party : List.of(cf.raiser(), cf.releaser())) {
            fx.actAs(party);
            assertThat(cutting.detail(id).actions().canPost()).isFalse();
        }
        // The preparer is Finance and holds cutting.post: refused by the engine and the database alike.
        fx.actAs(cf.raiser());
        assertThatThrownBy(() -> cutting.post(id)).isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("raised");
        // The Warehouse Manager who verified holds no posting right at all.
        fx.actAs(cf.verifier());
        assertThatThrownBy(() -> cutting.post(id)).isInstanceOf(AccessDeniedException.class);
        assertThat(status(id)).isEqualTo("APPROVED");

        fx.actAs(cf.poster);
        assertThat(cutting.detail(id).actions().canPost()).isTrue();
    }

    @Test
    void aPlaceUnderACountIsNotCutUntilTheCountIsVerified() {
        UUID id = cf.released(cf.standardForm());
        UUID manager = fx.user("cntm", "WH_MANAGER");
        fx.actAs(manager);
        CountForm count = new CountForm();
        count.setLocationId(cf.location);
        count.setScope(CountScope.PARTIAL);
        count.setItemIds(new ArrayList<>(List.of(cf.sheet)));
        counts.create(count);

        fx.actAs(cf.poster);
        var actions = cutting.detail(id).actions();
        assertThat(actions.canPost()).isFalse();
        assertThat(actions.postReason()).contains("CNT-KGL-");
        // Refused before the ledger is asked, so no shortage message reads the counted place's book back.
        assertThatThrownBy(() -> cutting.post(id)).isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("is counting what this order cuts");
        assertThat(status(id)).isEqualTo("APPROVED");
    }

    @Test
    void thePiecesLeaveThroughTheGateOnceTheOrderIsPostedAndTheOffcutsStay() {
        UUID id = cf.released(cf.standardForm());
        // Released but not cut: nothing to load yet.
        fx.actAs(cf.gate);
        assertThat(notes.readyToLoad(fx.kigali())).extracting(r -> r.id()).doesNotContain(id);
        assertThatThrownBy(() -> cf.raiseNote(id)).isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("posted");

        cf.post(id);
        fx.actAs(cf.gate);
        assertThat(notes.readyToLoad(fx.kigali())).filteredOn(r -> r.id().equals(id)).singleElement()
                .satisfies(r -> {
                    assertThat(r.cuttingOrder()).isTrue();
                    assertThat(r.lineCount()).isEqualTo(1);
                });
        var context = notes.contextFor(id);
        assertThat(context.daoLines()).singleElement().satisfies(l -> {
            assertThat(l.itemCode()).endsWith("-R-1200x800");
            assertThat(l.quantity()).isEqualByComparingTo("6");
        });

        UUID note = cf.raiseNote(id);
        cf.postNote(note);
        assertThat(cf.onHand(cf.itemOf(cf.sheetCode + "-R-1200x800"))).isEqualByComparingTo("0");
        assertThat(cf.onHand(cf.itemOf(cf.sheetCode + "-R-1800x600"))).isEqualByComparingTo("1");
        fx.actAs(cf.raiser());
        assertThat(cutting.find(id).displayState()).isEqualTo("DELIVERED");
        assertThat(cutting.detail(id).gate().state()).isEqualTo("DELIVERED");

        // Whoever recorded the cut is behind the delivery: a return of it is not theirs to take back.
        assertThat(jdbc.sql("SELECT delivery_party_role(:p, :n)").param("p", cf.poster).param("n", note)
                .query(String.class).single()).isEqualTo("cut");
    }

    @Test
    void aNoteLoadsTheOrdersPiecesExactlyAndNeverItsOffcut() {
        UUID id = cf.posted(cf.standardForm());
        var form = cf.loadForm(id);
        form.getLines().get(0).setQuantity(new BigDecimal("5"));
        fx.actAs(cf.gate);
        UUID note = notes.create(form);
        assertThatThrownBy(() -> cf.postNote(note)).isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("must equal the cutting order exactly");

        // An off-cut line cannot be written onto a note, whatever the form says.
        UUID offcutLine = jdbc.sql("SELECT id FROM cutting_order_output WHERE document_id = :id AND kind = 'OFFCUT'")
                .param("id", id).query(UUID.class).single();
        var withOffcut = cf.loadForm(id);
        var extra = new heritier.ntaganira.highbytes.wms.inventory.dispatch.DnLineForm();
        extra.setAuthorizationLineId(offcutLine);
        extra.setQuantity(BigDecimal.ONE);
        extra.setMeasuredThicknessMm(new BigDecimal("6.00"));
        withOffcut.getLines().add(extra);
        fx.actAs(cf.gate);
        notes.cancel(note, "Short load");
        assertThatThrownBy(() -> notes.create(withOffcut)).isInstanceOf(ControlRefusedException.class);
    }

    @Test
    void whoeverPostedTheCutDoesNotLetItOutOfTheGate() {
        // No segregation rule pairs Finance with the Warehouse Manager, so one person may hold both.
        UUID both = fx.user("cutboth", "FINANCE", "WH_MANAGER");
        UUID id = cf.released(cf.standardForm());
        fx.actAs(both);
        cutting.post(id);
        var form = cf.loadForm(id);
        fx.actAs(both);
        UUID note = notes.create(form);
        assertThat(notes.detail(note).actions().canPost()).isFalse();
        assertThat(notes.detail(note).actions().postReason()).contains("You posted cutting order");
        assertThatThrownBy(() -> notes.post(note)).isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("posted cutting order");
        // Anyone else at the gate lets it out.
        cf.postNote(note);
    }

    @Test
    void anOrderCutsAtMostFiftySizes() {
        var form = cf.standardForm();
        form.getOutputs().clear();
        for (int i = 0; i < 51; i++) {
            form.getOutputs().add(CuttingFlow.output("PIECE", String.valueOf(100 + i), "100", "1"));
        }
        assertThatThrownBy(() -> cf.draft(form)).isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("between 1 and 50 sizes");
    }

    @Test
    void cuttingIsReadAndRaisedOnlyWithItsRights() {
        fx.actAs(fx.user("sales", "SALES"));
        assertThatThrownBy(() -> cutting.list(fx.kigali(), null, false)).isInstanceOf(AccessDeniedException.class);
        fx.actAs(cf.gate);    // a Warehouse Manager reads and verifies cutting orders, but does not raise them
        assertThatThrownBy(() -> cutting.create(cf.standardForm())).isInstanceOf(AccessDeniedException.class);
    }
}
