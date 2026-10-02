package heritier.ntaganira.highbytes.wms.inventory.ticket;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.inventory.ticket
 * - File       : TicketTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Tickets are read as the ledger wrote them, at their own branch, with no balance of a counted place
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.count.CountScope;
import heritier.ntaganira.highbytes.wms.inventory.count.CountService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.support.CountFlow;
import heritier.ntaganira.highbytes.wms.support.GrnFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a real PostgreSQL 16 with Flyway V1 to V17: a receipt posted through the service writes the ticket read. */
class TicketTest extends IntegrationTest {

    @Autowired TicketService tickets;
    @Autowired ReceivingService receiving;
    @Autowired CountService counts;

    GrnFlow flow;
    UUID receipt;
    UUID ticket;
    String receiptSerial;
    String ticketSerial;
    UUID reader;

    @BeforeEach
    void post() {
        flow = new GrnFlow(fx, receiving);
        receipt = flow.approved();
        ticketSerial = flow.post(receipt);
        ticket = jdbc.sql("SELECT document_id FROM transaction_ticket WHERE source_document_id = :id")
                .param("id", receipt).query(UUID.class).single();
        receiptSerial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", receipt)
                .query(String.class).single();
        reader = fx.user("whm", "WH_MANAGER");
        fx.actAs(reader);
    }

    private TicketService.TicketQuery bySerial(String text) {
        return new TicketService.TicketQuery(null, null, null, null, text, null);
    }

    @Test
    void theRegisterListsTheTicketWithTheDocumentItAnswersTo() {
        var row = tickets.list(fx.kigali(), bySerial(ticketSerial), 10);
        assertThat(row).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(ticket);
            assertThat(t.status()).isEqualTo("POSTED");
            assertThat(t.movement()).isEqualTo(TicketMovement.RECEIPT);
            assertThat(t.direction()).isEqualTo("IN");
            assertThat(t.toCode()).isEqualTo("KGL-MAIN");
            assertThat(t.sourceId()).isEqualTo(receipt);
            assertThat(t.sourceSerial()).isEqualTo(receiptSerial);
            assertThat(t.lineCount()).isEqualTo(2);
            assertThat(t.value()).isPositive();
            assertThat(t.postedAt()).isNotNull();
        });
        // Found by the document it answers to, and narrowed by movement and direction.
        assertThat(tickets.list(fx.kigali(), bySerial(receiptSerial), 10))
                .extracting(TicketService.TicketRow::id).containsExactly(ticket);
        assertThat(tickets.list(fx.kigali(),
                new TicketService.TicketQuery(null, null, TicketMovement.DELIVERY, null, ticketSerial, null), 10)).isEmpty();
        assertThat(tickets.list(fx.kigali(),
                new TicketService.TicketQuery(null, null, null, "OUT", ticketSerial, null), 10)).isEmpty();
        // The reader's % is a character, not a wildcard.
        assertThat(tickets.list(fx.kigali(), bySerial("TT%"), 10)).isEmpty();
        // Another branch's register does not hold it.
        assertThat(tickets.list(fx.branch("RBV"), bySerial(ticketSerial), 10)).isEmpty();
    }

    @Test
    void aTicketReadsItsLinesItsMovementsAndTheSignaturesBehindIt() {
        var detail = tickets.find(ticket).orElseThrow();
        assertThat(detail.header().serialNo()).isEqualTo(ticketSerial);
        assertThat(detail.header().sourceSerial()).isEqualTo(receiptSerial);
        assertThat(detail.header().sourceStatus()).isEqualTo("POSTED");
        assertThat(detail.lines()).hasSize(2);
        assertThat(detail.lines().get(0).quantityBase()).isEqualByComparingTo("50");
        assertThat(detail.movements()).hasSize(2).allSatisfy(m -> {
            assertThat(m.direction()).isEqualTo("IN");
            assertThat(m.counted()).isFalse();
        });
        // The receipt's value with its landed cost: 400,000 + 60,000 + 50,000.
        assertThat(detail.value()).isEqualByComparingTo(new BigDecimal("510000"));
        assertThat(detail.sourceChain()).hasSize(flow.chainRoles.size())
                .allSatisfy(s -> assertThat(s.state()).isEqualTo("signed"));
        assertThat(tickets.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aPlaceUnderALiveCountShowsNothingOnATicketAndIsLeftOutOfItsValue() {
        CountFlow cf = new CountFlow(fx, counts, receiving);
        cf.stock("10", true, "4");
        UUID stocked = jdbc.sql("""
                SELECT t.document_id FROM transaction_ticket t
                  JOIN goods_received_note g ON g.document_id = t.source_document_id
                 WHERE g.location_id = :loc
                """).param("loc", cf.location).query(UUID.class).single();
        String serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", stocked)
                .query(String.class).single();
        String glass = jdbc.sql("SELECT item_code FROM item WHERE id = :id").param("id", cf.grn.glass)
                .query(String.class).single();
        fx.actAs(reader);
        BigDecimal before = tickets.find(stocked).orElseThrow().value();
        assertThat(before).isEqualByComparingTo("92000");   // 10 sheets at 8,000 and 4 pieces at 3,000

        cf.open(CountScope.PARTIAL, cf.grn.glass);
        fx.actAs(reader);
        var detail = tickets.find(stocked).orElseThrow();
        assertThat(detail.movements()).filteredOn(m -> m.itemCode().equals(glass)).singleElement().satisfies(m -> {
            assertThat(m.counted()).isTrue();
            assertThat(m.quantityBase()).isNull();
            assertThat(m.unitCost()).isNull();
            assertThat(m.value()).isNull();
            assertThat(m.runningBalance()).isNull();
        });
        assertThat(detail.lines()).filteredOn(l -> l.itemCode().equals(glass)).singleElement().satisfies(l -> {
            assertThat(l.counted()).isTrue();
            assertThat(l.quantity()).isNull();
            assertThat(l.quantityBase()).isNull();
        });
        // What the count does not count reads as before.
        assertThat(detail.movements()).filteredOn(m -> !m.itemCode().equals(glass)).singleElement()
                .satisfies(m -> assertThat(m.runningBalance()).isEqualByComparingTo("4"));
        // Left out of the value, never subtracted: the page and the register read the same.
        assertThat(detail.value()).isEqualByComparingTo("12000");
        assertThat(detail.countedMovements()).isEqualTo(1);
        assertThat(tickets.list(fx.kigali(), bySerial(serial), 10)).singleElement().satisfies(t -> {
            assertThat(t.value()).isEqualByComparingTo("12000");
            assertThat(t.countedMovements()).isEqualTo(1);
        });
    }

    @Test
    void ticketsAreReadOnlyWithTheRightAndAtTheirOwnBranch() {
        fx.actAs(fx.user("sales", "SALES"));
        assertThatThrownBy(() -> tickets.list(fx.kigali(), TicketService.TicketQuery.all(), 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> tickets.find(ticket)).isInstanceOf(AccessDeniedException.class);

        // A Warehouse Manager at Rubavu reads Rubavu's tickets, not Gahanga's reached by address.
        UUID rubavu = fx.userAt("RBV", "whmr", "WH_MANAGER");
        fx.actAs(rubavu, fx.branch("RBV"));
        assertThatThrownBy(() -> tickets.find(ticket)).isInstanceOf(AccessDeniedException.class);
    }
}
