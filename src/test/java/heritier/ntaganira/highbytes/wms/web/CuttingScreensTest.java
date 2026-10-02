package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : CuttingScreensTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The cutting screens render in each state, and the gate loads a posted order's pieces
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.cutting.CuttingService;
import heritier.ntaganira.highbytes.wms.inventory.dispatch.DeliveryNoteService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.CuttingFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As Finance (raises and posts), the Warehouse Manager at the gate, and a salesperson (no cutting right: 403). */
class CuttingScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired CuttingService cutting;
    @Autowired DeliveryNoteService notes;
    @Autowired ReceivingService receiving;

    CuttingFlow cf;
    AppUserDetails finance;
    AppUserDetails poster;
    AppUserDetails gate;

    @BeforeEach
    void cast() {
        cf = new CuttingFlow(fx, cutting, notes, receiving);
        finance = userDetails.reload(cf.raiser(), fx.kigali()).orElseThrow();
        poster = userDetails.reload(cf.poster, fx.kigali()).orElseThrow();
        gate = userDetails.reload(cf.gate, fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    @Test
    void theListTheFormAndARaisedDraftRender() throws Exception {
        mvc.perform(as(finance, get("/cutting"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Cutting Orders")));
        mvc.perform(as(finance, get("/cutting/new").param("location", cf.location.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The sheet cut")))
                .andExpect(content().string(containsString(cf.sheetCode)));
        // The sheet section and the rows redraw through htmx.
        mvc.perform(as(finance, post("/cutting/sheet").with(csrf())
                        .param("locationId", cf.location.toString()).param("sheetItemId", cf.sheet.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("On hand here")));
        mvc.perform(as(finance, post("/cutting/outputs/add").with(csrf())
                        .param("outputs[0].kind", "PIECE").param("outputs[0].widthMm", "100")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("outputs[1].widthMm")));

        // Raised through the form: a size too small to keep is refused there, with its reason.
        mvc.perform(as(finance, post("/cutting").with(csrf())
                        .param("customerId", cf.customer.toString())
                        .param("locationId", cf.location.toString())
                        .param("sheetItemId", cf.sheet.toString())
                        .param("sheets", "1")
                        .param("outputs[0].kind", "OFFCUT").param("outputs[0].widthMm", "250")
                        .param("outputs[0].heightMm", "1000").param("outputs[0].quantity", "1")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is waste")));
        mvc.perform(as(finance, post("/cutting").with(csrf())
                        .param("customerId", cf.customer.toString())
                        .param("locationId", cf.location.toString())
                        .param("sheetItemId", cf.sheet.toString())
                        .param("sheetBinId", cf.bin.toString())
                        .param("sheets", "1")
                        .param("outputs[0].kind", "PIECE").param("outputs[0].widthMm", "1000")
                        .param("outputs[0].heightMm", "500").param("outputs[0].quantity", "4")))
                .andExpect(redirectedUrlPattern("/cutting/*"));

        UUID draft = cf.draft(cf.standardForm());
        mvc.perform(as(finance, get("/cutting/" + draft))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Do not cut")))
                .andExpect(content().string(containsString(cf.sheetCode + "-R-1200x800")))
                .andExpect(content().string(containsString("Submit for signature")));
        mvc.perform(as(finance, get("/cutting/" + draft + "/edit"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Save changes")));
        mvc.perform(as(finance, get("/documents/" + draft)))
                .andExpect(redirectedUrl("/cutting/" + draft));
    }

    @Test
    void aReleasedOrderIsPostedOnItsPageAndItsPiecesLoadAtTheGate() throws Exception {
        UUID id = cf.released(cf.standardForm());
        SecurityContextHolder.clearContext();
        mvc.perform(as(poster, get("/cutting/" + id))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Released: ready to cut")))
                .andExpect(content().string(containsString("Post the cut")));
        mvc.perform(as(poster, post("/cutting/" + id + "/post").with(csrf())))
                .andExpect(flash().attribute("flashSuccess", containsString("Posted")));

        mvc.perform(as(gate, get("/cutting/" + id))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Cut and posted: ready to load")))
                .andExpect(content().string(containsString("Posted to the ledger")))
                .andExpect(content().string(containsString("Raise the delivery note")));
        mvc.perform(as(gate, get("/delivery-notes"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("/delivery-notes/new?dao=" + id)));
        mvc.perform(as(gate, get("/delivery-notes/new").param("dao", id.toString()))).andExpect(status().isOk())
                .andExpect(content().string(containsString("The load is the pieces of the cutting order")))
                .andExpect(content().string(containsString(cf.sheetCode + "-R-1200x800")))
                .andExpect(content().string(not(containsString(cf.sheetCode + "-R-1800x600"))));

        UUID note = cf.raiseNote(id);
        SecurityContextHolder.clearContext();
        mvc.perform(as(gate, get("/delivery-notes/" + note))).andExpect(status().isOk())
                .andExpect(content().string(containsString("cutting order")));
    }

    @Test
    void thoseWithoutTheRightAreRefused() throws Exception {
        AppUserDetails sales = userDetails.reload(fx.user("sales", "SALES"), fx.kigali()).orElseThrow();
        UUID draft = cf.draft(cf.standardForm());
        SecurityContextHolder.clearContext();
        mvc.perform(as(sales, get("/cutting"))).andExpect(status().isForbidden());
        mvc.perform(as(sales, get("/cutting/" + draft))).andExpect(status().isForbidden());
        // The Warehouse Manager reads and verifies, but does not raise.
        mvc.perform(as(gate, get("/cutting/new"))).andExpect(status().isForbidden());
    }
}
