-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V54__salary_advance_partner_lifecycle.sql (MVP-120C, Issue #209). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V53 schema exactly, but it REFUSES once any
-- MVP-120C business or policy row exists: a retention policy, a retention stamp, a purge preview, item or record, a
-- CANCELLED_BY_PARTNER review state, a callback or status event that uses it, or a callback correlation id. None of that
-- is erased by a schema rollback; there is no override and no deletion. V54 changed no V52/V53 row.
--
-- It does NOT edit flyway_schema_history: removing the V54 row is a separate, controlled operator step, taken only when
-- the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE payroll.salary_advance_handoff, payroll.salary_advance_handoff_callback,
    payroll.salary_advance_handoff_review_event, payroll.salary_advance_handoff_attempt,
    payroll.salary_advance_evidence_retention_policy, payroll.salary_advance_handoff_retention,
    payroll.salary_advance_purge_preview, payroll.salary_advance_purge_preview_item,
    payroll.salary_advance_purge_record IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.salary_advance_evidence_retention_policy)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_retention)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_purge_preview)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_purge_preview_item)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_purge_record)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_handoff WHERE review_state = 'CANCELLED_BY_PARTNER')
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_callback
                    WHERE review_state = 'CANCELLED_BY_PARTNER' OR correlation_id IS NOT NULL)
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_review_event
                    WHERE to_review_state = 'CANCELLED_BY_PARTNER' OR from_review_state = 'CANCELLED_BY_PARTNER') THEN
        RAISE EXCEPTION 'V54 rollback refused: partner-lifecycle, retention or purge rows exist';
    END IF;
END
$$;

DROP TRIGGER payroll_salary_advance_handoff_attempt_purge ON payroll.salary_advance_handoff_attempt;
DROP TRIGGER payroll_salary_advance_handoff_attempt_no_update ON payroll.salary_advance_handoff_attempt;
CREATE TRIGGER payroll_salary_advance_handoff_attempt_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_handoff_attempt
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
DROP FUNCTION payroll.salary_advance_attempt_purge_guard();

DROP TRIGGER payroll_salary_advance_handoff_retention_stamp ON payroll.salary_advance_handoff;
DROP TABLE payroll.salary_advance_purge_record;
DROP TABLE payroll.salary_advance_purge_preview_item;
DROP TABLE payroll.salary_advance_purge_preview;
DROP TABLE payroll.salary_advance_handoff_retention;
DROP TABLE payroll.salary_advance_evidence_retention_policy;
DROP FUNCTION payroll.salary_advance_handoff_retention_stamp();
DROP FUNCTION payroll.salary_advance_retention_policy_guard();
DROP VIEW payroll.salary_advance_handoff_status_history;

ALTER TABLE payroll.salary_advance_handoff_review_event
    DROP CONSTRAINT payroll_salary_advance_handoff_review_event_transition;
ALTER TABLE payroll.salary_advance_handoff_review_event
    ADD CONSTRAINT payroll_salary_advance_handoff_review_event_transition CHECK (
        (from_review_state = 'RECEIVED' AND to_review_state = 'UNDER_REVIEW')
        OR (from_review_state = 'UNDER_REVIEW' AND to_review_state IN ('MORE_INFORMATION_REQUIRED', 'DECLINED'))
        OR (from_review_state = 'MORE_INFORMATION_REQUIRED' AND to_review_state IN ('UNDER_REVIEW', 'DECLINED')));
ALTER TABLE payroll.salary_advance_handoff_callback DROP CONSTRAINT payroll_salary_advance_handoff_callback_correlation;
ALTER TABLE payroll.salary_advance_handoff_callback DROP COLUMN correlation_id;
ALTER TABLE payroll.salary_advance_handoff_callback DROP CONSTRAINT payroll_salary_advance_handoff_callback_state;
ALTER TABLE payroll.salary_advance_handoff_callback ADD CONSTRAINT payroll_salary_advance_handoff_callback_state CHECK (
    review_state IN ('UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'DECLINED'));
ALTER TABLE payroll.salary_advance_handoff DROP CONSTRAINT payroll_salary_advance_handoff_review;
ALTER TABLE payroll.salary_advance_handoff ADD CONSTRAINT payroll_salary_advance_handoff_review CHECK (
    (state = 'RECEIVED') = (review_state IS NOT NULL)
    AND (review_state IS NULL
         OR review_state IN ('RECEIVED', 'UNDER_REVIEW', 'MORE_INFORMATION_REQUIRED', 'DECLINED')));

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
COMMIT;
