package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : ScreensTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Renders the goods received and supplier screens for those who may see them and refuses the rest
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.approval.ApprovalQueueService;
import heritier.ntaganira.highbytes.wms.approval.AwaitingSignature;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.GrnFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A page that fails to render is a Thymeleaf error nobody sees until a user
 * opens it. Every screen the receiving module adds is rendered here in the
 * states it appears in, as someone who holds the right (200) and someone who
 * does not (403).
 */
class ScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReceivingService receiving;
    @Autowired ApprovalQueueService approvals;

    GrnFlow flow;
    AppUserDetails storekeeper;   // raises and prepares: receiving.view + receiving.create
    AppUserDetails finance;       // posts, and manages suppliers
    AppUserDetails nobody;        // holds no receiving or supplier right at all

    UUID draft;
    UUID pending;
    UUID approved;
    UUID posted;

    @BeforeEach
    void cast() {
        flow = new GrnFlow(fx, receiving);
        storekeeper = userDetails.reload(flow.raiser(), fx.kigali()).orElseThrow();
        finance = userDetails.reload(flow.poster, fx.kigali()).orElseThrow();
        nobody = userDetails.reload(fx.user("nobody", "SALES"), fx.kigali()).orElseThrow();

        draft = flow.draft();
        pending = flow.pending();
        approved = flow.approved();
        posted = flow.approved();
        flow.post(posted);
        SecurityContextHolder.clearContext();   // each request signs in as its own user
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    // ---- 200 for those who hold the right ------------------------------------

    @Test
    void theReceivingScreensRenderForTheStorekeeperInEveryState() throws Exception {
        mvc.perform(as(storekeeper, get("/receiving")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Goods Received")))
                .andExpect(content().string(containsString("New receipt")));
        mvc.perform(as(storekeeper, get("/receiving").param("status", "PENDING").param("awaitingMe", "true")))
                .andExpect(status().isOk());
        mvc.perform(as(storekeeper, get("/receiving/new")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"lines[0].quantity\"")));
        mvc.perform(as(storekeeper, get("/receiving/" + draft)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Submit for approval")))
                .andExpect(content().string(containsString("Landed cost allocation")));
        mvc.perform(as(storekeeper, get("/receiving/" + draft + "/edit")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"lines[1].quantity\"")));
        mvc.perform(as(storekeeper, get("/receiving/" + pending)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approval chain")))
                .andExpect(content().string(not(containsString("Submit for approval"))));
        mvc.perform(as(storekeeper, get("/receiving/" + approved)))
                .andExpect(status().isOk());
        mvc.perform(as(storekeeper, get("/receiving/" + posted)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Posted to the ledger")))
                .andExpect(content().string(containsString("TT-KGL-")));
        // The storekeeper edited nothing after posting: no edit offered, and the edit page bounces.
        mvc.perform(as(storekeeper, get("/receiving/" + posted + "/edit")))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void financeSeesTheSupplierScreensAndThePostButtonOnAnApprovedReceipt() throws Exception {
        UUID supplier = flow.supplier;
        mvc.perform(as(finance, get("/suppliers"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Suppliers")));
        mvc.perform(as(finance, get("/suppliers").param("q", "flow"))).andExpect(status().isOk());
        mvc.perform(as(finance, get("/suppliers/new"))).andExpect(status().isOk());
        mvc.perform(as(finance, get("/suppliers/" + supplier))).andExpect(status().isOk());
        mvc.perform(as(finance, get("/suppliers/" + supplier + "/edit"))).andExpect(status().isOk());

        mvc.perform(as(finance, get("/receiving"))).andExpect(status().isOk());
        mvc.perform(as(finance, get("/receiving/" + approved)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Post to the ledger")));
        mvc.perform(as(finance, get("/receiving/" + pending)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Post to the ledger"))));
    }

    @Test
    void theDashboardListsAPendingReceiptForTheNextSignerAndNotForItsRaiser() throws Exception {
        // The page shows only the five that have waited longest, and the shared test database holds many, so
        // the queue is asked directly: it is the same service the page reads.
        AppUserDetails nextSigner = userDetails.reload(flow.stepUsers.get(1), fx.kigali()).orElseThrow();
        mvc.perform(as(nextSigner, get("/"))).andExpect(status().isOk());
        mvc.perform(as(storekeeper, get("/"))).andExpect(status().isOk());

        fx.actAs(nextSigner.id());
        assertThat(approvals.awaiting(fx.kigali(), null, 100_000))
                .extracting(AwaitingSignature::documentId).contains(pending).doesNotContain(draft, approved, posted);

        // The raiser signed step 1; every later step is for someone else, and a draft awaits no one.
        fx.actAs(storekeeper.id());
        assertThat(approvals.awaiting(null, null, 100_000))
                .extracting(AwaitingSignature::documentId).doesNotContain(pending, draft, approved, posted);
    }

    @Test
    void theDashboardRedirectsADocumentAddressToItsScreen() throws Exception {
        mvc.perform(as(storekeeper, get("/documents/" + draft)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/receiving/" + draft));
        UUID ticket = jdbc.sql("SELECT document_id FROM transaction_ticket WHERE source_document_id = :id")
                .param("id", posted).query(UUID.class).single();
        mvc.perform(as(storekeeper, get("/documents/" + ticket)))
                .andExpect(header().string("Location", "/receiving/" + posted));
        // Finance reads tickets: the ticket opens on its own page.
        mvc.perform(as(finance, get("/documents/" + ticket)))
                .andExpect(header().string("Location", "/tickets/" + ticket));
    }

    // ---- 403 for those who do not ----------------------------------------------

    @Test
    void thoseWithoutTheRightsAreRefused() throws Exception {
        mvc.perform(as(nobody, get("/receiving"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/receiving/new"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/receiving/" + draft))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/suppliers"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/suppliers/new"))).andExpect(status().isForbidden());

        // The store reads receipts but does not manage suppliers, and Finance posts but does not raise.
        mvc.perform(as(storekeeper, get("/suppliers"))).andExpect(status().isForbidden());
        mvc.perform(as(storekeeper, get("/suppliers/new"))).andExpect(status().isForbidden());
        mvc.perform(as(finance, get("/receiving/new"))).andExpect(status().isForbidden());
        mvc.perform(as(finance, get("/receiving/" + draft + "/edit"))).andExpect(status().isForbidden());
        mvc.perform(as(finance, post("/receiving/" + draft + "/submit").with(csrf())))
                .andExpect(status().isForbidden());
        mvc.perform(as(storekeeper, post("/receiving/" + approved + "/post").with(csrf())))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRefusalRaisedAtCommitShowsItsReasonLeavesOnlyARejectRowAndNoSuccessRow() throws Exception {
        // A deferred control, as the "posted note must have moved stock" trigger is: it speaks only at COMMIT,
        // after the service has returned. Here it refuses any movement of the glass item.
        jdbc.sql("""
                CREATE FUNCTION test_commit_refusal() RETURNS trigger AS $body$
                BEGIN
                    RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = 'Test control - refused at commit.';
                END $body$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("CREATE CONSTRAINT TRIGGER test_commit_refusal AFTER INSERT ON stock_movement "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.item_id = '" + flow.glass + "') "
                + "EXECUTE FUNCTION test_commit_refusal()").update();
        try {
            mvc.perform(as(finance, post("/receiving/" + approved + "/post").with(csrf())))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attribute("flashError", containsString("refused at commit")));
        } finally {
            jdbc.sql("DROP TRIGGER test_commit_refusal ON stock_movement").update();
            jdbc.sql("DROP FUNCTION test_commit_refusal()").update();
        }

        assertThat(jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", approved)
                .query(String.class).single()).isEqualTo("APPROVED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'POST'")
                .param("id", approved).query(Long.class).single())
                .as("the success row rolled back with the change").isZero();
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_log WHERE entity_id = :id AND action = 'REJECT'
                   AND reason LIKE '%refused at commit%' AND actor_user_id = :actor
                """).param("id", approved).param("actor", finance.id()).query(Long.class).single())
                .as("the refusal is on the record").isEqualTo(1L);
    }

    @Test
    void aCommitTimeRefusalOnTheCreateAndEditFormsIsShownAsAFormError() throws Exception {
        // A deferred control, as the GRN's own "posted note must have moved stock" is: it speaks only at COMMIT.
        jdbc.sql("""
                CREATE FUNCTION test_form_commit_refusal() RETURNS trigger AS $body$
                BEGIN
                    RAISE EXCEPTION USING ERRCODE = '23Z02', MESSAGE = 'Test control - the receipt form was refused at commit.';
                END $body$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("CREATE CONSTRAINT TRIGGER test_form_commit_refusal AFTER INSERT OR UPDATE ON goods_received_note "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.supplier_id = '" + flow.supplier + "') "
                + "EXECUTE FUNCTION test_form_commit_refusal()").update();
        try {
            // Create: the page comes back with the reason, not a 500.
            mvc.perform(as(storekeeper, post("/receiving").with(csrf())
                            .param("supplierId", flow.supplier.toString())
                            .param("locationId", flow.location.toString())
                            .param("currencyCode", "RWF")
                            .param("exchangeRate", "1")
                            .param("lines[0].itemId", flow.silicone.toString())
                            .param("lines[0].uomId", flow.pieces.toString())
                            .param("lines[0].quantity", "5")
                            .param("lines[0].unitPrice", "3000")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("refused at commit")));

            // Edit: the same.
            mvc.perform(as(storekeeper, post("/receiving/" + draft).with(csrf())
                            .param("version", "0")
                            .param("supplierId", flow.supplier.toString())
                            .param("locationId", flow.location.toString())
                            .param("currencyCode", "RWF")
                            .param("exchangeRate", "1")
                            .param("lines[0].itemId", flow.silicone.toString())
                            .param("lines[0].uomId", flow.pieces.toString())
                            .param("lines[0].quantity", "5")
                            .param("lines[0].unitPrice", "3000")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("refused at commit")));
        } finally {
            jdbc.sql("DROP TRIGGER test_form_commit_refusal ON goods_received_note").update();
            jdbc.sql("DROP FUNCTION test_form_commit_refusal()").update();
        }
    }

    @Test
    void aRecordAtAnotherBranchIsRefusedEvenToSomeoneWhoHoldsTheRightThere() throws Exception {
        // Full rights at Rubavu, none at Gahanga: the Gahanga note is out of reach by its address.
        AppUserDetails rubavu = userDetails.reload(
                fx.userAt("RBV", "rbv", flow.chainRoles.get(0)), fx.branch("RBV")).orElseThrow();
        mvc.perform(as(rubavu, get("/receiving"))).andExpect(status().isOk());
        mvc.perform(as(rubavu, get("/receiving/" + draft))).andExpect(status().isForbidden());
        mvc.perform(as(rubavu, get("/receiving/" + draft + "/edit"))).andExpect(status().isForbidden());
        // Cancelling needs the right at the note's own branch, so a Rubavu right does not reach it.
        mvc.perform(as(rubavu, post("/receiving/" + draft + "/cancel").with(csrf()).param("reason", "x")))
                .andExpect(status().isForbidden());
    }

    // ---- line rows by htmx --------------------------------------------------------

    @Test
    void addingAndRemovingARowReturnsTheRowsRenumberedByTheServer() throws Exception {
        mvc.perform(as(storekeeper, post("/receiving/lines/add").with(csrf())
                        .param("lines[0].quantity", "4")
                        .param("lines[1].quantity", "7")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"grn-lines\"")))
                .andExpect(content().string(containsString("name=\"lines[2].quantity\"")))
                .andExpect(content().string(not(containsString("<html"))));

        mvc.perform(as(storekeeper, post("/receiving/lines/remove/0").with(csrf())
                        .param("lines[0].quantity", "4")
                        .param("lines[1].quantity", "7")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"lines[0].quantity\"")))
                .andExpect(content().string(containsString("value=\"7")))
                .andExpect(content().string(not(containsString("name=\"lines[1].quantity\""))));

        mvc.perform(as(storekeeper, post("/receiving/lines/refresh").with(csrf())
                        .param("locationId", flow.location.toString())
                        .param("lines[0].storageBinId", UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"grn-lines\"")));

        // No token, no change: the endpoint is not exempt from CSRF.
        mvc.perform(as(storekeeper, post("/receiving/lines/add"))).andExpect(status().isForbidden());
    }

    // ---- forms: field errors before the database's refusal ----------------------------

    @Test
    void theFormShowsAFieldErrorForGlassWithoutItsThicknessAndForAnRwfRateThatIsNotOne() throws Exception {
        mvc.perform(as(storekeeper, post("/receiving").with(csrf())
                        .param("supplierId", flow.supplier.toString())
                        .param("locationId", flow.location.toString())
                        .param("currencyCode", "RWF")
                        .param("exchangeRate", "1350")
                        .param("lines[0].itemId", flow.glass.toString())
                        .param("lines[0].uomId", flow.box.toString())
                        .param("lines[0].quantity", "4")
                        .param("lines[0].unitPrice", "100000")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Glass needs its thickness measured")))
                .andExpect(content().string(containsString("exchange rate of 1")));
    }

    @Test
    void aValidFormRaisesTheDraftAndTheLifecycleButtonsExplainRefusalsInWords() throws Exception {
        var created = mvc.perform(as(storekeeper, post("/receiving").with(csrf())
                        .param("supplierId", flow.supplier.toString())
                        .param("locationId", flow.location.toString())
                        .param("currencyCode", "RWF")
                        .param("exchangeRate", "1")
                        .param("lines[0].itemId", flow.silicone.toString())
                        .param("lines[0].uomId", flow.pieces.toString())
                        .param("lines[0].quantity", "5")
                        .param("lines[0].unitPrice", "3000")))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = created.getResponse().getRedirectedUrl();
        org.assertj.core.api.Assertions.assertThat(location).startsWith("/receiving/");

        // Submitting signs step 1; the same person then tries the next step and is told why not.
        mvc.perform(as(storekeeper, post(location + "/submit").with(csrf())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));
        if (flow.chainRoles.size() > 1) {
            AppUserDetails both = userDetails.reload(
                    fx.user("both", flow.chainRoles.get(0), flow.chainRoles.get(1)), fx.kigali()).orElseThrow();
            // A raiser holding step 2's role is refused by the rule, with its reason, not a stack trace.
            fx.actAs(both.id());
            UUID own = receiving.create(flow.standardForm());
            receiving.submit(own);
            SecurityContextHolder.clearContext();
            mvc.perform(as(both, post("/receiving/" + own + "/approve").with(csrf())))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attribute("flashError", containsString("raised")));
        }
        mvc.perform(as(finance, post(location + "/post").with(csrf())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("PENDING")));
    }
}
