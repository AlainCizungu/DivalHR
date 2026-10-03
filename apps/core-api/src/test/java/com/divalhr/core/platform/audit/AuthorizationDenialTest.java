package com.divalhr.core.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.platform.audit.AuthorizationDenial.Scope;
import com.divalhr.core.platform.audit.AuthorizationDenial.Stage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-013 (A13-5): the application invariants mirror the V12 checks. */
class AuthorizationDenialTest {

  private static final UUID TENANT = UUID.randomUUID();

  private static AuthorizationDenial row(
      String actor, String operation, Scope scope, Stage stage, UUID tenant, String correlation) {
    return new AuthorizationDenial(
        UUID.randomUUID(), Instant.now(), actor, operation, scope, stage, tenant, correlation);
  }

  @Test
  void tenantIsPresentExactlyForTenantStagesAfterTheMembershipGate() {
    for (Stage stage : Stage.values()) {
      boolean effective = AuthorizationDenial.EFFECTIVE_TENANT_STAGES.contains(stage);
      if (effective) {
        row("s", "site.list", Scope.TENANT, stage, TENANT, "corr-0001");
        assertThatThrownBy(() -> row("s", "site.list", Scope.TENANT, stage, null, "corr-0001"))
            .isInstanceOf(IllegalArgumentException.class);
      } else {
        row("s", "site.list", Scope.TENANT, stage, null, "corr-0001");
        assertThatThrownBy(() -> row("s", "site.list", Scope.TENANT, stage, TENANT, "corr-0001"))
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void platformRowsNeverHaveATenantAndOnlyPlatformStages() {
    for (Stage stage : Stage.values()) {
      if (AuthorizationDenial.PLATFORM_STAGES.contains(stage)) {
        row("s", "organization.create", Scope.PLATFORM, stage, null, "corr-0001");
        assertThatThrownBy(
                () -> row("s", "organization.create", Scope.PLATFORM, stage, TENANT, "corr-0001"))
            .isInstanceOf(IllegalArgumentException.class);
      } else {
        assertThatThrownBy(
                () -> row("s", "organization.create", Scope.PLATFORM, stage, null, "corr-0001"))
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void theActorIsTheUnchangedVerifiedSubjectOfAnyLength() {
    String long1000 = "s".repeat(1000);
    assertThat(
            row(long1000, "site.list", Scope.TENANT, Stage.ROLE, null, "corr-0001").actorSubject())
        .isEqualTo(long1000);
    assertThat(
            row(" padded ", "site.list", Scope.TENANT, Stage.ROLE, null, "corr-0001")
                .actorSubject())
        .isEqualTo(" padded ");
    for (String bad : new String[] {"", "   ", "\t", "a\u0000b"}) {
      assertThatThrownBy(() -> row(bad, "site.list", Scope.TENANT, Stage.ROLE, null, "corr-0001"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(AuthorizationDenial.eligibleSubject(null)).isFalse();
  }

  @Test
  void operationAndCorrelationFollowTheDatabaseGrammar() {
    assertThatThrownBy(() -> row("s", "Site.List", Scope.TENANT, Stage.ROLE, null, "corr-0001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> row("s", "", Scope.TENANT, Stage.ROLE, null, "corr-0001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> row("s", "site.list", Scope.TENANT, Stage.ROLE, null, "short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> row("s", "site.list", Scope.TENANT, Stage.ROLE, null, "bad id!!"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void databaseValuesAreTheClosedLowerCaseSets() {
    assertThat(Stage.values())
        .extracting(Stage::value)
        .containsExactly(
            "role", "tenant_context", "mfa", "membership", "rate_limit", "method_security");
    assertThat(Scope.values()).extracting(Scope::value).containsExactly("platform", "tenant");
  }
}
