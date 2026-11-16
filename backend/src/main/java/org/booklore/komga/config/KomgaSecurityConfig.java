package org.booklore.komga.config;

import jakarta.servlet.http.HttpServletResponse;
import org.booklore.komga.core.KomgaUserGateway;
import org.booklore.komga.session.KomgaSessionFilter;
import org.booklore.komga.session.KomgaSessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Basic authentication chain of the Komga API, with the OPDS accounts (same authentication manager
 * as the OPDS chain, defined in the core {@code SecurityConfig}). Order 2: right after the OPDS chain,
 * before the JWT chain that also matches {@code /komga/**}.
 * <p>
 * Stateless for Spring (no HTTP session), but with Komga's own {@code KOMGA-SESSION} cookie, limited to
 * {@code /komga/api} (see {@link KomgaSessionFilter}): the Mihon tracker needs it.
 */
@ConditionalOnKomgaEnabled
@Configuration
public class KomgaSecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain komgaBasicAuthSecurityChain(HttpSecurity http, KomgaSessionStore sessionStore,
                                                           KomgaUserGateway userGateway) throws Exception {
        http
                .securityMatcher("/komga/api/v1/**", "/komga/api/v2/**")
                // Spring Security enables CORS by default when a CorsConfigurationSource bean is visible.
                // It was not visible while this chain lived in SecurityConfig, so keep it off as before.
                .cors(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated()
                )
                .httpBasic(basic -> basic
                        .realmName("Grimmory Komga API")
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setHeader("WWW-Authenticate", "Basic realm=\"Grimmory Komga API\"");
                            response.getWriter().write("HTTP Status 401 - " + authException.getMessage());
                        })
                )
                .addFilterBefore(KomgaSessionFilter.restore(sessionStore, userGateway), BasicAuthenticationFilter.class)
                .addFilterAfter(KomgaSessionFilter.open(sessionStore), BasicAuthenticationFilter.class);

        return http.build();
    }
}
