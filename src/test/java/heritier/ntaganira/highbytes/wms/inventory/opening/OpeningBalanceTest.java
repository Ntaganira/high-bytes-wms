package heritier.ntaganira.highbytes.wms.inventory.opening;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.opening
 * - File       : OpeningBalanceTest.java
 * - Date       : 2026-10-04
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The cutover: an opening balance loads stock once per place, and only before the branch has traded
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.document.DocumentKind;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import heritier.ntaganira.highbytes.wms.support.OpeningFlow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The opening balance is the one document that brings stock in with no
 * supplier, invoice or customs entry behind it, so what is tested here is
 * mostly what it refuses.
 */
class OpeningBalanceTest extends IntegrationTest {

    @Autowired OpeningService opening;
    @Autowired ReceivingService receiving;

    private OpeningFlow flow() {
        return new OpeningFlow(fx, opening);
    }

    private BigDecimal onHand(UUID item, UUID location) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock_balance
                 WHERE item_id = :item AND location_id = :location
                """)
                .param("item", item).param("location", location)
                .query(BigDecimal.class).single();
    }

    private String statusOf(UUID doc) {
        return jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", doc)
                .query(String.class).single();
    }

    // ---- the whole path --------------------------------------------------

    @Test
    void theCutoverChainIsTheFourSignaturesTheBoardWouldExpect() {
        assertThat(flow().chainRoles)
                .containsExactly("WH_MANAGER", "INV_TX_OFFICER", "INTERNAL_CTRL", "MANAGING_DIR");
    }

    @Test
    void anOpeningBalancePostsAndTheLedgerCarriesExactlyWhatWasSignedFor() {
        OpeningFlow f = flow();
        UUID opb = f.approved();
        assertThat(statusOf(opb)).isEqualTo("APPROVED");

        String ticket = f.post(opb);
        assertThat(ticket).startsWith("TT-");
        assertThat(statusOf(opb)).isEqualTo("POSTED");

        // 4 boxes x 12.5 sheets = 50 sheets of glass, and 20 pieces of silicone.
        assertThat(onHand(f.glass, f.storeA)).isEqualByComparingTo("50");
        assertThat(onHand(f.silicone, f.storeA)).isEqualByComparingTo("20");

        // The place's average cost starts at what the old books held, not at an allocation.
        assertThat(jdbc.sql("""
                SELECT average_unit_cost FROM stock_balance
                 WHERE item_id = :item AND location_id = :location
                """)
                .param("item", f.silicone).param("location", f.storeA)
                .query(BigDecimal.class).single()).isEqualByComparingTo("3000.0000");

        // The movements are OPENING, IN, and out of nowhere.
        assertThat(jdbc.sql("""
                SELECT t.movement_type, t.direction, t.from_location_id IS NULL AS from_nowhere
                  FROM transaction_ticket t WHERE t.source_document_id = :id
                """)
                .param("id", opb)
                .query((rs, n) -> rs.getString("movement_type") + "/" + rs.getString("direction")
                        + "/" + rs.getBoolean("from_nowhere"))
                .single()).isEqualTo("OPENING/IN/true");

        assertThat(jdbc.sql("""
                SELECT SUM(signed_quantity) FROM stock_movement m
                  JOIN transaction_ticket t ON t.document_id = m.document_id
                 WHERE t.source_document_id = :id AND m.item_id = :item
                """)
                .param("id", opb).param("item", f.glass)
                .query(BigDecimal.class).single()).isEqualByComparingTo("50.000");
    }

    @Test
    void theSheetIsDatedTodayAndCarriesTheDayTheFiguresWereStruck() {
        OpeningFlow f = flow();
        UUID opb = f.draft();
        var header = jdbc.sql("""
                SELECT d.document_date, o.as_at_date FROM document d
                  JOIN opening_balance o ON o.document_id = d.id WHERE d.id = :id
                """)
                .param("id", opb)
                .query((rs, n) -> new LocalDate[]{
                        rs.getObject("document_date", LocalDate.class),
                        rs.getObject("as_at_date", LocalDate.class)})
                .single();
        assertThat(header[0]).isEqualTo(LocalDate.now());
        assertThat(header[1]).isBefore(header[0]);
    }

    // ---- once per place --------------------------------------------------

    @Test
    void aPlaceTakesAnOpeningBalanceOnlyOnce() {
        OpeningFlow f = flow();
        f.posted();

        // The service says so before anything is keyed.
        assertThat(opening.obstacleAt(f.storeA).alreadyLoaded()).isTrue();
        fx.actAs(f.raiser(), fx.branch(f.branchCode));
        assertThatThrownBy(() -> opening.create(f.standardForm()))
                .hasMessageContaining("already has its opening balance");
    }

    @Test
    void anotherPlaceAtTheSameBranchStillLoads() {
        OpeningFlow f = flow();
        f.posted();

        assertThat(opening.obstacleAt(f.storeB).blocked()).isFalse();
        UUID second = f.approved(f.formFor(f.storeB));
        f.post(second);
        assertThat(statusOf(second)).isEqualTo("POSTED");
        assertThat(onHand(f.glass, f.storeB)).isEqualByComparingTo("50");
    }

    // ---- before the branch has traded ------------------------------------

    @Test
    void anOpeningBalanceCannotBeRaisedOnceTheBranchHasTraded() {
        OpeningFlow f = flow();
        // Only opening balances so far, so another place still loads.
        f.posted();
        assertThat(opening.obstacleAt(f.storeB).branchHasTraded()).isFalse();

        // A receipt posts at the branch: now nothing more may be loaded there.
        f.tradeAt(receiving);
        assertThat(opening.obstacleAt(f.storeB).branchHasTraded()).isTrue();

        fx.actAs(f.raiser(), fx.branch(f.branchCode));
        assertThatThrownBy(() -> opening.create(f.formFor(f.storeB)))
                .hasMessageContaining("first entry in the ledger");
    }

    @Test
    void aSheetAlreadySignedIsRefusedAtThePostOnceTheBranchHasTraded() {
        OpeningFlow f = flow();
        // Signed while the branch was still untouched...
        UUID opb = f.approved(f.formFor(f.storeB));
        // ...and the branch trades before Finance gets to it.
        f.tradeAt(receiving);

        fx.actAs(f.poster, fx.branch(f.branchCode));
        // The control is a deferred constraint trigger, so it fires when the
        // posting transaction commits — after the service's own try block.
        // The reason is in the cause chain, not the top-level message.
        assertThatThrownBy(() -> opening.post(opb))
                .hasStackTraceContaining("first entry in the ledger");
        // Rolled back whole: the sheet is still approved and nothing moved.
        assertThat(statusOf(opb)).isEqualTo("APPROVED");
        assertThat(onHand(f.glass, f.storeB)).isEqualByComparingTo("0");
    }

    @Test
    void theRaiserCannotAlsoBeTheOnlySignature() {
        OpeningFlow f = flow();
        UUID opb = f.pending();
        // The raiser signed step 1 by submitting; signing again is refused.
        fx.actAs(f.raiser(), fx.branch(f.branchCode));
        assertThatThrownBy(() -> opening.sign(opb, true, null))
                .isInstanceOf(RuntimeException.class);
    }

    // ---- content ---------------------------------------------------------

    @Test
    void glassNeedsItsMeasuredThickness() {
        OpeningFlow f = flow();
        OpeningForm form = f.standardForm();
        form.getLines().get(0).setMeasuredThicknessMm(null);
        fx.actAs(f.raiser(), fx.branch(f.branchCode));
        assertThatThrownBy(() -> opening.create(form))
                .hasMessageContaining("measured thickness");
    }

    @Test
    void theFiguresCannotBeDatedIntoTheFuture() {
        OpeningFlow f = flow();
        OpeningForm form = f.standardForm();
        form.setAsAtDate(LocalDate.now().plusDays(1));
        fx.actAs(f.raiser(), fx.branch(f.branchCode));
        assertThatThrownBy(() -> opening.create(form))
                .hasMessageContaining("still to come");
    }

    @Test
    void aSheetWithNoLinesCannotBeSubmitted() {
        OpeningFlow f = flow();
        OpeningForm form = f.standardForm();
        form.getLines().clear();
        fx.actAs(f.raiser(), fx.branch(f.branchCode));
        UUID opb = opening.create(form);
        assertThatThrownBy(() -> opening.submit(opb))
                .hasMessageContaining("no lines");
    }

    @Test
    void aSignedSheetCannotBeEdited() {
        OpeningFlow f = flow();
        UUID opb = f.pending();
        OpeningForm form = f.standardForm();
        form.setId(opb);
        form.setVersion(1);
        fx.actAs(f.raiser(), fx.branch(f.branchCode));
        assertThatThrownBy(() -> opening.update(opb, form))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void thePostedSheetIsAuditedWithWhatItLoaded() {
        OpeningFlow f = flow();
        UUID opb = f.posted();
        String trail = jdbc.sql("""
                SELECT string_agg(after_state::text, ' ') FROM audit_log WHERE entity_id = :id
                """)
                .param("id", opb).query(String.class).single();
        assertThat(trail).contains("Opening value (RWF)").contains("Figures as at");
    }

    @Test
    void theDocumentEngineKnowsTheType() {
        assertThat(DocumentKind.of("OPB").module()).isEqualTo("opening");
        assertThat(DocumentKind.of("OPB").postRight()).isEqualTo("opening.post");
        assertThat(DocumentKind.of("OPB").rightForStep("VERIFY")).isEqualTo("opening.verify");
    }
}
