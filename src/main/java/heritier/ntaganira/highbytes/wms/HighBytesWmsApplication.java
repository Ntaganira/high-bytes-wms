package heritier.ntaganira.highbytes.wms;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms
 * - File       : HighBytesWmsApplication.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Spring Boot entry point for the HIGH BYTES warehouse management system
 * </pre>
 */

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * HIGH BYTES Ltd — Warehouse Management System, Phase 1.
 *
 * <p>Covers inventory control at the Gahanga main warehouse (Kigali) and the
 * BBF Public Bonded Warehouse (Rubavu), with the branch model open so further
 * branches are configuration rather than development.
 *
 * <p>Three rules hold everywhere in this codebase and are enforced by the schema
 * as well as the service layer:
 * <ul>
 *   <li>The stock ledger is append-only. A mistake is corrected by a reversing
 *       movement that references the original, never by an update or delete.</li>
 *   <li>No stock movement exists without a controlled document behind it.</li>
 *   <li>No document is released until every mandatory approval step its
 *       workflow definition names has been signed by a different user.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class HighBytesWmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HighBytesWmsApplication.class, args);
    }
}
