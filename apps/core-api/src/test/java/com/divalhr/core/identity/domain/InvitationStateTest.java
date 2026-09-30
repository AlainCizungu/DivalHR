package com.divalhr.core.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class InvitationStateTest {

  private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

  @Test
  void publicStatusHidesAcceptingAndDerivesExpiry() {
    Instant future = NOW.plusSeconds(60);
    Instant past = NOW.minusSeconds(1);
    assertThat(InvitationState.PENDING.publicStatus(future, NOW))
        .isEqualTo(InvitationStatus.PENDING);
    assertThat(InvitationState.PENDING.publicStatus(past, NOW)).isEqualTo(InvitationStatus.EXPIRED);
    assertThat(InvitationState.PENDING.publicStatus(NOW, NOW)).isEqualTo(InvitationStatus.EXPIRED);
    assertThat(InvitationState.ACCEPTING.publicStatus(past, NOW))
        .isEqualTo(InvitationStatus.PENDING);
    assertThat(InvitationState.ACCEPTED.publicStatus(future, NOW))
        .isEqualTo(InvitationStatus.ACCEPTED);
    assertThat(InvitationState.REVOKED.publicStatus(future, NOW))
        .isEqualTo(InvitationStatus.REVOKED);
    assertThat(InvitationState.EXPIRED.publicStatus(future, NOW))
        .isEqualTo(InvitationStatus.EXPIRED);
  }

  @Test
  void onlyTenantRolesExist() {
    assertThat(Arrays.stream(TenantRole.values()).map(TenantRole::wireName))
        .containsExactlyInAnyOrder("tenant-admin", "employee");
    assertThat(TenantRole.fromWire("platform-admin")).isEmpty();
    assertThat(TenantRole.fromWire("TENANT-ADMIN")).isEmpty();
    assertThat(TenantRole.fromWire("tenant-admin")).contains(TenantRole.TENANT_ADMIN);
    assertThat(InvitationLocale.fromTag("de")).isEmpty();
  }
}
