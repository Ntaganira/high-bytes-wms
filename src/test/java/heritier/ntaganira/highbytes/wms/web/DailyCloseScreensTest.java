package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : DailyCloseScreensTest.java
 * - Date       : 2026-10-01
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Renders the daily close screens for each reader and walks a day from open to locked
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.CloseFlow;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;

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
 * The register and a day, as Finance (reconciles), the Internal Controller (countersigns and locks), the Warehouse
 * Manager (reads) and a salesperson (holds no close right: 403), at a branch of the test's own.
 */
class DailyCloseScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;

    CloseFlow cf;
    AppUserDetails finance;
    AppUserDetails controller;
    AppUserDetails manager;
    AppUserDetails nobody;
    LocalDate first;
    LocalDate second;

    @BeforeEach
    void stock() {
        cf = new CloseFlow(fx, jdbc, transactions);
        first = cf.today().minusDays(3);
        second = cf.today().minusDays(2);
        cf.stockOn(first, 10);
        cf.stockOn(second, 5);
        finance = userDetails.reload(cf.finance, cf.branch).orElseThrow();
        controller = userDetails.reload(cf.controller, cf.branch).orElseThrow();
        manager = userDetails.reload(cf.manager, cf.branch).orElseThrow();
        nobody = userDetails.reload(cf.salesperson, cf.branch).orElseThrow();
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    @Test
    void theRegisterAndADayRenderForWhoeverMayReadThem() throws Exception {
        mvc.perform(as(finance, get("/daily-close")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Awaiting reconciliation")))
                .andExpect(content().string(containsString("/daily-close/" + first)))
                .andExpect(content().string(containsString("not prepared yet")));
        mvc.perform(as(manager, get("/daily-close"))).andExpect(status().isOk());
        mvc.perform(as(nobody, get("/daily-close"))).andExpect(status().isForbidden());
        mvc.perform(as(nobody, get("/daily-close/" + first))).andExpect(status().isForbidden());

        mvc.perform(as(finance, get("/daily-close/" + first)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("10,000.00")))
                .andExpect(content().string(containsString("Balance cache differs from the ledger")))
                .andExpect(content().string(containsString("Reconcile the day")))
                .andExpect(content().string(containsString("required: the day has exceptions")));
        mvc.perform(as(manager, get("/daily-close/" + first)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Reconcile the day"))));
        mvc.perform(as(finance, get("/daily-close/" + second)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("An earlier day comes first")))
                .andExpect(content().string(not(containsString("Reconcile the day"))));
        mvc.perform(as(finance, get("/daily-close/" + cf.today())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The day is not over.")));
        mvc.perform(as(finance, get("/daily-close/" + cf.today().plusDays(1))))
                .andExpect(redirectedUrl("/daily-close"));
    }

    @Test
    void financeReconcilesAndTheInternalControllerLocksTheDay() throws Exception {
        String day = "/daily-close/" + first;

        mvc.perform(as(manager, post(day + "/reconcile")).with(csrf()).param("note", "Mine"))
                .andExpect(status().isForbidden());
        mvc.perform(as(finance, post(day + "/reconcile")).with(csrf()))
                .andExpect(redirectedUrl(day))
                .andExpect(flash().attribute("flashError", containsString("in the note")));
        mvc.perform(as(finance, post(day + "/reconcile")).with(csrf())
                        .param("note", "Written to the ledger directly: the balance cache never saw it"))
                .andExpect(redirectedUrl(day))
                .andExpect(flash().attribute("flashSuccess", containsString("Reconciled")));

        mvc.perform(as(controller, get(day)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Countersign and lock")))
                .andExpect(content().string(containsString("Return it to be reconciled again")));
        mvc.perform(as(finance, post(day + "/countersign")).with(csrf()).param("confirm", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(as(controller, post(day + "/countersign")).with(csrf()))
                .andExpect(redirectedUrl(day))
                .andExpect(flash().attribute("flashError", containsString("not confirmed")));
        mvc.perform(as(controller, post(day + "/countersign")).with(csrf()).param("confirm", "true"))
                .andExpect(redirectedUrl(day))
                .andExpect(flash().attribute("flashSuccess", containsString("is locked")));

        mvc.perform(as(manager, get(day)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Locked</strong>")))
                .andExpect(content().string(containsString("never reopened")));
        mvc.perform(as(finance, get("/daily-close")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("10,000.00")));
    }
}
