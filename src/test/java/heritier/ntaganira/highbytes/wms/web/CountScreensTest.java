package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : CountScreensTest.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Renders every stock count screen in each phase and proves the book is absent while the count is blind
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.count.CountScope;
import heritier.ntaganira.highbytes.wms.inventory.count.CountService;
import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.CountFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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

/**
 * Every count screen, in the phases it appears in, as someone who holds the right
 * (200) and someone who does not (403).
 *
 * <p>The blind count is proved on the rendered HTML: the store holds 4,729 sheets
 * and the counter finds 4,711, and neither number appears on a page a person may
 * not read it on. Quantities are matched with their separator ("4,729" or
 * "4729.") so an identifier in the page can never match by chance.
 */
class CountScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReceivingService receiving;
    @Autowired CountService counts;

    CountFlow cf;
    AppUserDetails raiser;      // Warehouse Manager: count.create, count.enter
    AppUserDetails verifier;    // Internal Controller: count.verify
    AppUserDetails approver;    // Finance: count.approve, count.post
    AppUserDetails poster;      // a second Finance officer
    AppUserDetails nobody;      // holds no count right

    @BeforeEach
    void cast() {
        cf = new CountFlow(fx, counts, receiving);
        cf.stock("4729", true, null);
        raiser = userDetails.reload(cf.raiser(), fx.kigali()).orElseThrow();
        verifier = userDetails.reload(cf.verifier(), fx.kigali()).orElseThrow();
        approver = userDetails.reload(cf.approver(), fx.kigali()).orElseThrow();
        poster = userDetails.reload(cf.poster, fx.kigali()).orElseThrow();
        nobody = userDetails.reload(fx.user("nobody", "SALES"), fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    private String serialOf(UUID id) {
        return jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", id).query(String.class).single();
    }

    private UUID lineOf(UUID count) {
        return jdbc.sql("SELECT id FROM stock_count_line WHERE document_id = :id AND line_no = 1")
                .param("id", count).query(UUID.class).single();
    }

    /** Neither the book (4,729) nor, unless allowed, the first count (4,711) is anywhere in the page. */
    private ResultActions blind(ResultActions page, boolean firstCountAllowed) throws Exception {
        page.andExpect(content().string(not(containsString("4,729"))))
            .andExpect(content().string(not(containsString("4729."))));
        if (!firstCountAllowed) {
            page.andExpect(content().string(not(containsString("4,711"))))
                .andExpect(content().string(not(containsString("4711."))));
        }
        return page;
    }

    @Test
    void theBookIsInNoPageWhileTheCountIsBlindAndInTheViewOnceVerified() throws Exception {
        UUID id = cf.open(CountScope.FULL);
        cf.count(id, l -> "4711");
        SecurityContextHolder.clearContext();

        // Counting: the view shows progress only; the counter's sheet shows their count, never the book.
        blind(mvc.perform(as(raiser, get("/counts/" + id))), false)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Counting.")))
                .andExpect(content().string(containsString("Counting sheet")));
        blind(mvc.perform(as(raiser, get("/counts/" + id + "/count"))), true)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"4711.000\"")));
        mvc.perform(as(verifier, get("/counts/" + id + "/count"))).andExpect(status().isForbidden());
        blind(mvc.perform(as(verifier, get("/counts/" + id))), false).andExpect(status().isOk());

        cf.submit(id);
        SecurityContextHolder.clearContext();

        // Awaiting verification: the verifier's sheet shows neither the book nor the first count.
        blind(mvc.perform(as(verifier, get("/counts/" + id + "/verify"))), false)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("chosen")));
        blind(mvc.perform(as(verifier, get("/counts/" + id))), false)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Awaiting the verification count.")))
                .andExpect(content().string(containsString("Verification sheet")));
        blind(mvc.perform(as(raiser, get("/counts/" + id))), false).andExpect(status().isOk());
        mvc.perform(as(raiser, get("/counts/" + id + "/verify"))).andExpect(status().isForbidden());
        mvc.perform(as(raiser, get("/variances")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(serialOf(id)))));

        // The verifier recounts 4,711 too and signs; from then on the view carries the book and the variance.
        mvc.perform(as(verifier, post("/counts/" + id + "/verify")).with(csrf())
                        .param("lines[0].lineId", lineOf(id).toString())
                        .param("lines[0].quantity", "4711"))
                .andExpect(redirectedUrl("/counts/" + id + "/verify"))
                .andExpect(flash().attribute("flashSuccess", "1 line recounted."));
        mvc.perform(as(verifier, post("/counts/" + id + "/approve")).with(csrf()))
                .andExpect(redirectedUrl("/counts/" + id))
                .andExpect(flash().attribute("flashSuccess", "Signed. The count moves to the next step."));

        mvc.perform(as(raiser, get("/counts/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("4,729.000")))
                .andExpect(content().string(containsString("4,711.000")))
                .andExpect(content().string(containsString("-18.000")));
        mvc.perform(as(raiser, get("/variances")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(serialOf(id))))
                .andExpect(content().string(containsString("-18.000")));
    }

    @Test
    void theListAndTheOpeningFormAndOneCountPerLocation() throws Exception {
        mvc.perform(as(raiser, get("/counts")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Open a count")));
        mvc.perform(as(approver, get("/counts")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Open a count"))));
        mvc.perform(as(nobody, get("/counts"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/variances"))).andExpect(status().isForbidden());

        mvc.perform(as(raiser, get("/counts/new").param("location", cf.location.toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(cf.locationCode)))
                .andExpect(content().string(containsString("Cycle count")));
        mvc.perform(as(verifier, get("/counts/new"))).andExpect(status().isForbidden());

        mvc.perform(as(raiser, post("/counts")).with(csrf())
                        .param("locationId", cf.location.toString())
                        .param("scope", "FULL"))
                .andExpect(redirectedUrlPattern("/counts/*"));
        mvc.perform(as(raiser, post("/counts")).with(csrf())
                        .param("locationId", cf.location.toString())
                        .param("scope", "PARTIAL"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Choose the items a cycle count covers.")));
        mvc.perform(as(raiser, post("/counts")).with(csrf())
                        .param("locationId", cf.location.toString())
                        .param("scope", "PARTIAL")
                        .param("itemIds", cf.grn.glass.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("is already counting it")));
    }

    @Test
    void postingIsOfferedToTheSecondFinanceOfficerAndShowsTheLedger() throws Exception {
        UUID id = cf.approved(l -> "4700");
        SecurityContextHolder.clearContext();

        mvc.perform(as(approver, get("/counts/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("so you cannot post it")))
                .andExpect(content().string(not(containsString("Post the adjustment"))));
        mvc.perform(as(poster, get("/counts/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approved: ready for a second Finance officer to post")))
                .andExpect(content().string(containsString("Post the adjustment")));

        mvc.perform(as(poster, post("/counts/" + id + "/post")).with(csrf()))
                .andExpect(redirectedUrl("/counts/" + id))
                .andExpect(flash().attribute("flashError",
                        "Posting was not confirmed. Tick the confirmation to adjust the stock: it cannot be undone."));
        mvc.perform(as(poster, post("/counts/" + id + "/post")).with(csrf()).param("confirm", "true"))
                .andExpect(redirectedUrl("/counts/" + id));
        mvc.perform(as(raiser, get("/counts/" + id)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Posted to the ledger")))
                .andExpect(content().string(containsString("In the ledger")))
                .andExpect(content().string(containsString("-29.000")));

        mvc.perform(as(raiser, get("/documents/" + id))).andExpect(redirectedUrl("/counts/" + id));
    }
}
