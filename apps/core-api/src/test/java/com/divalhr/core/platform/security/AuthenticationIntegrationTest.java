package com.divalhr.core.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.TestTokens;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AuthenticationIntegrationTest {

  @Autowired private MockMvc mvc;

  @Test
  void rejectsMissingToken() throws Exception {
    mvc.perform(get("/api/v1/session"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void acceptsValidTokenAndReturnsOnlyTenantAndKnownRoles() throws Exception {
    String token = TestTokens.token().roles(List.of("tenant-admin", "offline_access")).build();
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(TestTokens.TENANT_A.toString()))
        .andExpect(jsonPath("$.roles").value(List.of("tenant-admin")))
        .andExpect(jsonPath("$.sub").doesNotExist());
  }

  @Test
  void rejectsWrongAudience() throws Exception {
    assertUnauthorized(TestTokens.token().audience("account").build());
  }

  @Test
  void rejectsWrongIssuer() throws Exception {
    assertUnauthorized(TestTokens.token().issuer("http://evil.example/realms/x").build());
  }

  @Test
  void rejectsExpiredToken() throws Exception {
    assertUnauthorized(TestTokens.token().expiresAt(Instant.now().minusSeconds(600)).build());
  }

  @Test
  void rejectsTamperedToken() throws Exception {
    String token = TestTokens.token().build();
    assertUnauthorized(token.substring(0, token.length() - 4) + "AAAA");
  }

  @Test
  void rejectsTokenWithoutTenantClaim() throws Exception {
    String token = TestTokens.token().tenant(null).build();
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
  }

  @Test
  void corsAllowsOnlyExplicitOrigins() throws Exception {
    mvc.perform(
            options("/api/v1/system/status")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

    mvc.perform(
            options("/api/v1/system/status")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  private void assertUnauthorized(String token) throws Exception {
    mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }
}
