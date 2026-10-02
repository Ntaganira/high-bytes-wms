package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : AdministrationScreensTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : The branch, workflow and audit log screens render for whoever holds their right, and for nobody else
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
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

/**
 * As the System Administrator (branches, workflows and the audit log), the Internal Controller (the audit log, at
 * one branch) and a Warehouse Manager (none of them: 403).
 */
class AdministrationScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;

    AppUserDetails admin;
    AppUserDetails controller;
    AppUserDetails manager;

    @BeforeEach
    void people() {
        admin = userDetails.reload(fx.user("adm", "SYS_ADMIN"), fx.kigali()).orElseThrow();
        controller = userDetails.reload(fx.user("ic", "INTERNAL_CTRL"), fx.kigali()).orElseThrow();
        manager = userDetails.reload(fx.user("whm", "WH_MANAGER"), fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    @Test
    void branchesAreListedCreatedAndReadByTheirAdministrator() throws Exception {
        mvc.perform(as(admin, get("/admin/branches")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Gahanga Main Warehouse")))
                .andExpect(content().string(containsString("BBF Public Bonded Warehouse")));
        mvc.perform(as(admin, get("/admin/branches/" + fx.kigali())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Not ready to close")))
                .andExpect(content().string(containsString("main branch")))
                .andExpect(content().string(not(containsString("Deactivate branch"))));
        mvc.perform(as(admin, get("/admin/branches/new"))).andExpect(status().isOk());

        String code = "W" + java.util.concurrent.ThreadLocalRandom.current().nextInt(10000, 100000);
        mvc.perform(as(admin, post("/admin/branches")).with(csrf())
                        .param("code", code).param("name", "Screens branch " + code)
                        .param("branchType", "BONDED").param("countryCode", "RW"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("A bonded warehouse is bonded")));
        mvc.perform(as(admin, post("/admin/branches")).with(csrf())
                        .param("code", code).param("name", "Screens branch " + code)
                        .param("branchType", "BRANCH").param("countryCode", "RW").param("city", "Huye"))
                .andExpect(redirectedUrlPattern("/admin/branches/*"))
                .andExpect(flash().attribute("flashSuccess", containsString("created")));

        mvc.perform(as(manager, get("/admin/branches"))).andExpect(status().isForbidden());
        mvc.perform(as(controller, get("/admin/branches"))).andExpect(status().isForbidden());
    }

    @Test
    void theChainsAreReadWholeAndASwitchoverNeedsAReason() throws Exception {
        mvc.perform(as(admin, get("/admin/workflows")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Goods Received Note")))
                .andExpect(content().string(containsString("The steps are the policy")))
                .andExpect(content().string(containsString("comes into force on")));

        UUID old2026 = jdbc.sql("""
                SELECT wd.id FROM workflow_definition wd JOIN document_type dt ON dt.id = wd.document_type_id
                 WHERE dt.code = 'DAO' AND wd.basis = 'POLICY_2026'
                """).query(UUID.class).single();
        mvc.perform(as(admin, post("/admin/workflows/" + old2026 + "/switchover")).with(csrf())
                        .param("date", "2027-02-01"))
                .andExpect(redirectedUrl("/admin/workflows"))
                .andExpect(flash().attribute("flashError", containsString("Say why")));

        mvc.perform(as(manager, get("/admin/workflows"))).andExpect(status().isForbidden());
        mvc.perform(as(manager, post("/admin/workflows/" + old2026 + "/switchover")).with(csrf())
                        .param("date", "2027-02-01").param("reason", "Mine"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAuditLogIsReadAsFarAsTheRightReaches() throws Exception {
        long recorded = jdbc.sql("""
                INSERT INTO audit_log (entity_name, entity_id, entity_label, action, actor_name, actor_username,
                                       branch_id, branch_name, reason)
                VALUES ('item', gen_random_uuid(), 'Screens audit entry', 'UPDATE', 'System', 'system',
                        :kgl, 'Gahanga Main Warehouse', 'Checked on screen')
                RETURNING id
                """).param("kgl", fx.kigali()).query(Long.class).single();
        long elsewhere = jdbc.sql("""
                INSERT INTO audit_log (entity_name, entity_id, entity_label, action, actor_name, actor_username,
                                       branch_id, branch_name)
                VALUES ('item', gen_random_uuid(), 'Screens audit entry elsewhere', 'UPDATE', 'System', 'system',
                        :rbv, 'BBF Public Bonded Warehouse')
                RETURNING id
                """).param("rbv", fx.branch("RBV")).query(Long.class).single();

        mvc.perform(as(controller, get("/admin/audit").param("q", "Screens audit entry")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Screens audit entry")))
                .andExpect(content().string(not(containsString("Screens audit entry elsewhere"))))
                .andExpect(content().string(containsString("Only the entries recorded at the branches")));
        mvc.perform(as(controller, get("/admin/audit/" + recorded)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Checked on screen")))
                .andExpect(content().string(containsString("Everything recorded about this record")));
        mvc.perform(as(controller, get("/admin/audit/" + elsewhere))).andExpect(status().isNotFound());

        mvc.perform(as(admin, get("/admin/audit").param("subject", "branch").param("action", "BOGUS")))
                .andExpect(status().isOk());
        mvc.perform(as(manager, get("/admin/audit"))).andExpect(status().isForbidden());
    }
}
