package heritier.ntaganira.highbytes.wms.document;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.document
 * - File       : DocumentListTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The register of every document reaches as far as the reader's view rights, branch by branch
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.support.GrnFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real PostgreSQL 16 with Flyway V1 to V17. One receipt in each state, raised through the service; every
 * assertion asks about this test's own serials, since the shared database holds every other test's documents too.
 */
class DocumentListTest extends IntegrationTest {

    @Autowired DocumentListService register;
    @Autowired ReceivingService receiving;

    GrnFlow flow;
    UUID draft;
    UUID pending;
    UUID posted;
    UUID cancelled;
    UUID ticket;
    UUID reader;

    @BeforeEach
    void cast() {
        flow = new GrnFlow(fx, receiving);
        draft = flow.draft();
        pending = flow.pending();
        posted = flow.approved();
        flow.post(posted);
        cancelled = flow.draft();
        fx.actAs(flow.raiser());
        receiving.cancel(cancelled, "Raised twice");
        ticket = jdbc.sql("SELECT document_id FROM transaction_ticket WHERE source_document_id = :id")
                .param("id", posted).query(UUID.class).single();
        reader = fx.user("whm", "WH_MANAGER");
    }

    private String serial(UUID id) {
        return jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", id).query(String.class).single();
    }

    private List<DocumentRow> find(UUID as, UUID document) {
        fx.actAs(as);
        return register.list(DocumentListService.Query.text(serial(document)), 10);
    }

    private DocumentRow rowOf(UUID as, UUID document) {
        return find(as, document).stream().filter(r -> r.id().equals(document)).findFirst().orElseThrow();
    }

    @Test
    void everyStateIsListedAndSaysWhereItStands() {
        assertThat(rowOf(reader, draft).standing()).isEqualTo("Draft, not yet submitted");
        var waiting = rowOf(reader, pending);
        assertThat(waiting.status()).isEqualTo("PENDING");
        assertThat(waiting.step()).isEqualTo(2);
        assertThat(waiting.standing()).startsWith("Step 2 of " + flow.chainRoles.size() + ", awaiting ");
        assertThat(rowOf(reader, posted).standing()).startsWith("Posted ").contains(" by ");
        // A cancelled serial stays on the register, with its reason.
        assertThat(rowOf(reader, cancelled).standing()).isEqualTo("Cancelled: Raised twice");
        // The Warehouse Manager reads tickets too: the receipt's ticket is on the register.
        var t = rowOf(reader, ticket);
        assertThat(t.typeCode()).isEqualTo("TT");
        assertThat(t.openHere()).isTrue();
    }

    @Test
    void theRegisterReachesAsFarAsTheViewRightBranchByBranch() {
        // No receiving right: no receipt. A Warehouse Manager at Rubavu reads Rubavu's, not Gahanga's.
        assertThat(find(fx.user("sales", "SALES"), pending)).isEmpty();
        assertThat(find(fx.userAt("RBV", "whmr", "WH_MANAGER"), pending)).isEmpty();

        // Holding the right at Gahanga while working at Rubavu, where they hold none: listed, opened by switching.
        UUID both = fx.user("whmk", "WH_MANAGER");
        fx.grantAt(both, "SALES", "RBV");
        fx.actAs(both, fx.branch("RBV"));
        var row = register.list(DocumentListService.Query.text(serial(pending)), 10);
        assertThat(row).singleElement().satisfies(r -> {
            assertThat(r.branchCode()).isEqualTo("KGL");
            assertThat(r.openHere()).isFalse();
        });
    }

    @Test
    void filtersNarrowAndNeverWiden() {
        fx.actAs(reader);
        String prefix = serial(pending).substring(0, serial(pending).lastIndexOf('-'));
        var postedOnly = register.list(new DocumentListService.Query(DocumentKind.GRN, "POSTED", fx.kigali(), null,
                null, serial(posted), false, null), 10);
        assertThat(postedOnly).extracting(DocumentRow::id).containsExactly(posted);
        assertThat(register.list(new DocumentListService.Query(DocumentKind.DAO, null, null, null, null,
                serial(posted), false, null), 10)).isEmpty();
        assertThat(register.list(new DocumentListService.Query(null, null, fx.branch("RBV"), null, null,
                serial(posted), false, null), 10)).isEmpty();
        // The reader's % is a character, not a wildcard.
        assertThat(register.list(DocumentListService.Query.text(prefix + "%"), 10)).isEmpty();

        // Raised by me: the raiser's own documents, nobody else's.
        var mine = new DocumentListService.Query(null, null, null, null, null, serial(draft), true, null);
        fx.actAs(flow.raiser());
        assertThat(register.list(mine, 10)).extracting(DocumentRow::id).containsExactly(draft);
        fx.actAs(reader);
        assertThat(register.list(mine, 10)).isEmpty();
    }

    @Test
    void pagesFollowOnFromTheLastDocumentShown() {
        fx.actAs(flow.raiser());
        var mine = new DocumentListService.Query(null, null, null, null, null, null, true, null);
        var first = register.list(mine, 2);
        assertThat(first).hasSize(2);
        var next = register.list(new DocumentListService.Query(null, null, null, null, null, null, true,
                first.get(1).id()), 2);
        assertThat(next).extracting(DocumentRow::id).doesNotContainAnyElementsOf(
                first.stream().map(DocumentRow::id).toList());
        assertThat(next).isNotEmpty();
    }
}
