package com.divalhr.core.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.api.CreateInvitationRequest;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.InvitationStatus;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvitationValidatorTest {

  private static final String KEY = "key-0000000000000001";
  private final InvitationValidator validator = new InvitationValidator();

  @Test
  void normalizesAValidRequest() {
    InvitationValidator.CreateCommand command =
        validator.create(KEY, CreateInvitationRequest.of(" Ana@Example.CD ", "tenant-admin", "en"));
    assertThat(command.email().value()).isEqualTo("ana@example.cd");
    assertThat(command.role()).isEqualTo(TenantRole.TENANT_ADMIN);
    assertThat(command.locale()).isEqualTo(InvitationLocale.EN);
  }

  @Test
  void everyFieldProblemIsReportedTogetherWithoutValues() {
    ApiException failure =
        (ApiException)
            catchThrowable(
                () ->
                    validator.create(
                        "bad key", CreateInvitationRequest.of("nope", "platform-admin", "sw")));
    assertThat(failure.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    @SuppressWarnings("unchecked")
    List<Map<String, String>> fields = (List<Map<String, String>>) failure.params().get("fields");
    assertThat(fields)
        .containsExactlyInAnyOrder(
            Map.of("field", "Idempotency-Key", "constraint", "FORMAT"),
            Map.of("field", "email", "constraint", "FORMAT"),
            Map.of("field", "role", "constraint", "FORMAT"),
            Map.of("field", "locale", "constraint", "FORMAT"));
    assertThat(failure.params().toString()).doesNotContain("nope").doesNotContain("platform-admin");
  }

  @Test
  void requiredFieldsAndListParameters() {
    assertThatThrownBy(() -> validator.create(KEY, CreateInvitationRequest.of(null, null, null)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.params().toString()).contains("REQUIRED").doesNotContain("FORMAT"));
    assertThat(validator.list(null, null)).isEqualTo(new InvitationValidator.ListQuery(null, 50));
    assertThat(validator.list("REVOKED", "10").status()).isEqualTo(InvitationStatus.REVOKED);
    assertThatThrownBy(() -> validator.list("revoked", "0")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> validator.invitationId(null, false, "x"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> validator.invitationId(null, true, java.util.UUID.randomUUID().toString()))
        .isInstanceOf(ApiException.class);
  }

  private static Throwable catchThrowable(Runnable runnable) {
    try {
      runnable.run();
    } catch (RuntimeException thrown) {
      return thrown;
    }
    throw new AssertionError("expected a failure");
  }
}
