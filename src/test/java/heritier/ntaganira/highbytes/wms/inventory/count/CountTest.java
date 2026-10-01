package heritier.ntaganira.highbytes.wms.inventory.count;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.count
 * - File       : CountTest.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Posts stock counts end to end and proves the blind count, the freeze and the verification hold
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.db.ControlRefusedException;
import heritier.ntaganira.highbytes.wms.dashboard.DashboardService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.GrnForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.GrnLineForm;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.support.CountFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against a real PostgreSQL 16 with Flyway V1 to V15.
 *
 * <p>Each test counts a location of its own, stocked through real goods received
 * notes: 10 sheets of glass in bin C at 8,000 and 20 pieces of silicone unbinned at
 * 3,000, unless it says otherwise. The count chain is read from the definition in
 * force, and a second Finance officer posts.
 */
class CountTest extends IntegrationTest {

    @Autowired ReceivingService receiving;
    @Autowired CountService counts;
    @Autowired DashboardService dashboard;

    CountFlow cf;

    @BeforeEach
    void cast() {
        cf = new CountFlow(fx, counts, receiving);
        cf.stock("10", true, "20");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String statusOf(UUID doc) {
        return jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", doc).query(String.class).single();
    }

    private BigDecimal held(UUID item, UUID bin) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(signed_quantity), 0) FROM stock_movement
                 WHERE item_id = :item AND location_id = :location AND storage_bin_id IS NOT DISTINCT FROM :bin::uuid
                """)
                .param("item", item).param("location", cf.location).param("bin", bin)
                .query(BigDecimal.class).single();
    }

    /** 7 sheets of glass and 22 pieces of silicone: 3 short, 2 over. */
    private String shortAndOver(CountLineRow l) {
        return l.itemId().equals(cf.grn.glass) ? "7" : "22";
    }

    private String asBooked(CountLineRow l) {
        return l.itemId().equals(cf.grn.glass) ? "10" : "20";
    }

    // ---- the whole count -------------------------------------------------------------------------

    @Test
    void aFullCountAdjustsTheLedgerToWhatWasFoundEachWayAtItsOwnValue() {
        UUID id = cf.approved(this::shortAndOver);
        List<String> tickets = cf.post(id);

        assertThat(statusOf(id)).isEqualTo("POSTED");
        assertThat(tickets).hasSize(2);
        assertThat(held(cf.grn.glass, cf.bin)).isEqualByComparingTo("7");
        assertThat(held(cf.grn.silicone, null)).isEqualByComparingTo("22");

        // The shortage leaves at the average (3 x 8,000); the surplus enters at its line's unit cost (2 x 3,000).
        var values = jdbc.sql("""
                SELECT m.direction, m.value FROM stock_movement m
                  JOIN transaction_ticket t ON t.document_id = m.document_id
                 WHERE t.source_document_id = :id AND t.movement_type = 'ADJUSTMENT' ORDER BY m.direction
                """)
                .param("id", id)
                .query((rs, n) -> rs.getString(1) + " " + rs.getBigDecimal(2).toPlainString()).list();
        assertThat(values).containsExactly("IN 6000.00", "OUT 24000.00");

        fx.actAs(cf.raiser());
        var lines = counts.detail(id).lines();
        assertThat(lines).extracting(CountLineRow::varianceQty)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("-3"), new BigDecimal("2"));
    }

    @Test
    void aCountThatAgreesEverywherePostsWithNoTicketAndMovesNothing() {
        UUID id = cf.approved(this::asBooked);
        assertThat(cf.post(id)).isEmpty();
        assertThat(statusOf(id)).isEqualTo("POSTED");
        assertThat(held(cf.grn.glass, cf.bin)).isEqualByComparingTo("10");
        assertThat(dashboard.kpis(fx.kigali()).inventoryAccuracy()).isNotEqualTo("—");
    }

    // ---- blind --------------------------------------------------------------------------------

    @Test
    void theBookIsInNoSheetUntilTheVerificationIsSigned() {
        UUID id = cf.open(CountScope.FULL);
        fx.actAs(cf.raiser());
        assertThat(counts.detail(id).lines()).allSatisfy(l -> {
            assertThat(l.bookQty()).isNull();
            assertThat(l.unitCost()).isNull();
        });
        assertThat(counts.countingSheet(id).lines()).allSatisfy(l -> assertThat(l.bookQty()).isNull());

        cf.count(id, this::shortAndOver);
        fx.actAs(cf.raiser());
        // The counter sees their own count on the sheet; the view shows only that a line is counted.
        assertThat(counts.countingSheet(id).lines()).allSatisfy(l -> assertThat(l.countedQty()).isNotNull());
        assertThat(counts.detail(id).lines()).allSatisfy(l -> {
            assertThat(l.counted()).isTrue();
            assertThat(l.countedQty()).isNull();
        });

        cf.submit(id);
        fx.actAs(cf.verifier());
        // The verifier sees neither the book nor the first count.
        assertThat(counts.verificationSheet(id).lines()).allSatisfy(l -> {
            assertThat(l.bookQty()).isNull();
            assertThat(l.countedQty()).isNull();
            assertThat(l.varianceQty()).isNull();
        });
        assertThat(counts.detail(id).lines()).allSatisfy(l -> assertThat(l.countedQty()).isNull());

        cf.verify(id, this::shortAndOver);
        fx.actAs(cf.verifier());
        counts.sign(id, true, null);
        assertThat(counts.detail(id).lines()).allSatisfy(l -> {
            assertThat(l.bookQty()).isNotNull();
            assertThat(l.countedQty()).isNotNull();
        });
    }

    @Test
    void aCountCancelledWhileCountingNeverShowsItsBook() {
        UUID id = cf.open(CountScope.FULL);
        cf.count(id, this::shortAndOver);
        fx.actAs(cf.raiser());
        counts.cancel(id, "Opened on the wrong day");
        assertThat(counts.detail(id).lines()).allSatisfy(l -> {
            assertThat(l.bookQty()).isNull();
            assertThat(l.varianceQty()).isNull();
        });
    }

    // ---- the freeze ------------------------------------------------------------------------------

    @Test
    void whatIsBeingCountedDoesNotMoveUntilTheVerificationIsSigned() {
        UUID id = cf.open(CountScope.FULL);
        GrnForm more = new GrnForm();
        more.setSupplierId(cf.grn.supplier);
        more.setLocationId(cf.location);
        GrnLineForm line = new GrnLineForm();
        line.setItemId(cf.grn.silicone);
        line.setUomId(cf.grn.pieces);
        line.setQuantity(new BigDecimal("5"));
        line.setUnitPrice(new BigDecimal("3000"));
        more.getLines().add(line);
        UUID receipt = cf.grn.approved(more);

        assertThatThrownBy(() -> cf.grn.post(receipt))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("is being counted");
        assertThat(statusOf(receipt)).isEqualTo("APPROVED");

        cf.count(id, this::asBooked);
        cf.submit(id);
        assertThatThrownBy(() -> cf.grn.post(receipt)).hasMessageContaining("is being counted");

        cf.verify(id, this::asBooked);
        fx.actAs(cf.verifier());
        counts.sign(id, true, null);
        cf.grn.post(receipt);
        assertThat(held(cf.grn.silicone, null)).isEqualByComparingTo("25");
    }

    @Test
    void aCycleCountFreezesOnlyTheItemsOnItsSheet() {
        UUID id = cf.open(CountScope.PARTIAL, cf.grn.glass);
        fx.actAs(cf.raiser());
        assertThat(counts.countingSheet(id).lines()).extracting(CountLineRow::itemId).containsOnly(cf.grn.glass);

        cf.stock(null, false, "4");                 // silicone still moves
        assertThat(held(cf.grn.silicone, null)).isEqualByComparingTo("24");
        assertThatThrownBy(() -> cf.stock("1", true, null)).hasMessageContaining("is being counted");
    }

    @Test
    void oneCountAtATimePerLocation() {
        cf.open(CountScope.PARTIAL, cf.grn.glass);
        assertThatThrownBy(() -> cf.open(CountScope.PARTIAL, cf.grn.silicone))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("is already counting it");
    }

    // ---- counting --------------------------------------------------------------------------------

    @Test
    void everyLineIsCountedBeforeSubmission() {
        UUID id = cf.open(CountScope.FULL);
        cf.count(id, l -> l.itemId().equals(cf.grn.glass) ? "10" : null);
        assertThatThrownBy(() -> cf.submit(id))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("1 line of")
                .hasMessageContaining("has not been counted");
        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void sheetsAreCountedInWholeNumbers() {
        UUID id = cf.open(CountScope.FULL);
        assertThatThrownBy(() -> cf.count(id, l -> l.itemId().equals(cf.grn.glass) ? "2.5" : "20"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("whole numbers");
    }

    @Test
    void twoCountersSavingTheSameSheetDoNotBlankEachOther() {
        UUID id = cf.open(CountScope.FULL);
        fx.actAs(cf.raiser());
        var first = counts.countingSheet(id);
        var second = counts.countingSheet(id);
        UUID glassLine = first.lines().stream().filter(l -> l.itemId().equals(cf.grn.glass)).findFirst().orElseThrow().id();
        UUID siliconeLine = first.lines().stream().filter(l -> !l.itemId().equals(cf.grn.glass)).findFirst().orElseThrow().id();
        first.form().getLines().stream().filter(l -> l.getLineId().equals(glassLine)).findFirst().orElseThrow()
                .setQuantity(new BigDecimal("9"));
        second.form().getLines().stream().filter(l -> l.getLineId().equals(siliconeLine)).findFirst().orElseThrow()
                .setQuantity(new BigDecimal("19"));
        assertThat(counts.saveCounts(id, first.form())).isEqualTo(1);
        assertThat(counts.saveCounts(id, second.form())).isEqualTo(1);
        assertThat(counts.countingSheet(id).lines()).allSatisfy(l -> assertThat(l.countedQty()).isNotNull());
    }

    @Test
    void stockFoundOffTheSheetIsAddedAndAFoundLineCanBeRemovedButNotTheSheetsOwn() {
        UUID id = cf.open(CountScope.FULL);
        FoundLineForm found = new FoundLineForm();
        found.setItemId(cf.grn.silicone);
        found.setBinId(cf.bin);
        found.setQuantity(new BigDecimal("3"));
        fx.actAs(cf.raiser());
        CountService.Found result = counts.addFoundLine(id, found);
        assertThat(result.onBook()).isFalse();
        assertThat(result.placesAdded()).isEmpty();
        int lineNo = result.lineNo();
        assertThat(lineNo).isEqualTo(3);

        var lines = counts.countingSheet(id).lines();
        CountLineRow added = lines.stream().filter(l -> l.lineNo() == lineNo).findFirst().orElseThrow();
        assertThat(added.onSheet()).isFalse();
        CountLineRow own = lines.stream().filter(CountLineRow::onSheet).findFirst().orElseThrow();
        assertThatThrownBy(() -> counts.removeLine(id, own.id()))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("is counted, not removed");
        counts.removeLine(id, added.id());
        assertThat(counts.countingSheet(id).lines()).hasSize(2);

        assertThatThrownBy(() -> counts.addFoundLine(id, withItem(cf.grn.glass, cf.bin)))
                .hasMessageContaining("is already on the sheet");
    }

    @Test
    void anItemACycleCountDoesNotCoverBringsItsPlacesOnTheBookOntoTheSheet() {
        UUID id = cf.open(CountScope.PARTIAL, cf.grn.glass);
        fx.actAs(cf.raiser());

        // Silicone found in bin C: the book holds silicone unbinned, so that place joins the sheet to be counted,
        // and bin C is a found line of its own, its book nothing, which may be removed again.
        CountService.Found inBin = counts.addFoundLine(id, withItem(cf.grn.silicone, cf.bin));
        assertThat(inBin.onBook()).isFalse();
        assertThat(inBin.placesAdded()).containsExactly(2);
        assertThat(inBin.lineNo()).isEqualTo(3);
        counts.removeLine(id, counts.countingSheet(id).lines().stream()
                .filter(l -> l.lineNo() == 3).findFirst().orElseThrow().id());

        // The unbinned silicone the sheet now lists is counted there, not added twice.
        assertThatThrownBy(() -> counts.addFoundLine(id, withItem(cf.grn.silicone, null)))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("already on the sheet as line 2");
    }

    @Test
    void stockFoundWhereTheBookHoldsItIsCountedOnThatPlacesLine() {
        UUID id = cf.open(CountScope.PARTIAL, cf.grn.glass);
        fx.actAs(cf.raiser());
        FoundLineForm found = withItem(cf.grn.silicone, null);
        found.setQuantity(new BigDecimal("22"));
        CountService.Found result = counts.addFoundLine(id, found);
        assertThat(result.onBook()).isTrue();
        assertThat(result.lineNo()).isEqualTo(2);
        assertThat(result.placesAdded()).isEmpty();

        CountLineRow line = counts.countingSheet(id).lines().stream().filter(l -> l.lineNo() == 2).findFirst().orElseThrow();
        assertThat(line.onSheet()).isTrue();
        assertThat(line.countedQty()).isEqualByComparingTo("22");
        // The silicone is now counted, so it is frozen here too.
        assertThatThrownBy(() -> cf.stock(null, false, "1")).hasMessageContaining("is being counted");
    }

    private FoundLineForm withItem(UUID item, UUID bin) {
        FoundLineForm f = new FoundLineForm();
        f.setItemId(item);
        f.setBinId(bin);
        f.setQuantity(BigDecimal.ONE);
        return f;
    }

    // ---- verification ----------------------------------------------------------------------------

    @Test
    void theVerificationIsSignedOnlyOnceEveryChosenLineIsRecountedAndTheRecountPrevails() {
        UUID id = cf.open(CountScope.FULL);
        cf.count(id, this::shortAndOver);
        cf.submit(id);

        fx.actAs(cf.verifier());
        assertThat(counts.verificationSheet(id).lines()).allSatisfy(l -> assertThat(l.verifyRequired()).isTrue());
        assertThatThrownBy(() -> counts.sign(id, true, null))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("Not every line")
                .hasMessageContaining("has been recounted");

        // The Internal Controller finds 8 sheets, not 7: the recount is what the adjustment uses.
        cf.verify(id, l -> l.itemId().equals(cf.grn.glass) ? "8" : "22");
        cf.signRemainingSteps(id);
        cf.post(id);
        assertThat(held(cf.grn.glass, cf.bin)).isEqualByComparingTo("8");
    }

    @Test
    void theFirstCountClosesAtSubmissionAndOnlyTheVerifierRecounts() {
        UUID id = cf.open(CountScope.FULL);
        cf.count(id, this::shortAndOver);
        cf.submit(id);
        fx.actAs(cf.raiser());
        assertThatThrownBy(() -> counts.countingSheet(id))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("first count closed");
        assertThatThrownBy(() -> counts.verificationSheet(id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void nobodyButTheVerifierLearnsWhichLinesAreRecountedUntilTheVerificationIsSigned() {
        UUID id = cf.open(CountScope.FULL);
        cf.count(id, this::shortAndOver);
        cf.submit(id);
        cf.verify(id, this::shortAndOver);

        // The chosen lines are every line differing from the book: shown to a counter, they would read the book.
        fx.actAs(cf.raiser());
        var seen = counts.detail(id);
        assertThat(seen.progress().recountShown()).isFalse();
        assertThat(seen.lines()).allSatisfy(l -> {
            assertThat(l.verifyRequired()).isFalse();
            assertThat(l.verified()).isFalse();
        });
        fx.actAs(cf.verifier());
        assertThat(counts.detail(id).progress().recountShown()).isTrue();

        // Nor does the trail the counters read: it says a verification count was entered, never on which lines.
        List<String> trail = jdbc.sql("SELECT COALESCE(after_state::text, '') || ' ' || COALESCE(reason, '') FROM audit_log WHERE entity_id = :id")
                .param("id", id).query(String.class).list();
        assertThat(trail).anyMatch(s -> s.contains("Verification count"));
        assertThat(trail).noneMatch(s -> s.contains("Verification count entered on lines"));
    }

    @Test
    void onceTheVerificationIsSignedTheCountIsNoLongerCancelled() {
        UUID id = cf.open(CountScope.FULL);
        cf.count(id, this::shortAndOver);
        cf.submit(id);
        cf.verify(id, this::shortAndOver);
        fx.actAs(cf.verifier());
        counts.sign(id, true, null);

        // The shortage the Internal Controller confirmed cannot be withdrawn by the custodian whose stock it is.
        fx.actAs(cf.raiser());
        assertThat(counts.detail(id).actions().canCancel()).isFalse();
        assertThatThrownBy(() -> counts.cancel(id, "Shortage found"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("can no longer be cancelled");
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void whoeverTookPartInTheFirstCountNeitherApprovesNorRejectsIt() {
        // No segregation rule pairs the Warehouse Manager with Finance, so one person may hold both.
        UUID both = fx.user("cntboth", "WH_MANAGER", "FINANCE");
        UUID id = cf.open(CountScope.FULL);
        fx.actAs(both);
        var sheet = counts.countingSheet(id);
        for (int i = 0; i < sheet.lines().size(); i++) {
            if (sheet.lines().get(i).itemId().equals(cf.grn.glass)) sheet.form().getLines().get(i).setQuantity(new BigDecimal("7"));
        }
        counts.saveCounts(id, sheet.form());
        cf.count(id, l -> l.itemId().equals(cf.grn.glass) ? null : "22");
        cf.submit(id);
        cf.verify(id, this::shortAndOver);
        fx.actAs(cf.verifier());
        counts.sign(id, true, null);

        fx.actAs(both);
        assertThat(counts.detail(id).actions().canSign()).isFalse();
        assertThatThrownBy(() -> counts.sign(id, true, null))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("took part in the first count");
        assertThatThrownBy(() -> counts.sign(id, false, "Not convinced"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("took part in the first count");
        assertThat(statusOf(id)).isEqualTo("PENDING");

        fx.actAs(cf.approver());
        counts.sign(id, true, null);
        assertThat(statusOf(id)).isEqualTo("APPROVED");
    }

    // ---- posting ---------------------------------------------------------------------------------

    @Test
    void whoeverSignedTheCountDoesNotPostIt() {
        UUID id = cf.approved(this::shortAndOver);
        fx.actAs(cf.approver());
        assertThatThrownBy(() -> counts.post(id)).isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("so you cannot post it");
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        cf.post(id);
        assertThat(statusOf(id)).isEqualTo("POSTED");
    }

    @Test
    void aPostedCountIsNeverCancelled() {
        UUID id = cf.approved(this::shortAndOver);
        cf.post(id);
        fx.actAs(cf.raiser());
        assertThatThrownBy(() -> counts.cancel(id, "Second thoughts"))
                .isInstanceOf(ControlRefusedException.class)
                .hasMessageContaining("posted");
        assertThat(statusOf(id)).isEqualTo("POSTED");
    }
}
