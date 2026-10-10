-- MVP-041D/E (Issue #92): an employee amends their own pending leave request by replacing it, and a
-- tenant administrator resolves a MANAGER-routed request that no qualifying manager covers. No
-- withdrawal of approved leave, delegation, substitute approver, escalation, notification, balance
-- or payroll effect.
--
-- Ownership: the people module owns the new table and column; only the bounded
-- com.divalhr.core.people.leave package reads or writes them. Keys reference people's own request
-- rows and the tenant root; there is no cross-module reference.
--
-- Classification: Restricted HR. The amendment reason is personal data; it never enters audit
-- metadata, events, logs or errors. The actor (amended_by) and the decision authority are never
-- returned to the employee.
--
-- Database authority (V22):
--   * a request moves exactly once, from PENDING to APPROVED, REJECTED, CANCELLED or AMENDED, and
--     nothing else about it ever changes (leave_request_transition, replaced);
--   * every request carries exactly one matching kind of terminal evidence (leave_request_decided,
--     replaced; deferred): PENDING has none; APPROVED and REJECTED their matching decision;
--     CANCELLED its cancellation; AMENDED the one amendment naming it as the original. An
--     amendment commits only with its original AMENDED and its distinct replacement PENDING, both
--     the same employee's (leave_request_amendment_consistent, deferred);
--   * amendments are append-only, one per original and one per replacement
--     (leave_request_amendment_immutable, _no_truncate, _original_unique, _replacement_unique) and
--     use the decision-reason grammar version 1 (people.leave_reason_valid, shared);
--   * a decision names its authority (decision_authority): MANAGER (with the deciding manager),
--     TENANT_ADMIN (no manager) under the policy route of the same name, or TENANT_ADMIN_OVERRIDE
--     (no manager) under the MANAGER route; no other shape (leave_request_decision_authority_shape).
--     The route stays the immutable policy route (V20's leave_request_decision_consistent, its
--     route/manager clause now carried by the shape constraint). Whether a qualifying manager
--     exists is the application's effective-dated rule, re-evaluated under the tenant's
--     manager-graph lock; it is never stored as a flag;
--   * PENDING and APPROVED requests of one employee never overlap (V20, unchanged): an AMENDED
--     original releases its dates in the same commit as its replacement takes them.
--
-- Indexes: the two amendment unique constraints are the original and replacement lookups. The
-- no-manager exception queue reads V20's leave_request_pending_queue (tenant, state, submission
-- order) and V14's employment_assignment_active; an override's replay reads V20's
-- leave_request_decision_request_unique. No other index is added.
--
-- Rollback: db/rollback/V22__rollback.sql (manual, never run by Flyway). It restores V21 exactly
-- only while no amendment exists, no request is AMENDED and no decision has the
-- TENANT_ADMIN_OVERRIDE authority; there is no override, and it never touches
-- flyway_schema_history.

-- ---------------------------------------------------------------------------------------------
-- Requests: five states, one transition
-- ---------------------------------------------------------------------------------------------

ALTER TABLE people.leave_request
    DROP CONSTRAINT leave_request_state_valid,
    ADD CONSTRAINT leave_request_state_valid
        CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'AMENDED'));

CREATE OR REPLACE FUNCTION people.leave_request_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('APPROVED', 'REJECTED', 'CANCELLED', 'AMENDED')
        OR (to_jsonb(NEW) - 'state') IS DISTINCT FROM (to_jsonb(OLD) - 'state') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_transition',
            MESSAGE = 'a leave request changes once, from PENDING to APPROVED, REJECTED, CANCELLED or AMENDED';
    END IF;
    RETURN NEW;
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Decisions: the authority they were taken under
-- ---------------------------------------------------------------------------------------------

-- Existing decisions were all taken under their policy route: the backfill is that route. The
-- append-only trigger is suspended for this one statement inside the migration transaction only.
ALTER TABLE people.leave_request_decision ADD COLUMN decision_authority text;
ALTER TABLE people.leave_request_decision DISABLE TRIGGER leave_request_decision_immutable;
UPDATE people.leave_request_decision SET decision_authority = approval_route;
ALTER TABLE people.leave_request_decision ENABLE TRIGGER leave_request_decision_immutable;
ALTER TABLE people.leave_request_decision
    ALTER COLUMN decision_authority SET NOT NULL,
    DROP CONSTRAINT leave_request_decision_route_shape,
    ADD CONSTRAINT leave_request_decision_authority_shape CHECK (
        (approval_route = 'MANAGER' AND decision_authority = 'MANAGER'
            AND manager_employee_id IS NOT NULL)
        OR (approval_route = 'TENANT_ADMIN' AND decision_authority = 'TENANT_ADMIN'
            AND manager_employee_id IS NULL)
        OR (approval_route = 'MANAGER' AND decision_authority = 'TENANT_ADMIN_OVERRIDE'
            AND manager_employee_id IS NULL));

-- A decision commits only with its request in the decided state and under the route of the
-- request's policy version; its route/authority/manager shape is the check constraint above.
CREATE OR REPLACE FUNCTION people.leave_request_decision_consistent() RETURNS trigger
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
    IF v_state IS DISTINCT FROM NEW.outcome OR v_route IS DISTINCT FROM NEW.approval_route THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decision_consistent',
            MESSAGE = 'a decision matches its request state and its policy route';
    END IF;
    RETURN NULL;
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Amendments: one per original, one per replacement, append-only
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.leave_request_amendment_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_amendment_immutable',
        MESSAGE = 'leave request amendments are never changed or deleted';
END
$fn$;

CREATE FUNCTION people.leave_request_amendment_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_amendment_no_truncate',
        MESSAGE = 'leave request amendments are never truncated';
END
$fn$;

CREATE TABLE people.leave_request_amendment (
    id                     uuid        PRIMARY KEY,
    tenant_id              uuid        NOT NULL,
    original_request_id    uuid        NOT NULL,
    replacement_request_id uuid        NOT NULL,
    reason_locale          text        NOT NULL,
    reason_text            text        NOT NULL,
    amended_at             timestamptz NOT NULL,
    amended_by             text        NOT NULL,
    CONSTRAINT leave_request_amendment_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT leave_request_amendment_original
        FOREIGN KEY (tenant_id, original_request_id) REFERENCES people.leave_request (tenant_id, id),
    CONSTRAINT leave_request_amendment_replacement
        FOREIGN KEY (tenant_id, replacement_request_id)
            REFERENCES people.leave_request (tenant_id, id),
    -- One amendment replaces an original, and a replacement belongs to one amendment; these are
    -- also the access paths of the history joins and of replays.
    CONSTRAINT leave_request_amendment_original_unique UNIQUE (tenant_id, original_request_id),
    CONSTRAINT leave_request_amendment_replacement_unique
        UNIQUE (tenant_id, replacement_request_id),
    CONSTRAINT leave_request_amendment_distinct
        CHECK (original_request_id <> replacement_request_id),
    CONSTRAINT leave_request_amendment_locale_valid CHECK (reason_locale IN ('en', 'fr')),
    CONSTRAINT leave_request_amendment_reason_valid CHECK (people.leave_reason_valid(reason_text)),
    CONSTRAINT leave_request_amendment_amended_by_length
        CHECK (char_length(amended_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.leave_request_amendment IS
    'MVP-041D employee amendment of a pending leave request by replacement (Restricted HR). One per original and per replacement; append-only.';

CREATE TRIGGER leave_request_amendment_immutable
    BEFORE UPDATE OR DELETE ON people.leave_request_amendment
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_amendment_immutable();
CREATE TRIGGER leave_request_amendment_no_truncate
    BEFORE TRUNCATE ON people.leave_request_amendment
    FOR EACH STATEMENT EXECUTE FUNCTION people.leave_request_amendment_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Deferred consistency (the application runs these IMMEDIATE before its audit and outbox writes)
-- ---------------------------------------------------------------------------------------------

-- Exactly one matching kind of terminal evidence per request, whether it was inserted in its state
-- or moved there (V20 R90-1, V21, extended to amendments).
CREATE OR REPLACE FUNCTION people.leave_request_decided() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state     text;
    v_decision  text;
    v_cancelled boolean;
    v_amended   boolean;
    v_ok        boolean;
BEGIN
    SELECT r.state INTO v_state FROM people.leave_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.id;
    SELECT d.outcome INTO v_decision FROM people.leave_request_decision d
        WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.id;
    v_cancelled := EXISTS (SELECT 1 FROM people.leave_request_cancellation c
        WHERE c.tenant_id = NEW.tenant_id AND c.request_id = NEW.id);
    v_amended := EXISTS (SELECT 1 FROM people.leave_request_amendment a
        WHERE a.tenant_id = NEW.tenant_id AND a.original_request_id = NEW.id);
    IF v_state = 'PENDING' THEN
        v_ok := v_decision IS NULL AND NOT v_cancelled AND NOT v_amended;
    ELSIF v_state = 'CANCELLED' THEN
        v_ok := v_decision IS NULL AND v_cancelled AND NOT v_amended;
    ELSIF v_state = 'AMENDED' THEN
        v_ok := v_decision IS NULL AND NOT v_cancelled AND v_amended;
    ELSE
        v_ok := v_decision IS NOT DISTINCT FROM v_state AND NOT v_cancelled AND NOT v_amended;
    END IF;
    IF NOT v_ok THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decided',
            MESSAGE = 'a leave request commits with exactly its matching terminal evidence';
    END IF;
    RETURN NULL;
END
$fn$;

-- An amendment commits only with its original AMENDED and its replacement PENDING, both requests of
-- the same employee. A later transaction may move the replacement normally.
CREATE FUNCTION people.leave_request_amendment_consistent() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_original_state    text;
    v_original_employee uuid;
    v_replacement_state text;
    v_replacement_employee uuid;
BEGIN
    SELECT r.state, r.employee_id INTO v_original_state, v_original_employee
        FROM people.leave_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.original_request_id;
    SELECT r.state, r.employee_id INTO v_replacement_state, v_replacement_employee
        FROM people.leave_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.replacement_request_id;
    IF v_original_state IS DISTINCT FROM 'AMENDED'
        OR v_replacement_state IS DISTINCT FROM 'PENDING'
        OR v_original_employee IS DISTINCT FROM v_replacement_employee THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'leave_request_amendment_consistent',
            MESSAGE = 'an amendment commits with its original amended and its replacement pending';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER leave_request_amendment_consistent
    AFTER INSERT ON people.leave_request_amendment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_amendment_consistent();
