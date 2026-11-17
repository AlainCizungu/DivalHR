-- =============================================================================================
-- V53 (MVP-120B, Issue #206): controlled financial-partner handoff of an employer-confirmed salary-advance request.
--
-- ADR 0042. SYNTHETIC-TEST partner only. This schema stores delivery and bounded partner-review STATUS evidence.
-- There is no approval, offer, funding, disbursement, repayment or credit-decision state anywhere in it, and the
-- canonical partner payload, signatures, headers and partner response bodies are never persisted (digest only).
--
-- Invariants enforced by the database (never only by the application):
--   * tenant ownership (composite tenant foreign keys on every table);
--   * one handoff per request, forever;
--   * a handoff starts only from an EMPLOYER_CONFIRMED request, authorised by the employee's own linked subject,
--     who is never the confirming reviewer;
--   * fresh versioned consent evidence, bound to the payload digest, in the same transaction;
--   * closed delivery and review transitions, each with a recorded evidence row (row_version = evidence rows);
--   * counters (attempts, reconciles, admin retries) equal the evidence rows that justify them;
--   * receipt acknowledgement is immutable and at most one; receipt and a withdrawal cannot both win;
--   * a request with a live handoff is never withdrawn through the MVP-120A route;
--   * evidence rows are append-only (UPDATE, DELETE and TRUNCATE are refused).
-- =============================================================================================

CREATE TABLE payroll.salary_advance_handoff (
    tenant_id              uuid        NOT NULL,
    id                     uuid        NOT NULL,
    request_id             uuid        NOT NULL,
    employee_id            uuid        NOT NULL,
    legal_entity_id        uuid        NOT NULL,
    partner_code           text        NOT NULL,
    application_id         uuid        NOT NULL,
    state                  text        NOT NULL,
    review_state           text,
    review_reason          text,
    row_version            integer     NOT NULL,
    attempt_count          smallint    NOT NULL,
    reconcile_count        smallint    NOT NULL,
    admin_retry_count      smallint    NOT NULL,
    payload_schema_version smallint    NOT NULL,
    payload_sha256         char(64)    NOT NULL,
    shared_categories      text[]      NOT NULL,
    key_version            text        NOT NULL,
    authorized_at          timestamptz NOT NULL,
    authorized_by          text        NOT NULL,
    next_attempt_at        timestamptz,
    lease_until            timestamptz,
    updated_at             timestamptz NOT NULL,
    CONSTRAINT payroll_salary_advance_handoff_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT payroll_salary_advance_handoff_request_unique UNIQUE (tenant_id, request_id),
    CONSTRAINT payroll_salary_advance_handoff_application_unique UNIQUE (application_id),
    CONSTRAINT payroll_salary_advance_handoff_request FOREIGN KEY (tenant_id, employee_id, request_id)
        REFERENCES payroll.salary_advance_request (tenant_id, employee_id, id),
    CONSTRAINT payroll_salary_advance_handoff_partner CHECK (partner_code = 'SYNTHETIC-TEST'),
    CONSTRAINT payroll_salary_advance_handoff_state CHECK (
        state IN ('AUTHORIZED', 'QUEUED', 'SENDING', 'RECEIVED', 'DELIVERY_UNKNOWN', 'FAILED', 'WITHDRAWN')),
    CONSTRAINT payroll_salary_advance_handoff_review CHECK (
        (state = 'RECEIVED') = (review_state IS NOT NULL)
        AND (review_state IS NULL
             OR review_state IN ('RECEIVED', 'UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'DECLINED'))),
    CONSTRAINT payroll_salary_advance_handoff_review_reason CHECK (
        review_reason IS NULL OR (review_state IS NOT NULL AND review_reason IN (
            'ADDITIONAL_DOCUMENTS', 'CLARIFICATION', 'PARTNER_POLICY', 'SYNTHETIC_TEST_SCENARIO'))),
    CONSTRAINT payroll_salary_advance_handoff_version CHECK (row_version BETWEEN 1 AND 400),
    CONSTRAINT payroll_salary_advance_handoff_counters CHECK (
        attempt_count BETWEEN 0 AND 24 AND reconcile_count BETWEEN 0 AND 3 AND admin_retry_count BETWEEN 0 AND 3),
    CONSTRAINT payroll_salary_advance_handoff_schema CHECK (payload_schema_version = 1),
    CONSTRAINT payroll_salary_advance_handoff_digest CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_salary_advance_handoff_categories CHECK (
        cardinality(shared_categories) BETWEEN 1 AND 12
        AND shared_categories <@ ARRAY['APPLICATION_REFERENCE', 'REQUEST_REFERENCE', 'LEGAL_ENTITY_REFERENCE',
            'EMPLOYMENT_STATUS', 'EMPLOYMENT_START', 'PAYROLL_REFERENCE_MONTH', 'CURRENCY', 'REQUESTED_AMOUNT',
            'EMPLOYER_CEILING', 'EMPLOYER_CONFIRMATION', 'CONSENT_EVIDENCE', 'CORRELATION_REFERENCE']::text[]),
    CONSTRAINT payroll_salary_advance_handoff_key CHECK (key_version ~ '^[A-Za-z0-9._-]{1,32}$'),
    CONSTRAINT payroll_salary_advance_handoff_by CHECK (char_length(authorized_by) BETWEEN 1 AND 255),
    CONSTRAINT payroll_salary_advance_handoff_schedule CHECK (
        (state = 'QUEUED') = (next_attempt_at IS NOT NULL) AND (state = 'SENDING') = (lease_until IS NOT NULL))
);

COMMENT ON TABLE payroll.salary_advance_handoff IS
    'MVP-120B controlled SYNTHETIC-TEST partner handoff status (Restricted). Delivery and review STATUS only: no '
    'approval, offer, funding or repayment state exists, and no payload, signature or partner response is stored.';

CREATE INDEX payroll_salary_advance_handoff_tenant_page
    ON payroll.salary_advance_handoff (tenant_id, authorized_at DESC, id DESC);
CREATE INDEX payroll_salary_advance_handoff_employee_page
    ON payroll.salary_advance_handoff (tenant_id, employee_id, authorized_at DESC, id DESC);
CREATE INDEX payroll_salary_advance_handoff_due
    ON payroll.salary_advance_handoff (next_attempt_at) WHERE state = 'QUEUED';
CREATE INDEX payroll_salary_advance_handoff_leased
    ON payroll.salary_advance_handoff (lease_until) WHERE state = 'SENDING';

-- Consent evidence ---------------------------------------------------------------------------------------------
CREATE TABLE payroll.salary_advance_handoff_consent (
    tenant_id             uuid        NOT NULL,
    handoff_id            uuid        NOT NULL,
    request_id            uuid        NOT NULL,
    consent_version       text        NOT NULL,
    language              text        NOT NULL,
    consented_at          timestamptz NOT NULL,
    disclosure_sha256     char(64)    NOT NULL,
    payload_sha256        char(64)    NOT NULL,
    consented_by          text        NOT NULL,
    affirmed              boolean     NOT NULL,
    synthetic_acknowledged boolean    NOT NULL,
    CONSTRAINT payroll_salary_advance_handoff_consent_pkey PRIMARY KEY (tenant_id, handoff_id),
    CONSTRAINT payroll_salary_advance_handoff_consent_handoff FOREIGN KEY (tenant_id, handoff_id)
        REFERENCES payroll.salary_advance_handoff (tenant_id, id),
    CONSTRAINT payroll_salary_advance_handoff_consent_version CHECK (consent_version = 'SA-HANDOFF-1'),
    CONSTRAINT payroll_salary_advance_handoff_consent_language CHECK (language IN ('en', 'fr')),
    CONSTRAINT payroll_salary_advance_handoff_consent_digests CHECK (
        disclosure_sha256 ~ '^[0-9a-f]{64}$' AND payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_salary_advance_handoff_consent_by CHECK (char_length(consented_by) BETWEEN 1 AND 255),
    CONSTRAINT payroll_salary_advance_handoff_consent_affirmed CHECK (affirmed AND synthetic_acknowledged)
);

-- Delivery evidence: one row per transition -----------------------------------------------------------------------
CREATE TABLE payroll.salary_advance_handoff_event (
    tenant_id     uuid        NOT NULL,
    handoff_id    uuid        NOT NULL,
    sequence      smallint    NOT NULL,
    from_state    text,
    to_state      text        NOT NULL,
    actor_role    text        NOT NULL,
    actor_subject text        NOT NULL,
    occurred_at   timestamptz NOT NULL,
    outcome       text,
    CONSTRAINT payroll_salary_advance_handoff_event_pkey PRIMARY KEY (tenant_id, handoff_id, sequence),
    CONSTRAINT payroll_salary_advance_handoff_event_handoff FOREIGN KEY (tenant_id, handoff_id)
        REFERENCES payroll.salary_advance_handoff (tenant_id, id),
    CONSTRAINT payroll_salary_advance_handoff_event_sequence CHECK (
        sequence BETWEEN 1 AND 200 AND (sequence = 1) = (from_state IS NULL)),
    CONSTRAINT payroll_salary_advance_handoff_event_actor CHECK (
        actor_role IN ('EMPLOYEE', 'TENANT_ADMIN', 'SYSTEM') AND char_length(actor_subject) BETWEEN 1 AND 255),
    CONSTRAINT payroll_salary_advance_handoff_event_outcome CHECK (
        outcome IS NULL OR outcome IN ('RECEIVED_ACKNOWLEDGED', 'REJECTED_BY_PARTNER', 'TRANSIENT_NOT_SENT',
            'UNKNOWN_TIMEOUT', 'UNKNOWN_BAD_RESPONSE', 'UNKNOWN_LEASE_EXPIRED', 'RETRIES_EXHAUSTED',
            'WITHDRAWN_BEFORE_RECEIPT', 'RECONCILED_RECEIVED', 'RECONCILED_NOT_RECEIVED', 'RECONCILE_INCONCLUSIVE',
            'ADMIN_RETRY')),
    -- The closed delivery lifecycle: transition, actor and outcome travel together. There is no approval or funding
    -- state, no resend after receipt, and an ambiguous send returns to QUEUED only through a signed administrator
    -- reconcile that proves non-acceptance.
    CONSTRAINT payroll_salary_advance_handoff_event_transition CHECK (
        (from_state IS NULL AND to_state = 'AUTHORIZED' AND actor_role = 'EMPLOYEE' AND outcome IS NULL)
        OR (from_state = 'AUTHORIZED' AND to_state = 'QUEUED' AND actor_role = 'EMPLOYEE' AND outcome IS NULL)
        OR (from_state = 'QUEUED' AND to_state = 'SENDING' AND actor_role = 'SYSTEM' AND outcome IS NULL)
        OR (from_state = 'QUEUED' AND to_state = 'WITHDRAWN' AND actor_role = 'EMPLOYEE'
            AND outcome = 'WITHDRAWN_BEFORE_RECEIPT')
        OR (from_state = 'SENDING' AND to_state = 'RECEIVED' AND actor_role = 'SYSTEM'
            AND outcome = 'RECEIVED_ACKNOWLEDGED')
        OR (from_state = 'SENDING' AND to_state = 'QUEUED' AND actor_role = 'SYSTEM'
            AND outcome = 'TRANSIENT_NOT_SENT')
        OR (from_state = 'SENDING' AND to_state = 'DELIVERY_UNKNOWN' AND actor_role = 'SYSTEM'
            AND outcome IN ('UNKNOWN_TIMEOUT', 'UNKNOWN_BAD_RESPONSE', 'UNKNOWN_LEASE_EXPIRED'))
        OR (from_state = 'SENDING' AND to_state = 'FAILED' AND actor_role = 'SYSTEM'
            AND outcome IN ('REJECTED_BY_PARTNER', 'RETRIES_EXHAUSTED'))
        OR (from_state = 'DELIVERY_UNKNOWN' AND to_state = 'RECEIVED' AND actor_role = 'TENANT_ADMIN'
            AND outcome = 'RECONCILED_RECEIVED')
        OR (from_state = 'DELIVERY_UNKNOWN' AND to_state = 'QUEUED' AND actor_role = 'TENANT_ADMIN'
            AND outcome = 'RECONCILED_NOT_RECEIVED')
        OR (from_state = 'DELIVERY_UNKNOWN' AND to_state = 'FAILED' AND actor_role = 'TENANT_ADMIN'
            AND outcome = 'RECONCILED_NOT_RECEIVED')
        OR (from_state = 'DELIVERY_UNKNOWN' AND to_state = 'DELIVERY_UNKNOWN' AND actor_role = 'TENANT_ADMIN'
            AND outcome = 'RECONCILE_INCONCLUSIVE')
        OR (from_state = 'FAILED' AND to_state = 'QUEUED' AND actor_role = 'TENANT_ADMIN'
            AND outcome = 'ADMIN_RETRY'))
);

CREATE INDEX payroll_salary_advance_handoff_event_latest
    ON payroll.salary_advance_handoff_event (tenant_id, handoff_id, sequence DESC);

-- One row per delivery attempt, written when the attempt finishes (never a payload, header or response body) -------
CREATE TABLE payroll.salary_advance_handoff_attempt (
    tenant_id      uuid        NOT NULL,
    handoff_id     uuid        NOT NULL,
    attempt_number smallint    NOT NULL,
    started_at     timestamptz NOT NULL,
    finished_at    timestamptz NOT NULL,
    outcome        text        NOT NULL,
    http_status    smallint,
    CONSTRAINT payroll_salary_advance_handoff_attempt_pkey PRIMARY KEY (tenant_id, handoff_id, attempt_number),
    CONSTRAINT payroll_salary_advance_handoff_attempt_handoff FOREIGN KEY (tenant_id, handoff_id)
        REFERENCES payroll.salary_advance_handoff (tenant_id, id),
    CONSTRAINT payroll_salary_advance_handoff_attempt_number CHECK (attempt_number BETWEEN 1 AND 24),
    CONSTRAINT payroll_salary_advance_handoff_attempt_time CHECK (finished_at >= started_at),
    CONSTRAINT payroll_salary_advance_handoff_attempt_outcome CHECK (outcome IN (
        'RECEIVED_ACKNOWLEDGED', 'REJECTED_BY_PARTNER', 'TRANSIENT_NOT_SENT', 'UNKNOWN_TIMEOUT', 'UNKNOWN_BAD_RESPONSE',
        'UNKNOWN_LEASE_EXPIRED', 'RETRIES_EXHAUSTED')),
    CONSTRAINT payroll_salary_advance_handoff_attempt_status CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599)
);

-- The receiver's acknowledgement: immutable, at most one per handoff ------------------------------------------------
CREATE TABLE payroll.salary_advance_handoff_ack (
    tenant_id         uuid        NOT NULL,
    handoff_id        uuid        NOT NULL,
    partner_reference text        NOT NULL,
    acknowledged_at   timestamptz NOT NULL,
    source            text        NOT NULL,
    CONSTRAINT payroll_salary_advance_handoff_ack_pkey PRIMARY KEY (tenant_id, handoff_id),
    CONSTRAINT payroll_salary_advance_handoff_ack_handoff FOREIGN KEY (tenant_id, handoff_id)
        REFERENCES payroll.salary_advance_handoff (tenant_id, id),
    CONSTRAINT payroll_salary_advance_handoff_ack_reference CHECK (partner_reference ~ '^[A-Za-z0-9._-]{8,64}$'),
    CONSTRAINT payroll_salary_advance_handoff_ack_source CHECK (source IN ('DELIVERY', 'RECONCILE'))
);

-- Signed callback replay evidence: the callback id is unique across the platform ------------------------------------
CREATE TABLE payroll.salary_advance_handoff_callback (
    callback_id   uuid        NOT NULL,
    tenant_id     uuid        NOT NULL,
    handoff_id    uuid        NOT NULL,
    review_state  text        NOT NULL,
    reason_code   text,
    issued_at     timestamptz NOT NULL,
    received_at   timestamptz NOT NULL,
    CONSTRAINT payroll_salary_advance_handoff_callback_pkey PRIMARY KEY (callback_id),
    CONSTRAINT payroll_salary_advance_handoff_callback_owner UNIQUE (tenant_id, handoff_id, callback_id),
    CONSTRAINT payroll_salary_advance_handoff_callback_handoff FOREIGN KEY (tenant_id, handoff_id)
        REFERENCES payroll.salary_advance_handoff (tenant_id, id),
    CONSTRAINT payroll_salary_advance_handoff_callback_state CHECK (
        review_state IN ('UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'DECLINED')),
    CONSTRAINT payroll_salary_advance_handoff_callback_reason CHECK (
        reason_code IS NULL OR reason_code IN (
            'ADDITIONAL_DOCUMENTS', 'CLARIFICATION', 'PARTNER_POLICY', 'SYNTHETIC_TEST_SCENARIO'))
);

-- Bounded partner-review evidence (callback-driven; RECEIVED is the initial review state, set with the receipt) ------
CREATE TABLE payroll.salary_advance_handoff_review_event (
    tenant_id         uuid        NOT NULL,
    handoff_id        uuid        NOT NULL,
    sequence          smallint    NOT NULL,
    from_review_state text        NOT NULL,
    to_review_state   text        NOT NULL,
    reason_code       text,
    callback_id       uuid        NOT NULL,
    occurred_at       timestamptz NOT NULL,
    CONSTRAINT payroll_salary_advance_handoff_review_event_pkey PRIMARY KEY (tenant_id, handoff_id, sequence),
    CONSTRAINT payroll_salary_advance_handoff_review_event_callback_unique UNIQUE (callback_id),
    CONSTRAINT payroll_salary_advance_handoff_review_event_handoff FOREIGN KEY (tenant_id, handoff_id)
        REFERENCES payroll.salary_advance_handoff (tenant_id, id),
    CONSTRAINT payroll_salary_advance_handoff_review_event_callback FOREIGN KEY (tenant_id, handoff_id, callback_id)
        REFERENCES payroll.salary_advance_handoff_callback (tenant_id, handoff_id, callback_id),
    CONSTRAINT payroll_salary_advance_handoff_review_event_sequence CHECK (sequence BETWEEN 1 AND 100),
    CONSTRAINT payroll_salary_advance_handoff_review_event_transition CHECK (
        (from_review_state = 'RECEIVED' AND to_review_state = 'UNDER_REVIEW')
        OR (from_review_state = 'UNDER_REVIEW' AND to_review_state IN ('MORE_INFORMATION_REQUIRED', 'DECLINED'))
        OR (from_review_state = 'MORE_INFORMATION_REQUIRED' AND to_review_state IN ('UNDER_REVIEW', 'DECLINED'))),
    CONSTRAINT payroll_salary_advance_handoff_review_event_reason CHECK (
        (to_review_state = 'UNDER_REVIEW' AND reason_code IS NULL)
        OR (to_review_state <> 'UNDER_REVIEW' AND reason_code IN (
            'ADDITIONAL_DOCUMENTS', 'CLARIFICATION', 'PARTNER_POLICY', 'SYNTHETIC_TEST_SCENARIO')))
);

-- Immutable evidence: no UPDATE, DELETE or TRUNCATE (the V52 function raises for every call) -----------------------
CREATE TRIGGER payroll_salary_advance_handoff_consent_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_handoff_consent
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_consent_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_handoff_consent
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_event_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_handoff_event
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_event_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_handoff_event
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_attempt_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_handoff_attempt
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_attempt_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_handoff_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_ack_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_handoff_ack
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_ack_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_handoff_ack
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_callback_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_handoff_callback
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_callback_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_handoff_callback
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_review_event_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_handoff_review_event
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_review_event_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_handoff_review_event
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_no_delete BEFORE DELETE ON payroll.salary_advance_handoff
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_no_truncate BEFORE TRUNCATE ON payroll.salary_advance_handoff
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();

-- Handoff header guard --------------------------------------------------------------------------------------------
-- INSERT proves the source request and the actors; UPDATE moves only the mutable columns along the closed lifecycle
-- and keeps every counter equal to the evidence that justifies it.
CREATE FUNCTION payroll.salary_advance_handoff_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    req     payroll.salary_advance_request%ROWTYPE;
    decider text;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (to_jsonb(NEW) - 'state' - 'review_state' - 'review_reason' - 'row_version' - 'attempt_count'
                - 'reconcile_count' - 'admin_retry_count' - 'next_attempt_at' - 'lease_until' - 'updated_at')
            IS DISTINCT FROM
           (to_jsonb(OLD) - 'state' - 'review_state' - 'review_reason' - 'row_version' - 'attempt_count'
                - 'reconcile_count' - 'admin_retry_count' - 'next_attempt_at' - 'lease_until' - 'updated_at')
            OR NEW.row_version <> OLD.row_version + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_transition',
                MESSAGE = 'a handoff only moves its status columns, one version at a time';
        END IF;
        IF OLD.state = NEW.state THEN
            -- Same-state updates: an inconclusive reconcile, or a bounded partner-review step after receipt.
            IF NOT ((OLD.state = 'DELIVERY_UNKNOWN' AND NEW.reconcile_count = OLD.reconcile_count + 1
                        AND NEW.attempt_count = OLD.attempt_count AND NEW.admin_retry_count = OLD.admin_retry_count
                        AND NEW.review_state IS NULL)
                    OR (OLD.state = 'RECEIVED' AND NEW.reconcile_count = OLD.reconcile_count
                        AND NEW.attempt_count = OLD.attempt_count AND NEW.admin_retry_count = OLD.admin_retry_count
                        AND ((OLD.review_state = 'RECEIVED' AND NEW.review_state = 'UNDER_REVIEW')
                          OR (OLD.review_state = 'UNDER_REVIEW'
                              AND NEW.review_state IN ('MORE_INFORMATION_REQUIRED', 'DECLINED'))
                          OR (OLD.review_state = 'MORE_INFORMATION_REQUIRED'
                              AND NEW.review_state IN ('UNDER_REVIEW', 'DECLINED'))))) THEN
                RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_transition',
                    MESSAGE = 'the handoff does not follow the closed lifecycle';
            END IF;
            RETURN NEW;
        END IF;
        IF NOT ((OLD.state = 'QUEUED' AND NEW.state IN ('SENDING', 'WITHDRAWN'))
                OR (OLD.state = 'SENDING' AND NEW.state IN ('RECEIVED', 'QUEUED', 'DELIVERY_UNKNOWN', 'FAILED'))
                OR (OLD.state = 'DELIVERY_UNKNOWN' AND NEW.state IN ('RECEIVED', 'QUEUED', 'FAILED'))
                OR (OLD.state = 'FAILED' AND NEW.state = 'QUEUED'))
            OR NEW.attempt_count <> OLD.attempt_count + (CASE WHEN NEW.state = 'SENDING' THEN 1 ELSE 0 END)
            OR NEW.admin_retry_count <> OLD.admin_retry_count
                + (CASE WHEN OLD.state = 'FAILED' AND NEW.state = 'QUEUED' THEN 1 ELSE 0 END)
            OR NEW.reconcile_count <> OLD.reconcile_count
                + (CASE WHEN OLD.state = 'DELIVERY_UNKNOWN' THEN 1 ELSE 0 END)
            OR (NEW.state = 'RECEIVED' AND (NEW.review_state IS DISTINCT FROM 'RECEIVED' OR NEW.review_reason IS NOT NULL)) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_transition',
                MESSAGE = 'the handoff does not follow the closed lifecycle';
        END IF;
        RETURN NEW;
    END IF;

    -- INSERT -------------------------------------------------------------------------------------------------
    IF NEW.state <> 'QUEUED' OR NEW.row_version <> 1 OR NEW.attempt_count <> 0 OR NEW.reconcile_count <> 0
        OR NEW.admin_retry_count <> 0 OR NEW.review_state IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_transition',
            MESSAGE = 'a handoff is recorded QUEUED with its two authorisation events';
    END IF;
    SELECT * INTO req FROM payroll.salary_advance_request r
     WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.request_id FOR UPDATE;
    IF NOT FOUND OR req.status <> 'EMPLOYER_CONFIRMED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_source',
            MESSAGE = 'a handoff starts only from an employer-confirmed request';
    END IF;
    IF req.employee_id <> NEW.employee_id OR req.legal_entity_id <> NEW.legal_entity_id
        OR req.partner_code <> NEW.partner_code THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_tenant',
            MESSAGE = 'the handoff does not match the request it is built from';
    END IF;
    -- The authorising subject is the employee's own active linked membership.
    IF NOT EXISTS (
            SELECT 1 FROM identity.employee_access_link l
              JOIN identity.tenant_membership m ON m.tenant_id = l.tenant_id AND m.id = l.membership_id
             WHERE l.tenant_id = NEW.tenant_id AND l.employee_id = NEW.employee_id
               AND l.unlinked_at IS NULL AND m.subject = NEW.authorized_by) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_owner',
            MESSAGE = 'a handoff is authorised by the employee''s own linked subject';
    END IF;
    -- Separation of actors: the confirming reviewer never authorises the employee's consent.
    SELECT d.decided_by INTO decider FROM payroll.salary_advance_employer_decision d
     WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.request_id AND d.decision = 'CONFIRMED';
    IF decider IS NULL OR decider = NEW.authorized_by THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_actor',
            MESSAGE = 'the authorising employee is never the confirming reviewer';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_handoff_guard BEFORE INSERT OR UPDATE ON payroll.salary_advance_handoff
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_guard();

-- Consent guard: bound to this handoff, request, subject and payload digest, at creation only ------------------------
CREATE FUNCTION payroll.salary_advance_handoff_consent_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    h payroll.salary_advance_handoff%ROWTYPE;
BEGIN
    SELECT * INTO h FROM payroll.salary_advance_handoff x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.handoff_id FOR UPDATE;
    IF NOT FOUND OR h.row_version <> 1 OR h.state <> 'QUEUED' OR h.request_id <> NEW.request_id
        OR h.authorized_by <> NEW.consented_by OR h.payload_sha256 <> NEW.payload_sha256 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_consent_binding',
            MESSAGE = 'consent evidence is bound to the handoff, subject and payload at authorisation';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_handoff_consent_guard BEFORE INSERT ON payroll.salary_advance_handoff_consent
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_consent_guard();

-- Event guard: legal chain, order and actors; the handoff row is locked first so concurrent commands serialise -------
CREATE FUNCTION payroll.salary_advance_handoff_event_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    h             payroll.salary_advance_handoff%ROWTYPE;
    last_sequence integer;
    last_state    text;
    last_at       timestamptz;
BEGIN
    SELECT * INTO h FROM payroll.salary_advance_handoff x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.handoff_id FOR UPDATE;
    SELECT e.sequence, e.to_state, e.occurred_at INTO last_sequence, last_state, last_at
      FROM payroll.salary_advance_handoff_event e
     WHERE e.tenant_id = NEW.tenant_id AND e.handoff_id = NEW.handoff_id
     ORDER BY e.sequence DESC LIMIT 1;
    IF NEW.sequence <> coalesce(last_sequence, 0) + 1
        OR NEW.from_state IS DISTINCT FROM last_state
        OR (last_at IS NOT NULL AND NEW.occurred_at < last_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_event_transition',
            MESSAGE = 'the handoff event does not follow the handoff history';
    END IF;
    -- Employee events are the employee's own subject; administrators are never that subject (actor separation).
    IF NEW.actor_role = 'EMPLOYEE' AND NEW.actor_subject <> h.authorized_by THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_actor',
            MESSAGE = 'employee events carry the employee''s own subject';
    END IF;
    IF NEW.actor_role = 'TENANT_ADMIN' AND NEW.actor_subject = h.authorized_by THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_actor',
            MESSAGE = 'an administrator event is never the employee''s subject';
    END IF;
    -- A reconcile is bounded and a retry is bounded: the counters are verified against these rows at commit.
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_handoff_event_guard BEFORE INSERT ON payroll.salary_advance_handoff_event
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_event_guard();

-- Attempt guard: an attempt finishes only while the handoff is SENDING, numbered by the claim ------------------------
CREATE FUNCTION payroll.salary_advance_handoff_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    h payroll.salary_advance_handoff%ROWTYPE;
BEGIN
    SELECT * INTO h FROM payroll.salary_advance_handoff x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.handoff_id FOR UPDATE;
    IF NOT FOUND OR h.state <> 'SENDING' OR h.attempt_count <> NEW.attempt_number THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_attempt_state',
            MESSAGE = 'an attempt finishes only for the claimed SENDING handoff';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_handoff_attempt_guard BEFORE INSERT ON payroll.salary_advance_handoff_attempt
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_attempt_guard();

-- Acknowledgement guard: recorded while the outcome is open (SENDING or DELIVERY_UNKNOWN); never after a withdrawal --
CREATE FUNCTION payroll.salary_advance_handoff_ack_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    current_state text;
BEGIN
    SELECT x.state INTO current_state FROM payroll.salary_advance_handoff x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.handoff_id FOR UPDATE;
    IF current_state IS NULL OR current_state NOT IN ('SENDING', 'DELIVERY_UNKNOWN')
        OR (NEW.source = 'DELIVERY' AND current_state <> 'SENDING')
        OR (NEW.source = 'RECONCILE' AND current_state <> 'DELIVERY_UNKNOWN') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_ack_state',
            MESSAGE = 'an acknowledgement applies only to an open delivery outcome';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_handoff_ack_guard BEFORE INSERT ON payroll.salary_advance_handoff_ack
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_ack_guard();

-- Callback and review guards: only after receipt, one legal step at a time --------------------------------------
CREATE FUNCTION payroll.salary_advance_handoff_callback_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    current_state text;
BEGIN
    SELECT x.state INTO current_state FROM payroll.salary_advance_handoff x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.handoff_id FOR UPDATE;
    IF current_state IS DISTINCT FROM 'RECEIVED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_callback_state',
            MESSAGE = 'a partner callback applies only to a received handoff';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_handoff_callback_guard BEFORE INSERT ON payroll.salary_advance_handoff_callback
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_callback_guard();

CREATE FUNCTION payroll.salary_advance_handoff_review_event_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    h             payroll.salary_advance_handoff%ROWTYPE;
    last_sequence integer;
    last_review   text;
    cb            payroll.salary_advance_handoff_callback%ROWTYPE;
BEGIN
    SELECT * INTO h FROM payroll.salary_advance_handoff x
     WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.handoff_id FOR UPDATE;
    SELECT e.sequence, e.to_review_state INTO last_sequence, last_review
      FROM payroll.salary_advance_handoff_review_event e
     WHERE e.tenant_id = NEW.tenant_id AND e.handoff_id = NEW.handoff_id ORDER BY e.sequence DESC LIMIT 1;
    SELECT * INTO cb FROM payroll.salary_advance_handoff_callback c
     WHERE c.callback_id = NEW.callback_id AND c.tenant_id = NEW.tenant_id AND c.handoff_id = NEW.handoff_id;
    IF h.state <> 'RECEIVED'
        OR NEW.sequence <> coalesce(last_sequence, 0) + 1
        OR NEW.from_review_state <> coalesce(last_review, 'RECEIVED')
        OR cb.callback_id IS NULL OR cb.review_state <> NEW.to_review_state
        OR cb.reason_code IS DISTINCT FROM NEW.reason_code THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_review_event_transition',
            MESSAGE = 'the review event does not follow the received handoff and its signed callback';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_handoff_review_event_guard
    BEFORE INSERT ON payroll.salary_advance_handoff_review_event
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_review_event_guard();

-- MVP-120A bridge: a request with a live handoff is never withdrawn through the 120A route ------------------------
CREATE FUNCTION payroll.salary_advance_request_handoff_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF NEW.status = 'WITHDRAWN' AND OLD.status = 'EMPLOYER_CONFIRMED' AND EXISTS (
            SELECT 1 FROM payroll.salary_advance_handoff h
             WHERE h.tenant_id = NEW.tenant_id AND h.request_id = NEW.id AND h.state <> 'WITHDRAWN') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_request_withdraw',
            MESSAGE = 'a request with a live partner handoff is withdrawn only through its handoff';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_request_handoff_guard BEFORE UPDATE ON payroll.salary_advance_request
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_request_handoff_guard();

-- Deferred completeness: at commit, every touched handoff is exactly explained by its evidence -----------------------
CREATE FUNCTION payroll.salary_advance_handoff_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    hid          uuid;
    h            payroll.salary_advance_handoff%ROWTYPE;
    req_status   text;
    event_count  integer;
    review_count integer;
    last_state   text;
    last_review  text;
    sending_in   integer;
    sending_out  integer;
    unknown_out  integer;
    retries      integer;
    attempts     integer;
    callbacks    integer;
BEGIN
    hid := (to_jsonb(NEW) ->> (CASE TG_TABLE_NAME WHEN 'salary_advance_handoff' THEN 'id' ELSE 'handoff_id' END))::uuid;
    SELECT * INTO h FROM payroll.salary_advance_handoff x WHERE x.tenant_id = NEW.tenant_id AND x.id = hid;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT count(*), count(*) FILTER (WHERE to_state = 'SENDING'), count(*) FILTER (WHERE from_state = 'SENDING'),
           count(*) FILTER (WHERE from_state = 'DELIVERY_UNKNOWN'), count(*) FILTER (WHERE outcome = 'ADMIN_RETRY')
      INTO event_count, sending_in, sending_out, unknown_out, retries
      FROM payroll.salary_advance_handoff_event e WHERE e.tenant_id = h.tenant_id AND e.handoff_id = h.id;
    SELECT e.to_state INTO last_state FROM payroll.salary_advance_handoff_event e
     WHERE e.tenant_id = h.tenant_id AND e.handoff_id = h.id ORDER BY e.sequence DESC LIMIT 1;
    SELECT count(*) INTO review_count FROM payroll.salary_advance_handoff_review_event r
     WHERE r.tenant_id = h.tenant_id AND r.handoff_id = h.id;
    SELECT r.to_review_state INTO last_review FROM payroll.salary_advance_handoff_review_event r
     WHERE r.tenant_id = h.tenant_id AND r.handoff_id = h.id ORDER BY r.sequence DESC LIMIT 1;
    SELECT count(*) INTO attempts FROM payroll.salary_advance_handoff_attempt a
     WHERE a.tenant_id = h.tenant_id AND a.handoff_id = h.id;
    SELECT count(*) INTO callbacks FROM payroll.salary_advance_handoff_callback c
     WHERE c.tenant_id = h.tenant_id AND c.handoff_id = h.id;
    SELECT r.status INTO req_status FROM payroll.salary_advance_request r
     WHERE r.tenant_id = h.tenant_id AND r.id = h.request_id;

    IF NOT EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_consent c
                    WHERE c.tenant_id = h.tenant_id AND c.handoff_id = h.id)
        OR event_count < 2 OR last_state IS DISTINCT FROM h.state
        OR h.row_version <> event_count + review_count - 1
        OR h.attempt_count <> sending_in OR attempts <> sending_out
        OR h.reconcile_count <> unknown_out OR h.admin_retry_count <> retries
        OR callbacks <> review_count
        OR (h.state = 'RECEIVED') <> EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_ack k
                                              WHERE k.tenant_id = h.tenant_id AND k.handoff_id = h.id)
        OR (h.state = 'RECEIVED' AND h.review_state IS DISTINCT FROM coalesce(last_review, 'RECEIVED'))
        OR (h.state = 'WITHDRAWN' AND req_status IS DISTINCT FROM 'WITHDRAWN')
        OR (h.state <> 'WITHDRAWN' AND req_status IS DISTINCT FROM 'EMPLOYER_CONFIRMED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_handoff_incomplete',
            MESSAGE = 'a handoff is committed only with the exact evidence that explains its state';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_salary_advance_handoff_complete AFTER INSERT OR UPDATE
    ON payroll.salary_advance_handoff DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_complete();
CREATE CONSTRAINT TRIGGER payroll_salary_advance_handoff_consent_complete AFTER INSERT
    ON payroll.salary_advance_handoff_consent DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_complete();
CREATE CONSTRAINT TRIGGER payroll_salary_advance_handoff_event_complete AFTER INSERT
    ON payroll.salary_advance_handoff_event DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_complete();
CREATE CONSTRAINT TRIGGER payroll_salary_advance_handoff_attempt_complete AFTER INSERT
    ON payroll.salary_advance_handoff_attempt DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_complete();
CREATE CONSTRAINT TRIGGER payroll_salary_advance_handoff_ack_complete AFTER INSERT
    ON payroll.salary_advance_handoff_ack DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_complete();
CREATE CONSTRAINT TRIGGER payroll_salary_advance_handoff_review_complete AFTER INSERT
    ON payroll.salary_advance_handoff_review_event DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_complete();
CREATE CONSTRAINT TRIGGER payroll_salary_advance_handoff_callback_complete AFTER INSERT
    ON payroll.salary_advance_handoff_callback DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_complete();
