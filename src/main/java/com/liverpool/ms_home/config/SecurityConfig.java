package com.liverpool.ms_home.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline security posture for a stateless, gateway-fronted composition service (Rule 5 + security).
 *
 * <p>Authentication is performed upstream (API gateway / auth service); ms-home derives only the
 * login/guest <em>context</em> from the already-validated token and never trusts a client-asserted
 * identity. This chain therefore focuses on what this service owns: no server session, CSRF disabled
 * (no browser form/session state), and hardened response headers.</p>
 *
 * <p>Actuator endpoints are served on a dedicated management port ({@code MANAGEMENT_PORT}, default
 * 8081) and are therefore unreachable via the API gateway. The {@code /actuator/**} deny rule below
 * is a belt-and-suspenders guard for environments where management and application ports coincide.</p>
 */
@Configuration
public class SecurityConfig {

    /**
     * Defines the stateless filter chain with secure response headers.
     *
     * @param http the security builder
     * @return the configured filter chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().permitAll())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(opts -> {
                        })
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        .referrerPolicy(referrer -> referrer
                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }
}
