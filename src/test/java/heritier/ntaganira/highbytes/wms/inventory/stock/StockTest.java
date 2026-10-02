package heritier.ntaganira.highbytes.wms.inventory.stock;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.stock
 * - File       : StockTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Stock read by item, by place and by movement; a place under a live count shows no book anywhere
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.dashboard.DashboardService;
import heritier.ntaganira.highbytes.wms.inventory.count.CountScope;
import heritier.ntaganira.highbytes.wms.inventory.count.CountService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.inventory.transfer.TransferLookupService;
import heritier.ntaganira.highbytes.wms.masterdata.item.ItemService;
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
 * Against a real PostgreSQL 16 with Flyway V1 to V17, at a location of the test's own: 10 sheets of glass at 8,000
 * in a bin and 4 pieces of silicone at 3,000 unbinned, received through a real receipt, so the balance the ledger
 * keeps is the one read.
 */
class StockTest extends IntegrationTest {

    @Autowired StockService stock;
    @Autowired CountService counts;
    @Autowired ReceivingService receiving;
    @Autowired DashboardService dashboard;
    @Autowired TransferLookupService transferLookups;
    @Autowired ItemService items;

    CountFlow cf;
    UUID reader;

    @BeforeEach
    void stockIt() {
        cf = new CountFlow(fx, counts, receiving);
        cf.stock("10", true, "4");
        reader = fx.user("whm", "WH_MANAGER");
        fx.actAs(reader);
    }

    private StockService.ItemStock itemRow(UUID item) {
        return stock.item(fx.kigali(), item).orElseThrow();
    }

    private BigDecimal branchValue() {
        return dashboard.stockValue(fx.kigali());
    }

    @Test
    void stockIsReadByItemByPlaceAndByMovement() {
        var glass = itemRow(cf.grn.glass);
        assertThat(glass.onHand()).isEqualByComparingTo("10");
        assertThat(glass.value()).isEqualByComparingTo("80000");
        assertThat(glass.places()).isEqualTo(1);
        assertThat(glass.placesCounted()).isZero();

        assertThat(stock.items(fx.kigali(), null, null, cf.location, false, 50))
                .extracting(StockService.ItemStock::itemId)
                .containsExactlyInAnyOrder(cf.grn.glass, cf.grn.silicone);

        assertThat(stock.places(fx.kigali(), cf.grn.glass)).singleElement().satisfies(p -> {
            assertThat(p.locationCode()).isEqualTo(cf.locationCode);
            assertThat(p.binCode()).startsWith("C");
            assertThat(p.onHand()).isEqualByComparingTo("10");
            assertThat(p.cacheDiffers()).isFalse();
            assertThat(p.counted()).isFalse();
        });

        var moves = stock.movements(fx.kigali(), StockService.MovementQuery.ofItem(cf.grn.glass), 10);
        assertThat(moves).singleElement().satisfies(m -> {
            assertThat(m.direction()).isEqualTo("IN");
            assertThat(m.quantity()).isEqualByComparingTo("10");
            assertThat(m.serialNo()).startsWith("GRN-KGL-");
            assertThat(m.movementType()).isEqualTo("RECEIPT");
        });
        // The reader's % is a character, not a wildcard.
        assertThat(stock.movements(fx.kigali(),
                new StockService.MovementQuery(null, null, cf.grn.glass, null, null, "GRN%", null), 10)).isEmpty();
        assertThat(stock.movements(fx.kigali(),
                new StockService.MovementQuery(null, null, cf.grn.glass, null, "OUT", null, null), 10)).isEmpty();
    }

    @Test
    void aPlaceUnderALiveCountShowsNoBookOnAnyPageUntilItsVerificationIsSigned() {
        BigDecimal valueBefore = branchValue();
        UUID count = cf.open(CountScope.PARTIAL, cf.grn.glass);
        fx.actAs(reader);

        // The stock screens: the place is listed as being counted, its quantity in no figure and no movement.
        var glass = itemRow(cf.grn.glass);
        assertThat(glass.onHand()).isEqualByComparingTo("0");
        assertThat(glass.value()).isEqualByComparingTo("0");
        assertThat(glass.places()).isZero();
        assertThat(glass.placesCounted()).isEqualTo(1);
        assertThat(stock.places(fx.kigali(), cf.grn.glass)).singleElement().satisfies(p -> {
            assertThat(p.counted()).isTrue();
            assertThat(p.onHand()).isNull();
            assertThat(p.value()).isNull();
            assertThat(p.ledgerOnHand()).isNull();
        });
        assertThat(stock.movements(fx.kigali(), StockService.MovementQuery.ofItem(cf.grn.glass), 10)).isEmpty();
        assertThat(stock.liveCounts(fx.kigali())).extracting(StockService.LiveCount::documentId).contains(count);
        // What the count does not count stays readable.
        assertThat(itemRow(cf.grn.silicone).onHand()).isEqualByComparingTo("4");

        // Nor anywhere else a total or a lookup would give it away.
        assertThat(branchValue()).isEqualByComparingTo(valueBefore.subtract(new BigDecimal("80000")));
        assertThat(transferLookups.stockAt(cf.location, List.of(cf.grn.glass, cf.grn.silicone)))
                .containsOnlyKeys(cf.grn.silicone);
        assertThat(items.findById(cf.grn.glass, fx.kigali()).orElseThrow().totalOnHand()).isEqualByComparingTo("0");

        // Counted, submitted, and recounted by the Internal Controller: the verification is signed, the book is back.
        cf.count(count, line -> "10");
        cf.submit(count);
        cf.verify(count, line -> "10");
        fx.actAs(cf.verifier());
        counts.sign(count, true, null);
        fx.actAs(reader);
        assertThat(itemRow(cf.grn.glass).onHand()).isEqualByComparingTo("10");
        assertThat(stock.movements(fx.kigali(), StockService.MovementQuery.ofItem(cf.grn.glass), 10)).hasSize(1);
        assertThat(branchValue()).isEqualByComparingTo(valueBefore);
    }

    @Test
    void belowReorderJudgesWhatMayBeReadAndIncludesItemsHoldingNothing() {
        jdbc.sql("UPDATE item SET reorder_level = 20 WHERE id = :id").param("id", cf.grn.glass).update();
        jdbc.sql("UPDATE item SET reorder_level = 2 WHERE id = :id").param("id", cf.grn.silicone).update();
        UUID nothing = fx.item("empty", "HARDWARE", "PC", null);
        jdbc.sql("UPDATE item SET reorder_level = 5 WHERE id = :id").param("id", nothing).update();

        var below = stock.items(fx.kigali(), null, null, null, true, 500).stream()
                .map(StockService.ItemStock::itemId).toList();
        assertThat(below).contains(cf.grn.glass, nothing).doesNotContain(cf.grn.silicone);

        // Being counted, it is not judged: no reorder flag gives the book away either.
        cf.open(CountScope.PARTIAL, cf.grn.glass);
        fx.actAs(reader);
        assertThat(stock.items(fx.kigali(), null, null, null, true, 500))
                .extracting(StockService.ItemStock::itemId).doesNotContain(cf.grn.glass);
    }

    @Test
    void aPlaceCountedWhereTheBookHoldsNothingReadsLikeOneThatHoldsSome() {
        UUID empty = fx.item("cntz", "HARDWARE", "PC", null);
        jdbc.sql("UPDATE item SET reorder_level = 5 WHERE id = :id").param("id", empty).update();
        cf.open(CountScope.PARTIAL, cf.grn.glass, empty);
        fx.actAs(reader);

        // Ten sheets on the book, and nothing: the same shape, read from the count's sheet, never from the book.
        var rows = stock.items(fx.kigali(), null, null, cf.location, false, 50);
        for (UUID counted : List.of(cf.grn.glass, empty)) {
            assertThat(rows).filteredOn(i -> i.itemId().equals(counted)).singleElement().satisfies(i -> {
                assertThat(i.places()).isZero();
                assertThat(i.placesCounted()).isEqualTo(1);
                assertThat(i.onHand()).isEqualByComparingTo("0");
                assertThat(i.belowReorder()).isFalse();
            });
            assertThat(stock.places(fx.kigali(), counted)).singleElement().satisfies(p -> {
                assertThat(p.counted()).isTrue();
                assertThat(p.onHand()).isNull();
            });
        }
        assertThat(stock.items(fx.kigali(), null, null, null, true, 500))
                .extracting(StockService.ItemStock::itemId).doesNotContain(empty, cf.grn.glass);

        // The receipt that stocked the place: its balance after is the book while nothing has moved since.
        UUID receipt = jdbc.sql("""
                SELECT d.id FROM document d JOIN goods_received_note g ON g.document_id = d.id
                 WHERE g.location_id = :loc AND d.status = 'POSTED'
                """).param("loc", cf.location).query(UUID.class).single();
        assertThat(receiving.postingOf(receipt).orElseThrow().movements())
                .filteredOn(m -> m.quantityBase().compareTo(BigDecimal.TEN) == 0)
                .singleElement().satisfies(m -> assertThat(m.runningBalance()).isNull());
    }

    @Test
    void stockIsReadOnlyWithTheRight() {
        fx.actAs(fx.user("sales", "SALES"));
        assertThatThrownBy(() -> stock.items(fx.kigali(), null, null, null, false, 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> stock.movements(fx.kigali(), StockService.MovementQuery.ofItem(cf.grn.glass), 10))
                .isInstanceOf(AccessDeniedException.class);
    }
}
