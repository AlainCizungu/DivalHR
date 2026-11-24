package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.PartnerCrypto;
import com.divalhr.core.integrations.partner.referral.PartnerReferralDomain.Field;
import com.divalhr.core.integrations.partner.referral.PartnerReferralRepository.ProductIdentity;
import com.divalhr.core.integrations.partner.referral.ReferralCommands.Inputs;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.access.EmployeeLegalNames;
import com.divalhr.core.platform.access.MembershipContacts;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Builds the exact disclosure of one referral and its digest (MVP-120F, D120F-3, D120F-4). The
 * values come from the verified employee, membership and organization records and the eligible
 * catalogue version; only the phone is typed by the employee. Preview and commit use this one
 * builder, so the committed disclosure is the previewed one or the commit is refused.
 */
@Component
public class PartnerReferralDisclosures {

  /** The disclosure and what is needed to bind it. */
  record Built(
      ProductIdentity product,
      List<String> categories,
      List<Entry> entries,
      List<String> blockers) {

    String value(Field field) {
      return entries.stream()
          .filter(entry -> entry.field() == field)
          .map(Entry::value)
          .findFirst()
          .orElse(null);
    }
  }

  /**
   * One disclosed field. The value is {@code null} only for the assigned fields.
   *
   * @param field the field
   * @param value the value
   */
  record Entry(Field field, String value) {}

  private final PartnerReferralRepository repository;
  private final OrganizationDirectory organizations;
  private final EmployeeLegalNames legalNames;
  private final MembershipContacts contacts;
  private final PartnerCrypto crypto;
  private final Clock clock;

  /**
   * Creates the builder.
   *
   * @param repository storage
   * @param organizations organization directory
   * @param legalNames employee legal names
   * @param contacts verified membership contacts
   * @param crypto keyed digests
   * @param clock clock
   */
  public PartnerReferralDisclosures(
      PartnerReferralRepository repository,
      OrganizationDirectory organizations,
      EmployeeLegalNames legalNames,
      MembershipContacts contacts,
      PartnerCrypto crypto,
      Clock clock) {
    this.repository = repository;
    this.organizations = organizations;
    this.legalNames = legalNames;
    this.contacts = contacts;
    this.crypto = crypto;
    this.clock = clock;
  }

  /**
   * Builds the disclosure of the employee's referral to an eligible product. An unknown, malformed,
   * foreign or unavailable product is the same safe 404.
   *
   * @param tenant verified tenant
   * @param link the caller's own active link
   * @param inputs the validated inputs
   * @return the disclosure with its blockers
   */
  Built build(TenantId tenant, SelfLink link, Inputs inputs) {
    if (!PartnerReferralDomain.PRODUCT_REFERENCE.matcher(inputs.productId()).matches()) {
      throw unavailable();
    }
    OrganizationSummary organization =
        organizations.find(tenant).orElseThrow(this::unavailableSupplier);
    String country = organizations.countryCode(tenant).orElseThrow(this::unavailableSupplier);
    ProductIdentity product =
        repository
            .eligibleIdentity(country, today(), inputs.productId())
            .orElseThrow(this::unavailableSupplier);
    List<String> categories = repository.categories(product.partnerId(), product.primaryCategory());
    List<String> blockers = new ArrayList<>();
    Optional<String> name = legalNames.legalName(tenant, link.employeeId());
    if (name.isEmpty()) {
      blockers.add("EMPLOYEE_LEGAL_NAME_UNAVAILABLE");
    }
    Optional<String> email = contacts.verifiedEmail(tenant, link.membershipId());
    if (email.isEmpty()) {
      blockers.add("MEMBERSHIP_EMAIL_UNAVAILABLE");
    }
    String productName = "fr".equals(inputs.language()) ? product.nameFr() : product.nameEn();
    List<Entry> entries = new ArrayList<>();
    for (Field field : Field.values()) {
      switch (field) {
        case REFERRAL_REFERENCE, CONSENT_TIMESTAMP -> entries.add(new Entry(field, null));
        case PRODUCT_REFERENCE -> entries.add(new Entry(field, product.publicReference()));
        case PRODUCT_NAME -> entries.add(new Entry(field, productName));
        case PARTNER_NAME -> entries.add(new Entry(field, product.partnerDisplayName()));
        case EMPLOYEE_LEGAL_NAME -> entries.add(new Entry(field, name.orElse("")));
        case EMPLOYEE_EMAIL -> entries.add(new Entry(field, email.orElse("")));
        case EMPLOYEE_PHONE -> {
          if (inputs.includePhone()) {
            entries.add(new Entry(field, inputs.phone()));
          }
        }
        case EMPLOYEE_COUNTRY -> entries.add(new Entry(field, country));
        case PREFERRED_LANGUAGE -> entries.add(new Entry(field, inputs.language()));
        case ORGANIZATION_LEGAL_NAME -> {
          if (inputs.includeOrganizationName()) {
            entries.add(new Entry(field, organization.name()));
          }
        }
        case CONSENT_VERSION ->
            entries.add(new Entry(field, PartnerReferralDomain.CONSENT_VERSION));
      }
    }
    return new Built(product, List.copyOf(categories), List.copyOf(entries), List.copyOf(blockers));
  }

  /**
   * The preview digest: a keyed HMAC binding the tenant, the employee, the product version, the
   * partner, every disclosed value, the language, the consent wording version and the expiry. Any
   * change to any of them changes the digest.
   *
   * @param tenant verified tenant
   * @param link the caller's own active link
   * @param built the disclosure
   * @param language the language
   * @param expiresAt the expiry
   * @return 64 lowercase hex characters
   */
  String digest(TenantId tenant, SelfLink link, Built built, String language, Instant expiresAt) {
    if (!crypto.available()) {
      throw new IllegalStateException("partner referral digest key is not configured");
    }
    StringBuilder canonical = new StringBuilder("v1");
    append(canonical, "tenant", tenant.toString());
    append(canonical, "employee", link.employeeId().toString());
    append(canonical, "version", built.product().versionId().toString());
    append(canonical, "partner", built.product().partnerId().toString());
    append(canonical, "language", language);
    append(canonical, "consent", PartnerReferralDomain.CONSENT_VERSION);
    append(canonical, "expires", expiresAt.truncatedTo(ChronoUnit.SECONDS).toString());
    for (Entry entry : built.entries()) {
      if (!entry.field().assigned()) {
        append(canonical, entry.field().name().toLowerCase(Locale.ROOT), entry.value());
      }
    }
    return crypto.digest("partner-referral-preview", canonical.toString());
  }

  /** The expiry of a preview made now: fifteen minutes, on a whole second. */
  Instant expiry(Instant now) {
    return now.plus(PartnerReferralDomain.PREVIEW_TTL).truncatedTo(ChronoUnit.SECONDS);
  }

  /** The current instant. */
  Instant now() {
    return clock.instant();
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC));
  }

  private static void append(StringBuilder canonical, String name, String value) {
    String text = value == null ? "" : value;
    canonical.append('\n').append(name).append('=').append(text.length()).append(':').append(text);
  }

  private ApiException unavailableSupplier() {
    return unavailable();
  }

  static ApiException unavailable() {
    return new ApiException(ErrorCode.PARTNER_REFERRAL_PRODUCT_UNAVAILABLE, Map.of());
  }
}
