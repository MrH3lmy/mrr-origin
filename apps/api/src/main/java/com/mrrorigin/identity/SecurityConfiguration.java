package com.mrrorigin.identity;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /**
     * {@code /actuator/prometheus} is permitAll alongside health/info for the same structural reason:
     * a Prometheus-compatible scraper is not a workspace member and cannot present a user JWT
     * (there is no operator/service-account identity in this system's auth model -- see
     * docs/security/threat-model.md #4). Per docs/operations/observability-runbook.md, this endpoint
     * exposes only aggregate operational counters/gauges/timers with no workspace/customer/tenant
     * identifiers in any label, so the exposure is scoped the same way health/info already are; the
     * access-control boundary for this endpoint in production is deployment-level network isolation
     * (documented explicitly, not enforced in code), never a hardcoded shared secret.
     */
    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/error",
                                "/api/public/v1/events",
                                "/api/stripe/connections/oauth/callback",
                                "/api/stripe/webhooks/*")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(withDefaults()));

        return http.build();
    }
}
