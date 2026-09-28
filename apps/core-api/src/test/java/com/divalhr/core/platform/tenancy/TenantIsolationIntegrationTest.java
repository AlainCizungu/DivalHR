package com.divalhr.core.platform.tenancy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tenant-isolation scaffold. Every future tenant-owned endpoint must add the same positive and
 * negative cases.
 */
@IntegrationTest
class TenantIsolationIntegrationTest {

  @Autowired private MockMvc mvc;

  @Test
  void allowsAccessWithinOwnTenant() throws Exception {
    mvc.perform(
            get("/test-support/tenants/{id}/probe", TestTokens.TENANT_A)
                .header("Authorization", bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(TestTokens.TENANT_A.toString()));
  }

  @Test
  void deniesCrossTenantRead() throws Exception {
    mvc.perform(
            get("/test-support/tenants/{id}/probe", TestTokens.TENANT_B)
                .header("Authorization", bearer()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"))
        .andExpect(jsonPath("$.tenantId").doesNotExist());
  }

  @Test
  void neverTrustsTenantSuppliedOnlyInRequestBody() throws Exception {
    mvc.perform(
            post("/test-support/probes")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + TestTokens.TENANT_B + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
  }

  private static String bearer() {
    return "Bearer " + TestTokens.token().tenant(TestTokens.TENANT_A).build();
  }
}
