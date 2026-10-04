package com.divalhr.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RevocationPolicyTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-00000000000a");
  private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-00000000000b");
  private static final String INVITATION = "00000000-0000-4000-8000-0000000000c1";

  private static RevocationPolicy.Identity employee() {
    return new RevocationPolicy.Identity(
        List.of(INVITATION), TENANT, false, false, 1, Optional.of(InvitationRole.EMPLOYEE));
  }

  private static RevocationPolicy.Identity with(
      List<String> invitations,
      UUID tenant,
      boolean serviceAccount,
      boolean platformAdmin,
      long groups,
      Optional<InvitationRole> role) {
    return new RevocationPolicy.Identity(
        invitations, tenant, serviceAccount, platformAdmin, groups, role);
  }

  @Test
  void disablesOnlyAnEmployeeIdentityThisExtensionCreatedForTheTenant() {
    assertThat(RevocationPolicy.revocable(employee(), TENANT)).isTrue();
  }

  @Test
  void refusesEveryOtherIdentity() {
    Optional<InvitationRole> employee = Optional.of(InvitationRole.EMPLOYEE);
    assertThat(RevocationPolicy.revocable(employee(), OTHER)).as("other tenant").isFalse();
    assertThat(
            RevocationPolicy.revocable(with(List.of(), TENANT, false, false, 1, employee), TENANT))
        .as("not created by the extension")
        .isFalse();
    assertThat(
            RevocationPolicy.revocable(
                with(List.of(INVITATION, INVITATION), TENANT, false, false, 1, employee), TENANT))
        .as("two invitation values")
        .isFalse();
    assertThat(
            RevocationPolicy.revocable(
                with(List.of(INVITATION.toUpperCase()), TENANT, false, false, 1, employee), TENANT))
        .as("non-canonical invitation value")
        .isFalse();
    assertThat(
            RevocationPolicy.revocable(
                with(List.of(INVITATION), null, false, false, 1, employee), TENANT))
        .as("no tenant")
        .isFalse();
    assertThat(
            RevocationPolicy.revocable(
                with(List.of(INVITATION), TENANT, true, false, 1, employee), TENANT))
        .as("service account")
        .isFalse();
    assertThat(
            RevocationPolicy.revocable(
                with(List.of(INVITATION), TENANT, false, true, 1, employee), TENANT))
        .as("platform admin")
        .isFalse();
    assertThat(
            RevocationPolicy.revocable(
                with(List.of(INVITATION), TENANT, false, false, 2, employee), TENANT))
        .as("two groups")
        .isFalse();
    assertThat(
            RevocationPolicy.revocable(
                with(List.of(INVITATION), TENANT, false, false, 0, employee), TENANT))
        .as("no group")
        .isFalse();
    assertThat(
            RevocationPolicy.revocable(
                with(
                    List.of(INVITATION),
                    TENANT,
                    false,
                    false,
                    1,
                    Optional.of(InvitationRole.TENANT_ADMIN)),
                TENANT))
        .as("tenant admin")
        .isFalse();
    assertThat(
            RevocationPolicy.revocable(
                with(List.of(INVITATION), TENANT, false, false, 1, Optional.empty()), TENANT))
        .as("no single role")
        .isFalse();
  }

  @Test
  void pathValuesMustBeCanonicalAndTheBodyEmpty() {
    assertThat(code(() -> RequestBodies.canonicalId(INVITATION))).isEqualTo("accepted");
    assertThat(code(() -> RequestBodies.canonicalId(INVITATION.toUpperCase())))
        .isEqualTo("INVALID_REQUEST/400");
    assertThat(code(() -> RequestBodies.canonicalId("not-a-uuid")))
        .isEqualTo("INVALID_REQUEST/400");
    assertThat(code(() -> RequestBodies.canonicalId(null))).isEqualTo("INVALID_REQUEST/400");
    assertThat(code(() -> RequestBodies.requireEmpty(-1, null))).isEqualTo("accepted");
    assertThat(code(() -> RequestBodies.requireEmpty(0, new ByteArrayInputStream(new byte[0]))))
        .isEqualTo("accepted");
    assertThat(code(() -> RequestBodies.requireEmpty(2, null))).isEqualTo("INVALID_REQUEST/400");
    assertThat(
            code(
                () ->
                    RequestBodies.requireEmpty(
                        -1, new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)))))
        .isEqualTo("INVALID_REQUEST/400");
  }

  private static String code(ThrowingCall call) {
    try {
      call.run();
    } catch (RequestBodies.Rejected rejected) {
      return rejected.code() + "/" + rejected.status();
    }
    return "accepted";
  }

  @FunctionalInterface
  interface ThrowingCall {
    void run() throws RequestBodies.Rejected;
  }
}
