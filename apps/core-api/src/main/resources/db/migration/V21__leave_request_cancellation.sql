-- MVP-041C (Issue #91): an employee cancels their own pending leave request, with immutable
-- cancellation evidence. No amendment, administrator or manager cancellation, balance, working-day,
-- holiday, schedule, payroll or notification.
--
-- Ownership: the people module owns the new table; only the bounded com.divalhr.core.people.leave
-- package reads or writes it. Keys reference people's own request row and the tenant root; there is
-- no cross-module reference.
--
-- Classification: Restricted HR. The cancellation reason is personal data; it never enters audit
-- metadata, events, logs or errors. The actor (cancelled_by) is never returned by the API.
--
-- Database authority (V21):
--   * a request moves exactly once, from PENDING to APPROVED, REJECTED or CANCELLED, and nothing
--     else about it ever changes (leave_request_transition, replaced);
--   * every request carries exactly one matching kind of terminal evidence (leave_request_decided,
--     replaced; deferred): PENDING has neither a decision nor a cancellation; APPROVED and REJECTED
--     have their matching decision and no cancellation; CANCELLED has its cancellation and no
--     decision. A cancellation commits only with its request CANCELLED
--     (leave_request_cancellation_consistent, deferred); a decision only with its request in the
--     decided state (V20's leave_request_decision_consistent, unchanged);
--   * cancellations are append-only, one per request (leave_request_cancellation_immutable,
--     _no_truncate, _request_unique) and use the decision-reason grammar version 1
--     (people.leave_reason_valid, shared with Core and the web form);
--   * PENDING and APPROVED requests of one employee never overlap (V20, unchanged): a cancellation
--     releases its dates in the same commit as its evidence.
--
-- Rollback: db/rollback/V21__rollback.sql (manual, never run by Flyway). It restores V20 exactly
-- only while no cancellation exists and no request is CANCELLED; there is no override, and it never
-- touches flyway_schema_history.

-- ---------------------------------------------------------------------------------------------
-- Requests: four states, one transition
-- ---------------------------------------------------------------------------------------------

ALTER TABLE people.leave_request
    DROP CONSTRAINT leave_request_state_valid,
    ADD CONSTRAINT leave_request_state_valid
        CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'));

CREATE OR REPLACE FUNCTION people.leave_request_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('APPROVED', 'REJECTED', 'CANCELLED')
        OR (to_jsonb(NEW) - 'state') IS DISTINCT FROM (to_jsonb(OLD) - 'state') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_transition',
            MESSAGE = 'a leave request changes once, from PENDING to APPROVED, REJECTED or CANCELLED';
    END IF;
    RETURN NEW;
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Cancellations: one per request, append-only
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.leave_request_cancellation_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_cancellation_immutable',
        MESSAGE = 'leave request cancellations are never changed or deleted';
END
$fn$;

CREATE FUNCTION people.leave_request_cancellation_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_cancellation_no_truncate',
        MESSAGE = 'leave request cancellations are never truncated';
END
$fn$;

CREATE TABLE people.leave_request_cancellation (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    request_id    uuid        NOT NULL,
    reason_locale text        NOT NULL,
    reason_text   text        NOT NULL,
    cancelled_at  timestamptz NOT NULL,
    cancelled_by  text        NOT NULL,
    CONSTRAINT leave_request_cancellation_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT leave_request_cancellation_request
        FOREIGN KEY (tenant_id, request_id) REFERENCES people.leave_request (tenant_id, id),
    -- One cancellation per request; also the access path of the history join and of replays.
    CONSTRAINT leave_request_cancellation_request_unique UNIQUE (tenant_id, request_id),
    CONSTRAINT leave_request_cancellation_locale_valid CHECK (reason_locale IN ('en', 'fr')),
    CONSTRAINT leave_request_cancellation_reason_valid
        CHECK (people.leave_reason_valid(reason_text)),
    CONSTRAINT leave_request_cancellation_cancelled_by_length
        CHECK (char_length(cancelled_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.leave_request_cancellation IS
    'MVP-041C employee cancellation of a pending leave request (Restricted HR). One per request; append-only.';

CREATE TRIGGER leave_request_cancellation_immutable
    BEFORE UPDATE OR DELETE ON people.leave_request_cancellation
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_cancellation_immutable();
CREATE TRIGGER leave_request_cancellation_no_truncate
    BEFORE TRUNCATE ON people.leave_request_cancellation
    FOR EACH STATEMENT EXECUTE FUNCTION people.leave_request_cancellation_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Deferred consistency (the application runs these IMMEDIATE before its audit and outbox writes)
-- ---------------------------------------------------------------------------------------------

-- Exactly one matching kind of terminal evidence per request, whether it was inserted in its state
-- or moved there (V20 R90-1, extended to cancellations).
CREATE OR REPLACE FUNCTION people.leave_request_decided() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state     text;
    v_decision  text;
    v_cancelled boolean;
    v_ok        boolean;
BEGIN
    SELECT r.state INTO v_state FROM people.leave_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.id;
    SELECT d.outcome INTO v_decision FROM people.leave_request_decision d
        WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.id;
    v_cancelled := EXISTS (SELECT 1 FROM people.leave_request_cancellation c
        WHERE c.tenant_id = NEW.tenant_id AND c.request_id = NEW.id);
    IF v_state = 'PENDING' THEN
        v_ok := v_decision IS NULL AND NOT v_cancelled;
    ELSIF v_state = 'CANCELLED' THEN
        v_ok := v_decision IS NULL AND v_cancelled;
    ELSE
        v_ok := v_decision IS NOT DISTINCT FROM v_state AND NOT v_cancelled;
    END IF;
    IF NOT v_ok THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decided',
            MESSAGE = 'a leave request commits with exactly its matching terminal evidence';
    END IF;
    RETURN NULL;
END
$fn$;

-- A cancellation commits only with its request CANCELLED.
CREATE FUNCTION people.leave_request_cancellation_consistent() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state text;
BEGIN
    SELECT r.state INTO v_state FROM people.leave_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.request_id;
    IF v_state IS DISTINCT FROM 'CANCELLED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'leave_request_cancellation_consistent',
            MESSAGE = 'a cancellation commits with its request cancelled';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER leave_request_cancellation_consistent
    AFTER INSERT ON people.leave_request_cancellation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_cancellation_consistent();
