-- MVP-111 (Issue #163): a tenant administrator connects one WhatsApp Business Cloud API connector,
-- registers up to 25 opted-in operational recipients and DivalHR delivers generic notifications with
-- an authenticated deep link only (ADR 0029). Additive to V39.
--
-- Ownership: the integrations module; only the bounded com.divalhr.core.integrations package reads
-- or writes these objects. Business events reach it only through the platform outbox reader port.
-- The key to tenant.organization is the documented tenant-binding exception.
--
-- Classification: Restricted. The Meta system-user token and every recipient phone number are stored
-- only as AES-256-GCM ciphertext (the key lives outside the database, a dedicated protected file);
-- the number is additionally indexed by a domain-separated HMAC digest for per-connector uniqueness.
-- No message body, template parameter, provider response body or header, or deep link is stored.
--
-- Shape:
--   * messaging_connector: one per tenant (UNIQUE tenant), states DRAFT/ACTIVE/DISABLED/SUSPENDED,
--     guarded transitions, never deleted.
--   * messaging_template: the admin-configured Meta template name per notification type and language.
--   * messaging_credential: one row per credential version; exactly one holds ciphertext (the
--     current one); rotation erases the previous ciphertext at once and it can never be restored.
--   * messaging_recipient: at most 25 per tenant, immutable identity, encrypted number, digest
--     uniqueness, consent attestation, guarded versions, never deleted.
--   * messaging_delivery: one per (connector, recipient, event); forward-only monotonic state;
--     test deliveries are limited inside the database (3 per recipient and 10 per tenant per hour).
--   * messaging_delivery_attempt / messaging_status_callback: append-only evidence.
--   * messaging_fanout_event / messaging_fanout_state: the fan-out ledger and read watermark.
--
-- Rollback: db/rollback/V40__rollback.sql (manual, never run by Flyway) restores V39 exactly only
-- while no MVP-111 data exists; there is no override.

CREATE FUNCTION integrations.messaging_append_only() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_append_only',
        MESSAGE = 'messaging evidence is never deleted, truncated or (where append-only) updated';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Connector
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.messaging_connector (
    tenant_id            uuid        NOT NULL,
    id                   uuid        NOT NULL,
    provider             text        NOT NULL,
    state                text        NOT NULL,
    config_version       integer     NOT NULL,
    credential_version   integer     NOT NULL,
    waba_id              text        NOT NULL,
    phone_number_id      text        NOT NULL,
    display_number       text        NOT NULL,
    consecutive_failures integer     NOT NULL,
    verified_at          timestamptz,
    activated_at         timestamptz,
    last_success_at      timestamptz,
    last_failure_at      timestamptz,
    created_at           timestamptz NOT NULL,
    created_by           text        NOT NULL,
    updated_at           timestamptz NOT NULL,
    updated_by           text        NOT NULL,
    CONSTRAINT integrations_messaging_connector_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT integrations_messaging_connector_one_per_tenant UNIQUE (tenant_id),
    CONSTRAINT integrations_messaging_connector_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT integrations_messaging_connector_provider CHECK (provider = 'WHATSAPP'),
    CONSTRAINT integrations_messaging_connector_state CHECK (state IN ('DRAFT', 'ACTIVE', 'DISABLED', 'SUSPENDED')),
    CONSTRAINT integrations_messaging_connector_versions CHECK (config_version >= 1 AND credential_version >= 1),
    CONSTRAINT integrations_messaging_connector_waba CHECK (waba_id ~ '^[0-9]{5,20}$'),
    CONSTRAINT integrations_messaging_connector_phone_id CHECK (phone_number_id ~ '^[0-9]{5,20}$'),
    CONSTRAINT integrations_messaging_connector_display CHECK (display_number ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT integrations_messaging_connector_failures CHECK (consecutive_failures BETWEEN 0 AND 10),
    CONSTRAINT integrations_messaging_connector_suspended
        CHECK (state <> 'SUSPENDED' OR consecutive_failures >= 10),
    CONSTRAINT integrations_messaging_connector_active
        CHECK (state <> 'ACTIVE' OR (verified_at IS NOT NULL AND activated_at IS NOT NULL)),
    CONSTRAINT integrations_messaging_connector_actor
        CHECK (char_length(created_by) BETWEEN 1 AND 255 AND char_length(updated_by) BETWEEN 1 AND 255),
    CONSTRAINT integrations_messaging_connector_updated CHECK (updated_at >= created_at)
);
COMMENT ON TABLE integrations.messaging_connector IS
    'The tenant''s single WhatsApp Business connector configuration (MVP-111); never deleted';

-- A phone-number id is served by one connector only, so a callback can never match two tenants.
CREATE UNIQUE INDEX integrations_messaging_connector_phone_binding
    ON integrations.messaging_connector (phone_number_id);

CREATE FUNCTION integrations.messaging_connector_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    config_changed boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'DRAFT' OR NEW.config_version <> 1 OR NEW.credential_version <> 1
            OR NEW.consecutive_failures <> 0 OR NEW.verified_at IS NOT NULL OR NEW.activated_at IS NOT NULL
            OR NEW.last_success_at IS NOT NULL OR NEW.last_failure_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_connector_initial',
                MESSAGE = 'a connector starts as a draft at version 1';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.id <> OLD.id OR NEW.created_at <> OLD.created_at
        OR NEW.created_by <> OLD.created_by OR NEW.provider <> OLD.provider THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_connector_identity',
            MESSAGE = 'connector identity is immutable';
    END IF;
    config_changed := NEW.waba_id <> OLD.waba_id OR NEW.phone_number_id <> OLD.phone_number_id
        OR NEW.display_number <> OLD.display_number;
    IF NEW.config_version NOT IN (OLD.config_version, OLD.config_version + 1)
        OR (config_changed AND NEW.config_version <> OLD.config_version + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_connector_config_version',
            MESSAGE = 'the configuration version moves by one with a configuration change';
    END IF;
    IF NEW.credential_version NOT IN (OLD.credential_version, OLD.credential_version + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_connector_credential_version',
            MESSAGE = 'the credential version moves by one';
    END IF;
    IF NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_connector_updated',
            MESSAGE = 'updated_at never moves back';
    END IF;
    IF NEW.state <> OLD.state AND NOT (
        (OLD.state = 'DRAFT' AND NEW.state IN ('ACTIVE', 'DISABLED'))
        OR (OLD.state = 'ACTIVE' AND NEW.state IN ('DISABLED', 'SUSPENDED'))
        OR (OLD.state = 'DISABLED' AND NEW.state = 'ACTIVE')
        OR (OLD.state = 'SUSPENDED' AND NEW.state IN ('ACTIVE', 'DISABLED'))) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_connector_transition',
            MESSAGE = 'illegal connector state transition';
    END IF;
    IF NEW.state = 'ACTIVE' AND OLD.state <> 'ACTIVE' AND NEW.consecutive_failures <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_connector_reenable',
            MESSAGE = 'enabling resets the failure counter';
    END IF;
    IF NEW.consecutive_failures NOT IN (0, OLD.consecutive_failures, OLD.consecutive_failures + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_connector_counter',
            MESSAGE = 'the failure counter resets or moves by one';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_messaging_connector_guard BEFORE INSERT OR UPDATE ON integrations.messaging_connector
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_connector_guard();
CREATE TRIGGER integrations_messaging_connector_no_delete BEFORE DELETE ON integrations.messaging_connector
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_append_only();
CREATE TRIGGER integrations_messaging_connector_no_truncate BEFORE TRUNCATE ON integrations.messaging_connector
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.messaging_append_only();

-- ---------------------------------------------------------------------------------------------
-- Templates (admin-configured Meta template names; the notification wording lives at Meta)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.messaging_template (
    tenant_id         uuid        NOT NULL,
    connector_id      uuid        NOT NULL,
    notification_type text        NOT NULL,
    language          text        NOT NULL,
    template_name     text        NOT NULL,
    body_parameters   smallint    NOT NULL DEFAULT 0,
    verified_at       timestamptz,
    CONSTRAINT integrations_messaging_template_pkey PRIMARY KEY (tenant_id, connector_id, notification_type, language),
    CONSTRAINT integrations_messaging_template_connector
        FOREIGN KEY (tenant_id, connector_id) REFERENCES integrations.messaging_connector (tenant_id, id),
    CONSTRAINT integrations_messaging_template_type
        CHECK (notification_type IN ('LEAVE_DECISION', 'TIMESHEET_APPROVED', 'ATTENDANCE_CORRECTED',
            'PAYROLL_INPUT_LOCKED', 'PAYROLL_EXPORT_READY', 'CONNECTOR_TEST')),
    CONSTRAINT integrations_messaging_template_language CHECK (language IN ('en', 'fr')),
    CONSTRAINT integrations_messaging_template_name CHECK (template_name ~ '^[a-z][a-z0-9_]{0,511}$'),
    CONSTRAINT integrations_messaging_template_body CHECK (body_parameters IN (0, 1))
);
COMMENT ON TABLE integrations.messaging_template IS
    'Meta template names per notification type and language (configuration, not message content)';

-- ---------------------------------------------------------------------------------------------
-- Credential versions (the token is stored only as ciphertext; rotation erases the previous one)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.messaging_credential (
    tenant_id    uuid        NOT NULL,
    connector_id uuid        NOT NULL,
    version      integer     NOT NULL,
    ciphertext   bytea,
    created_at   timestamptz NOT NULL,
    erased_at    timestamptz,
    CONSTRAINT integrations_messaging_credential_pkey PRIMARY KEY (tenant_id, connector_id, version),
    CONSTRAINT integrations_messaging_credential_connector
        FOREIGN KEY (tenant_id, connector_id) REFERENCES integrations.messaging_connector (tenant_id, id),
    CONSTRAINT integrations_messaging_credential_version CHECK (version >= 1),
    CONSTRAINT integrations_messaging_credential_cipher
        CHECK (ciphertext IS NULL OR octet_length(ciphertext) BETWEEN 40 AND 2048),
    CONSTRAINT integrations_messaging_credential_erased CHECK ((ciphertext IS NULL) = (erased_at IS NOT NULL))
);
COMMENT ON TABLE integrations.messaging_credential IS
    'Encrypted WhatsApp system-user token versions (AES-256-GCM); no plaintext column exists';
CREATE UNIQUE INDEX integrations_messaging_credential_current
    ON integrations.messaging_credential (tenant_id, connector_id) WHERE ciphertext IS NOT NULL;

CREATE FUNCTION integrations.messaging_credential_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.erased_at IS NOT NULL OR NEW.ciphertext IS NULL
            OR NEW.version <> coalesce((SELECT max(version) FROM integrations.messaging_credential
                WHERE tenant_id = NEW.tenant_id AND connector_id = NEW.connector_id), 0) + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_credential_new',
                MESSAGE = 'a credential version is new, current and numbered without gaps';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.connector_id <> OLD.connector_id OR NEW.version <> OLD.version
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_credential_identity',
            MESSAGE = 'credential version identity is immutable';
    END IF;
    IF NEW.ciphertext IS DISTINCT FROM OLD.ciphertext
        AND NOT (OLD.ciphertext IS NOT NULL AND NEW.ciphertext IS NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_credential_erase_only',
            MESSAGE = 'ciphertext can only be erased';
    END IF;
    IF NEW.erased_at IS DISTINCT FROM OLD.erased_at AND OLD.erased_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_credential_erase_once',
            MESSAGE = 'a credential version is erased once';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_messaging_credential_guard BEFORE INSERT OR UPDATE ON integrations.messaging_credential
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_credential_guard();
CREATE TRIGGER integrations_messaging_credential_no_delete BEFORE DELETE ON integrations.messaging_credential
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_append_only();
CREATE TRIGGER integrations_messaging_credential_no_truncate BEFORE TRUNCATE ON integrations.messaging_credential
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.messaging_append_only();

-- At commit: the connector's credential_version is its single stored (current) credential.
CREATE FUNCTION integrations.messaging_credential_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    t uuid;
    c uuid;
BEGIN
    t := NEW.tenant_id;
    IF TG_TABLE_NAME = 'messaging_connector' THEN
        c := NEW.id;
    ELSE
        c := NEW.connector_id;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM integrations.messaging_connector h
        JOIN integrations.messaging_credential s
          ON s.tenant_id = h.tenant_id AND s.connector_id = h.id AND s.version = h.credential_version
        WHERE h.tenant_id = t AND h.id = c AND s.ciphertext IS NOT NULL)
        OR (SELECT count(*) FROM integrations.messaging_credential
            WHERE tenant_id = t AND connector_id = c AND ciphertext IS NOT NULL) <> 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_credential_exact',
            MESSAGE = 'a connector has exactly one stored credential matching its credential version';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER integrations_messaging_credential_exact_connector
    AFTER INSERT OR UPDATE ON integrations.messaging_connector
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.messaging_credential_exact();
CREATE CONSTRAINT TRIGGER integrations_messaging_credential_exact_credential
    AFTER INSERT OR UPDATE ON integrations.messaging_credential
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.messaging_credential_exact();

-- ---------------------------------------------------------------------------------------------
-- Recipients
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.messaging_recipient (
    tenant_id             uuid        NOT NULL,
    id                    uuid        NOT NULL,
    connector_id          uuid        NOT NULL,
    label                 text        NOT NULL,
    number_ciphertext     bytea       NOT NULL,
    number_digest         bytea       NOT NULL,
    number_masked         text        NOT NULL,
    language              text        NOT NULL,
    notification_types    text[]      NOT NULL,
    status                text        NOT NULL,
    consent_source        text        NOT NULL,
    consent_confirmed_at  timestamptz NOT NULL,
    consent_attested_by   text        NOT NULL,
    version               integer     NOT NULL,
    created_at            timestamptz NOT NULL,
    created_by            text        NOT NULL,
    updated_at            timestamptz NOT NULL,
    updated_by            text        NOT NULL,
    CONSTRAINT integrations_messaging_recipient_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT integrations_messaging_recipient_connector
        FOREIGN KEY (tenant_id, connector_id) REFERENCES integrations.messaging_connector (tenant_id, id),
    CONSTRAINT integrations_messaging_recipient_number_unique UNIQUE (tenant_id, connector_id, number_digest),
    CONSTRAINT integrations_messaging_recipient_label
        CHECK (char_length(label) BETWEEN 1 AND 80 AND label = btrim(label)
            AND label !~ '[<>[:cntrl:]]' AND label !~* 'https?://'),
    CONSTRAINT integrations_messaging_recipient_cipher CHECK (octet_length(number_ciphertext) BETWEEN 40 AND 256),
    CONSTRAINT integrations_messaging_recipient_digest CHECK (octet_length(number_digest) = 32),
    CONSTRAINT integrations_messaging_recipient_masked CHECK (number_masked ~ '^\+[0-9]{1,3}[ *]*[0-9]{2}$'),
    CONSTRAINT integrations_messaging_recipient_language CHECK (language IN ('en', 'fr')),
    CONSTRAINT integrations_messaging_recipient_types
        CHECK (cardinality(notification_types) BETWEEN 1 AND 5
            AND notification_types <@ ARRAY['LEAVE_DECISION', 'TIMESHEET_APPROVED', 'ATTENDANCE_CORRECTED',
                'PAYROLL_INPUT_LOCKED', 'PAYROLL_EXPORT_READY']::text[]),
    CONSTRAINT integrations_messaging_recipient_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT integrations_messaging_recipient_consent
        CHECK (consent_source IN ('WRITTEN', 'WEB_FORM', 'VERBAL_RECORDED_OUTSIDE_DIVALHR', 'OTHER_DOCUMENTED')),
    CONSTRAINT integrations_messaging_recipient_version CHECK (version >= 1),
    CONSTRAINT integrations_messaging_recipient_actor
        CHECK (char_length(created_by) BETWEEN 1 AND 255 AND char_length(updated_by) BETWEEN 1 AND 255
            AND char_length(consent_attested_by) BETWEEN 1 AND 255),
    CONSTRAINT integrations_messaging_recipient_updated CHECK (updated_at >= created_at)
);
COMMENT ON TABLE integrations.messaging_recipient IS
    'Opted-in operational WhatsApp recipients; number stored as ciphertext plus a keyed digest; never deleted';

CREATE FUNCTION integrations.messaging_recipient_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    config_changed boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('integrations.messaging_recipient:' || NEW.tenant_id::text, 0));
        IF (SELECT count(*) FROM integrations.messaging_recipient WHERE tenant_id = NEW.tenant_id) >= 25 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_recipient_limit',
                MESSAGE = 'recipient limit reached';
        END IF;
        IF NEW.status <> 'ACTIVE' OR NEW.version <> 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_recipient_initial',
                MESSAGE = 'a recipient starts active at version 1';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.id <> OLD.id OR NEW.connector_id <> OLD.connector_id
        OR NEW.created_at <> OLD.created_at OR NEW.created_by <> OLD.created_by
        OR NEW.number_ciphertext <> OLD.number_ciphertext OR NEW.number_digest <> OLD.number_digest
        OR NEW.number_masked <> OLD.number_masked THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_recipient_identity',
            MESSAGE = 'recipient identity and number are immutable';
    END IF;
    config_changed := NEW.label <> OLD.label OR NEW.language <> OLD.language
        OR NEW.notification_types <> OLD.notification_types OR NEW.consent_source <> OLD.consent_source
        OR NEW.consent_confirmed_at <> OLD.consent_confirmed_at OR NEW.consent_attested_by <> OLD.consent_attested_by
        OR NEW.status <> OLD.status;
    IF NEW.version NOT IN (OLD.version, OLD.version + 1) OR (config_changed AND NEW.version <> OLD.version + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_recipient_version',
            MESSAGE = 'the recipient version moves by one with a change';
    END IF;
    IF NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_recipient_updated',
            MESSAGE = 'updated_at never moves back';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_messaging_recipient_guard BEFORE INSERT OR UPDATE ON integrations.messaging_recipient
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_recipient_guard();
CREATE TRIGGER integrations_messaging_recipient_no_delete BEFORE DELETE ON integrations.messaging_recipient
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_append_only();
CREATE TRIGGER integrations_messaging_recipient_no_truncate BEFORE TRUNCATE ON integrations.messaging_recipient
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.messaging_append_only();

-- ---------------------------------------------------------------------------------------------
-- Deliveries
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.messaging_delivery (
    tenant_id           uuid        NOT NULL,
    id                  uuid        NOT NULL,
    connector_id        uuid        NOT NULL,
    recipient_id        uuid        NOT NULL,
    event_id            uuid        NOT NULL,
    notification_type   text        NOT NULL,
    kind                text        NOT NULL,
    resource_id         uuid,
    language            text        NOT NULL,
    state               text        NOT NULL,
    attempt_count       integer     NOT NULL,
    created_at          timestamptz NOT NULL,
    next_attempt_at     timestamptz,
    last_attempt_at     timestamptz,
    last_http_status    integer,
    last_failure_class  text,
    provider_message_id text,
    accepted_at         timestamptz,
    delivered_at        timestamptz,
    read_at             timestamptz,
    completed_at        timestamptz,
    claim_token         uuid,
    claimed_until       timestamptz,
    CONSTRAINT integrations_messaging_delivery_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT integrations_messaging_delivery_connector
        FOREIGN KEY (tenant_id, connector_id) REFERENCES integrations.messaging_connector (tenant_id, id),
    CONSTRAINT integrations_messaging_delivery_recipient
        FOREIGN KEY (tenant_id, recipient_id) REFERENCES integrations.messaging_recipient (tenant_id, id),
    CONSTRAINT integrations_messaging_delivery_unique_event UNIQUE (connector_id, recipient_id, event_id),
    CONSTRAINT integrations_messaging_delivery_type
        CHECK (notification_type IN ('LEAVE_DECISION', 'TIMESHEET_APPROVED', 'ATTENDANCE_CORRECTED',
            'PAYROLL_INPUT_LOCKED', 'PAYROLL_EXPORT_READY', 'CONNECTOR_TEST')),
    CONSTRAINT integrations_messaging_delivery_kind
        CHECK (kind IN ('EVENT', 'TEST') AND (kind = 'TEST') = (notification_type = 'CONNECTOR_TEST')),
    CONSTRAINT integrations_messaging_delivery_language CHECK (language IN ('en', 'fr')),
    CONSTRAINT integrations_messaging_delivery_state
        CHECK (state IN ('PENDING', 'RETRYING', 'ACCEPTED', 'DELIVERED', 'READ', 'FAILED', 'CANCELLED',
            'STATUS_UNKNOWN')),
    CONSTRAINT integrations_messaging_delivery_failure_class
        CHECK (last_failure_class IS NULL OR last_failure_class IN ('DNS_FAILED', 'CONNECT_TIMEOUT',
            'REQUEST_TIMEOUT', 'TLS_FAILED', 'CONNECTION_FAILED', 'RATE_LIMITED', 'HTTP_RESPONSE',
            'CREDENTIAL_INVALID', 'TEMPLATE_INVALID', 'RECIPIENT_REJECTED', 'PROVIDER_FAILED',
            'INTERNAL_FAILURE', 'CONNECTOR_INACTIVE')),
    CONSTRAINT integrations_messaging_delivery_status CHECK (last_http_status IS NULL OR last_http_status BETWEEN 100 AND 599),
    CONSTRAINT integrations_messaging_delivery_attempts CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT integrations_messaging_delivery_provider_id
        CHECK (provider_message_id IS NULL OR provider_message_id ~ '^[A-Za-z0-9=_.:+/-]{8,256}$'),
    CONSTRAINT integrations_messaging_delivery_claim CHECK ((claim_token IS NULL) = (claimed_until IS NULL)),
    CONSTRAINT integrations_messaging_delivery_shape CHECK (
        (state = 'PENDING' AND attempt_count = 0 AND next_attempt_at IS NOT NULL AND completed_at IS NULL
            AND provider_message_id IS NULL AND last_failure_class IS NULL)
        OR (state = 'RETRYING' AND attempt_count BETWEEN 1 AND 4 AND next_attempt_at IS NOT NULL
            AND completed_at IS NULL AND provider_message_id IS NULL AND last_failure_class IS NOT NULL)
        OR (state IN ('ACCEPTED', 'DELIVERED') AND provider_message_id IS NOT NULL AND accepted_at IS NOT NULL
            AND next_attempt_at IS NULL AND completed_at IS NULL AND claim_token IS NULL
            AND attempt_count BETWEEN 1 AND 5)
        OR (state = 'READ' AND provider_message_id IS NOT NULL AND accepted_at IS NOT NULL
            AND read_at IS NOT NULL AND delivered_at IS NOT NULL AND next_attempt_at IS NULL
            AND completed_at IS NOT NULL AND claim_token IS NULL)
        OR (state = 'FAILED' AND next_attempt_at IS NULL AND completed_at IS NOT NULL
            AND last_failure_class IS NOT NULL AND claim_token IS NULL)
        OR (state = 'STATUS_UNKNOWN' AND attempt_count = 5 AND next_attempt_at IS NULL
            AND completed_at IS NOT NULL AND provider_message_id IS NULL AND claim_token IS NULL)
        OR (state = 'CANCELLED' AND next_attempt_at IS NULL AND completed_at IS NOT NULL
            AND claim_token IS NULL AND provider_message_id IS NULL))
);
COMMENT ON TABLE integrations.messaging_delivery IS
    'One WhatsApp notification delivery per connector, recipient and event; no message content is stored';

CREATE UNIQUE INDEX integrations_messaging_delivery_provider_message
    ON integrations.messaging_delivery (provider_message_id) WHERE provider_message_id IS NOT NULL;
CREATE INDEX integrations_messaging_delivery_history
    ON integrations.messaging_delivery (tenant_id, created_at DESC, id DESC);
CREATE INDEX integrations_messaging_delivery_due ON integrations.messaging_delivery (next_attempt_at)
    WHERE state IN ('PENDING', 'RETRYING');

CREATE FUNCTION integrations.messaging_delivery_rank(s text) RETURNS integer LANGUAGE sql IMMUTABLE AS
$fn$
    SELECT CASE s WHEN 'ACCEPTED' THEN 1 WHEN 'DELIVERED' THEN 2 WHEN 'READ' THEN 3 ELSE 0 END
$fn$;

CREATE FUNCTION integrations.messaging_delivery_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PENDING' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_delivery_initial',
                MESSAGE = 'a delivery starts pending';
        END IF;
        IF NEW.kind = 'TEST' THEN
            PERFORM pg_advisory_xact_lock(hashtextextended('integrations.messaging_test:' || NEW.tenant_id::text, 0));
            IF (SELECT count(*) FROM integrations.messaging_delivery d
                WHERE d.recipient_id = NEW.recipient_id AND d.kind = 'TEST'
                  AND d.created_at > NEW.created_at - interval '1 hour' AND d.created_at <= NEW.created_at) >= 3
               OR (SELECT count(*) FROM integrations.messaging_delivery d
                WHERE d.tenant_id = NEW.tenant_id AND d.kind = 'TEST'
                  AND d.created_at > NEW.created_at - interval '1 hour' AND d.created_at <= NEW.created_at) >= 10 THEN
                RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_delivery_test_quota',
                    MESSAGE = 'test delivery quota reached';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state IN ('READ', 'FAILED', 'CANCELLED', 'STATUS_UNKNOWN') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_delivery_final',
            MESSAGE = 'a finished delivery never changes';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.id <> OLD.id OR NEW.connector_id <> OLD.connector_id
        OR NEW.recipient_id <> OLD.recipient_id OR NEW.event_id <> OLD.event_id
        OR NEW.notification_type <> OLD.notification_type OR NEW.kind <> OLD.kind
        OR NEW.resource_id IS DISTINCT FROM OLD.resource_id OR NEW.language <> OLD.language
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_delivery_immutable',
            MESSAGE = 'delivery identity is immutable';
    END IF;
    IF NEW.attempt_count NOT IN (OLD.attempt_count, OLD.attempt_count + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_delivery_attempt_number',
            MESSAGE = 'attempts are numbered without gaps';
    END IF;
    -- Once a provider message id exists it never changes and the message is never sent again.
    IF OLD.provider_message_id IS NOT NULL AND NEW.provider_message_id IS DISTINCT FROM OLD.provider_message_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_delivery_provider_id_once',
            MESSAGE = 'the provider message id never changes';
    END IF;
    IF OLD.provider_message_id IS NOT NULL AND NEW.attempt_count <> OLD.attempt_count THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_delivery_no_resend',
            MESSAGE = 'an accepted message is never sent again';
    END IF;
    -- Callback statuses are monotonic: a lower status never moves the state backward.
    IF OLD.state IN ('ACCEPTED', 'DELIVERED') AND NOT (
        (OLD.state = 'ACCEPTED' AND NEW.state IN ('ACCEPTED', 'DELIVERED', 'READ', 'FAILED'))
        OR (OLD.state = 'DELIVERED' AND NEW.state IN ('DELIVERED', 'READ'))) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_delivery_monotonic',
            MESSAGE = 'delivery status is forward-only';
    END IF;
    IF OLD.state IN ('PENDING', 'RETRYING') AND NEW.state IN ('DELIVERED', 'READ') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_delivery_monotonic',
            MESSAGE = 'a message must be accepted before it is delivered';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_messaging_delivery_guard BEFORE INSERT OR UPDATE ON integrations.messaging_delivery
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_delivery_guard();
CREATE TRIGGER integrations_messaging_delivery_no_delete BEFORE DELETE ON integrations.messaging_delivery
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_append_only();
CREATE TRIGGER integrations_messaging_delivery_no_truncate BEFORE TRUNCATE ON integrations.messaging_delivery
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.messaging_append_only();

CREATE TABLE integrations.messaging_delivery_attempt (
    tenant_id     uuid        NOT NULL,
    delivery_id   uuid        NOT NULL,
    number        integer     NOT NULL,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz NOT NULL,
    http_status   integer,
    failure_class text,
    latency_ms    integer     NOT NULL,
    CONSTRAINT integrations_messaging_attempt_pkey PRIMARY KEY (tenant_id, delivery_id, number),
    CONSTRAINT integrations_messaging_attempt_delivery
        FOREIGN KEY (tenant_id, delivery_id) REFERENCES integrations.messaging_delivery (tenant_id, id),
    CONSTRAINT integrations_messaging_attempt_number CHECK (number BETWEEN 1 AND 5),
    CONSTRAINT integrations_messaging_attempt_times CHECK (finished_at >= started_at AND latency_ms >= 0),
    CONSTRAINT integrations_messaging_attempt_status CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    CONSTRAINT integrations_messaging_attempt_class
        CHECK (failure_class IS NULL OR failure_class IN ('DNS_FAILED', 'CONNECT_TIMEOUT', 'REQUEST_TIMEOUT',
            'TLS_FAILED', 'CONNECTION_FAILED', 'RATE_LIMITED', 'HTTP_RESPONSE', 'CREDENTIAL_INVALID',
            'TEMPLATE_INVALID', 'RECIPIENT_REJECTED', 'PROVIDER_FAILED', 'INTERNAL_FAILURE',
            'CONNECTOR_INACTIVE'))
);
COMMENT ON TABLE integrations.messaging_delivery_attempt IS
    'Append-only messaging attempts: status class and closed failure class only, never a body or header';

CREATE FUNCTION integrations.messaging_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    PERFORM 1 FROM integrations.messaging_delivery
        WHERE tenant_id = NEW.tenant_id AND id = NEW.delivery_id FOR UPDATE;
    IF NEW.number <> coalesce((SELECT max(number) FROM integrations.messaging_delivery_attempt
        WHERE tenant_id = NEW.tenant_id AND delivery_id = NEW.delivery_id), 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_attempt_sequence',
            MESSAGE = 'attempts are numbered without gaps';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_messaging_attempt_guard BEFORE INSERT ON integrations.messaging_delivery_attempt
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_attempt_guard();
CREATE TRIGGER integrations_messaging_attempt_immutable BEFORE UPDATE OR DELETE ON integrations.messaging_delivery_attempt
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_append_only();
CREATE TRIGGER integrations_messaging_attempt_no_truncate BEFORE TRUNCATE ON integrations.messaging_delivery_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.messaging_append_only();

CREATE FUNCTION integrations.messaging_delivery_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    t uuid;
    d uuid;
BEGIN
    t := NEW.tenant_id;
    IF TG_TABLE_NAME = 'messaging_delivery' THEN
        d := NEW.id;
    ELSE
        d := NEW.delivery_id;
    END IF;
    IF (SELECT attempt_count FROM integrations.messaging_delivery WHERE tenant_id = t AND id = d)
        <> (SELECT count(*) FROM integrations.messaging_delivery_attempt WHERE tenant_id = t AND delivery_id = d) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_messaging_delivery_exact',
            MESSAGE = 'a delivery counts exactly its recorded attempts';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER integrations_messaging_delivery_exact_delivery
    AFTER INSERT OR UPDATE ON integrations.messaging_delivery
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.messaging_delivery_exact();
CREATE CONSTRAINT TRIGGER integrations_messaging_delivery_exact_attempt
    AFTER INSERT ON integrations.messaging_delivery_attempt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.messaging_delivery_exact();

-- ---------------------------------------------------------------------------------------------
-- Provider status callbacks (append-only; only allow-listed fields; one row per delivery and status)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.messaging_status_callback (
    tenant_id   uuid        NOT NULL,
    delivery_id uuid        NOT NULL,
    status      text        NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    error_class text,
    CONSTRAINT integrations_messaging_callback_pkey PRIMARY KEY (tenant_id, delivery_id, status),
    CONSTRAINT integrations_messaging_callback_delivery
        FOREIGN KEY (tenant_id, delivery_id) REFERENCES integrations.messaging_delivery (tenant_id, id),
    CONSTRAINT integrations_messaging_callback_status CHECK (status IN ('SENT', 'DELIVERED', 'READ', 'FAILED')),
    CONSTRAINT integrations_messaging_callback_error
        CHECK (error_class IS NULL OR error_class IN ('RECIPIENT_REJECTED', 'TEMPLATE_INVALID',
            'CREDENTIAL_INVALID', 'PROVIDER_FAILED')),
    CONSTRAINT integrations_messaging_callback_error_only_failed CHECK ((status = 'FAILED') = (error_class IS NOT NULL))
);
COMMENT ON TABLE integrations.messaging_status_callback IS
    'Append-only verified provider status callbacks: status, times and a closed error class only';
CREATE TRIGGER integrations_messaging_callback_immutable BEFORE UPDATE OR DELETE ON integrations.messaging_status_callback
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_append_only();
CREATE TRIGGER integrations_messaging_callback_no_truncate BEFORE TRUNCATE ON integrations.messaging_status_callback
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.messaging_append_only();

-- ---------------------------------------------------------------------------------------------
-- Fan-out ledger
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.messaging_fanout_event (
    event_id  uuid        NOT NULL,
    tenant_id uuid        NOT NULL,
    fanned_at timestamptz NOT NULL,
    CONSTRAINT integrations_messaging_fanout_event_pkey PRIMARY KEY (event_id)
);
COMMENT ON TABLE integrations.messaging_fanout_event IS 'Business events already turned into messaging deliveries';
CREATE TRIGGER integrations_messaging_fanout_event_immutable BEFORE UPDATE OR DELETE ON integrations.messaging_fanout_event
    FOR EACH ROW EXECUTE FUNCTION integrations.messaging_append_only();
CREATE TRIGGER integrations_messaging_fanout_event_no_truncate BEFORE TRUNCATE ON integrations.messaging_fanout_event
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.messaging_append_only();

CREATE TABLE integrations.messaging_fanout_state (
    id                 smallint    NOT NULL,
    watermark          timestamptz NOT NULL,
    watermark_event_id uuid        NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT integrations_messaging_fanout_state_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_messaging_fanout_state_single CHECK (id = 1)
);
COMMENT ON TABLE integrations.messaging_fanout_state IS
    'Read watermark of the messaging fan-out; starts at the migration time so history is never replayed';
INSERT INTO integrations.messaging_fanout_state (id, watermark, watermark_event_id, updated_at)
    VALUES (1, now(), '00000000-0000-0000-0000-000000000000', now());
