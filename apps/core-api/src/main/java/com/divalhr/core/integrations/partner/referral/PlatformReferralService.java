package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.PartnerCrypto;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.Event;
import com.divalhr.core.integrations.partner.referral.PlatformReferralBodies.Contact;
import com.divalhr.core.integrations.partner.referral.PlatformReferralBodies.History;
import com.divalhr.core.integrations.partner.referral.PlatformReferralBodies.Page;
import com.divalhr.core.integrations.partner.referral.PlatformReferralBodies.Referral;
import com.divalhr.core.integrations.partner.referral.PlatformReferralStore.Row;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only operational view of partner referrals for platform administrators with exact MFA
 * (MVP-120F, D120F-5). Operational status only: the employee's contact fields are masked, there is
 * no name and no disclosed value, and nothing here can approve, edit, resend, override or delete.
 * Reading one referral is audited by reference.
 */
@Service
public class PlatformReferralService {

  /** Operation names. */
  public static final String LIST = "partner-referral.platform.list";

  public static final String GET = "partner-referral.platform.get";
  public static final String HISTORY = "partner-referral.platform.history";

  private static final UUID PLATFORM_SCOPE = new UUID(0L, 0L);
  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 50;
  private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,3}$");
  private static final Set<String> CATEGORIES =
      Set.of(
          "BANK",
          "MICROFINANCE",
          "INSURANCE",
          "HEALTH_BENEFITS",
          "TELECOM",
          "MOBILE_MONEY_PAYMENT",
          "PENSION_SAVINGS",
          "OTHER_REGULATED_PROVIDER");

  private final PlatformReferralStore store;
  private final PartnerReferralRepository catalogue;
  private final PartnerCrypto crypto;
  private final PartnerReferralRecorder recorder;
  private final CursorCodec cursors;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param store cross-tenant reads
   * @param catalogue catalogue storage (categories)
   * @param crypto opens the sealed disclosure for masking only
   * @param recorder audit
   * @param cursors signed cursors
   * @param json JSON mapper
   */
  public PlatformReferralService(
      PlatformReferralStore store,
      PartnerReferralRepository catalogue,
      PartnerCrypto crypto,
      PartnerReferralRecorder recorder,
      CursorCodec cursors,
      JsonMapper json) {
    this.store = store;
    this.catalogue = catalogue;
    this.crypto = crypto;
    this.recorder = recorder;
    this.cursors = cursors;
    this.json = json;
  }

  /**
   * The operational queue.
   *
   * @param subject verified platform administrator
   * @param state raw state filter or {@code null}
   * @param category raw category filter or {@code null}
   * @param cursor opaque cursor or {@code null}
   * @param limit raw page size or {@code null}
   * @return the page
   */
  @Transactional(readOnly = true)
  public Page list(String subject, String state, String category, String cursor, String limit) {
    int size = limit(limit);
    String stateFilter = null;
    if (state != null) {
      try {
        stateFilter = PartnerReferralDomain.State.valueOf(state).name();
      } catch (IllegalArgumentException unknown) {
        throw ReferralCommands.fail("state", "FORMAT");
      }
    }
    if (category != null && !CATEGORIES.contains(category)) {
      throw ReferralCommands.fail("category", "FORMAT");
    }
    Map<String, String> filters = new TreeMap<>();
    filters.put("role", "platform-admin");
    filters.put("subject", subject);
    filters.put("limit", Integer.toString(size));
    if (stateFilter != null) {
      filters.put("state", stateFilter);
    }
    if (category != null) {
      filters.put("category", category);
    }
    CursorScope scope = new CursorScope(LIST, new TenantId(PLATFORM_SCOPE), filters);
    ReferralStore.Position after = null;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, scope);
      Row at =
          store
              .byId(position.id())
              .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
      after = new ReferralStore.Position(at.createdAt(), at.id());
    }
    List<Row> rows = store.page(stateFilter, category, after, size + 1);
    List<Row> shown = rows.subList(0, Math.min(rows.size(), size));
    String next =
        rows.size() > size
            ? cursors.encode(scope, new KeysetPosition("PRF", shown.get(shown.size() - 1).id()))
            : null;
    return new Page(shown.stream().map(this::view).toList(), next);
  }

  /**
   * One referral, operational view; the read is audited by reference.
   *
   * @param caller verified platform administrator
   * @param referralId raw opaque referral reference
   * @return the referral
   */
  @Transactional
  public Referral get(PlatformCaller caller, String referralId) {
    Row row =
        store.byReference(reference(referralId)).orElseThrow(PlatformReferralService::notFound);
    recorder.operatorRead(caller.subject(), caller.correlationId(), row.id(), Instant.now());
    return view(row);
  }

  /**
   * The append-only history of one referral.
   *
   * @param referralId raw opaque referral reference
   * @return the history
   */
  @Transactional(readOnly = true)
  public History history(String referralId) {
    Row row =
        store.byReference(reference(referralId)).orElseThrow(PlatformReferralService::notFound);
    List<Event> events =
        store.events(row.tenantId(), row.id()).stream()
            .map(
                e ->
                    new Event(
                        e.sequence(),
                        e.fromState(),
                        e.toState(),
                        e.actorRole(),
                        e.outcome(),
                        e.occurredAt()))
            .toList();
    return new History(row.reference(), events);
  }

  private Referral view(Row row) {
    return new Referral(
        row.reference(),
        row.tenantId(),
        row.partnerName(),
        row.productType(),
        catalogue.categories(row.partnerId(), row.primaryCategory()),
        row.state(),
        row.version(),
        row.createdAt(),
        row.updatedAt(),
        row.lastOutcome(),
        row.attemptCount(),
        contact(row));
  }

  private Contact contact(Row row) {
    JsonNode values =
        json.readTree(
            crypto.open(
                row.sealed(), "partner-referral:" + new TenantId(row.tenantId()) + ":" + row.id()));
    JsonNode phone = values.get("EMPLOYEE_PHONE");
    return new Contact(
        maskEmail(values.path("EMPLOYEE_EMAIL").asString("")),
        phone == null || phone.isNull() ? null : maskPhone(phone.asString("")));
  }

  /** {@code a***@example.test}: first character of the local part and the domain. */
  static String maskEmail(String email) {
    int at = email.indexOf('@');
    if (at < 1) {
      return "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }

  /** {@code +243*****789}: the country prefix and the last three digits. */
  static String maskPhone(String phone) {
    if (phone.length() < 8) {
      return "*****";
    }
    return phone.substring(0, 4) + "*****" + phone.substring(phone.length() - 3);
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

  /**
   * The verified platform administrator.
   *
   * @param subject JWT subject
   * @param correlationId request correlation id
   */
  public record PlatformCaller(String subject, String correlationId) {}
}
