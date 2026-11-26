package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.PartnerCrypto;
import com.divalhr.core.integrations.partner.privacy.PrivacyExportStore.ReferralData;
import com.divalhr.core.integrations.partner.privacy.PrivacyExportStore.ReferralEvent;
import com.divalhr.core.integrations.partner.privacy.PrivacyExportStore.StoredPackage;
import com.divalhr.core.integrations.partner.privacy.PrivacyQuotas.Quota;
import com.divalhr.core.integrations.partner.privacy.PrivacyRequestStore.RequestRow;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The employee's own access package (LAUNCH-001, L8): a deterministic JSON document of only the
 * caller's own partner-referral data, stored encrypted for seven days and bound by a manifest
 * SHA-256 over its canonical content. It holds no audit metadata, no other employee's data, no
 * platform note and no secret, and it is never emailed. Generation is limited to three per request
 * per day.
 */
@Service
public class PrivacyExportService {

  /** Operation name. */
  public static final String DOWNLOAD = "privacy.export.download";

  /** The fixed package format. */
  public static final String FORMAT = "divalhr-privacy-access-package-1";

  private static final Duration VALIDITY = Duration.ofDays(7);
  private static final Set<String> DOWNLOADABLE =
      Set.of("APPROVED", "PARTIALLY_APPROVED", "COMPLETED");
  private static final Set<String> GENERATABLE = Set.of("APPROVED", "PARTIALLY_APPROVED");

  private final EmployeeAccessLinks links;
  private final PrivacyRequestStore requests;
  private final PrivacyExportStore store;
  private final PrivacyRecorder recorder;
  private final PrivacyQuotas quotas;
  private final PartnerCrypto crypto;
  private final JsonMapper json;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param links employee access links
   * @param requests request storage
   * @param store package storage
   * @param recorder audit and outbox
   * @param quotas privacy quotas
   * @param crypto seals and opens the package
   * @param json JSON mapper
   * @param clock server clock
   */
  public PrivacyExportService(
      EmployeeAccessLinks links,
      PrivacyRequestStore requests,
      PrivacyExportStore store,
      PrivacyRecorder recorder,
      PrivacyQuotas quotas,
      PartnerCrypto crypto,
      JsonMapper json,
      Clock clock) {
    this.links = links;
    this.requests = requests;
    this.store = store;
    this.recorder = recorder;
    this.quotas = quotas;
    this.crypto = crypto;
    this.json = json;
    this.clock = clock;
  }

  /**
   * A package ready to send.
   *
   * @param body the exact bytes of the package
   * @param sha256 the manifest digest, also the {@code X-Content-SHA256} header
   */
  public record Download(byte[] body, String sha256) {}

  /**
   * Returns the package of the employee's own approved ACCESS request, generating it first when
   * none is valid.
   *
   * @param caller verified employee
   * @param reference raw request reference
   * @return the package
   */
  @Transactional
  public Download download(PrivacyRequestService.Caller caller, String reference) {
    TenantId tenant = caller.tenant();
    SelfLink link =
        links
            .linkedEmployee(tenant, caller.subject())
            .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
    if (reference == null || !PrivacyCommands.REQUEST.matcher(reference).matches()) {
      throw new ApiException(ErrorCode.PRIVACY_REQUEST_NOT_FOUND, Map.of());
    }
    RequestRow request =
        requests
            .lockMine(tenant.value(), link.employeeId(), reference)
            .orElseThrow(() -> new ApiException(ErrorCode.PRIVACY_REQUEST_NOT_FOUND, Map.of()));
    if (!"ACCESS".equals(request.requestType()) || !DOWNLOADABLE.contains(request.state())) {
      throw new ApiException(ErrorCode.PRIVACY_EXPORT_NOT_AVAILABLE, Map.of());
    }
    Instant now = clock.instant();
    String aad = "privacy-export:" + tenant + ':' + request.id();
    StoredPackage stored = store.find(tenant.value(), request.id()).orElse(null);
    if (stored != null && stored.expiresAt().isAfter(now)) {
      byte[] body = crypto.open(stored.sealed(), aad);
      return delivered(caller, request, now, new Download(body, stored.sha256()), false);
    }
    if (!GENERATABLE.contains(request.state()) || (stored != null && stored.generation() >= 3)) {
      throw new ApiException(ErrorCode.PRIVACY_EXPORT_NOT_AVAILABLE, Map.of());
    }
    quotas.require(Quota.PACKAGE_GENERATION, request.id().toString());
    Download built = build(tenant, link, request);
    byte[] sealed = crypto.seal(built.body(), aad);
    Instant expires = now.plus(VALIDITY);
    if (stored == null) {
      store.insert(tenant.value(), request.id(), sealed, built.sha256(), now, expires);
    } else {
      store.replace(
          tenant.value(),
          request.id(),
          sealed,
          built.sha256(),
          stored.generation() + 1,
          now,
          expires);
    }
    store.insertEvent(tenant.value(), request.id(), "GENERATED", now);
    return delivered(caller, request, now, built, true);
  }

  private Download delivered(
      PrivacyRequestService.Caller caller,
      RequestRow request,
      Instant now,
      Download download,
      boolean generated) {
    store.insertEvent(request.tenantId(), request.id(), "DOWNLOADED", now);
    recorder.record(
        caller.subject(),
        "privacy.export.download",
        "integrations.privacy-export.downloaded.v1",
        request.tenantId(),
        "privacy-request",
        request.id(),
        caller.correlationId(),
        Map.of(
            "requestId", request.id().toString(),
            "requestType", request.requestType(),
            "state", request.state(),
            "actorRole", "EMPLOYEE"),
        now);
    return download;
  }

  private Download build(TenantId tenant, SelfLink link, RequestRow request) {
    List<Object> referrals = new ArrayList<>();
    for (ReferralData data :
        store.referrals(tenant.value(), link.employeeId(), request.referralId())) {
      referrals.add(referral(tenant, data));
    }
    List<Object> history = new ArrayList<>();
    for (RequestRow row :
        requests.pageMine(tenant.value(), link.employeeId(), null, null, 200).reversed()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", row.reference());
      entry.put("requestType", row.requestType());
      entry.put("state", row.state());
      entry.put("submittedAt", row.createdAt().toString());
      history.add(entry);
    }
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("referrals", referrals);
    content.put("requests", history);
    String sha256 = Fingerprints.sha256(json.writeValueAsString(content));
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("format", FORMAT);
    manifest.put("requestId", request.reference());
    manifest.put("referralCount", referrals.size());
    manifest.put("sha256", sha256);
    Map<String, Object> pkg = new LinkedHashMap<>();
    pkg.put("manifest", manifest);
    pkg.put("referrals", referrals);
    pkg.put("requests", history);
    return new Download(json.writeValueAsString(pkg).getBytes(StandardCharsets.UTF_8), sha256);
  }

  private Map<String, Object> referral(TenantId tenant, ReferralData data) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("referralId", data.reference());
    out.put("state", data.state());
    out.put("createdAt", data.createdAt().toString());
    out.put("partnerName", data.partnerName());
    out.put("productName", data.productName());
    out.put("fieldNames", data.fieldNames());
    if (data.sealedValues() == null) {
      out.put("disclosedValues", null);
      out.put(
          "disclosureStatus", "ERASE".equals(data.disclosureOutcome()) ? "ERASED" : "MINIMIZED");
    } else {
      JsonNode values =
          json.readTree(
              crypto.open(data.sealedValues(), "partner-referral:" + tenant + ':' + data.id()));
      Map<String, String> sorted = new TreeMap<>();
      for (String name : values.propertyNames()) {
        sorted.put(name, values.path(name).asString(""));
      }
      out.put("disclosedValues", sorted);
      out.put("disclosureStatus", "AVAILABLE");
    }
    if (data.consentedAt() == null) {
      out.put("consent", null);
    } else {
      Map<String, Object> consent = new LinkedHashMap<>();
      consent.put("wordingVersion", data.consentVersion());
      consent.put("language", data.consentLanguage());
      consent.put("consentedAt", data.consentedAt().toString());
      out.put("consent", consent);
    }
    Map<String, Object> delivery = new LinkedHashMap<>();
    delivery.put("attemptCount", data.attemptCount());
    delivery.put("receiptConfirmed", data.receiptConfirmed());
    out.put("delivery", delivery);
    Map<String, Object> policy = new LinkedHashMap<>();
    policy.put("status", data.stampStatus() == null ? "POLICY_PENDING" : data.stampStatus());
    policy.put("countryCode", data.policyCountry());
    policy.put("wordingVersion", data.policyWording());
    out.put("policy", policy);
    out.put("legalHoldActive", store.holdActive(tenant.value(), data.id()));
    List<Object> events = new ArrayList<>();
    for (ReferralEvent e : store.events(tenant.value(), data.id())) {
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("sequence", e.sequence());
      event.put("fromState", e.fromState());
      event.put("toState", e.toState());
      event.put("actorRole", e.actorRole());
      event.put("outcome", e.outcome());
      event.put("occurredAt", e.occurredAt().toString());
      events.add(event);
    }
    out.put("events", events);
    return out;
  }
}
