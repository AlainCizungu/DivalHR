-- MVP-041B (Issue #89): one-step approval or rejection of a pending leave request, with
-- immutable decision evidence. No balance, working-day, holiday, schedule or payroll calculation.
--
-- Ownership: the people module owns both tables; only the bounded com.divalhr.core.people.leave
-- package reads or writes them. Keys reference people's own request, policy version and employee
-- rows and the tenant root; there is no cross-module reference.
--
-- Classification: Restricted HR. The decision reason is personal data; it never enters audit
-- metadata, events, logs or errors.
--
-- Database authority (V20):
--   * a request moves exactly once, from PENDING to APPROVED or REJECTED, and nothing else about it
--     ever changes; it is never deleted or truncated (leave_request_transition,
--     leave_request_immutable, leave_request_no_truncate);
--   * a terminal request commits only with its one matching decision (leave_request_decided,
--     deferred), and a decision only with its request in that state, under the route of the
--     request's policy version, with the exact route/manager shape
--     (leave_request_decision_consistent, deferred);
--   * decisions are append-only (leave_request_decision_immutable, _no_truncate);
--   * PENDING and APPROVED requests of one employee never overlap; a REJECTED request releases
--     its dates in the same commit as its decision (leave_request_no_overlap).
--
-- Rollback: db/rollback/V20__rollback.sql (manual, never run by Flyway). It restores V19 exactly
-- only while no decision exists and every request is still PENDING; there is no override, and it
-- never touches flyway_schema_history.

-- ---------------------------------------------------------------------------------------------
-- Requests: three states, one transition
-- ---------------------------------------------------------------------------------------------

ALTER TABLE people.leave_request
    DROP CONSTRAINT leave_request_state_valid,
    ADD CONSTRAINT leave_request_state_valid CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED'));

-- Pending and approved requests of one employee never overlap (both ends inclusive).
ALTER TABLE people.leave_request
    DROP CONSTRAINT leave_request_no_overlap,
    ADD CONSTRAINT leave_request_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, employee_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&) WHERE (state IN ('PENDING', 'APPROVED'));

CREATE FUNCTION people.leave_request_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('APPROVED', 'REJECTED')
        OR (to_jsonb(NEW) - 'state') IS DISTINCT FROM (to_jsonb(OLD) - 'state') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_transition',
            MESSAGE = 'a leave request changes once, from PENDING to APPROVED or REJECTED';
    END IF;
    RETURN NEW;
END
$fn$;

-- V19's insert-only trigger keeps refusing deletes; updates go through the transition guard.
DROP TRIGGER leave_request_immutable ON people.leave_request;
CREATE TRIGGER leave_request_immutable
    BEFORE DELETE ON people.leave_request
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_immutable();
CREATE TRIGGER leave_request_transition
    BEFORE UPDATE ON people.leave_request
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_transition();

-- The tenant administrators' pending queue, newest first.
CREATE INDEX leave_request_pending_queue
    ON people.leave_request (tenant_id, state, submitted_at DESC, id DESC);
-- A manager's pending queue: the active reporting lines (employment_assignment_reports), then
-- each report's pending requests by employment in request order.
CREATE INDEX leave_request_employment_pending
    ON people.leave_request (tenant_id, employment_id, submitted_at DESC, id DESC)
    WHERE state = 'PENDING';

-- ---------------------------------------------------------------------------------------------
-- Decisions: one per request, append-only
-- ---------------------------------------------------------------------------------------------

-- The reason grammar (the application enforces the same): NFC, trimmed, 2 to 500 code points,
-- no control character.
CREATE FUNCTION people.leave_reason_valid(reason text) RETURNS boolean
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $fn$
SELECT reason IS NFC NORMALIZED
    AND reason = btrim(reason)
    AND char_length(reason) BETWEEN 2 AND 500
    AND reason !~ '[[:cntrl:]]'
$fn$;

CREATE FUNCTION people.leave_request_decision_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decision_immutable',
        MESSAGE = 'leave request decisions are never changed or deleted';
END
$fn$;

CREATE FUNCTION people.leave_request_decision_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decision_no_truncate',
        MESSAGE = 'leave request decisions are never truncated';
END
$fn$;

CREATE TABLE people.leave_request_decision (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL,
    request_id          uuid        NOT NULL,
    outcome             text        NOT NULL,
    approval_route      text        NOT NULL,
    manager_employee_id uuid,
    reason_locale       text        NOT NULL,
    reason_text         text        NOT NULL,
    decided_at          timestamptz NOT NULL,
    decided_by          text        NOT NULL,
    CONSTRAINT leave_request_decision_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT leave_request_decision_request
        FOREIGN KEY (tenant_id, request_id) REFERENCES people.leave_request (tenant_id, id),
    CONSTRAINT leave_request_decision_request_unique UNIQUE (tenant_id, request_id),
    CONSTRAINT leave_request_decision_manager
        FOREIGN KEY (tenant_id, manager_employee_id) REFERENCES people.employee (tenant_id, id),
    CONSTRAINT leave_request_decision_outcome_valid CHECK (outcome IN ('APPROVED', 'REJECTED')),
    -- A manager decision names the deciding manager's employee; an administrator's names none.
    CONSTRAINT leave_request_decision_route_shape CHECK (
        (approval_route = 'MANAGER' AND manager_employee_id IS NOT NULL)
        OR (approval_route = 'TENANT_ADMIN' AND manager_employee_id IS NULL)),
    CONSTRAINT leave_request_decision_locale_valid CHECK (reason_locale IN ('en', 'fr')),
    CONSTRAINT leave_request_decision_reason_valid CHECK (people.leave_reason_valid(reason_text)),
    CONSTRAINT leave_request_decision_decided_by_length
        CHECK (char_length(decided_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.leave_request_decision IS
    'MVP-041B terminal leave decision (Restricted HR). One per request; append-only.';

CREATE TRIGGER leave_request_decision_immutable
    BEFORE UPDATE OR DELETE ON people.leave_request_decision
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_decision_immutable();
CREATE TRIGGER leave_request_decision_no_truncate
    BEFORE TRUNCATE ON people.leave_request_decision
    FOR EACH STATEMENT EXECUTE FUNCTION people.leave_request_decision_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Deferred consistency (the application runs both IMMEDIATE before its audit and outbox writes)
-- ---------------------------------------------------------------------------------------------

-- A terminal request commits only with its one decision of the same outcome.
CREATE FUNCTION people.leave_request_decided() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state text;
BEGIN
    SELECT r.state INTO v_state FROM people.leave_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.id;
    IF v_state <> 'PENDING' AND NOT EXISTS (SELECT 1 FROM people.leave_request_decision d
        WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.id AND d.outcome = v_state) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decided',
            MESSAGE = 'a decided leave request commits with its matching decision';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER leave_request_decided
    AFTER UPDATE ON people.leave_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_decided();

-- A decision commits only with its request in the decided state, under the route of the request's
-- policy version, with the exact route/manager shape.
CREATE FUNCTION people.leave_request_decision_consistent() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state text;
    v_route text;
BEGIN
    SELECT r.state, v.approval_route INTO v_state, v_route
        FROM people.leave_request r
        JOIN people.leave_policy_version v
            ON v.tenant_id = r.tenant_id AND v.id = r.policy_version_id
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.request_id;
    IF v_state IS DISTINCT FROM NEW.outcome
        OR v_route IS DISTINCT FROM NEW.approval_route
        OR (NEW.approval_route = 'MANAGER') <> (NEW.manager_employee_id IS NOT NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decision_consistent',
            MESSAGE = 'a decision matches its request state and its policy route';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER leave_request_decision_consistent
    AFTER INSERT ON people.leave_request_decision
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_decision_consistent();
