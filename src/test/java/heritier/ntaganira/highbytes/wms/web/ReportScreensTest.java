package heritier.ntaganira.highbytes.wms.web;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.web
 * - File       : ReportScreensTest.java
 * - Date       : 2026-10-02
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Reports & KPIs render for whoever holds report.view, and every report downloads as PDF and Excel
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.reporting.report.ReportKind;
import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import heritier.ntaganira.highbytes.wms.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As the Internal Controller and Finance (every report open to both) and a salesperson (no report right: 403). */
class ReportScreensTest extends IntegrationTest {

    @Autowired MockMvc mvc;

    AppUserDetails finance;
    AppUserDetails financeOnly;
    AppUserDetails sales;

    @BeforeEach
    void cast() {
        // The Internal Controller holds every view right, so every report is open to it.
        finance = userDetails.reload(fx.user("ric", "INTERNAL_CTRL"), fx.kigali()).orElseThrow();
        financeOnly = userDetails.reload(fx.user("rfin", "FINANCE"), fx.kigali()).orElseThrow();
        sales = userDetails.reload(fx.user("rsales", "SALES"), fx.kigali()).orElseThrow();
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequestBuilder as(AppUserDetails who, MockHttpServletRequestBuilder request) {
        return request.with(user(who));
    }

    @Test
    void theIndexEveryReportAndEveryExportRender() throws Exception {
        mvc.perform(as(finance, get("/reports"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Inventory accuracy")))
                .andExpect(content().string(containsString("Stock valuation")));
        for (ReportKind kind : ReportKind.values()) {
            mvc.perform(as(finance, get("/reports/" + kind.key()).param("from", "2026-01-01")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(kind.title())));
            mvc.perform(as(finance, get("/reports/" + kind.key() + "/pdf")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/pdf"))
                    .andExpect(header().string("Content-Disposition", containsString(kind.key() + "-KGL-")));
            mvc.perform(as(finance, get("/reports/" + kind.key() + "/xlsx")))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString(".xlsx")));
        }
    }

    @Test
    void aBadPeriodOrAnUnknownReportIsRefused() throws Exception {
        mvc.perform(as(finance, get("/reports/movements").param("from", "2026-10-02").param("to", "2026-09-01")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The period ends before it starts.")));
        mvc.perform(as(finance, get("/reports/movements/pdf").param("from", "2026-10-02").param("to", "2026-09-01")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(finance, get("/reports/nope"))).andExpect(status().isNotFound());
        mvc.perform(as(sales, get("/reports"))).andExpect(status().isForbidden());
        mvc.perform(as(financeOnly, get("/reports"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("/reports/damage")));
        mvc.perform(as(sales, get("/reports/stock-valuation/pdf"))).andExpect(status().isForbidden());
    }
}
