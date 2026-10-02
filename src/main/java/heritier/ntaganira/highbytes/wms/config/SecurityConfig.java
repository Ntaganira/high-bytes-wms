package heritier.ntaganira.highbytes.wms.config;

/**
 * <pre>
 * - Project    : HIGH BYTES WMS
 * - Package    : heritier.ntaganira.highbytes.wms.config
 * - File       : SecurityConfig.java
 * - Date       : 2026-09-29
 * - Author     : NTAGANIRA Heritier
 * - Desc       : Session-based authentication, URL authorization and CSRF settings
 * </pre>
 */

import heritier.ntaganira.highbytes.wms.common.web.ErrorPages;
import heritier.ntaganira.highbytes.wms.security.AccountStateFilter;
import heritier.ntaganira.highbytes.wms.security.AppUserDetailsService;
import heritier.ntaganira.highbytes.wms.security.SessionAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.Map;

/**
 * Session-based authentication.
 *
 * <p>Not JWT: this is a server-rendered application, so an HTTP session and
 * a CSRF token do correctly what a token in local storage would do badly.
 *
 * <p>Authorisation here hides what a user cannot use. It is not the control
 * — {@code @PreAuthorize} on the service method is, because a hidden button
 * stops nobody holding a URL.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Holding any one of these lets a user read some kind of document. */
    private static final String[] DOCUMENT_VIEW = {
            "receiving.view", "dispatch.view", "transfer.view", "cutting.view",
            "damage.view", "count.view", "ticket.view"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /** Where the signed-in user is kept between requests. Shared with SessionAccess. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    /** Tells the session registry when a session ends, so it counts only live ones. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           AppUserDetailsService userDetailsService,
                                           SecurityContextRepository contexts,
                                           SessionAccess sessions,
                                           JdbcClient jdbc) throws Exception {
        http
            .userDetailsService(userDetailsService)

            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/webjars/**", "/favicon.ico", "/favicon.svg").permitAll()
                .requestMatchers("/login", "/error").permitAll()
                .requestMatchers("/actuator/health").permitAll()

                .requestMatchers("/admin/users/**")     .hasAuthority("admin.users")
                .requestMatchers("/admin/roles/**")     .hasAuthority("admin.roles")
                .requestMatchers("/admin/workflows/**") .hasAuthority("admin.workflow")
                .requestMatchers("/admin/branches/**")  .hasAuthority("admin.branches")
                .requestMatchers("/admin/audit/**")     .hasAuthority("audit.view")

                .requestMatchers("/receiving/**")      .hasAuthority("receiving.view")
                // Setting up who the company buys from is Finance's; the store only picks from the list.
                .requestMatchers("/suppliers/**")      .hasAuthority("partner.manage")
                .requestMatchers("/customers/**")      .hasAuthority("partner.manage")
                .requestMatchers("/dispatch/**")       .hasAuthority("dispatch.view")
                .requestMatchers("/delivery-notes/**") .hasAuthority("dispatch.view")
                .requestMatchers("/transfers/**")      .hasAuthority("transfer.view")
                .requestMatchers("/cutting/**")        .hasAuthority("cutting.view")
                .requestMatchers("/damage/**")         .hasAuthority("damage.view")
                .requestMatchers("/tickets/**")        .hasAuthority("ticket.view")
                .requestMatchers("/counts/**")         .hasAuthority("count.view")
                .requestMatchers("/variances/**")      .hasAuthority("count.view")
                .requestMatchers("/stock/**")          .hasAuthority("stock.view")
                .requestMatchers("/daily-close/**")    .hasAuthority("close.view")
                .requestMatchers("/reports/**")        .hasAuthority("report.view")

                // Documents span every document type, so the gate is "can read
                // at least one". Which documents a user may open is for the
                // document service to decide, per type.
                .requestMatchers("/documents/**").hasAnyAuthority(DOCUMENT_VIEW)
                // The approval queue is the reader's own, at every branch they
                // sign at, and its query judges each row by the reader's rights
                // at that row's branch: signed in is enough, even working where
                // they read no document.
                .requestMatchers("/approvals/**").authenticated()

                // /profile/** is everyone's own account: signed in is enough.
                .anyRequest().authenticated())

            .securityContext(context -> context.securityContextRepository(contexts))

            // Before authorization, so a revoked right is already gone when
            // this request is judged, and a deactivated account never is.
            .addFilterBefore(new AccountStateFilter(jdbc, sessions), AuthorizationFilter.class)

            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/", false)
                .failureHandler(signInFailure())
                .permitAll())

            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))

            .sessionManagement(session -> session
                .sessionFixation(fixation -> fixation.migrateSession())
                .maximumSessions(3))

            .exceptionHandling(errors -> errors.accessDeniedHandler(accessDenied()))

            // CSRF stays on. Every mutating request carries the token —
            // forms through the hidden field, htmx through the header set
            // in app.js.
            .csrf(csrf -> {});

        return http.build();
    }

    /** A locked account is told so; every other failure reads the same, so usernames cannot be probed. */
    private static ExceptionMappingAuthenticationFailureHandler signInFailure() {
        var failure = new ExceptionMappingAuthenticationFailureHandler();
        failure.setDefaultFailureUrl("/login?error");
        failure.setExceptionMappings(Map.of(LockedException.class.getName(), "/login?locked"));
        return failure;
    }

    /**
     * A 403. A missing or stale CSRF token is flagged first, so the error
     * page can say the form expired rather than tell someone who holds the
     * right that they lack it.
     */
    private static AccessDeniedHandler accessDenied() {
        var standard = new AccessDeniedHandlerImpl();
        return (request, response, denied) -> {
            if (denied instanceof CsrfException) {
                request.setAttribute(ErrorPages.FORM_EXPIRED, Boolean.TRUE);
            }
            standard.handle(request, response, denied);
        };
    }
}
