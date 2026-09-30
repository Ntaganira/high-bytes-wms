package heritier.ntaganira.highbytes.wms.support;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.support
 * - File       : IntegrationTest.java
 * - Date       : 2026-09-30
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Base for tests that run the application against a real PostgreSQL 16
 * </pre>
 */

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import heritier.ntaganira.highbytes.wms.security.AppUserDetailsService;

/**
 * The ledger rules are triggers, so they are tested against the database that
 * has them, never an in-memory stand-in. One container serves every test
 * class in the run, so Flyway (V1 to V11) runs once and the Spring context is
 * shared.
 *
 * <p>Tests that touch the ledger use their own users, items and suppliers
 * (unique names), and leave them behind: the ledger is append-only, so there
 * is nothing to clean.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("highbytes_wms")
            .withUsername("highbytes")
            .withPassword("highbytes");

    static {
        DB.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired protected JdbcClient jdbc;
    @Autowired protected AppUserDetailsService userDetails;

    protected Fixtures fx;

    @BeforeEach
    void fixtures() {
        fx = new Fixtures(jdbc, userDetails);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }
}
