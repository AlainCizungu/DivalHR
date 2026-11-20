package com.divalhr.core.integrations.partner;

import com.divalhr.core.integrations.partner.PartnerDomain.ApiAvailability;
import com.divalhr.core.integrations.partner.PartnerDomain.Capability;
import com.divalhr.core.integrations.partner.PartnerDomain.Category;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A validated, trimmed partner-application submission (MVP-120D). Every value is an unverified
 * applicant assertion. Its {@link #toString()} is deliberately opaque so a stray log line can never
 * carry contact data, a licence reference or the service summary.
 *
 * @param legalName legal name
 * @param tradingName trading name or {@code null}
 * @param countryOfRegistration ISO country
 * @param countriesOfOperation ISO countries
 * @param registrationReference business registration reference (confidential)
 * @param primaryCategory primary category
 * @param additionalCategories up to three more
 * @param capabilities requested capabilities
 * @param website https website
 * @param contactName contact person (confidential)
 * @param contactRole role of the contact
 * @param businessEmail business email (confidential)
 * @param businessPhone E.164 phone or {@code null} (confidential)
 * @param languages supported languages
 * @param apiAvailability API availability
 * @param regulatorName regulator or {@code null}
 * @param licenceReference licence reference or {@code null} (confidential)
 * @param serviceSummary service summary
 * @param privacyContactEmail privacy contact (confidential)
 * @param privacyNoticeVersion accepted privacy notice version
 * @param consentVersion accepted consent version
 * @param locale submission locale
 */
public record PartnerSubmission(
    String legalName,
    String tradingName,
    String countryOfRegistration,
    List<String> countriesOfOperation,
    String registrationReference,
    Category primaryCategory,
    List<Category> additionalCategories,
    List<Capability> capabilities,
    String website,
    String contactName,
    String contactRole,
    String businessEmail,
    String businessPhone,
    List<String> languages,
    ApiAvailability apiAvailability,
    String regulatorName,
    String licenceReference,
    String serviceSummary,
    String privacyContactEmail,
    String privacyNoticeVersion,
    String consentVersion,
    String locale) {

  /** Copies the lists defensively. */
  public PartnerSubmission {
    countriesOfOperation = List.copyOf(countriesOfOperation);
    additionalCategories = List.copyOf(additionalCategories);
    capabilities = List.copyOf(capabilities);
    languages = List.copyOf(languages);
  }

  /**
   * A deterministic text of every field, with set-like lists sorted, used only as input to a keyed
   * digest (the idempotent-replay fingerprint). Never stored, logged or returned.
   *
   * @return canonical text
   */
  String canonical() {
    List<String> lines = new ArrayList<>();
    lines.add("legalName=" + legalName);
    lines.add("tradingName=" + tradingName);
    lines.add("country=" + countryOfRegistration);
    lines.add("operation=" + String.join(",", new TreeSet<>(countriesOfOperation)));
    lines.add("registration=" + registrationReference);
    lines.add("primary=" + primaryCategory);
    lines.add("additional=" + sorted(additionalCategories));
    lines.add("capabilities=" + sorted(capabilities));
    lines.add("website=" + website);
    lines.add("contactName=" + contactName);
    lines.add("contactRole=" + contactRole);
    lines.add("email=" + businessEmail);
    lines.add("phone=" + businessPhone);
    lines.add("languages=" + String.join(",", new TreeSet<>(languages)));
    lines.add("api=" + apiAvailability);
    lines.add("regulator=" + regulatorName);
    lines.add("licence=" + licenceReference);
    lines.add("summary=" + serviceSummary);
    lines.add("privacy=" + privacyContactEmail);
    lines.add("notice=" + privacyNoticeVersion);
    lines.add("consent=" + consentVersion);
    lines.add("locale=" + locale);
    return String.join("\n", lines.stream().map(l -> l.replace("\n", "\\n")).toList());
  }

  private static String sorted(List<? extends Enum<?>> values) {
    TreeSet<String> names = new TreeSet<>();
    values.forEach(v -> names.add(v.name()));
    return String.join(",", names);
  }

  @Override
  public String toString() {
    return "PartnerSubmission[redacted]";
  }
}
