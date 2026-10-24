package com.divalhr.core.integrations.messaging.application;

import com.divalhr.core.integrations.messaging.api.ConnectorCommandRequest;
import com.divalhr.core.integrations.messaging.api.CreateConnectorRequest;
import com.divalhr.core.integrations.messaging.api.CreateRecipientRequest;
import com.divalhr.core.integrations.messaging.api.MessagingTestRequest;
import com.divalhr.core.integrations.messaging.api.RecipientCommandRequest;
import com.divalhr.core.integrations.messaging.api.RotateTokenRequest;
import com.divalhr.core.integrations.messaging.api.UpdateConnectorRequest;
import com.divalhr.core.integrations.messaging.api.UpdateRecipientRequest;
import com.divalhr.core.integrations.messaging.application.MessagingProvider.CredentialResult;
import com.divalhr.core.integrations.messaging.application.MessagingProvider.TemplateCheck;
import com.divalhr.core.integrations.messaging.application.MessagingProvider.TemplateResult;
import com.divalhr.core.integrations.messaging.application.MessagingViews.AttemptItem;
import com.divalhr.core.integrations.messaging.application.MessagingViews.CallbackItem;
import com.divalhr.core.integrations.messaging.application.MessagingViews.ConnectorView;
import com.divalhr.core.integrations.messaging.application.MessagingViews.DeliveryDetail;
import com.divalhr.core.integrations.messaging.application.MessagingViews.DeliveryPage;
import com.divalhr.core.integrations.messaging.application.MessagingViews.DeliveryView;
import com.divalhr.core.integrations.messaging.application.MessagingViews.RecipientList;
import com.divalhr.core.integrations.messaging.application.MessagingViews.RecipientView;
import com.divalhr.core.integrations.messaging.application.MessagingViews.TemplateCheckView;
import com.divalhr.core.integrations.messaging.application.MessagingViews.TestDeliveryResult;
import com.divalhr.core.integrations.messaging.application.MessagingViews.VerificationView;
import com.divalhr.core.integrations.messaging.domain.Connector;
import com.divalhr.core.integrations.messaging.domain.ConnectorState;
import com.divalhr.core.integrations.messaging.domain.DeepLink;
import com.divalhr.core.integrations.messaging.domain.MessagingDelivery;
import com.divalhr.core.integrations.messaging.domain.MessagingLanguage;
import com.divalhr.core.integrations.messaging.domain.NotificationType;
import com.divalhr.core.integrations.messaging.domain.Recipient;
import com.divalhr.core.integrations.messaging.domain.RecipientStatus;
import com.divalhr.core.integrations.messaging.domain.TemplateConfig;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository.StoredCredential;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Management of the WhatsApp connector (MVP-111, ADR 0029): connect, change, verify, rotate the
 * token, disable, enable, register recipients, send a test message and read delivery history.
 * Commands are idempotent and run state, audit, lifecycle outbox event and idempotency record in
 * one transaction; the provider is called before that transaction and never inside it. Reads are
 * fail-closed disclosures. Nothing here logs or audits a token, a number, an identifier of the
 * provider, a template name or a deep link.
 */
@Service
public class MessagingService {

  /** Connect (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = "messaging.whatsapp.create";

  /** Change the configuration. */
  public static final String UPDATE = "messaging.whatsapp.update";

  /** Verify with the provider. */
  public static final String VERIFY = "messaging.whatsapp.verify";

  /** Rotate the access token. */
  public static final String ROTATE = "messaging.whatsapp.rotate-token";

  /** Disable the connector. */
  public static final String DISABLE = "messaging.whatsapp.disable";

  /** Enable the connector. */
  public static final String ENABLE = "messaging.whatsapp.enable";

  /** Register a recipient. */
  public static final String RECIPIENT_CREATE = "messaging.whatsapp.recipient-create";

  /** Change a recipient. */
  public static final String RECIPIENT_UPDATE = "messaging.whatsapp.recipient-update";

  /** Disable a recipient. */
  public static final String RECIPIENT_DISABLE = "messaging.whatsapp.recipient-disable";

  /** Enable a recipient. */
  public static final String RECIPIENT_ENABLE = "messaging.whatsapp.recipient-enable";

  /** Send a test message. */
  public static final String TEST = "messaging.whatsapp.test-delivery";

  /** Connector and recipient reads (disclosure audit action). */
  public static final String READ = "messaging.whatsapp.read";

  /** Delivery history reads (disclosure audit action). */
  public static final String DELIVERY_READ = "messaging.whatsapp.delivery-read";

  /** Subject bucket of reads. */
  public static final String SUBJECT_READ_BUCKET = "messaging-read";

  /** Subject bucket of commands. */
  public static final String SUBJECT_WRITE_BUCKET = "messaging-write";

  /** Tenant bucket of commands. */
  public static final String TENANT_WRITE_BUCKET = "messaging-write";

  private static final String SOURCE = "core-api/integrations";
  private static final int SCHEMA_VERSION = 1;
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);
  private static final Logger LOG = LoggerFactory.getLogger(MessagingService.class);
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private static final int TEST_RETRY_AFTER_SECONDS = 3600;

  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "messaging_connector", "create", "created", 201);
  private static final IdempotentOperation.Spec UPDATE_SPEC =
      new IdempotentOperation.Spec(UPDATE, "messaging_connector", "update", "updated", 200);
  private static final IdempotentOperation.Spec VERIFY_SPEC =
      new IdempotentOperation.Spec(VERIFY, "messaging_connector", "verify", "verified", 200);
  private static final IdempotentOperation.Spec ROTATE_SPEC =
      new IdempotentOperation.Spec(ROTATE, "messaging_credential", "rotate", "rotated", 200);
  private static final IdempotentOperation.Spec DISABLE_SPEC =
      new IdempotentOperation.Spec(DISABLE, "messaging_connector", "disable", "disabled", 200);
  private static final IdempotentOperation.Spec ENABLE_SPEC =
      new IdempotentOperation.Spec(ENABLE, "messaging_connector", "enable", "enabled", 200);
  private static final IdempotentOperation.Spec RECIPIENT_CREATE_SPEC =
      new IdempotentOperation.Spec(
          RECIPIENT_CREATE, "messaging_recipient", "create", "created", 201);
  private static final IdempotentOperation.Spec RECIPIENT_UPDATE_SPEC =
      new IdempotentOperation.Spec(
          RECIPIENT_UPDATE, "messaging_recipient", "update", "updated", 200);
  private static final IdempotentOperation.Spec RECIPIENT_DISABLE_SPEC =
      new IdempotentOperation.Spec(
          RECIPIENT_DISABLE, "messaging_recipient", "disable", "disabled", 200);
  private static final IdempotentOperation.Spec RECIPIENT_ENABLE_SPEC =
      new IdempotentOperation.Spec(
          RECIPIENT_ENABLE, "messaging_recipient", "enable", "enabled", 200);
  private static final IdempotentOperation.Spec TEST_SPEC =
      new IdempotentOperation.Spec(TEST, "messaging_test_delivery", "create", "created", 202);

  private final IdempotentOperation operations;
  private final JdbcMessagingRepository repository;
  private final MessagingSecrets secrets;
  private final MessagingProvider provider;
  private final MessagingProperties properties;
  private final CursorCodec cursors;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final Clock clock;
  private final PlatformTransactionManager transactions;
  private final boolean relaxed;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param repository storage
   * @param secrets token and number sealing
   * @param provider the WhatsApp provider
   * @param properties settings
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param clock the platform clock
   * @param transactions transaction manager
   * @param environment deployment environment
   */
  public MessagingService(
      IdempotentOperation operations,
      JdbcMessagingRepository repository,
      MessagingSecrets secrets,
      MessagingProvider provider,
      MessagingProperties properties,
      CursorCodec cursors,
      AuditRecorder audit,
      OutboxWriter outbox,
      Clock clock,
      PlatformTransactionManager transactions,
      @Value("${divalhr.environment}") String environment) {
    this.operations = operations;
    this.repository = repository;
    this.secrets = secrets;
    this.provider = provider;
    this.properties = properties;
    this.cursors = cursors;
    this.audit = audit;
    this.outbox = outbox;
    this.clock = clock;
    this.transactions = transactions;
    String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
    this.relaxed = "development".equals(env) || "test".equals(env);
  }

  /**
   * A command's outcome.
   *
   * @param body the body to return
   * @param replayed whether an earlier response for the key was replayed
   * @param <T> body type
   */
  public record Command<T>(T body, boolean replayed) {}

  // ------------------------------------------------------------------------------------------
  // Connector commands
  // ------------------------------------------------------------------------------------------

  /**
   * Connects the tenant's one connector as a draft. The token is sealed and never returned.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the connector
   */
  public Command<ConnectorView> create(
      MessagingCaller caller, String idempotencyKey, CreateConnectorRequest body) {
    var command =
        operations.validated(
            CREATE_SPEC, () -> MessagingCommand.createConnector(idempotencyKey, body));
    requireSecrets();
    var canonical = command.canonical(tenantText(caller), secrets.idempotencyDigest());
    var earlier =
        operations.replayOf(
            CREATE_SPEC, caller.subject(), idempotencyKey, canonical, ConnectorView.class);
    if (earlier.isPresent()) {
      return new Command<>(earlier.get().body(), true);
    }
    var result =
        operations.execute(
            CREATE_SPEC,
            caller.subject(),
            idempotencyKey,
            canonical,
            ConnectorView.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              UUID id = UUID.randomUUID();
              try {
                repository.insertConnector(
                    caller.tenant(),
                    id,
                    command.wabaId(),
                    command.phoneNumberId(),
                    command.displayNumber().e164(),
                    caller.subject(),
                    now,
                    secrets.sealToken(command.token(), caller.tenant().value(), id, 1));
                for (MessagingCommand.TemplateInput t : command.templates()) {
                  repository.upsertTemplate(caller.tenant(), id, t.type(), t.language(), t.name());
                }
              } catch (DataIntegrityViolationException violated) {
                throw mapped(violated);
              }
              Connector created = repository.findConnector(caller.tenant(), false).orElseThrow();
              record(caller, CREATE, "messaging-connector", id, now, metadata(created, "CREATED"));
              emit(caller, "integrations.messaging-connector.created.v1", created, now);
              return new IdempotentOperation.Completed<>(
                  view(caller.tenant(), created), id, Outcome.CREATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  /**
   * Changes the Meta identifiers and template names of a connector that is not active; the
   * provider's verification is cleared and must be repeated before enabling.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the connector
   */
  public Command<ConnectorView> update(
      MessagingCaller caller, String idempotencyKey, UpdateConnectorRequest body) {
    var command =
        operations.validated(
            UPDATE_SPEC, () -> MessagingCommand.updateConnector(idempotencyKey, body));
    requireSecrets();
    var canonical = command.canonical(tenantText(caller), secrets.idempotencyDigest());
    var earlier =
        operations.replayOf(
            UPDATE_SPEC, caller.subject(), idempotencyKey, canonical, ConnectorView.class);
    if (earlier.isPresent()) {
      return new Command<>(earlier.get().body(), true);
    }
    var result =
        operations.execute(
            UPDATE_SPEC,
            caller.subject(),
            idempotencyKey,
            canonical,
            ConnectorView.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Connector current = lockConnector(caller);
              if (current.state() == ConnectorState.ACTIVE) {
                throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_ACTIVE, Map.of());
              }
              if (current.configVersion() != command.configVersion()) {
                throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_VERSION_STALE, Map.of());
              }
              String waba = command.wabaId() == null ? current.wabaId() : command.wabaId();
              String phoneId =
                  command.phoneNumberId() == null
                      ? current.phoneNumberId()
                      : command.phoneNumberId();
              String display =
                  command.displayNumber() == null
                      ? current.displayNumber()
                      : command.displayNumber().e164();
              Map<String, String> existing = new HashMap<>();
              for (TemplateConfig t : repository.templates(caller.tenant(), current.id())) {
                existing.put(t.type() + "/" + t.language(), t.name());
              }
              boolean templatesChanged = false;
              for (MessagingCommand.TemplateInput t : command.templates()) {
                templatesChanged |= !t.name().equals(existing.get(t.type() + "/" + t.language()));
              }
              boolean identifiersChanged =
                  !waba.equals(current.wabaId())
                      || !phoneId.equals(current.phoneNumberId())
                      || !display.equals(current.displayNumber());
              if (!identifiersChanged && !templatesChanged) {
                return new IdempotentOperation.Completed<>(
                    view(caller.tenant(), current), current.id(), Outcome.UNCHANGED);
              }
              try {
                if (!repository.updateConnector(
                    caller.tenant(),
                    current.id(),
                    command.configVersion(),
                    waba,
                    phoneId,
                    display,
                    caller.subject(),
                    now)) {
                  throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_VERSION_STALE, Map.of());
                }
                repository.clearTemplateVerification(caller.tenant(), current.id());
                for (MessagingCommand.TemplateInput t : command.templates()) {
                  repository.upsertTemplate(
                      caller.tenant(), current.id(), t.type(), t.language(), t.name());
                }
              } catch (DataIntegrityViolationException violated) {
                throw mapped(violated);
              }
              Connector updated = repository.findConnector(caller.tenant(), false).orElseThrow();
              record(
                  caller,
                  UPDATE,
                  "messaging-connector",
                  current.id(),
                  now,
                  metadata(updated, "CONFIGURATION"));
              emit(caller, "integrations.messaging-connector.updated.v1", updated, now);
              return new IdempotentOperation.Completed<>(
                  view(caller.tenant(), updated), current.id(), Outcome.UPDATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  /**
   * Asks the provider whether the token works and whether every template exists, is approved, has
   * the expected language and the exact supported shape. It reports; it does not activate.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the connector and the verdicts
   */
  public Command<VerificationView> verify(
      MessagingCaller caller, String idempotencyKey, ConnectorCommandRequest body) {
    var command =
        operations.validated(
            VERIFY_SPEC, () -> MessagingCommand.connectorCommand(idempotencyKey, body));
    var canonical = command.canonical(tenantText(caller), "connector", "verify");
    var earlier =
        operations.replayOf(
            VERIFY_SPEC, caller.subject(), idempotencyKey, canonical, VerificationView.class);
    if (earlier.isPresent()) {
      return new Command<>(earlier.get().body(), true);
    }
    requireSecrets();
    Connector current = readConnector(caller);
    if (current.configVersion() != command.version()) {
      throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_VERSION_STALE, Map.of());
    }
    Verification verification = verifyWithProvider(caller.tenant(), current);
    var result =
        operations.execute(
            VERIFY_SPEC,
            caller.subject(),
            idempotencyKey,
            canonical,
            VerificationView.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Connector locked = lockConnector(caller);
              if (locked.configVersion() != command.version()) {
                throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_VERSION_STALE, Map.of());
              }
              recordVerification(caller.tenant(), locked.id(), verification, now);
              if (verification.ok() && locked.state() != ConnectorState.ACTIVE) {
                repository.markVerified(caller.tenant(), locked.id(), false, caller.subject(), now);
              }
              Connector after = repository.findConnector(caller.tenant(), false).orElseThrow();
              Map<String, Object> metadata = metadata(after, "VERIFICATION");
              metadata.put("verified", verification.ok());
              record(caller, VERIFY, "messaging-connector", locked.id(), now, metadata);
              return new IdempotentOperation.Completed<>(
                  new VerificationView(
                      view(caller.tenant(), after),
                      verification.credential().name(),
                      verification.checkViews(),
                      verification.ok()),
                  locked.id(),
                  Outcome.UPDATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  /**
   * Replaces the access token. The new token is verified with the provider first; if that fails the
   * current token stays untouched. The previous token is erased in the same transaction.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the connector
   */
  public Command<ConnectorView> rotateToken(
      MessagingCaller caller, String idempotencyKey, RotateTokenRequest body) {
    var command =
        operations.validated(ROTATE_SPEC, () -> MessagingCommand.rotate(idempotencyKey, body));
    requireSecrets();
    var canonical = command.canonical(tenantText(caller), secrets.idempotencyDigest());
    var earlier =
        operations.replayOf(
            ROTATE_SPEC, caller.subject(), idempotencyKey, canonical, ConnectorView.class);
    if (earlier.isPresent()) {
      return new Command<>(earlier.get().body(), true);
    }
    Connector current = readConnector(caller);
    if (current.configVersion() != command.configVersion()) {
      throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_VERSION_STALE, Map.of());
    }
    switch (provider.checkCredential(command.token(), current.phoneNumberId())) {
      case VALID -> {
        // the new credential works
      }
      case REJECTED ->
          throw new ApiException(ErrorCode.MESSAGING_PROVIDER_CREDENTIAL_INVALID, Map.of());
      default -> throw new ApiException(ErrorCode.MESSAGING_PROVIDER_UNAVAILABLE, Map.of());
    }
    var result =
        operations.execute(
            ROTATE_SPEC,
            caller.subject(),
            idempotencyKey,
            canonical,
            ConnectorView.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Connector locked = lockConnector(caller);
              if (locked.configVersion() != command.configVersion()) {
                throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_VERSION_STALE, Map.of());
              }
              int version = locked.credentialVersion() + 1;
              repository.rotateCredential(
                  caller.tenant(),
                  locked.id(),
                  version,
                  secrets.sealToken(command.token(), caller.tenant().value(), locked.id(), version),
                  caller.subject(),
                  now);
              Connector rotated = repository.findConnector(caller.tenant(), false).orElseThrow();
              record(
                  caller,
                  ROTATE,
                  "messaging-connector",
                  locked.id(),
                  now,
                  metadata(rotated, "CREDENTIAL"));
              emit(caller, "integrations.messaging-connector.token-rotated.v1", rotated, now);
              return new IdempotentOperation.Completed<>(
                  view(caller.tenant(), rotated), locked.id(), Outcome.UPDATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  /**
   * Disables the connector; queued work is cancelled and history stays readable.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the connector
   */
  public Command<ConnectorView> disable(
      MessagingCaller caller, String idempotencyKey, ConnectorCommandRequest body) {
    var command =
        operations.validated(
            DISABLE_SPEC, () -> MessagingCommand.connectorCommand(idempotencyKey, body));
    var result =
        operations.execute(
            DISABLE_SPEC,
            caller.subject(),
            idempotencyKey,
            command.canonical(tenantText(caller), "connector", "disable"),
            ConnectorView.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Connector current = lockConnector(caller);
              if (current.state() == ConnectorState.DISABLED) {
                throw new ApiException(ErrorCode.MESSAGING_ALREADY_DISABLED, Map.of());
              }
              if (current.configVersion() != command.version()) {
                throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_VERSION_STALE, Map.of());
              }
              repository.disableConnector(caller.tenant(), current.id(), caller.subject(), now);
              repository.cancelOpenDeliveries(caller.tenant(), current.id(), now);
              Connector disabled = repository.findConnector(caller.tenant(), false).orElseThrow();
              record(
                  caller,
                  DISABLE,
                  "messaging-connector",
                  current.id(),
                  now,
                  metadata(disabled, "STATE"));
              emit(caller, "integrations.messaging-connector.disabled.v1", disabled, now);
              return new IdempotentOperation.Completed<>(
                  view(caller.tenant(), disabled), current.id(), Outcome.UPDATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  /**
   * Activates a draft, disabled or suspended connector. Activation fails closed: the provider must
   * confirm the token and every template first. The failure counter restarts and older work is not
   * replayed.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the connector
   */
  public Command<ConnectorView> enable(
      MessagingCaller caller, String idempotencyKey, ConnectorCommandRequest body) {
    var command =
        operations.validated(
            ENABLE_SPEC, () -> MessagingCommand.connectorCommand(idempotencyKey, body));
    var canonical = command.canonical(tenantText(caller), "connector", "enable");
    var earlier =
        operations.replayOf(
            ENABLE_SPEC, caller.subject(), idempotencyKey, canonical, ConnectorView.class);
    if (earlier.isPresent()) {
      return new Command<>(earlier.get().body(), true);
    }
    requireSecrets();
    Connector current = readConnector(caller);
    if (current.state() == ConnectorState.ACTIVE) {
      throw new ApiException(ErrorCode.MESSAGING_ALREADY_ENABLED, Map.of());
    }
    if (current.configVersion() != command.version()) {
      throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_VERSION_STALE, Map.of());
    }
    Verification verification = verifyWithProvider(caller.tenant(), current);
    if (verification.credential() == CredentialResult.REJECTED) {
      throw new ApiException(ErrorCode.MESSAGING_PROVIDER_CREDENTIAL_INVALID, Map.of());
    }
    if (verification.credential() == CredentialResult.UNAVAILABLE || verification.unavailable()) {
      throw new ApiException(ErrorCode.MESSAGING_PROVIDER_UNAVAILABLE, Map.of());
    }
    if (!verification.ok()) {
      throw new ApiException(ErrorCode.MESSAGING_TEMPLATE_INVALID, Map.of());
    }
    var result =
        operations.execute(
            ENABLE_SPEC,
            caller.subject(),
            idempotencyKey,
            canonical,
            ConnectorView.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Connector locked = lockConnector(caller);
              if (locked.state() == ConnectorState.ACTIVE) {
                throw new ApiException(ErrorCode.MESSAGING_ALREADY_ENABLED, Map.of());
              }
              if (locked.configVersion() != command.version()) {
                throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_VERSION_STALE, Map.of());
              }
              recordVerification(caller.tenant(), locked.id(), verification, now);
              repository.markVerified(caller.tenant(), locked.id(), true, caller.subject(), now);
              Connector enabled = repository.findConnector(caller.tenant(), false).orElseThrow();
              record(
                  caller,
                  ENABLE,
                  "messaging-connector",
                  locked.id(),
                  now,
                  metadata(enabled, "STATE"));
              emit(caller, "integrations.messaging-connector.enabled.v1", enabled, now);
              return new IdempotentOperation.Completed<>(
                  view(caller.tenant(), enabled), locked.id(), Outcome.UPDATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  // ------------------------------------------------------------------------------------------
  // Recipient commands
  // ------------------------------------------------------------------------------------------

  /**
   * Registers an opted-in recipient. The number is sealed and shown only masked.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the recipient
   */
  public Command<RecipientView> createRecipient(
      MessagingCaller caller, String idempotencyKey, CreateRecipientRequest body) {
    var command =
        operations.validated(
            RECIPIENT_CREATE_SPEC,
            () -> MessagingCommand.createRecipient(idempotencyKey, body, Instant.now(clock)));
    requireSecrets();
    var canonical = command.canonical(tenantText(caller), secrets.idempotencyDigest());
    var earlier =
        operations.replayOf(
            RECIPIENT_CREATE_SPEC,
            caller.subject(),
            idempotencyKey,
            canonical,
            RecipientView.class);
    if (earlier.isPresent()) {
      return new Command<>(earlier.get().body(), true);
    }
    var result =
        operations.execute(
            RECIPIENT_CREATE_SPEC,
            caller.subject(),
            idempotencyKey,
            canonical,
            RecipientView.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Connector connector = lockConnector(caller);
              UUID id = UUID.randomUUID();
              try {
                repository.insertRecipient(
                    caller.tenant(),
                    id,
                    connector.id(),
                    command.label(),
                    secrets.sealNumber(
                        command.number(), caller.tenant().value(), connector.id(), id),
                    secrets.digest(command.number(), caller.tenant().value(), connector.id()),
                    command.number().masked(),
                    command.language(),
                    command.types(),
                    command.consent().source(),
                    command.consent().confirmedAt(),
                    caller.subject(),
                    now);
              } catch (DataIntegrityViolationException violated) {
                throw mapped(violated);
              }
              Recipient created =
                  repository.findRecipient(caller.tenant(), id, false).orElseThrow();
              record(
                  caller,
                  RECIPIENT_CREATE,
                  "messaging-recipient",
                  id,
                  now,
                  recipientMetadata(created, "CREATED"));
              emit(caller, "integrations.messaging-recipient.created.v1", created, now);
              return new IdempotentOperation.Completed<>(
                  RecipientView.of(created), id, Outcome.CREATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  /**
   * Changes a recipient's label, language, notifications or consent record.
   *
   * @param caller verified tenant administrator
   * @param recipientId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the recipient
   */
  public Command<RecipientView> updateRecipient(
      MessagingCaller caller,
      String recipientId,
      String idempotencyKey,
      UpdateRecipientRequest body) {
    var command =
        operations.validated(
            RECIPIENT_UPDATE_SPEC,
            () -> MessagingCommand.updateRecipient(idempotencyKey, body, Instant.now(clock)));
    UUID id = recipientId(recipientId);
    var result =
        operations.execute(
            RECIPIENT_UPDATE_SPEC,
            caller.subject(),
            idempotencyKey,
            command.canonical(tenantText(caller), id),
            RecipientView.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Recipient current = lockRecipient(caller, id);
              if (current.version() != command.version()) {
                throw new ApiException(ErrorCode.MESSAGING_RECIPIENT_VERSION_STALE, Map.of());
              }
              boolean consentChanged =
                  command.consent() != null
                      && (command.consent().source() != current.consentSource()
                          || !command.consent().confirmedAt().equals(current.consentConfirmedAt()));
              boolean changed =
                  !current.label().equals(command.label())
                      || current.language() != command.language()
                      || !current.notificationTypes().equals(command.types())
                      || consentChanged;
              if (!changed) {
                return new IdempotentOperation.Completed<>(
                    RecipientView.of(current), id, Outcome.UNCHANGED);
              }
              if (!repository.updateRecipient(
                  caller.tenant(),
                  id,
                  command.version(),
                  command.label(),
                  command.language(),
                  command.types(),
                  command.consent() == null ? current.consentSource() : command.consent().source(),
                  command.consent() == null
                      ? current.consentConfirmedAt()
                      : command.consent().confirmedAt(),
                  caller.subject(),
                  now)) {
                throw new ApiException(ErrorCode.MESSAGING_RECIPIENT_VERSION_STALE, Map.of());
              }
              Recipient updated =
                  repository.findRecipient(caller.tenant(), id, false).orElseThrow();
              record(
                  caller,
                  RECIPIENT_UPDATE,
                  "messaging-recipient",
                  id,
                  now,
                  recipientMetadata(updated, "CONFIGURATION"));
              emit(caller, "integrations.messaging-recipient.updated.v1", updated, now);
              return new IdempotentOperation.Completed<>(
                  RecipientView.of(updated), id, Outcome.UPDATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  /**
   * Disables a recipient; its queued messages are cancelled.
   *
   * @param caller verified tenant administrator
   * @param recipientId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the recipient
   */
  public Command<RecipientView> disableRecipient(
      MessagingCaller caller,
      String recipientId,
      String idempotencyKey,
      RecipientCommandRequest body) {
    return recipientStatus(
        caller,
        recipientId,
        idempotencyKey,
        body,
        RecipientStatus.DISABLED,
        RECIPIENT_DISABLE_SPEC);
  }

  /**
   * Enables a disabled recipient.
   *
   * @param caller verified tenant administrator
   * @param recipientId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the recipient
   */
  public Command<RecipientView> enableRecipient(
      MessagingCaller caller,
      String recipientId,
      String idempotencyKey,
      RecipientCommandRequest body) {
    return recipientStatus(
        caller, recipientId, idempotencyKey, body, RecipientStatus.ACTIVE, RECIPIENT_ENABLE_SPEC);
  }

  private Command<RecipientView> recipientStatus(
      MessagingCaller caller,
      String recipientId,
      String idempotencyKey,
      RecipientCommandRequest body,
      RecipientStatus target,
      IdempotentOperation.Spec spec) {
    var command =
        operations.validated(spec, () -> MessagingCommand.recipientCommand(idempotencyKey, body));
    UUID id = recipientId(recipientId);
    String action = target == RecipientStatus.DISABLED ? "disable" : "enable";
    var result =
        operations.execute(
            spec,
            caller.subject(),
            idempotencyKey,
            command.canonical(tenantText(caller), id.toString(), action),
            RecipientView.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Recipient current = lockRecipient(caller, id);
              if (current.status() == target) {
                throw new ApiException(
                    target == RecipientStatus.DISABLED
                        ? ErrorCode.MESSAGING_ALREADY_DISABLED
                        : ErrorCode.MESSAGING_ALREADY_ENABLED,
                    Map.of());
              }
              if (current.version() != command.version()) {
                throw new ApiException(ErrorCode.MESSAGING_RECIPIENT_VERSION_STALE, Map.of());
              }
              repository.setRecipientStatus(caller.tenant(), id, target, caller.subject(), now);
              if (target == RecipientStatus.DISABLED) {
                repository.cancelOpenDeliveriesOfRecipient(caller.tenant(), id, now);
              }
              Recipient changed =
                  repository.findRecipient(caller.tenant(), id, false).orElseThrow();
              record(
                  caller,
                  spec.operation(),
                  "messaging-recipient",
                  id,
                  now,
                  recipientMetadata(changed, "STATE"));
              emit(
                  caller,
                  "integrations.messaging-recipient."
                      + (action.equals("disable") ? "disabled" : "enabled")
                      + ".v1",
                  changed,
                  now);
              return new IdempotentOperation.Completed<>(
                  RecipientView.of(changed), id, Outcome.UPDATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  /**
   * Queues one connector test message to a recipient. Both limits (3 accepted tests per recipient
   * and 10 per tenant per hour) answer the same {@code MESSAGING_TEST_RATE_LIMITED}; they count
   * only messages that were accepted into the queue, so authorization, validation and
   * inactive-state failures consume nothing and a replay of the same key counts once.
   *
   * @param caller verified tenant administrator
   * @param recipientId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the queued delivery
   */
  public Command<TestDeliveryResult> testDelivery(
      MessagingCaller caller,
      String recipientId,
      String idempotencyKey,
      MessagingTestRequest body) {
    operations.validated(
        TEST_SPEC,
        () -> {
          MessagingCommand.test(idempotencyKey, body);
          return Boolean.TRUE;
        });
    UUID id = recipientId(recipientId);
    var result =
        operations.execute(
            TEST_SPEC,
            caller.subject(),
            idempotencyKey,
            Map.of(
                "tenantId",
                tenantText(caller),
                "recipientId",
                id.toString(),
                "action",
                "test-delivery",
                "confirm",
                true),
            TestDeliveryResult.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Connector connector = lockConnector(caller);
              switch (connector.state()) {
                case DRAFT ->
                    throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_INCOMPLETE, Map.of());
                case DISABLED ->
                    throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_DISABLED, Map.of());
                case SUSPENDED ->
                    throw new ApiException(ErrorCode.MESSAGING_CONNECTOR_SUSPENDED, Map.of());
                default -> {
                  // active
                }
              }
              Recipient recipient = lockRecipient(caller, id);
              if (recipient.status() == RecipientStatus.DISABLED) {
                throw new ApiException(ErrorCode.MESSAGING_RECIPIENT_DISABLED, Map.of());
              }
              if (!secrets.available()) {
                throw new ApiException(ErrorCode.MESSAGING_SECRET_UNAVAILABLE, Map.of());
              }
              UUID deliveryId = UUID.randomUUID();
              UUID eventId = UUID.randomUUID();
              try {
                repository.insertDelivery(
                    caller.tenant(),
                    deliveryId,
                    connector.id(),
                    id,
                    eventId,
                    NotificationType.CONNECTOR_TEST,
                    null,
                    recipient.language(),
                    now);
              } catch (DataIntegrityViolationException violated) {
                throw mapped(violated);
              }
              Map<String, Object> metadata = recipientMetadata(recipient, "TEST_DELIVERY");
              record(caller, TEST, "messaging-recipient", id, now, metadata);
              return new IdempotentOperation.Completed<>(
                  new TestDeliveryResult(deliveryId, id, eventId), deliveryId, Outcome.CREATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  // ------------------------------------------------------------------------------------------
  // Reads (fail-closed disclosures)
  // ------------------------------------------------------------------------------------------

  /**
   * The tenant's connector.
   *
   * @param caller verified tenant administrator
   * @return the connector, after its disclosure audit committed
   */
  public ConnectorView connector(MessagingCaller caller) {
    return observed(
        READ,
        () ->
            read(
                () -> {
                  Connector found = readConnector(caller);
                  disclose(caller, READ, "connector-detail", found.id(), 1);
                  return view(caller.tenant(), found);
                }));
  }

  /**
   * The tenant's recipients.
   *
   * @param caller verified tenant administrator
   * @return the recipients, after their disclosure audit committed
   */
  public RecipientList recipients(MessagingCaller caller) {
    return observed(
        READ,
        () ->
            read(
                () -> {
                  List<Recipient> rows = repository.recipients(caller.tenant());
                  disclose(caller, READ, "recipient-list", caller.tenant().value(), rows.size());
                  return new RecipientList(rows.stream().map(RecipientView::of).toList());
                }));
  }

  /**
   * One recipient.
   *
   * @param caller verified tenant administrator
   * @param recipientId path value
   * @return the recipient, after its disclosure audit committed
   */
  public RecipientView recipient(MessagingCaller caller, String recipientId) {
    return observed(
        READ,
        () -> {
          UUID id = recipientId(recipientId);
          return read(
              () -> {
                Recipient found =
                    repository
                        .findRecipient(caller.tenant(), id, false)
                        .orElseThrow(
                            () ->
                                new ApiException(
                                    ErrorCode.MESSAGING_RECIPIENT_NOT_FOUND, Map.of()));
                disclose(caller, READ, "recipient-detail", id, 1);
                return RecipientView.of(found);
              });
        });
  }

  /**
   * One page of delivery history, newest first.
   *
   * @param caller verified tenant administrator
   * @param recipientId restrict to one recipient, or null
   * @param cursor opaque cursor or null
   * @param limit page size or null
   * @return the page, after its disclosure audit committed
   */
  public DeliveryPage deliveries(
      MessagingCaller caller, String recipientId, String cursor, String limit) {
    return observed(
        DELIVERY_READ,
        () -> {
          UUID recipient = recipientId == null ? null : recipientId(recipientId);
          int size = pageSize(limit);
          CursorScope scope =
              new CursorScope(
                  DELIVERY_READ,
                  caller.tenant(),
                  Map.of("recipientId", recipient == null ? "" : recipient.toString()));
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          return read(
              () -> {
                if (recipient != null) {
                  repository
                      .findRecipient(caller.tenant(), recipient, false)
                      .orElseThrow(
                          () ->
                              new ApiException(ErrorCode.MESSAGING_RECIPIENT_NOT_FOUND, Map.of()));
                }
                List<MessagingDelivery> rows =
                    repository.deliveries(
                        caller.tenant(),
                        recipient,
                        after == null ? null : fromCode(after.code()),
                        after == null ? null : after.id(),
                        size + 1);
                boolean more = rows.size() > size;
                List<MessagingDelivery> page = more ? rows.subList(0, size) : rows;
                disclose(
                    caller, DELIVERY_READ, "delivery-list", caller.tenant().value(), page.size());
                String next =
                    more
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                toCode(page.get(size - 1).createdAt()), page.get(size - 1).id()))
                        : null;
                return new DeliveryPage(page.stream().map(DeliveryView::of).toList(), next);
              });
        });
  }

  /**
   * One delivery with its attempts and callbacks.
   *
   * @param caller verified tenant administrator
   * @param deliveryId path value
   * @return the delivery, after its disclosure audit committed
   */
  public DeliveryDetail delivery(MessagingCaller caller, String deliveryId) {
    return observed(
        DELIVERY_READ,
        () -> {
          UUID id = deliveryUuid(deliveryId);
          return read(
              () -> {
                MessagingDelivery found =
                    repository
                        .delivery(caller.tenant(), id)
                        .orElseThrow(
                            () ->
                                new ApiException(ErrorCode.MESSAGING_DELIVERY_NOT_FOUND, Map.of()));
                disclose(caller, DELIVERY_READ, "delivery-detail", id, 1);
                List<AttemptItem> attempts =
                    repository.attempts(caller.tenant(), id).stream().map(AttemptItem::of).toList();
                List<CallbackItem> callbacks =
                    repository.callbacks(caller.tenant(), id).stream()
                        .map(CallbackItem::of)
                        .toList();
                return new DeliveryDetail(DeliveryView.of(found), attempts, callbacks);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Provider verification
  // ------------------------------------------------------------------------------------------

  private record Check(
      NotificationType type, MessagingLanguage language, String result, int bodyParameters) {}

  private record Verification(CredentialResult credential, List<Check> checks) {
    boolean ok() {
      return credential == CredentialResult.VALID
          && checks.stream().allMatch(c -> "OK".equals(c.result()));
    }

    boolean unavailable() {
      return checks.stream().anyMatch(c -> "UNAVAILABLE".equals(c.result()));
    }

    List<TemplateCheckView> checkViews() {
      return checks.stream()
          .map(c -> new TemplateCheckView(c.type().name(), c.language().code(), c.result()))
          .toList();
    }
  }

  /** Runs the provider checks outside any transaction. */
  private Verification verifyWithProvider(TenantId tenant, Connector connector) {
    String base =
        properties
            .deepLinkBase(relaxed)
            .orElseThrow(
                () -> new ApiException(ErrorCode.MESSAGING_CONNECTOR_INCOMPLETE, Map.of()));
    StoredCredential stored =
        repository
            .credential(tenant, connector.id())
            .orElseThrow(() -> new ApiException(ErrorCode.MESSAGING_SECRET_UNAVAILABLE, Map.of()));
    String token;
    try {
      token =
          secrets.openToken(stored.ciphertext(), tenant.value(), connector.id(), stored.version());
    } catch (IllegalStateException unreadable) {
      throw new ApiException(ErrorCode.MESSAGING_SECRET_UNAVAILABLE, Map.of());
    }
    Map<String, TemplateConfig> configured = new HashMap<>();
    for (TemplateConfig t : repository.templates(tenant, connector.id())) {
      configured.put(t.type() + "/" + t.language(), t);
    }
    CredentialResult credential = provider.checkCredential(token, connector.phoneNumberId());
    List<Check> checks = new ArrayList<>();
    for (NotificationType type : NotificationType.values()) {
      for (MessagingLanguage language : MessagingLanguage.values()) {
        TemplateConfig config = configured.get(type + "/" + language);
        if (config == null) {
          checks.add(new Check(type, language, "NOT_CONFIGURED", 0));
        } else if (credential != CredentialResult.VALID) {
          checks.add(new Check(type, language, "NOT_CHECKED", 0));
        } else {
          TemplateCheck verdict =
              provider.checkTemplate(
                  token,
                  connector.wabaId(),
                  config.name(),
                  language,
                  DeepLink.expectedButtonUrl(base));
          checks.add(
              new Check(
                  type,
                  language,
                  verdict.result() == TemplateResult.OK ? "OK" : verdict.result().name(),
                  verdict.bodyParameters()));
        }
      }
    }
    return new Verification(credential, checks);
  }

  private void recordVerification(
      TenantId tenant, UUID connector, Verification verification, Instant now) {
    for (Check check : verification.checks()) {
      if ("OK".equals(check.result())) {
        repository.markTemplateVerified(
            tenant, connector, check.type(), check.language(), check.bodyParameters(), now);
      }
    }
  }

  // ------------------------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------------------------

  private void requireSecrets() {
    if (!secrets.available()) {
      throw new ApiException(ErrorCode.MESSAGING_SECRET_UNAVAILABLE, Map.of());
    }
  }

  private static String tenantText(MessagingCaller caller) {
    return caller.tenant().value().toString();
  }

  private Connector readConnector(MessagingCaller caller) {
    return repository
        .findConnector(caller.tenant(), false)
        .orElseThrow(() -> new ApiException(ErrorCode.MESSAGING_CONNECTOR_NOT_FOUND, Map.of()));
  }

  private Connector lockConnector(MessagingCaller caller) {
    return repository
        .findConnector(caller.tenant(), true)
        .orElseThrow(() -> new ApiException(ErrorCode.MESSAGING_CONNECTOR_NOT_FOUND, Map.of()));
  }

  private Recipient lockRecipient(MessagingCaller caller, UUID id) {
    return repository
        .findRecipient(caller.tenant(), id, true)
        .orElseThrow(() -> new ApiException(ErrorCode.MESSAGING_RECIPIENT_NOT_FOUND, Map.of()));
  }

  private ConnectorView view(TenantId tenant, Connector connector) {
    return ConnectorView.of(
        connector,
        repository.templates(tenant, connector.id()),
        repository.recipientCount(tenant),
        properties.graphVersion());
  }

  /** A malformed or unknown id is the same 404 as a foreign one. */
  private static UUID recipientId(String value) {
    if (value == null || !UUID_SHAPE.matcher(value).matches()) {
      throw new ApiException(ErrorCode.MESSAGING_RECIPIENT_NOT_FOUND, Map.of());
    }
    return UUID.fromString(value);
  }

  private static UUID deliveryUuid(String value) {
    if (value == null || !UUID_SHAPE.matcher(value).matches()) {
      throw new ApiException(ErrorCode.MESSAGING_DELIVERY_NOT_FOUND, Map.of());
    }
    return UUID.fromString(value);
  }

  private static int pageSize(String limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    if (!LIMIT.matcher(limit).matches()
        || Integer.parseInt(limit) < 1
        || Integer.parseInt(limit) > MAX_LIMIT) {
      new FieldErrors().add("limit", Constraint.RANGE).throwIfAny();
    }
    return Integer.parseInt(limit);
  }

  private static String toCode(Instant createdAt) {
    return "G" + (createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1000L);
  }

  private static Instant fromCode(String code) {
    long micros = Long.parseLong(code.substring(1));
    return Instant.ofEpochSecond(
        Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
  }

  private static ApiException mapped(DataIntegrityViolationException violated) {
    String text = String.valueOf(violated.getMostSpecificCause().getMessage());
    if (text.contains("recipient limit reached")) {
      return new ApiException(ErrorCode.MESSAGING_RECIPIENT_LIMIT_REACHED, Map.of());
    }
    if (text.contains("integrations_messaging_recipient_number_unique")) {
      return new ApiException(ErrorCode.MESSAGING_RECIPIENT_EXISTS, Map.of());
    }
    if (text.contains("integrations_messaging_connector_one_per_tenant")
        || text.contains("integrations_messaging_connector_pkey")) {
      return new ApiException(ErrorCode.MESSAGING_CONNECTOR_EXISTS, Map.of());
    }
    if (text.contains("integrations_messaging_connector_phone_binding")) {
      // Never reveals that another tenant uses the phone-number id.
      return new ApiException(ErrorCode.MESSAGING_PROVIDER_CREDENTIAL_INVALID, Map.of());
    }
    if (text.contains("test delivery quota")) {
      return new RateLimitedException(
          ErrorCode.MESSAGING_TEST_RATE_LIMITED, TEST_RETRY_AFTER_SECONDS);
    }
    throw violated;
  }

  /** Bounded audit metadata for a connector: state, versions and a change kind. */
  private static Map<String, Object> metadata(Connector connector, String changeKind) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("provider", "WHATSAPP");
    metadata.put("state", connector.state().name());
    metadata.put("configVersion", connector.configVersion());
    metadata.put("credentialVersion", connector.credentialVersion());
    metadata.put("changeKind", changeKind);
    return metadata;
  }

  /** Bounded audit metadata for a recipient: no number, no label, no template name. */
  private static Map<String, Object> recipientMetadata(Recipient recipient, String changeKind) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("provider", "WHATSAPP");
    metadata.put("status", recipient.status().name());
    metadata.put("language", recipient.language().code());
    metadata.put("notificationTypeCount", recipient.notificationTypes().size());
    metadata.put("consentSource", recipient.consentSource().name());
    metadata.put("version", recipient.version());
    metadata.put("changeKind", changeKind);
    return metadata;
  }

  private void record(
      MessagingCaller caller,
      String action,
      String targetType,
      UUID id,
      Instant now,
      Map<String, Object> metadata) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            action,
            targetType,
            id,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-MESSAGING-STATE\nversion=1\naction="
                    + action
                    + "\nid="
                    + id
                    + "\nmetadata="
                    + new java.util.TreeMap<>(metadata)
                    + "\n")));
  }

  /** The module's own lifecycle event: identifiers, state and versions only. */
  private void emit(MessagingCaller caller, String type, Connector connector, Instant now) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("connectorId", connector.id().toString());
    data.put("state", connector.state().name());
    data.put("configVersion", connector.configVersion());
    data.put("credentialVersion", connector.credentialVersion());
    append(caller, type, connector.id(), now, data);
  }

  private void emit(MessagingCaller caller, String type, Recipient recipient, Instant now) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("recipientId", recipient.id().toString());
    data.put("status", recipient.status().name());
    data.put("version", recipient.version());
    append(caller, type, recipient.id(), now, data);
  }

  private void append(
      MessagingCaller caller, String type, UUID subject, Instant now, Map<String, Object> data) {
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            type,
            SCHEMA_VERSION,
            caller.tenant().value().toString(),
            SOURCE,
            subject.toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
  }

  /** The fail-closed disclosure audit: the view and count; identifiers only in the digest. */
  private void disclose(
      MessagingCaller caller, String action, String view, UUID target, int count) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", "first");
    metadata.put("resultCount", count);
    Instant now = Instant.now(clock);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            action,
            "organization",
            caller.tenant().value(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-MESSAGING-DISCLOSURE\nversion=1\naction="
                    + action
                    + "\ntarget="
                    + target
                    + "\nby="
                    + caller.subject()
                    + "\n")));
  }

  private <T> T read(Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setTimeout((int) TRANSACTION_TIMEOUT.toSeconds());
    T result = template.execute(status -> work.get());
    return Objects.requireNonNull(result, "read transaction returned nothing");
  }

  /** Logs a read's bounded outcome; rejections log only their code. */
  private <T> T observed(String operation, Supplier<T> work) {
    try {
      T result = work.get();
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("provider", "WHATSAPP")
          .addKeyValue("outcome", "listed")
          .log("messaging_read");
      return result;
    } catch (ApiException rejected) {
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("provider", "WHATSAPP")
          .addKeyValue("outcome", rejected.code().name().toLowerCase(Locale.ROOT))
          .log("messaging_read_rejected");
      throw rejected;
    }
  }
}
