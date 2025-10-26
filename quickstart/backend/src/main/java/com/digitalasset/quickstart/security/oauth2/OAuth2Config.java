// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.security.oauth2;

import com.digitalasset.quickstart.security.Auth;
import com.digitalasset.quickstart.security.PartyAuthority;
import com.digitalasset.quickstart.security.TenantAuthority;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

@Configuration
@EnableWebSecurity
@Profile("oauth2")
public class OAuth2Config {

    @Value("${application.tenants.AppProvider.partyId}")
    private String partyId;

    @Value("${application.tenants.AppProvider.tenantId}")
    private String tenantId;

    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:3001,http://localhost:4001}")
    private String allowedOrigins;

    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String allowedMethods;

    @Value("${cors.allowed-headers:Authorization,Content-Type,X-Idempotency-Key,X-Request-ID}")
    private String allowedHeaders;

    @Value("${cors.exposed-headers:Retry-After,X-Request-ID}")
    private String exposedHeaders;

    @Value("${cors.allow-credentials:true}")
    private boolean allowCredentials;

    @Value("${cors.max-age:3600}")
    private long maxAge;

    private final OAuth2AuthenticationSuccessHandler authenticationSuccessHandler;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final OAuth2AuthorizedClientService authorizedClientService;

    public OAuth2Config(OAuth2AuthenticationSuccessHandler authenticationSuccessHandler, ClientRegistrationRepository clientRegistrationRepository, OAuth2AuthorizedClientService authorizedClientService) {
        this.authenticationSuccessHandler = authenticationSuccessHandler;
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.authorizedClientService = authorizedClientService;
    }

    @Bean
    public Auth auth() {
        return Auth.OAUTH2;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())  // Enable CORS with corsConfigurationSource bean
                .csrf((csrf) -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/clearportx/**")  // TODO: Add CSRF back with proper token handling
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/user", "/login-links", "/feature-flags", "/oauth2/authorization/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/logout").permitAll()
                        .requestMatchers("/api/health/**").permitAll()  // Health endpoints are public
                        .requestMatchers("/api/actuator/**").permitAll()  // Metrics endpoints are public (Prometheus scraping)
                        .requestMatchers("/api/clearportx/**").permitAll()  // TODO: Add proper admin auth after testing
                        .requestMatchers("/api/pools").permitAll()  // Pools endpoint is public (read-only)
                        .requestMatchers("/api/tokens/*").permitAll()  // TEMPORARY: Public tokens for testing (/api/tokens/alice)
                        // Ledger API endpoints require JWT authentication (party extracted from JWT sub)
                        .requestMatchers("/api/tokens").authenticated()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.getWriter().write("Unauthorized");
                        })
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        // Exclude actuator endpoints from JWT validation
                        .bearerTokenResolver(request -> {
                            String path = request.getRequestURI();
                            if (path.startsWith("/api/actuator/") || path.startsWith("/api/health/")) {
                                return null;  // No bearer token required for metrics/health
                            }
                            // Default bearer token extraction for other endpoints
                            String authorization = request.getHeader("Authorization");
                            if (authorization != null && authorization.startsWith("Bearer ")) {
                                return authorization.substring(7);
                            }
                            return null;
                        })
                )
                .oauth2Login(oauth2 ->
                        oauth2.defaultSuccessUrl("/", true)
                                .successHandler(authenticationSuccessHandler)
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                            .logoutSuccessHandler(oidcLogoutSuccessHandler())
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                );
        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new Converter<>() {
            private final JwtGrantedAuthoritiesConverter defaultGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();

            @Override
            public Collection<GrantedAuthority> convert(Jwt jwt) {
                Collection<GrantedAuthority> authorities = new HashSet<>(defaultGrantedAuthoritiesConverter.convert(jwt));
                // there is only one AppProvider issuer that can issue JWT to authenticate to ResourceServer
                // we consider anybody with JWT from that issuer to be admin
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                authorities.add(new PartyAuthority(partyId));
                authorities.add(new TenantAuthority(tenantId));
                return authorities;
            }
        });
        return converter;
    }

    private LogoutSuccessHandler oidcLogoutSuccessHandler() {
        return new OidcClientInitiatedLogoutSuccessHandler(this.clientRegistrationRepository);
    }

    @Bean
    @Primary
    public OAuth2AuthorizedClientManager multiGrantTypeClientManager() {
        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials()
                        .authorizationCode()
                        .refreshToken()
                        .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);

        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);
        return authorizedClientManager;
    }

    /**
     * Custom JWT decoder that accepts multiple issuer URLs.
     * This is needed because Keycloak may issue JWTs with different issuer claims
     * depending on how it's accessed (keycloak, keycloak.localhost, localhost).
     */
    @Bean
    public org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
        // Accept multiple issuer URLs (keycloak, keycloak.localhost, localhost)
        String keycloakBaseUrl = System.getenv("AUTH_APP_PROVIDER_ISSUER_URL");
        if (keycloakBaseUrl == null) {
            keycloakBaseUrl = "http://keycloak:8082/realms/AppProvider";
        }

        // Create decoder using JWK Set URI (public keys endpoint)
        // Note: We use keycloak:8082 for the JWK URI since backend runs in Docker network
        String jwkSetUri = "http://keycloak:8082/realms/AppProvider/protocol/openid-connect/certs";

        org.springframework.security.oauth2.jwt.NimbusJwtDecoder jwtDecoder =
            org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        // Custom validator that accepts multiple issuers
        java.util.Set<String> allowedIssuers = new HashSet<>(java.util.Arrays.asList(
            "http://keycloak:8082/realms/AppProvider",
            "http://keycloak.localhost:8082/realms/AppProvider",
            "http://localhost:8082/realms/AppProvider"
        ));

        org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> multiIssuerValidator = token -> {
            String issuer = token.getIssuer() != null ? token.getIssuer().toString() : null;
            if (issuer != null && allowedIssuers.contains(issuer)) {
                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
            }
            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                new org.springframework.security.oauth2.core.OAuth2Error(
                    "invalid_token",
                    "The iss claim is not valid. Expected one of: " + allowedIssuers + ", but got: " + issuer,
                    null
                )
            );
        };

        org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> validator =
            new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                new org.springframework.security.oauth2.jwt.JwtTimestampValidator(),
                multiIssuerValidator
            );

        jwtDecoder.setJwtValidator(validator);
        return jwtDecoder;
    }

    /**
     * CORS configuration for frontend integration.
     * Allows requests from localhost:3000, localhost:3001, localhost:4001
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

        // Parse comma-separated allowed headers
        List<String> headers = Arrays.asList(allowedHeaders.split(","));
        configuration.setAllowedHeaders(headers);

        // Parse comma-separated exposed headers
        List<String> exposed = Arrays.asList(exposedHeaders.split(","));
        configuration.setExposedHeaders(exposed);

        // Allow credentials (cookies, authorization headers)
        configuration.setAllowCredentials(allowCredentials);

        // Preflight cache duration (in seconds)
        configuration.setMaxAge(maxAge);

        // Apply CORS to all endpoints
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}