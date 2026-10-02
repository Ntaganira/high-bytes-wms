package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : ApprovalScreensTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The approval queue renders, links to what waits, and switches branch only to a document's address
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.inventory.receiving.ReceivingService;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.security.SessionAccess;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As the receipt's next signer, its raiser, and an administrator (no document right: an empty queue). */
class ApprovalScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReceivingService receiving;

    GrnFlow flow;
    UUID pending;
    String serial;

    @BeforeEach
    void cast() {
        flow = new GrnFlow(fx, receiving);
        pending = flow.pending();
        serial = jdbc.sql("SELECT serial_no FROM document WHERE id = :id").param("id", pending)
                .query(String.class).single();
        SecurityContextHolder.clearContext();
    }

    private AppUserDetails at(UUID user, String branchCode) {
        return userDetails.reload(user, fx.branch(branchCode)).orElseThrow();
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    @Test
    void theQueueListsWhatWaitsOnTheReaderAndLinksToIt() throws Exception {
        AppUserDetails nextSigner = at(flow.stepUsers.get(1), "KGL");
        mvc.perform(as(nextSigner, get("/approvals")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Approval Queue")))
                .andExpect(content().string(containsString(serial)))
                .andExpect(content().string(containsString("/documents/" + pending)))
                .andExpect(content().string(containsString("Step 2 of " + flow.chainRoles.size())));
        mvc.perform(as(nextSigner, get("/approvals").param("type", "DAO")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(serial))));
        // The sidebar carries the queue with its badge, and the bell names it.
        mvc.perform(as(nextSigner, get("/")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("awaiting your signature")));

        mvc.perform(as(at(flow.raiser(), "KGL"), get("/approvals")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(serial))));
        // An administrator signs nothing: the queue is theirs, and empty, and no document link reaches them.
        AppUserDetails administrator = at(fx.user("adm", "SYS_ADMIN"), "KGL");
        mvc.perform(as(administrator, get("/approvals")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing waits on you")))
                .andExpect(content().string(not(containsString("/documents/"))));
        mvc.perform(as(administrator, get("/documents/" + pending))).andExpect(status().isForbidden());
    }

    @Test
    void aDocumentAtAnotherBranchOffersTheSwitchToItAndNothingElse() throws Exception {
        UUID signer = flow.stepUsers.get(1);
        fx.grantAt(signer, "SALES", "RBV");
        AppUserDetails atRubavu = at(signer, "RBV");

        mvc.perform(as(atRubavu, get("/approvals")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(serial)))
                .andExpect(content().string(containsString("Switch to KGL")))
                .andExpect(content().string(not(containsString("href=\"/documents/" + pending))));

        mvc.perform(as(atRubavu, post("/branch/switch").with(csrf())
                        .param("branchId", fx.kigali().toString())
                        .param("next", "/documents/" + pending)))
                .andExpect(redirectedUrl("/documents/" + pending))
                .andExpect(request().sessionAttribute(SessionAccess.BRANCH_SESSION_KEY, fx.kigali()));
        // Anywhere but a document's own address goes home.
        for (String elsewhere : new String[]{"//evil.example/x", "/admin/users", "/documents/../admin",
                                             "/documents/" + pending + "/edit", "https://evil.example"}) {
            mvc.perform(as(atRubavu, post("/branch/switch").with(csrf())
                            .param("branchId", fx.kigali().toString())
                            .param("next", elsewhere)))
                    .andExpect(redirectedUrl("/"));
        }
    }
}
