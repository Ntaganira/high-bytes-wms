package heritier.ntaganira.highbytes.wms.config;

import heritier.ntaganira.highbytes.wms.security.AppUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           AppUserDetailsService userDetailsService) throws Exception {
        http
            .userDetailsService(userDetailsService)

            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/webjars/**", "/favicon.ico").permitAll()
                .requestMatchers("/login", "/error").permitAll()
                .requestMatchers("/actuator/health").permitAll()

                .requestMatchers("/admin/users/**")     .hasAuthority("admin.users")
                .requestMatchers("/admin/roles/**")     .hasAuthority("admin.roles")
                .requestMatchers("/admin/workflows/**") .hasAuthority("admin.workflow")
                .requestMatchers("/admin/branches/**")  .hasAuthority("admin.branches")
                .requestMatchers("/admin/audit/**")     .hasAuthority("audit.view")

                .requestMatchers("/receiving/**")  .hasAuthority("receiving.view")
                .requestMatchers("/dispatch/**")   .hasAuthority("dispatch.view")
                .requestMatchers("/transfers/**")  .hasAuthority("transfer.view")
                .requestMatchers("/cutting/**")    .hasAuthority("cutting.view")
                .requestMatchers("/damage/**")     .hasAuthority("damage.view")
                .requestMatchers("/counts/**")     .hasAuthority("count.view")
                .requestMatchers("/stock/**")      .hasAuthority("stock.view")

                .anyRequest().authenticated())

            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/", false)
                .failureUrl("/login?error")
                .permitAll())

            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))

            .sessionManagement(session -> session
                .sessionFixation(fixation -> fixation.migrateSession())
                .maximumSessions(3))

            // CSRF stays on. Every mutating request carries the token —
            // forms through the hidden field, htmx through the header set
            // in app.js.
            .csrf(csrf -> {});

        return http.build();
    }
}
