package com.divalhr.keycloak.provisioning;

import static com.divalhr.keycloak.provisioning.InvitationRole.EMPLOYEE;
import static com.divalhr.keycloak.provisioning.InvitationRole.TENANT_ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A1: only the proven role-specific terminal state is COMPLETED; drift is INVALID. */
class SetupStateTest {

  private static SetupState.Snapshot of(
      Set<InvitationRole> groups, Map<String, Long> credentials, Set<String> actions) {
    return new SetupState.Snapshot(groups, credentials, actions);
  }

  @Test
  void employeeStates() {
    assertThat(
            SetupState.classify(
                EMPLOYEE, of(Set.of(EMPLOYEE), Map.of(), Set.of("UPDATE_PASSWORD"))))
        .isEqualTo(SetupState.PRISTINE);
    assertThat(
            SetupState.classify(EMPLOYEE, of(Set.of(EMPLOYEE), Map.of("password", 1L), Set.of())))
        .isEqualTo(SetupState.COMPLETED);
    // Action removed without its credential.
    assertThat(SetupState.classify(EMPLOYEE, of(Set.of(EMPLOYEE), Map.of(), Set.of())))
        .isEqualTo(SetupState.INVALID);
    // Credential present while the action is still pending.
    assertThat(
            SetupState.classify(
                EMPLOYEE, of(Set.of(EMPLOYEE), Map.of("password", 1L), Set.of("UPDATE_PASSWORD"))))
        .isEqualTo(SetupState.INVALID);
    // Unexpected credential type, count or action.
    assertThat(
            SetupState.classify(
                EMPLOYEE, of(Set.of(EMPLOYEE), Map.of("password", 1L, "otp", 1L), Set.of())))
        .isEqualTo(SetupState.INVALID);
    assertThat(
            SetupState.classify(EMPLOYEE, of(Set.of(EMPLOYEE), Map.of("password", 2L), Set.of())))
        .isEqualTo(SetupState.INVALID);
    assertThat(
            SetupState.classify(
                EMPLOYEE,
                of(Set.of(EMPLOYEE), Map.of(), Set.of("UPDATE_PASSWORD", "VERIFY_EMAIL"))))
        .isEqualTo(SetupState.INVALID);
    assertThat(
            SetupState.classify(
                EMPLOYEE, of(Set.of(EMPLOYEE), Map.of("password", 1L, "webauthn", 1L), Set.of())))
        .isEqualTo(SetupState.INVALID);
  }

  @Test
  void tenantAdministratorStates() {
    Set<InvitationRole> admin = Set.of(TENANT_ADMIN);
    assertThat(
            SetupState.classify(
                TENANT_ADMIN, of(admin, Map.of(), Set.of("UPDATE_PASSWORD", "CONFIGURE_TOTP"))))
        .isEqualTo(SetupState.PRISTINE);
    // Consistently in progress: the password is set, the authenticator still pending.
    assertThat(
            SetupState.classify(
                TENANT_ADMIN, of(admin, Map.of("password", 1L), Set.of("CONFIGURE_TOTP"))))
        .isEqualTo(SetupState.PENDING);
    assertThat(
            SetupState.classify(
                TENANT_ADMIN, of(admin, Map.of("otp", 1L), Set.of("UPDATE_PASSWORD"))))
        .isEqualTo(SetupState.PENDING);
    assertThat(
            SetupState.classify(
                TENANT_ADMIN, of(admin, Map.of("password", 1L, "otp", 1L), Set.of())))
        .isEqualTo(SetupState.COMPLETED);
    // Only one required credential and nothing pending: partial, never completed.
    assertThat(SetupState.classify(TENANT_ADMIN, of(admin, Map.of("password", 1L), Set.of())))
        .isEqualTo(SetupState.INVALID);
    assertThat(SetupState.classify(TENANT_ADMIN, of(admin, Map.of("otp", 1L), Set.of())))
        .isEqualTo(SetupState.INVALID);
    // Actions removed without credentials.
    assertThat(SetupState.classify(TENANT_ADMIN, of(admin, Map.of(), Set.of())))
        .isEqualTo(SetupState.INVALID);
    assertThat(SetupState.classify(TENANT_ADMIN, of(admin, Map.of(), Set.of("CONFIGURE_TOTP"))))
        .isEqualTo(SetupState.INVALID);
    // Two authenticators.
    assertThat(
            SetupState.classify(
                TENANT_ADMIN, of(admin, Map.of("password", 1L, "otp", 2L), Set.of())))
        .isEqualTo(SetupState.INVALID);
  }

  @Test
  void roleOrGroupDriftIsInvalid() {
    assertThat(
            SetupState.classify(
                TENANT_ADMIN, of(Set.of(EMPLOYEE), Map.of("password", 1L), Set.of())))
        .isEqualTo(SetupState.INVALID);
    assertThat(
            SetupState.classify(
                EMPLOYEE, of(Set.of(EMPLOYEE, TENANT_ADMIN), Map.of(), Set.of("UPDATE_PASSWORD"))))
        .isEqualTo(SetupState.INVALID);
    assertThat(SetupState.classify(EMPLOYEE, of(Set.of(), Map.of(), Set.of("UPDATE_PASSWORD"))))
        .isEqualTo(SetupState.INVALID);
  }
}
