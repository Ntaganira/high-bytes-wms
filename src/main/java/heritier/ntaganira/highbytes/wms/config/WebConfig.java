package heritier.ntaganira.highbytes.wms.config;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.config
 * - File       : WebConfig.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Supplies the auditorAware bean: the signed-in user, for JPA auditing
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.security.AppUserDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

@Configuration
public class WebConfig {

    /** Who is acting, for JPA auditing and the audit log. */
    @Bean
    public AuditorAware<UUID> auditorAware() {
        return () -> Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .map(a -> a.getPrincipal())
                .filter(AppUserDetails.class::isInstance)
                .map(AppUserDetails.class::cast)
                .map(AppUserDetails::id);
    }
}
