package com.divalhr.core.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.internal.JdbcAccessLinkRepository.Binding;
import com.divalhr.core.identity.internal.JdbcAccessLinkRepository.RevocationRow;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A22-5 revalidation rules and the D22-14 backoff, without a database. */
class AccessRevocationJobsTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID EMPLOYEE = UUID.randomUUID();
  private static final UUID MEMBERSHIP = UUID.randomUUID();

  private static RevocationRow row() {
    return new RevocationRow(
        UUID.randomUUID(),
        new TenantId(TENANT),
        MEMBERSHIP,
        "employee",
        UUID.randomUUID(),
        EMPLOYEE,
        UUID.randomUUID(),
        Instant.now(),
        "IDP_PENDING",
        0,
        3,
        true,
        true);
  }

  private static Binding binding(
      UUID linkTenant,
      UUID linkEmployee,
      UUID linkMembership,
      boolean active,
      UUID membershipTenant,
      String role,
      String subject) {
    return new Binding(
        linkTenant, linkEmployee, linkMembership, active, membershipTenant, role, subject);
  }

  @Test
  void onlyTheOriginalActiveLinkAndEmployeeMembershipOfTheTenantMayReachTheProvider() {
    RevocationRow row = row();
    assertThat(
            AccessRevocationJobs.refusal(
                row, binding(TENANT, EMPLOYEE, MEMBERSHIP, true, TENANT, "employee", "sub")))
        .isNull();
    assertThat(
            AccessRevocationJobs.refusal(
                row, binding(null, null, null, false, TENANT, "employee", "sub")))
        .isEqualTo("STALE_LINK");
    assertThat(
            AccessRevocationJobs.refusal(
                row, binding(TENANT, EMPLOYEE, MEMBERSHIP, false, TENANT, "employee", "sub")))
        .isEqualTo("STALE_LINK");
    assertThat(
            AccessRevocationJobs.refusal(
                row,
                binding(TENANT, UUID.randomUUID(), MEMBERSHIP, true, TENANT, "employee", "sub")))
        .isEqualTo("STALE_LINK");
    assertThat(
            AccessRevocationJobs.refusal(
                row, binding(TENANT, EMPLOYEE, UUID.randomUUID(), true, TENANT, "employee", "sub")))
        .isEqualTo("STALE_LINK");
    assertThat(
            AccessRevocationJobs.refusal(
                row,
                binding(UUID.randomUUID(), EMPLOYEE, MEMBERSHIP, true, TENANT, "employee", "sub")))
        .isEqualTo("STALE_LINK");
    assertThat(
            AccessRevocationJobs.refusal(
                row, binding(TENANT, EMPLOYEE, MEMBERSHIP, true, null, null, null)))
        .isEqualTo("MEMBERSHIP_CHANGED");
    assertThat(
            AccessRevocationJobs.refusal(
                row,
                binding(TENANT, EMPLOYEE, MEMBERSHIP, true, UUID.randomUUID(), "employee", "sub")))
        .isEqualTo("TENANT_MISMATCH");
    assertThat(
            AccessRevocationJobs.refusal(
                row, binding(TENANT, EMPLOYEE, MEMBERSHIP, true, TENANT, "tenant-admin", "sub")))
        .isEqualTo("MEMBERSHIP_CHANGED");
    assertThat(
            AccessRevocationJobs.refusal(
                row, binding(TENANT, EMPLOYEE, MEMBERSHIP, true, TENANT, "employee", " ")))
        .isEqualTo("MEMBERSHIP_CHANGED");
  }

  @Test
  void backoffDoublesUpToTheCeilingAndSettingsAreBounded() {
    AccessRevocationProperties defaults =
        new AccessRevocationProperties(null, null, null, null, null);
    assertThat(defaults.maxAttempts()).isEqualTo(10);
    assertThat(defaults.backoff(1)).isEqualTo(Duration.ofMinutes(1));
    assertThat(defaults.backoff(2)).isEqualTo(Duration.ofMinutes(2));
    assertThat(defaults.backoff(6)).isEqualTo(Duration.ofMinutes(32));
    assertThat(defaults.backoff(7)).isEqualTo(Duration.ofMinutes(60));
    assertThat(defaults.backoff(10)).isEqualTo(Duration.ofMinutes(60));
    assertThatThrownBy(() -> new AccessRevocationProperties(null, null, 11, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessRevocationProperties(0, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new AccessRevocationProperties(null, Duration.ofSeconds(5), null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
