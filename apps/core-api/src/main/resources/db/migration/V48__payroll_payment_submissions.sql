-- MVP-095B (Issue #187, ADR 0037): provider-neutral submission of a PREPARED payment batch to a controlled provider
-- connector. This migration records the HANDOFF only. Nothing here says, or can say, that an employee was paid, that
-- funds settled or that money moved: the strongest state is ACCEPTED_FOR_PROCESSING.
--
-- Classification: restricted financial data. NO destination plaintext, account, phone, holder, branch, signature, key,
-- request body or response body is ever stored here. The provider payload is built in memory at send time from the
-- sealed destinations (MVP-095A) and discarded. What is stored: closed states, an allow-listed outcome code, a bounded
-- provider reference matching a closed grammar, counts, the batch's own (already stored) digest, timestamps and ids.
--
--   * payment_submission: the CURRENT row of one submission (exactly one per batch, forever: resubmission and
--     resolution are later stories). Optimistic version; every change is a legal transition of the closed state
--     machine and has its evidence row recorded first in the same transaction.
--   * payment_submission_attempt: append-only evidence, one row per state change (sequence 1 = QUEUED at creation).
--
-- State machine (closed): QUEUED -> SENDING -> ACCEPTED_FOR_PROCESSING | REJECTED | DELIVERY_UNKNOWN | QUEUED (a
-- retryable failure before acceptance, attempt counted) ; QUEUED -> MANUAL_INTERVENTION (bounded handling ended) ;
-- SENDING -> MANUAL_INTERVENTION. ACCEPTED_FOR_PROCESSING, REJECTED, DELIVERY_UNKNOWN and MANUAL_INTERVENTION have no
-- outgoing transition in this story.
--
-- Guards (D95B-7): once a submission exists for a batch the batch can never be cancelled and its package can never be
-- voided; both are refused here so a bypass write fails closed. The submission never releases the void guard.
--
-- Privileges: like V43 to V47 this migration issues no GRANT or REVOKE (the application role owns the schema).
-- Rollback: db/rollback/V48__rollback.sql refuses while any submission or attempt exists.

CREATE TABLE payroll.payment_submission (
    tenant_id             uuid        NOT NULL,
    legal_entity_id       uuid        NOT NULL,
    id                    uuid        NOT NULL,
    batch_id              uuid        NOT NULL,
    package_id            uuid        NOT NULL,
    request_id            uuid        NOT NULL,
    provider_code         text        NOT NULL,
    key_version           integer     NOT NULL,
    state                 text        NOT NULL,
    version               integer     NOT NULL,
    batch_version         integer     NOT NULL,
    batch_digest          char(64)    NOT NULL,
    currency              text        NOT NULL,
    line_count            integer     NOT NULL,
    bank_count            integer     NOT NULL,
    mobile_count          integer     NOT NULL,
    manual_count          integer     NOT NULL,
    total_net_minor       bigint      NOT NULL,
    attempt_count         integer     NOT NULL,
    outcome_code          text,
    provider_reference    text,
    next_attempt_at       timestamptz,
    lease_owner           text,
    lease_expires_at      timestamptz,
    reviewed_provider_totals boolean  NOT NULL,
    understands_not_paid  boolean     NOT NULL,
    created_at            timestamptz NOT NULL,
    created_by_subject    text        NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT payment_submission_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT payment_submission_batch_fk FOREIGN KEY (tenant_id, legal_entity_id, batch_id)
        REFERENCES payroll.payment_batch (tenant_id, legal_entity_id, id),
    CONSTRAINT payment_submission_one_per_batch UNIQUE (tenant_id, batch_id),
    CONSTRAINT payment_submission_request_id UNIQUE (tenant_id, request_id),
    CONSTRAINT payment_submission_state CHECK (state IN
        ('QUEUED', 'SENDING', 'ACCEPTED_FOR_PROCESSING', 'REJECTED', 'DELIVERY_UNKNOWN', 'MANUAL_INTERVENTION')),
    CONSTRAINT payment_submission_provider CHECK (provider_code ~ '^[A-Z][A-Z0-9-]{2,39}$'),
    CONSTRAINT payment_submission_key_version CHECK (key_version BETWEEN 1 AND 1000),
    CONSTRAINT payment_submission_version CHECK (version >= 1),
    CONSTRAINT payment_submission_digest CHECK (batch_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payment_submission_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payment_submission_counts CHECK (
        line_count BETWEEN 1 AND 5000 AND bank_count >= 0 AND mobile_count >= 0 AND manual_count >= 0
        AND bank_count + mobile_count + manual_count = line_count AND total_net_minor >= 0),
    CONSTRAINT payment_submission_attempts CHECK (attempt_count BETWEEN 0 AND 6),
    CONSTRAINT payment_submission_outcome CHECK (outcome_code IS NULL OR outcome_code IN (
        'ACCEPTED', 'REJECTED_INVALID_REQUEST', 'REJECTED_SIGNATURE', 'REJECTED_UNSUPPORTED',
        'REJECTED_DUPLICATE_MISMATCH', 'REJECTED_LIMIT', 'REJECTED_OTHER',
        'TRANSIENT_CONNECT', 'TRANSIENT_TLS', 'TRANSIENT_RATE_LIMITED', 'TRANSIENT_SERVER',
        'UNKNOWN_TIMEOUT', 'UNKNOWN_CONNECTION_LOST', 'UNKNOWN_LEASE_EXPIRED', 'UNKNOWN_BAD_RESPONSE',
        'RETRIES_EXHAUSTED', 'NOT_SENT_UNREADABLE')),
    CONSTRAINT payment_submission_reference CHECK (
        provider_reference IS NULL OR provider_reference ~ '^[A-Z0-9][A-Z0-9._-]{5,63}$'),
    CONSTRAINT payment_submission_lease_owner CHECK (
        lease_owner IS NULL OR (char_length(lease_owner) BETWEEN 1 AND 64 AND lease_owner !~ '[[:cntrl:]]')),
    CONSTRAINT payment_submission_subject CHECK (char_length(created_by_subject) BETWEEN 1 AND 255),
    CONSTRAINT payment_submission_confirmations CHECK (reviewed_provider_totals AND understands_not_paid),
    -- Shape of every state.
    CONSTRAINT payment_submission_state_shape CHECK (
        (state = 'QUEUED' AND next_attempt_at IS NOT NULL AND lease_owner IS NULL AND lease_expires_at IS NULL
            AND provider_reference IS NULL AND (outcome_code IS NULL OR outcome_code LIKE 'TRANSIENT\_%'))
        OR (state = 'SENDING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL
            AND next_attempt_at IS NULL AND provider_reference IS NULL AND attempt_count >= 1)
        OR (state = 'ACCEPTED_FOR_PROCESSING' AND outcome_code = 'ACCEPTED' AND provider_reference IS NOT NULL
            AND next_attempt_at IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL AND attempt_count >= 1)
        OR (state = 'REJECTED' AND outcome_code LIKE 'REJECTED\_%' AND provider_reference IS NULL
            AND next_attempt_at IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL AND attempt_count >= 1)
        OR (state = 'DELIVERY_UNKNOWN' AND outcome_code LIKE 'UNKNOWN\_%' AND provider_reference IS NULL
            AND next_attempt_at IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL AND attempt_count >= 1)
        OR (state = 'MANUAL_INTERVENTION' AND outcome_code IS NOT NULL AND provider_reference IS NULL
            AND next_attempt_at IS NULL AND lease_owner IS NULL AND lease_expires_at IS NULL))
);

-- Worker claim (due QUEUED rows) and lease recovery (expired SENDING rows); tenant lookup by batch is the unique key.
CREATE INDEX payment_submission_due ON payroll.payment_submission (next_attempt_at, tenant_id, id)
    WHERE state = 'QUEUED';
CREATE INDEX payment_submission_lease ON payroll.payment_submission (lease_expires_at, tenant_id, id)
    WHERE state = 'SENDING';
CREATE INDEX payment_submission_state ON payroll.payment_submission (tenant_id, legal_entity_id, state, created_at DESC, id DESC);

CREATE TABLE payroll.payment_submission_attempt (
    tenant_id           uuid        NOT NULL,
    submission_id       uuid        NOT NULL,
    seq                 integer     NOT NULL,
    attempt_number      integer     NOT NULL,
    state               text        NOT NULL,
    outcome_code        text,
    provider_reference  text,
    occurred_at         timestamptz NOT NULL,
    CONSTRAINT payment_submission_attempt_pkey PRIMARY KEY (tenant_id, submission_id, seq),
    CONSTRAINT payment_submission_attempt_fk FOREIGN KEY (tenant_id, submission_id)
        REFERENCES payroll.payment_submission (tenant_id, id),
    CONSTRAINT payment_submission_attempt_seq CHECK (seq >= 1),
    CONSTRAINT payment_submission_attempt_number CHECK (attempt_number BETWEEN 0 AND 6),
    CONSTRAINT payment_submission_attempt_state CHECK (state IN
        ('QUEUED', 'SENDING', 'ACCEPTED_FOR_PROCESSING', 'REJECTED', 'DELIVERY_UNKNOWN', 'MANUAL_INTERVENTION')),
    CONSTRAINT payment_submission_attempt_outcome CHECK (outcome_code IS NULL OR outcome_code IN (
        'ACCEPTED', 'REJECTED_INVALID_REQUEST', 'REJECTED_SIGNATURE', 'REJECTED_UNSUPPORTED',
        'REJECTED_DUPLICATE_MISMATCH', 'REJECTED_LIMIT', 'REJECTED_OTHER',
        'TRANSIENT_CONNECT', 'TRANSIENT_TLS', 'TRANSIENT_RATE_LIMITED', 'TRANSIENT_SERVER',
        'UNKNOWN_TIMEOUT', 'UNKNOWN_CONNECTION_LOST', 'UNKNOWN_LEASE_EXPIRED', 'UNKNOWN_BAD_RESPONSE',
        'RETRIES_EXHAUSTED', 'NOT_SENT_UNREADABLE')),
    CONSTRAINT payment_submission_attempt_reference CHECK (
        provider_reference IS NULL OR provider_reference ~ '^[A-Z0-9][A-Z0-9._-]{5,63}$'),
    CONSTRAINT payment_submission_attempt_creation CHECK (seq <> 1 OR (state = 'QUEUED' AND attempt_number = 0))
);

-- ---------------------------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.payment_submission_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_immutable',
        MESSAGE = 'payment submission evidence is append-only: it is never deleted, truncated or rewritten';
END
$fn$;

-- The legal transitions of the closed state machine.
CREATE FUNCTION payroll.payment_submission_transition_ok(old_state text, new_state text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS
$fn$
SELECT (old_state, new_state) IN (
    ('QUEUED', 'SENDING'),
    ('QUEUED', 'MANUAL_INTERVENTION'),
    ('SENDING', 'QUEUED'),
    ('SENDING', 'ACCEPTED_FOR_PROCESSING'),
    ('SENDING', 'REJECTED'),
    ('SENDING', 'DELIVERY_UNKNOWN'),
    ('SENDING', 'MANUAL_INTERVENTION'))
$fn$;

CREATE FUNCTION payroll.payment_submission_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    batch payroll.payment_batch%ROWTYPE;
    pkg   payroll.payroll_package%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'payroll.payment-submission:' || NEW.tenant_id::text || ':' || NEW.batch_id::text, 0));
        SELECT * INTO batch FROM payroll.payment_batch
            WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.batch_id FOR SHARE;
        IF batch.id IS NULL THEN
            RETURN NEW; -- a missing batch is refused by its foreign key
        END IF;
        IF batch.status <> 'PREPARED' OR batch.package_id <> NEW.package_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_batch_state',
                MESSAGE = 'a payment submission is created only for a PREPARED payment batch of its package';
        END IF;
        IF batch.version <> NEW.batch_version OR batch.batch_digest <> NEW.batch_digest
            OR batch.currency <> NEW.currency OR batch.line_count <> NEW.line_count
            OR batch.bank_count <> NEW.bank_count OR batch.mobile_count <> NEW.mobile_count
            OR batch.manual_count <> NEW.manual_count OR batch.total_net_minor <> NEW.total_net_minor THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_batch_exact',
                MESSAGE = 'a payment submission copies its batch exactly: version, digest, currency, counts and total';
        END IF;
        SELECT * INTO pkg FROM payroll.payroll_package
            WHERE tenant_id = batch.tenant_id AND legal_entity_id = batch.legal_entity_id AND id = batch.package_id
            FOR SHARE;
        IF pkg.id IS NULL OR pkg.status <> 'APPROVED' OR pkg.version <> batch.package_version THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_package_state',
                MESSAGE = 'a payment submission requires the APPROVED, non-voided package of its batch';
        END IF;
        IF EXISTS (SELECT 1 FROM payroll.payroll_package p
                    WHERE p.tenant_id = pkg.tenant_id AND p.legal_entity_id = pkg.legal_entity_id
                      AND p.period_start = pkg.period_start AND p.currency = pkg.currency
                      AND p.version > pkg.version) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_package_latest',
                MESSAGE = 'a payment submission requires the latest package of its entity and month';
        END IF;
        IF NEW.state <> 'QUEUED' OR NEW.version <> 1 OR NEW.attempt_count <> 0 OR NEW.outcome_code IS NOT NULL
            OR NEW.created_at <> NEW.updated_at OR NEW.next_attempt_at IS NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_creation',
                MESSAGE = 'a payment submission is created QUEUED at version 1 with no attempt';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: a legal transition at the next version, nothing else about the submission changes.
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.legal_entity_id <> OLD.legal_entity_id OR NEW.id <> OLD.id
        OR NEW.batch_id <> OLD.batch_id OR NEW.package_id <> OLD.package_id OR NEW.request_id <> OLD.request_id
        OR NEW.provider_code <> OLD.provider_code OR NEW.key_version <> OLD.key_version
        OR NEW.batch_version <> OLD.batch_version OR NEW.batch_digest <> OLD.batch_digest
        OR NEW.currency <> OLD.currency OR NEW.line_count <> OLD.line_count
        OR NEW.bank_count <> OLD.bank_count OR NEW.mobile_count <> OLD.mobile_count
        OR NEW.manual_count <> OLD.manual_count OR NEW.total_net_minor <> OLD.total_net_minor
        OR NEW.reviewed_provider_totals <> OLD.reviewed_provider_totals
        OR NEW.understands_not_paid <> OLD.understands_not_paid
        OR NEW.created_at <> OLD.created_at OR NEW.created_by_subject <> OLD.created_by_subject THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_lifecycle_only',
            MESSAGE = 'a payment submission changes only its state, outcome, attempt count, lease and schedule';
    END IF;
    IF NOT payroll.payment_submission_transition_ok(OLD.state, NEW.state) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_transition',
            MESSAGE = 'illegal payment submission state transition';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at
        OR NEW.attempt_count < OLD.attempt_count OR NEW.attempt_count > OLD.attempt_count + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_version_order',
            MESSAGE = 'a payment submission moves to its next version, forward in time, one attempt at a time';
    END IF;
    IF (OLD.state = 'QUEUED' AND NEW.state = 'SENDING' AND NEW.attempt_count <> OLD.attempt_count + 1)
        OR (NOT (OLD.state = 'QUEUED' AND NEW.state = 'SENDING') AND NEW.attempt_count <> OLD.attempt_count) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_attempt_count',
            MESSAGE = 'an attempt is counted exactly when a worker claims the submission';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payment_submission_guard BEFORE INSERT OR UPDATE ON payroll.payment_submission
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_submission_guard();
CREATE TRIGGER payment_submission_no_delete BEFORE DELETE ON payroll.payment_submission
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_submission_immutable();
CREATE TRIGGER payment_submission_no_truncate BEFORE TRUNCATE ON payroll.payment_submission
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payment_submission_immutable();

-- Attempt evidence: append-only, the next sequence, a legal transition from the previous event.
CREATE FUNCTION payroll.payment_submission_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    last payroll.payment_submission_attempt%ROWTYPE;
BEGIN
    SELECT * INTO last FROM payroll.payment_submission_attempt
        WHERE tenant_id = NEW.tenant_id AND submission_id = NEW.submission_id ORDER BY seq DESC LIMIT 1;
    IF NEW.seq <> coalesce(last.seq, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_attempt_order',
            MESSAGE = 'payment submission evidence is appended as the next sequence';
    END IF;
    IF last.seq IS NOT NULL THEN
        IF NOT payroll.payment_submission_transition_ok(last.state, NEW.state) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_attempt_transition',
                MESSAGE = 'illegal payment submission state transition in the evidence';
        END IF;
        IF NEW.occurred_at < last.occurred_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_attempt_time',
                MESSAGE = 'payment submission evidence moves forward in time';
        END IF;
        IF NEW.attempt_number < last.attempt_number OR NEW.attempt_number > last.attempt_number + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_attempt_number_order',
                MESSAGE = 'payment submission attempts are numbered in order';
        END IF;
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payment_submission_attempt_guard BEFORE INSERT ON payroll.payment_submission_attempt
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_submission_attempt_guard();
CREATE TRIGGER payment_submission_attempt_immutable BEFORE UPDATE OR DELETE ON payroll.payment_submission_attempt
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_submission_immutable();
CREATE TRIGGER payment_submission_attempt_no_truncate BEFORE TRUNCATE ON payroll.payment_submission_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payment_submission_immutable();

-- Deferred exactness: the current row always equals the latest evidence row, and every submission has its
-- creation evidence.
CREATE FUNCTION payroll.payment_submission_check(p_tenant uuid, p_submission uuid) RETURNS void LANGUAGE plpgsql AS
$fn$
DECLARE
    sub  payroll.payment_submission%ROWTYPE;
    last payroll.payment_submission_attempt%ROWTYPE;
BEGIN
    SELECT * INTO sub FROM payroll.payment_submission WHERE tenant_id = p_tenant AND id = p_submission;
    IF sub.id IS NULL THEN
        RETURN;
    END IF;
    SELECT * INTO last FROM payroll.payment_submission_attempt
        WHERE tenant_id = p_tenant AND submission_id = p_submission ORDER BY seq DESC LIMIT 1;
    IF last.seq IS NULL OR last.seq <> sub.version OR last.state <> sub.state
        OR last.attempt_number <> sub.attempt_count
        OR last.outcome_code IS DISTINCT FROM sub.outcome_code
        OR last.provider_reference IS DISTINCT FROM sub.provider_reference
        OR last.occurred_at <> sub.updated_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_evidence_exact',
            MESSAGE = 'a payment submission equals its latest evidence row';
    END IF;
END
$fn$;

CREATE FUNCTION payroll.payment_submission_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    PERFORM payroll.payment_submission_check(NEW.tenant_id, NEW.id);
    RETURN NULL;
END
$fn$;

CREATE FUNCTION payroll.payment_submission_attempt_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    PERFORM payroll.payment_submission_check(NEW.tenant_id, NEW.submission_id);
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payment_submission_exact AFTER INSERT OR UPDATE ON payroll.payment_submission
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.payment_submission_exact();
CREATE CONSTRAINT TRIGGER payment_submission_attempt_exact AFTER INSERT ON payroll.payment_submission_attempt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.payment_submission_attempt_exact();

-- ---------------------------------------------------------------------------------------------
-- Batch and payroll guards (D95B-7): any submission, in any state, blocks cancelling the batch and voiding the
-- package. The V47 void guard is replaced by a version that also refuses on a submission.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.payment_submission_blocks_cancel() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_TABLE_NAME = 'payment_batch' THEN
        IF NEW.status = 'CANCELLED' AND EXISTS (
            SELECT 1 FROM payroll.payment_submission s WHERE s.tenant_id = OLD.tenant_id AND s.batch_id = OLD.id) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_blocks_batch_cancel',
                MESSAGE = 'a payment batch with a submission cannot be cancelled';
        END IF;
    ELSIF EXISTS (
        SELECT 1 FROM payroll.payment_submission s WHERE s.tenant_id = NEW.tenant_id AND s.batch_id = NEW.batch_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payment_submission_blocks_batch_cancel',
            MESSAGE = 'a payment batch with a submission cannot be cancelled';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payment_submission_blocks_cancel BEFORE UPDATE ON payroll.payment_batch
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_submission_blocks_cancel();
CREATE TRIGGER payment_submission_blocks_cancel_evidence BEFORE INSERT ON payroll.payment_batch_cancel
    FOR EACH ROW EXECUTE FUNCTION payroll.payment_submission_blocks_cancel();

CREATE OR REPLACE FUNCTION payroll.payment_batch_blocks_void() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF NEW.status = 'VOIDED' AND EXISTS (
        SELECT 1 FROM payroll.payment_batch b
         WHERE b.tenant_id = OLD.tenant_id AND b.package_id = OLD.id AND b.status = 'PREPARED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payment_batch_blocks_void',
            MESSAGE = 'a payroll package with a PREPARED payment batch cannot be voided; cancel the batch first';
    END IF;
    IF NEW.status = 'VOIDED' AND EXISTS (
        SELECT 1 FROM payroll.payment_submission s
         WHERE s.tenant_id = OLD.tenant_id AND s.package_id = OLD.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payment_submission_blocks_void',
            MESSAGE = 'a payroll package with a payment submission cannot be voided';
    END IF;
    RETURN NEW;
END
$fn$;

COMMENT ON TABLE payroll.payment_submission IS
    'MVP-095B current state of the one provider handoff of a PREPARED payment batch (accepted for processing is NOT paid; no destination, signature, body or provider text is stored); restricted financial data';
COMMENT ON TABLE payroll.payment_submission_attempt IS
    'MVP-095B append-only state evidence of a payment submission (allow-listed outcome codes and bounded references only)';
