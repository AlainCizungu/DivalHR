-- MVP-095C (Issue #190, ADR 0038): reconciliation of uncertain provider submissions WITHOUT resending payment.
-- A reconciliation is an administrator-initiated investigation of one payment submission that is DELIVERY_UNKNOWN or
-- MANUAL_INTERVENTION. It asks the controlled provider for the STATUS of the existing stable payment request ID under
-- one stable inquiry ID. It never creates a payment, a request ID or a new attempt of the submission, and its strongest
-- conclusion is CONFIRMED_ACCEPTED_FOR_PROCESSING, which never means paid or settled.
--
-- Classification: restricted financial data. NO destination, account, phone, holder, amount, signature, key, request
-- body, response body, provider reference or provider text is stored here. What is stored: closed states, allow-listed
-- outcome codes, counts, the ids that already exist on the submission, timestamps.
--
--   * payment_reconciliation: the CURRENT row, exactly one per submission forever (re-investigation is a later story).
--     Optimistic version; every change is a legal transition of the closed state machine with its evidence row recorded
--     in the same transaction.
--   * payment_reconciliation_inquiry: append-only evidence, one row per state change (sequence 1 = OPEN at creation).
--
-- State machine (closed): OPEN -> INQUIRY_IN_PROGRESS -> OPEN (transport failure / expired lease; the SAME inquiry id is
-- repeated) | CONFIRMED_ACCEPTED_FOR_PROCESSING | CONFIRMED_REJECTED | STILL_UNRESOLVED | MANUAL_REVIEW_REQUIRED ;
-- OPEN -> MANUAL_REVIEW_REQUIRED (attempts exhausted). The four conclusions have no outgoing transition.
--
-- Privileges: like V43 to V48 this migration issues no GRANT or REVOKE (the application role owns the schema).
-- Rollback: db/rollback/V49__rollback.sql refuses while any reconciliation or inquiry exists.

CREATE TABLE payroll.payment_reconciliation (
    tenant_id             uuid        NOT NULL,
    legal_entity_id       uuid        NOT NULL,
    id                    uuid        NOT NULL,
    submission_id         uuid        NOT NULL,
    batch_id              uuid        NOT NULL,
    request_id            uuid        NOT NULL,
    provider_code         text        NOT NULL,
    key_version           integer     NOT NULL,
    inquiry_id            uuid        NOT NULL,
    opened_from_state     text        NOT NULL,
    opened_from_version   integer     NOT NULL,
    state                 text        NOT NULL,
    version               integer     NOT NULL,
    attempt_count         integer     NOT NULL,
    outcome_code          text,
    next_attempt_at       timestamptz,
    lease_owner           text,
    lease_expires_at      timestamptz,
    understands_no_resend boolean     NOT NULL,
    understands_not_paid  boolean     NOT NULL,
    created_at            timestamptz NOT NULL,
    created_by_subject    text        NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT payment_reconciliation_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT payment_reconciliation_submission_fk FOREIGN KEY (tenant_id, submission_id)
        REFERENCES payroll.payment_submission (tenant_id, id),
    CONSTRAINT payment_reconciliation_one_per_submission UNIQUE (tenant_id, submission_id),
    CONSTRAINT payment_reconciliation_inquiry_id UNIQUE (tenant_id, inquiry_id),
    CONSTRAINT payment_reconciliation_state CHECK (state IN
        ('OPEN', 'INQUIRY_IN_PROGRESS', 'CONFIRMED_ACCEPTED_FOR_PROCESSING', 'CONFIRMED_REJECTED',
         'STILL_UNRESOLVED', 'MANUAL_REVIEW_REQUIRED')),
    CONSTRAINT payment_reconciliation_opened_from CHECK (opened_from_state IN ('DELIVERY_UNKNOWN', 'MANUAL_INTERVENTION')),
    CONSTRAINT payment_reconciliation_provider CHECK (provider_code ~ '^[A-Z][A-Z0-9-]{2,39}$'),
    CONSTRAINT payment_reconciliation_key_version CHECK (key_version BETWEEN 1 AND 1000),
    CONSTRAINT payment_reconciliation_version CHECK (version >= 1 AND opened_from_version >= 1),
    CONSTRAINT payment_reconciliation_attempts CHECK (attempt_count BETWEEN 0 AND 6),
    CONSTRAINT payment_reconciliation_outcome CHECK (outcome_code IS NULL OR outcome_code IN (
        'ACCEPTED_FOR_PROCESSING', 'REJECTED_INVALID_REQUEST', 'REJECTED_SIGNATURE', 'REJECTED_UNSUPPORTED',
        'REJECTED_DUPLICATE_MISMATCH', 'REJECTED_LIMIT', 'REJECTED_OTHER', 'NOT_FOUND', 'PENDING', 'UNKNOWN',
        'BAD_RESPONSE', 'TRANSIENT_CONNECT', 'TRANSIENT_TLS', 'TRANSIENT_TIMEOUT', 'TRANSIENT_RATE_LIMITED',
        'TRANSIENT_SERVER', 'RETRIES_EXHAUSTED')),
    CONSTRAINT payment_reconciliation_lease_owner CHECK (
        lease_owner IS NULL OR (char_length(lease_owner) BETWEEN 1 AND 64 AND lease_owner !~ '[[:cntrl:]]')),
    CONSTRAINT payment_reconciliation_subject CHECK (char_length(created_by_subject) BETWEEN 1 AND 255),
    CONSTRAINT payment_reconciliation_confirmations CHECK (understands_no_resend AND understands_not_paid),
    -- Shape of every state.
    CONSTRAINT payment_reconciliation_state_shape CHECK (COALESCE(
        (state = 'OPEN' AND next_attempt_at IS NOT NULL AND lease_owner IS NULL AND lease_expires_at IS NULL
            AND (outcome_code IS NULL OR outcome_code LIKE 'TRANSIENT\_%'))
        OR (state = 'INQUIRY_IN_PROGRESS' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL
            AND next_attempt_at IS NULL AND outcome_code IS NULL AND attempt_count >= 1)
        OR (state = 'CONFIRMED_ACCEPTED_FOR_PROCESSING' AND outcome_code = 'ACCEPTED_FOR_PROCESSING'
            AND next_attempt_at IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL AND attempt_count >= 1)
        OR (state = 'CONFIRMED_REJECTED' AND outcome_code LIKE 'REJECTED\_%'
            AND next_attempt_at IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL AND attempt_count >= 1)
        OR (state = 'STILL_UNRESOLVED' AND outcome_code IN ('NOT_FOUND', 'PENDING', 'UNKNOWN', 'BAD_RESPONSE')
            AND next_attempt_at IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL AND attempt_count >= 1)
        OR (state = 'MANUAL_REVIEW_REQUIRED' AND outcome_code = 'RETRIES_EXHAUSTED'
            AND next_attempt_at IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL), FALSE))
);

-- Worker claim (due OPEN rows) and lease recovery (expired INQUIRY_IN_PROGRESS rows); the submission lookup and the
-- current read use the unique key (tenant_id, submission_id).
CREATE INDEX payment_reconciliation_due ON payroll.payment_reconciliation (next_attempt_at, tenant_id, id)
    WHERE state = 'OPEN';
CREATE INDEX payment_reconciliation_lease ON payroll.payment_reconciliation (lease_expires_at, tenant_id, id)
    WHERE state = 'INQUIRY_IN_PROGRESS';

CREATE TABLE payroll.payment_reconciliation_inquiry (
    tenant_id          uuid        NOT NULL,
    reconciliation_id  uuid        NOT NULL,
    seq                integer     NOT NULL,
    attempt_number     integer     NOT NULL,
    state              text        NOT NULL,
    outcome_code       text,
    authenticated      boolean     NOT NULL,
    occurred_at        timestamptz NOT NULL,
    CONSTRAINT payment_reconciliation_inquiry_pkey PRIMARY KEY (tenant_id, reconciliation_id, seq),
    CONSTRAINT payment_reconciliation_inquiry_fk FOREIGN KEY (tenant_id, reconciliation_id)
        REFERENCES payroll.payment_reconciliation (tenant_id, id),
    CONSTRAINT payment_reconciliation_inquiry_seq CHECK (seq >= 1),
    CONSTRAINT payment_reconciliation_inquiry_number CHECK (attempt_number BETWEEN 0 AND 6),
    CONSTRAINT payment_reconciliation_inquiry_state CHECK (state IN
        ('OPEN', 'INQUIRY_IN_PROGRESS', 'CONFIRMED_ACCEPTED_FOR_PROCESSING', 'CONFIRMED_REJECTED',
         'STILL_UNRESOLVED', 'MANUAL_REVIEW_REQUIRED')),
    CONSTRAINT payment_reconciliation_inquiry_outcome CHECK (outcome_code IS NULL OR outcome_code IN (
        'ACCEPTED_FOR_PROCESSING', 'REJECTED_INVALID_REQUEST', 'REJECTED_SIGNATURE', 'REJECTED_UNSUPPORTED',
        'REJECTED_DUPLICATE_MISMATCH', 'REJECTED_LIMIT', 'REJECTED_OTHER', 'NOT_FOUND', 'PENDING', 'UNKNOWN',
        'BAD_RESPONSE', 'TRANSIENT_CONNECT', 'TRANSIENT_TLS', 'TRANSIENT_TIMEOUT', 'TRANSIENT_RATE_LIMITED',
        'TRANSIENT_SERVER', 'RETRIES_EXHAUSTED')),
    CONSTRAINT payment_reconciliation_inquiry_creation CHECK (seq <> 1 OR (state = 'OPEN' AND attempt_number = 0)),
    -- A conclusion exists only with matching AUTHENTICATED evidence: an accepted or rejected conclusion needs a
    -- verified provider answer of the same kind; an unverified answer can only leave the investigation unresolved.
    CONSTRAINT payment_reconciliation_inquiry_evidence CHECK (COALESCE(
        (state = 'OPEN' AND (outcome_code IS NULL OR outcome_code LIKE 'TRANSIENT\_%') AND NOT authenticated)
        OR (state = 'INQUIRY_IN_PROGRESS' AND outcome_code IS NULL AND NOT authenticated AND attempt_number >= 1)
        OR (state = 'CONFIRMED_ACCEPTED_FOR_PROCESSING' AND outcome_code = 'ACCEPTED_FOR_PROCESSING' AND authenticated
            AND attempt_number >= 1)
        OR (state = 'CONFIRMED_REJECTED' AND outcome_code LIKE 'REJECTED\_%' AND authenticated
            AND attempt_number >= 1)
        OR (state = 'STILL_UNRESOLVED' AND attempt_number >= 1
            AND ((outcome_code IN ('NOT_FOUND', 'PENDING', 'UNKNOWN') AND authenticated)
                 OR (outcome_code = 'BAD_RESPONSE' AND NOT authenticated)))
        OR (state = 'MANUAL_REVIEW_REQUIRED' AND outcome_code = 'RETRIES_EXHAUSTED' AND NOT authenticated), FALSE))
);

-- ---------------------------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.payment_reconciliation_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_immutable',
        MESSAGE = 'payment reconciliation evidence is append-only: it is never deleted, truncated or rewritten';
END
$fn$;

CREATE FUNCTION payroll.payment_reconciliation_transition_ok(old_state text, new_state text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS
$fn$
SELECT (old_state, new_state) IN (
    ('OPEN', 'INQUIRY_IN_PROGRESS'),
    ('OPEN', 'MANUAL_REVIEW_REQUIRED'),
    ('INQUIRY_IN_PROGRESS', 'OPEN'),
    ('INQUIRY_IN_PROGRESS', 'CONFIRMED_ACCEPTED_FOR_PROCESSING'),
    ('INQUIRY_IN_PROGRESS', 'CONFIRMED_REJECTED'),
    ('INQUIRY_IN_PROGRESS', 'STILL_UNRESOLVED'),
    ('INQUIRY_IN_PROGRESS', 'MANUAL_REVIEW_REQUIRED'))
$fn$;

CREATE FUNCTION payroll.payment_reconciliation_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    sub payroll.payment_submission%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'payroll.payment-reconciliation:' || NEW.tenant_id::text || ':' || NEW.submission_id::text, 0));
        SELECT * INTO sub FROM payroll.payment_submission
            WHERE tenant_id = NEW.tenant_id AND id = NEW.submission_id FOR SHARE;
        IF sub.id IS NULL THEN
            RETURN NEW; -- a missing submission is refused by its foreign key
        END IF;
        IF sub.state NOT IN ('DELIVERY_UNKNOWN', 'MANUAL_INTERVENTION')
            OR NEW.opened_from_state <> sub.state OR NEW.opened_from_version <> sub.version THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_submission_state',
                MESSAGE = 'a reconciliation is opened only for a DELIVERY_UNKNOWN or MANUAL_INTERVENTION submission';
        END IF;
        IF sub.legal_entity_id <> NEW.legal_entity_id OR sub.batch_id <> NEW.batch_id
            OR sub.request_id <> NEW.request_id OR sub.provider_code <> NEW.provider_code
            OR sub.key_version <> NEW.key_version THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_submission_exact',
                MESSAGE = 'a reconciliation uses its submission''s payment request ID, provider and key version exactly';
        END IF;
        IF NEW.state <> 'OPEN' OR NEW.version <> 1 OR NEW.attempt_count <> 0 OR NEW.outcome_code IS NOT NULL
            OR NEW.created_at <> NEW.updated_at OR NEW.next_attempt_at IS NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_creation',
                MESSAGE = 'a reconciliation is created OPEN at version 1 with no inquiry attempt';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: a legal transition at the next version, nothing else about the reconciliation changes.
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.legal_entity_id <> OLD.legal_entity_id OR NEW.id <> OLD.id
        OR NEW.submission_id <> OLD.submission_id OR NEW.batch_id <> OLD.batch_id
        OR NEW.request_id <> OLD.request_id OR NEW.provider_code <> OLD.provider_code
        OR NEW.key_version <> OLD.key_version OR NEW.inquiry_id <> OLD.inquiry_id
        OR NEW.opened_from_state <> OLD.opened_from_state OR NEW.opened_from_version <> OLD.opened_from_version
        OR NEW.understands_no_resend <> OLD.understands_no_resend
        OR NEW.understands_not_paid <> OLD.understands_not_paid
        OR NEW.created_at <> OLD.created_at OR NEW.created_by_subject <> OLD.created_by_subject THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_lifecycle_only',
            MESSAGE = 'a reconciliation changes only its state, outcome, attempt count, lease and schedule';
    END IF;
    IF NOT payroll.payment_reconciliation_transition_ok(OLD.state, NEW.state) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_transition',
            MESSAGE = 'illegal payment reconciliation state transition';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at
        OR NEW.attempt_count < OLD.attempt_count OR NEW.attempt_count > OLD.attempt_count + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_version_order',
            MESSAGE = 'a reconciliation moves to its next version, forward in time, one attempt at a time';
    END IF;
    IF (OLD.state = 'OPEN' AND NEW.state = 'INQUIRY_IN_PROGRESS' AND NEW.attempt_count <> OLD.attempt_count + 1)
        OR (NOT (OLD.state = 'OPEN' AND NEW.state = 'INQUIRY_IN_PROGRESS') AND NEW.attempt_count <> OLD.attempt_count) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_attempt_count',
            MESSAGE = 'an inquiry attempt is counted exactly when a worker claims the reconciliation';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payment_reconciliation_guard BEFORE INSERT OR UPDATE ON payroll.payment_reconciliation
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_reconciliation_guard();
CREATE TRIGGER payment_reconciliation_no_delete BEFORE DELETE ON payroll.payment_reconciliation
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_reconciliation_immutable();
CREATE TRIGGER payment_reconciliation_no_truncate BEFORE TRUNCATE ON payroll.payment_reconciliation
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payment_reconciliation_immutable();

-- Inquiry evidence: append-only, the next sequence, a legal transition from the previous event.
CREATE FUNCTION payroll.payment_reconciliation_inquiry_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    last payroll.payment_reconciliation_inquiry%ROWTYPE;
BEGIN
    SELECT * INTO last FROM payroll.payment_reconciliation_inquiry
        WHERE tenant_id = NEW.tenant_id AND reconciliation_id = NEW.reconciliation_id ORDER BY seq DESC LIMIT 1;
    IF NEW.seq <> coalesce(last.seq, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_inquiry_order',
            MESSAGE = 'payment reconciliation evidence is appended as the next sequence';
    END IF;
    IF last.seq IS NOT NULL THEN
        IF NOT payroll.payment_reconciliation_transition_ok(last.state, NEW.state) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_inquiry_transition',
                MESSAGE = 'illegal payment reconciliation state transition in the evidence';
        END IF;
        IF NEW.occurred_at < last.occurred_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_inquiry_time',
                MESSAGE = 'payment reconciliation evidence moves forward in time';
        END IF;
        IF NEW.attempt_number < last.attempt_number OR NEW.attempt_number > last.attempt_number + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_inquiry_number_order',
                MESSAGE = 'payment reconciliation inquiry attempts are numbered in order';
        END IF;
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payment_reconciliation_inquiry_guard BEFORE INSERT ON payroll.payment_reconciliation_inquiry
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_reconciliation_inquiry_guard();
CREATE TRIGGER payment_reconciliation_inquiry_immutable BEFORE UPDATE OR DELETE ON payroll.payment_reconciliation_inquiry
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_reconciliation_immutable();
CREATE TRIGGER payment_reconciliation_inquiry_no_truncate BEFORE TRUNCATE ON payroll.payment_reconciliation_inquiry
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payment_reconciliation_immutable();

-- Deferred exactness: the current row always equals the latest evidence row, and every reconciliation has its opening
-- evidence.
CREATE FUNCTION payroll.payment_reconciliation_check(p_tenant uuid, p_reconciliation uuid) RETURNS void
LANGUAGE plpgsql AS
$fn$
DECLARE
    rec  payroll.payment_reconciliation%ROWTYPE;
    last payroll.payment_reconciliation_inquiry%ROWTYPE;
BEGIN
    SELECT * INTO rec FROM payroll.payment_reconciliation WHERE tenant_id = p_tenant AND id = p_reconciliation;
    IF rec.id IS NULL THEN
        RETURN;
    END IF;
    SELECT * INTO last FROM payroll.payment_reconciliation_inquiry
        WHERE tenant_id = p_tenant AND reconciliation_id = p_reconciliation ORDER BY seq DESC LIMIT 1;
    IF last.seq IS NULL OR last.seq <> rec.version OR last.state <> rec.state
        OR last.attempt_number <> rec.attempt_count
        OR last.outcome_code IS DISTINCT FROM rec.outcome_code
        OR last.occurred_at <> rec.updated_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_reconciliation_evidence_exact',
            MESSAGE = 'a payment reconciliation equals its latest evidence row';
    END IF;
END
$fn$;

CREATE FUNCTION payroll.payment_reconciliation_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    PERFORM payroll.payment_reconciliation_check(NEW.tenant_id, NEW.id);
    RETURN NULL;
END
$fn$;

CREATE FUNCTION payroll.payment_reconciliation_inquiry_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    PERFORM payroll.payment_reconciliation_check(NEW.tenant_id, NEW.reconciliation_id);
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payment_reconciliation_exact AFTER INSERT OR UPDATE ON payroll.payment_reconciliation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.payment_reconciliation_exact();
CREATE CONSTRAINT TRIGGER payment_reconciliation_inquiry_exact AFTER INSERT ON payroll.payment_reconciliation_inquiry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.payment_reconciliation_inquiry_exact();

COMMENT ON TABLE payroll.payment_reconciliation IS
    'MVP-095C current state of the one investigation of an uncertain payment submission (a status inquiry by the stable request ID, never a resend; confirmed acceptance is NOT paid); restricted financial data';
COMMENT ON TABLE payroll.payment_reconciliation_inquiry IS
    'MVP-095C append-only state evidence of a payment reconciliation (allow-listed outcome codes and an authenticated flag only)';
