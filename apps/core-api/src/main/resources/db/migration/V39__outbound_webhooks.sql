-- MVP-110 (Issue #161): tenant administrators manage signed, at-least-once outbound webhooks for a
-- closed catalogue of public events (ADR 0028). The first objects of the integrations schema.
-- Additive to V38.
--
-- Ownership: the integrations module; only the bounded com.divalhr.core.integrations package reads
-- or writes these objects. Business events reach it only through the platform outbox reader port;
-- no integrations object references another module's table. The key to tenant.organization is the
-- documented tenant-binding exception.
--
-- Classification: Restricted (customer endpoint addresses and encrypted signing secrets). The
-- signing secret is stored only as AES-256-GCM ciphertext (the key lives outside the database); no
-- response body, response header, resolved address or signature is stored anywhere.
--
-- Shape:
--   * integrations.webhook: the endpoint configuration and a small projection (status, versions,
--     bounded failure counters). Updated only through the guard trigger: identity never changes,
--     the configuration version moves by one, statuses follow ACTIVE <-> DISABLED/SUSPENDED, at most
--     20 webhooks per tenant, never deleted.
--   * integrations.webhook_secret: one row per secret version (ciphertext only). Exactly one current
--     version (expires_at NULL, equal to webhook.secret_version) and at most one previous version
--     with an expiry; the ciphertext of an expired version may be erased once and never restored.
--   * integrations.delivery: one delivery per webhook and event (UNIQUE (webhook_id, event_id)) with
--     the immutable canonical payload bytes, a forward-only state machine, the claim lease and the
--     closed failure class. Test deliveries (webhook.test.v1) are limited to five per webhook per
--     hour inside the database.
--   * integrations.delivery_attempt: append-only attempts, numbered 1 to 6 without gaps; exactly
--     the attempts counted by the delivery.
--   * integrations.fanout_event / fanout_state: which business events were already turned into
--     deliveries, and the read watermark, so several instances never double-dispatch.
--
-- Rollback: db/rollback/V39__rollback.sql (manual, never run by Flyway) restores V38 exactly only
-- while no webhook exists; there is no override.

-- ---------------------------------------------------------------------------------------------
-- Webhooks
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.webhook (
    tenant_id            uuid        NOT NULL,
    id                   uuid        NOT NULL,
    name                 text        NOT NULL,
    url                  text        NOT NULL,
    event_types          text[]      NOT NULL,
    status               text        NOT NULL,
    config_version       integer     NOT NULL,
    secret_version       integer     NOT NULL,
    consecutive_failures integer     NOT NULL,
    last_success_at      timestamptz,
    last_failure_at      timestamptz,
    created_at           timestamptz NOT NULL,
    created_by           text        NOT NULL,
    updated_at           timestamptz NOT NULL,
    updated_by           text        NOT NULL,
    CONSTRAINT integrations_webhook_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT integrations_webhook_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT integrations_webhook_name
        CHECK (char_length(name) BETWEEN 1 AND 100 AND name = btrim(name)
            AND name !~ '[<>[:cntrl:]]' AND name !~* 'https?://'),
    CONSTRAINT integrations_webhook_url
        CHECK (char_length(url) <= 2048 AND url ~ '^https://[a-z0-9.-]+(:[0-9]{1,5})?/[\x21-\x7e]*$'),
    CONSTRAINT integrations_webhook_events
        CHECK (cardinality(event_types) BETWEEN 1 AND 6
            AND event_types <@ ARRAY['leave.request.approved.v1', 'leave.request.rejected.v1',
                'timesheet.approved.v1', 'attendance.corrected.v1', 'payroll.input.locked.v1',
                'payroll.input-export.created.v1']::text[]),
    CONSTRAINT integrations_webhook_status CHECK (status IN ('ACTIVE', 'DISABLED', 'SUSPENDED')),
    CONSTRAINT integrations_webhook_versions CHECK (config_version >= 1 AND secret_version >= 1),
    CONSTRAINT integrations_webhook_failures CHECK (consecutive_failures BETWEEN 0 AND 10),
    CONSTRAINT integrations_webhook_suspended
        CHECK (status <> 'SUSPENDED' OR consecutive_failures >= 10),
    CONSTRAINT integrations_webhook_actor
        CHECK (char_length(created_by) BETWEEN 1 AND 255 AND char_length(updated_by) BETWEEN 1 AND 255),
    CONSTRAINT integrations_webhook_updated CHECK (updated_at >= created_at)
);
COMMENT ON TABLE integrations.webhook IS
    'Outbound webhook endpoint configuration and bounded projection (MVP-110); never deleted';

CREATE FUNCTION integrations.webhook_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    config_changed boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('integrations.webhook:' || NEW.tenant_id::text, 0));
        IF (SELECT count(*) FROM integrations.webhook WHERE tenant_id = NEW.tenant_id) >= 20 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_limit',
                MESSAGE = 'webhook limit reached';
        END IF;
        IF NEW.status <> 'ACTIVE' OR NEW.config_version <> 1 OR NEW.secret_version <> 1
            OR NEW.consecutive_failures <> 0 OR NEW.last_success_at IS NOT NULL
            OR NEW.last_failure_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_initial',
                MESSAGE = 'a webhook starts active at version 1';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.id <> OLD.id OR NEW.created_at <> OLD.created_at
        OR NEW.created_by <> OLD.created_by THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_identity',
            MESSAGE = 'webhook identity is immutable';
    END IF;
    config_changed := NEW.name <> OLD.name OR NEW.url <> OLD.url OR NEW.event_types <> OLD.event_types;
    IF NEW.config_version NOT IN (OLD.config_version, OLD.config_version + 1)
        OR (config_changed AND NEW.config_version <> OLD.config_version + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_config_version',
            MESSAGE = 'the configuration version moves by one with a configuration change';
    END IF;
    IF NEW.secret_version NOT IN (OLD.secret_version, OLD.secret_version + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_secret_version',
            MESSAGE = 'the secret version moves by one';
    END IF;
    IF NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_updated',
            MESSAGE = 'updated_at never moves back';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'ACTIVE' AND NEW.status IN ('DISABLED', 'SUSPENDED'))
        OR (OLD.status = 'DISABLED' AND NEW.status = 'ACTIVE')
        OR (OLD.status = 'SUSPENDED' AND NEW.status IN ('ACTIVE', 'DISABLED'))) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_transition',
            MESSAGE = 'illegal webhook status transition';
    END IF;
    IF NEW.status = 'ACTIVE' AND OLD.status <> 'ACTIVE' AND NEW.consecutive_failures <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_reenable',
            MESSAGE = 're-enabling resets the failure counter';
    END IF;
    IF NEW.consecutive_failures NOT IN (0, OLD.consecutive_failures, OLD.consecutive_failures + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_counter',
            MESSAGE = 'the failure counter resets or moves by one';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_webhook_guard BEFORE INSERT OR UPDATE ON integrations.webhook
    FOR EACH ROW EXECUTE FUNCTION integrations.webhook_guard();

CREATE FUNCTION integrations.append_only() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_append_only',
        MESSAGE = 'integrations evidence is never deleted, truncated or (where append-only) updated';
END
$fn$;

CREATE TRIGGER integrations_webhook_no_delete BEFORE DELETE ON integrations.webhook
    FOR EACH ROW EXECUTE FUNCTION integrations.append_only();
CREATE TRIGGER integrations_webhook_no_truncate BEFORE TRUNCATE ON integrations.webhook
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.append_only();

-- ---------------------------------------------------------------------------------------------
-- Secret versions
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.webhook_secret (
    tenant_id   uuid        NOT NULL,
    webhook_id  uuid        NOT NULL,
    version     integer     NOT NULL,
    ciphertext  bytea,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz,
    erased_at   timestamptz,
    CONSTRAINT integrations_webhook_secret_pkey PRIMARY KEY (tenant_id, webhook_id, version),
    CONSTRAINT integrations_webhook_secret_webhook
        FOREIGN KEY (tenant_id, webhook_id) REFERENCES integrations.webhook (tenant_id, id),
    CONSTRAINT integrations_webhook_secret_version CHECK (version >= 1),
    CONSTRAINT integrations_webhook_secret_cipher
        CHECK (ciphertext IS NULL OR octet_length(ciphertext) BETWEEN 40 AND 256),
    CONSTRAINT integrations_webhook_secret_erased CHECK ((ciphertext IS NULL) = (erased_at IS NOT NULL)),
    CONSTRAINT integrations_webhook_secret_expiry CHECK (expires_at IS NULL OR expires_at >= created_at),
    CONSTRAINT integrations_webhook_secret_erase_after_expiry
        CHECK (erased_at IS NULL OR (expires_at IS NOT NULL AND erased_at >= expires_at))
);
COMMENT ON TABLE integrations.webhook_secret IS
    'Encrypted webhook signing secret versions (AES-256-GCM); no plaintext column exists';

-- One current version, and at most one previous version that still holds its ciphertext.
CREATE UNIQUE INDEX integrations_webhook_secret_current
    ON integrations.webhook_secret (tenant_id, webhook_id) WHERE expires_at IS NULL;
CREATE UNIQUE INDEX integrations_webhook_secret_previous
    ON integrations.webhook_secret (tenant_id, webhook_id)
    WHERE expires_at IS NOT NULL AND ciphertext IS NOT NULL;

CREATE FUNCTION integrations.webhook_secret_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.expires_at IS NOT NULL OR NEW.erased_at IS NOT NULL OR NEW.ciphertext IS NULL
            OR NEW.version <> coalesce((SELECT max(version) FROM integrations.webhook_secret
                WHERE tenant_id = NEW.tenant_id AND webhook_id = NEW.webhook_id), 0) + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_secret_new',
                MESSAGE = 'a secret version is new, current and numbered without gaps';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.webhook_id <> OLD.webhook_id OR NEW.version <> OLD.version
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_secret_identity',
            MESSAGE = 'secret version identity is immutable';
    END IF;
    IF NEW.expires_at IS DISTINCT FROM OLD.expires_at
        AND NOT (OLD.expires_at IS NULL AND NEW.expires_at IS NOT NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_secret_expiry_once',
            MESSAGE = 'a secret version expires once';
    END IF;
    IF NEW.ciphertext IS DISTINCT FROM OLD.ciphertext
        AND NOT (OLD.ciphertext IS NOT NULL AND NEW.ciphertext IS NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_secret_erase_only',
            MESSAGE = 'ciphertext can only be erased';
    END IF;
    IF NEW.erased_at IS DISTINCT FROM OLD.erased_at AND OLD.erased_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_secret_erase_once',
            MESSAGE = 'a secret version is erased once';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_webhook_secret_guard BEFORE INSERT OR UPDATE ON integrations.webhook_secret
    FOR EACH ROW EXECUTE FUNCTION integrations.webhook_secret_guard();
CREATE TRIGGER integrations_webhook_secret_no_delete BEFORE DELETE ON integrations.webhook_secret
    FOR EACH ROW EXECUTE FUNCTION integrations.append_only();
CREATE TRIGGER integrations_webhook_secret_no_truncate BEFORE TRUNCATE ON integrations.webhook_secret
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.append_only();

-- At commit: the webhook's secret_version is its single current secret row.
CREATE FUNCTION integrations.webhook_secret_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    t uuid;
    w uuid;
BEGIN
    IF TG_TABLE_NAME = 'webhook' THEN
        t := NEW.tenant_id; w := NEW.id;
    ELSE
        t := NEW.tenant_id; w := NEW.webhook_id;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM integrations.webhook h
        JOIN integrations.webhook_secret s
          ON s.tenant_id = h.tenant_id AND s.webhook_id = h.id AND s.version = h.secret_version
        WHERE h.tenant_id = t AND h.id = w AND s.expires_at IS NULL)
        OR (SELECT count(*) FROM integrations.webhook_secret
            WHERE tenant_id = t AND webhook_id = w AND expires_at IS NULL) <> 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_webhook_secret_exact',
            MESSAGE = 'a webhook has exactly one current secret matching its secret version';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER integrations_webhook_secret_exact_webhook
    AFTER INSERT OR UPDATE ON integrations.webhook
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.webhook_secret_exact();
CREATE CONSTRAINT TRIGGER integrations_webhook_secret_exact_secret
    AFTER INSERT OR UPDATE ON integrations.webhook_secret
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.webhook_secret_exact();

-- ---------------------------------------------------------------------------------------------
-- Deliveries and attempts
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.delivery (
    tenant_id          uuid        NOT NULL,
    id                 uuid        NOT NULL,
    webhook_id         uuid        NOT NULL,
    event_id           uuid        NOT NULL,
    event_type         text        NOT NULL,
    kind               text        NOT NULL,
    payload            bytea       NOT NULL,
    state              text        NOT NULL,
    attempt_count      integer     NOT NULL,
    created_at         timestamptz NOT NULL,
    next_attempt_at    timestamptz,
    last_attempt_at    timestamptz,
    last_http_status   integer,
    last_failure_class text,
    completed_at       timestamptz,
    claim_token        uuid,
    claimed_until      timestamptz,
    CONSTRAINT integrations_delivery_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT integrations_delivery_webhook
        FOREIGN KEY (tenant_id, webhook_id) REFERENCES integrations.webhook (tenant_id, id),
    CONSTRAINT integrations_delivery_unique_event UNIQUE (webhook_id, event_id),
    CONSTRAINT integrations_delivery_event_type
        CHECK (event_type IN ('leave.request.approved.v1', 'leave.request.rejected.v1',
            'timesheet.approved.v1', 'attendance.corrected.v1', 'payroll.input.locked.v1',
            'payroll.input-export.created.v1', 'webhook.test.v1')),
    CONSTRAINT integrations_delivery_kind
        CHECK ((kind = 'TEST') = (event_type = 'webhook.test.v1') AND kind IN ('EVENT', 'TEST')),
    CONSTRAINT integrations_delivery_payload CHECK (octet_length(payload) BETWEEN 2 AND 4096),
    CONSTRAINT integrations_delivery_state
        CHECK (state IN ('PENDING', 'RETRYING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT integrations_delivery_failure_class
        CHECK (last_failure_class IS NULL OR last_failure_class IN ('DNS_BLOCKED', 'DNS_FAILED',
            'CONNECT_TIMEOUT', 'REQUEST_TIMEOUT', 'TLS_FAILED', 'CONNECTION_FAILED',
            'HTTP_RESPONSE', 'INTERNAL_FAILURE')),
    CONSTRAINT integrations_delivery_status CHECK (last_http_status IS NULL OR last_http_status BETWEEN 100 AND 599),
    CONSTRAINT integrations_delivery_attempts CHECK (attempt_count BETWEEN 0 AND 6),
    CONSTRAINT integrations_delivery_claim CHECK ((claim_token IS NULL) = (claimed_until IS NULL)),
    CONSTRAINT integrations_delivery_shape CHECK (
        (state = 'PENDING' AND attempt_count = 0 AND next_attempt_at IS NOT NULL AND completed_at IS NULL
            AND last_attempt_at IS NULL AND last_http_status IS NULL AND last_failure_class IS NULL)
        OR (state = 'RETRYING' AND attempt_count BETWEEN 1 AND 5 AND next_attempt_at IS NOT NULL
            AND completed_at IS NULL AND last_attempt_at IS NOT NULL AND last_failure_class IS NOT NULL)
        OR (state = 'SUCCEEDED' AND attempt_count BETWEEN 1 AND 6 AND next_attempt_at IS NULL
            AND completed_at IS NOT NULL AND last_attempt_at IS NOT NULL AND last_failure_class IS NULL
            AND last_http_status BETWEEN 200 AND 299 AND claim_token IS NULL)
        OR (state = 'FAILED' AND attempt_count = 6 AND next_attempt_at IS NULL
            AND completed_at IS NOT NULL AND last_attempt_at IS NOT NULL AND last_failure_class IS NOT NULL
            AND claim_token IS NULL)
        OR (state = 'CANCELLED' AND attempt_count BETWEEN 0 AND 5 AND next_attempt_at IS NULL
            AND completed_at IS NOT NULL AND claim_token IS NULL))
);
COMMENT ON TABLE integrations.delivery IS
    'One outbound webhook delivery with its immutable canonical payload; no response data is stored';

CREATE INDEX integrations_delivery_history ON integrations.delivery (tenant_id, webhook_id, created_at DESC, id DESC);
CREATE INDEX integrations_delivery_due ON integrations.delivery (next_attempt_at)
    WHERE state IN ('PENDING', 'RETRYING');

CREATE FUNCTION integrations.delivery_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PENDING' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_delivery_initial',
                MESSAGE = 'a delivery starts pending';
        END IF;
        IF NEW.kind = 'TEST' THEN
            PERFORM pg_advisory_xact_lock(hashtextextended('integrations.test:' || NEW.webhook_id::text, 0));
            IF (SELECT count(*) FROM integrations.delivery d
                WHERE d.webhook_id = NEW.webhook_id AND d.kind = 'TEST'
                  AND d.created_at > NEW.created_at - interval '1 hour'
                  AND d.created_at <= NEW.created_at) >= 5 THEN
                RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_delivery_test_quota',
                    MESSAGE = 'test delivery quota reached';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_delivery_final',
            MESSAGE = 'a finished delivery never changes';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.id <> OLD.id OR NEW.webhook_id <> OLD.webhook_id
        OR NEW.event_id <> OLD.event_id OR NEW.event_type <> OLD.event_type OR NEW.kind <> OLD.kind
        OR NEW.payload <> OLD.payload OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_delivery_immutable',
            MESSAGE = 'delivery identity and payload are immutable';
    END IF;
    IF NEW.attempt_count NOT IN (OLD.attempt_count, OLD.attempt_count + 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_delivery_attempt_number',
            MESSAGE = 'attempts are numbered without gaps';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_delivery_guard BEFORE INSERT OR UPDATE ON integrations.delivery
    FOR EACH ROW EXECUTE FUNCTION integrations.delivery_guard();
CREATE TRIGGER integrations_delivery_no_delete BEFORE DELETE ON integrations.delivery
    FOR EACH ROW EXECUTE FUNCTION integrations.append_only();
CREATE TRIGGER integrations_delivery_no_truncate BEFORE TRUNCATE ON integrations.delivery
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.append_only();

CREATE TABLE integrations.delivery_attempt (
    tenant_id     uuid        NOT NULL,
    delivery_id   uuid        NOT NULL,
    number        integer     NOT NULL,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz NOT NULL,
    http_status   integer,
    failure_class text,
    latency_ms    integer     NOT NULL,
    CONSTRAINT integrations_delivery_attempt_pkey PRIMARY KEY (tenant_id, delivery_id, number),
    CONSTRAINT integrations_delivery_attempt_delivery
        FOREIGN KEY (tenant_id, delivery_id) REFERENCES integrations.delivery (tenant_id, id),
    CONSTRAINT integrations_delivery_attempt_number CHECK (number BETWEEN 1 AND 6),
    CONSTRAINT integrations_delivery_attempt_times CHECK (finished_at >= started_at AND latency_ms >= 0),
    CONSTRAINT integrations_delivery_attempt_class
        CHECK (failure_class IS NULL OR failure_class IN ('DNS_BLOCKED', 'DNS_FAILED', 'CONNECT_TIMEOUT',
            'REQUEST_TIMEOUT', 'TLS_FAILED', 'CONNECTION_FAILED', 'HTTP_RESPONSE', 'INTERNAL_FAILURE')),
    CONSTRAINT integrations_delivery_attempt_shape CHECK (
        (failure_class IS NULL AND http_status BETWEEN 200 AND 299)
        OR (failure_class = 'HTTP_RESPONSE' AND http_status BETWEEN 100 AND 599
            AND http_status NOT BETWEEN 200 AND 299)
        OR (failure_class IS NOT NULL AND failure_class <> 'HTTP_RESPONSE' AND http_status IS NULL))
);
COMMENT ON TABLE integrations.delivery_attempt IS
    'Append-only webhook delivery attempts: status and closed failure class only, never a response body or header';

CREATE FUNCTION integrations.delivery_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    current_state text;
BEGIN
    SELECT state INTO current_state FROM integrations.delivery
        WHERE tenant_id = NEW.tenant_id AND id = NEW.delivery_id FOR UPDATE;
    IF current_state NOT IN ('PENDING', 'RETRYING') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_delivery_attempt_open',
            MESSAGE = 'attempts are only recorded for an open delivery';
    END IF;
    IF NEW.number <> coalesce((SELECT max(number) FROM integrations.delivery_attempt
        WHERE tenant_id = NEW.tenant_id AND delivery_id = NEW.delivery_id), 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_delivery_attempt_sequence',
            MESSAGE = 'attempts are numbered without gaps';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_delivery_attempt_guard BEFORE INSERT ON integrations.delivery_attempt
    FOR EACH ROW EXECUTE FUNCTION integrations.delivery_attempt_guard();
CREATE TRIGGER integrations_delivery_attempt_immutable BEFORE UPDATE OR DELETE ON integrations.delivery_attempt
    FOR EACH ROW EXECUTE FUNCTION integrations.append_only();
CREATE TRIGGER integrations_delivery_attempt_no_truncate BEFORE TRUNCATE ON integrations.delivery_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.append_only();

-- At commit: the delivery counts exactly its recorded attempts.
CREATE FUNCTION integrations.delivery_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    t uuid;
    d uuid;
BEGIN
    IF TG_TABLE_NAME = 'delivery' THEN
        t := NEW.tenant_id; d := NEW.id;
    ELSE
        t := NEW.tenant_id; d := NEW.delivery_id;
    END IF;
    IF (SELECT attempt_count FROM integrations.delivery WHERE tenant_id = t AND id = d)
        <> (SELECT count(*) FROM integrations.delivery_attempt WHERE tenant_id = t AND delivery_id = d) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_delivery_exact',
            MESSAGE = 'a delivery counts exactly its recorded attempts';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER integrations_delivery_exact_delivery AFTER INSERT OR UPDATE ON integrations.delivery
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.delivery_exact();
CREATE CONSTRAINT TRIGGER integrations_delivery_exact_attempt AFTER INSERT ON integrations.delivery_attempt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.delivery_exact();

-- ---------------------------------------------------------------------------------------------
-- Fan-out ledger
-- ---------------------------------------------------------------------------------------------

CREATE TABLE integrations.fanout_event (
    event_id  uuid        NOT NULL,
    tenant_id uuid        NOT NULL,
    fanned_at timestamptz NOT NULL,
    CONSTRAINT integrations_fanout_event_pkey PRIMARY KEY (event_id)
);
COMMENT ON TABLE integrations.fanout_event IS 'Business events already turned into webhook deliveries';
CREATE TRIGGER integrations_fanout_event_immutable BEFORE UPDATE OR DELETE ON integrations.fanout_event
    FOR EACH ROW EXECUTE FUNCTION integrations.append_only();
CREATE TRIGGER integrations_fanout_event_no_truncate BEFORE TRUNCATE ON integrations.fanout_event
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.append_only();

CREATE TABLE integrations.fanout_state (
    id         smallint    NOT NULL,
    watermark  timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT integrations_fanout_state_pkey PRIMARY KEY (id),
    CONSTRAINT integrations_fanout_state_single CHECK (id = 1)
);
COMMENT ON TABLE integrations.fanout_state IS
    'Read watermark of the webhook fan-out; starts at the migration time so history is never replayed';
INSERT INTO integrations.fanout_state (id, watermark, updated_at) VALUES (1, now(), now());
