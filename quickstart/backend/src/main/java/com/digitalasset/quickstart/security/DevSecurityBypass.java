package com.digitalasset.quickstart.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEMPORARY: Bypass security for debugging pool creation.
 * REMOVE THIS BEFORE PRODUCTION!
 */
@Configuration
@ConditionalOnProperty(name = "security.bypass.enabled", havingValue = "true")
public class DevSecurityBypass {

    @Bean
    @Order(1)  // Higher priority than other security configs
    public SecurityFilterChain bypassSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/**")
            .authorizeHttpRequests(authz -> authz.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
