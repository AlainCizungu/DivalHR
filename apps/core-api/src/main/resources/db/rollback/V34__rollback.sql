-- Manual rollback for V34__timesheet_reopening.sql (MVP-070D, Issue #132). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V33 schema exactly, but it
-- REFUSES once any reopening exists, any timesheet is REOPENED, or any submitted or approved
-- timesheet belongs to a generation refreshed after its latest action (a state only V34 allows):
-- a reopening is evidence that approved time was superseded by a correction and must never be
-- erased by a schema rollback. There is no override. Nothing outside the V34 objects and the four
-- V33 functions it replaced is touched; actions, revisions, rows and corrections stay.
--
-- It does NOT edit flyway_schema_history: removing the V34 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.timesheet_generation, people.timesheet, people.timesheet_action,
    people.attendance_correction IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.timesheet_action WHERE action = 'REOPENED')
        OR EXISTS (SELECT 1 FROM people.timesheet WHERE status = 'REOPENED')
        OR EXISTS (SELECT 1 FROM people.timesheet t
            JOIN people.timesheet_generation g ON g.tenant_id = t.tenant_id AND g.id = t.generation_id
            JOIN people.timesheet_action a ON a.tenant_id = t.tenant_id AND a.id = t.current_action_id
            WHERE t.status IN ('SUBMITTED', 'APPROVED') AND g.version <> a.generation_version) THEN
        RAISE EXCEPTION 'V34 rollback refused: timesheet reopening evidence exists';
    END IF;
END
$$;

-- The V33 functions, exactly as V33 created them.
CREATE OR REPLACE FUNCTION people.timesheet_action_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_sheet   people.timesheet%ROWTYPE;
    v_latest  people.timesheet_action%ROWTYPE;
    v_version integer;
BEGIN
    SELECT * INTO v_sheet FROM people.timesheet
    WHERE tenant_id = NEW.tenant_id AND id = NEW.timesheet_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23503', CONSTRAINT = 'timesheet_action_owner',
            MESSAGE = 'an action needs its timesheet';
    END IF;
    SELECT * INTO v_latest FROM people.timesheet_action
    WHERE tenant_id = NEW.tenant_id AND timesheet_id = NEW.timesheet_id
    ORDER BY sequence DESC LIMIT 1;
    IF NEW.sequence IS DISTINCT FROM coalesce(v_latest.sequence, 0) + 1
        OR v_sheet.current_action_id IS DISTINCT FROM v_latest.id
        OR (v_latest.id IS NOT NULL AND NEW.occurred_at < v_latest.occurred_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_sequence',
            MESSAGE = 'a timesheet action follows the latest one';
    END IF;
    SELECT version INTO v_version FROM people.timesheet_generation
    WHERE tenant_id = v_sheet.tenant_id AND id = v_sheet.generation_id;
    IF NEW.revision <> v_sheet.current_revision OR NEW.generation_version <> v_version THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_transition',
            MESSAGE = 'a timesheet action binds the current revision and generation version';
    END IF;
    IF NEW.action = 'SUBMITTED' THEN
        IF v_sheet.status <> 'DRAFT'
            OR (v_latest.id IS NOT NULL AND v_latest.action <> 'RETURNED')
            OR NEW.cycle <> v_sheet.submission_cycle + 1
            -- The employment is the qualifying employment of every row of the revision.
            OR EXISTS (SELECT 1 FROM people.timesheet_row r
                WHERE r.tenant_id = NEW.tenant_id AND r.timesheet_id = NEW.timesheet_id
                  AND r.revision = NEW.revision AND r.employment_id <> NEW.employment_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_transition',
                MESSAGE = 'only a draft is submitted, as the next cycle';
        END IF;
    ELSIF v_sheet.status <> 'SUBMITTED' OR v_latest.action IS DISTINCT FROM 'SUBMITTED'
        OR NEW.submission_action_id IS DISTINCT FROM v_latest.id
        OR NEW.cycle <> v_sheet.submission_cycle THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_transition',
            MESSAGE = 'only the current submission is approved or returned';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION people.timesheet_action_current() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_sheet  people.timesheet%ROWTYPE;
    v_latest people.timesheet_action%ROWTYPE;
BEGIN
    SELECT * INTO v_sheet FROM people.timesheet
    WHERE tenant_id = NEW.tenant_id AND id = NEW.timesheet_id;
    SELECT * INTO v_latest FROM people.timesheet_action
    WHERE tenant_id = NEW.tenant_id AND timesheet_id = NEW.timesheet_id
    ORDER BY sequence DESC LIMIT 1;
    IF v_sheet.current_action_id IS DISTINCT FROM v_latest.id
        OR v_sheet.submission_cycle IS DISTINCT FROM v_latest.cycle
        OR v_sheet.status IS DISTINCT FROM (CASE v_latest.action
            WHEN 'SUBMITTED' THEN 'SUBMITTED' WHEN 'APPROVED' THEN 'APPROVED' ELSE 'DRAFT' END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_action_current',
            MESSAGE = 'a timesheet shows its latest action';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE OR REPLACE FUNCTION people.timesheet_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_action people.timesheet_action%ROWTYPE;
    v_latest integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_immutable',
            MESSAGE = 'a timesheet is never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.current_revision <> 1 OR NEW.updated_at <> NEW.created_at
            OR NEW.status <> 'DRAFT' OR NEW.submission_cycle <> 0
            OR NEW.current_action_id IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
                MESSAGE = 'a timesheet starts as a draft at revision 1';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.id IS DISTINCT FROM OLD.id
        OR NEW.generation_id IS DISTINCT FROM OLD.generation_id
        OR NEW.employee_id IS DISTINCT FROM OLD.employee_id
        OR NEW.site_id IS DISTINCT FROM OLD.site_id OR NEW.week_start IS DISTINCT FROM OLD.week_start
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
            MESSAGE = 'a timesheet keeps its identity';
    END IF;
    IF NEW.current_action_id IS NOT DISTINCT FROM OLD.current_action_id THEN
        -- A refresh: the next revision of a draft.
        IF OLD.status <> 'DRAFT' OR NEW.status <> 'DRAFT'
            OR NEW.submission_cycle <> OLD.submission_cycle
            OR NEW.current_revision <> OLD.current_revision + 1
            OR NEW.updated_at < OLD.updated_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
                MESSAGE = 'a timesheet changes only to its next revision while it is a draft';
        END IF;
        RETURN NEW;
    END IF;
    -- An action: the new latest action of this timesheet, with its state; nothing else changes.
    SELECT * INTO v_action FROM people.timesheet_action
    WHERE tenant_id = NEW.tenant_id AND id = NEW.current_action_id
      AND timesheet_id = NEW.id;
    SELECT max(sequence) INTO v_latest FROM people.timesheet_action
    WHERE tenant_id = NEW.tenant_id AND timesheet_id = NEW.id;
    IF v_action.id IS NULL OR v_action.sequence IS DISTINCT FROM v_latest
        OR NEW.current_revision <> OLD.current_revision
        OR NEW.row_count <> OLD.row_count OR NEW.ready_rows <> OLD.ready_rows
        OR NEW.missing_rows <> OLD.missing_rows OR NEW.open_rows <> OLD.open_rows
        OR NEW.changed_rows <> OLD.changed_rows OR NEW.worked_minutes <> OLD.worked_minutes
        OR NEW.updated_at IS DISTINCT FROM OLD.updated_at
        OR NEW.submission_cycle <> v_action.cycle
        OR NOT (CASE v_action.action
            WHEN 'SUBMITTED' THEN OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED'
                AND NEW.submission_cycle = OLD.submission_cycle + 1
            WHEN 'APPROVED' THEN OLD.status = 'SUBMITTED' AND NEW.status = 'APPROVED'
                AND NEW.submission_cycle = OLD.submission_cycle
            ELSE OLD.status = 'SUBMITTED' AND NEW.status = 'DRAFT'
                AND NEW.submission_cycle = OLD.submission_cycle
            END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
            MESSAGE = 'a timesheet changes state only through its matching latest action';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION people.timesheet_generation_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_generation_immutable',
            MESSAGE = 'a timesheet generation is never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.version <> 1 OR NEW.refreshed_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_generation_guard',
                MESSAGE = 'a timesheet generation starts at version 1';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.id IS DISTINCT FROM OLD.id
        OR NEW.site_id IS DISTINCT FROM OLD.site_id OR NEW.week_start IS DISTINCT FROM OLD.week_start
        OR NEW.week_end IS DISTINCT FROM OLD.week_end
        OR NEW.site_timezone IS DISTINCT FROM OLD.site_timezone
        OR NEW.period_start_at IS DISTINCT FROM OLD.period_start_at
        OR NEW.period_end_at IS DISTINCT FROM OLD.period_end_at
        OR NEW.status IS DISTINCT FROM OLD.status
        OR NEW.generated_at IS DISTINCT FROM OLD.generated_at
        OR NEW.generated_by IS DISTINCT FROM OLD.generated_by
        OR NEW.version <> OLD.version + 1 OR NEW.refreshed_at IS NULL
        OR (OLD.refreshed_at IS NOT NULL AND NEW.refreshed_at < OLD.refreshed_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_generation_guard',
            MESSAGE = 'a timesheet generation changes only by a refresh';
    END IF;
    IF EXISTS (SELECT 1 FROM people.timesheet t
        WHERE t.tenant_id = NEW.tenant_id AND t.generation_id = NEW.id AND t.status <> 'DRAFT') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_generation_locked',
            MESSAGE = 'a generation with submitted or approved time is never refreshed';
    END IF;
    RETURN NEW;
END
$fn$;

-- The V33 action constraints and indexes.
DROP INDEX people.timesheet_action_correction_idx;
DROP INDEX people.timesheet_action_reopening_unique;
DROP INDEX people.timesheet_action_reopened_cycle_unique;
DROP INDEX people.timesheet_action_decision_unique;
CREATE UNIQUE INDEX timesheet_action_decision_unique
    ON people.timesheet_action (tenant_id, timesheet_id, cycle) WHERE action <> 'SUBMITTED';
ALTER TABLE people.timesheet_action
    DROP CONSTRAINT timesheet_action_correction,
    DROP CONSTRAINT timesheet_action_superseded,
    DROP CONSTRAINT timesheet_action_shape,
    DROP CONSTRAINT timesheet_action_after_digest,
    DROP CONSTRAINT timesheet_action_kind;
ALTER TABLE people.timesheet_action
    DROP COLUMN correction_id,
    DROP COLUMN after_source_digest,
    DROP COLUMN superseded_action_id;
ALTER TABLE people.timesheet_action
    ADD CONSTRAINT timesheet_action_kind CHECK (action IN ('SUBMITTED', 'RETURNED', 'APPROVED')),
    ADD CONSTRAINT timesheet_action_shape CHECK (CASE action
        WHEN 'SUBMITTED' THEN submission_action_id IS NULL AND return_reason IS NULL
        WHEN 'APPROVED' THEN submission_action_id IS NOT NULL AND return_reason IS NULL
        ELSE submission_action_id IS NOT NULL AND return_reason IS NOT NULL
            AND people.timesheet_return_reason_valid(return_reason)
        END);
COMMENT ON TABLE people.timesheet_action IS
    'MVP-070B/C append-only submission and decision evidence of a timesheet (Restricted HR/Operations): the cycle, the revision with its source digest and totals, the generation version, the employee and employment, the routed or deciding manager, the verified subject, the return reason and the instant.';
ALTER TABLE people.attendance_correction DROP CONSTRAINT attendance_correction_employee_key;
ALTER TABLE people.timesheet DROP CONSTRAINT timesheet_status;
ALTER TABLE people.timesheet
    ADD CONSTRAINT timesheet_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED'));
COMMENT ON TABLE people.timesheet IS
    'MVP-070A/B/C weekly timesheet of one employee at one site (Restricted HR/Operations): DRAFT, SUBMITTED or APPROVED, the current revision and exactly its totals, the latest submission cycle and the current action.';
COMMIT;
