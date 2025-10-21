// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Web security configuration for Canton Network devnet deployment.
 *
 * Features:
 * 1. Basic Auth protection for actuator endpoints (/actuator/**)
 * 2. CORS configuration for frontend integration
 * 3. OAuth2 JWT authentication for API endpoints (existing)
 *
 * Only activated when security.actuator.username and password are configured.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "security.actuator.username")
public class WebSecurityConfig {

    @Value("${security.actuator.username}")
    private String actuatorUsername;

    @Value("${security.actuator.password}")
    private String actuatorPassword;

    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String allowedMethods;

    @Value("${cors.allowed-headers:*}")
    private String allowedHeaders;

    @Value("${cors.allow-credentials:true}")
    private boolean allowCredentials;

    /**
     * Security filter chain for actuator endpoints.
     * Higher precedence (Order 1) to apply Basic Auth before OAuth2.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/**")
            .authorizeHttpRequests(authz -> authz
                .anyRequest().authenticated()
            )
            .httpBasic()
            .and()
            .csrf().disable();  // Actuator endpoints don't need CSRF

        return http.build();
    }

    /**
     * Security filter chain for API endpoints.
     * Lower precedence (Order 2) - uses OAuth2 JWT from existing config.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")
            .cors()  // Enable CORS for API endpoints
            .and()
            .authorizeHttpRequests(authz -> authz
                // Public endpoints
                .requestMatchers("/api/health/**").permitAll()
                .requestMatchers("/api/pools").permitAll()
                // Protected endpoints (require OAuth2 JWT)
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer()
                .jwt()
            .and()
            .and()
            .csrf().disable();  // API uses JWT, not session-based auth

        return http.build();
    }

    /**
     * User details service for Basic Auth (actuator only).
     */
    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails user = User.builder()
            .username(actuatorUsername)
            .password(passwordEncoder().encode(actuatorPassword))
            .roles("ACTUATOR")
            .build();

        return new InMemoryUserDetailsManager(user);
    }

    /**
     * Password encoder for Basic Auth credentials.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS configuration for frontend integration.
     *
     * Allows canton-website to make API calls from different domain.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Parse comma-separated origins
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        configuration.setAllowedOrigins(origins);

        // Parse comma-separated methods
        List<String> methods = Arrays.asList(allowedMethods.split(","));
        configuration.setAllowedMethods(methods);

        // Parse comma-separated headers or allow all with "*"
        if ("*".equals(allowedHeaders)) {
            configuration.addAllowedHeader("*");
        } else {
            List<String> headers = Arrays.asList(allowedHeaders.split(","));
            configuration.setAllowedHeaders(headers);
        }

        configuration.setAllowCredentials(allowCredentials);

        // Apply CORS to all API endpoints
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        return source;
    }
}
