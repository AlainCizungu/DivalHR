package com.divalhr.core.support;

import com.divalhr.core.platform.security.DivalJwtValidators;
import com.divalhr.core.platform.security.SecurityProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Swaps the JWKS-backed decoder for one bound to the test key, keeping production validators. */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfig {

  @Bean
  @Primary
  JwtDecoder testJwtDecoder(SecurityProperties properties) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestTokens.publicKey()).build();
    decoder.setJwtValidator(DivalJwtValidators.create(properties));
    return decoder;
  }
}
