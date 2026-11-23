-- =============================================================================================
-- V57 (MVP-120F, Issue #224): employee partner-product discovery and consented referral. ADR 0045.
--
-- A referral is a consented introduction of one employee to one approved partner product, delivered only to the
-- controlled SYNTHETIC-TEST provider. Nothing in this schema decides, prices, approves, insures, finances, activates,
-- deducts or pays: no state means approved, eligible, enrolled, funded or paid. There is no partner decision or callback
-- table in this story.
--
-- Separated (D120F-4, D120F-6):
--   partner_referral             identity and current delivery state (the only mutable table)
--   partner_referral_disclosure  the immutable manifest: allow-listed field names, encrypted values, canonical digest
--   partner_referral_consent     explicit consent evidence and wording version
--   partner_referral_event       append-only status history
--   partner_referral_attempt     one row per finished delivery attempt (bounded outcome, no payload, no body)
--   partner_referral_ack         the provider receipt, at most one
--
-- Invariants enforced by the database (never only by the application):
--   * tenant ownership (composite tenant foreign keys) and product/version/partner consistency;
--   * one referral per preview digest and employee, forever (unique preview/commit identity);
--   * a referral is recorded SUBMITTED with its disclosure, consent and first event in one transaction, by the
--     employee's own linked subject, for a product version that is ACTIVE and effective in the employee's country;
--   * closed delivery transitions, each with one recorded event (row_version equals the number of events);
--   * a referral is never both delivered and withdrawn (the withdrawal and the receipt take the same row lock; a
--     claimed attempt cannot be withdrawn; DELIVERED and DELIVERY_UNKNOWN are never withdrawable);
--   * disclosure and consent are immutable; events, attempts and receipts are append-only; nothing is deleted.
--
-- Lock order (ADR 0045): partner_referral row first, then its children. Every guard that touches a child locks the
-- referral row first, so concurrent withdrawal, claim and receipt serialise on one lock.
-- Rollback: db/rollback/V57__rollback.sql, which refuses once any referral row exists.
-- =============================================================================================

CREATE FUNCTION integrations.partner_referral_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_immutable',
        MESSAGE = 'partner-referral evidence is never deleted, truncated or updated';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Referral identity and current state
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.partner_referral (
    tenant_id       uuid        NOT NULL,
    id              uuid        NOT NULL,
    reference       text        NOT NULL,
    employee_id     uuid        NOT NULL,
    partner_id      uuid        NOT NULL,
    product_id      uuid        NOT NULL,
    version_id      uuid        NOT NULL,
    partner_code    text        NOT NULL,
    language        text        NOT NULL,
    state           text        NOT NULL,
    row_version     integer     NOT NULL,
    attempt_count   smallint    NOT NULL,
    preview_sha256  char(64)    NOT NULL,
    key_version     text        NOT NULL,
    submitted_by    text        NOT NULL,
    next_attempt_at timestamptz,
    lease_until     timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT integrations_partner_referral_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT integrations_partner_referral_reference_key UNIQUE (reference),
    CONSTRAINT integrations_partner_referral_preview_key UNIQUE (tenant_id, employee_id, preview_sha256),
    CONSTRAINT integrations_partner_referral_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT integrations_partner_referral_partner FOREIGN KEY (partner_id) REFERENCES integrations.partner (id),
    CONSTRAINT integrations_partner_referral_product FOREIGN KEY (product_id) REFERENCES integrations.partner_product (id),
    CONSTRAINT integrations_partner_referral_version FOREIGN KEY (version_id)
        REFERENCES integrations.partner_product_version (id),
    CONSTRAINT integrations_partner_referral_reference CHECK (reference ~ '^PR-[0-9A-HJKMNP-TV-Z]{16}$'),
    CONSTRAINT integrations_partner_referral_partner_code CHECK (partner_code = 'SYNTHETIC-TEST'),
    CONSTRAINT integrations_partner_referral_language CHECK (language IN ('en', 'fr')),
    CONSTRAINT integrations_partner_referral_state CHECK (
        state IN ('SUBMITTED', 'DELIVERY_PENDING', 'DELIVERED', 'DELIVERY_UNKNOWN', 'DELIVERY_FAILED', 'WITHDRAWN')),
    CONSTRAINT integrations_partner_referral_version_number CHECK (row_version BETWEEN 1 AND 64),
    CONSTRAINT integrations_partner_referral_attempts CHECK (attempt_count BETWEEN 0 AND 24),
    CONSTRAINT integrations_partner_referral_digest CHECK (preview_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT integrations_partner_referral_key CHECK (key_version ~ '^[A-Za-z0-9._-]{1,32}$'),
    CONSTRAINT integrations_partner_referral_by CHECK (char_length(submitted_by) BETWEEN 1 AND 255),
    CONSTRAINT integrations_partner_referral_times CHECK (updated_at >= created_at),
    -- A lease exists only while an attempt is in flight (DELIVERY_PENDING). A due time exists exactly while a delivery is
    -- waiting to be claimed: SUBMITTED, or DELIVERY_PENDING without a lease. DELIVERY_PENDING has one of the two.
    CONSTRAINT integrations_partner_referral_lease CHECK (lease_until IS NULL OR state = 'DELIVERY_PENDING'),
    CONSTRAINT integrations_partner_referral_due CHECK (
        (next_attempt_at IS NOT NULL) = (state = 'SUBMITTED' OR (state = 'DELIVERY_PENDING' AND lease_until IS NULL))),
    CONSTRAINT integrations_partner_referral_pending CHECK (
        state <> 'DELIVERY_PENDING' OR ((next_attempt_at IS NULL) <> (lease_until IS NULL))),
    CONSTRAINT integrations_partner_referral_attempts_state CHECK (state <> 'SUBMITTED' OR attempt_count = 0)
);

COMMENT ON TABLE integrations.partner_referral IS
    'MVP-120F consented partner referral (Restricted): identity and delivery state only. No approval, offer, pricing, '
    'enrollment, funding, deduction or payment state exists; no personal value is stored here (see the disclosure).';

CREATE INDEX integrations_partner_referral_employee_page
    ON integrations.partner_referral (tenant_id, employee_id, created_at DESC, id DESC);
CREATE INDEX integrations_partner_referral_platform_page
    ON integrations.partner_referral (created_at DESC, tenant_id DESC, id DESC);
CREATE INDEX integrations_partner_referral_due
    ON integrations.partner_referral (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE INDEX integrations_partner_referral_leased
    ON integrations.partner_referral (lease_until) WHERE lease_until IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Disclosure manifest (immutable). The values travel only inside the AES-GCM ciphertext.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.partner_referral_disclosure (
    tenant_id         uuid        NOT NULL,
    referral_id       uuid        NOT NULL,
    field_names       text[]      NOT NULL,
    values_ciphertext bytea       NOT NULL,
    disclosure_sha256 char(64)    NOT NULL,
    created_at        timestamptz NOT NULL,
    CONSTRAINT integrations_partner_referral_disclosure_pkey PRIMARY KEY (tenant_id, referral_id),
    CONSTRAINT integrations_partner_referral_disclosure_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id),
    CONSTRAINT integrations_partner_referral_disclosure_allowlist CHECK (
        cardinality(field_names) BETWEEN 10 AND 12
        AND field_names <@ ARRAY['REFERRAL_REFERENCE', 'PRODUCT_REFERENCE', 'PRODUCT_NAME', 'PARTNER_NAME',
            'EMPLOYEE_LEGAL_NAME', 'EMPLOYEE_EMAIL', 'EMPLOYEE_PHONE', 'EMPLOYEE_COUNTRY', 'PREFERRED_LANGUAGE',
            'ORGANIZATION_LEGAL_NAME', 'CONSENT_TIMESTAMP', 'CONSENT_VERSION']::text[]),
    CONSTRAINT integrations_partner_referral_disclosure_required CHECK (
        field_names @> ARRAY['REFERRAL_REFERENCE', 'PRODUCT_REFERENCE', 'PRODUCT_NAME', 'PARTNER_NAME',
            'EMPLOYEE_LEGAL_NAME', 'EMPLOYEE_EMAIL', 'EMPLOYEE_COUNTRY', 'PREFERRED_LANGUAGE', 'CONSENT_TIMESTAMP',
            'CONSENT_VERSION']::text[]),
    CONSTRAINT integrations_partner_referral_disclosure_cipher CHECK (octet_length(values_ciphertext) BETWEEN 40 AND 4096),
    CONSTRAINT integrations_partner_referral_disclosure_digest CHECK (disclosure_sha256 ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE integrations.partner_referral_disclosure IS
    'MVP-120F immutable disclosure manifest (Restricted). field_names is the allow-listed set; values_ciphertext holds '
    'the previewed values as AES-GCM ciphertext bound to the referral. The names and the digest, never the values, '
    'appear in audit and outbox metadata.';

-- ---------------------------------------------------------------------------------------------
-- Consent evidence (immutable)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.partner_referral_consent (
    tenant_id         uuid        NOT NULL,
    referral_id       uuid        NOT NULL,
    consent_version   text        NOT NULL,
    language          text        NOT NULL,
    consented_at      timestamptz NOT NULL,
    consented_by      text        NOT NULL,
    disclosure_sha256 char(64)    NOT NULL,
    preview_sha256    char(64)    NOT NULL,
    affirmed          boolean     NOT NULL,
    CONSTRAINT integrations_partner_referral_consent_pkey PRIMARY KEY (tenant_id, referral_id),
    CONSTRAINT integrations_partner_referral_consent_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id),
    CONSTRAINT integrations_partner_referral_consent_version CHECK (consent_version = 'PR-REFERRAL-1'),
    CONSTRAINT integrations_partner_referral_consent_language CHECK (language IN ('en', 'fr')),
    CONSTRAINT integrations_partner_referral_consent_digests CHECK (
        disclosure_sha256 ~ '^[0-9a-f]{64}$' AND preview_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT integrations_partner_referral_consent_by CHECK (char_length(consented_by) BETWEEN 1 AND 255),
    CONSTRAINT integrations_partner_referral_consent_affirmed CHECK (affirmed)
);

-- ---------------------------------------------------------------------------------------------
-- Append-only history
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.partner_referral_event (
    tenant_id   uuid        NOT NULL,
    referral_id uuid        NOT NULL,
    sequence    smallint    NOT NULL,
    from_state  text,
    to_state    text        NOT NULL,
    actor_role  text        NOT NULL,
    outcome     text,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT integrations_partner_referral_event_pkey PRIMARY KEY (tenant_id, referral_id, sequence),
    CONSTRAINT integrations_partner_referral_event_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id),
    CONSTRAINT integrations_partner_referral_event_sequence CHECK (
        sequence BETWEEN 1 AND 64 AND (sequence = 1) = (from_state IS NULL)),
    CONSTRAINT integrations_partner_referral_event_actor CHECK (actor_role IN ('EMPLOYEE', 'SYSTEM')),
    CONSTRAINT integrations_partner_referral_event_outcome CHECK (
        outcome IS NULL OR outcome IN ('PROVIDER_ACKNOWLEDGED', 'NOT_SENT_RETRYING', 'REJECTED_BY_PROVIDER',
            'RETRIES_EXHAUSTED', 'UNCERTAIN_NOT_RESENT', 'WITHDRAWN_BEFORE_DELIVERY')),
    -- The closed lifecycle: transition, actor and outcome travel together. There is no resend after DELIVERED or
    -- DELIVERY_UNKNOWN, no administrator action and no partner decision.
    CONSTRAINT integrations_partner_referral_event_transition CHECK (
        (from_state IS NULL AND to_state = 'SUBMITTED' AND actor_role = 'EMPLOYEE' AND outcome IS NULL)
        OR (from_state = 'SUBMITTED' AND to_state = 'DELIVERY_PENDING' AND actor_role = 'SYSTEM' AND outcome IS NULL)
        OR (from_state = 'DELIVERY_PENDING' AND to_state = 'DELIVERY_PENDING' AND actor_role = 'SYSTEM'
            AND outcome IS NULL)
        OR (from_state = 'DELIVERY_PENDING' AND to_state = 'DELIVERY_PENDING' AND actor_role = 'SYSTEM'
            AND outcome = 'NOT_SENT_RETRYING')
        OR (from_state = 'DELIVERY_PENDING' AND to_state = 'DELIVERED' AND actor_role = 'SYSTEM'
            AND outcome = 'PROVIDER_ACKNOWLEDGED')
        OR (from_state = 'DELIVERY_PENDING' AND to_state = 'DELIVERY_UNKNOWN' AND actor_role = 'SYSTEM'
            AND outcome = 'UNCERTAIN_NOT_RESENT')
        OR (from_state = 'DELIVERY_PENDING' AND to_state = 'DELIVERY_FAILED' AND actor_role = 'SYSTEM'
            AND outcome IN ('REJECTED_BY_PROVIDER', 'RETRIES_EXHAUSTED'))
        OR (from_state IN ('SUBMITTED', 'DELIVERY_PENDING', 'DELIVERY_FAILED') AND to_state = 'WITHDRAWN'
            AND actor_role = 'EMPLOYEE' AND outcome = 'WITHDRAWN_BEFORE_DELIVERY'))
);

-- ---------------------------------------------------------------------------------------------
-- Delivery attempts and the provider receipt
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integrations.partner_referral_attempt (
    tenant_id      uuid        NOT NULL,
    referral_id    uuid        NOT NULL,
    attempt_number smallint    NOT NULL,
    started_at     timestamptz NOT NULL,
    finished_at    timestamptz NOT NULL,
    outcome        text        NOT NULL,
    http_status    smallint,
    CONSTRAINT integrations_partner_referral_attempt_pkey PRIMARY KEY (tenant_id, referral_id, attempt_number),
    CONSTRAINT integrations_partner_referral_attempt_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id),
    CONSTRAINT integrations_partner_referral_attempt_number CHECK (attempt_number BETWEEN 1 AND 24),
    CONSTRAINT integrations_partner_referral_attempt_time CHECK (finished_at >= started_at),
    CONSTRAINT integrations_partner_referral_attempt_outcome CHECK (outcome IN (
        'PROVIDER_ACKNOWLEDGED', 'NOT_SENT_RETRYING', 'REJECTED_BY_PROVIDER', 'RETRIES_EXHAUSTED',
        'UNCERTAIN_NOT_RESENT')),
    CONSTRAINT integrations_partner_referral_attempt_status CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599)
);

CREATE TABLE integrations.partner_referral_ack (
    tenant_id          uuid        NOT NULL,
    referral_id        uuid        NOT NULL,
    provider_reference text        NOT NULL,
    acknowledged_at    timestamptz NOT NULL,
    CONSTRAINT integrations_partner_referral_ack_pkey PRIMARY KEY (tenant_id, referral_id),
    CONSTRAINT integrations_partner_referral_ack_referral FOREIGN KEY (tenant_id, referral_id)
        REFERENCES integrations.partner_referral (tenant_id, id),
    CONSTRAINT integrations_partner_referral_ack_reference CHECK (provider_reference ~ '^[A-Za-z0-9._-]{8,64}$')
);

-- ---------------------------------------------------------------------------------------------
-- Immutability: no UPDATE, DELETE or TRUNCATE on the evidence; no DELETE or TRUNCATE on the referral
-- ---------------------------------------------------------------------------------------------
CREATE TRIGGER integrations_partner_referral_disclosure_immutable
    BEFORE UPDATE OR DELETE ON integrations.partner_referral_disclosure
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_disclosure_no_truncate
    BEFORE TRUNCATE ON integrations.partner_referral_disclosure
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_consent_immutable
    BEFORE UPDATE OR DELETE ON integrations.partner_referral_consent
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_consent_no_truncate
    BEFORE TRUNCATE ON integrations.partner_referral_consent
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_event_immutable
    BEFORE UPDATE OR DELETE ON integrations.partner_referral_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_event_no_truncate
    BEFORE TRUNCATE ON integrations.partner_referral_event
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_attempt_immutable
    BEFORE UPDATE OR DELETE ON integrations.partner_referral_attempt
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_attempt_no_truncate
    BEFORE TRUNCATE ON integrations.partner_referral_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_ack_immutable
    BEFORE UPDATE OR DELETE ON integrations.partner_referral_ack
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_ack_no_truncate
    BEFORE TRUNCATE ON integrations.partner_referral_ack
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_no_delete BEFORE DELETE ON integrations.partner_referral
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_no_truncate BEFORE TRUNCATE ON integrations.partner_referral
    FOR EACH STATEMENT EXECUTE FUNCTION integrations.partner_referral_immutable();

-- ---------------------------------------------------------------------------------------------
-- Referral guard. INSERT proves the employee, the subject and the product's availability. UPDATE moves only the
-- mutable columns along the closed lifecycle, one version at a time, with counters equal to their evidence.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION integrations.partner_referral_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    v_status      text;
    v_product     uuid;
    v_effective   date;
    v_end         date;
    v_partner_id  uuid;
    v_partner_st  text;
    v_today       date;
    v_country     text;
    v_ack         boolean;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (to_jsonb(NEW) - 'state' - 'row_version' - 'attempt_count' - 'next_attempt_at' - 'lease_until' - 'updated_at')
            IS DISTINCT FROM
           (to_jsonb(OLD) - 'state' - 'row_version' - 'attempt_count' - 'next_attempt_at' - 'lease_until' - 'updated_at')
            OR NEW.row_version <> OLD.row_version + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_transition',
                MESSAGE = 'a referral only moves its status columns, one version at a time';
        END IF;
        SELECT EXISTS (SELECT 1 FROM integrations.partner_referral_ack a
                        WHERE a.tenant_id = OLD.tenant_id AND a.referral_id = OLD.id) INTO v_ack;
        IF NOT (
            -- claim from SUBMITTED, or re-claim of a waiting retry: one more attempt, a lease, no due time
            ((OLD.state = 'SUBMITTED' OR (OLD.state = 'DELIVERY_PENDING' AND OLD.lease_until IS NULL))
                AND NEW.state = 'DELIVERY_PENDING' AND NEW.lease_until IS NOT NULL AND NEW.next_attempt_at IS NULL
                AND NEW.attempt_count = OLD.attempt_count + 1)
            -- the claimed attempt was not sent: the lease is released and a retry is due
            OR (OLD.state = 'DELIVERY_PENDING' AND OLD.lease_until IS NOT NULL AND NEW.state = 'DELIVERY_PENDING'
                AND NEW.lease_until IS NULL AND NEW.next_attempt_at IS NOT NULL
                AND NEW.attempt_count = OLD.attempt_count)
            -- the claimed attempt resolved: a receipt, an uncertain outcome (never resent) or a proven failure
            OR (OLD.state = 'DELIVERY_PENDING' AND OLD.lease_until IS NOT NULL
                AND NEW.state IN ('DELIVERED', 'DELIVERY_UNKNOWN', 'DELIVERY_FAILED')
                AND NEW.lease_until IS NULL AND NEW.next_attempt_at IS NULL AND NEW.attempt_count = OLD.attempt_count
                AND ((NEW.state = 'DELIVERED') = v_ack))
            -- the employee withdraws before the partner has or may have received the referral (no attempt in flight)
            OR (NEW.state = 'WITHDRAWN'
                AND (OLD.state IN ('SUBMITTED', 'DELIVERY_FAILED')
                     OR (OLD.state = 'DELIVERY_PENDING' AND OLD.lease_until IS NULL))
                AND NEW.lease_until IS NULL AND NEW.next_attempt_at IS NULL AND NEW.attempt_count = OLD.attempt_count
                AND NOT v_ack)) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_transition',
                MESSAGE = 'the referral does not follow the closed lifecycle';
        END IF;
        RETURN NEW;
    END IF;

    -- INSERT -------------------------------------------------------------------------------------------------
    IF NEW.state <> 'SUBMITTED' OR NEW.row_version <> 1 OR NEW.attempt_count <> 0 OR NEW.lease_until IS NOT NULL
        OR NEW.next_attempt_at IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_transition',
            MESSAGE = 'a referral is recorded SUBMITTED with its disclosure, consent and first event';
    END IF;
    -- The submitting subject is the employee's own active linked employee membership.
    IF NOT EXISTS (
            SELECT 1 FROM identity.employee_access_link l
              JOIN identity.tenant_membership m ON m.tenant_id = l.tenant_id AND m.id = l.membership_id
             WHERE l.tenant_id = NEW.tenant_id AND l.employee_id = NEW.employee_id
               AND l.unlinked_at IS NULL AND m.subject = NEW.submitted_by AND m.role = 'employee') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_owner',
            MESSAGE = 'a referral is submitted by the employee''s own linked subject';
    END IF;
    -- The product version is the live version of this product, of this partner, effective in the employee's country.
    SELECT v.status, v.product_id, v.effective_date, v.end_date, x.partner_id, p.status
      INTO v_status, v_product, v_effective, v_end, v_partner_id, v_partner_st
      FROM integrations.partner_product_version v
      JOIN integrations.partner_product x ON x.id = v.product_id
      JOIN integrations.partner p ON p.id = x.partner_id
     WHERE v.id = NEW.version_id;
    v_today := (NEW.created_at AT TIME ZONE 'UTC')::date;
    SELECT o.country_code INTO v_country FROM tenant.organization o WHERE o.id = NEW.tenant_id;
    IF v_status IS DISTINCT FROM 'ACTIVE' OR v_partner_st IS DISTINCT FROM 'ACTIVE'
        OR v_product IS DISTINCT FROM NEW.product_id OR v_partner_id IS DISTINCT FROM NEW.partner_id
        OR v_effective IS NULL OR v_effective > v_today OR (v_end IS NOT NULL AND v_end < v_today)
        OR v_country IS NULL
        OR NOT EXISTS (SELECT 1 FROM integrations.partner_product_country c
                        WHERE c.version_id = NEW.version_id AND c.country_code = v_country) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_product',
            MESSAGE = 'a referral needs a live product of an active partner, available in the employee''s country';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_referral_guard BEFORE INSERT OR UPDATE ON integrations.partner_referral
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_guard();

-- ---------------------------------------------------------------------------------------------
-- Disclosure guard: at submission only, no duplicate field, bound to the referral
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION integrations.partner_referral_disclosure_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    r integrations.partner_referral%ROWTYPE;
BEGIN
    SELECT * INTO r FROM integrations.partner_referral x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.referral_id FOR UPDATE;
    IF NOT FOUND OR r.row_version <> 1 OR r.state <> 'SUBMITTED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_disclosure_binding',
            MESSAGE = 'the disclosure is recorded with the new referral only';
    END IF;
    IF cardinality(NEW.field_names) <> (SELECT count(DISTINCT f) FROM unnest(NEW.field_names) AS f) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_disclosure_allowlist',
            MESSAGE = 'a disclosure field is named once';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_referral_disclosure_guard
    BEFORE INSERT ON integrations.partner_referral_disclosure
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_disclosure_guard();

-- ---------------------------------------------------------------------------------------------
-- Consent guard: bound to the referral, the subject, the preview and the stored disclosure digest
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION integrations.partner_referral_consent_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    r integrations.partner_referral%ROWTYPE;
    d text;
BEGIN
    SELECT * INTO r FROM integrations.partner_referral x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.referral_id FOR UPDATE;
    SELECT x.disclosure_sha256 INTO d FROM integrations.partner_referral_disclosure x
     WHERE x.tenant_id = NEW.tenant_id AND x.referral_id = NEW.referral_id;
    IF NOT FOUND OR r.row_version <> 1 OR r.state <> 'SUBMITTED' OR r.submitted_by <> NEW.consented_by
        OR r.preview_sha256 <> NEW.preview_sha256 OR r.language <> NEW.language OR d IS NULL
        OR d <> NEW.disclosure_sha256 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_consent_binding',
            MESSAGE = 'consent evidence is bound to the referral, subject, preview and disclosure at submission';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_referral_consent_guard
    BEFORE INSERT ON integrations.partner_referral_consent
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_consent_guard();

-- ---------------------------------------------------------------------------------------------
-- Event guard: legal chain and order; the referral row is locked first so concurrent commands serialise
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION integrations.partner_referral_event_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    last_sequence integer;
    last_state    text;
    last_at       timestamptz;
BEGIN
    PERFORM 1 FROM integrations.partner_referral x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.referral_id FOR UPDATE;
    SELECT e.sequence, e.to_state, e.occurred_at INTO last_sequence, last_state, last_at
      FROM integrations.partner_referral_event e
     WHERE e.tenant_id = NEW.tenant_id AND e.referral_id = NEW.referral_id
     ORDER BY e.sequence DESC LIMIT 1;
    IF NEW.sequence <> coalesce(last_sequence, 0) + 1
        OR NEW.from_state IS DISTINCT FROM last_state
        OR (last_at IS NOT NULL AND NEW.occurred_at < last_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_event_transition',
            MESSAGE = 'the referral event does not follow the referral history';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_referral_event_guard BEFORE INSERT ON integrations.partner_referral_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_event_guard();

-- ---------------------------------------------------------------------------------------------
-- Attempt guard: an attempt finishes only for the claimed attempt (lease held), numbered by the claim
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION integrations.partner_referral_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    r integrations.partner_referral%ROWTYPE;
BEGIN
    SELECT * INTO r FROM integrations.partner_referral x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.referral_id FOR UPDATE;
    IF NOT FOUND OR r.state <> 'DELIVERY_PENDING' OR r.lease_until IS NULL OR r.attempt_count <> NEW.attempt_number THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_attempt_state',
            MESSAGE = 'an attempt finishes only for the claimed delivery';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_referral_attempt_guard BEFORE INSERT ON integrations.partner_referral_attempt
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_attempt_guard();

-- ---------------------------------------------------------------------------------------------
-- Receipt guard: recorded only while an attempt is in flight; never after a withdrawal
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION integrations.partner_referral_ack_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    r integrations.partner_referral%ROWTYPE;
BEGIN
    SELECT * INTO r FROM integrations.partner_referral x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.referral_id FOR UPDATE;
    IF NOT FOUND OR r.state <> 'DELIVERY_PENDING' OR r.lease_until IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_ack_state',
            MESSAGE = 'a receipt is recorded only while the claimed delivery is in flight';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER integrations_partner_referral_ack_guard BEFORE INSERT ON integrations.partner_referral_ack
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_ack_guard();

-- ---------------------------------------------------------------------------------------------
-- Completeness at commit: a referral always has its disclosure, consent and first event; its version equals its
-- events; its current state is the last event; its attempt counter equals the finished attempts (one more while a
-- claimed attempt is in flight). Deferred, so the whole submission or command commits together or not at all.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION integrations.partner_referral_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    r          integrations.partner_referral%ROWTYPE;
    n_events   integer;
    last_state text;
    n_attempts integer;
BEGIN
    IF TG_TABLE_NAME = 'partner_referral' THEN
        r := NEW;
    ELSE
        SELECT * INTO r FROM integrations.partner_referral x
         WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.referral_id;
        IF NOT FOUND THEN
            RETURN NULL;
        END IF;
    END IF;
    SELECT count(*), (array_agg(e.to_state ORDER BY e.sequence DESC))[1] INTO n_events, last_state
      FROM integrations.partner_referral_event e WHERE e.tenant_id = r.tenant_id AND e.referral_id = r.id;
    SELECT count(*) INTO n_attempts
      FROM integrations.partner_referral_attempt a WHERE a.tenant_id = r.tenant_id AND a.referral_id = r.id;
    IF NOT EXISTS (SELECT 1 FROM integrations.partner_referral_disclosure d
                    WHERE d.tenant_id = r.tenant_id AND d.referral_id = r.id)
        OR NOT EXISTS (SELECT 1 FROM integrations.partner_referral_consent c
                        WHERE c.tenant_id = r.tenant_id AND c.referral_id = r.id)
        OR n_events <> r.row_version OR last_state IS DISTINCT FROM r.state
        OR n_attempts <> r.attempt_count - (CASE WHEN r.lease_until IS NOT NULL THEN 1 ELSE 0 END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_complete',
            MESSAGE = 'a referral carries its disclosure, consent, history and attempts exactly';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER integrations_partner_referral_complete
    AFTER INSERT OR UPDATE ON integrations.partner_referral
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_complete();
CREATE CONSTRAINT TRIGGER integrations_partner_referral_event_complete
    AFTER INSERT ON integrations.partner_referral_event
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_complete();
CREATE CONSTRAINT TRIGGER integrations_partner_referral_attempt_complete
    AFTER INSERT ON integrations.partner_referral_attempt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_complete();
