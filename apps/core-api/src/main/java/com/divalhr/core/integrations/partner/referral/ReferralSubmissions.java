package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.PartnerCrypto;
import com.divalhr.core.integrations.partner.referral.ReferralCommands.Inputs;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** Strict parsing of the submission, withdrawal and list inputs of the consented referral. */
final class ReferralSubmissions {

  private static final Set<String> SUBMIT_KEYS =
      Set.of(
          "productId",
          "language",
          "includePhone",
          "phone",
          "includeOrganizationName",
          "previewDigest",
          "expiresAt",
          "consent");
  private static final Set<String> CONSENT_KEYS = Set.of("version", "affirmed");
  private static final Set<String> WITHDRAW_KEYS = Set.of("expectedVersion", "confirmed");

  private ReferralSubmissions() {}

  /**
   * A well-formed submission.
   *
   * @param inputs the previewed inputs, repeated
   * @param previewDigest the digest the preview returned
   * @param expiresAt the expiry the preview returned
   * @param consentVersion the consent wording version the employee affirmed
   */
  record Submit(Inputs inputs, String previewDigest, Instant expiresAt, String consentVersion) {

    /** The canonical command that fingerprints an idempotent submission (no personal value). */
    Map<String, Object> canonical(TenantId tenant, PartnerCrypto crypto) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("productId", inputs.productId());
      canonical.put("language", inputs.language());
      canonical.put("includePhone", inputs.includePhone());
      canonical.put(
          "phoneDigest",
          inputs.phone() == null ? "" : crypto.digest("partner-referral-phone", inputs.phone()));
      canonical.put("includeOrganizationName", inputs.includeOrganizationName());
      canonical.put("previewDigest", previewDigest);
      canonical.put("expiresAt", expiresAt.toString());
      canonical.put("consentVersion", consentVersion);
      canonical.put("affirmed", true);
      return canonical;
    }
  }

  /**
   * A well-formed withdrawal.
   *
   * @param expectedVersion the version the employee saw
   */
  record Withdraw(int expectedVersion) {

    Map<String, Object> canonical(TenantId tenant, String reference) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("referralId", reference);
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("confirmed", true);
      return canonical;
    }
  }

  /** The idempotency key: required and well formed. */
  static void key(String key) {
    if (key == null || key.isEmpty()) {
      throw ReferralCommands.fail(IdempotencyKeys.HEADER, "REQUIRED");
    }
    if (!IdempotencyKeys.isWellFormed(key)) {
      throw ReferralCommands.fail(IdempotencyKeys.HEADER, "FORMAT");
    }
  }

  static Submit submit(JsonNode body) {
    List<Map<String, String>> problems = new ArrayList<>();
    Inputs inputs = ReferralCommands.inputs(body, SUBMIT_KEYS, problems);
    String digest = ReferralCommands.string(body, "previewDigest", problems);
    if (digest != null && !PartnerReferralDomain.DIGEST.matcher(digest).matches()) {
      problems.add(ReferralCommands.problem("previewDigest", "FORMAT"));
    }
    Instant expiresAt = null;
    String expiresText = ReferralCommands.string(body, "expiresAt", problems);
    if (expiresText != null) {
      try {
        expiresAt = Instant.parse(expiresText);
      } catch (RuntimeException malformed) {
        problems.add(ReferralCommands.problem("expiresAt", "FORMAT"));
      }
    }
    String consentVersion = null;
    boolean affirmed = false;
    JsonNode consent = body.get("consent");
    if (consent == null || consent.isNull()) {
      problems.add(ReferralCommands.problem("consent", "REQUIRED"));
    } else if (!consent.isObject()) {
      problems.add(ReferralCommands.problem("consent", "FORMAT"));
    } else {
      for (String name : consent.propertyNames()) {
        if (!CONSENT_KEYS.contains(name)) {
          problems.add(ReferralCommands.problem("consent", "UNKNOWN_PROPERTY"));
          break;
        }
      }
      consentVersion = ReferralCommands.string(consent, "version", problems);
      Boolean flag = ReferralCommands.bool(consent, "affirmed", problems);
      affirmed = Boolean.TRUE.equals(flag);
    }
    ReferralCommands.throwIfAny(problems);
    ReferralCommands.semantic(inputs);
    if (!affirmed) {
      throw new ApiException(ErrorCode.PARTNER_REFERRAL_CONSENT_REQUIRED, Map.of());
    }
    return new Submit(inputs, digest, expiresAt, consentVersion);
  }

  static Withdraw withdraw(JsonNode body) {
    List<Map<String, String>> problems = new ArrayList<>();
    for (String name : body.propertyNames()) {
      if (!WITHDRAW_KEYS.contains(name)) {
        problems.add(ReferralCommands.problem("body", "UNKNOWN_PROPERTY"));
        break;
      }
    }
    JsonNode version = body.get("expectedVersion");
    int expected = 0;
    if (version == null || version.isNull()) {
      problems.add(ReferralCommands.problem("expectedVersion", "REQUIRED"));
    } else if (!version.isInt()) {
      problems.add(ReferralCommands.problem("expectedVersion", "FORMAT"));
    } else if (version.asInt() < 1 || version.asInt() > 64) {
      problems.add(ReferralCommands.problem("expectedVersion", "RANGE"));
    } else {
      expected = version.asInt();
    }
    Boolean confirmed = ReferralCommands.bool(body, "confirmed", problems);
    ReferralCommands.throwIfAny(problems);
    if (!Boolean.TRUE.equals(confirmed)) {
      throw ReferralCommands.fail("confirmed", "REQUIRED");
    }
    return new Withdraw(expected);
  }
}
