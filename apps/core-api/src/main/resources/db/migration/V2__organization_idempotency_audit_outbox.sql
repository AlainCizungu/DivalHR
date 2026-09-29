-- MVP-001 (Issue #10): organization aggregate plus platform idempotency, audit and outbox records.
-- Application validation is authoritative for supported values; these constraints are a second,
-- independent line of defence. Rollback: see db/rollback/V2__rollback.sql (never run by Flyway).

-- ---------------------------------------------------------------------------------------------
-- tenant module: organization aggregate (organization id IS the tenant id)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenant.organization (
    id             uuid        PRIMARY KEY,
    name           text        NOT NULL,
    country_code   char(2)     NOT NULL,
    default_locale text        NOT NULL,
    timezone       text        NOT NULL,
    status         text        NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz NOT NULL,
    created_by     text        NOT NULL,
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT organization_name_trimmed_length
        CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 160),
    CONSTRAINT organization_name_no_control_chars CHECK (name !~ '[[:cntrl:]]'),
    CONSTRAINT organization_country_code_format CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT organization_default_locale_supported CHECK (default_locale IN ('fr', 'en')),
    CONSTRAINT organization_timezone_length CHECK (char_length(timezone) BETWEEN 3 AND 64),
    CONSTRAINT organization_status_valid CHECK (status IN ('ACTIVE')),
    CONSTRAINT organization_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT organization_version_non_negative CHECK (version >= 0)
);

COMMENT ON TABLE tenant.organization IS 'Organization aggregate; id is the tenant identifier';
COMMENT ON COLUMN tenant.organization.created_by IS 'Verified JWT subject (opaque ID), not a name';

CREATE TABLE tenant.organization_currency (
    organization_id uuid    NOT NULL REFERENCES tenant.organization (id),
    currency_code   char(3) NOT NULL,
    PRIMARY KEY (organization_id, currency_code),
    CONSTRAINT organization_currency_code_format CHECK (currency_code ~ '^[A-Z]{3}$')
);

-- ---------------------------------------------------------------------------------------------
-- platform: idempotency records
-- ---------------------------------------------------------------------------------------------
CREATE TABLE platform.idempotency_record (
    operation           text        NOT NULL,
    principal           text        NOT NULL,
    idempotency_key     text        NOT NULL,
    request_fingerprint char(64)    NOT NULL,
    state               text        NOT NULL,
    response_status     integer,
    response_body       jsonb,
    resource_id         uuid,
    created_at          timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    PRIMARY KEY (operation, principal, idempotency_key),
    CONSTRAINT idempotency_operation_format CHECK (operation ~ '^[a-z]+(\.[a-z-]+)+$'),
    CONSTRAINT idempotency_principal_length CHECK (char_length(principal) BETWEEN 1 AND 255),
    CONSTRAINT idempotency_key_format CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,128}$'),
    CONSTRAINT idempotency_fingerprint_format CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_state_valid CHECK (state IN ('PENDING', 'COMPLETED')),
    CONSTRAINT idempotency_state_consistent CHECK (
        (state = 'PENDING' AND response_status IS NULL AND response_body IS NULL
            AND resource_id IS NULL)
        OR (state = 'COMPLETED' AND response_status BETWEEN 200 AND 299
            AND response_body IS NOT NULL AND jsonb_typeof(response_body) = 'object'
            AND resource_id IS NOT NULL)),
    CONSTRAINT idempotency_retention_positive CHECK (expires_at > created_at)
);

COMMENT ON TABLE platform.idempotency_record IS
    'Successful idempotent responses. Retained at least until expires_at; cleanup must never remove a record earlier.';

-- A record may exist in PENDING state only inside the transaction that created it. This deferred
-- trigger re-reads the row at commit time and rejects any commit that leaves it PENDING.
CREATE FUNCTION platform.idempotency_require_completed() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1
                 FROM platform.idempotency_record r
                WHERE r.operation = NEW.operation
                  AND r.principal = NEW.principal
                  AND r.idempotency_key = NEW.idempotency_key
                  AND r.state <> 'COMPLETED') THEN
        RAISE EXCEPTION 'idempotency record must be COMPLETED before commit'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER idempotency_completed_at_commit
    AFTER INSERT OR UPDATE ON platform.idempotency_record
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION platform.idempotency_require_completed();

-- ---------------------------------------------------------------------------------------------
-- platform: append-only audit events
-- ---------------------------------------------------------------------------------------------
CREATE TABLE platform.audit_event (
    id                 uuid        PRIMARY KEY,
    occurred_at        timestamptz NOT NULL,
    actor_subject      text        NOT NULL,
    action             text        NOT NULL,
    resource_type      text        NOT NULL,
    resource_id        uuid        NOT NULL,
    tenant_id          uuid        NOT NULL,
    result             text        NOT NULL,
    correlation_id     text        NOT NULL,
    metadata           jsonb       NOT NULL,
    after_state_sha256 char(64)    NOT NULL,
    CONSTRAINT audit_actor_length CHECK (char_length(actor_subject) BETWEEN 1 AND 255),
    CONSTRAINT audit_action_format CHECK (action ~ '^[a-z]+(\.[a-z-]+)+$'),
    CONSTRAINT audit_resource_type_format CHECK (resource_type ~ '^[a-z][a-z-]{1,63}$'),
    CONSTRAINT audit_result_valid CHECK (result IN ('SUCCESS', 'DENIED', 'FAILURE')),
    CONSTRAINT audit_correlation_length CHECK (char_length(correlation_id) BETWEEN 8 AND 64),
    CONSTRAINT audit_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT audit_after_state_format CHECK (after_state_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX audit_event_tenant_time ON platform.audit_event (tenant_id, occurred_at);

CREATE FUNCTION platform.audit_event_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'platform.audit_event is append-only (% rejected)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON platform.audit_event
    FOR EACH ROW EXECUTE FUNCTION platform.audit_event_append_only();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON platform.audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION platform.audit_event_append_only();

-- ---------------------------------------------------------------------------------------------
-- platform: transactional outbox
-- ---------------------------------------------------------------------------------------------
CREATE TABLE platform.outbox_event (
    event_id         uuid        PRIMARY KEY,
    event_type       text        NOT NULL,
    tenant_id        uuid        NOT NULL,
    envelope         jsonb       NOT NULL,
    created_at       timestamptz NOT NULL,
    published_at     timestamptz,
    publish_attempts integer     NOT NULL DEFAULT 0,
    CONSTRAINT outbox_event_type_format CHECK (event_type ~ '^[a-z]+(\.[a-z-]+)+\.v[0-9]+$'),
    CONSTRAINT outbox_envelope_object CHECK (jsonb_typeof(envelope) = 'object'),
    CONSTRAINT outbox_envelope_matches_columns CHECK (
        envelope ->> 'eventId' = event_id::text
        AND envelope ->> 'eventType' = event_type
        AND envelope ->> 'tenantId' = tenant_id::text),
    CONSTRAINT outbox_envelope_required_fields CHECK (
        envelope ?& ARRAY['eventId', 'eventType', 'schemaVersion', 'tenantId', 'source',
                          'subject', 'eventTime', 'correlationId', 'causationId', 'data']
        AND jsonb_typeof(envelope -> 'data') = 'object'
        AND jsonb_typeof(envelope -> 'schemaVersion') = 'number'
        AND jsonb_typeof(envelope -> 'causationId') IN ('string', 'null')
        AND envelope ->> 'eventTime' LIKE '%Z'),
    CONSTRAINT outbox_publish_attempts_non_negative CHECK (publish_attempts >= 0),
    CONSTRAINT outbox_published_after_created CHECK (published_at IS NULL OR published_at >= created_at)
);

CREATE INDEX outbox_event_unpublished ON platform.outbox_event (created_at)
    WHERE published_at IS NULL;
