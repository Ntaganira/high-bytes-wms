package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : DispatchScreensTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Renders the dispatch, delivery note and customer screens in every banner state, and refuses the rest
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.dispatch.DeliveryNoteService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DispatchService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.DispatchFlow;
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
 * Every screen the dispatch module adds is rendered here in the states it
 * appears in, as someone who holds the right (200) and someone who does not
 * (403), and the release banner is asserted in each of its states: a page that
 * fails to render, or says the wrong thing about whether goods may leave, is
 * the failure that matters here.
 */
class DispatchScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReceivingService receiving;
    @Autowired DispatchService dispatch;
    @Autowired DeliveryNoteService notes;

    DispatchFlow flow;
    AppUserDetails raiser;      // raises and prepares the authorization: dispatch.create
    AppUserDetails gate;        // the warehouse: dispatch.post
    AppUserDetails finance;     // manages customers
    AppUserDetails nobody;      // no dispatch or customer right

    UUID draft;
    UUID pending;
    UUID released;
    UUID delivered;
    UUID draftNote;
    UUID postedNote;

    @BeforeEach
    void cast() {
        flow = new DispatchFlow(fx, receiving, dispatch, notes);
        raiser = userDetails.reload(flow.raiser(), fx.kigali()).orElseThrow();
        gate = userDetails.reload(flow.gate, fx.kigali()).orElseThrow();
        finance = userDetails.reload(fx.user("fin", "FINANCE"), fx.kigali()).orElseThrow();
        nobody = userDetails.reload(fx.user("nobody", "SALES"), fx.kigali()).orElseThrow();

        draft = flow.draft(flow.standardForm());
        pending = flow.pending();
        released = flow.released();
        draftNote = flow.raiseNote(released);
        delivered = flow.released();
        postedNote = flow.raiseNote(delivered);
        flow.post(postedNote);
        SecurityContextHolder.clearContext();   // each request signs in as its own user
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    // ---- the authorization, in each banner state ------------------------------------------

    @Test
    void theAuthorizationScreensRenderAndTheBannerSaysWhetherGoodsMayLeave() throws Exception {
        mvc.perform(as(raiser, get("/dispatch")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Delivery Authorizations")))
                .andExpect(content().string(containsString("New authorization")));
        mvc.perform(as(raiser, get("/dispatch").param("status", "RELEASED").param("awaitingMe", "true")))
                .andExpect(status().isOk());
        mvc.perform(as(raiser, get("/dispatch/new")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"lines[0].quantity\"")));

        // BLOCKED: a draft and a pending authorization both say "Do not load" and name the unsigned steps.
        mvc.perform(as(raiser, get("/dispatch/" + draft)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Do not load")))
                .andExpect(content().string(containsString("Submit for signature")))
                .andExpect(content().string(containsString("On hand (base unit)")));
        mvc.perform(as(raiser, get("/dispatch/" + draft + "/edit")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"lines[1].quantity\"")));
        mvc.perform(as(raiser, get("/dispatch/" + pending)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Do not load")))
                .andExpect(content().string(containsString("awaiting signature")))
                .andExpect(content().string(containsString("can sign:")))
                .andExpect(content().string(containsString("Approval chain")));

        // RELEASED: "Released: ready to load", and the warehouse is offered the delivery note.
        mvc.perform(as(gate, get("/dispatch/" + released)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Released: ready to load")))
                .andExpect(content().string(not(containsString("Do not load"))))
                .andExpect(content().string(containsString("Delivery note")));

        // DELIVERED: the delivery note, the gate time and who posted it.
        mvc.perform(as(gate, get("/dispatch/" + delivered)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Delivered")))
                .andExpect(content().string(containsString("posted at the gate by")));
    }

    // ---- the gate ----------------------------------------------------------------------------

    @Test
    void theGateSeesReadyToLoadAndTheNoteScreensRender() throws Exception {
        UUID ready = flow.released();
        String readySerial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", ready)
                .query(String.class).single();
        mvc.perform(as(gate, get("/delivery-notes")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ready to load")))
                .andExpect(content().string(containsString(readySerial)));
        mvc.perform(as(gate, get("/delivery-notes").param("status", "POSTED"))).andExpect(status().isOk());

        // Someone who reads dispatch documents but cannot load does not get the list of what to load.
        mvc.perform(as(raiser, get("/delivery-notes")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Ready to load"))));

        // The form starts from the released authorization, prefilled, banner first, stock beside it.
        mvc.perform(as(gate, get("/delivery-notes/new").param("dao", ready.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Released: ready to load")))
                .andExpect(content().string(containsString("name=\"lines[0].measuredThicknessMm\"")))
                .andExpect(content().string(containsString("Authorized")))
                .andExpect(content().string(containsString("nominal 6.00 mm")));

        // For an authorization that is not released the same form says Do not load and offers no fields.
        mvc.perform(as(gate, get("/delivery-notes/new").param("dao", pending.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Do not load")))
                .andExpect(content().string(containsString("This note cannot be raised")))
                .andExpect(content().string(not(containsString("name=\"vehicleRegistration\""))));
        // ... and for one that already has a live note.
        mvc.perform(as(gate, get("/delivery-notes/new").param("dao", released.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("already has a live delivery note")));

        mvc.perform(as(gate, get("/delivery-notes/" + draftNote)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Confirm release at the gate")))
                .andExpect(content().string(containsString("Released: ready to load")));
        mvc.perform(as(gate, get("/delivery-notes/" + draftNote + "/edit"))).andExpect(status().isOk());
        mvc.perform(as(gate, get("/delivery-notes/" + postedNote)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Left the ledger")))
                .andExpect(content().string(containsString("TT-KGL-")))
                .andExpect(content().string(containsString("Delivered")));
        mvc.perform(as(gate, get("/delivery-notes/" + postedNote + "/edit"))).andExpect(status().is3xxRedirection());
    }

    @Test
    void confirmingReleaseIsRequiredByTheServerAndTheRefusalIsShownInWords() throws Exception {
        // Without the confirmation nothing happens.
        mvc.perform(as(gate, post("/delivery-notes/" + draftNote + "/post").with(csrf())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("not confirmed")));
        assertThat(jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", draftNote)
                .query(String.class).single()).isEqualTo("DRAFT");

        // A short load is refused at the post with the database's reason, on the page, not a stack trace.
        UUID short1 = flow.released();
        var form = flow.loadForm(short1);
        form.getLines().get(0).setQuantity(new java.math.BigDecimal("15"));
        fx.actAs(flow.gate);
        UUID shortNote = notes.create(form);
        SecurityContextHolder.clearContext();
        mvc.perform(as(gate, post("/delivery-notes/" + shortNote + "/post").with(csrf()).param("confirm", "true")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("must equal the authorization exactly")));

        // Confirmed, an exact load leaves the gate.
        mvc.perform(as(gate, post("/delivery-notes/" + draftNote + "/post").with(csrf()).param("confirm", "true")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashSuccess", containsString("Released at the gate")));
        assertThat(jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", draftNote)
                .query(String.class).single()).isEqualTo("POSTED");
    }

    @Test
    void theFormShowsFieldErrorsForAMismatchedLoadAndGlassWithoutThickness() throws Exception {
        UUID ready = flow.released();
        var loadable = flow.loadForm(ready);
        var glassLine = loadable.getLines().get(0).getAuthorizationLineId();
        var siliconeLine = loadable.getLines().get(1).getAuthorizationLineId();
        SecurityContextHolder.clearContext();
        mvc.perform(as(gate, post("/delivery-notes").with(csrf())
                        .param("authorizationId", ready.toString())
                        .param("vehicleRegistration", "RAD 5 B")
                        .param("driverName", "Driver")
                        .param("lines[0].authorizationLineId", glassLine.toString())
                        .param("lines[0].quantity", "15")
                        .param("lines[1].authorizationLineId", siliconeLine.toString())
                        .param("lines[1].quantity", "5")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("must equal the authorization exactly")))
                .andExpect(content().string(containsString("Glass needs its thickness measured at the gate")));
    }

    @Test
    void splittingAndRemovingARowReturnsTheRowsRenumberedByTheServer() throws Exception {
        UUID ready = flow.released();
        var loadable = flow.loadForm(ready);
        var glassLine = loadable.getLines().get(0).getAuthorizationLineId();
        var siliconeLine = loadable.getLines().get(1).getAuthorizationLineId();
        SecurityContextHolder.clearContext();

        mvc.perform(as(gate, post("/delivery-notes/lines/split/0").with(csrf())
                        .param("authorizationId", ready.toString())
                        .param("lines[0].authorizationLineId", glassLine.toString())
                        .param("lines[0].quantity", "20")
                        .param("lines[1].authorizationLineId", siliconeLine.toString())
                        .param("lines[1].quantity", "5")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"dn-lines\"")))
                .andExpect(content().string(containsString("name=\"lines[2].quantity\"")))
                .andExpect(content().string(not(containsString("<html"))));

        // The last part serving an authorization line cannot be removed; a split part can.
        mvc.perform(as(gate, post("/delivery-notes/lines/remove/1").with(csrf())
                        .param("authorizationId", ready.toString())
                        .param("lines[0].authorizationLineId", glassLine.toString())
                        .param("lines[0].quantity", "20")
                        .param("lines[1].authorizationLineId", siliconeLine.toString())
                        .param("lines[1].quantity", "5")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"lines[1].quantity\"")));
        mvc.perform(as(gate, post("/delivery-notes/lines/split/0").with(csrf()))).andExpect(status().is4xxClientError());
        mvc.perform(as(gate, post("/delivery-notes/lines/split/0"))).andExpect(status().isForbidden());
    }

    @Test
    void theAuthorizationFormLinesAreRenumberedByTheServerToo() throws Exception {
        mvc.perform(as(raiser, post("/dispatch/lines/add").with(csrf())
                        .param("lines[0].quantity", "4")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"dao-lines\"")))
                .andExpect(content().string(containsString("name=\"lines[1].quantity\"")));
        mvc.perform(as(raiser, post("/dispatch/lines/remove/0").with(csrf())
                        .param("lines[0].quantity", "4")
                        .param("lines[1].quantity", "9")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"9")));
    }

    @Test
    void theDocumentAddressSendsEachDocumentToItsOwnScreen() throws Exception {
        mvc.perform(as(gate, get("/documents/" + released)))
                .andExpect(header().string("Location", "/dispatch/" + released));
        mvc.perform(as(gate, get("/documents/" + draftNote)))
                .andExpect(header().string("Location", "/delivery-notes/" + draftNote));
        UUID ticket = jdbc.sql("SELECT document_id FROM transaction_ticket WHERE source_document_id = :id")
                .param("id", postedNote).query(UUID.class).single();
        mvc.perform(as(gate, get("/documents/" + ticket)))
                .andExpect(header().string("Location", "/delivery-notes/" + postedNote));
    }

    // ---- 403 for those who do not hold the right -----------------------------------------------

    @Test
    void thoseWithoutTheRightsAreRefused() throws Exception {
        mvc.perform(as(nobody, get("/dispatch"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/dispatch/new"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/dispatch/" + released))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/delivery-notes"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/delivery-notes/" + draftNote))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/customers"))).andExpect(status().isForbidden());

        // The warehouse loads but does not authorize; the authorizer does not release at the gate.
        mvc.perform(as(gate, get("/dispatch/new"))).andExpect(status().isForbidden());
        mvc.perform(as(gate, post("/dispatch/" + draft + "/submit").with(csrf())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
        mvc.perform(as(raiser, get("/delivery-notes/new").param("dao", released.toString())))
                .andExpect(status().isForbidden());
        mvc.perform(as(raiser, post("/delivery-notes/" + draftNote + "/post").with(csrf()).param("confirm", "true")))
                .andExpect(status().isForbidden());
        mvc.perform(as(raiser, post("/delivery-notes/" + draftNote + "/cancel").with(csrf()).param("reason", "x")))
                .andExpect(status().isForbidden());
        // The warehouse does not manage customers: that is Finance's.
        mvc.perform(as(gate, get("/customers"))).andExpect(status().isForbidden());
        mvc.perform(as(gate, get("/customers/new"))).andExpect(status().isForbidden());
    }

    // ---- customers ------------------------------------------------------------------------------

    @Test
    void financeManagesCustomersAndABlockNeedsAReasonAndHidesTheCustomerFromThePicker() throws Exception {
        UUID customer = flow.customer;
        mvc.perform(as(finance, get("/customers"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Customers")));
        mvc.perform(as(finance, get("/customers").param("q", "flow"))).andExpect(status().isOk());
        mvc.perform(as(finance, get("/customers/new"))).andExpect(status().isOk());
        mvc.perform(as(finance, get("/customers/" + customer)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Delivery block")));
        mvc.perform(as(finance, get("/customers/" + customer + "/edit"))).andExpect(status().isOk());

        mvc.perform(as(finance, post("/customers/" + customer + "/block").with(csrf())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("needs a reason")));
        mvc.perform(as(finance, post("/customers/" + customer + "/block").with(csrf()).param("reason", "Unpaid invoices")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        // A blocked customer is not offered on the authorization form.
        String name = jdbc.sql("SELECT code FROM customer WHERE id = :id").param("id", customer).query(String.class).single();
        mvc.perform(as(raiser, get("/dispatch/new")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(name))));
        mvc.perform(as(finance, post("/customers/" + customer + "/unblock").with(csrf())))
                .andExpect(flash().attributeExists("flashSuccess"));
        mvc.perform(as(raiser, get("/dispatch/new")))
                .andExpect(content().string(containsString(name)));
    }
}
