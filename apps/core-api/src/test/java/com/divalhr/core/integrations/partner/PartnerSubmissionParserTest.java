package com.divalhr.core.integrations.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * R216: the country catalogue, the regulatory-field rule and the consent identifiers are enforced
 * by Core itself, independently of the web form. Every value here is SYNTHETIC.
 */
class PartnerSubmissionParserTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final List<String> DRC = List.of("CD");
  private static final List<String> FRANCOPHONE =
      List.of("CG", "CI", "SN", "CM", "GA", "ML", "BF", "TG", "BJ", "MG", "MA", "TN", "DZ");
  private static final List<String> ANGLOPHONE =
      List.of("RW", "BI", "KE", "NG", "GH", "ZA", "UG", "TZ", "ZM", "ET", "SS");
  private static final List<String> INTERNATIONAL =
      List.of("FR", "BE", "CH", "CA", "US", "GB", "DE", "JP", "BR", "IN", "BQ");
  private static final List<String> NOT_ISO =
      List.of("ZZ", "XK", "EU", "UN", "AN", "UK", "OTHER", "cd", "Cd", "C", "CDD", "", " CD");
  private static final List<String> REGULATED =
      List.of(
          "BANK",
          "MICROFINANCE",
          "INSURANCE",
          "TELECOM",
          "MOBILE_MONEY_PAYMENT",
          "PENSION_SAVINGS");
  private static final List<String> WRONG_NOTICES =
      List.of("2026-10", "PARTNER-PRIVACY-2", "partner-privacy-1", "PARTNER-APPLICATION-CONSENT-1");
  private static final List<String> WRONG_CONSENTS =
      List.of("2026-10", "PARTNER-APPLICATION-CONSENT-2", "PARTNER-PRIVACY-1", "");

  private static Map<String, Object> valid() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("legalName", "Synthetic Partner SARL");
    body.put("countryOfRegistration", "CD");
    body.put("countriesOfOperation", List.of("CD"));
    body.put("registrationReference", "SYN-RCCM-0001");
    body.put("primaryCategory", "HEALTH_BENEFITS");
    body.put("additionalCategories", List.of());
    body.put("requestedCapabilities", List.of("HEALTHCARE_BENEFITS"));
    body.put("website", "https://partner.example.test/");
    body.put("contactName", "Synthetic Contact");
    body.put("contactRole", "Head of Partnerships");
    body.put("businessEmail", "contact@example.test");
    body.put("supportedLanguages", List.of("fr"));
    body.put("apiAvailability", "NO");
    body.put("serviceSummary", "Synthetic provider of healthcare benefits for employees.");
    body.put("privacyContactEmail", "privacy@example.test");
    body.put("acknowledgment", true);
    body.put("privacyNoticeVersion", PartnerConsentVersions.PRIVACY_NOTICE);
    body.put("consentVersion", PartnerConsentVersions.CONSENT);
    body.put("locale", "fr");
    return body;
  }

  private static PartnerSubmission parse(Map<String, Object> body) {
    return PartnerSubmissionParser.parse(JSON.valueToTree(body));
  }

  private static Set<String> refusedFields(Map<String, Object> body) {
    ApiException refused = assertThrows(ApiException.class, () -> parse(body));
    assertThat(refused.code()).isEqualTo(ErrorCode.PARTNER_APPLICATION_INVALID);
    Set<String> fields = new TreeSet<>();
    for (Object entry : (List<?>) refused.params().get("fields")) {
      Map<?, ?> field = (Map<?, ?>) entry;
      fields.add(field.get("field") + ":" + field.get("constraint"));
    }
    return fields;
  }

  @Test
  void theCatalogueIsTheAssignedIsoSetAndHasNoOtherValue() {
    assertThat(PartnerCountries.codes()).hasSize(249);
    assertThat(PartnerCountries.codes())
        .containsExactlyInAnyOrderElementsOf(Set.of(Locale.getISOCountries()));
    assertThat(PartnerCountries.codes())
        .doesNotContain("OTHER", "XK", "EU", "UN", "ZZ", "AN", "UK");
  }

  @Test
  void representativeCountriesAreAcceptedForRegistrationAndOperation() {
    for (List<String> group : List.of(DRC, FRANCOPHONE, ANGLOPHONE, INTERNATIONAL)) {
      for (String code : group) {
        Map<String, Object> body = valid();
        body.put("countryOfRegistration", code);
        body.put("countriesOfOperation", List.of(code));
        PartnerSubmission submission = parse(body);
        assertThat(submission.countryOfRegistration()).as(code).isEqualTo(code);
        assertThat(submission.countriesOfOperation()).as(code).containsExactly(code);
      }
    }
  }

  @Test
  void unknownOrNonIsoCountriesAreRefusedWithoutEchoing() {
    for (String code : NOT_ISO) {
      Map<String, Object> registration = valid();
      registration.put("countryOfRegistration", code);
      assertThat(refusedFields(registration))
          .as(code)
          .containsExactly("countryOfRegistration:FORMAT");
      Map<String, Object> operation = valid();
      operation.put("countriesOfOperation", List.of("CD", code));
      assertThat(refusedFields(operation)).as(code).containsExactly("countriesOfOperation:FORMAT");
    }
    Map<String, Object> typed = valid();
    typed.put("countryOfRegistration", 12);
    assertThat(refusedFields(typed)).containsExactly("countryOfRegistration:FORMAT");
  }

  @Test
  void aRegulatedAdditionalCategoryRequiresTheRegulatoryFieldsEvenWithANonRegulatedPrimary() {
    for (String category : REGULATED) {
      Map<String, Object> body = valid();
      body.put("additionalCategories", List.of(category));
      assertThat(refusedFields(body))
          .as(category)
          .containsExactly("licenceReference:REQUIRED", "regulatorName:REQUIRED");
      body.put("regulatorName", "Synthetic Regulator");
      assertThat(refusedFields(body)).as(category).containsExactly("licenceReference:REQUIRED");
      body.put("licenceReference", "SYN-LICENCE-0001");
      PartnerSubmission accepted = parse(body);
      assertThat(accepted.regulatorName()).isEqualTo("Synthetic Regulator");
      assertThat(accepted.licenceReference()).isEqualTo("SYN-LICENCE-0001");
    }
  }

  @Test
  void nonRegulatedSelectionsNeedNoRegulatoryFieldsAndRegulatedPrimariesStillDo() {
    Map<String, Object> plain = valid();
    plain.put("additionalCategories", List.of("OTHER_REGULATED_PROVIDER"));
    assertThat(parse(plain).regulatorName()).isNull();
    Map<String, Object> primary = valid();
    primary.put("primaryCategory", "INSURANCE");
    primary.put("additionalCategories", List.of("HEALTH_BENEFITS"));
    assertThat(refusedFields(primary))
        .containsExactly("licenceReference:REQUIRED", "regulatorName:REQUIRED");
  }

  @Test
  void onlyTheCurrentConsentIdentifiersAreRecorded() {
    PartnerSubmission accepted = parse(valid());
    assertThat(accepted.privacyNoticeVersion()).isEqualTo("PARTNER-PRIVACY-1");
    assertThat(accepted.consentVersion()).isEqualTo("PARTNER-APPLICATION-CONSENT-1");
    for (String other : WRONG_NOTICES) {
      Map<String, Object> notice = valid();
      notice.put("privacyNoticeVersion", other);
      assertThat(refusedFields(notice)).containsExactly("privacyNoticeVersion:FORMAT");
    }
    for (String other : WRONG_CONSENTS) {
      Map<String, Object> consent = valid();
      consent.put("consentVersion", other);
      assertThat(refusedFields(consent)).containsExactly("consentVersion:FORMAT");
    }
    Map<String, Object> missing = valid();
    missing.remove("consentVersion");
    assertThat(refusedFields(missing)).containsExactly("consentVersion:REQUIRED");
  }

  @Test
  void aRefusalNeverCarriesTheRefusedValues() {
    Map<String, Object> body = valid();
    body.put("countryOfRegistration", "SYNTHETIC-COUNTRY");
    body.put("privacyNoticeVersion", "SYNTHETIC-VERSION");
    ApiException refused = assertThrows(ApiException.class, () -> parse(body));
    String shown = String.valueOf(refused.params());
    assertThat(shown).doesNotContain("SYNTHETIC-COUNTRY", "SYNTHETIC-VERSION");
  }
}
