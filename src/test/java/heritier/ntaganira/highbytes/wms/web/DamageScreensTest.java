package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : DamageScreensTest.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Renders the return and damage screens for every kind and state and refuses those without the right
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.damage.DamageService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DeliveryNoteService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DispatchService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.inventory.transfer.ReceiptService;
import heritier.ntaganira.highbytes.wms.inventory.transfer.TransferService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.DamageFlow;
import heritier.ntaganira.highbytes.wms.support.DispatchFlow;
import heritier.ntaganira.highbytes.wms.support.GrnFlow;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every screen the module adds is rendered here in the states it appears in, as
 * someone who holds the right (200) and someone who does not (403), and the
 * banner is asserted in each of its states.
 */
class DamageScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReceivingService receiving;
    @Autowired TransferService transfers;
    @Autowired ReceiptService receipts;
    @Autowired DispatchService dispatch;
    @Autowired DeliveryNoteService notes;
    @Autowired DamageService reports;

    DamageFlow dmg;
    TransferFlow tf;
    DispatchFlow df;
    GrnFlow grn;

    AppUserDetails raiser;      // Warehouse Manager: damage.create
    AppUserDetails verifier;    // Internal Controller: damage.verify
    AppUserDetails finance;     // damage.post
    AppUserDetails nobody;      // holds no damage right

    UUID draft;
    UUID pending;
    UUID approved;
    UUID posted;
    UUID cancelled;
    UUID toLose;        // dispatched, stock still in transit
    UUID lost;          // dispatched, written off by a posted loss
    UUID lossDraft;     // a draft loss against toLose
    UUID note;          // a delivery note with a posted return
    UUID returnPosted;
    UUID returnDraft;   // a draft return against `note`
    UUID releaseDraft;

    @BeforeEach
    void cast() {
        dmg = new DamageFlow(fx, reports);
        tf = new TransferFlow(fx, receiving, transfers, receipts);
        df = new DispatchFlow(fx, receiving, dispatch, notes);
        grn = new GrnFlow(fx, receiving);
        grn.post(grn.approved());
        raiser = userDetails.reload(dmg.raiser(), fx.kigali()).orElseThrow();
        verifier = userDetails.reload(dmg.stepUsers.get(1), fx.kigali()).orElseThrow();
        finance = userDetails.reload(dmg.poster, fx.kigali()).orElseThrow();
        nobody = userDetails.reload(fx.user("nobody", "SALES"), fx.kigali()).orElseThrow();

        draft = dmg.draft(dmg.writeOff(grn.glass, "SHEET", "5", grn.bin));
        pending = dmg.pending(dmg.writeOff(grn.glass, "SHEET", "4", grn.bin));
        approved = dmg.approved(dmg.writeOff(grn.glass, "SHEET", "3", grn.bin));
        posted = dmg.approved(dmg.writeOff(grn.glass, "SHEET", "2", grn.bin));
        dmg.post(posted);
        cancelled = dmg.draft(dmg.writeOff(grn.glass, "SHEET", "1", grn.bin));
        fx.actAs(dmg.raiser());
        reports.cancel(cancelled, "Raised against the wrong item");

        toLose = tf.dispatched();
        lost = tf.dispatched();
        fx.actAs(dmg.raiser());
        var whole = reports.prefillLoss(lost);
        whole.setReason("The lorry never reached Rubavu");
        UUID lossPosted = dmg.approved(whole);
        dmg.post(lossPosted);
        fx.actAs(dmg.raiser());
        var some = reports.prefillLoss(toLose);
        some.setReason("Two sheets were missing on the way");
        lossDraft = dmg.draft(some);

        UUID delivered = df.raiseNote(df.released());
        df.post(delivered);
        note = delivered;
        fx.actAs(dmg.raiser());
        var back = reports.prefillReturn(note);
        back.getLines().get(0).setQuantity(new BigDecimal("8"));
        back.getLines().get(1).setQuantity(BigDecimal.ZERO);
        back.setReason("Eight sheets came back scratched");
        fx.actAs(dmg.raiser());
        returnPosted = dmg.approved(back);
        dmg.post(returnPosted);
        fx.actAs(dmg.raiser());
        var again = reports.prefillReturn(note);
        again.setReason("The rest of the order came back");
        returnDraft = dmg.draft(again);

        releaseDraft = dmg.draft(dmg.release(df.grn.glass, "SHEET", "3"));
        SecurityContextHolder.clearContext();   // each request signs in as its own user
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    // ---- lists and worklists ---------------------------------------------------------------------

    @Test
    void theListFiltersByKindAndStateAndLeadsToTheWorklists() throws Exception {
        mvc.perform(as(raiser, get("/damage")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("New report")))
                .andExpect(content().string(containsString("Left in transit")))
                .andExpect(content().string(containsString("Quarantine stock")))
                .andExpect(content().string(containsString(serialOf(draft))))
                .andExpect(content().string(containsString(serialOf(returnPosted))));

        mvc.perform(as(raiser, get("/damage").param("kind", "CUSTOMER_RETURN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(serialOf(returnPosted))))
                .andExpect(content().string(not(containsString(serialOf(draft)))));
        mvc.perform(as(raiser, get("/damage").param("status", "CANCELLED")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(serialOf(cancelled))))
                .andExpect(content().string(not(containsString(serialOf(draft)))));

        // Finance reads the list but cannot raise a report: no button.
        mvc.perform(as(finance, get("/damage")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("New report"))));
        mvc.perform(as(nobody, get("/damage"))).andExpect(status().isForbidden());
    }

    @Test
    void theLeftInTransitWorklistNamesTheTransfersAndLinksToRaiseTheLoss() throws Exception {
        mvc.perform(as(raiser, get("/damage/in-transit")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(transferSerial(toLose))))
                .andExpect(content().string(not(containsString(transferSerial(lost)))))
                .andExpect(content().string(containsString("kind=TRANSIT_LOSS")))
                .andExpect(content().string(containsString("transfer=" + toLose)));
        mvc.perform(as(finance, get("/damage/in-transit")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(transferSerial(toLose))))
                .andExpect(content().string(not(containsString("kind=TRANSIT_LOSS"))));
        mvc.perform(as(nobody, get("/damage/in-transit"))).andExpect(status().isForbidden());
    }

    @Test
    void theQuarantineWorklistShowsWhatIsHeldAndLinksToReleaseOrWriteItOff() throws Exception {
        mvc.perform(as(raiser, get("/damage/quarantine")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(itemCodeOf(df.grn.glass))))
                .andExpect(content().string(containsString("kind=QUARANTINE_RELEASE")))
                .andExpect(content().string(containsString("kind=WRITE_OFF")));
        mvc.perform(as(finance, get("/damage/quarantine")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(itemCodeOf(df.grn.glass))))
                .andExpect(content().string(not(containsString("kind=QUARANTINE_RELEASE"))));
        mvc.perform(as(nobody, get("/damage/quarantine"))).andExpect(status().isForbidden());
    }

    // ---- new: each kind's form -------------------------------------------------------------------------

    @Test
    void newPicksTheKindFirstAndThenFitsTheFormToIt() throws Exception {
        mvc.perform(as(raiser, get("/damage/new")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Write-off")))
                .andExpect(content().string(containsString("Loss in transit")))
                .andExpect(content().string(containsString("Customer return")))
                .andExpect(content().string(containsString("Quarantine release")));

        // A write-off: a location, item rows, and the stock on hand shown beside them.
        mvc.perform(as(raiser, get("/damage/new").param("kind", "WRITE_OFF").param("from", fx.location("KGL-MAIN").toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"lines[0].itemId\"")))
                .andExpect(content().string(containsString("name=\"lines[0].quantity\"")))
                .andExpect(content().string(containsString("On hand at the location now")))
                .andExpect(content().string(containsString(itemCodeOf(grn.glass))));

        // A loss: first which transfer, then a row per line prefilled at what remains in transit.
        mvc.perform(as(raiser, get("/damage/new").param("kind", "TRANSIT_LOSS")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Which transfer lost stock?")))
                .andExpect(content().string(containsString(transferSerial(toLose))));
        mvc.perform(as(raiser, get("/damage/new").param("kind", "TRANSIT_LOSS").param("transfer", toLose.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Written off against transfer")))
                .andExpect(content().string(containsString("name=\"lines[0].transferLineId\"")))
                .andExpect(content().string(containsString("name=\"lines[1].quantity\"")))
                .andExpect(content().string(containsString("value=\"20.000\"")));

        // A return: first which delivery, then what could still come back.
        mvc.perform(as(raiser, get("/damage/new").param("kind", "CUSTOMER_RETURN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Which delivery came back?")))
                .andExpect(content().string(containsString(serialOfDocument(note))));
        mvc.perform(as(raiser, get("/damage/new").param("kind", "CUSTOMER_RETURN").param("note", note.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Goods returned against delivery note")))
                .andExpect(content().string(containsString("name=\"lines[0].deliveryNoteLineId\"")))
                .andExpect(content().string(containsString("value=\"12.000\"")));

        // A release: the quarantine locations to take from, the sellable ones to put into.
        mvc.perform(as(raiser, get("/damage/new").param("kind", "QUARANTINE_RELEASE")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Released from quarantine")))
                .andExpect(content().string(containsString("KGL-QUAR")))
                .andExpect(content().string(containsString("name=\"lines[0].toStorageBinId\"")));
    }

    @Test
    void newIsRefusedForThoseWhoCannotRaiseAReportAndForThePartiesToWhatItIsAbout() throws Exception {
        mvc.perform(as(finance, get("/damage/new"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/damage/new"))).andExpect(status().isForbidden());
        mvc.perform(as(verifier, get("/damage/new").param("kind", "WRITE_OFF"))).andExpect(status().isForbidden());

        // The warehouse manager who raised the transfer cannot open its loss form, and sees why.
        AppUserDetails transferRaiser = userDetails.reload(tf.raiser(), fx.kigali()).orElseThrow();
        mvc.perform(as(transferRaiser, get("/damage/new").param("kind", "TRANSIT_LOSS").param("transfer", toLose.toString())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/transfers/" + toLose))
                .andExpect(flash().attribute("flashError", containsString("You raised transfer")));
    }

    // ---- create and htmx rows ------------------------------------------------------------------------------

    @Test
    void creatingAWriteOffRedirectsToItsDraft_andAnIncompleteFormComesBackWithFieldErrors() throws Exception {
        var result = mvc.perform(as(raiser, post("/damage").with(csrf())
                        .param("kind", "WRITE_OFF")
                        .param("reasonCode", "DAMAGED")
                        .param("reason", "Two sheets fell off the A-frame")
                        .param("fromLocationId", fx.location("KGL-MAIN").toString())
                        .param("lines[0].itemId", grn.glass.toString())
                        .param("lines[0].uomId", fx.uom("SHEET").toString())
                        .param("lines[0].quantity", "2")
                        .param("lines[0].storageBinId", grn.bin.toString())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashSuccess", containsString("raised as a draft")))
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).startsWith("/damage/");

        mvc.perform(as(raiser, post("/damage").with(csrf())
                        .param("kind", "WRITE_OFF")
                        .param("reasonCode", "DAMAGED")
                        .param("lines[0].quantity", "2")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Say what happened")))
                .andExpect(content().string(containsString("Choose the location the stock is written off from.")));

        // Bonded stock without a customs reference is a field error before it is the database's refusal.
        mvc.perform(as(raiser, post("/damage").with(csrf())
                        .param("kind", "WRITE_OFF")
                        .param("reasonCode", "DAMAGED")
                        .param("reason", "Sealant expired")
                        .param("fromLocationId", fx.location("KGL-MAIN").toString())
                        .param("lines[0].itemId", grn.glass.toString())
                        .param("lines[0].uomId", fx.uom("SHEET").toString())
                        .param("lines[0].quantity", "99999")))
                .andExpect(status().is3xxRedirection());      // creating is fine: stock is weighed at posting

        mvc.perform(as(nobody, post("/damage").with(csrf()).param("kind", "WRITE_OFF"))).andExpect(status().isForbidden());
        mvc.perform(as(finance, post("/damage").with(csrf()).param("kind", "WRITE_OFF"))).andExpect(status().isForbidden());
    }

    @Test
    void theLineRowsComeBackAsFragmentsNumberedByTheServer() throws Exception {
        mvc.perform(as(raiser, post("/damage/lines/add").with(csrf())
                        .param("kind", "WRITE_OFF")
                        .param("fromLocationId", fx.location("KGL-MAIN").toString())
                        .param("lines[0].quantity", "2")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"lines[1].quantity\"")))
                .andExpect(content().string(not(containsString("<html"))));
        mvc.perform(as(raiser, post("/damage/lines/remove/0").with(csrf())
                        .param("kind", "WRITE_OFF")
                        .param("lines[0].quantity", "2")
                        .param("lines[1].quantity", "3")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"lines[1].quantity\""))));
        mvc.perform(as(nobody, post("/damage/lines/add").with(csrf()).param("kind", "WRITE_OFF")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theLineRowsOfferOnlyThisBranchsBinsAndStock_whateverLocationIsPosted() throws Exception {
        String binCode = jdbc.sql("SELECT bin_code FROM storage_bin WHERE id = :id")
                .param("id", grn.bin).query(String.class).single();
        mvc.perform(as(raiser, post("/damage/lines/refresh").with(csrf())
                        .param("kind", "WRITE_OFF")
                        .param("fromLocationId", fx.location("KGL-MAIN").toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(binCode)))
                .andExpect(content().string(not(containsString("Nothing is on hand at this location."))));

        // A Warehouse Manager at Rubavu naming Gahanga's store reads neither its bins nor its stock.
        AppUserDetails rubavu = userDetails.reload(fx.userAt("RBV", "rbvwm", "WH_MANAGER"), fx.branch("RBV")).orElseThrow();
        mvc.perform(as(rubavu, post("/damage/lines/refresh").with(csrf())
                        .param("kind", "WRITE_OFF")
                        .param("fromLocationId", fx.location("KGL-MAIN").toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(binCode))))
                .andExpect(content().string(containsString("Nothing is on hand at this location.")));
    }

    // ---- view: the banner in each state ---------------------------------------------------------------------

    @Test
    void theBannerAndTheActionsSayWhereTheReportIsInItsLife() throws Exception {
        mvc.perform(as(raiser, get("/damage/" + draft)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Do not post")))
                .andExpect(content().string(containsString("Submit for signature")))
                .andExpect(content().string(containsString("Edit draft")))
                .andExpect(content().string(containsString("Cancel this report")));
        mvc.perform(as(raiser, get("/damage/" + pending)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Do not post")))
                .andExpect(content().string(containsString("awaiting signature")))
                .andExpect(content().string(containsString("can sign:")));
        mvc.perform(as(verifier, get("/damage/" + pending)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approve")))
                .andExpect(content().string(containsString("Reject this report")));

        mvc.perform(as(finance, get("/damage/" + approved)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approved: ready for Finance to post")))
                .andExpect(content().string(containsString("Post to the ledger")))
                .andExpect(content().string(containsString("name=\"confirm\"")))
                .andExpect(content().string(not(containsString("Do not post"))));
        mvc.perform(as(raiser, get("/damage/" + approved)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Post to the ledger"))));

        mvc.perform(as(finance, get("/damage/" + posted)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Posted to the ledger")))
                .andExpect(content().string(containsString("status-posted")))
                .andExpect(content().string(containsString("In the ledger")))
                .andExpect(content().string(containsString("Value (RWF)")))
                .andExpect(content().string(containsString("has been posted, so it cannot be cancelled")));
        mvc.perform(as(raiser, get("/damage/" + cancelled)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cancelled")))
                .andExpect(content().string(containsString("Raised against the wrong item")))
                .andExpect(content().string(containsString("status-cancelled")));
    }

    @Test
    void eachKindsViewSaysWhatItIsAboutAndLinksBackToIt() throws Exception {
        mvc.perform(as(raiser, get("/damage/" + lossDraft)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Loss in transit")))
                .andExpect(content().string(containsString("/transfers/" + toLose)))
                .andExpect(content().string(containsString("transfer line 1")));
        mvc.perform(as(finance, get("/damage/" + returnPosted)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Customer return")))
                .andExpect(content().string(containsString("/delivery-notes/" + note)))
                .andExpect(content().string(containsString("delivery line 1")));
        mvc.perform(as(raiser, get("/damage/" + releaseDraft)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quarantine release")))
                .andExpect(content().string(containsString("KGL-QUAR")))
                .andExpect(content().string(containsString("On hand at")));
    }

    @Test
    void aViewIsRefusedToThoseWithoutTheRight_andTheDocumentAddressFindsIt() throws Exception {
        mvc.perform(as(nobody, get("/damage/" + posted))).andExpect(status().isForbidden());
        mvc.perform(as(raiser, get("/documents/" + posted)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/damage/" + posted));
        mvc.perform(as(raiser, get("/damage/" + UUID.randomUUID()))).andExpect(status().isNotFound());
    }

    @Test
    void theSidebarLinksReturnsAndDamageForThoseWhoMayReadIt() throws Exception {
        mvc.perform(as(raiser, get("/damage")))
                .andExpect(content().string(containsString("Returns &amp; Damage")));
        mvc.perform(as(nobody, get("/")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/damage\""))));
    }

    // ---- lifecycle posts -------------------------------------------------------------------------------------

    @Test
    void submitApproveAndPostGoThroughTheirOwnRightsAndComeBackWithTheReason() throws Exception {
        mvc.perform(as(raiser, post("/damage/" + draft + "/submit").with(csrf())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashSuccess", containsString("Submitted")));
        mvc.perform(as(verifier, post("/damage/" + draft + "/approve").with(csrf()).param("comment", "Verified")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashSuccess", containsString("Signed")));
        // The next step is the Managing Director's: the verifier does not hold that right, so the URL is a 403.
        mvc.perform(as(verifier, post("/damage/" + draft + "/approve").with(csrf())))
                .andExpect(status().isForbidden());

        // Finance cannot post what is not approved, and posting needs the confirmation, server side.
        mvc.perform(as(finance, post("/damage/" + pending + "/post").with(csrf()).param("confirm", "true")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("only an approved document is posted")));
        mvc.perform(as(finance, post("/damage/" + approved + "/post").with(csrf())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("Posting was not confirmed")));
        mvc.perform(as(finance, post("/damage/" + approved + "/post").with(csrf()).param("confirm", "true")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashSuccess", containsString("Posted to the ledger under ticket TT-KGL-")));
        assertThat(jdbc.sql("SELECT status FROM document WHERE id = :id").param("id", approved)
                .query(String.class).single()).isEqualTo("POSTED");

        // Nobody without the right gets a 403, whatever they hold a URL for.
        mvc.perform(as(nobody, post("/damage/" + pending + "/submit").with(csrf()))).andExpect(status().isForbidden());
        mvc.perform(as(raiser, post("/damage/" + pending + "/post").with(csrf()).param("confirm", "true")))
                .andExpect(status().isForbidden());
        mvc.perform(as(finance, post("/damage/" + pending + "/cancel").with(csrf()).param("reason", "No")))
                .andExpect(status().isForbidden());
        mvc.perform(as(raiser, post("/damage/" + posted + "/cancel").with(csrf()).param("reason", "Undo")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("flashError", containsString("is posted, so it cannot be cancelled")));
    }

    // ---- cross-links from the transfer and the delivery note ------------------------------------------------------

    @Test
    void theTransferShowsWhatWasWrittenOffPerLineAndLinksItsLossReports() throws Exception {
        AppUserDetails manager = userDetails.reload(tf.raiser(), fx.kigali()).orElseThrow();
        mvc.perform(as(raiser, get("/transfers/" + lost)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Written off")))
                .andExpect(content().string(containsString("Losses written off")))
                .andExpect(content().string(containsString("status-written_off")))
                .andExpect(content().string(containsString("Written off in full")));
        mvc.perform(as(raiser, get("/transfers/" + toLose)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Write off a loss")))
                .andExpect(content().string(containsString("kind=TRANSIT_LOSS")));
        // The one who raised the transfer is not offered the button, and is told why.
        mvc.perform(as(manager, get("/transfers/" + toLose)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Write off a loss"))))
                .andExpect(content().string(containsString("You raised transfer")));
    }

    @Test
    void theDeliveryNoteShowsWhatCameBackPerLineAndLinksItsReturns() throws Exception {
        mvc.perform(as(raiser, get("/delivery-notes/" + note)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Returned")))
                .andExpect(content().string(containsString("Returns")))
                .andExpect(content().string(containsString("/damage/" + returnPosted)))
                .andExpect(content().string(containsString("Take goods back")));
        // The gate that posted the delivery is not offered the button, and is told why.
        AppUserDetails gate = userDetails.reload(df.gate, fx.kigali()).orElseThrow();
        mvc.perform(as(gate, get("/delivery-notes/" + note)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Take goods back"))))
                .andExpect(content().string(containsString("at the gate")));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private String serialOf(UUID report) {
        return serialOfDocument(report);
    }

    private String serialOfDocument(UUID doc) {
        return jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", doc).query(String.class).single();
    }

    private String transferSerial(UUID transfer) {
        return serialOfDocument(transfer);
    }

    private String itemCodeOf(UUID item) {
        return jdbc.sql("SELECT item_code FROM item WHERE id = :id").param("id", item).query(String.class).single();
    }
}
