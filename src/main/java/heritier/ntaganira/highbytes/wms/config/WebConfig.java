package heritier.ntaganira.highbytes.wms.config;

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
