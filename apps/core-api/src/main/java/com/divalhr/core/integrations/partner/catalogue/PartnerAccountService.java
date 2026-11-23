package com.divalhr.core.integrations.partner.catalogue;

import com.divalhr.core.integrations.partner.PartnerCrypto;
import com.divalhr.core.integrations.partner.PartnerDomain;
import com.divalhr.core.integrations.partner.PartnerReviewService.Caller;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.Bilingual;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.Content;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CreateReceipt;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CurrencyLimit;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.HistoryEntry;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.HistoryPage;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.PartnerDetail;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.PartnerPage;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.PartnerSummary;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductDetail;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductHistoryEntry;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductHistoryPage;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductList;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductSummary;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductTransitionReceipt;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductVersion;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ReadyApplication;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ReadyApplicationPage;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.TransitionReceipt;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountCommands.DraftSave;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountCommands.PartnerTransition;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountCommands.ProductCreate;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountCommands.ProductTransition;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountDomain.PartnerAction;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountDomain.PartnerStatus;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountDomain.ProductAction;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountDomain.ProductStatus;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountDomain.ProductType;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountRepository.EventRow;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountRepository.PartnerRow;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountRepository.ProductEventRow;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountRepository.ProductRow;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountRepository.ReadyRow;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountRepository.SummaryRow;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountRepository.VersionRow;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Partner accounts and the controlled product catalogue for platform administrators (MVP-120E, ADR
 * 0044). Every operation is authorized before it gets here (platform-admin with exact MFA). A
 * partner is created only from an application that is exactly approved for onboarding, copying only
 * the approved business identity; creating, activating, publishing or pausing connects nothing,
 * issues no credential or invitation and reads no employee data. Audit entries and history carry
 * references, statuses and codes only; notes are encrypted and never returned, audited or logged.
 */
@Service
public class PartnerAccountService {

  static final String READY = "partner.account-ready";
  static final String LIST = "partner.account-list";
  static final String VIEW = "partner.account-view";
  static final String HISTORY = "partner.account-history";
  static final String PRODUCTS = "partner.product-list";
  static final String PRODUCT_VIEW = "partner.product-view";
  static final String PRODUCT_HISTORY = "partner.product-history";
  static final String CREATE = "partner.account-create";
  static final String TRANSITION = "partner.account-transition";
  static final String PRODUCT_CREATE = "partner.product-create";
  static final String PRODUCT_SAVE = "partner.product-save";
  static final String PRODUCT_TRANSITION = "partner.product-transition";

  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 50;
  private static final int MAX_PRODUCTS = 200;
  private static final Pattern APPLICATION_REFERENCE =
      Pattern.compile("^PA-[0-9A-HJKMNP-TV-Z]{12}$");
  private static final Pattern PARTNER_REFERENCE = Pattern.compile("^PT-[0-9A-HJKMNP-TV-Z]{16}$");
  private static final Pattern PRODUCT_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,39}$");
  private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,3}$");
  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final UUID PLATFORM_SCOPE = new UUID(0L, 0L);
  private static final String ROLE = "platform-admin";

  private final PartnerAccountRepository repository;
  private final PartnerCrypto crypto;
  private final AuditRecorder audit;
  private final CursorCodec cursors;
  private final IdempotentOperation operations;
  private final TransactionTemplate transactions;
  private final JsonMapper json;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param repository storage
   * @param crypto encryption and digests
   * @param audit audit recorder
   * @param cursors signed cursors
   * @param operations idempotent command flow
   * @param transactions transaction template
   * @param json JSON mapper
   * @param clock clock
   */
  public PartnerAccountService(
      PartnerAccountRepository repository,
      PartnerCrypto crypto,
      AuditRecorder audit,
      CursorCodec cursors,
      IdempotentOperation operations,
      TransactionTemplate transactions,
      JsonMapper json,
      Clock clock) {
    this.repository = repository;
    this.crypto = crypto;
    this.audit = audit;
    this.cursors = cursors;
    this.operations = operations;
    this.transactions = transactions;
    this.json = json;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ reads

  /**
   * Applications approved for onboarding and their partner, if created.
   *
   * @param caller verified administrator
   * @param cursor opaque cursor
   * @param limit raw page size
   * @return the page
   */
  public ReadyApplicationPage ready(Caller caller, String cursor, String limit) {
    int size = limit(limit);
    CursorScope scope =
        scope(READY, caller, Map.of("view", "ready", "limit", Integer.toString(size)));
    return transactions.execute(
        status -> {
          Instant afterAt = null;
          UUID afterId = null;
          if (cursor != null) {
            KeysetPosition position = cursors.decode(cursor, scope);
            afterId = position.id();
            afterAt =
                repository
                    .approvedAt(position.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
          }
          List<ReadyRow> rows = repository.readyApplications(afterAt, afterId, size + 1);
          List<ReadyRow> shown = rows.subList(0, Math.min(rows.size(), size));
          String next =
              rows.size() > size
                  ? cursors.encode(
                      scope, new KeysetPosition("PA", shown.get(shown.size() - 1).id()))
                  : null;
          List<ReadyApplication> items = new ArrayList<>();
          for (ReadyRow row : shown) {
            items.add(
                new ReadyApplication(
                    row.reference(),
                    row.legalName(),
                    row.tradingName(),
                    row.country(),
                    row.category(),
                    row.approvedAt(),
                    row.partnerReference()));
          }
          disclose(
              caller,
              READY,
              "partner",
              PLATFORM_SCOPE,
              Map.of("view", "ready", "resultCount", items.size()));
          return new ReadyApplicationPage(items, next);
        });
  }

  /**
   * A page of partners.
   *
   * @param caller verified administrator
   * @param cursor opaque cursor
   * @param limit raw page size
   * @param status raw status filter
   * @param category raw category filter
   * @return the page
   */
  public PartnerPage list(
      Caller caller, String cursor, String limit, String status, String category) {
    int size = limit(limit);
    String statusFilter = null;
    if (status != null) {
      PartnerStatus parsed = PartnerAccountDomain.parse(PartnerStatus.class, status);
      if (parsed == null) {
        throw PartnerAccountCommands.shape("status", "FORMAT");
      }
      statusFilter = parsed.name();
    }
    String categoryFilter = null;
    if (category != null) {
      if (PartnerDomain.parse(PartnerDomain.Category.class, category) == null) {
        throw PartnerAccountCommands.shape("category", "FORMAT");
      }
      categoryFilter = category;
    }
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "list");
    filters.put("limit", Integer.toString(size));
    if (statusFilter != null) {
      filters.put("status", statusFilter);
    }
    if (categoryFilter != null) {
      filters.put("category", categoryFilter);
    }
    CursorScope scope = scope(LIST, caller, filters);
    String fStatus = statusFilter;
    String fCategory = categoryFilter;
    return transactions.execute(
        s -> {
          Instant afterAt = null;
          UUID afterId = null;
          if (cursor != null) {
            KeysetPosition position = cursors.decode(cursor, scope);
            PartnerRow at =
                repository
                    .findById(position.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
            afterAt = at.createdAt();
            afterId = at.id();
          }
          List<PartnerRow> rows = repository.page(fStatus, fCategory, afterAt, afterId, size + 1);
          List<PartnerRow> shown = rows.subList(0, Math.min(rows.size(), size));
          String next =
              rows.size() > size
                  ? cursors.encode(
                      scope, new KeysetPosition("PT", shown.get(shown.size() - 1).id()))
                  : null;
          List<PartnerSummary> items = new ArrayList<>();
          for (PartnerRow row : shown) {
            int[] counts = repository.productCounts(row.id());
            items.add(
                new PartnerSummary(
                    row.reference(),
                    row.status(),
                    row.legalName(),
                    row.tradingName(),
                    row.countryOfRegistration(),
                    row.primaryCategory(),
                    counts[0],
                    counts[1],
                    row.createdAt(),
                    row.updatedAt()));
          }
          disclose(
              caller,
              LIST,
              "partner",
              PLATFORM_SCOPE,
              Map.of("view", "list", "resultCount", items.size()));
          return new PartnerPage(items, next, repository.statusCounts(fCategory));
        });
  }

  /**
   * One partner.
   *
   * @param caller verified administrator
   * @param reference raw reference
   * @return the partner
   */
  public PartnerDetail get(Caller caller, String reference) {
    String ref = partnerReference(reference);
    return transactions.execute(
        s -> {
          PartnerRow row = findPartner(ref);
          disclose(caller, VIEW, "partner", row.id(), Map.of("reference", row.reference()));
          return detail(row);
        });
  }

  /**
   * The history of a partner.
   *
   * @param caller verified administrator
   * @param reference raw reference
   * @param cursor opaque cursor
   * @param limit raw page size
   * @return the page
   */
  public HistoryPage history(Caller caller, String reference, String cursor, String limit) {
    String ref = partnerReference(reference);
    int size = limit(limit);
    CursorScope scope =
        scope(
            HISTORY,
            caller,
            Map.of("view", "history", "reference", ref, "limit", Integer.toString(size)));
    return transactions.execute(
        s -> {
          PartnerRow row = findPartner(ref);
          int after =
              cursor == null
                  ? 0
                  : (int) cursors.decode(cursor, scope).id().getLeastSignificantBits();
          List<EventRow> rows = repository.events(row.id(), after, size + 1);
          List<EventRow> shown = rows.subList(0, Math.min(rows.size(), size));
          String next =
              rows.size() > size
                  ? cursors.encode(
                      scope,
                      new KeysetPosition(
                          "SEQ", new UUID(0L, shown.get(shown.size() - 1).sequence())))
                  : null;
          List<HistoryEntry> items = new ArrayList<>();
          for (EventRow e : shown) {
            items.add(
                new HistoryEntry(
                    e.sequence(),
                    e.eventType(),
                    e.fromStatus(),
                    e.toStatus(),
                    e.reasonCode(),
                    e.noteRecorded(),
                    e.itemCount(),
                    "PLATFORM_ADMIN",
                    e.occurredAt()));
          }
          disclose(
              caller,
              HISTORY,
              "partner",
              row.id(),
              Map.of("reference", row.reference(), "resultCount", items.size()));
          return new HistoryPage(items, next);
        });
  }

  /**
   * The products of a partner.
   *
   * @param caller verified administrator
   * @param reference raw partner reference
   * @return the products
   */
  public ProductList products(Caller caller, String reference) {
    String ref = partnerReference(reference);
    return transactions.execute(
        s -> {
          PartnerRow row = findPartner(ref);
          List<ProductSummary> items = new ArrayList<>();
          for (SummaryRow summary : repository.summaries(row.id())) {
            items.add(
                new ProductSummary(
                    summary.product().productCode(),
                    summary.product().productType(),
                    summary.product().publicReference(),
                    summary.latestVersion(),
                    summary.latestStatus(),
                    summary.liveVersion(),
                    summary.liveStatus(),
                    summary.name()));
          }
          disclose(
              caller,
              PRODUCTS,
              "partner",
              row.id(),
              Map.of("reference", row.reference(), "resultCount", items.size()));
          return new ProductList(items);
        });
  }

  /**
   * One product with all versions.
   *
   * @param caller verified administrator
   * @param reference raw partner reference
   * @param code raw product code
   * @return the product
   */
  public ProductDetail product(Caller caller, String reference, String code) {
    String ref = partnerReference(reference);
    String productCode = productCode(code);
    return transactions.execute(
        s -> {
          PartnerRow row = findPartner(ref);
          ProductRow product = findProduct(row, productCode);
          disclose(
              caller,
              PRODUCT_VIEW,
              "partner-product",
              product.id(),
              Map.of("reference", row.reference(), "productCode", product.productCode()));
          return productDetail(row, product);
        });
  }

  /**
   * The history of a product across all versions.
   *
   * @param caller verified administrator
   * @param reference raw partner reference
   * @param code raw product code
   * @param cursor opaque cursor
   * @param limit raw page size
   * @return the page
   */
  public ProductHistoryPage productHistory(
      Caller caller, String reference, String code, String cursor, String limit) {
    String ref = partnerReference(reference);
    String productCode = productCode(code);
    int size = limit(limit);
    CursorScope scope =
        scope(
            PRODUCT_HISTORY,
            caller,
            Map.of(
                "view",
                "history",
                "reference",
                ref,
                "product",
                productCode,
                "limit",
                Integer.toString(size)));
    return transactions.execute(
        s -> {
          PartnerRow row = findPartner(ref);
          ProductRow product = findProduct(row, productCode);
          int after =
              cursor == null
                  ? 0
                  : (int) cursors.decode(cursor, scope).id().getLeastSignificantBits();
          List<ProductEventRow> rows = repository.productEvents(product.id(), after, size + 1);
          List<ProductEventRow> shown = rows.subList(0, Math.min(rows.size(), size));
          String next =
              rows.size() > size
                  ? cursors.encode(
                      scope,
                      new KeysetPosition(
                          "SEQ", new UUID(0L, shown.get(shown.size() - 1).sequence())))
                  : null;
          List<ProductHistoryEntry> items = new ArrayList<>();
          for (ProductEventRow e : shown) {
            items.add(
                new ProductHistoryEntry(
                    e.sequence(),
                    e.version(),
                    e.eventType(),
                    e.fromStatus(),
                    e.toStatus(),
                    e.reasonCode(),
                    e.noteRecorded(),
                    "SUPERSEDED".equals(e.eventType()) ? "SYSTEM" : "PLATFORM_ADMIN",
                    e.occurredAt()));
          }
          disclose(
              caller,
              PRODUCT_HISTORY,
              "partner-product",
              product.id(),
              Map.of("reference", row.reference(), "resultCount", items.size()));
          return new ProductHistoryPage(items, next);
        });
  }

  // --------------------------------------------------------------- commands

  /**
   * Creates the partner of an approved application, idempotently.
   *
   * @param caller verified administrator
   * @param applicationReference raw application reference
   * @param idempotencyKey the header
   * @param body parsed body
   * @return the receipt
   */
  public IdempotentOperation.Result<CreateReceipt> create(
      Caller caller, String applicationReference, String idempotencyKey, JsonNode body) {
    IdempotentOperation.Spec spec =
        new IdempotentOperation.Spec(CREATE, "partner_account", "create", "created", 200);
    String reference =
        operations.validated(
            spec,
            () -> {
              key(idempotencyKey);
              PartnerAccountCommands.parseCreate(body);
              return applicationReference(applicationReference);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("applicationReference", reference);
    canonical.put("confirmed", true);
    return operations.execute(
        spec,
        caller.subject(),
        idempotencyKey,
        canonical,
        CreateReceipt.class,
        TIMEOUT,
        () -> runCreate(caller, reference));
  }

  private IdempotentOperation.Completed<CreateReceipt> runCreate(Caller caller, String reference) {
    Instant at = now();
    var application =
        repository
            .lockApplication(reference)
            .orElseThrow(() -> new ApiException(ErrorCode.PARTNER_APPLICATION_NOT_FOUND, Map.of()));
    Optional<PartnerRow> existing = repository.findByApplication(application.id());
    if (existing.isPresent()) {
      PartnerRow row = existing.get();
      return new IdempotentOperation.Completed<>(
          new CreateReceipt(false, detail(row)), row.id(), Outcome.UNCHANGED);
    }
    if (!"APPROVED_FOR_ONBOARDING".equals(application.status())) {
      throw new ApiException(ErrorCode.PARTNER_SOURCE_APPLICATION_NOT_APPROVED, Map.of());
    }
    UUID id = UUID.randomUUID();
    String partnerReference = "PT-" + random(16);
    try {
      repository.insertFromApplication(id, partnerReference, application.id(), at);
      repository.appendEvent(id, "CREATED", null, "DRAFT", null, null, null, caller.subject(), at);
    } catch (DataIntegrityViolationException violated) {
      Optional<PartnerRow> raced = repository.findByApplication(application.id());
      if (raced.isPresent()) {
        return new IdempotentOperation.Completed<>(
            new CreateReceipt(false, detail(raced.get())), raced.get().id(), Outcome.UNCHANGED);
      }
      throw new ApiException(ErrorCode.PARTNER_SOURCE_APPLICATION_NOT_APPROVED, Map.of());
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reference", partnerReference);
    metadata.put("applicationReference", application.reference());
    metadata.put("toStatus", "DRAFT");
    audited(caller, CREATE, "partner", id, metadata, at);
    PartnerRow created = repository.findById(id).orElseThrow();
    return new IdempotentOperation.Completed<>(
        new CreateReceipt(true, detail(created)), id, Outcome.CREATED);
  }

  /**
   * Activates, suspends, reactivates or closes a partner, or amends its scope.
   *
   * @param caller verified administrator
   * @param reference raw partner reference
   * @param idempotencyKey the header
   * @param body parsed body
   * @return the receipt
   */
  public IdempotentOperation.Result<TransitionReceipt> transition(
      Caller caller, String reference, String idempotencyKey, JsonNode body) {
    IdempotentOperation.Spec spec =
        new IdempotentOperation.Spec(
            TRANSITION, "partner_account", "transition", "transitioned", 200);
    record Parsed(String reference, PartnerTransition command) {}
    Parsed parsed =
        operations.validated(
            spec,
            () -> {
              key(idempotencyKey);
              return new Parsed(
                  partnerReference(reference), PartnerAccountCommands.parseTransition(body));
            });
    PartnerTransition c = parsed.command();
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("reference", parsed.reference());
    canonical.put("action", c.action().name());
    canonical.put("expectedVersion", c.expectedVersion());
    canonical.put("reason", c.reason() == null ? "" : c.reason().name());
    canonical.put("note", c.note() == null ? "" : crypto.digest("note", c.note()));
    canonical.put("countries", String.join(",", c.countries().stream().sorted().toList()));
    return operations.execute(
        spec,
        caller.subject(),
        idempotencyKey,
        canonical,
        TransitionReceipt.class,
        TIMEOUT,
        () -> runTransition(caller, parsed.reference(), c));
  }

  private IdempotentOperation.Completed<TransitionReceipt> runTransition(
      Caller caller, String reference, PartnerTransition c) {
    Instant at = now();
    PartnerRow row =
        repository.lockByReference(reference).orElseThrow(PartnerAccountService::partnerNotFound);
    PartnerStatus from = PartnerStatus.valueOf(row.status());
    if (row.version() != c.expectedVersion() || !c.action().allowedFrom(from)) {
      throw new ApiException(ErrorCode.PARTNER_TRANSITION_INVALID, Map.of());
    }
    if (c.action() == PartnerAction.CLOSE && repository.productCounts(row.id())[1] > 0) {
      throw new ApiException(ErrorCode.PARTNER_HAS_ACTIVE_PRODUCTS, Map.of());
    }
    PartnerStatus to = c.action().target(from);
    byte[] note =
        c.note() == null ? null : seal(c.note(), "partner", row.id(), c.action().eventType());
    String reason = c.reason() == null ? null : c.reason().name();
    Integer items = null;
    int sequence;
    try {
      if (c.action() == PartnerAction.AMEND_SCOPE) {
        List<String> existing = repository.countries(row.id());
        List<String> add = c.countries().stream().filter(code -> !existing.contains(code)).toList();
        if (add.isEmpty()) {
          throw new ApiException(ErrorCode.PARTNER_TRANSITION_INVALID, Map.of());
        }
        repository.addCountries(row.id(), add, at);
        items = add.size();
        sequence =
            repository.appendEvent(
                row.id(),
                c.action().eventType(),
                from.name(),
                to.name(),
                null,
                null,
                items,
                caller.subject(),
                at);
        if (repository.touch(row.id(), row.version(), at) != 1) {
          throw new ApiException(ErrorCode.PARTNER_TRANSITION_INVALID, Map.of());
        }
      } else {
        if (repository.updateStatus(row.id(), row.version(), to.name(), at) != 1) {
          throw new ApiException(ErrorCode.PARTNER_TRANSITION_INVALID, Map.of());
        }
        sequence =
            repository.appendEvent(
                row.id(),
                c.action().eventType(),
                from.name(),
                to.name(),
                reason,
                note,
                null,
                caller.subject(),
                at);
      }
    } catch (DataIntegrityViolationException violated) {
      throw new ApiException(
          c.action() == PartnerAction.CLOSE
              ? ErrorCode.PARTNER_HAS_ACTIVE_PRODUCTS
              : ErrorCode.PARTNER_TRANSITION_INVALID,
          Map.of());
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reference", row.reference());
    metadata.put("action", c.action().name());
    metadata.put("fromStatus", from.name());
    metadata.put("toStatus", to.name());
    if (reason != null) {
      metadata.put("reasonCode", reason);
    }
    if (items != null) {
      metadata.put("itemCount", items);
    }
    audited(caller, TRANSITION, "partner", row.id(), metadata, at);
    return new IdempotentOperation.Completed<>(
        new TransitionReceipt(detail(repository.findById(row.id()).orElseThrow()), sequence),
        row.id(),
        Outcome.UPDATED);
  }

  /**
   * Creates a product with its first draft version.
   *
   * @param caller verified administrator
   * @param reference raw partner reference
   * @param idempotencyKey the header
   * @param body parsed body
   * @return the product
   */
  public IdempotentOperation.Result<ProductDetail> createProduct(
      Caller caller, String reference, String idempotencyKey, JsonNode body) {
    IdempotentOperation.Spec spec =
        new IdempotentOperation.Spec(PRODUCT_CREATE, "partner_product", "create", "created", 200);
    record Parsed(String reference, ProductCreate command) {}
    Parsed parsed =
        operations.validated(
            spec,
            () -> {
              key(idempotencyKey);
              return new Parsed(
                  partnerReference(reference), PartnerAccountCommands.parseProductCreate(body));
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("reference", parsed.reference());
    canonical.put("productCode", parsed.command().code());
    canonical.put("productType", parsed.command().type().name());
    canonical.put("content", contentDigest(parsed.command().content()));
    return operations.execute(
        spec,
        caller.subject(),
        idempotencyKey,
        canonical,
        ProductDetail.class,
        TIMEOUT,
        () -> runCreateProduct(caller, parsed.reference(), parsed.command()));
  }

  private IdempotentOperation.Completed<ProductDetail> runCreateProduct(
      Caller caller, String reference, ProductCreate c) {
    Instant at = now();
    PartnerRow partner =
        repository.lockByReference(reference).orElseThrow(PartnerAccountService::partnerNotFound);
    if ("CLOSED".equals(partner.status())) {
      throw new ApiException(ErrorCode.PARTNER_PRODUCT_PARTNER_INACTIVE, Map.of());
    }
    checkScope(partner, c.content());
    if (repository.product(partner.id(), c.code()).isPresent()) {
      throw new ApiException(ErrorCode.PARTNER_TRANSITION_INVALID, Map.of());
    }
    if (repository.productCounts(partner.id())[0] >= MAX_PRODUCTS) {
      throw PartnerAccountCommands.invalid("partner.products", "LIMIT");
    }
    UUID productId = UUID.randomUUID();
    try {
      repository.insertProduct(
          productId, partner.id(), c.code(), "PP-" + random(16), c.type().name(), at);
      repository.insertDraft(UUID.randomUUID(), productId, 1, c.content(), at);
      repository.appendProductEvent(
          productId, 1, "VERSION_CREATED", null, "DRAFT", null, null, caller.subject(), at);
    } catch (DataIntegrityViolationException violated) {
      throw new ApiException(ErrorCode.PARTNER_TRANSITION_INVALID, Map.of());
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reference", partner.reference());
    metadata.put("productCode", c.code());
    metadata.put("productType", c.type().name());
    metadata.put("version", 1);
    audited(caller, PRODUCT_CREATE, "partner-product", productId, metadata, at);
    ProductRow product = repository.product(partner.id(), c.code()).orElseThrow();
    return new IdempotentOperation.Completed<>(
        productDetail(partner, product), productId, Outcome.CREATED);
  }

  /**
   * Saves the draft of a product, creating a new version when only a published one exists.
   *
   * @param caller verified administrator
   * @param reference raw partner reference
   * @param code raw product code
   * @param idempotencyKey the header
   * @param body parsed body
   * @return the product
   */
  public IdempotentOperation.Result<ProductDetail> saveDraft(
      Caller caller, String reference, String code, String idempotencyKey, JsonNode body) {
    IdempotentOperation.Spec spec =
        new IdempotentOperation.Spec(PRODUCT_SAVE, "partner_product", "save", "saved", 200);
    record Parsed(String reference, String code, DraftSave command) {}
    Parsed parsed =
        operations.validated(
            spec,
            () -> {
              key(idempotencyKey);
              return new Parsed(
                  partnerReference(reference),
                  productCode(code),
                  PartnerAccountCommands.parseDraft(body));
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("reference", parsed.reference());
    canonical.put("productCode", parsed.code());
    canonical.put("expectedVersion", parsed.command().expectedVersion());
    canonical.put("expectedRowVersion", parsed.command().expectedRowVersion());
    canonical.put("content", contentDigest(parsed.command().content()));
    return operations.execute(
        spec,
        caller.subject(),
        idempotencyKey,
        canonical,
        ProductDetail.class,
        TIMEOUT,
        () -> runSaveDraft(caller, parsed.reference(), parsed.code(), parsed.command()));
  }

  private IdempotentOperation.Completed<ProductDetail> runSaveDraft(
      Caller caller, String reference, String code, DraftSave c) {
    Instant at = now();
    PartnerRow partner =
        repository.lockByReference(reference).orElseThrow(PartnerAccountService::partnerNotFound);
    ProductRow product =
        repository
            .lockProduct(partner.id(), code)
            .orElseThrow(PartnerAccountService::productNotFound);
    if ("CLOSED".equals(partner.status())) {
      throw new ApiException(ErrorCode.PARTNER_PRODUCT_PARTNER_INACTIVE, Map.of());
    }
    checkScope(partner, c.content());
    int latest = repository.latestVersion(product.id());
    VersionRow current = repository.lockVersion(product.id(), latest).orElseThrow();
    ProductStatus status = ProductStatus.valueOf(current.status());
    if (status == ProductStatus.UNDER_REVIEW) {
      throw new ApiException(ErrorCode.PARTNER_PRODUCT_TRANSITION_INVALID, Map.of());
    }
    int version = current.version();
    try {
      if (status == ProductStatus.DRAFT) {
        if (c.expectedVersion() != current.version()
            || c.expectedRowVersion() != current.rowVersion()) {
          throw new ApiException(ErrorCode.PARTNER_PRODUCT_VERSION_STALE, Map.of());
        }
        if (repository.replaceDraft(current, c.content(), at) != 1) {
          throw new ApiException(ErrorCode.PARTNER_PRODUCT_VERSION_STALE, Map.of());
        }
      } else {
        if (c.expectedVersion() != latest) {
          throw new ApiException(ErrorCode.PARTNER_PRODUCT_VERSION_STALE, Map.of());
        }
        version = latest + 1;
        repository.insertDraft(UUID.randomUUID(), product.id(), version, c.content(), at);
        repository.appendProductEvent(
            product.id(),
            version,
            "VERSION_CREATED",
            null,
            "DRAFT",
            null,
            null,
            caller.subject(),
            at);
      }
    } catch (DataIntegrityViolationException violated) {
      throw new ApiException(ErrorCode.PARTNER_PRODUCT_VERSION_STALE, Map.of());
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reference", partner.reference());
    metadata.put("productCode", product.productCode());
    metadata.put("version", version);
    audited(caller, PRODUCT_SAVE, "partner-product", product.id(), metadata, at);
    return new IdempotentOperation.Completed<>(
        productDetail(partner, product), product.id(), Outcome.UPDATED);
  }

  /**
   * Submits, returns, publishes, pauses, resumes or retires a product version.
   *
   * @param caller verified administrator
   * @param reference raw partner reference
   * @param code raw product code
   * @param idempotencyKey the header
   * @param body parsed body
   * @return the receipt
   */
  public IdempotentOperation.Result<ProductTransitionReceipt> transitionProduct(
      Caller caller, String reference, String code, String idempotencyKey, JsonNode body) {
    IdempotentOperation.Spec spec =
        new IdempotentOperation.Spec(
            PRODUCT_TRANSITION, "partner_product", "transition", "transitioned", 200);
    record Parsed(String reference, String code, ProductTransition command) {}
    Parsed parsed =
        operations.validated(
            spec,
            () -> {
              key(idempotencyKey);
              return new Parsed(
                  partnerReference(reference),
                  productCode(code),
                  PartnerAccountCommands.parseProductTransition(body));
            });
    ProductTransition c = parsed.command();
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("reference", parsed.reference());
    canonical.put("productCode", parsed.code());
    canonical.put("action", c.action().name());
    canonical.put("version", c.version());
    canonical.put("expectedRowVersion", c.expectedRowVersion());
    canonical.put("reason", c.reason() == null ? "" : c.reason().name());
    canonical.put("note", c.note() == null ? "" : crypto.digest("note", c.note()));
    return operations.execute(
        spec,
        caller.subject(),
        idempotencyKey,
        canonical,
        ProductTransitionReceipt.class,
        TIMEOUT,
        () -> runTransitionProduct(caller, parsed.reference(), parsed.code(), c));
  }

  private IdempotentOperation.Completed<ProductTransitionReceipt> runTransitionProduct(
      Caller caller, String reference, String code, ProductTransition c) {
    Instant at = now();
    PartnerRow partner =
        repository.lockByReference(reference).orElseThrow(PartnerAccountService::partnerNotFound);
    ProductRow product =
        repository
            .lockProduct(partner.id(), code)
            .orElseThrow(PartnerAccountService::productNotFound);
    VersionRow version =
        repository
            .lockVersion(product.id(), c.version())
            .orElseThrow(PartnerAccountService::productNotFound);
    if (version.rowVersion() != c.expectedRowVersion()) {
      throw new ApiException(ErrorCode.PARTNER_PRODUCT_VERSION_STALE, Map.of());
    }
    ProductStatus from = ProductStatus.valueOf(version.status());
    if (!c.action().allowedFrom(from)) {
      throw new ApiException(ErrorCode.PARTNER_PRODUCT_TRANSITION_INVALID, Map.of());
    }
    boolean closed = "CLOSED".equals(partner.status());
    boolean active = "ACTIVE".equals(partner.status());
    if ((closed && c.action() != ProductAction.RETIRE)
        || ((c.action() == ProductAction.PUBLISH || c.action() == ProductAction.RESUME)
            && !active)) {
      throw new ApiException(ErrorCode.PARTNER_PRODUCT_PARTNER_INACTIVE, Map.of());
    }
    ProductType type = ProductType.valueOf(product.productType());
    if (c.action() == ProductAction.SUBMIT) {
      requireComplete(type, version.content());
    }
    Integer superseded = null;
    byte[] note =
        c.note() == null
            ? null
            : seal(c.note(), "partner-product", product.id(), c.action().eventType());
    String reason = c.reason() == null ? null : c.reason().name();
    try {
      if (c.action() == ProductAction.PUBLISH) {
        Optional<Integer> live = repository.liveVersion(product.id());
        if (live.isPresent() && live.get() != version.version()) {
          VersionRow old = repository.lockVersion(product.id(), live.get()).orElseThrow();
          if (repository.updateVersionStatus(old, "RETIRED", at) != 1) {
            throw new ApiException(ErrorCode.PARTNER_PRODUCT_VERSION_STALE, Map.of());
          }
          repository.appendProductEvent(
              product.id(),
              old.version(),
              "SUPERSEDED",
              old.status(),
              "RETIRED",
              null,
              null,
              caller.subject(),
              at);
          superseded = old.version();
        }
      }
      if (repository.updateVersionStatus(version, c.action().target().name(), at) != 1) {
        throw new ApiException(ErrorCode.PARTNER_PRODUCT_VERSION_STALE, Map.of());
      }
      repository.appendProductEvent(
          product.id(),
          version.version(),
          c.action().eventType(),
          from.name(),
          c.action().target().name(),
          reason,
          note,
          caller.subject(),
          at);
    } catch (DataIntegrityViolationException violated) {
      throw new ApiException(ErrorCode.PARTNER_PRODUCT_TRANSITION_INVALID, Map.of());
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reference", partner.reference());
    metadata.put("productCode", product.productCode());
    metadata.put("version", version.version());
    metadata.put("action", c.action().name());
    metadata.put("fromStatus", from.name());
    metadata.put("toStatus", c.action().target().name());
    if (reason != null) {
      metadata.put("reasonCode", reason);
    }
    if (superseded != null) {
      metadata.put("supersededVersion", superseded);
    }
    audited(caller, PRODUCT_TRANSITION, "partner-product", product.id(), metadata, at);
    return new IdempotentOperation.Completed<>(
        new ProductTransitionReceipt(productDetail(partner, product), superseded),
        product.id(),
        Outcome.UPDATED);
  }

  // ----------------------------------------------------------------- helpers

  private PartnerDetail detail(PartnerRow row) {
    int[] counts = repository.productCounts(row.id());
    PartnerStatus status = PartnerStatus.valueOf(row.status());
    List<String> allowed = new ArrayList<>();
    for (PartnerAction action : PartnerAction.values()) {
      if (action.allowedFrom(status)) {
        allowed.add(action.name());
      }
    }
    return new PartnerDetail(
        row.reference(),
        row.applicationReference(),
        row.status(),
        row.version(),
        row.legalName(),
        row.tradingName(),
        row.countryOfRegistration(),
        row.primaryCategory(),
        repository.additionalCategories(row.id()),
        repository.capabilities(row.id()),
        row.website(),
        row.languages(),
        repository.countries(row.id()),
        allowed,
        counts[0],
        counts[1],
        row.createdAt(),
        row.updatedAt());
  }

  private ProductDetail productDetail(PartnerRow partner, ProductRow product) {
    boolean closed = "CLOSED".equals(partner.status());
    boolean active = "ACTIVE".equals(partner.status());
    List<ProductVersion> versions = new ArrayList<>();
    for (VersionRow v : repository.versions(product.id())) {
      ProductStatus status = ProductStatus.valueOf(v.status());
      List<String> allowed = new ArrayList<>();
      for (ProductAction action : ProductAction.values()) {
        boolean needsActive = action == ProductAction.PUBLISH || action == ProductAction.RESUME;
        if (action.allowedFrom(status)
            && (!closed || action == ProductAction.RETIRE)
            && (!needsActive || active)) {
          allowed.add(action.name());
        }
      }
      versions.add(
          new ProductVersion(
              v.version(),
              v.status(),
              v.rowVersion(),
              v.content(),
              allowed,
              v.createdAt(),
              v.updatedAt()));
    }
    return new ProductDetail(
        partner.reference(),
        partner.status(),
        product.productCode(),
        product.productType(),
        product.publicReference(),
        versions);
  }

  /**
   * Country and currency scope: countries within the partner's approved scope, offered currencies.
   */
  private void checkScope(PartnerRow partner, Content content) {
    List<String> approved = repository.countries(partner.id());
    for (String country : content.countries()) {
      if (!approved.contains(country)) {
        throw new ApiException(
            ErrorCode.PARTNER_PRODUCT_SCOPE_INVALID,
            Map.of("fields", List.of(Map.of("field", "content.countries", "constraint", "SCOPE"))));
      }
    }
    for (CurrencyLimit limit : content.currencies()) {
      if (!PartnerAccountDomain.OFFERED_CURRENCIES.contains(limit.currency())) {
        throw new ApiException(
            ErrorCode.PARTNER_PRODUCT_SCOPE_INVALID,
            Map.of(
                "fields", List.of(Map.of("field", "content.currencies", "constraint", "SCOPE"))));
      }
    }
  }

  /** A version leaving DRAFT is complete in both languages, dated and, where relevant, limited. */
  private static void requireComplete(ProductType type, Content c) {
    require(c.name(), "content.name");
    require(c.summary(), "content.summary");
    require(c.eligibility(), "content.eligibility");
    require(c.disclosure(), "content.disclosure");
    if (c.effectiveDate() == null) {
      throw PartnerAccountCommands.invalid("content.effectiveDate", "REQUIRED");
    }
    if (c.countries().isEmpty()) {
      throw PartnerAccountCommands.invalid("content.countries", "REQUIRED");
    }
    if (c.currencies().isEmpty()) {
      throw PartnerAccountCommands.invalid("content.currencies", "REQUIRED");
    }
    if (type.needsLimits()) {
      if (c.minMonths() == null) {
        throw PartnerAccountCommands.invalid("content.minMonths", "REQUIRED");
      }
      for (CurrencyLimit limit : c.currencies()) {
        if (limit.minAmount() == null) {
          throw PartnerAccountCommands.invalid("content.currencies", "REQUIRED");
        }
      }
    }
  }

  private static void require(Bilingual text, String field) {
    if (text.en() == null || text.fr() == null) {
      throw PartnerAccountCommands.invalid(field, "REQUIRED");
    }
  }

  private String contentDigest(Content content) {
    return Fingerprints.sha256(json.writeValueAsString(content));
  }

  private byte[] seal(String note, String resource, UUID id, String event) {
    return crypto.seal(
        note.getBytes(StandardCharsets.UTF_8),
        "divalhr-partner-account\nnote\nresource=" + resource + "\nid=" + id + "\nevent=" + event);
  }

  private PartnerRow findPartner(String reference) {
    return repository
        .findByReference(reference)
        .orElseThrow(PartnerAccountService::partnerNotFound);
  }

  private ProductRow findProduct(PartnerRow partner, String code) {
    return repository
        .product(partner.id(), code)
        .orElseThrow(PartnerAccountService::productNotFound);
  }

  private void disclose(
      Caller caller, String action, String type, UUID resource, Map<String, Object> metadata) {
    audited(caller, action, type, resource, metadata, now());
  }

  private void audited(
      Caller caller,
      String action,
      String type,
      UUID resource,
      Map<String, Object> metadata,
      Instant at) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            type,
            resource,
            PLATFORM_SCOPE,
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-PARTNER-ACCOUNT\nversion=1\naction="
                    + action
                    + "\nresource="
                    + resource
                    + "\nby="
                    + caller.subject()
                    + "\n")));
  }

  private static CursorScope scope(String operation, Caller caller, Map<String, String> extra) {
    Map<String, String> filters = new TreeMap<>(extra);
    filters.put("role", ROLE);
    filters.put("subject", caller.subject());
    return new CursorScope(operation, new TenantId(PLATFORM_SCOPE), filters);
  }

  private Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }

  private static String random(int length) {
    StringBuilder text = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      text.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return text.toString();
  }

  private static int limit(String raw) {
    if (raw == null || raw.isEmpty()) {
      return DEFAULT_LIMIT;
    }
    if (!DIGITS.matcher(raw).matches()) {
      throw PartnerAccountCommands.shape("limit", "FORMAT");
    }
    int value = Integer.parseInt(raw);
    if (value < 1 || value > MAX_LIMIT) {
      throw PartnerAccountCommands.shape("limit", "RANGE");
    }
    return value;
  }

  private static void key(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw PartnerAccountCommands.shape(IdempotencyKeys.HEADER, "REQUIRED");
    }
    if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      throw PartnerAccountCommands.shape(IdempotencyKeys.HEADER, "FORMAT");
    }
  }

  private static String applicationReference(String raw) {
    if (raw == null || !APPLICATION_REFERENCE.matcher(raw).matches()) {
      throw new ApiException(ErrorCode.PARTNER_APPLICATION_NOT_FOUND, Map.of());
    }
    return raw;
  }

  private static String partnerReference(String raw) {
    if (raw == null || !PARTNER_REFERENCE.matcher(raw).matches()) {
      throw partnerNotFound();
    }
    return raw;
  }

  private static String productCode(String raw) {
    if (raw == null || !PRODUCT_CODE.matcher(raw).matches()) {
      throw productNotFound();
    }
    return raw;
  }

  private static ApiException partnerNotFound() {
    return new ApiException(ErrorCode.PARTNER_NOT_FOUND, Map.of());
  }

  private static ApiException productNotFound() {
    return new ApiException(ErrorCode.PARTNER_PRODUCT_NOT_FOUND, Map.of());
  }
}
