package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.ConsentDocument;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.DisclosedValue;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.Preview;
import com.divalhr.core.integrations.partner.referral.PartnerReferralDisclosures.Built;
import com.divalhr.core.integrations.partner.referral.PartnerReferralDisclosures.Entry;
import com.divalhr.core.integrations.partner.referral.ReferralCommands.Inputs;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stateless, short-lived disclosure preview (MVP-120F, D120F-3): it lists every field that
 * would be disclosed with its source and purpose, writes nothing, and returns a digest valid for
 * fifteen minutes that binds the employee, organization, product version, partner, every value, the
 * language and the consent wording version. A preview that cannot produce a required field carries
 * a blocker and no digest.
 */
@Service
public class PartnerReferralPreviewService {

  private final EmployeeAccessLinks links;
  private final PartnerReferralDisclosures disclosures;
  private final PartnerReferralQuotas quotas;

  /**
   * Creates the service.
   *
   * @param links the identity port
   * @param disclosures the disclosure builder
   * @param quotas hourly quotas
   */
  public PartnerReferralPreviewService(
      EmployeeAccessLinks links,
      PartnerReferralDisclosures disclosures,
      PartnerReferralQuotas quotas) {
    this.links = links;
    this.disclosures = disclosures;
    this.quotas = quotas;
  }

  /**
   * Previews the disclosure of a referral to one eligible product.
   *
   * @param caller verified employee
   * @param inputs validated inputs
   * @return the preview
   */
  // Not read-only: the identity port locks the employee's link and membership FOR SHARE, which a
  // read-only transaction refuses. Nothing is written.
  @Transactional
  public Preview preview(ReferralCaller caller, Inputs inputs) {
    Instant now = disclosures.now();
    quotas.requirePreview(caller.tenant(), caller.subject(), now);
    SelfLink link =
        links
            .linkedEmployee(caller.tenant(), caller.subject())
            .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
    Built built = disclosures.build(caller.tenant(), link, inputs);
    Instant expiresAt = disclosures.expiry(now);
    String digest =
        built.blockers().isEmpty()
            ? disclosures.digest(caller.tenant(), link, built, inputs.language(), expiresAt)
            : null;
    List<DisclosedValue> fields =
        built.entries().stream().map(PartnerReferralPreviewService::value).toList();
    return new Preview(
        built.product().publicReference(),
        built.product().partnerDisplayName(),
        built.product().productType(),
        built.categories(),
        inputs.language(),
        new ConsentDocument(PartnerReferralDomain.CONSENT_VERSION, inputs.language()),
        fields,
        PartnerReferralDomain.NEVER_SHARED,
        built.blockers(),
        digest,
        digest == null ? null : expiresAt,
        PartnerReferralBodies.NOTICE);
  }

  private static DisclosedValue value(Entry entry) {
    return new DisclosedValue(
        entry.field().name(),
        entry.field().source(),
        entry.field().purpose(),
        entry.value(),
        entry.field().assigned());
  }
}
