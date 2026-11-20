package com.divalhr.core.integrations.partner;

import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Receipt;
import com.divalhr.core.integrations.partner.PartnerApplicationRepository.Row;
import com.divalhr.core.integrations.partner.PartnerApplicationRepository.SubmissionRow;
import com.divalhr.core.integrations.partner.PartnerDomain.EventType;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.ratelimit.PublicRateLimiter;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The anonymous partner-application submission (MVP-120D, ADR 0043). A new application, a detected
 * duplicate and the hidden honeypot all answer with the same status and receipt shape, so nothing
 * here tells the caller whether an organization, business email or registration reference is
 * already known. The raw email, registration and licence references, contact data, notes, summary
 * and client address are never written to a log, an audit entry or an error: only keyed digests
 * leave this class, and only to the database.
 */
@Service
public class PartnerSubmissionService {

  private static final Logger LOG = LoggerFactory.getLogger(PartnerSubmissionService.class);

  /** Rate-limit bucket of the public route. */
  public static final String BUCKET = "partner-application.public";

  /** Audit action of a submission. */
  public static final String AUDIT_ACTION = "partner.application-submit";

  /** Actor recorded for an anonymous applicant. */
  static final String ANONYMOUS_ACTOR = "anonymous-applicant";

  /** Sentinel scope of platform rows. */
  static final UUID PLATFORM_SCOPE = new UUID(0L, 0L);

  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  private static final int REFERENCE_LENGTH = 12;
  private static final int ATTEMPTS = 3;
  private static final String NEXT_STEP = "DIVALHR_WILL_CONTACT_BUSINESS_EMAIL";
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Outcome of a submission. */
  public record Outcome(Receipt receipt, boolean replayed) {}

  private final PartnerApplicationRepository repository;
  private final PartnerCrypto crypto;
  private final PublicRateLimiter clients;
  private final PartnerEmailLimiter emails;
  private final AuditRecorder audit;
  private final TransactionTemplate transactions;
  private final JsonMapper json;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param repository storage
   * @param crypto encryption and digests
   * @param clients per-client public limiter
   * @param emails per-email limiter
   * @param audit audit recorder
   * @param transactions transaction template
   * @param json JSON mapper
   * @param clock clock
   */
  public PartnerSubmissionService(
      PartnerApplicationRepository repository,
      PartnerCrypto crypto,
      PublicRateLimiter clients,
      PartnerEmailLimiter emails,
      AuditRecorder audit,
      TransactionTemplate transactions,
      JsonMapper json,
      Clock clock) {
    this.repository = repository;
    this.crypto = crypto;
    this.clients = clients;
    this.emails = emails;
    this.audit = audit;
    this.transactions = transactions;
    this.json = json;
    this.clock = clock;
  }

  /**
   * Whether partner data can be sealed (the shared encryption key is configured).
   *
   * @return true when submissions can be accepted
   */
  public boolean available() {
    return crypto.available();
  }

  /**
   * Counts one request of the client against the public limit.
   *
   * @param clientAddress resolved client address (never stored or logged)
   * @throws RateLimitedException {@link ErrorCode#PARTNER_APPLICATION_RATE_LIMITED}
   */
  public void admit(String clientAddress) {
    try {
      clients.acquire(BUCKET, clientAddress);
    } catch (RateLimitedException limited) {
      throw new RateLimitedException(
          ErrorCode.PARTNER_APPLICATION_RATE_LIMITED, limited.retryAfterSeconds());
    }
  }

  /**
   * Submits an application.
   *
   * @param idempotencyKey the {@code Idempotency-Key} header
   * @param body the parsed JSON body
   * @param correlationId request correlation id
   * @return the receipt and whether it is an exact replay
   */
  public Outcome submit(String idempotencyKey, JsonNode body, String correlationId) {
    if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      throw new ApiException(
          ErrorCode.PARTNER_APPLICATION_INVALID,
          Map.of(
              "fields",
              List.of(
                  Map.of(
                      "field",
                      IdempotencyKeys.HEADER,
                      "constraint",
                      idempotencyKey == null || idempotencyKey.isBlank() ? "REQUIRED" : "FORMAT"))));
    }
    if (isHoneypot(body)) {
      // Same receipt shape and status as an ordinary submission; nothing is stored, audited or
      // logged, and the submitted fields are never read.
      return new Outcome(decoy(), false);
    }
    PartnerSubmission submission = PartnerSubmissionParser.parse(body);
    String keyDigest = crypto.digest("idempotency-key", idempotencyKey);
    String requestFingerprint = crypto.digest("request", submission.canonical());
    Outcome replay = replay(keyDigest, requestFingerprint);
    if (replay != null) {
      return replay;
    }
    String emailDigest = crypto.digest("email", normalizeEmail(submission.businessEmail()));
    long retry = emails.acquire(emailDigest);
    if (retry > 0) {
      throw new RateLimitedException(ErrorCode.PARTNER_APPLICATION_RATE_LIMITED, retry);
    }
    String organizationFingerprint =
        crypto.digest(
            "organization",
            submission.countryOfRegistration()
                + "\n"
                + normalizeText(submission.legalName())
                + "\n"
                + normalizeText(submission.registrationReference()));
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try {
        return new Outcome(
            create(
                submission,
                keyDigest,
                requestFingerprint,
                emailDigest,
                organizationFingerprint,
                correlationId),
            false);
      } catch (DuplicateKeyException collision) {
        // Either the same key committed first (replay it) or a reference collided (retry).
        Outcome winner = replay(keyDigest, requestFingerprint);
        if (winner != null) {
          return winner;
        }
      }
    }
    // Unreachable in practice (a reference collision three times in a row): retryable, and never
    // a signal about any existing applicant.
    throw new Busy();
  }

  /** The submission could not be committed right now; retry with the same key. */
  public static final class Busy extends RuntimeException {

    private static final long serialVersionUID = 1L;

    Busy() {
      super("partner submission busy");
    }
  }

  private Outcome replay(String keyDigest, String requestFingerprint) {
    return transactions.execute(
        status -> {
          SubmissionRow stored = repository.findSubmission(keyDigest).orElse(null);
          if (stored == null) {
            return null;
          }
          if (!stored.requestFingerprint().equals(requestFingerprint)) {
            throw new ApiException(ErrorCode.PARTNER_APPLICATION_IDEMPOTENCY_REUSED, Map.of());
          }
          Row row = repository.findById(stored.applicationId()).orElseThrow();
          return new Outcome(receipt(row.reference(), row.submittedAt()), true);
        });
  }

  private Receipt create(
      PartnerSubmission s,
      String keyDigest,
      String requestFingerprint,
      String emailDigest,
      String organizationFingerprint,
      String correlationId) {
    return transactions.execute(
        status -> {
          Instant at = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
          UUID id = UUID.randomUUID();
          String reference = newReference();
          repository.insertApplication(
              id,
              reference,
              s,
              sealContact(id, s),
              emailDigest,
              organizationFingerprint,
              at);
          repository.insertDetails(id, s, at);
          repository.appendEvent(
              id, EventType.SUBMITTED, null, "SUBMITTED", null, null, null, "APPLICANT", null, at);
          repository.insertSubmission(keyDigest, requestFingerprint, id, at);
          Map<String, Object> metadata = new LinkedHashMap<>();
          metadata.put("status", "SUBMITTED");
          metadata.put("primaryCategory", s.primaryCategory().name());
          metadata.put("locale", s.locale());
          audit.record(
              new AuditEvent(
                  UUID.randomUUID(),
                  at,
                  ANONYMOUS_ACTOR,
                  AUDIT_ACTION,
                  "partner-application",
                  id,
                  PLATFORM_SCOPE,
                  "SUCCESS",
                  correlationId,
                  metadata,
                  Fingerprints.sha256(
                      "DIVALHR-PARTNER-APPLICATION-SUBMIT\nversion=1\napplication=" + id + "\n")));
          LOG.atInfo()
              .addKeyValue("operation", AUDIT_ACTION)
              .addKeyValue("outcome", "recorded")
              .log("partner_application_submitted");
          return receipt(reference, at);
        });
  }

  /**
   * Seals the confidential contact block, bound to the application id.
   *
   * @param id application id
   * @param s submission
   * @return sealed bytes
   */
  byte[] sealContact(UUID id, PartnerSubmission s) {
    Map<String, Object> contact = new LinkedHashMap<>();
    contact.put("contactName", s.contactName());
    contact.put("businessEmail", s.businessEmail());
    contact.put("businessPhone", s.businessPhone());
    contact.put("registrationReference", s.registrationReference());
    contact.put("licenceReference", s.licenceReference());
    contact.put("privacyContactEmail", s.privacyContactEmail());
    return crypto.seal(json.writeValueAsBytes(contact), contactContext(id));
  }

  /**
   * The associated data binding a contact block to its application.
   *
   * @param id application id
   * @return context text
   */
  static String contactContext(UUID id) {
    return "divalhr-partner-application\ncontact\napplication=" + id;
  }

  private static boolean isHoneypot(JsonNode body) {
    if (body == null || !body.isObject()) {
      return false;
    }
    JsonNode fax = body.get("faxNumber");
    if (fax == null || fax.isNull()) {
      return false;
    }
    return !(fax.isString() && fax.asString().isEmpty());
  }

  private static Receipt decoy() {
    return receipt(newReference(), Instant.now().truncatedTo(ChronoUnit.MICROS));
  }

  private static Receipt receipt(String reference, Instant at) {
    return new Receipt(reference, at, "SUBMITTED", NEXT_STEP);
  }

  static String newReference() {
    StringBuilder text = new StringBuilder("PA-");
    for (int i = 0; i < REFERENCE_LENGTH; i++) {
      text.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return text.toString();
  }

  static String normalizeEmail(String email) {
    return Normalizer.normalize(email.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  static String normalizeText(String value) {
    String folded = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    return folded.replaceAll("[^\\p{L}\\p{N}]+", "");
  }
}
