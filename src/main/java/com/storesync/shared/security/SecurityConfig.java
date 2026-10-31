package com.storesync.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storesync.shared.web.ProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Bearer tokens on everything under /v1; health and metrics are open for probes and scraping. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectMapper json) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> { })
                        .authenticationEntryPoint((req, res, ex) -> ProblemWriter.write(res, json,
                                HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ProblemWriter.write(res, json,
                                HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required"))
                        .accessDeniedHandler((req, res, ex) -> ProblemWriter.write(res, json,
                                HttpStatus.FORBIDDEN, "forbidden", "Not allowed")));
        return http.build();
    }
}
