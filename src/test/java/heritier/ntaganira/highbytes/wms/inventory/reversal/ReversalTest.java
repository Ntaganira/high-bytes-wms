package heritier.ntaganira.highbytes.wms.inventory.reversal;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.reversal
 * - File       : ReversalTest.java
 * - Date       : 2026-10-05
 * - Author     : NTAGANIRA Heritier
 * - Desc       : A posted document undone whole, by its exact mirror, by people who had no hand in it
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.document.DocumentService;
import heritier.ntaganira.highbytes.wms.inventory.count.CountService;
import heritier.ntaganira.highbytes.wms.inventory.damage.DamageService;
import heritier.ntaganira.highbytes.wms.inventory.opening.OpeningService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.GrnForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.GrnLineForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.CountFlow;
import heritier.ntaganira.highbytes.wms.support.DamageFlow;
import heritier.ntaganira.highbytes.wms.support.GrnFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import heritier.ntaganira.highbytes.wms.support.OpeningFlow;
import heritier.ntaganira.highbytes.wms.support.ReversalFlow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Against a real PostgreSQL 16 with Flyway V1 to V21: the database holds the rules, this class names the field. */
class ReversalTest extends IntegrationTest {

    @Autowired ReversalService reversals;
    @Autowired ReceivingService receiving;
    @Autowired DamageService damage;
    @Autowired CountService counts;
    @Autowired OpeningService opening;
    @Autowired DocumentService documents;
    @Autowired MockMvc mvc;

    record Balance(BigDecimal quantity, BigDecimal value) {}

    private Balance balance(UUID item, UUID location, UUID bin) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(qty_on_hand), 0) AS q, COALESCE(SUM(total_value), 0) AS v FROM stock_balance
                 WHERE item_id = :item AND location_id = :location AND storage_bin_id IS NOT DISTINCT FROM :bin::uuid
                """)
                .param("item", item, Types.OTHER)
                .param("location", location, Types.OTHER)
                .param("bin", bin, Types.OTHER)
                .query((rs, n) -> new Balance(rs.getBigDecimal("q"), rs.getBigDecimal("v")))
                .single();
    }

    private String statusOf(UUID id) {
        return jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", id, Types.OTHER)
                .query(String.class).single();
    }

    private UUID postedReceipt(GrnFlow g) {
        UUID grn = g.approved();
        g.post(grn);
        return grn;
    }

    // ---- the mirror ------------------------------------------------------------------------------------------

    @Test
    void aReceiptIsUndoneWholeAndBothStayOnRecord() {
        GrnFlow g = new GrnFlow(fx, receiving);
        UUID grn = postedReceipt(g);
        assertThat(balance(g.glass, g.location, g.bin).quantity()).isEqualByComparingTo("50");

        ReversalFlow r = new ReversalFlow(fx, reversals);
        UUID rev = r.reverse(grn);

        assertThat(balance(g.glass, g.location, g.bin).quantity()).isEqualByComparingTo("0");
        assertThat(balance(g.glass, g.location, g.bin).value()).as("left at the value it came in at").isEqualByComparingTo("0");
        assertThat(balance(g.silicone, g.location, null).quantity()).isEqualByComparingTo("0");
        assertThat(balance(g.silicone, g.location, null).value()).isEqualByComparingTo("0");
        assertThat(statusOf(grn)).as("the original stays exactly as posted").isEqualTo("POSTED");
        assertThat(statusOf(rev)).isEqualTo("POSTED");

        // One mirror per movement: the other way, the same quantity and value, naming what it undoes.
        long mirrored = jdbc.sql("""
                SELECT COUNT(*)
                  FROM stock_movement o
                  JOIN transaction_ticket t ON t.document_id = o.document_id
                  JOIN stock_movement m     ON m.reverses_movement_id = o.id
                 WHERE t.source_document_id = :grn
                   AND m.direction <> o.direction AND m.quantity_base_uom = o.quantity_base_uom AND m.value = o.value
                """).param("grn", grn, Types.OTHER).query(Long.class).single();
        assertThat(mirrored).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM transaction_ticket WHERE source_document_id = :rev AND movement_type = 'REVERSAL'
                """).param("rev", rev, Types.OTHER).query(Long.class).single()).isEqualTo(1);

        assertThat(documents.reversedBy(grn)).hasValueSatisfying(by -> {
            assertThat(by.id()).isEqualTo(rev);
            assertThat(by.posted()).isTrue();
        });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :rev AND action = 'POST'")
                .param("rev", rev, Types.OTHER).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void stockThatHasSinceLeftCannotBeTakenOffTheBooksAgain() {
        GrnFlow g = new GrnFlow(fx, receiving);
        UUID grn = postedReceipt(g);
        DamageFlow d = new DamageFlow(fx, damage);
        d.post(d.approved(d.writeOff(g.glass, "SHEET", "10", g.bin)));

        ReversalFlow r = new ReversalFlow(fx, reversals);
        UUID rev = r.approved(grn);

        fx.actAs(r.poster);
        var glassLine = reversals.detail(rev).lines().stream()
                .filter(l -> l.itemId().equals(g.glass)).findFirst().orElseThrow();
        assertThat(glassLine.problem()).as("the page says why before anyone tries").contains("holds 40");

        assertThatThrownBy(() -> r.post(rev))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("Not enough stock");
        assertThat(statusOf(rev)).as("rolled back whole").isEqualTo("APPROVED");
        assertThat(balance(g.glass, g.location, g.bin).quantity()).isEqualByComparingTo("40");
    }

    // ---- who may undo it --------------------------------------------------------------------------------------

    @Test
    void theOriginalsRaiserAndPosterTakeNoPart() {
        GrnFlow g = new GrnFlow(fx, receiving);
        postedReceipt(g);
        DamageFlow d = new DamageFlow(fx, damage);   // raised by a Warehouse Manager, posted by Finance
        UUID dmg = d.approved(d.writeOff(g.glass, "SHEET", "5", g.bin));
        d.post(dmg);
        ReversalFlow r = new ReversalFlow(fx, reversals);

        fx.actAs(d.raiser());
        assertThatThrownBy(() -> reversals.create(r.form(dmg)))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("You raised")
                .hasMessageContaining("takes no part");

        UUID rev = r.approved(dmg);
        fx.actAs(d.poster);
        assertThatThrownBy(() -> reversals.post(rev))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("takes no part in undoing it");

        r.post(rev);
        assertThat(balance(g.glass, g.location, g.bin).quantity()).as("the five written off are back")
                .isEqualByComparingTo("50");
    }

    @Test
    void theOriginalsRaiserCanNeitherEditNorCancelItsReversal() {
        GrnFlow g = new GrnFlow(fx, receiving);
        postedReceipt(g);
        DamageFlow d = new DamageFlow(fx, damage);   // raised by a Warehouse Manager, who holds reversal.create
        UUID dmg = d.approved(d.writeOff(g.glass, "SHEET", "5", g.bin));
        d.post(dmg);
        ReversalFlow r = new ReversalFlow(fx, reversals);
        UUID rev = r.draft(dmg);

        fx.actAs(d.raiser());
        ReversalForm edit = r.form(dmg);
        edit.setVersion(reversals.find(rev).version());
        edit.setReason("Withdrawn: the write-off was right after all, says its author.");
        assertThatThrownBy(() -> reversals.update(rev, edit))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("take no part");

        fx.actAs(r.raiser);
        reversals.submit(rev);
        fx.actAs(r.verifier);
        reversals.sign(rev, true, null);
        fx.actAs(r.approver);
        reversals.sign(rev, true, null);

        fx.actAs(d.raiser());
        assertThatThrownBy(() -> reversals.cancel(rev, "Not needed"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("cannot cancel its reversal");
        assertThat(statusOf(rev)).as("the signed reversal stands").isEqualTo("APPROVED");
    }

    @Test
    void aReversalIsReadOnlyWhereTheReaderHoldsTheRight() {
        GrnFlow g = new GrnFlow(fx, receiving);
        UUID grn = postedReceipt(g);
        UUID rev = new ReversalFlow(fx, reversals).draft(grn);

        UUID elsewhere = fx.userAt("RBV", "revrbv", "WH_MANAGER");
        fx.actAs(elsewhere, fx.branch("RBV"));
        assertThatThrownBy(() -> reversals.detail(rev))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void aDocumentIsReversedOnceAndACancelledReversalStepsAside() {
        GrnFlow g = new GrnFlow(fx, receiving);
        UUID grn = postedReceipt(g);
        ReversalFlow r = new ReversalFlow(fx, reversals);

        UUID first = r.draft(grn);
        assertThatThrownBy(() -> r.draft(grn))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("already being reversed");

        fx.actAs(r.raiser);
        reversals.cancel(first, "Raised against the wrong receipt");
        assertThat(r.draft(grn)).isNotEqualTo(first);
    }

    @Test
    void onlyAPostedDocumentIsReversed() {
        GrnFlow g = new GrnFlow(fx, receiving);
        UUID approved = g.approved();
        ReversalFlow r = new ReversalFlow(fx, reversals);
        assertThatThrownBy(() -> r.draft(approved))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("Only a posted document is reversed");
    }

    @Test
    void aReversalNeedsAReasonTheSignersCanJudge() {
        GrnFlow g = new GrnFlow(fx, receiving);
        UUID grn = postedReceipt(g);
        ReversalFlow r = new ReversalFlow(fx, reversals);
        ReversalForm form = r.form(grn);
        form.setReason("wrong");
        fx.actAs(r.raiser);
        assertThatThrownBy(() -> reversals.create(form))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("reversal reason says what was wrong");
    }

    // ---- each kind it reverses --------------------------------------------------------------------------------

    @Test
    void aWriteOffComesBackAtTheValueItLeftAt() {
        GrnFlow g = new GrnFlow(fx, receiving);
        postedReceipt(g);
        Balance before = balance(g.glass, g.location, g.bin);
        DamageFlow d = new DamageFlow(fx, damage);
        UUID dmg = d.approved(d.writeOff(g.glass, "SHEET", "12", g.bin));
        d.post(dmg);

        new ReversalFlow(fx, reversals).reverse(dmg);
        assertThat(balance(g.glass, g.location, g.bin).quantity()).isEqualByComparingTo(before.quantity());
        assertThat(balance(g.glass, g.location, g.bin).value()).isEqualByComparingTo(before.value());
    }

    @Test
    void aQuarantineReleaseGoesBackToQuarantine() {
        GrnFlow g = new GrnFlow(fx, receiving);
        UUID quarantine = fx.location("KGL-QUAR");
        GrnForm form = new GrnForm();
        form.setSupplierId(g.supplier);
        form.setLocationId(quarantine);
        GrnLineForm line = new GrnLineForm();
        line.setItemId(g.silicone);
        line.setUomId(g.pieces);
        line.setQuantity(new BigDecimal("30"));
        line.setUnitPrice(new BigDecimal("3000"));
        form.getLines().add(line);
        g.post(g.approved(form));

        DamageFlow d = new DamageFlow(fx, damage);
        UUID release = d.approved(d.release(g.silicone, "PC", "12"));
        d.post(release);
        assertThat(balance(g.silicone, quarantine, null).quantity()).isEqualByComparingTo("18");

        new ReversalFlow(fx, reversals).reverse(release);
        assertThat(balance(g.silicone, quarantine, null).quantity()).isEqualByComparingTo("30");
        assertThat(balance(g.silicone, quarantine, null).value()).isEqualByComparingTo("90000");
        assertThat(balance(g.silicone, g.location, null).quantity()).isEqualByComparingTo("0");
    }

    @Test
    void aCountAdjustmentIsUndoneBackToTheBook() {
        CountFlow cf = new CountFlow(fx, counts, receiving);
        cf.stock("10", true, "20");
        UUID cnt = cf.approved(l -> l.itemId().equals(cf.grn.glass) ? "7" : "22");
        cf.post(cnt);
        assertThat(balance(cf.grn.glass, cf.location, cf.bin).quantity()).isEqualByComparingTo("7");
        assertThat(balance(cf.grn.silicone, cf.location, null).quantity()).isEqualByComparingTo("22");

        new ReversalFlow(fx, reversals).reverse(cnt);
        assertThat(balance(cf.grn.glass, cf.location, cf.bin).quantity()).isEqualByComparingTo("10");
        assertThat(balance(cf.grn.silicone, cf.location, null).quantity()).isEqualByComparingTo("20");
        assertThat(statusOf(cnt)).isEqualTo("POSTED");
    }

    @Test
    void aReversedOpeningBalanceLoadsAgainUntilTheBranchTrades() {
        OpeningFlow of = new OpeningFlow(fx, opening);
        UUID first = of.approved();
        of.post(first);
        ReversalFlow r = new ReversalFlow(fx, reversals, of.branchCode);
        r.reverse(first);
        assertThat(balance(of.glass, of.storeA, of.bin).quantity()).isEqualByComparingTo("0");
        assertThat(opening.obstacleAt(of.storeA).blocked()).as("the place may be loaded again").isFalse();

        UUID corrected = of.approved();
        of.post(corrected);
        assertThat(balance(of.glass, of.storeA, of.bin).quantity()).isEqualByComparingTo("50");

        // Once the branch has traded, a reversed baseline is not replaced: stock found later is a count adjustment.
        // The third sheet is signed while the branch is still untouched, and refused at the post.
        r.reverse(corrected);
        UUID third = of.approved();
        of.tradeAt(receiving);
        fx.actAs(of.poster, fx.branch(of.branchCode));
        assertThatThrownBy(() -> opening.post(third)).hasStackTraceContaining("first entry in the ledger");
    }

    // ---- what the close and the screens show ------------------------------------------------------------------

    @Test
    void theCloseCountsAReversalAsAnAdjustmentNotADispatch() {
        GrnFlow g = new GrnFlow(fx, receiving);
        UUID grn = postedReceipt(g);
        record Figures(BigDecimal receipts, BigDecimal dispatches, BigDecimal adjustments) {}
        var read = (java.util.function.Supplier<Figures>) () -> jdbc.sql("""
                SELECT receipts_value, dispatches_value, adjustments_value FROM close_figures(:branch, kigali_today())
                """).param("branch", fx.kigali(), Types.OTHER)
                .query((rs, n) -> new Figures(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3))).single();
        BigDecimal value = jdbc.sql("""
                SELECT SUM(m.value) FROM stock_movement m JOIN transaction_ticket t ON t.document_id = m.document_id
                 WHERE t.source_document_id = :grn
                """).param("grn", grn, Types.OTHER).query(BigDecimal.class).single();

        Figures before = read.get();
        new ReversalFlow(fx, reversals).reverse(grn);
        Figures after = read.get();

        assertThat(after.receipts()).isEqualByComparingTo(before.receipts());
        assertThat(after.dispatches()).as("a reversed receipt is not goods leaving").isEqualByComparingTo(before.dispatches());
        assertThat(after.adjustments()).isEqualByComparingTo(before.adjustments().subtract(value));
    }

    @Test
    void theScreensRaiseAReversalFromTheOriginalsPage() throws Exception {
        GrnFlow g = new GrnFlow(fx, receiving);
        UUID grn = postedReceipt(g);
        ReversalFlow r = new ReversalFlow(fx, reversals);
        AppUserDetails raiser = userDetails.reload(r.raiser, fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();

        mvc.perform(get("/receiving/" + grn).with(user(raiser)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Raise a reversal")));
        mvc.perform(get("/reversals/new").param("original", grn.toString()).with(user(raiser)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Raise the reversal as a draft")));
        mvc.perform(post("/reversals").with(user(raiser)).with(csrf())
                        .param("originalId", grn.toString())
                        .param("reason", ReversalFlow.REASON))
                .andExpect(redirectedUrlPattern("/reversals/*"));

        UUID rev = documents.reversedBy(grn).orElseThrow().id();
        mvc.perform(get("/reversals/" + rev).with(user(raiser)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ReversalFlow.REASON)));
        mvc.perform(get("/receiving/" + grn).with(user(raiser)))
                .andExpect(content().string(containsString("Being reversed")));
        mvc.perform(get("/documents/" + rev).with(user(raiser)))
                .andExpect(redirectedUrlPattern("/reversals/*"));
        mvc.perform(get("/reversals").with(user(raiser)))
                .andExpect(status().isOk());
    }
}
