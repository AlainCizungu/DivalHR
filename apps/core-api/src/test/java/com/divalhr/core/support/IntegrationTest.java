package com.divalhr.core.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;

/** Full application context against a real PostgreSQL container. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    properties = {
      "springdoc.api-docs.enabled=true",
      "divalhr.environment=test",
      "divalhr.pagination.cursor-signing-key=test-only-cursor-signing-key-0000000000000001",
      "divalhr.invitations.email-lookup-key=test-only-email-lookup-key-00000000000000001",
      "divalhr.invitations.jobs.enabled=false",
      "divalhr.identity-provider.admin-base-url=http://localhost:1",
      "divalhr.identity-provider.client-secret=test-only-provisioner-secret",
      "divalhr.mail.host=localhost",
      "divalhr.mail.port=2525",
      "divalhr.mail.from=no-reply@divalhr.test",
      "divalhr.mail.starttls=false",
      "divalhr.rate-limit.per-client-requests=1000",
      "divalhr.rate-limit.global-requests=100000",
      "divalhr.tenant-administration.lock-timeout=3s",
      // MVP-013: the shared test context records every denial of the whole suite; the instance
      // ceiling is exercised by DenialAuditBoundsIntegrationTest with its own small value.
      "divalhr.denial-audit.per-instance-per-minute=100000"
    })
@AutoConfigureMockMvc
@Import({TestSecurityConfig.class, PostgresContainerConfig.class, TestInvitationConfig.class})
@TestExecutionListeners(
    listeners = Memberships.Binding.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface IntegrationTest {}
