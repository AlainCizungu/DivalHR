package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.PartnerCrypto;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.Bilingual;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.Event;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.Page;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.Summary;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.View;
import com.divalhr.core.integrations.partner.referral.PartnerReferralDisclosures.Built;
import com.divalhr.core.integrations.partner.referral.PartnerReferralDisclosures.Entry;
import com.divalhr.core.integrations.partner.referral.PartnerReferralDomain.Field;
import com.divalhr.core.integrations.partner.referral.ReferralStore.EventRow;
import com.divalhr.core.integrations.partner.referral.ReferralStore.NewReferral;
import com.divalhr.core.integrations.partner.referral.ReferralStore.Position;
import com.divalhr.core.integrations.partner.referral.ReferralStore.ReferralRow;
import com.divalhr.core.integrations.partner.referral.ReferralSubmissions.Submit;
import com.divalhr.core.integrations.partner.referral.ReferralSubmissions.Withdraw;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The employee's consented partner referral (MVP-120F, ADR 0045): the idempotent submission with
 * explicit consent, the employee-only reads and the withdrawal before delivery. A referral is a
 * consented introduction only: DivalHR decides nothing, prices nothing and moves no money. The
 * employee is resolved from the caller's active link on every request, every statement carries the
 * employee predicate, and another employee's, a foreign or an unknown referral is the same 404.
 *
 * <p>Lock order: the referral row first (withdrawal, delivery claim, receipt and the database
 * guards all take it first), then its children. A referral is never both delivered and withdrawn.
 */
@Service
public class PartnerReferralService {

  /** Submission (idempotency operation). */
  public static final String SELF_SUBMIT = "partner-referral.submit";

  /** Withdrawal (idempotency operation). */
  public static final String SELF_WITHDRAW = "partner-referral.withdraw";

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 50;
  private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,3}$");
  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final IdempotentOperation.Spec SUBMIT_SPEC =
      new IdempotentOperation.Spec(SELF_SUBMIT, "partner_referral", "submit", "submitted", 201);
  private static final IdempotentOperation.Spec WITHDRAW_SPEC =
      new IdempotentOperation.Spec(SELF_WITHDRAW, "partner_referral", "withdraw", "withdrawn", 200);

  private final IdempotentOperation operations;
  private final EmployeeAccessLinks links;
  private final PartnerReferralDisclosures disclosures;
  private final ReferralStore store;
  private final PartnerReferralRecorder recorder;
  private final PartnerReferralQuotas quotas;
  private final PartnerReferralRepository catalogue;
  private final PartnerCrypto crypto;
  private final CursorCodec cursors;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param links the identity port
   * @param disclosures disclosure builder
   * @param store referral storage
   * @param recorder audit and outbox
   * @param quotas hourly quotas
   * @param catalogue catalogue storage (categories)
   * @param crypto sealing and keyed digests
   * @param cursors signed cursors
   * @param json JSON mapper
   */
  public PartnerReferralService(
      IdempotentOperation operations,
      EmployeeAccessLinks links,
      PartnerReferralDisclosures disclosures,
      ReferralStore store,
      PartnerReferralRecorder recorder,
      PartnerReferralQuotas quotas,
      PartnerReferralRepository catalogue,
      PartnerCrypto crypto,
      CursorCodec cursors,
      JsonMapper json) {
    this.operations = operations;
    this.links = links;
    this.disclosures = disclosures;
    this.store = store;
    this.recorder = recorder;
    this.quotas = quotas;
    this.catalogue = catalogue;
    this.crypto = crypto;
    this.cursors = cursors;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------
  // Submit
  // ------------------------------------------------------------------------------------------

  /**
   * Submits a consented referral, or replays an identical earlier submission without revalidation,
   * outbound access or key checks.
   *
   * @param caller verified employee
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the raw request object
   * @return the recorded referral
   */
  public IdempotentOperation.Result<View> submit(
      ReferralCaller caller, String idempotencyKey, JsonNode body) {
    Submit command =
        operations.validated(
            SUBMIT_SPEC,
            () -> {
              ReferralSubmissions.key(idempotencyKey);
              return ReferralSubmissions.submit(body);
            });
    try {
      return operations.execute(
          SUBMIT_SPEC,
          caller.subject(),
          idempotencyKey,
          command.canonical(caller.tenant(), crypto),
          View.class,
          TIMEOUT,
          () -> submitted(caller, command));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<View> submitted(ReferralCaller caller, Submit command) {
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    Instant now = disclosures.now();
    quotas.requireCommands(
        store.commitsSince(tenant, link.employeeId(), PartnerReferralQuotas.windowStart(now)),
        PartnerReferralQuotas.COMMITS_PER_HOUR,
        now);
    if (!PartnerReferralDomain.CONSENT_VERSION.equals(command.consentVersion())) {
      throw new ApiException(ErrorCode.PARTNER_REFERRAL_PREVIEW_STALE, Map.of());
    }
    Built built = disclosures.build(tenant, link, command.inputs());
    if (!built.blockers().isEmpty()) {
      throw new ApiException(ErrorCode.PARTNER_REFERRAL_PREVIEW_STALE, Map.of());
    }
    Instant expiresAt = command.expiresAt();
    String expected =
        disclosures.digest(tenant, link, built, command.inputs().language(), expiresAt);
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        command.previewDigest().getBytes(StandardCharsets.US_ASCII))) {
      throw new ApiException(ErrorCode.PARTNER_REFERRAL_PREVIEW_STALE, Map.of());
    }
    if (!expiresAt.isAfter(now)) {
      throw new ApiException(ErrorCode.PARTNER_REFERRAL_PREVIEW_EXPIRED, Map.of());
    }
    if (expiresAt.isAfter(disclosures.expiry(now))) {
      throw new ApiException(ErrorCode.PARTNER_REFERRAL_PREVIEW_STALE, Map.of());
    }
    if (store.previewUsed(tenant, link.employeeId(), expected)) {
      throw new ApiException(ErrorCode.PARTNER_REFERRAL_PREVIEW_ALREADY_USED, Map.of());
    }
    UUID id = UUID.randomUUID();
    String reference = newReference();
    Map<Field, String> values = new LinkedHashMap<>();
    for (Entry entry : built.entries()) {
      values.put(
          entry.field(),
          switch (entry.field()) {
            case REFERRAL_REFERENCE -> reference;
            case CONSENT_TIMESTAMP -> now.toString();
            default -> entry.value();
          });
    }
    List<String> fieldNames = values.keySet().stream().map(Field::name).toList();
    String disclosureDigest = disclosureDigest(values);
    byte[] sealed = seal(tenant, id, values);
    try {
      store.insert(
          tenant,
          new NewReferral(
              id,
              reference,
              link.employeeId(),
              built.product().partnerId(),
              built.product().productId(),
              built.product().versionId(),
              command.inputs().language(),
              expected,
              caller.subject(),
              now));
      store.insertDisclosure(tenant, id, fieldNames, sealed, disclosureDigest, now);
      store.insertConsent(
          tenant,
          id,
          command.inputs().language(),
          caller.subject(),
          disclosureDigest,
          expected,
          now);
      store.insertEvent(tenant, id, 1, null, "SUBMITTED", "EMPLOYEE", null, now);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.submitted(
        caller,
        new PartnerReferralRecorder.Change(
            id, "SUBMITTED", 1, null, 0, "EMPLOYEE", fieldNames, disclosureDigest, now));
    View view = view(tenant, store.mineById(tenant, link.employeeId(), id).orElseThrow());
    return new IdempotentOperation.Completed<>(view, id, Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Withdraw
  // ------------------------------------------------------------------------------------------

  /**
   * Withdraws a referral before the partner has, or may have, received it.
   *
   * @param caller verified employee
   * @param referralId raw opaque referral reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the raw request object
   * @return the withdrawn referral
   */
  public IdempotentOperation.Result<View> withdraw(
      ReferralCaller caller, String referralId, String idempotencyKey, JsonNode body) {
    record Parsed(String reference, Withdraw command) {}
    Parsed parsed =
        operations.validated(
            WITHDRAW_SPEC,
            () -> {
              ReferralSubmissions.key(idempotencyKey);
              Withdraw command = ReferralSubmissions.withdraw(body);
              return new Parsed(reference(referralId), command);
            });
    try {
      return operations.execute(
          WITHDRAW_SPEC,
          caller.subject(),
          idempotencyKey,
          parsed.command().canonical(caller.tenant(), parsed.reference()),
          View.class,
          TIMEOUT,
          () -> withdrawn(caller, parsed.reference(), parsed.command()));
    } catch (ApiException refused) {
      throw translate(refused);
    }
  }

  private IdempotentOperation.Completed<View> withdrawn(
      ReferralCaller caller, String reference, Withdraw command) {
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    Instant now = disclosures.now();
    quotas.requireCommands(
        store.withdrawalsSince(tenant, link.employeeId(), PartnerReferralQuotas.windowStart(now)),
        PartnerReferralQuotas.WITHDRAWALS_PER_HOUR,
        now);
    // The referral row is locked first: a delivery claim or receipt in flight holds the same lock.
    ReferralRow row =
        store
            .lockMine(tenant, link.employeeId(), reference)
            .orElseThrow(PartnerReferralService::notFound);
    if (row.version() != command.expectedVersion()) {
      throw new ApiException(ErrorCode.PARTNER_REFERRAL_VERSION_STALE, Map.of());
    }
    if (!withdrawable(row)) {
      throw new ApiException(ErrorCode.PARTNER_REFERRAL_NOT_WITHDRAWABLE, Map.of());
    }
    int next = row.version() + 1;
    try {
      store.markWithdrawn(tenant, row.id(), next, now);
      store.insertEvent(
          tenant,
          row.id(),
          next,
          row.state(),
          "WITHDRAWN",
          "EMPLOYEE",
          "WITHDRAWN_BEFORE_DELIVERY",
          now);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.withdrawn(
        caller,
        new PartnerReferralRecorder.Change(
            row.id(),
            "WITHDRAWN",
            next,
            "WITHDRAWN_BEFORE_DELIVERY",
            0,
            "EMPLOYEE",
            null,
            null,
            now));
    View view = view(tenant, store.mineById(tenant, link.employeeId(), row.id()).orElseThrow());
    return new IdempotentOperation.Completed<>(view, row.id(), Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * The employee's own referrals, newest first.
   *
   * @param caller verified employee
   * @param state raw state filter or {@code null}
   * @param cursor opaque cursor or {@code null}
   * @param limit raw page size or {@code null}
   * @return the page
   */
  @org.springframework.transaction.annotation.Transactional
  public Page list(ReferralCaller caller, String state, String cursor, String limit) {
    int size = limit(limit);
    String filter = null;
    if (state != null) {
      try {
        filter = PartnerReferralDomain.State.valueOf(state).name();
      } catch (IllegalArgumentException unknown) {
        throw ReferralCommands.fail("state", "FORMAT");
      }
    }
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    CursorScope scope = scope(caller, filter, size);
    Position after = null;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, scope);
      ReferralRow at =
          store
              .mineById(tenant, link.employeeId(), position.id())
              .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
      after = new Position(at.createdAt(), at.id());
    }
    List<ReferralRow> rows = store.page(tenant, link.employeeId(), filter, after, size + 1);
    List<ReferralRow> shown = rows.subList(0, Math.min(rows.size(), size));
    String next =
        rows.size() > size
            ? cursors.encode(scope, new KeysetPosition("PRF", shown.get(shown.size() - 1).id()))
            : null;
    return new Page(shown.stream().map(this::summary).toList(), next);
  }

  /**
   * One of the employee's own referrals.
   *
   * @param caller verified employee
   * @param referralId raw opaque referral reference
   * @return the view
   */
  @org.springframework.transaction.annotation.Transactional
  public View get(ReferralCaller caller, String referralId) {
    String reference = reference(referralId);
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    ReferralRow row =
        store
            .mine(tenant, link.employeeId(), reference)
            .orElseThrow(PartnerReferralService::notFound);
    return view(tenant, row);
  }

  // ------------------------------------------------------------------------------------------
  // Views and helpers
  // ------------------------------------------------------------------------------------------

  static boolean withdrawable(ReferralRow row) {
    return switch (row.state()) {
      case "SUBMITTED", "DELIVERY_FAILED" -> true;
      case "DELIVERY_PENDING" -> !row.leased();
      default -> false;
    };
  }

  private Summary summary(ReferralRow row) {
    return new Summary(
        row.reference(),
        row.state(),
        row.version(),
        withdrawable(row),
        row.productReference(),
        new Bilingual(row.nameEn(), row.nameFr()),
        row.partnerName(),
        row.productType(),
        catalogue.categories(row.partnerId(), row.primaryCategory()),
        row.createdAt(),
        row.updatedAt());
  }

  private View view(TenantId tenant, ReferralRow row) {
    List<Event> events = new ArrayList<>();
    String lastOutcome = null;
    for (EventRow event : store.events(tenant, row.id())) {
      events.add(
          new Event(
              event.sequence(),
              event.fromState(),
              event.toState(),
              event.actorRole(),
              event.outcome(),
              event.occurredAt()));
      lastOutcome = event.outcome();
    }
    return new View(
        row.reference(),
        row.state(),
        row.version(),
        withdrawable(row),
        row.productReference(),
        new Bilingual(row.nameEn(), row.nameFr()),
        row.partnerName(),
        row.productType(),
        catalogue.categories(row.partnerId(), row.primaryCategory()),
        row.language(),
        row.createdAt(),
        row.updatedAt(),
        lastOutcome,
        PartnerReferralDomain.CONSENT_VERSION,
        store.disclosedFields(tenant, row.id()),
        List.copyOf(events),
        PartnerReferralBodies.NOTICE);
  }

  private SelfLink link(ReferralCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  private static CursorScope scope(ReferralCaller caller, String state, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "own-referrals");
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    if (state != null) {
      filters.put("state", state);
    }
    return new CursorScope("partner-referral.list", caller.tenant(), filters);
  }

  private static String reference(String raw) {
    if (raw == null || !PartnerReferralDomain.REFERRAL_REFERENCE.matcher(raw).matches()) {
      throw notFound();
    }
    return raw;
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.PARTNER_REFERRAL_NOT_FOUND, Map.of());
  }

  private static int limit(String raw) {
    if (raw == null || raw.isEmpty()) {
      return DEFAULT_LIMIT;
    }
    if (!DIGITS.matcher(raw).matches()) {
      throw ReferralCommands.fail("limit", "FORMAT");
    }
    int value = Integer.parseInt(raw);
    if (value < 1 || value > MAX_LIMIT) {
      throw ReferralCommands.fail("limit", "RANGE");
    }
    return value;
  }

  private static String newReference() {
    StringBuilder reference = new StringBuilder("PR-");
    for (int i = 0; i < 16; i++) {
      reference.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return reference.toString();
  }

  private static String disclosureDigestText(Map<Field, String> values) {
    StringBuilder canonical = new StringBuilder("v1");
    values.forEach(
        (field, value) ->
            canonical
                .append('\n')
                .append(field.name())
                .append('=')
                .append(value.length())
                .append(':')
                .append(value));
    return canonical.toString();
  }

  private String disclosureDigest(Map<Field, String> values) {
    return crypto.digest("partner-referral-disclosure", disclosureDigestText(values));
  }

  private byte[] seal(TenantId tenant, UUID referralId, Map<Field, String> values) {
    Map<String, String> plain = new LinkedHashMap<>();
    values.forEach((field, value) -> plain.put(field.name(), value));
    return crypto.seal(
        json.writeValueAsBytes(plain), "partner-referral:" + tenant + ":" + referralId);
  }

  /** A key reused with different input is the referral-specific conflict. */
  private static ApiException translate(ApiException refused) {
    if (refused.code() == ErrorCode.IDEMPOTENCY_KEY_REUSED) {
      return new ApiException(ErrorCode.PARTNER_REFERRAL_IDEMPOTENCY_REUSED, Map.of());
    }
    return refused;
  }

  /** A guard that still fires is a lost race or a defect; no database text is exposed. */
  private static RuntimeException integrity(DataIntegrityViolationException violated) {
    String constraint = constraint(violated);
    if (constraint != null) {
      switch (constraint) {
        case "integrations_partner_referral_preview_key" -> {
          return new ApiException(ErrorCode.PARTNER_REFERRAL_PREVIEW_ALREADY_USED, Map.of());
        }
        case "integrations_partner_referral_product" -> {
          return new ApiException(ErrorCode.PARTNER_REFERRAL_PRODUCT_UNAVAILABLE, Map.of());
        }
        case "integrations_partner_referral_transition" -> {
          return new ApiException(ErrorCode.PARTNER_REFERRAL_NOT_WITHDRAWABLE, Map.of());
        }
        default -> {
          // a defect: fall through to the unexpected-failure path
        }
      }
    }
    return violated;
  }

  private static String constraint(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof org.postgresql.util.PSQLException psql) {
        org.postgresql.util.ServerErrorMessage server = psql.getServerErrorMessage();
        if (server != null) {
          return server.getConstraint();
        }
      }
    }
    return null;
  }
}
