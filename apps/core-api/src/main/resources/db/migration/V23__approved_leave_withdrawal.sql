-- MVP-041F (Issue #95): an employee withdraws their own approved leave before it starts, with
-- immutable withdrawal evidence; the original approval decision is kept unchanged. No partial
-- withdrawal, approver approval of a withdrawal, administrator withdrawal, balance, working-day,
-- holiday, payroll or notification.
--
-- Ownership: the people module owns the new table; only the bounded com.divalhr.core.people.leave
-- package reads or writes it. Keys reference people's own request row and the tenant root; there is
-- no cross-module reference.
--
-- Classification: Restricted HR. The withdrawal reason is personal data; it never enters audit
-- metadata, events, logs or errors. The actor (withdrawn_by) is never returned by the API.
--
-- Database authority (V23):
--   * a request moves from PENDING to APPROVED, REJECTED, CANCELLED or AMENDED, and an APPROVED
--     request may move once more, to WITHDRAWN; nothing else about it ever changes, and WITHDRAWN
--     never changes again (leave_request_transition, replaced);
--   * every request carries exactly its matching terminal evidence (leave_request_decided,
--     replaced; deferred): PENDING none; APPROVED and REJECTED their matching decision; CANCELLED
--     its cancellation; AMENDED the one amendment naming it as the original; WITHDRAWN its original
--     APPROVED decision AND one withdrawal. The decision is never rewritten: a withdrawn approval
--     proves both what was approved and how it was later withdrawn. A withdrawal commits only with
--     its request WITHDRAWN, its APPROVED decision and no cancellation or amendment naming it as
--     the original (leave_request_withdrawal_consistent, deferred). V20's
--     leave_request_decision_consistent still runs only when a decision is inserted;
--   * withdrawals are append-only, one per request (leave_request_withdrawal_immutable,
--     _no_truncate, _request_unique) and use the decision-reason grammar version 1
--     (people.leave_reason_valid, shared with Core and the web form);
--   * PENDING and APPROVED requests of one employee never overlap (V20, unchanged): WITHDRAWN is
--     outside the exclusion, so a withdrawal releases its dates in the same commit as its evidence.
--   * whether the leave has started (the organization's business date against the first day) and
--     whose request it is are application rules, checked under the request's row lock.
--
-- Indexes: leave_request_withdrawal_request_unique is the history join and the replay lookup. No
-- other index is added.
--
-- Rollback: db/rollback/V23__rollback.sql (manual, never run by Flyway). It restores V22 exactly
-- only while no withdrawal exists and no request is WITHDRAWN; there is no override, and it never
-- touches flyway_schema_history.

-- ---------------------------------------------------------------------------------------------
-- Requests: six states; PENDING moves once, APPROVED may be withdrawn once
-- ---------------------------------------------------------------------------------------------

ALTER TABLE people.leave_request
    DROP CONSTRAINT leave_request_state_valid,
    ADD CONSTRAINT leave_request_state_valid
        CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'AMENDED', 'WITHDRAWN'));

CREATE OR REPLACE FUNCTION people.leave_request_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF NOT ((OLD.state = 'PENDING'
                AND NEW.state IN ('APPROVED', 'REJECTED', 'CANCELLED', 'AMENDED'))
            OR (OLD.state = 'APPROVED' AND NEW.state = 'WITHDRAWN'))
        OR (to_jsonb(NEW) - 'state') IS DISTINCT FROM (to_jsonb(OLD) - 'state') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_transition',
            MESSAGE = 'a leave request changes once from PENDING, and an approved one may be withdrawn once';
    END IF;
    RETURN NEW;
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Withdrawals: one per request, append-only
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.leave_request_withdrawal_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_withdrawal_immutable',
        MESSAGE = 'leave request withdrawals are never changed or deleted';
END
$fn$;

CREATE FUNCTION people.leave_request_withdrawal_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_withdrawal_no_truncate',
        MESSAGE = 'leave request withdrawals are never truncated';
END
$fn$;

CREATE TABLE people.leave_request_withdrawal (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    request_id    uuid        NOT NULL,
    reason_locale text        NOT NULL,
    reason_text   text        NOT NULL,
    withdrawn_at  timestamptz NOT NULL,
    withdrawn_by  text        NOT NULL,
    CONSTRAINT leave_request_withdrawal_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT leave_request_withdrawal_request
        FOREIGN KEY (tenant_id, request_id) REFERENCES people.leave_request (tenant_id, id),
    -- One withdrawal per request; also the access path of the history join and of replays.
    CONSTRAINT leave_request_withdrawal_request_unique UNIQUE (tenant_id, request_id),
    CONSTRAINT leave_request_withdrawal_locale_valid CHECK (reason_locale IN ('en', 'fr')),
    CONSTRAINT leave_request_withdrawal_reason_valid
        CHECK (people.leave_reason_valid(reason_text)),
    CONSTRAINT leave_request_withdrawal_withdrawn_by_length
        CHECK (char_length(withdrawn_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.leave_request_withdrawal IS
    'MVP-041F employee withdrawal of approved future leave (Restricted HR). One per request; append-only.';

CREATE TRIGGER leave_request_withdrawal_immutable
    BEFORE UPDATE OR DELETE ON people.leave_request_withdrawal
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_withdrawal_immutable();
CREATE TRIGGER leave_request_withdrawal_no_truncate
    BEFORE TRUNCATE ON people.leave_request_withdrawal
    FOR EACH STATEMENT EXECUTE FUNCTION people.leave_request_withdrawal_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Deferred consistency (the application runs these IMMEDIATE before its audit and outbox writes)
-- ---------------------------------------------------------------------------------------------

-- Exactly the matching terminal evidence per request, whether it was inserted in its state or
-- moved there (V20 R90-1, V21, V22, extended to withdrawals).
CREATE OR REPLACE FUNCTION people.leave_request_decided() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state     text;
    v_decision  text;
    v_cancelled boolean;
    v_amended   boolean;
    v_withdrawn boolean;
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
    v_withdrawn := EXISTS (SELECT 1 FROM people.leave_request_withdrawal w
        WHERE w.tenant_id = NEW.tenant_id AND w.request_id = NEW.id);
    IF v_state = 'PENDING' THEN
        v_ok := v_decision IS NULL AND NOT v_cancelled AND NOT v_amended AND NOT v_withdrawn;
    ELSIF v_state = 'CANCELLED' THEN
        v_ok := v_decision IS NULL AND v_cancelled AND NOT v_amended AND NOT v_withdrawn;
    ELSIF v_state = 'AMENDED' THEN
        v_ok := v_decision IS NULL AND NOT v_cancelled AND v_amended AND NOT v_withdrawn;
    ELSIF v_state = 'WITHDRAWN' THEN
        v_ok := v_decision IS NOT DISTINCT FROM 'APPROVED' AND NOT v_cancelled AND NOT v_amended
            AND v_withdrawn;
    ELSE
        v_ok := v_decision IS NOT DISTINCT FROM v_state AND NOT v_cancelled AND NOT v_amended
            AND NOT v_withdrawn;
    END IF;
    IF NOT v_ok THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decided',
            MESSAGE = 'a leave request commits with exactly its matching terminal evidence';
    END IF;
    RETURN NULL;
END
$fn$;

-- A withdrawal commits only with its request WITHDRAWN, its original APPROVED decision kept, and
-- no cancellation or amendment naming the request as the original.
CREATE FUNCTION people.leave_request_withdrawal_consistent() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state    text;
    v_decision text;
BEGIN
    SELECT r.state INTO v_state FROM people.leave_request r
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.request_id;
    SELECT d.outcome INTO v_decision FROM people.leave_request_decision d
        WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.request_id;
    IF v_state IS DISTINCT FROM 'WITHDRAWN'
        OR v_decision IS DISTINCT FROM 'APPROVED'
        OR EXISTS (SELECT 1 FROM people.leave_request_cancellation c
            WHERE c.tenant_id = NEW.tenant_id AND c.request_id = NEW.request_id)
        OR EXISTS (SELECT 1 FROM people.leave_request_amendment a
            WHERE a.tenant_id = NEW.tenant_id AND a.original_request_id = NEW.request_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'leave_request_withdrawal_consistent',
            MESSAGE = 'a withdrawal commits with its approved request withdrawn';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER leave_request_withdrawal_consistent
    AFTER INSERT ON people.leave_request_withdrawal
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_withdrawal_consistent();
