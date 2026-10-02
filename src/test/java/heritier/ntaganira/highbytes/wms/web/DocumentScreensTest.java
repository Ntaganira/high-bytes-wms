package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : DocumentScreensTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The register of every document renders, filtered, and offers the switch for another branch's
 * </pre>
 */

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As a Warehouse Manager, the same working at Rubavu, and an administrator (no document right: 403). */
class DocumentScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReceivingService receiving;

    UUID pending;
    String serial;

    @BeforeEach
    void cast() {
        GrnFlow flow = new GrnFlow(fx, receiving);
        pending = flow.pending();
        serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", pending)
                .query(String.class).single();
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    @Test
    void theRegisterRendersFilteredAndLinksToEachDocument() throws Exception {
        AppUserDetails manager = userDetails.reload(fx.user("whm", "WH_MANAGER"), fx.kigali()).orElseThrow();
        mvc.perform(as(manager, get("/documents").param("q", serial)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("All Documents")))
                .andExpect(content().string(containsString(serial)))
                .andExpect(content().string(containsString("href=\"/documents/" + pending)))
                .andExpect(content().string(containsString("Step 2 of")));
        mvc.perform(as(manager, get("/documents").param("q", serial).param("status", "POSTED")
                        .param("type", "GRN").param("branch", fx.kigali().toString())
                        .param("from", "2026-01-01").param("to", "2030-12-31").param("mine", "true")))
                .andExpect(status().isOk())
                // The serial stays in the search box; the document is what must be gone.
                .andExpect(content().string(not(containsString("/documents/" + pending))))
                .andExpect(content().string(containsString("No document matches")));
        // Nonsense filters are ignored rather than failing the page.
        mvc.perform(as(manager, get("/documents").param("type", "NOPE").param("status", "NOPE")))
                .andExpect(status().isOk());

        mvc.perform(as(userDetails.reload(fx.user("adm", "SYS_ADMIN"), fx.kigali()).orElseThrow(), get("/documents")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherBranchsDocumentOffersTheSwitch() throws Exception {
        UUID both = fx.user("whmk", "WH_MANAGER");
        fx.grantAt(both, "DIR_COMMERCIAL", "RBV");
        AppUserDetails atRubavu = userDetails.reload(both, fx.branch("RBV")).orElseThrow();
        mvc.perform(as(atRubavu, get("/documents").param("q", serial)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(serial)))
                .andExpect(content().string(containsString("Switch to KGL")))
                .andExpect(content().string(not(containsString("href=\"/documents/" + pending))));
    }
}
