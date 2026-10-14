-- Manual rollback for V33__timesheet_submission_approval.sql (MVP-070B/C, Issue #130). NEVER run by
-- Flyway.
--
-- Prefer rolling back only the application. This script restores the V32 schema exactly, but it
-- REFUSES once any timesheet action exists or any timesheet was ever submitted: submissions,
-- returns and approvals are evidence of what an employee declared and a manager decided, and must
-- never be erased by a schema rollback. There is no override. Nothing outside the V33 objects and
-- the two V32 guard functions it extended is touched; generations, drafts, revisions and rows stay.
--
-- It does NOT edit flyway_schema_history: removing the V33 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE people.timesheet_generation, people.timesheet, people.timesheet_revision,
    people.timesheet_action IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.timesheet_action)
        OR EXISTS (SELECT 1 FROM people.timesheet
            WHERE status <> 'DRAFT' OR submission_cycle <> 0 OR current_action_id IS NOT NULL) THEN
        RAISE EXCEPTION 'V33 rollback refused: timesheet submission or decision evidence exists';
    END IF;
END
$$;

-- The V32 guards, exactly as V32 created them.
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
    RETURN NEW;
END
$fn$;

CREATE OR REPLACE FUNCTION people.timesheet_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_immutable',
            MESSAGE = 'a timesheet is never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.current_revision <> 1 OR NEW.updated_at <> NEW.created_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
                MESSAGE = 'a timesheet starts at revision 1';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.id IS DISTINCT FROM OLD.id
        OR NEW.generation_id IS DISTINCT FROM OLD.generation_id
        OR NEW.employee_id IS DISTINCT FROM OLD.employee_id
        OR NEW.site_id IS DISTINCT FROM OLD.site_id OR NEW.week_start IS DISTINCT FROM OLD.week_start
        OR NEW.status IS DISTINCT FROM OLD.status OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.current_revision <> OLD.current_revision + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_guard',
            MESSAGE = 'a timesheet changes only to its next revision';
    END IF;
    RETURN NEW;
END
$fn$;

ALTER TABLE people.timesheet DROP CONSTRAINT timesheet_current_action;
DROP TABLE people.timesheet_action;
DROP FUNCTION people.timesheet_action_current();
DROP FUNCTION people.timesheet_action_transition();
ALTER TABLE people.timesheet_revision DROP CONSTRAINT timesheet_revision_evidence_key;
DROP INDEX people.timesheet_submitted;
ALTER TABLE people.timesheet
    DROP CONSTRAINT timesheet_cycle,
    DROP CONSTRAINT timesheet_status,
    DROP COLUMN current_action_id,
    DROP COLUMN submission_cycle;
ALTER TABLE people.timesheet ADD CONSTRAINT timesheet_status CHECK (status = 'DRAFT');
COMMENT ON TABLE people.timesheet IS
    'MVP-070A weekly draft timesheet of one employee at one site (Restricted HR/Operations): the current revision and exactly its totals.';
DROP FUNCTION people.timesheet_return_reason_valid(text);
COMMIT;
