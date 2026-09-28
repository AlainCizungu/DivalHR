package com.divalhr.core.platform.security;

import com.divalhr.core.platform.web.CorrelationId;
import com.divalhr.core.platform.web.CorsProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Deny-by-default HTTP security. Only the public status endpoint and (when enabled for development
 * and contract verification) the generated API description are anonymous.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  /** Anonymous, browser-callable endpoints. Keep this list minimal. */
  static final String[] PUBLIC_PATHS = {"/api/v1/system/status", "/v3/api-docs", "/v3/api-docs/**"};

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http, JwtAuthenticationConverter converter)
      throws Exception {
    http.csrf(csrf -> csrf.disable()) // Stateless bearer-token API; no cookies are accepted.
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, PUBLIC_PATHS)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(ProblemAuthenticationHandlers.entryPoint())
                    .accessDeniedHandler(ProblemAuthenticationHandlers.accessDeniedHandler()))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(ProblemAuthenticationHandlers.entryPoint())
                    .accessDeniedHandler(ProblemAuthenticationHandlers.accessDeniedHandler()));
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(SecurityProperties properties) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
    decoder.setJwtValidator(DivalJwtValidators.create(properties));
    return decoder;
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
    return converter;
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(properties.allowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
    config.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "Accept-Language", CorrelationId.HEADER));
    config.setExposedHeaders(List.of(CorrelationId.HEADER));
    // Bearer tokens travel in the Authorization header; browser credentials are never needed.
    config.setAllowCredentials(false);
    config.setMaxAge(600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }
}
