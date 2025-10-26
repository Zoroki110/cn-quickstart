package com.digitalasset.quickstart.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

import java.util.List;

@Profile("devnet")
@EnableWebSecurity
@Configuration
public class DevNetSecurityConfig {

  @Value("${security.oauth2.resourceserver.jwt.issuer-uri}")
  private String issuer;

  @Value("${security.oauth2.resourceserver.jwt.jwk-set-uri}")
  private String jwks;

  @Value("${security.oauth2.audience}")
  private String audience;

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(List.of(
      "http://localhost:3000",
      "http://localhost:3001",
      "http://localhost:4001",
      "https://app.clearportx.com",
      "https://clearportx-dex.netlify.app",
      "https://clearportx-staging.netlify.app",
      "https://devnet.clearportx.com",
      "https://clearportx.com",
      "https://nonexplicable-lacily-leesa.ngrok-free.dev"
    ));
    c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Idempotency-Key", "X-Request-ID", "X-Party", "ngrok-skip-browser-warning"));
    c.setExposedHeaders(List.of("Retry-After", "X-Request-ID", "Authorization", "X-Total-Count", "X-Rate-Limit-Remaining"));
    c.setAllowCredentials(true);
    c.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
    s.registerCorsConfiguration("/**", c);
    return s;
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
      .cors(Customizer.withDefaults())
      .csrf(csrf -> csrf.disable())
      .authorizeHttpRequests(auth -> auth
        // TEMPORARILY: Allow all for Netlify testing
        // TODO: Re-enable OAuth2 JWT after CORS is verified
        .anyRequest().permitAll()
      );
      // TEMPORARILY DISABLED:
      // .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));

    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    var withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
    var withAudience = new AudienceValidator(audience);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
    return decoder;
  }

  @Bean
  Auth auth() {
    // DevNet uses local participant without OAuth2 client
    return Auth.SHARED_SECRET;
  }

  @Bean
  TokenProvider tokenProvider() {
    // DevNet local participant doesn't require authentication
    return () -> "";
  }
}
