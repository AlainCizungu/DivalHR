package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.tenant.api.CreateOrganizationRequest;
import com.divalhr.core.tenant.application.CreateOrganizationCommand;
import com.divalhr.core.tenant.application.OrganizationValidator;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrganizationValidatorTest {

  private static final String KEY = "key-0123456789abcdef";
  private final OrganizationValidator validator = new OrganizationValidator();

  private static CreateOrganizationRequest request(
      String name, String country, String locale, String timezone, String... currencies) {
    return CreateOrganizationRequest.of(
        name, country, locale, timezone, currencies == null ? null : Arrays.asList(currencies));
  }

  private static CreateOrganizationRequest valid() {
    return request("  Hôpital Saint-Joseph  ", "CD", "fr", "Africa/Kinshasa", "USD", "CDF");
  }

  @Test
  void normalizesAValidRequest() {
    CreateOrganizationCommand command = validator.validate(KEY, valid());
    assertThat(command.name()).isEqualTo("Hôpital Saint-Joseph");
    assertThat(command.currencies()).containsExactly("CDF", "USD");
    assertThat(command.timezone()).isEqualTo("Africa/Kinshasa");
  }

  @Test
  void acceptsLubumbashiAndSingleCurrency() {
    CreateOrganizationCommand command =
        validator.validate(KEY, request("École Lumière", "CD", "en", "Africa/Lubumbashi", "CDF"));
    assertThat(command.currencies()).containsExactly("CDF");
  }

  @Test
  void countsUnicodeCodePointsForLength() {
    // 160 characters outside the Basic Multilingual Plane is still within the limit.
    String longest = "𝔸".repeat(160);
    assertThat(validator.validate(KEY, request(longest, "CD", "fr", "Africa/Kinshasa", "CDF")).name())
        .isEqualTo(longest);
    assertFields(request("𝔸".repeat(161), "CD", "fr", "Africa/Kinshasa", "CDF"), "name", "LENGTH");
    assertFields(request(" A ", "CD", "fr", "Africa/Kinshasa", "CDF"), "name", "LENGTH");
  }

  @Test
  void reportsEveryFormatErrorTogetherWithoutEchoingValues() {
    CreateOrganizationRequest request = request(" ", "cd", "FR", "Mars/Olympus", "usd", "usd");
    ApiException error = catchApi(null, request);
    assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(fields(error))
        .containsExactly(
            Map.of("field", "Idempotency-Key", "constraint", "REQUIRED"),
            Map.of("field", "name", "constraint", "REQUIRED"),
            Map.of("field", "countryCode", "constraint", "FORMAT"),
            Map.of("field", "defaultLocale", "constraint", "FORMAT"),
            Map.of("field", "timezone", "constraint", "FORMAT"),
            Map.of("field", "currencies", "constraint", "FORMAT"));
    assertThat(error.params().toString()).doesNotContain("Mars", "usd", "cd", "FR");
  }

  @ParameterizedTest
  @ValueSource(strings = {"short", "has space in key 123", "é-accent-not-allowed-123"})
  void rejectsMalformedIdempotencyKeys(String key) {
    ApiException error = catchApi(key, valid());
    assertThat(fields(error)).containsExactly(Map.of("field", "Idempotency-Key", "constraint", "FORMAT"));
  }

  @Test
  void rejectsControlCharactersInName() {
    assertFields(request("Bad\nName", "CD", "fr", "Africa/Kinshasa", "CDF"), "name", "FORMAT");
  }

  @Test
  void rejectsMissingAndEmptyAndDuplicateCurrencies() {
    assertFields(request("Valid", "CD", "fr", "Africa/Kinshasa", (String[]) null), "currencies", "REQUIRED");
    assertFields(request("Valid", "CD", "fr", "Africa/Kinshasa"), "currencies", "REQUIRED");
    assertFields(request("Valid", "CD", "fr", "Africa/Kinshasa", "CDF", "CDF"), "currencies", "DUPLICATE");
    assertFields(request("Valid", "CD", "fr", "Africa/Kinshasa", "CDF", null), "currencies", "FORMAT");
  }

  @Test
  void rejectsMissingFields() {
    assertFields(request("Valid", null, "fr", "Africa/Kinshasa", "CDF"), "countryCode", "REQUIRED");
    assertFields(request("Valid", "CD", null, "Africa/Kinshasa", "CDF"), "defaultLocale", "REQUIRED");
    assertFields(request("Valid", "CD", "fr", null, "CDF"), "timezone", "REQUIRED");
  }

  @Test
  void appliesNotSupportedPrecedenceCountryLocaleTimezoneCurrency() {
    assertUnsupported(
        request("Valid", "CG", "de", "Europe/Paris", "EUR"), ErrorCode.COUNTRY_NOT_SUPPORTED, "countryCode", List.of("CD"));
    assertUnsupported(
        request("Valid", "CD", "de", "Europe/Paris", "EUR"), ErrorCode.LOCALE_NOT_SUPPORTED, "defaultLocale", List.of("en", "fr"));
    assertUnsupported(
        request("Valid", "CD", "fr", "Europe/Paris", "EUR"),
        ErrorCode.TIMEZONE_NOT_SUPPORTED,
        "timezone",
        List.of("Africa/Kinshasa", "Africa/Lubumbashi"));
    assertUnsupported(
        request("Valid", "CD", "fr", "Africa/Kinshasa", "CDF", "EUR"),
        ErrorCode.CURRENCY_NOT_SUPPORTED,
        "currencies",
        List.of("CDF", "USD"));
  }

  private void assertFields(CreateOrganizationRequest request, String field, String constraint) {
    assertThat(fields(catchApi(KEY, request)))
        .containsExactly(Map.of("field", field, "constraint", constraint));
  }

  private void assertUnsupported(
      CreateOrganizationRequest request, ErrorCode code, String field, List<String> supported) {
    ApiException error = catchApi(KEY, request);
    assertThat(error.code()).isEqualTo(code);
    assertThat(error.params()).isEqualTo(Map.of("field", field, "supported", supported));
  }

  private ApiException catchApi(String key, CreateOrganizationRequest request) {
    Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> validator.validate(key, request));
    assertThat(thrown).isInstanceOf(ApiException.class);
    return (ApiException) thrown;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, String>> fields(ApiException error) {
    return (List<Map<String, String>>) error.params().get("fields");
  }

  @Test
  void unknownPropertiesAreRejectedWithoutEchoingTheirNames() {
    assertThatThrownBy(() -> validator.validate(KEY, withUnknown()))
        .isInstanceOfSatisfying(
            ApiException.class,
            error ->
                assertThat(fields(error))
                    .containsExactly(Map.of("field", "body", "constraint", "UNKNOWN_PROPERTY")));
  }

  private static CreateOrganizationRequest withUnknown() {
    try {
      return tools.jackson.databind.json.JsonMapper.builder()
          .build()
          .readValue(
              """
              {"name":"Valid","countryCode":"CD","defaultLocale":"fr","timezone":"Africa/Kinshasa",
               "currencies":["CDF"],"tenantId":"00000000-0000-4000-8000-00000000000b"}
              """,
              CreateOrganizationRequest.class);
    } catch (RuntimeException e) {
      throw new IllegalStateException(e);
    }
  }
}
