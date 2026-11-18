-- =============================================================================================
-- V54 (MVP-120C, Issue #209): complete the controlled salary-advance partner lifecycle.
--
-- ADR 0042 amendment (D120C-1 to D120C-16). SYNTHETIC-TEST partner only. Adds the terminal partner-review status
-- CANCELLED_BY_PARTNER, the correlation id of accepted callbacks, a read view of the immutable status history, and the
-- evidence-retention model: versioned organization policy, a prospective per-handoff retention stamp, and a previewed,
-- confirmed purge of delivery diagnostics only. There is still no approval, funding, disbursement or repayment status.
--
-- Everything from V52/V53 is preserved: no row is rewritten and no evidence is touched. What may ever be deleted is only
-- the per-attempt delivery diagnostics of a terminal handoff with no DELIVERY_UNKNOWN history, only after its stamped
-- retention date, only when named by a confirmed preview, and only through the guard below.
-- =============================================================================================

-- Closed review catalogue ---------------------------------------------------------------------------------------
ALTER TABLE payroll.salary_advance_handoff DROP CONSTRAINT payroll_salary_advance_handoff_review;
ALTER TABLE payroll.salary_advance_handoff ADD CONSTRAINT payroll_salary_advance_handoff_review CHECK (
    (state = 'RECEIVED') = (review_state IS NOT NULL)
    AND (review_state IS NULL
         OR review_state IN ('RECEIVED', 'UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'DECLINED',
                             'CANCELLED_BY_PARTNER')));

ALTER TABLE payroll.salary_advance_handoff_callback DROP CONSTRAINT payroll_salary_advance_handoff_callback_state;
ALTER TABLE payroll.salary_advance_handoff_callback ADD CONSTRAINT payroll_salary_advance_handoff_callback_state CHECK (
    review_state IN ('UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'DECLINED', 'CANCELLED_BY_PARTNER'));
ALTER TABLE payroll.salary_advance_handoff_callback ADD COLUMN correlation_id text;
ALTER TABLE payroll.salary_advance_handoff_callback ADD CONSTRAINT payroll_salary_advance_handoff_callback_correlation
    CHECK (correlation_id IS NULL OR char_length(correlation_id) BETWEEN 1 AND 64);

ALTER TABLE payroll.salary_advance_handoff_review_event
    DROP CONSTRAINT payroll_salary_advance_handoff_review_event_transition;
ALTER TABLE payroll.salary_advance_handoff_review_event
    ADD CONSTRAINT payroll_salary_advance_handoff_review_event_transition CHECK (
        (from_review_state = 'RECEIVED'
            AND to_review_state IN ('UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'DECLINED', 'CANCELLED_BY_PARTNER'))
        OR (from_review_state = 'UNDER_REVIEW'
            AND to_review_state IN ('MORE_INFORMATION_REQUIRED', 'DECLINED', 'CANCELLED_BY_PARTNER'))
        OR (from_review_state = 'MORE_INFORMATION_REQUIRED'
            AND to_review_state IN ('UNDER_REVIEW', 'DECLINED', 'CANCELLED_BY_PARTNER')));

-- The immutable status history as one safe read model (digest of the event id, never the raw id) -----------------------
CREATE VIEW payroll.salary_advance_handoff_status_history AS
SELECT e.tenant_id,
       h.request_id,
       e.handoff_id,
       h.partner_code,
       e.sequence                                                        AS receive_sequence,
       e.from_review_state                                               AS previous_status,
       e.to_review_state                                                 AS new_status,
       e.reason_code,
       encode(sha256(convert_to(lower(c.callback_id::text), 'UTF8')), 'hex') AS event_digest,
       c.issued_at                                                       AS partner_event_at,
       c.received_at,
       c.correlation_id
  FROM payroll.salary_advance_handoff_review_event e
  JOIN payroll.salary_advance_handoff h ON h.tenant_id = e.tenant_id AND h.id = e.handoff_id
  JOIN payroll.salary_advance_handoff_callback c
    ON c.tenant_id = e.tenant_id AND c.handoff_id = e.handoff_id AND c.callback_id = e.callback_id;

-- Evidence retention policy: versioned, immutable, disabled until an administrator configures one ------------------
CREATE TABLE payroll.salary_advance_evidence_retention_policy (
    tenant_id      uuid        NOT NULL,
    version        integer     NOT NULL,
    retention_days integer     NOT NULL,
    effective_at   timestamptz NOT NULL,
    configured_by  text        NOT NULL,
    created_at     timestamptz NOT NULL,
    CONSTRAINT payroll_salary_advance_retention_policy_pkey PRIMARY KEY (tenant_id, version),
    CONSTRAINT payroll_salary_advance_retention_policy_version CHECK (version BETWEEN 1 AND 1000),
    CONSTRAINT payroll_salary_advance_retention_policy_days CHECK (retention_days BETWEEN 30 AND 3650),
    CONSTRAINT payroll_salary_advance_retention_policy_by CHECK (char_length(configured_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE payroll.salary_advance_evidence_retention_policy IS
    'MVP-120C organization policy for how long delivery diagnostics of a terminal partner handoff are kept (Restricted). '
    'An operator choice between 30 and 3650 days, not a legal recommendation. No row means retention is disabled.';

CREATE FUNCTION payroll.salary_advance_retention_policy_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id::text, 120));
    SELECT max(p.version) INTO latest FROM payroll.salary_advance_evidence_retention_policy p
     WHERE p.tenant_id = NEW.tenant_id;
    IF NEW.version <> coalesce(latest, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_retention_policy_version',
            MESSAGE = 'a retention policy is a new version after the latest one';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_retention_policy_guard
    BEFORE INSERT ON payroll.salary_advance_evidence_retention_policy
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_retention_policy_guard();

-- Prospective retention stamp: one immutable row per handoff that became terminal while a policy was in force ---------
CREATE TABLE payroll.salary_advance_handoff_retention (
    tenant_id       uuid        NOT NULL,
    handoff_id      uuid        NOT NULL,
    policy_version  integer     NOT NULL,
    terminal_at     timestamptz NOT NULL,
    retention_until timestamptz NOT NULL,
    CONSTRAINT payroll_salary_advance_handoff_retention_pkey PRIMARY KEY (tenant_id, handoff_id),
    CONSTRAINT payroll_salary_advance_handoff_retention_handoff FOREIGN KEY (tenant_id, handoff_id)
        REFERENCES payroll.salary_advance_handoff (tenant_id, id),
    CONSTRAINT payroll_salary_advance_handoff_retention_policy FOREIGN KEY (tenant_id, policy_version)
        REFERENCES payroll.salary_advance_evidence_retention_policy (tenant_id, version),
    CONSTRAINT payroll_salary_advance_handoff_retention_after CHECK (retention_until > terminal_at)
);

CREATE INDEX payroll_salary_advance_handoff_retention_due
    ON payroll.salary_advance_handoff_retention (tenant_id, retention_until, handoff_id);

CREATE FUNCTION payroll.salary_advance_handoff_retention_stamp() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    p payroll.salary_advance_evidence_retention_policy%ROWTYPE;
BEGIN
    IF (NEW.state = 'WITHDRAWN' AND OLD.state IS DISTINCT FROM 'WITHDRAWN')
        OR (NEW.review_state IN ('DECLINED', 'CANCELLED_BY_PARTNER')
            AND OLD.review_state IS DISTINCT FROM NEW.review_state) THEN
        SELECT * INTO p FROM payroll.salary_advance_evidence_retention_policy x
         WHERE x.tenant_id = NEW.tenant_id ORDER BY x.version DESC LIMIT 1;
        IF FOUND THEN
            INSERT INTO payroll.salary_advance_handoff_retention
                (tenant_id, handoff_id, policy_version, terminal_at, retention_until)
            VALUES (NEW.tenant_id, NEW.id, p.version, NEW.updated_at,
                    NEW.updated_at + make_interval(days => p.retention_days));
        END IF;
    END IF;
    RETURN NULL;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_handoff_retention_stamp AFTER UPDATE ON payroll.salary_advance_handoff
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_handoff_retention_stamp();

-- Purge preview, its exact items and the confirmation record ------------------------------------------------------------
CREATE TABLE payroll.salary_advance_purge_preview (
    tenant_id      uuid        NOT NULL,
    id             uuid        NOT NULL,
    policy_version integer     NOT NULL,
    digest         char(64)    NOT NULL,
    handoff_count  integer     NOT NULL,
    attempt_count  integer     NOT NULL,
    created_by     text        NOT NULL,
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    CONSTRAINT payroll_salary_advance_purge_preview_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT payroll_salary_advance_purge_preview_policy FOREIGN KEY (tenant_id, policy_version)
        REFERENCES payroll.salary_advance_evidence_retention_policy (tenant_id, version),
    CONSTRAINT payroll_salary_advance_purge_preview_digest CHECK (digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_salary_advance_purge_preview_counts CHECK (
        handoff_count BETWEEN 0 AND 500 AND attempt_count BETWEEN 0 AND 12000),
    CONSTRAINT payroll_salary_advance_purge_preview_by CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT payroll_salary_advance_purge_preview_expiry CHECK (expires_at > created_at)
);

CREATE TABLE payroll.salary_advance_purge_preview_item (
    tenant_id     uuid     NOT NULL,
    preview_id    uuid     NOT NULL,
    handoff_id    uuid     NOT NULL,
    attempt_count smallint NOT NULL,
    CONSTRAINT payroll_salary_advance_purge_item_pkey PRIMARY KEY (tenant_id, preview_id, handoff_id),
    CONSTRAINT payroll_salary_advance_purge_item_preview FOREIGN KEY (tenant_id, preview_id)
        REFERENCES payroll.salary_advance_purge_preview (tenant_id, id),
    CONSTRAINT payroll_salary_advance_purge_item_retention FOREIGN KEY (tenant_id, handoff_id)
        REFERENCES payroll.salary_advance_handoff_retention (tenant_id, handoff_id),
    CONSTRAINT payroll_salary_advance_purge_item_count CHECK (attempt_count BETWEEN 1 AND 24)
);

CREATE TABLE payroll.salary_advance_purge_record (
    tenant_id     uuid        NOT NULL,
    preview_id    uuid        NOT NULL,
    confirmed_by  text        NOT NULL,
    confirmed_at  timestamptz NOT NULL,
    handoff_count integer     NOT NULL,
    attempt_count integer     NOT NULL,
    CONSTRAINT payroll_salary_advance_purge_record_pkey PRIMARY KEY (tenant_id, preview_id),
    CONSTRAINT payroll_salary_advance_purge_record_preview FOREIGN KEY (tenant_id, preview_id)
        REFERENCES payroll.salary_advance_purge_preview (tenant_id, id),
    CONSTRAINT payroll_salary_advance_purge_record_by CHECK (char_length(confirmed_by) BETWEEN 1 AND 255),
    CONSTRAINT payroll_salary_advance_purge_record_counts CHECK (handoff_count >= 0 AND attempt_count >= 0)
);

-- Every 120C evidence table is append-only -------------------------------------------------------------------------------
CREATE TRIGGER payroll_salary_advance_retention_policy_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_evidence_retention_policy
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_retention_policy_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_evidence_retention_policy
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_retention_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_handoff_retention
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_handoff_retention_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_handoff_retention
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_purge_preview_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_purge_preview
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_purge_preview_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_purge_preview
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_purge_item_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_purge_preview_item
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_purge_item_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_purge_preview_item
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_purge_record_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_purge_record
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_purge_record_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_purge_record
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();

-- Attempt rows: still never updated; deleted only by a confirmed, previewed, elapsed purge ---------------------------------
DROP TRIGGER payroll_salary_advance_handoff_attempt_immutable ON payroll.salary_advance_handoff_attempt;
CREATE TRIGGER payroll_salary_advance_handoff_attempt_no_update
    BEFORE UPDATE ON payroll.salary_advance_handoff_attempt
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();

CREATE FUNCTION payroll.salary_advance_attempt_purge_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    purge_id uuid;
BEGIN
    BEGIN
        purge_id := nullif(current_setting('divalhr.salary_advance_purge_id', true), '')::uuid;
    EXCEPTION WHEN OTHERS THEN
        purge_id := NULL;
    END;
    IF purge_id IS NULL
        OR NOT EXISTS (
            SELECT 1
              FROM payroll.salary_advance_purge_record r
              JOIN payroll.salary_advance_purge_preview_item i
                ON i.tenant_id = r.tenant_id AND i.preview_id = r.preview_id
              JOIN payroll.salary_advance_handoff_retention k
                ON k.tenant_id = i.tenant_id AND k.handoff_id = i.handoff_id
              JOIN payroll.salary_advance_handoff h ON h.tenant_id = k.tenant_id AND h.id = k.handoff_id
             WHERE r.preview_id = purge_id AND r.tenant_id = OLD.tenant_id AND i.handoff_id = OLD.handoff_id
               AND k.retention_until <= r.confirmed_at
               AND (h.state = 'WITHDRAWN' OR h.review_state IN ('DECLINED', 'CANCELLED_BY_PARTNER')))
        OR EXISTS (
            SELECT 1 FROM payroll.salary_advance_handoff_event e
             WHERE e.tenant_id = OLD.tenant_id AND e.handoff_id = OLD.handoff_id
               AND 'DELIVERY_UNKNOWN' IN (e.from_state, e.to_state)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_attempt_purge',
            MESSAGE = 'delivery diagnostics are deleted only by a confirmed purge of an eligible terminal handoff';
    END IF;
    RETURN OLD;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_handoff_attempt_purge
    BEFORE DELETE ON payroll.salary_advance_handoff_attempt
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_attempt_purge_guard();

-- Handoff guard: the widened, still closed review transitions (V53 body otherwise unchanged) ------------------------
CREATE OR REPLACE FUNCTION payroll.salary_advance_handoff_guard() RETURNS trigger LANGUAGE plpgsql AS
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
                        AND ((OLD.review_state = 'RECEIVED'
                              AND NEW.review_state IN ('UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'DECLINED',
                                                       'CANCELLED_BY_PARTNER'))
                          OR (OLD.review_state = 'UNDER_REVIEW'
                              AND NEW.review_state IN ('MORE_INFORMATION_REQUIRED', 'DECLINED',
                                                       'CANCELLED_BY_PARTNER'))
                          OR (OLD.review_state = 'MORE_INFORMATION_REQUIRED'
                              AND NEW.review_state IN ('UNDER_REVIEW', 'DECLINED', 'CANCELLED_BY_PARTNER'))))) THEN
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

-- Completeness: purged diagnostics are accounted for by the confirmed purge record (V53 body otherwise unchanged) ----
CREATE OR REPLACE FUNCTION payroll.salary_advance_handoff_complete() RETURNS trigger LANGUAGE plpgsql AS
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
    purged       integer;
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
    SELECT coalesce(sum(i.attempt_count), 0) INTO purged
      FROM payroll.salary_advance_purge_record r
      JOIN payroll.salary_advance_purge_preview_item i ON i.tenant_id = r.tenant_id AND i.preview_id = r.preview_id
     WHERE i.tenant_id = h.tenant_id AND i.handoff_id = h.id;
    SELECT count(*) INTO callbacks FROM payroll.salary_advance_handoff_callback c
     WHERE c.tenant_id = h.tenant_id AND c.handoff_id = h.id;
    SELECT r.status INTO req_status FROM payroll.salary_advance_request r
     WHERE r.tenant_id = h.tenant_id AND r.id = h.request_id;

    IF NOT EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_consent c
                    WHERE c.tenant_id = h.tenant_id AND c.handoff_id = h.id)
        OR event_count < 2 OR last_state IS DISTINCT FROM h.state
        OR h.row_version <> event_count + review_count - 1
        OR h.attempt_count <> sending_in OR attempts + purged <> sending_out
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
