package com.divalhr.probe;

import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.security.TenantScoped;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only handlers for MVP-013 (A13-1): method-security provenance. Not part of the API.
 *
 * <p>Deliberately outside {@code com.divalhr.core}: component scanning never registers it, so the
 * application and every other test context keep only production handlers (including the contract
 * rule that privileged handlers use the method-security annotations). Only {@code
 * AuthorizationDenialAuditIntegrationTest} imports it.
 *
 * <p>{@code drift} simulates annotation drift: the scope interceptor requires {@code tenant-admin}
 * (with MFA and membership) while method security requires {@code platform-admin}, so only method
 * security denies. {@code plain} is an authorized privileged handler that throws an ordinary {@link
 * AccessDeniedException}, which must never be taken for drift.
 */
@RestController
public class DenialProbeTestController {

  /** Operation of the drift probe. */
  public static final String DRIFT = "denial-probe.drift";

  /** Operation of the plain-denial probe. */
  public static final String PLAIN = "denial-probe.plain";

  /** Interceptor and method security disagree on purpose. */
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  @TenantScoped(role = "tenant-admin", operation = DRIFT)
  @PreAuthorize("hasRole('platform-admin')")
  public @interface DriftedOperation {}

  /**
   * Never reached: method security denies first.
   *
   * @return nothing useful
   */
  @DriftedOperation
  @GetMapping("/test-support/denial-probes/drift")
  public Map<String, String> drift() {
    return Map.of("reached", "true");
  }

  /**
   * Authorized, then denied by application code.
   *
   * @return never
   */
  @TenantAdminOperation(operation = PLAIN)
  @GetMapping("/test-support/denial-probes/plain")
  public Map<String, String> plain() {
    throw new AccessDeniedException("application-level denial");
  }
}
