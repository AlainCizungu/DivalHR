package com.divalhr.core.integrations.messaging.internal;

import com.divalhr.core.integrations.messaging.domain.Connector;
import com.divalhr.core.integrations.messaging.domain.ConnectorState;
import com.divalhr.core.integrations.messaging.domain.ConsentSource;
import com.divalhr.core.integrations.messaging.domain.DeliveryStatus;
import com.divalhr.core.integrations.messaging.domain.MessagingDelivery;
import com.divalhr.core.integrations.messaging.domain.MessagingLanguage;
import com.divalhr.core.integrations.messaging.domain.NotificationType;
import com.divalhr.core.integrations.messaging.domain.Recipient;
import com.divalhr.core.integrations.messaging.domain.RecipientStatus;
import com.divalhr.core.integrations.messaging.domain.SendFailure;
import com.divalhr.core.integrations.messaging.domain.TemplateConfig;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Storage of the WhatsApp connector, its credential versions, recipients, deliveries and evidence
 * (V40). Every tenant-facing operation takes the verified tenant; the schema's guard triggers
 * refuse everything the service must not do. The access token and the recipient numbers are handled
 * only as ciphertext here; this class never sees a plaintext of either.
 */
@Repository
public class JdbcMessagingRepository {

  private static final String CONNECTOR_COLUMNS =
      "id, state, config_version, credential_version, waba_id, phone_number_id, display_number,"
          + " consecutive_failures, verified_at, last_success_at, last_failure_at, created_at,"
          + " updated_at";

  private static final String RECIPIENT_COLUMNS =
      "id, label, number_masked, language, notification_types, status, consent_source,"
          + " consent_confirmed_at, version, created_at, updated_at";

  private static final String DELIVERY_SELECT =
      "SELECT d.id, d.recipient_id, r.label, r.number_masked, d.notification_type, d.kind,"
          + " d.language, d.state, d.attempt_count, d.created_at, d.last_attempt_at,"
          + " d.next_attempt_at, d.accepted_at, d.delivered_at, d.read_at, d.completed_at,"
          + " d.last_http_status, d.last_failure_class"
          + " FROM integrations.messaging_delivery d"
          + " JOIN integrations.messaging_recipient r"
          + "   ON r.tenant_id = d.tenant_id AND r.id = d.recipient_id";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcMessagingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Connector connector(ResultSet rs, int row) throws SQLException {
    return new Connector(
        rs.getObject("id", UUID.class),
        ConnectorState.valueOf(rs.getString("state")),
        rs.getInt("config_version"),
        rs.getInt("credential_version"),
        rs.getString("waba_id"),
        rs.getString("phone_number_id"),
        rs.getString("display_number"),
        rs.getInt("consecutive_failures"),
        instant(rs, "verified_at"),
        instant(rs, "last_success_at"),
        instant(rs, "last_failure_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Recipient recipient(ResultSet rs, int row) throws SQLException {
    List<NotificationType> types = new ArrayList<>();
    for (String code : (String[]) rs.getArray("notification_types").getArray()) {
      types.add(NotificationType.valueOf(code));
    }
    return new Recipient(
        rs.getObject("id", UUID.class),
        rs.getString("label"),
        rs.getString("number_masked"),
        MessagingLanguage.valueOf(rs.getString("language").toUpperCase(java.util.Locale.ROOT)),
        types,
        RecipientStatus.valueOf(rs.getString("status")),
        ConsentSource.valueOf(rs.getString("consent_source")),
        instant(rs, "consent_confirmed_at"),
        rs.getInt("version"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static MessagingDelivery delivery(ResultSet rs, int row) throws SQLException {
    String failure = rs.getString("last_failure_class");
    int status = rs.getInt("last_http_status");
    boolean noStatus = rs.wasNull();
    return new MessagingDelivery(
        rs.getObject("id", UUID.class),
        rs.getObject("recipient_id", UUID.class),
        rs.getString("label"),
        rs.getString("number_masked"),
        NotificationType.valueOf(rs.getString("notification_type")),
        "TEST".equals(rs.getString("kind")),
        MessagingLanguage.valueOf(rs.getString("language").toUpperCase(java.util.Locale.ROOT)),
        DeliveryStatus.valueOf(rs.getString("state")),
        rs.getInt("attempt_count"),
        instant(rs, "created_at"),
        instant(rs, "last_attempt_at"),
        instant(rs, "next_attempt_at"),
        instant(rs, "accepted_at"),
        instant(rs, "delivered_at"),
        instant(rs, "read_at"),
        instant(rs, "completed_at"),
        noStatus ? null : status,
        failure == null ? null : SendFailure.valueOf(failure));
  }

  // ------------------------------------------------------------------------------------------
  // Connector
  // ------------------------------------------------------------------------------------------

  /**
   * Finds the tenant's connector.
   *
   * @param tenant verified tenant
   * @param lock whether to lock the row for the transaction
   * @return the connector, or empty when the tenant has none
   */
  public Optional<Connector> findConnector(TenantId tenant, boolean lock) {
    return jdbc.sql(
            "SELECT "
                + CONNECTOR_COLUMNS
                + " FROM integrations.messaging_connector WHERE tenant_id = :tenant"
                + (lock ? " FOR UPDATE" : ""))
        .param("tenant", tenant.value())
        .query(JdbcMessagingRepository::connector)
        .optional();
  }

  /**
   * Inserts the connector (one per tenant, enforced by the schema) and its first credential.
   *
   * @param tenant verified tenant
   * @param id new id
   * @param wabaId WhatsApp Business Account id
   * @param phoneNumberId sending phone-number id
   * @param displayNumber display number
   * @param actor creating subject
   * @param now instant
   * @param credentialCipher first credential ciphertext
   */
  public void insertConnector(
      TenantId tenant,
      UUID id,
      String wabaId,
      String phoneNumberId,
      String displayNumber,
      String actor,
      Instant now,
      byte[] credentialCipher) {
    jdbc.sql(
            """
            INSERT INTO integrations.messaging_connector
              (tenant_id, id, provider, state, config_version, credential_version, waba_id,
               phone_number_id, display_number, consecutive_failures, created_at, created_by,
               updated_at, updated_by)
            VALUES (:tenant, :id, 'WHATSAPP', 'DRAFT', 1, 1, :waba, :phone, :display, 0,
                    :now, :actor, :now, :actor)
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("waba", wabaId)
        .param("phone", phoneNumberId)
        .param("display", displayNumber)
        .param("now", ts(now))
        .param("actor", actor)
        .update();
    insertCredential(tenant, id, 1, credentialCipher, now);
  }

  private void insertCredential(
      TenantId tenant, UUID connector, int version, byte[] cipher, Instant now) {
    jdbc.sql(
            """
            INSERT INTO integrations.messaging_credential
              (tenant_id, connector_id, version, ciphertext, created_at)
            VALUES (:tenant, :connector, :version, :cipher, :now)
            """)
        .param("tenant", tenant.value())
        .param("connector", connector)
        .param("version", version)
        .param("cipher", cipher)
        .param("now", ts(now))
        .update();
  }

  /**
   * Updates the Meta identifiers when the version matches; verification is cleared.
   *
   * @param tenant verified tenant
   * @param id connector id
   * @param expectedVersion version the caller saw
   * @param wabaId new account id
   * @param phoneNumberId new phone-number id
   * @param displayNumber new display number
   * @param actor subject
   * @param now instant
   * @return whether a row was updated
   */
  public boolean updateConnector(
      TenantId tenant,
      UUID id,
      int expectedVersion,
      String wabaId,
      String phoneNumberId,
      String displayNumber,
      String actor,
      Instant now) {
    return jdbc.sql(
                """
                UPDATE integrations.messaging_connector
                   SET waba_id = :waba, phone_number_id = :phone, display_number = :display,
                       config_version = config_version + 1, verified_at = NULL,
                       updated_at = :now, updated_by = :actor
                 WHERE tenant_id = :tenant AND id = :id AND config_version = :version
                """)
            .param("tenant", tenant.value())
            .param("id", id)
            .param("version", expectedVersion)
            .param("waba", wabaId)
            .param("phone", phoneNumberId)
            .param("display", displayNumber)
            .param("now", ts(now))
            .param("actor", actor)
            .update()
        == 1;
  }

  /**
   * Stores the templates an administrator configured (insert or replace the name; the template must
   * then be verified again).
   *
   * @param tenant verified tenant
   * @param connector connector id
   * @param type notification type
   * @param language language
   * @param name template name
   */
  public void upsertTemplate(
      TenantId tenant,
      UUID connector,
      NotificationType type,
      MessagingLanguage language,
      String name) {
    jdbc.sql(
            """
            INSERT INTO integrations.messaging_template
              (tenant_id, connector_id, notification_type, language, template_name,
               body_parameters, verified_at)
            VALUES (:tenant, :connector, :type, :language, :name, 0, NULL)
            ON CONFLICT (tenant_id, connector_id, notification_type, language)
            DO UPDATE SET template_name = EXCLUDED.template_name, body_parameters = 0,
                          verified_at = NULL
            """)
        .param("tenant", tenant.value())
        .param("connector", connector)
        .param("type", type.name())
        .param("language", language.code())
        .param("name", name)
        .update();
  }

  /**
   * Clears every template's verification (after a configuration change).
   *
   * @param tenant verified tenant
   * @param connector connector id
   */
  public void clearTemplateVerification(TenantId tenant, UUID connector) {
    jdbc.sql(
            "UPDATE integrations.messaging_template SET verified_at = NULL, body_parameters = 0"
                + " WHERE tenant_id = :tenant AND connector_id = :connector")
        .param("tenant", tenant.value())
        .param("connector", connector)
        .update();
  }

  /**
   * The configured templates.
   *
   * @param tenant verified tenant
   * @param connector connector id
   * @return the templates, in catalogue order
   */
  public List<TemplateConfig> templates(TenantId tenant, UUID connector) {
    return jdbc.sql(
            """
            SELECT notification_type, language, template_name, body_parameters, verified_at
              FROM integrations.messaging_template
             WHERE tenant_id = :tenant AND connector_id = :connector
             ORDER BY notification_type, language
            """)
        .param("tenant", tenant.value())
        .param("connector", connector)
        .query(
            (rs, n) ->
                new TemplateConfig(
                    NotificationType.valueOf(rs.getString("notification_type")),
                    MessagingLanguage.valueOf(
                        rs.getString("language").toUpperCase(java.util.Locale.ROOT)),
                    rs.getString("template_name"),
                    rs.getInt("body_parameters"),
                    instant(rs, "verified_at")))
        .list();
  }

  /**
   * Records the provider's confirmation of one template.
   *
   * @param tenant verified tenant
   * @param connector connector id
   * @param type notification type
   * @param language language
   * @param bodyParameters 0 or 1
   * @param now instant
   */
  public void markTemplateVerified(
      TenantId tenant,
      UUID connector,
      NotificationType type,
      MessagingLanguage language,
      int bodyParameters,
      Instant now) {
    jdbc.sql(
            """
            UPDATE integrations.messaging_template
               SET verified_at = :now, body_parameters = :parameters
             WHERE tenant_id = :tenant AND connector_id = :connector
               AND notification_type = :type AND language = :language
            """)
        .param("tenant", tenant.value())
        .param("connector", connector)
        .param("type", type.name())
        .param("language", language.code())
        .param("parameters", bodyParameters)
        .param("now", ts(now))
        .update();
  }

  /**
   * Records the connector's verification and activates it when asked.
   *
   * @param tenant verified tenant
   * @param id connector id
   * @param activate whether to move the connector to ACTIVE
   * @param actor subject
   * @param now instant
   */
  public void markVerified(TenantId tenant, UUID id, boolean activate, String actor, Instant now) {
    jdbc.sql(
            """
            UPDATE integrations.messaging_connector
               SET verified_at = :now,
                   state = CASE WHEN :activate THEN 'ACTIVE' ELSE state END,
                   activated_at = CASE WHEN :activate THEN :now ELSE activated_at END,
                   consecutive_failures = CASE WHEN :activate THEN 0 ELSE consecutive_failures END,
                   config_version = config_version + 1, updated_at = :now, updated_by = :actor
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("activate", activate)
        .param("now", ts(now))
        .param("actor", actor)
        .update();
  }

  /**
   * Disables the connector.
   *
   * @param tenant verified tenant
   * @param id connector id
   * @param actor subject
   * @param now instant
   */
  public void disableConnector(TenantId tenant, UUID id, String actor, Instant now) {
    jdbc.sql(
            """
            UPDATE integrations.messaging_connector
               SET state = 'DISABLED', config_version = config_version + 1,
                   updated_at = :now, updated_by = :actor
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("now", ts(now))
        .param("actor", actor)
        .update();
  }

  /** A stored credential version. */
  public record StoredCredential(int version, byte[] ciphertext) {
    /** Copies the array. */
    public StoredCredential {
      ciphertext = ciphertext == null ? null : ciphertext.clone();
    }

    @Override
    public byte[] ciphertext() {
      return ciphertext == null ? null : ciphertext.clone();
    }
  }

  /**
   * The current credential ciphertext.
   *
   * @param tenant verified tenant
   * @param connector connector id
   * @return the credential, or empty
   */
  public Optional<StoredCredential> credential(TenantId tenant, UUID connector) {
    return jdbc.sql(
            """
            SELECT version, ciphertext FROM integrations.messaging_credential
             WHERE tenant_id = :tenant AND connector_id = :connector AND ciphertext IS NOT NULL
            """)
        .param("tenant", tenant.value())
        .param("connector", connector)
        .query((rs, n) -> new StoredCredential(rs.getInt("version"), rs.getBytes("ciphertext")))
        .optional();
  }

  /**
   * Replaces the credential: the previous ciphertext is erased in the same transaction (no previous
   * copy is kept) and the new one becomes current.
   *
   * @param tenant verified tenant
   * @param id connector id
   * @param newVersion the new credential version
   * @param cipher the new ciphertext
   * @param actor subject
   * @param now instant
   */
  public void rotateCredential(
      TenantId tenant, UUID id, int newVersion, byte[] cipher, String actor, Instant now) {
    jdbc.sql(
            """
            UPDATE integrations.messaging_credential
               SET ciphertext = NULL, erased_at = :now
             WHERE tenant_id = :tenant AND connector_id = :id AND ciphertext IS NOT NULL
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("now", ts(now))
        .update();
    insertCredential(tenant, id, newVersion, cipher, now);
    jdbc.sql(
            """
            UPDATE integrations.messaging_connector
               SET credential_version = :version, config_version = config_version + 1,
                   updated_at = :now, updated_by = :actor
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("version", newVersion)
        .param("now", ts(now))
        .param("actor", actor)
        .update();
  }

  /**
   * Applies a finished delivery to the connector projection: delivered or read clears the counter;
   * a terminal failure moves it by one and suspends the connector at ten, cancelling its open
   * deliveries.
   *
   * @param tenant owning tenant
   * @param connector connector id
   * @param success whether the delivery reached the handset
   * @param now instant
   * @return true when this call suspended the connector
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background delivery worker and callbacks")
  public boolean applyOutcome(TenantId tenant, UUID connector, boolean success, Instant now) {
    if (success) {
      jdbc.sql(
              """
              UPDATE integrations.messaging_connector
                 SET consecutive_failures = 0, last_success_at = :now
               WHERE tenant_id = :tenant AND id = :id
              """)
          .param("tenant", tenant.value())
          .param("id", connector)
          .param("now", ts(now))
          .update();
      return false;
    }
    Optional<Connector> locked = findConnector(tenant, true);
    if (locked.isEmpty()
        || !locked.get().id().equals(connector)
        || locked.get().state() != ConnectorState.ACTIVE) {
      return false;
    }
    boolean suspend = locked.get().consecutiveFailures() + 1 >= 10;
    jdbc.sql(
            """
            UPDATE integrations.messaging_connector
               SET consecutive_failures = consecutive_failures + 1, last_failure_at = :now,
                   state = CASE WHEN :suspend THEN 'SUSPENDED' ELSE state END,
                   config_version = CASE WHEN :suspend THEN config_version + 1 ELSE config_version END,
                   updated_at = :now, updated_by = 'system'
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", tenant.value())
        .param("id", connector)
        .param("now", ts(now))
        .param("suspend", suspend)
        .update();
    if (suspend) {
      cancelOpenDeliveries(tenant, connector, now);
    }
    return suspend;
  }

  // ------------------------------------------------------------------------------------------
  // Recipients
  // ------------------------------------------------------------------------------------------

  /**
   * Inserts a recipient (the schema enforces 25 per tenant and number uniqueness).
   *
   * @param tenant verified tenant
   * @param id new id
   * @param connector connector id
   * @param label label
   * @param cipher sealed number
   * @param digest keyed number digest
   * @param masked masked number
   * @param language language
   * @param types selected types
   * @param source consent source
   * @param confirmedAt consent confirmation instant
   * @param actor attesting subject
   * @param now instant
   */
  public void insertRecipient(
      TenantId tenant,
      UUID id,
      UUID connector,
      String label,
      byte[] cipher,
      byte[] digest,
      String masked,
      MessagingLanguage language,
      List<NotificationType> types,
      ConsentSource source,
      Instant confirmedAt,
      String actor,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO integrations.messaging_recipient
              (tenant_id, id, connector_id, label, number_ciphertext, number_digest, number_masked,
               language, notification_types, status, consent_source, consent_confirmed_at,
               consent_attested_by, version, created_at, created_by, updated_at, updated_by)
            VALUES (:tenant, :id, :connector, :label, :cipher, :digest, :masked, :language,
                    CAST(:types AS text[]), 'ACTIVE', :source, :confirmed, :actor, 1, :now, :actor,
                    :now, :actor)
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("connector", connector)
        .param("label", label)
        .param("cipher", cipher)
        .param("digest", digest)
        .param("masked", masked)
        .param("language", language.code())
        .param("types", textArray(types))
        .param("source", source.name())
        .param("confirmed", ts(confirmedAt))
        .param("actor", actor)
        .param("now", ts(now))
        .update();
  }

  private static String textArray(List<NotificationType> types) {
    return "{" + String.join(",", types.stream().map(Enum::name).toList()) + "}";
  }

  /**
   * Finds a recipient.
   *
   * @param tenant verified tenant
   * @param id recipient id
   * @param lock whether to lock the row for the transaction
   * @return the recipient, or empty when missing or foreign
   */
  public Optional<Recipient> findRecipient(TenantId tenant, UUID id, boolean lock) {
    return jdbc.sql(
            "SELECT "
                + RECIPIENT_COLUMNS
                + " FROM integrations.messaging_recipient WHERE tenant_id = :tenant AND id = :id"
                + (lock ? " FOR UPDATE" : ""))
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcMessagingRepository::recipient)
        .optional();
  }

  /**
   * Every recipient of the tenant (at most 25), oldest first.
   *
   * @param tenant verified tenant
   * @return the recipients
   */
  public List<Recipient> recipients(TenantId tenant) {
    return jdbc.sql(
            "SELECT "
                + RECIPIENT_COLUMNS
                + " FROM integrations.messaging_recipient WHERE tenant_id = :tenant"
                + " ORDER BY created_at, id LIMIT 25")
        .param("tenant", tenant.value())
        .query(JdbcMessagingRepository::recipient)
        .list();
  }

  /**
   * @param tenant verified tenant
   * @return how many recipients the tenant has
   */
  public int recipientCount(TenantId tenant) {
    Integer count =
        jdbc.sql("SELECT count(*) FROM integrations.messaging_recipient WHERE tenant_id = :tenant")
            .param("tenant", tenant.value())
            .query(Integer.class)
            .single();
    return count == null ? 0 : count;
  }

  /**
   * Updates a recipient when the version matches.
   *
   * @param tenant verified tenant
   * @param id recipient id
   * @param expectedVersion version the caller saw
   * @param label label
   * @param language language
   * @param types selected types
   * @param source consent source
   * @param confirmedAt consent confirmation instant
   * @param actor attesting subject
   * @param now instant
   * @return whether a row was updated
   */
  public boolean updateRecipient(
      TenantId tenant,
      UUID id,
      int expectedVersion,
      String label,
      MessagingLanguage language,
      List<NotificationType> types,
      ConsentSource source,
      Instant confirmedAt,
      String actor,
      Instant now) {
    return jdbc.sql(
                """
                UPDATE integrations.messaging_recipient
                   SET label = :label, language = :language,
                       notification_types = CAST(:types AS text[]), consent_source = :source,
                       consent_confirmed_at = :confirmed, consent_attested_by = :actor,
                       version = version + 1, updated_at = :now, updated_by = :actor
                 WHERE tenant_id = :tenant AND id = :id AND version = :version
                """)
            .param("tenant", tenant.value())
            .param("id", id)
            .param("version", expectedVersion)
            .param("label", label)
            .param("language", language.code())
            .param("types", textArray(types))
            .param("source", source.name())
            .param("confirmed", ts(confirmedAt))
            .param("actor", actor)
            .param("now", ts(now))
            .update()
        == 1;
  }

  /**
   * Changes a recipient's status.
   *
   * @param tenant verified tenant
   * @param id recipient id
   * @param status new status
   * @param actor subject
   * @param now instant
   */
  public void setRecipientStatus(
      TenantId tenant, UUID id, RecipientStatus status, String actor, Instant now) {
    jdbc.sql(
            """
            UPDATE integrations.messaging_recipient
               SET status = :status, version = version + 1, updated_at = :now, updated_by = :actor
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("status", status.name())
        .param("now", ts(now))
        .param("actor", actor)
        .update();
  }

  /** A recipient's sealed number with the ids bound into its authenticated data. */
  public record StoredNumber(UUID connectorId, byte[] ciphertext) {
    /** Copies the array. */
    public StoredNumber {
      ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] ciphertext() {
      return ciphertext.clone();
    }
  }

  /**
   * The sealed number, for sending.
   *
   * @param tenant owning tenant
   * @param recipient recipient id
   * @return the sealed number, or empty
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background delivery worker")
  public Optional<StoredNumber> number(TenantId tenant, UUID recipient) {
    return jdbc.sql(
            """
            SELECT connector_id, number_ciphertext FROM integrations.messaging_recipient
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", tenant.value())
        .param("id", recipient)
        .query(
            (rs, n) ->
                new StoredNumber(
                    rs.getObject("connector_id", UUID.class), rs.getBytes("number_ciphertext")))
        .optional();
  }

  // ------------------------------------------------------------------------------------------
  // Deliveries
  // ------------------------------------------------------------------------------------------

  /**
   * Inserts a pending delivery; the first attempt is due at once.
   *
   * @param tenant verified tenant
   * @param id delivery id
   * @param connector connector id
   * @param recipient recipient id
   * @param eventId public event id (a fresh id for a test)
   * @param type notification type
   * @param resourceId the resource of the event, or null for a test
   * @param language template language
   * @param now instant
   */
  public void insertDelivery(
      TenantId tenant,
      UUID id,
      UUID connector,
      UUID recipient,
      UUID eventId,
      NotificationType type,
      UUID resourceId,
      MessagingLanguage language,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO integrations.messaging_delivery
              (tenant_id, id, connector_id, recipient_id, event_id, notification_type, kind,
               resource_id, language, state, attempt_count, created_at, next_attempt_at)
            VALUES (:tenant, :id, :connector, :recipient, :event, :type, :kind, :resource,
                    :language, 'PENDING', 0, :now, :now)
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("connector", connector)
        .param("recipient", recipient)
        .param("event", eventId)
        .param("type", type.name())
        .param("kind", type == NotificationType.CONNECTOR_TEST ? "TEST" : "EVENT")
        .param("resource", resourceId)
        .param("language", language.code())
        .param("now", ts(now))
        .update();
  }

  /**
   * One page of deliveries, newest first.
   *
   * @param tenant verified tenant
   * @param recipient restrict to one recipient, or null
   * @param afterCreated keyset position instant, or null for the first page
   * @param afterId keyset position id
   * @param limit maximum rows
   * @return the rows
   */
  public List<MessagingDelivery> deliveries(
      TenantId tenant, UUID recipient, Instant afterCreated, UUID afterId, int limit) {
    String where =
        (recipient == null ? "" : " AND d.recipient_id = :recipient")
            + (afterCreated == null ? "" : " AND (d.created_at, d.id) < (:afterCreated, :afterId)");
    var spec =
        jdbc.sql(
                DELIVERY_SELECT
                    + " WHERE d.tenant_id = :tenant"
                    + where
                    + " ORDER BY d.created_at DESC, d.id DESC LIMIT :limit")
            .param("tenant", tenant.value())
            .param("limit", limit);
    if (recipient != null) {
      spec = spec.param("recipient", recipient);
    }
    if (afterCreated != null) {
      spec = spec.param("afterCreated", ts(afterCreated)).param("afterId", afterId);
    }
    return spec.query(JdbcMessagingRepository::delivery).list();
  }

  /**
   * One delivery.
   *
   * @param tenant verified tenant
   * @param id delivery id
   * @return the delivery, or empty when missing or foreign
   */
  public Optional<MessagingDelivery> delivery(TenantId tenant, UUID id) {
    return jdbc.sql(DELIVERY_SELECT + " WHERE d.tenant_id = :tenant AND d.id = :id")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcMessagingRepository::delivery)
        .optional();
  }

  /**
   * One recorded attempt.
   *
   * @param number attempt number
   * @param startedAt start
   * @param finishedAt end
   * @param httpStatus exact status when a response arrived
   * @param failure closed class, null on acceptance
   * @param latencyMillis wall time
   */
  public record AttemptView(
      int number,
      Instant startedAt,
      Instant finishedAt,
      Integer httpStatus,
      SendFailure failure,
      int latencyMillis) {}

  /**
   * The attempts of a delivery.
   *
   * @param tenant verified tenant
   * @param delivery delivery id
   * @return the attempts, oldest first
   */
  public List<AttemptView> attempts(TenantId tenant, UUID delivery) {
    return jdbc.sql(
            """
            SELECT number, started_at, finished_at, http_status, failure_class, latency_ms
              FROM integrations.messaging_delivery_attempt
             WHERE tenant_id = :tenant AND delivery_id = :delivery ORDER BY number
            """)
        .param("tenant", tenant.value())
        .param("delivery", delivery)
        .query(
            (rs, n) -> {
              int status = rs.getInt("http_status");
              boolean noStatus = rs.wasNull();
              String failure = rs.getString("failure_class");
              return new AttemptView(
                  rs.getInt("number"),
                  instant(rs, "started_at"),
                  instant(rs, "finished_at"),
                  noStatus ? null : status,
                  failure == null ? null : SendFailure.valueOf(failure),
                  rs.getInt("latency_ms"));
            })
        .list();
  }

  /**
   * One recorded provider status callback.
   *
   * @param status SENT, DELIVERED, READ or FAILED
   * @param occurredAt provider time
   * @param receivedAt our time
   * @param errorClass closed class for FAILED, else null
   */
  public record CallbackView(
      String status, Instant occurredAt, Instant receivedAt, SendFailure errorClass) {}

  /**
   * The verified callbacks of a delivery.
   *
   * @param tenant verified tenant
   * @param delivery delivery id
   * @return the callbacks, oldest first
   */
  public List<CallbackView> callbacks(TenantId tenant, UUID delivery) {
    return jdbc.sql(
            """
            SELECT status, occurred_at, received_at, error_class
              FROM integrations.messaging_status_callback
             WHERE tenant_id = :tenant AND delivery_id = :delivery ORDER BY occurred_at, status
            """)
        .param("tenant", tenant.value())
        .param("delivery", delivery)
        .query(
            (rs, n) -> {
              String error = rs.getString("error_class");
              return new CallbackView(
                  rs.getString("status"),
                  instant(rs, "occurred_at"),
                  instant(rs, "received_at"),
                  error == null ? null : SendFailure.valueOf(error));
            })
        .list();
  }

  /**
   * Cancels every open delivery of a connector.
   *
   * @param tenant verified tenant
   * @param connector connector id
   * @param now instant
   * @return deliveries cancelled
   */
  public int cancelOpenDeliveries(TenantId tenant, UUID connector, Instant now) {
    return jdbc.sql(
            """
            UPDATE integrations.messaging_delivery
               SET state = 'CANCELLED', next_attempt_at = NULL, completed_at = :now,
                   last_failure_class = 'CONNECTOR_INACTIVE', claim_token = NULL, claimed_until = NULL
             WHERE tenant_id = :tenant AND connector_id = :connector
               AND state IN ('PENDING', 'RETRYING')
            """)
        .param("tenant", tenant.value())
        .param("connector", connector)
        .param("now", ts(now))
        .update();
  }

  /**
   * Cancels every open delivery of a recipient.
   *
   * @param tenant verified tenant
   * @param recipient recipient id
   * @param now instant
   * @return deliveries cancelled
   */
  public int cancelOpenDeliveriesOfRecipient(TenantId tenant, UUID recipient, Instant now) {
    return jdbc.sql(
            """
            UPDATE integrations.messaging_delivery
               SET state = 'CANCELLED', next_attempt_at = NULL, completed_at = :now,
                   last_failure_class = 'CONNECTOR_INACTIVE', claim_token = NULL, claimed_until = NULL
             WHERE tenant_id = :tenant AND recipient_id = :recipient
               AND state IN ('PENDING', 'RETRYING')
            """)
        .param("tenant", tenant.value())
        .param("recipient", recipient)
        .param("now", ts(now))
        .update();
  }

  // ------------------------------------------------------------------------------------------
  // Fan-out
  // ------------------------------------------------------------------------------------------

  /**
   * A recipient due a notification.
   *
   * @param recipientId recipient
   * @param connectorId connector
   * @param language the recipient's language
   */
  public record Subscriber(UUID recipientId, UUID connectorId, MessagingLanguage language) {}

  /**
   * Active recipients of an active connector who selected the notification, for an event recorded
   * after the connector was activated and after the recipient existed.
   *
   * @param tenant the event's tenant (taken from the committed event, not a request)
   * @param type notification type
   * @param recordedAt when the event was recorded
   * @return the subscribers
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background event fan-out")
  public List<Subscriber> subscribers(TenantId tenant, NotificationType type, Instant recordedAt) {
    return jdbc.sql(
            """
            SELECT r.id, r.connector_id, r.language
              FROM integrations.messaging_recipient r
              JOIN integrations.messaging_connector c
                ON c.tenant_id = r.tenant_id AND c.id = r.connector_id
             WHERE r.tenant_id = :tenant AND r.status = 'ACTIVE' AND c.state = 'ACTIVE'
               AND :type = ANY (r.notification_types)
               AND r.created_at <= :recordedAt AND c.activated_at <= :recordedAt
             ORDER BY r.created_at, r.id
            """)
        .param("tenant", tenant.value())
        .param("type", type.name())
        .param("recordedAt", ts(recordedAt))
        .query(
            (rs, n) ->
                new Subscriber(
                    rs.getObject("id", UUID.class),
                    rs.getObject("connector_id", UUID.class),
                    MessagingLanguage.valueOf(
                        rs.getString("language").toUpperCase(java.util.Locale.ROOT))))
        .list();
  }

  /**
   * The fan-out read position.
   *
   * @param watermark created_at of the last processed event
   * @param eventId id of the last processed event
   */
  public record FanoutPosition(Instant watermark, UUID eventId) {}

  /**
   * Reads and locks the fan-out position.
   *
   * @return the watermark and the last event id
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background event fan-out")
  public FanoutPosition lockFanout() {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('integrations.messaging_fanout', 0))")
        .query()
        .listOfRows();
    return jdbc.sql(
            "SELECT watermark, watermark_event_id FROM integrations.messaging_fanout_state"
                + " WHERE id = 1")
        .query(
            (rs, n) ->
                new FanoutPosition(
                    instant(rs, "watermark"), rs.getObject("watermark_event_id", UUID.class)))
        .single();
  }

  /**
   * Moves the fan-out position.
   *
   * @param position the new position
   * @param now instant
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background event fan-out")
  public void saveFanout(FanoutPosition position, Instant now) {
    jdbc.sql(
            "UPDATE integrations.messaging_fanout_state SET watermark = :w, watermark_event_id ="
                + " :e, updated_at = :now WHERE id = 1")
        .param("w", ts(position.watermark()))
        .param("e", position.eventId())
        .param("now", ts(now))
        .update();
  }

  /**
   * Which of the given events were already fanned out.
   *
   * @param ids event ids
   * @return those already recorded
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background event fan-out")
  public Set<UUID> alreadyFannedOut(List<UUID> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        jdbc.sql(
                "SELECT event_id FROM integrations.messaging_fanout_event WHERE event_id IN (:ids)")
            .param("ids", ids)
            .query(UUID.class)
            .list());
  }

  /**
   * Records an event as fanned out.
   *
   * @param eventId event id
   * @param tenant tenant
   * @param now instant
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background event fan-out")
  public void markFannedOut(UUID eventId, TenantId tenant, Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.messaging_fanout_event (event_id, tenant_id, fanned_at)"
                + " VALUES (:e, :t, :now) ON CONFLICT DO NOTHING")
        .param("e", eventId)
        .param("t", tenant.value())
        .param("now", ts(now))
        .update();
  }

  // ------------------------------------------------------------------------------------------
  // Worker
  // ------------------------------------------------------------------------------------------

  /**
   * A delivery claimed by a worker.
   *
   * @param tenant owning tenant
   * @param id delivery id
   * @param connectorId connector
   * @param recipientId recipient
   * @param type notification type
   * @param language template language
   * @param attemptCount attempts already made
   * @param claimToken the lease token
   */
  public record Claimed(
      TenantId tenant,
      UUID id,
      UUID connectorId,
      UUID recipientId,
      NotificationType type,
      MessagingLanguage language,
      int attemptCount,
      UUID claimToken) {}

  /**
   * Claims due deliveries for this worker (row locks with SKIP LOCKED, so instances never share
   * one; a crashed worker's lease simply expires).
   *
   * @param now instant
   * @param lease how long the claim lasts
   * @param limit maximum rows
   * @return the claimed deliveries
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background delivery worker")
  public List<Claimed> claimDue(Instant now, Duration lease, int limit) {
    UUID token = UUID.randomUUID();
    return jdbc.sql(
            """
            WITH due AS (
              SELECT tenant_id, id FROM integrations.messaging_delivery
               WHERE state IN ('PENDING', 'RETRYING') AND next_attempt_at <= :now
                 AND (claimed_until IS NULL OR claimed_until < :now)
               ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED)
            UPDATE integrations.messaging_delivery d
               SET claim_token = :token, claimed_until = :until
              FROM due
             WHERE d.tenant_id = due.tenant_id AND d.id = due.id
            RETURNING d.tenant_id, d.id, d.connector_id, d.recipient_id, d.notification_type,
                      d.language, d.attempt_count
            """)
        .param("now", ts(now))
        .param("until", ts(now.plus(lease)))
        .param("limit", limit)
        .param("token", token)
        .query(
            (rs, n) ->
                new Claimed(
                    new TenantId(rs.getObject("tenant_id", UUID.class)),
                    rs.getObject("id", UUID.class),
                    rs.getObject("connector_id", UUID.class),
                    rs.getObject("recipient_id", UUID.class),
                    NotificationType.valueOf(rs.getString("notification_type")),
                    MessagingLanguage.valueOf(
                        rs.getString("language").toUpperCase(java.util.Locale.ROOT)),
                    rs.getInt("attempt_count"),
                    token))
        .list();
  }

  /** What one attempt decided. */
  public enum Decision {
    /** The provider accepted the message. */
    ACCEPTED,
    /** A retryable failure; the next attempt is scheduled. */
    RETRY,
    /** A terminal failure. */
    FAILED,
    /** The last attempt ended without knowing whether the provider accepted it. */
    UNKNOWN
  }

  /**
   * Records one attempt's result under the claim; returns false when the claim was lost.
   *
   * @param claim the claimed delivery
   * @param startedAt attempt start
   * @param finishedAt attempt end
   * @param httpStatus exact status when a response arrived
   * @param failure closed class, null on acceptance
   * @param latencyMillis wall time
   * @param decision what the attempt decided
   * @param providerMessageId the provider's message id when accepted
   * @param nextAttemptAt when to retry, or null
   * @return whether the result was recorded
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background delivery worker")
  public boolean recordAttempt(
      Claimed claim,
      Instant startedAt,
      Instant finishedAt,
      Integer httpStatus,
      SendFailure failure,
      int latencyMillis,
      Decision decision,
      String providerMessageId,
      Instant nextAttemptAt) {
    int number = claim.attemptCount() + 1;
    List<UUID> locked =
        jdbc.sql(
                """
                SELECT id FROM integrations.messaging_delivery
                 WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
                   AND state IN ('PENDING', 'RETRYING') FOR UPDATE
                """)
            .param("tenant", claim.tenant().value())
            .param("id", claim.id())
            .param("token", claim.claimToken())
            .query(UUID.class)
            .list();
    if (locked.size() != 1) {
      return false;
    }
    jdbc.sql(
            """
            INSERT INTO integrations.messaging_delivery_attempt
              (tenant_id, delivery_id, number, started_at, finished_at, http_status, failure_class,
               latency_ms)
            VALUES (:tenant, :id, :number, :started, :finished, :status, :failure, :latency)
            """)
        .param("tenant", claim.tenant().value())
        .param("id", claim.id())
        .param("number", number)
        .param("started", ts(startedAt))
        .param("finished", ts(finishedAt))
        .param("status", httpStatus)
        .param("failure", failure == null ? null : failure.name())
        .param("latency", latencyMillis)
        .update();
    String state =
        switch (decision) {
          case ACCEPTED -> "ACCEPTED";
          case RETRY -> "RETRYING";
          case FAILED -> "FAILED";
          case UNKNOWN -> "STATUS_UNKNOWN";
        };
    boolean finished = decision == Decision.FAILED || decision == Decision.UNKNOWN;
    jdbc.sql(
            """
            UPDATE integrations.messaging_delivery
               SET state = :state, attempt_count = :number, last_attempt_at = :finished,
                   last_http_status = :status, last_failure_class = :failure,
                   next_attempt_at = :next, provider_message_id = :provider,
                   accepted_at = CAST(:accepted AS timestamptz),
                   completed_at = CAST(:completed AS timestamptz),
                   claim_token = NULL, claimed_until = NULL
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", claim.tenant().value())
        .param("id", claim.id())
        .param("state", state)
        .param("number", number)
        .param("finished", ts(finishedAt))
        .param("status", httpStatus)
        .param("failure", failure == null ? null : failure.name())
        .param("next", ts(nextAttemptAt))
        .param("provider", providerMessageId)
        .param("accepted", decision == Decision.ACCEPTED ? ts(finishedAt) : null)
        .param("completed", finished ? ts(finishedAt) : null)
        .update();
    return true;
  }

  /**
   * Cancels a claimed delivery whose connector or recipient is no longer active.
   *
   * @param claim the claimed delivery
   * @param now instant
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background delivery worker")
  public void cancel(Claimed claim, Instant now) {
    jdbc.sql(
            """
            UPDATE integrations.messaging_delivery
               SET state = 'CANCELLED', next_attempt_at = NULL, completed_at = :now,
                   last_failure_class = 'CONNECTOR_INACTIVE', claim_token = NULL, claimed_until = NULL
             WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
               AND state IN ('PENDING', 'RETRYING')
            """)
        .param("tenant", claim.tenant().value())
        .param("id", claim.id())
        .param("token", claim.claimToken())
        .param("now", ts(now))
        .update();
  }

  // ------------------------------------------------------------------------------------------
  // Provider callbacks
  // ------------------------------------------------------------------------------------------

  /** The result of applying one verified callback status. */
  public enum CallbackEffect {
    /** No delivery with this provider message id and phone-number binding. */
    UNKNOWN_MESSAGE,
    /** The same status for the same delivery was already recorded. */
    DUPLICATE,
    /** Recorded; the delivery state did not change (a lower status, or already final). */
    RECORDED,
    /** Recorded and the delivery state moved forward. */
    ADVANCED
  }

  /**
   * Applies one verified provider status to the delivery it belongs to, idempotently and
   * monotonically (D111-9, D111-10): the message is found by provider message id and the stored
   * phone-number binding of its connector, a repeated status is recorded once, a lower status never
   * moves the state backward, and READ implies delivered.
   *
   * @param providerMessageId the provider message id
   * @param phoneNumberId the callback's phone-number id (must match the stored connector binding)
   * @param status SENT, DELIVERED, READ or FAILED
   * @param occurredAt the provider's time (already bounded)
   * @param errorClass closed class for FAILED, else null
   * @param now our time
   * @return the effect
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("provider callback boundary")
  public CallbackEffect applyCallback(
      String providerMessageId,
      String phoneNumberId,
      String status,
      Instant occurredAt,
      SendFailure errorClass,
      Instant now) {
    record Found(UUID tenant, UUID id, UUID connector, String state) {}
    List<Found> found =
        jdbc.sql(
                """
                SELECT d.tenant_id, d.id, d.connector_id, d.state
                  FROM integrations.messaging_delivery d
                  JOIN integrations.messaging_connector c
                    ON c.tenant_id = d.tenant_id AND c.id = d.connector_id
                 WHERE d.provider_message_id = :provider AND c.phone_number_id = :phone
                 FOR UPDATE OF d
                """)
            .param("provider", providerMessageId)
            .param("phone", phoneNumberId)
            .query(
                (rs, n) ->
                    new Found(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getObject("connector_id", UUID.class),
                        rs.getString("state")))
            .list();
    if (found.size() != 1) {
      return CallbackEffect.UNKNOWN_MESSAGE;
    }
    Found delivery = found.get(0);
    int inserted =
        jdbc.sql(
                """
                INSERT INTO integrations.messaging_status_callback
                  (tenant_id, delivery_id, status, occurred_at, received_at, error_class)
                VALUES (:tenant, :id, :status, :occurred, :now, :error)
                ON CONFLICT DO NOTHING
                """)
            .param("tenant", delivery.tenant())
            .param("id", delivery.id())
            .param("status", status)
            .param("occurred", ts(occurredAt))
            .param("now", ts(now))
            .param("error", errorClass == null ? null : errorClass.name())
            .update();
    if (inserted == 0) {
      return CallbackEffect.DUPLICATE;
    }
    DeliveryStatus current = DeliveryStatus.valueOf(delivery.state());
    TenantId tenant = new TenantId(delivery.tenant());
    switch (status) {
      case "DELIVERED":
        if (current == DeliveryStatus.ACCEPTED) {
          setProviderState(delivery.tenant(), delivery.id(), "DELIVERED", occurredAt, null, now);
          applyOutcome(tenant, delivery.connector(), true, now);
          return CallbackEffect.ADVANCED;
        }
        return CallbackEffect.RECORDED;
      case "READ":
        if (current == DeliveryStatus.ACCEPTED || current == DeliveryStatus.DELIVERED) {
          setProviderState(delivery.tenant(), delivery.id(), "READ", occurredAt, occurredAt, now);
          applyOutcome(tenant, delivery.connector(), true, now);
          return CallbackEffect.ADVANCED;
        }
        return CallbackEffect.RECORDED;
      case "FAILED":
        if (current == DeliveryStatus.ACCEPTED) {
          jdbc.sql(
                  """
                  UPDATE integrations.messaging_delivery
                     SET state = 'FAILED', last_failure_class = :failure, completed_at = :now
                   WHERE tenant_id = :tenant AND id = :id
                  """)
              .param("tenant", delivery.tenant())
              .param("id", delivery.id())
              .param(
                  "failure", (errorClass == null ? SendFailure.PROVIDER_FAILED : errorClass).name())
              .param("now", ts(now))
              .update();
          applyOutcome(tenant, delivery.connector(), false, now);
          return CallbackEffect.ADVANCED;
        }
        return CallbackEffect.RECORDED;
      default:
        return CallbackEffect.RECORDED;
    }
  }

  private void setProviderState(
      UUID tenant, UUID id, String state, Instant deliveredAt, Instant readAt, Instant now) {
    jdbc.sql(
            """
            UPDATE integrations.messaging_delivery
               SET state = :state,
                   delivered_at = COALESCE(delivered_at, :delivered),
                   read_at = CAST(:read AS timestamptz),
                   completed_at = CASE WHEN :state = 'READ' THEN :now ELSE completed_at END
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", tenant)
        .param("id", id)
        .param("state", state)
        .param("delivered", ts(deliveredAt))
        .param("read", ts(readAt))
        .param("now", ts(now))
        .update();
  }
}
