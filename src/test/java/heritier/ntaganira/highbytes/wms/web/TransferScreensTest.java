package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : TransferScreensTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Renders the transfer and receipt screens in every state and refuses those without the right
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.inventory.transfer.ReceiptService;
import heritier.ntaganira.highbytes.wms.inventory.transfer.TransferService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import heritier.ntaganira.highbytes.wms.support.TransferFlow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
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
 * Every screen the transfer module adds is rendered here in the states it
 * appears in, as someone who holds the right (200) and someone who does not
 * (403), and the banner is asserted in each of its states.
 */
class TransferScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReceivingService receiving;
    @Autowired TransferService transfers;
    @Autowired ReceiptService receipts;

    TransferFlow flow;
    AppUserDetails raiser;       // raises and prepares at Gahanga: transfer.create
    AppUserDetails dispatcher;   // the source gate
    AppUserDetails receiver;     // the destination, at Rubavu
    AppUserDetails nobody;

    UUID draft;
    UUID pending;
    UUID approved;
    UUID dispatched;
    UUID short1;            // received short: in transit
    UUID full;              // received in full
    UUID draftReceipt;      // a draft receipt against `approvedLater`
    UUID toReceive;         // dispatched, no receipt yet

    @BeforeEach
    void cast() {
        flow = new TransferFlow(fx, receiving, transfers, receipts);
        raiser = userDetails.reload(flow.raiser(), fx.kigali()).orElseThrow();
        dispatcher = userDetails.reload(flow.dispatcher, fx.kigali()).orElseThrow();
        receiver = userDetails.reload(flow.receiver, flow.rubavu).orElseThrow();
        nobody = userDetails.reload(fx.user("nobody", "SALES"), fx.kigali()).orElseThrow();

        // More stock on the shelf than the starting receipt brings: several consignments are sent from it.
        var more = flow.grn.siliconeOnlyForm();
        more.getLines().get(0).setQuantity(new BigDecimal("30"));
        var extraGlass = flow.grn.siliconeOnlyForm().getLines().get(0);
        extraGlass.setItemId(flow.grn.glass);
        extraGlass.setUomId(fx.uom("SHEET"));
        extraGlass.setQuantity(new BigDecimal("100"));
        extraGlass.setStorageBinId(flow.grn.bin);
        extraGlass.setMeasuredThicknessMm(new BigDecimal("6.00"));
        more.getLines().add(extraGlass);
        flow.grn.post(flow.grn.approved(more));

        draft = flow.draft(flow.standardForm());
        pending = flow.pending();
        approved = flow.approved();
        dispatched = flow.dispatched();

        short1 = flow.dispatched();
        var shortForm = flow.receiptForm(short1);
        shortForm.getLines().get(0).setQuantity(new BigDecimal("18"));
        flow.postReceipt(flow.raiseReceipt(shortForm));

        full = flow.dispatched();
        flow.postReceipt(flow.raiseReceipt(flow.receiptForm(full)));

        toReceive = flow.dispatched();
        draftReceipt = flow.raiseReceipt(flow.receiptForm(toReceive));
        SecurityContextHolder.clearContext();   // each request signs in as its own user
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    @Test
    void theBannerAndChipSayWhereTheConsignmentIs() throws Exception {
        mvc.perform(as(raiser, get("/transfers")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("New transfer")));
        mvc.perform(as(raiser, get("/transfers/new")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"lines[0].quantity\"")));

        mvc.perform(as(raiser, get("/transfers/" + draft)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Do not dispatch")))
                .andExpect(content().string(containsString("Submit for signature")));
        mvc.perform(as(raiser, get("/transfers/" + draft + "/edit")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"lines[1].quantity\"")));
        mvc.perform(as(raiser, get("/transfers/" + pending)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Do not dispatch")))
                .andExpect(content().string(containsString("awaiting signature")))
                .andExpect(content().string(containsString("can sign:")));
        mvc.perform(as(dispatcher, get("/transfers/" + approved)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approved: ready to dispatch")))
                .andExpect(content().string(containsString("Dispatch at the gate")))
                .andExpect(content().string(not(containsString("Do not dispatch"))));
        mvc.perform(as(dispatcher, get("/transfers/" + dispatched)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dispatched: in transit")))
                .andExpect(content().string(containsString("status-dispatched")))
                .andExpect(content().string(containsString("In the ledger")));

        // Received short: the shortfall is stated plainly, line by line.
        mvc.perform(as(raiser, get("/transfers/" + short1)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Received, with stock left in transit")))
                .andExpect(content().string(containsString("status-in_transit")))
                .andExpect(content().string(containsString("left in transit: awaiting a loss or damage report")));
        mvc.perform(as(raiser, get("/transfers/" + full)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Received in full")))
                .andExpect(content().string(containsString("status-received")))
                .andExpect(content().string(not(containsString("left in transit: awaiting"))));
    }

    @Test
    void theGateAndTheDestinationEachSeeTheirOwnWorklist() throws Exception {
        String approvedSerial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", approved)
                .query(String.class).single();
        String toReceiveSerial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", dispatched)
                .query(String.class).single();
        mvc.perform(as(dispatcher, get("/transfers")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ready to dispatch")))
                .andExpect(content().string(containsString(approvedSerial)));
        mvc.perform(as(raiser, get("/transfers").param("status", "IN_TRANSIT").param("awaitingMe", "true")))
                .andExpect(status().isOk());
        // An approver, who neither loads nor unloads, gets neither worklist.
        var approver = userDetails.reload(flow.stepUsers.get(1), fx.kigali()).orElseThrow();
        mvc.perform(as(approver, get("/transfers")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Ready to dispatch"))))
                .andExpect(content().string(not(containsString("Awaiting receipt"))));
        // Rubavu, the destination, sees what is on its way.
        mvc.perform(as(receiver, get("/transfers")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Awaiting receipt")))
                .andExpect(content().string(containsString(toReceiveSerial)));
        // The destination can open the transfer it must receive, though it belongs to the source branch.
        mvc.perform(as(receiver, get("/transfers/" + dispatched)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Receive")));
    }

    @Test
    void theDispatchScreenShowsStockAndAsksForConfirmation() throws Exception {
        mvc.perform(as(dispatcher, get("/transfers/" + approved + "/dispatch")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approved: ready to dispatch")))
                .andExpect(content().string(containsString("What leaves")))
                .andExpect(content().string(containsString("On hand (base unit)")))
                .andExpect(content().string(containsString("Confirm dispatch at the gate")));

        // Without the confirmation nothing happens, and the screen says so.
        mvc.perform(as(dispatcher, post("/transfers/" + approved + "/dispatch").with(csrf())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("not confirmed")));
        assertThat(jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", approved)
                .query(String.class).single()).isEqualTo("APPROVED");

        // The raiser is a Warehouse Manager and holds the dispatch right, but is refused with the database's reason, on the page.
        mvc.perform(as(raiser, post("/transfers/" + approved + "/dispatch").with(csrf()).param("confirm", "true")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("raised")));
    }

    @Test
    void confirmedDispatchAndReceiptMoveTheStockAndRefusalsAreShownInWords() throws Exception {
        mvc.perform(as(dispatcher, post("/transfers/" + approved + "/dispatch").with(csrf()).param("confirm", "true")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashSuccess", containsString("Dispatched into transit under ticket")));
        assertThat(jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", approved)
                .query(String.class).single()).isEqualTo("POSTED");

        // A second dispatch is refused in words.
        mvc.perform(as(dispatcher, post("/transfers/" + approved + "/dispatch").with(csrf()).param("confirm", "true")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));

        // Receipt: form prefilled, confirmation required, then posted.
        mvc.perform(as(receiver, get("/transfers/receipts/new").param("transfer", approved.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("What arrived")))
                .andExpect(content().string(containsString("Dispatched: in transit")))
                .andExpect(content().string(containsString("name=\"lines[0].quantity\"")));
        mvc.perform(as(receiver, get("/transfers/receipts/" + draftReceipt)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Confirm receipt")));
        mvc.perform(as(receiver, get("/transfers/receipts/" + draftReceipt + "/edit"))).andExpect(status().isOk());
        mvc.perform(as(receiver, post("/transfers/receipts/" + draftReceipt + "/post").with(csrf())))
                .andExpect(flash().attribute("flashError", containsString("not confirmed")));
        mvc.perform(as(receiver, post("/transfers/receipts/" + draftReceipt + "/post").with(csrf()).param("confirm", "true")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashSuccess", containsString("Receipt confirmed")));
        mvc.perform(as(receiver, get("/transfers/receipts/" + draftReceipt)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("In the ledger")));
    }

    @Test
    void aReceiptFormRefusesMoreThanWasSentAndNothingAtAll() throws Exception {
        UUID trf = flow.dispatched();
        fx.actAs(flow.receiver, flow.rubavu);
        var form = receipts.prefill(trf);
        UUID glassLine = form.getLines().get(0).getTransferLineId();
        UUID siliconeLine = form.getLines().get(1).getTransferLineId();
        SecurityContextHolder.clearContext();

        mvc.perform(as(receiver, post("/transfers/receipts").with(csrf())
                        .param("transferId", trf.toString())
                        .param("lines[0].transferLineId", glassLine.toString())
                        .param("lines[0].quantity", "25")
                        .param("lines[1].transferLineId", siliconeLine.toString())
                        .param("lines[1].quantity", "5")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("More cannot arrive than was sent")));

        mvc.perform(as(receiver, post("/transfers/receipts").with(csrf())
                        .param("transferId", trf.toString())
                        .param("lines[0].transferLineId", glassLine.toString())
                        .param("lines[0].quantity", "0")
                        .param("lines[1].transferLineId", siliconeLine.toString())
                        .param("lines[1].quantity", "0")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("If nothing arrived there is nothing to receive")));
    }

    @Test
    void lineRowsAreAddedAndRemovedByTheServerAndTheDocumentAddressRoutes() throws Exception {
        mvc.perform(as(raiser, post("/transfers/lines/add").with(csrf()).param("lines[0].quantity", "4")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"trf-lines\"")))
                .andExpect(content().string(containsString("name=\"lines[1].quantity\"")))
                .andExpect(content().string(not(containsString("<html"))));
        mvc.perform(as(raiser, post("/transfers/lines/remove/0").with(csrf())
                        .param("lines[0].quantity", "4").param("lines[1].quantity", "9")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"9")));

        mvc.perform(as(dispatcher, get("/documents/" + approved)))
                .andExpect(header().string("Location", "/transfers/" + approved));
        UUID receipt = draftReceipt;
        mvc.perform(as(receiver, get("/documents/" + receipt)))
                .andExpect(header().string("Location", "/transfers/receipts/" + receipt));
        UUID ticket = jdbc.sql("SELECT document_id FROM transaction_ticket WHERE source_document_id = :id AND movement_type = 'TRANSFER_OUT'")
                .param("id", dispatched).query(UUID.class).single();
        mvc.perform(as(dispatcher, get("/documents/" + ticket)))
                .andExpect(header().string("Location", "/transfers/" + dispatched));
    }

    @Test
    void aDestinationViewerSeesTheTransferButIsOfferedNoSourceSideAction() throws Exception {
        for (UUID trf : new UUID[]{draft, pending, approved, dispatched}) {
            mvc.perform(as(receiver, get("/transfers/" + trf)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("Dispatch at the gate"))))
                    .andExpect(content().string(not(containsString("Submit for signature"))))
                    .andExpect(content().string(not(containsString("Cancel this transfer"))))
                    .andExpect(content().string(not(containsString("Edit draft"))))
                    .andExpect(content().string(not(containsString("/approve"))))
                    .andExpect(content().string(not(containsString("/reject"))));
        }
        // What the destination can do is receive what has been dispatched to it.
        mvc.perform(as(receiver, get("/transfers/" + dispatched)))
                .andExpect(content().string(containsString("Receive")));
        // The source-side screens and actions stay closed to it.
        mvc.perform(as(receiver, get("/transfers/" + approved + "/dispatch"))).andExpect(status().isOk());
        mvc.perform(as(receiver, post("/transfers/" + approved + "/dispatch").with(csrf()).param("confirm", "true")))
                .andExpect(status().isForbidden());        // its right at Rubavu does not reach the Gahanga gate
        assertThat(jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", approved)
                .query(String.class).single()).isEqualTo("APPROVED");
    }

    @Test
    void thoseWithoutTheRightsAreRefused() throws Exception {
        mvc.perform(as(nobody, get("/transfers"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/transfers/" + approved))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/transfers/new"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/transfers/receipts/" + draftReceipt))).andExpect(status().isForbidden());

        // The warehouse loads and unloads but does not raise transfers.
        mvc.perform(as(dispatcher, get("/transfers/new"))).andExpect(status().isForbidden());
        // Whoever raised the transfer is told, on the dispatch screen, why they cannot let it out; no form is offered.
        mvc.perform(as(raiser, get("/transfers/" + approved + "/dispatch")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("cannot post it")))
                .andExpect(content().string(not(containsString("Confirm dispatch at the gate"))));
        // A user without the right at all is refused.
        mvc.perform(as(nobody, post("/transfers/" + approved + "/dispatch").with(csrf()).param("confirm", "true")))
                .andExpect(status().isForbidden());
        mvc.perform(as(dispatcher, get("/transfers/receipts/new").param("transfer", dispatched.toString())))
                .andExpect(status().isForbidden());
        // The destination branch reads the source's transfer, but a Rubavu right does not cancel it.
        mvc.perform(as(receiver, post("/transfers/" + approved + "/cancel").with(csrf()).param("reason", "x")))
                .andExpect(status().isForbidden());
    }
}
