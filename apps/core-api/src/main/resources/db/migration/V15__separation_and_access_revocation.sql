-- MVP-022 (Issue #49): separation of an employee, the employee-to-membership access link, durable
-- revocation of DivalHR access and the separation follow-up checklist.
--
-- Ownership (ADR 0008): people owns employment_separation, separation_task and
-- separation_task_event and the SEPARATION change type; identity owns employee_access_link and
-- access_revocation. The cross-schema foreign keys below (identity -> people.employee and
-- people.employment_separation) are a deliberate modular-monolith integrity exception: they are
-- constraints, not reads. Application code reaches the other module only through ports.
--
-- Personal data (approved classification, A22-6): the last day, reason, access timing,
-- direct-report action and counts, task codes, statuses and due dates are Restricted HR; every ID
-- is a personal-data reference; the membership role is Confidential; revocation state, attempts
-- and outcome codes are security-sensitive operational data. No email, subject, name or free text
-- is stored in the new tables.
--
-- Database authority: a separation is the only way to end an employment (employment_end_governed);
-- active rows never extend past the employment (employment_assignment_within_employment); a
-- manager is employed on every day of every active reporting line (employment_manager_employed);
-- the SEPARATION change, the direct-report changes it generates (reason MANAGER_SEPARATED) and the
-- cancellations that reverse them have an exact shape (employment_change_shape); a revocation can
-- only target an employee-role membership, and its states move only as the access lifecycle
-- allows (access_revocation_guard). Trigger failures carry stable constraint names.
--
-- Rollback: db/rollback/V15__rollback.sql (manual, never run by Flyway). It refuses once any
-- separation, link, revocation, checklist task, SEPARATION change or ended employment exists, and
-- never touches flyway_schema_history or the identity provider.

-- =============================================================================================
-- people: the change log learns SEPARATION and MANAGER_SEPARATED
-- =============================================================================================
ALTER TABLE people.employment_change ADD COLUMN separation_id uuid;

ALTER TABLE people.employment_change
    DROP CONSTRAINT employment_change_type_valid,
    ADD CONSTRAINT employment_change_type_valid
        CHECK (type IN ('HIRE', 'CHANGE', 'CORRECTION', 'CANCELLATION', 'SEPARATION')),
    DROP CONSTRAINT employment_change_reason_valid,
    ADD CONSTRAINT employment_change_reason_valid CHECK (
        (type IN ('HIRE', 'CANCELLATION', 'SEPARATION') AND reason_code IS NULL)
        OR (type = 'CHANGE' AND (reason_code IS NULL OR reason_code IN (
            'LATE_NOTIFICATION', 'REORGANIZATION', 'CONTRACT_CHANGE', 'OTHER_BUSINESS_CHANGE',
            'MANAGER_SEPARATED')))
        OR (type = 'CORRECTION' AND reason_code IN (
            'DATA_ENTRY_ERROR', 'IMPORT_ERROR', 'DOCUMENT_RECEIVED'))),
    DROP CONSTRAINT employment_change_state_valid,
    ADD CONSTRAINT employment_change_state_valid CHECK (
        state IN ('ACTIVE', 'CANCELLED')
        AND (state = 'ACTIVE' OR type IN ('CHANGE', 'SEPARATION'))),
    -- A22-2: MANAGER_SEPARATED if and only if a CHANGE is bound to a separation; such a change
    -- sets exactly the MANAGER kind. A SEPARATION always names its separation; a cancellation may
    -- (when it reverses a separation's work); hires and corrections never do.
    ADD CONSTRAINT employment_change_separation_valid CHECK (
        (type = 'SEPARATION' AND separation_id IS NOT NULL)
        OR (type = 'CHANGE'
            AND (separation_id IS NOT NULL) = (reason_code IS NOT DISTINCT FROM 'MANAGER_SEPARATED')
            AND (separation_id IS NULL OR kinds = ARRAY['MANAGER']::text[]))
        OR type = 'CANCELLATION'
        OR (type IN ('HIRE', 'CORRECTION') AND separation_id IS NULL));

CREATE INDEX employment_change_separation
    ON people.employment_change (separation_id) WHERE separation_id IS NOT NULL;

-- =============================================================================================
-- people.employment_separation
-- =============================================================================================
CREATE TABLE people.employment_separation (
    id                     uuid        PRIMARY KEY,
    tenant_id              uuid        NOT NULL,
    employee_id            uuid        NOT NULL,
    employment_id          uuid        NOT NULL,
    change_id              uuid        NOT NULL,
    last_day               date        NOT NULL,
    reason_code            text        NOT NULL,
    access_timing          text        NOT NULL,
    report_action          text,
    replacement_manager_id uuid,
    report_count           integer     NOT NULL,
    interval_count         integer     NOT NULL,
    state                  text        NOT NULL,
    -- HR effectiveness: the start of the day after last_day in the organization's time zone.
    effective_at           timestamptz NOT NULL,
    effective_marked_at    timestamptz,
    recorded_at            timestamptz NOT NULL,
    recorded_by            text        NOT NULL,
    cancelled_at           timestamptz,
    cancelled_by           text,
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT employment_separation_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT employment_separation_ownership_unique UNIQUE (tenant_id, id, employee_id),
    CONSTRAINT employment_separation_change_unique UNIQUE (change_id),
    CONSTRAINT employment_separation_employment FOREIGN KEY (tenant_id, employment_id, employee_id)
        REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT employment_separation_change FOREIGN KEY
        (tenant_id, change_id, employee_id, employment_id)
        REFERENCES people.employment_change (tenant_id, id, employee_id, employment_id),
    CONSTRAINT employment_separation_replacement FOREIGN KEY (tenant_id, replacement_manager_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT employment_separation_reason_valid CHECK (reason_code IN (
        'RESIGNATION', 'END_OF_FIXED_TERM', 'DISMISSAL', 'MUTUAL_AGREEMENT', 'RETIREMENT',
        'OTHER_SEPARATION')),
    CONSTRAINT employment_separation_access_timing_valid
        CHECK (access_timing IN ('END_OF_LAST_DAY', 'IMMEDIATELY')),
    CONSTRAINT employment_separation_report_plan_valid CHECK (
        (report_action IS NULL AND replacement_manager_id IS NULL AND interval_count = 0
            AND report_count = 0)
        OR (report_action = 'CLEAR' AND replacement_manager_id IS NULL AND interval_count > 0)
        OR (report_action = 'REASSIGN' AND replacement_manager_id IS NOT NULL
            AND interval_count > 0)),
    CONSTRAINT employment_separation_replacement_not_self
        CHECK (replacement_manager_id IS DISTINCT FROM employee_id),
    -- A22-2: the safety limit counts affected intervals, not reports.
    CONSTRAINT employment_separation_counts_valid CHECK (
        report_count >= 0 AND interval_count BETWEEN 0 AND 200 AND report_count <= interval_count),
    CONSTRAINT employment_separation_state_valid
        CHECK (state IN ('SCHEDULED', 'EFFECTIVE', 'CANCELLED')),
    CONSTRAINT employment_separation_state_consistent CHECK (
        CASE state
            WHEN 'SCHEDULED' THEN effective_marked_at IS NULL AND cancelled_at IS NULL
                AND cancelled_by IS NULL
            WHEN 'EFFECTIVE' THEN effective_marked_at IS NOT NULL AND cancelled_at IS NULL
                AND cancelled_by IS NULL
            WHEN 'CANCELLED' THEN effective_marked_at IS NULL AND cancelled_at IS NOT NULL
                AND cancelled_by IS NOT NULL
            ELSE FALSE
        END),
    CONSTRAINT employment_separation_last_day_range
        CHECK (last_day BETWEEN DATE '1900-01-01' AND DATE '2999-12-30'),
    CONSTRAINT employment_separation_recorded_by_length
        CHECK (char_length(recorded_by) BETWEEN 1 AND 255),
    CONSTRAINT employment_separation_cancelled_by_length
        CHECK (cancelled_by IS NULL OR char_length(cancelled_by) BETWEEN 1 AND 255),
    CONSTRAINT employment_separation_version_non_negative CHECK (version >= 0)
);

COMMENT ON TABLE people.employment_separation IS
    'MVP-022 separations (Restricted HR). Never deleted; cancelled ones are kept.';

-- One separation that is not cancelled per employment.
CREATE UNIQUE INDEX employment_separation_one_open
    ON people.employment_separation (employment_id) WHERE state <> 'CANCELLED';
CREATE INDEX employment_separation_employee
    ON people.employment_separation (tenant_id, employee_id, recorded_at DESC, id DESC);
CREATE INDEX employment_separation_due
    ON people.employment_separation (effective_at) WHERE state = 'SCHEDULED';

-- The change log names its separation (checked at commit: the separation row is written after
-- the change that it names).
ALTER TABLE people.employment_change
    ADD CONSTRAINT employment_change_separation FOREIGN KEY (tenant_id, separation_id)
        REFERENCES people.employment_separation (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION people.employment_separation_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_separation_immutable',
            MESSAGE = 'separations are never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.version <> 0 OR NEW.state = 'CANCELLED'
            OR (NEW.state = 'EFFECTIVE') <> (NEW.effective_at <= statement_timestamp()) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'employment_separation_immutable',
                MESSAGE = 'a separation is inserted SCHEDULED, or EFFECTIVE once its date passed';
        END IF;
        RETURN NEW;
    END IF;
    -- Only the state moves, once, and the version with it; nothing else ever changes.
    IF NEW.version <> OLD.version + 1
        OR (to_jsonb(NEW) - 'state' - 'effective_marked_at' - 'cancelled_at' - 'cancelled_by'
                - 'version')
            IS DISTINCT FROM (to_jsonb(OLD) - 'state' - 'effective_marked_at' - 'cancelled_at'
                - 'cancelled_by' - 'version')
        OR OLD.state <> 'SCHEDULED'
        OR NOT (
            -- The effective-date job, once the start of the day after the last day has passed.
            (NEW.state = 'EFFECTIVE' AND OLD.effective_at <= statement_timestamp())
            -- A cancellation, before that instant, once the SEPARATION change is cancelled.
            OR (NEW.state = 'CANCELLED' AND statement_timestamp() < OLD.effective_at
                AND EXISTS (SELECT 1 FROM people.employment_change c
                    WHERE c.id = OLD.change_id AND c.state = 'CANCELLED')))
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_separation_immutable',
            MESSAGE = 'a separation only becomes effective or cancelled, once, at the right time';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER employment_separation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON people.employment_separation
    FOR EACH ROW EXECUTE FUNCTION people.employment_separation_guard();
CREATE TRIGGER employment_separation_no_truncate
    BEFORE TRUNCATE ON people.employment_separation
    FOR EACH STATEMENT EXECUTE FUNCTION people.employment_history_no_truncate();

-- =============================================================================================
-- people.separation_task and its append-only history
-- =============================================================================================
CREATE TABLE people.separation_task (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    employee_id   uuid        NOT NULL,
    separation_id uuid        NOT NULL,
    code          text        NOT NULL,
    status        text        NOT NULL,
    due_date      date        NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT separation_task_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT separation_task_one_per_code UNIQUE (separation_id, code),
    CONSTRAINT separation_task_separation FOREIGN KEY (tenant_id, separation_id, employee_id)
        REFERENCES people.employment_separation (tenant_id, id, employee_id),
    CONSTRAINT separation_task_code_valid
        CHECK (code IN ('RETURN_ASSIGNED_ASSETS', 'COLLECT_OR_ARCHIVE_DOCUMENTS')),
    CONSTRAINT separation_task_status_valid
        CHECK (status IN ('OPEN', 'DONE', 'NOT_APPLICABLE', 'CANCELLED')),
    CONSTRAINT separation_task_updated_by_length CHECK (char_length(updated_by) BETWEEN 1 AND 255),
    CONSTRAINT separation_task_version_non_negative CHECK (version >= 0)
);

COMMENT ON TABLE people.separation_task IS
    'MVP-022 separation follow-up reminders (Restricted HR); never an asset or document record.';

CREATE INDEX separation_task_separation ON people.separation_task (separation_id);

CREATE TABLE people.separation_task_event (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL,
    task_id     uuid        NOT NULL,
    from_status text,
    to_status   text        NOT NULL,
    changed_at  timestamptz NOT NULL,
    changed_by  text        NOT NULL,
    version     bigint      NOT NULL,
    CONSTRAINT separation_task_event_task FOREIGN KEY (tenant_id, task_id)
        REFERENCES people.separation_task (tenant_id, id),
    CONSTRAINT separation_task_event_once UNIQUE (task_id, version)
);

CREATE INDEX separation_task_event_task ON people.separation_task_event (task_id, version);

CREATE FUNCTION people.separation_task_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'separation_task_immutable',
            MESSAGE = 'separation tasks are never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.version <> 0 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'separation_task_immutable',
                MESSAGE = 'a separation task is created OPEN';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.version <> OLD.version + 1
        OR NEW.status = OLD.status
        OR (to_jsonb(NEW) - 'status' - 'updated_at' - 'updated_by' - 'version')
            IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'updated_at' - 'updated_by' - 'version')
        OR OLD.status = 'CANCELLED'
        -- Tasks close with their separation, and only then.
        OR (NEW.status = 'CANCELLED') <> EXISTS (SELECT 1 FROM people.employment_separation s
            WHERE s.id = OLD.separation_id AND s.state = 'CANCELLED')
        OR (NEW.status <> 'OPEN' AND OLD.status <> 'OPEN' AND NEW.status <> 'CANCELLED')
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'separation_task_immutable',
            MESSAGE = 'a separation task only changes status through an allowed transition';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER separation_task_guard
    BEFORE INSERT OR UPDATE OR DELETE ON people.separation_task
    FOR EACH ROW EXECUTE FUNCTION people.separation_task_guard();

-- Every status, including the initial one, is recorded by the database itself: the history can
-- never disagree with the task.
CREATE FUNCTION people.separation_task_history() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    INSERT INTO people.separation_task_event (tenant_id, task_id, from_status, to_status,
            changed_at, changed_by, version)
        VALUES (NEW.tenant_id, NEW.id, CASE WHEN TG_OP = 'UPDATE' THEN OLD.status END,
            NEW.status, NEW.updated_at, NEW.updated_by, NEW.version);
    RETURN NULL;
END
$fn$;

CREATE TRIGGER separation_task_history
    AFTER INSERT OR UPDATE ON people.separation_task
    FOR EACH ROW EXECUTE FUNCTION people.separation_task_history();

CREATE FUNCTION people.separation_task_event_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'separation_task_event_immutable',
        MESSAGE = 'separation task history is append-only';
END
$fn$;

CREATE TRIGGER separation_task_event_guard
    BEFORE UPDATE OR DELETE ON people.separation_task_event
    FOR EACH ROW EXECUTE FUNCTION people.separation_task_event_guard();
CREATE TRIGGER separation_task_no_truncate
    BEFORE TRUNCATE ON people.separation_task
    FOR EACH STATEMENT EXECUTE FUNCTION people.employment_history_no_truncate();
CREATE TRIGGER separation_task_event_no_truncate
    BEFORE TRUNCATE ON people.separation_task_event
    FOR EACH STATEMENT EXECUTE FUNCTION people.employment_history_no_truncate();

-- =============================================================================================
-- people.employment: only a separation ends an employment, and only its cancellation reopens it
-- =============================================================================================
CREATE FUNCTION people.employment_end_governed() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_end_governed',
            MESSAGE = 'employments are never deleted';
    END IF;
    -- Only the end date and the timeline version ever change.
    IF (to_jsonb(NEW) - 'effective_to' - 'version')
            IS DISTINCT FROM (to_jsonb(OLD) - 'effective_to' - 'version')
        OR (NEW.effective_to IS DISTINCT FROM OLD.effective_to AND NOT (
            -- Closed on the last day of its one active SEPARATION.
            (OLD.effective_to IS NULL AND EXISTS (
                SELECT 1 FROM people.employment_separation s
                JOIN people.employment_change c ON c.id = s.change_id
                WHERE s.employment_id = OLD.id AND s.state <> 'CANCELLED'
                    AND s.last_day = NEW.effective_to AND c.type = 'SEPARATION'
                    AND c.state = 'ACTIVE' AND c.effective_from = NEW.effective_to + 1))
            -- Reopened once that separation is cancelled.
            OR (NEW.effective_to IS NULL AND EXISTS (
                SELECT 1 FROM people.employment_separation s
                JOIN people.employment_change c ON c.id = s.change_id
                WHERE s.employment_id = OLD.id AND s.state = 'CANCELLED'
                    AND s.last_day = OLD.effective_to AND c.state = 'CANCELLED')
                AND NOT EXISTS (SELECT 1 FROM people.employment_separation s
                    WHERE s.employment_id = OLD.id AND s.state <> 'CANCELLED'))))
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_end_governed',
            MESSAGE = 'an employment ends only through a separation';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER employment_end_governed
    BEFORE UPDATE OR DELETE ON people.employment
    FOR EACH ROW EXECUTE FUNCTION people.employment_end_governed();
CREATE TRIGGER employment_no_truncate
    BEFORE TRUNCATE ON people.employment
    FOR EACH STATEMENT EXECUTE FUNCTION people.employment_history_no_truncate();

-- =============================================================================================
-- Active rows lie inside their employment (checked at commit)
-- =============================================================================================
CREATE FUNCTION people.employment_rows_within(p_employment uuid) RETURNS boolean
    LANGUAGE sql STABLE
AS $fn$
SELECT NOT EXISTS (
    SELECT 1 FROM people.employment_assignment a
    JOIN people.employment e ON e.id = a.employment_id
    WHERE a.employment_id = p_employment AND a.superseded_by_change_id IS NULL
        AND (a.effective_from < e.effective_from
            OR (e.effective_to IS NOT NULL
                AND (a.effective_to IS NULL OR a.effective_to > e.effective_to))))
$fn$;

CREATE FUNCTION people.employment_assignment_within_employment() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_employment uuid;
BEGIN
    IF TG_TABLE_NAME = 'employment' THEN
        v_employment := NEW.id;
    ELSE
        v_employment := NEW.employment_id;
    END IF;
    IF NOT people.employment_rows_within(v_employment) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'employment_assignment_within_employment',
            MESSAGE = 'an active assignment extends outside its employment';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER employment_assignment_within_employment
    AFTER INSERT ON people.employment_assignment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.employment_assignment_within_employment();
CREATE CONSTRAINT TRIGGER employment_within_employment
    AFTER UPDATE ON people.employment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.effective_to IS DISTINCT FROM NEW.effective_to)
    EXECUTE FUNCTION people.employment_assignment_within_employment();

-- =============================================================================================
-- A manager is employed on every day of every active reporting line (D22-18; checked at commit
-- under the tenant's manager-graph lock, like employment_manager_acyclic)
-- =============================================================================================
CREATE FUNCTION people.employment_manager_covered(p_tenant uuid, p_manager uuid, p_period daterange)
    RETURNS boolean
    LANGUAGE sql STABLE
AS $fn$
SELECT coalesce((SELECT range_agg(daterange(e.effective_from, e.effective_to, '[]'))
                 FROM people.employment e
                 WHERE e.tenant_id = p_tenant AND e.employee_id = p_manager) @> p_period, false)
$fn$;

CREATE FUNCTION people.employment_manager_employed() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_row people.employment_assignment%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('people.manager-graph:' || NEW.tenant_id, 0));
    IF TG_TABLE_NAME = 'employment' THEN
        -- Every active reporting line naming this employee as manager.
        IF EXISTS (SELECT 1 FROM people.employment_assignment a
            WHERE a.tenant_id = NEW.tenant_id AND a.kind = 'MANAGER'
                AND a.manager_employee_id = NEW.employee_id
                AND a.superseded_by_change_id IS NULL
                AND NOT people.employment_manager_covered(a.tenant_id, a.manager_employee_id,
                    a.period)) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'employment_manager_employed',
                MESSAGE = 'a manager must be employed on every day of a reporting line';
        END IF;
        RETURN NULL;
    END IF;
    -- Re-read the row after the lock: it may have been superseded later in this transaction.
    SELECT * INTO v_row FROM people.employment_assignment WHERE id = NEW.id;
    IF v_row.superseded_by_change_id IS NULL AND NOT people.employment_manager_covered(
        v_row.tenant_id, v_row.manager_employee_id, v_row.period) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_manager_employed',
            MESSAGE = 'a manager must be employed on every day of a reporting line';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER employment_manager_employed
    AFTER INSERT ON people.employment_assignment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.kind = 'MANAGER')
    EXECUTE FUNCTION people.employment_manager_employed();
-- Only when the end date moves: version bumps of ordinary changes never take the graph lock.
CREATE CONSTRAINT TRIGGER employment_manager_employed_on_employment
    AFTER UPDATE ON people.employment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.effective_to IS DISTINCT FROM NEW.effective_to)
    EXECUTE FUNCTION people.employment_manager_employed();

-- The application has always enforced both rules (MVP-021); existing data must already obey them.
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.employment_assignment a
            WHERE a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL
                AND NOT people.employment_manager_covered(a.tenant_id, a.manager_employee_id,
                    a.period))
        OR EXISTS (SELECT 1 FROM people.employment e
            WHERE NOT people.employment_rows_within(e.id)) THEN
        RAISE EXCEPTION 'V15 refused: existing history violates the manager-employed or '
            'within-employment rule';
    END IF;
END
$$;

-- =============================================================================================
-- Change shape: SEPARATION, separation-bound changes and their exact reversal (A22-2)
-- =============================================================================================
CREATE OR REPLACE FUNCTION people.employment_change_shape_check(p_change uuid) RETURNS void
    LANGUAGE plpgsql
AS $fn$
DECLARE
    c          people.employment_change%ROWTYPE;
    v_cancel   people.employment_change%ROWTYPE;
    v_employ   people.employment%ROWTYPE;
    v_sep      people.employment_separation%ROWTYPE;
    v_bad      boolean;
    v_created  integer;
    v_replaced integer;
    v_touched  text[];
    v_kinds    text[];
BEGIN
    SELECT * INTO c FROM people.employment_change WHERE id = p_change;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT count(*) INTO v_created FROM people.employment_assignment
        WHERE created_by_change_id = c.id;
    SELECT count(*) INTO v_replaced FROM people.employment_assignment
        WHERE superseded_by_change_id = c.id;
    -- The distinct kinds the change actually touched, and its normalized kinds[].
    SELECT coalesce(array_agg(DISTINCT kind ORDER BY kind), ARRAY[]::text[]) INTO v_touched
        FROM people.employment_assignment
        WHERE created_by_change_id = c.id OR superseded_by_change_id = c.id;
    SELECT array_agg(DISTINCT k ORDER BY k) INTO v_kinds FROM unnest(c.kinds) AS k;
    IF c.type = 'CANCELLATION' THEN
        SELECT * INTO v_cancel FROM people.employment_change WHERE id = c.cancels_change_id;
    END IF;

    IF c.type = 'HIRE' THEN
        -- One open placement row over the whole employment; nothing replaced.
        SELECT * INTO v_employ FROM people.employment WHERE id = c.employment_id;
        v_bad := v_replaced <> 0 OR v_created <> 1 OR NOT EXISTS (
            SELECT 1 FROM people.employment_assignment a
            WHERE a.created_by_change_id = c.id AND a.kind = 'PLACEMENT'
                AND a.origin_change_id = c.id AND a.restores_assignment_id IS NULL
                AND a.effective_from = v_employ.effective_from
                AND a.effective_to IS NOT DISTINCT FROM v_employ.effective_to);

    ELSIF c.type = 'CHANGE' THEN
        v_bad := v_created + v_replaced = 0
            -- Exactly the change's kinds are touched (R21-1), at most one replaced row per kind.
            OR v_touched <> v_kinds
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.superseded_by_change_id = c.id
                GROUP BY a.kind HAVING count(*) > 1)
            -- New values start on the effective date; nothing is restored.
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.created_by_change_id = c.id AND (a.restores_assignment_id IS NOT NULL
                    OR (a.origin_change_id = c.id AND a.effective_from <> c.effective_from)))
            -- A copied row keeps the replaced row's value, origin and start, and ends the day
            -- before the effective date.
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.created_by_change_id = c.id AND a.origin_change_id <> c.id
                    AND NOT EXISTS (SELECT 1 FROM people.employment_assignment r
                        WHERE r.superseded_by_change_id = c.id AND r.kind = a.kind
                            AND r.origin_change_id = a.origin_change_id
                            AND r.effective_from = a.effective_from
                            AND a.effective_to = c.effective_from - 1
                            AND people.employment_assignment_value(r)
                                = people.employment_assignment_value(a)));
        IF NOT v_bad AND c.separation_id IS NOT NULL THEN
            -- A22-2: one direct-report interval row of the separated manager, rewritten from a
            -- date after the last day with the plan's value (REASSIGN) or nothing (CLEAR).
            SELECT * INTO v_sep FROM people.employment_separation WHERE id = c.separation_id;
            v_bad := NOT FOUND
                OR v_sep.tenant_id <> c.tenant_id
                OR c.employee_id = v_sep.employee_id
                OR v_replaced <> 1
                OR c.effective_from <= v_sep.last_day
                OR EXISTS (SELECT 1 FROM people.employment_assignment r
                    WHERE r.superseded_by_change_id = c.id
                        AND (r.manager_employee_id <> v_sep.employee_id
                            OR (r.effective_to IS NOT NULL AND r.effective_to < c.effective_from)
                            OR r.effective_from > c.effective_from
                            OR (r.effective_from < c.effective_from
                                AND c.effective_from <> v_sep.last_day + 1)))
                OR EXISTS (SELECT 1 FROM people.employment_assignment a
                    JOIN people.employment_assignment r ON r.superseded_by_change_id = c.id
                    WHERE a.created_by_change_id = c.id AND a.origin_change_id = c.id
                        AND (v_sep.report_action <> 'REASSIGN'
                            OR a.manager_employee_id <> v_sep.replacement_manager_id
                            OR a.effective_to IS DISTINCT FROM r.effective_to))
                OR (v_sep.report_action = 'REASSIGN' AND NOT EXISTS (
                    SELECT 1 FROM people.employment_assignment a
                    WHERE a.created_by_change_id = c.id AND a.origin_change_id = c.id));
        END IF;

    ELSIF c.type = 'CORRECTION' THEN
        -- Exactly one active row of the one kind replaced, by exactly one row with identical
        -- dates and the correction as its origin.
        v_bad := v_replaced <> 1 OR v_created <> 1 OR NOT EXISTS (
            SELECT 1 FROM people.employment_assignment n
            JOIN people.employment_assignment o ON o.superseded_by_change_id = c.id
            WHERE n.created_by_change_id = c.id AND n.kind = o.kind AND n.kind = c.kinds[1]
                AND n.origin_change_id = c.id AND n.restores_assignment_id IS NULL
                AND n.effective_from = o.effective_from
                AND n.effective_to IS NOT DISTINCT FROM o.effective_to
                AND o.effective_from = c.effective_from);

    ELSIF c.type = 'SEPARATION' THEN
        -- Every active row extending past the last day D is replaced; each one that started on
        -- or before D is copied over [its start, D] with its value and origin; nothing else is
        -- written. The employment itself is closed on D (employment_end_governed) and no active
        -- row may extend past it (employment_assignment_within_employment).
        SELECT * INTO v_sep FROM people.employment_separation WHERE change_id = c.id;
        v_bad := NOT FOUND
            OR v_sep.id <> c.separation_id
            OR v_sep.employment_id <> c.employment_id
            OR c.effective_from <> v_sep.last_day + 1
            OR v_replaced = 0
            OR v_touched <> v_kinds
            OR EXISTS (SELECT 1 FROM people.employment_assignment r
                WHERE r.superseded_by_change_id = c.id
                    AND r.effective_to IS NOT NULL AND r.effective_to <= v_sep.last_day)
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.created_by_change_id = c.id AND (a.restores_assignment_id IS NOT NULL
                    OR a.effective_to IS DISTINCT FROM v_sep.last_day
                    OR NOT EXISTS (SELECT 1 FROM people.employment_assignment r
                        WHERE r.superseded_by_change_id = c.id AND r.kind = a.kind
                            AND r.origin_change_id = a.origin_change_id
                            AND r.effective_from = a.effective_from
                            AND people.employment_assignment_value(r)
                                = people.employment_assignment_value(a))))
            OR v_created <> (SELECT count(*) FROM people.employment_assignment r
                WHERE r.superseded_by_change_id = c.id AND r.effective_from <= v_sep.last_day);

    ELSIF c.type = 'CANCELLATION' AND (v_cancel.type = 'SEPARATION'
        OR v_cancel.separation_id IS NOT NULL) THEN
        -- Exact reversal (A22-2): every row the cancelled change wrote is replaced by this
        -- cancellation, and every row it replaced is restored once, with its own dates, value and
        -- origin. Nothing else is touched.
        v_bad := v_cancel.state <> 'CANCELLED'
            OR c.kinds <> v_cancel.kinds OR c.effective_from <> v_cancel.effective_from
            OR c.separation_id IS DISTINCT FROM v_cancel.separation_id
            -- Only the cancellation of the whole separation reverses its work.
            OR NOT EXISTS (SELECT 1 FROM people.employment_separation s
                WHERE s.id = v_cancel.separation_id AND s.state = 'CANCELLED')
            OR v_created + v_replaced = 0
            OR v_touched <> v_kinds
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.created_by_change_id = v_cancel.id
                    AND a.superseded_by_change_id IS DISTINCT FROM c.id)
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.superseded_by_change_id = c.id AND a.created_by_change_id <> v_cancel.id)
            OR v_created <> (SELECT count(*) FROM people.employment_assignment r
                WHERE r.superseded_by_change_id = v_cancel.id)
            OR EXISTS (SELECT 1 FROM people.employment_assignment n
                WHERE n.created_by_change_id = c.id AND NOT EXISTS (
                    SELECT 1 FROM people.employment_assignment r
                    WHERE r.id = n.restores_assignment_id
                        AND r.superseded_by_change_id = v_cancel.id
                        AND r.origin_change_id = n.origin_change_id
                        AND r.effective_from = n.effective_from
                        AND r.effective_to IS NOT DISTINCT FROM n.effective_to
                        AND people.employment_assignment_value(r)
                            = people.employment_assignment_value(n)))
            OR EXISTS (SELECT 1 FROM people.employment_assignment n
                WHERE n.created_by_change_id = c.id
                GROUP BY n.restores_assignment_id HAVING count(*) > 1);

    ELSIF c.type = 'CANCELLATION' THEN
        v_bad := v_cancel.type <> 'CHANGE' OR v_cancel.state <> 'CANCELLED'
            OR c.separation_id IS NOT NULL
            OR c.kinds <> v_cancel.kinds OR c.effective_from <> v_cancel.effective_from
            OR v_replaced = 0
            -- Every kind of the cancelled change takes part (R21-1): no partial cancellation.
            OR v_touched <> v_kinds
            -- It replaces only rows written by, or carrying the value of, the cancelled change,
            -- and the earlier rows its restored rows extend.
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.superseded_by_change_id = c.id
                    AND a.created_by_change_id <> v_cancel.id
                    AND a.origin_change_id <> v_cancel.id
                    AND NOT EXISTS (SELECT 1 FROM people.employment_assignment n
                        WHERE n.created_by_change_id = c.id
                            AND n.restores_assignment_id = a.id))
            -- Every restored row names the row whose value and origin it carries: a row the
            -- cancelled change replaced, or an earlier restored row this cancellation extends
            -- (lineage to the cancelled change).
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.created_by_change_id = c.id AND NOT EXISTS (
                    SELECT 1 FROM people.employment_assignment r
                    WHERE r.id = a.restores_assignment_id
                        AND (r.superseded_by_change_id = v_cancel.id
                            OR (r.superseded_by_change_id = c.id
                                AND r.restores_assignment_id IS NOT NULL))
                        AND r.origin_change_id = a.origin_change_id
                        AND people.employment_assignment_value(r)
                            = people.employment_assignment_value(a)));
    END IF;

    IF v_bad THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_change_shape',
            MESSAGE = 'an employment change wrote rows its type does not allow';
    END IF;
END
$fn$;

-- The separation's own row is the last write of a separation; recheck its change at commit.
CREATE FUNCTION people.employment_separation_shape() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    PERFORM people.employment_change_shape_check(NEW.change_id);
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER employment_separation_shape
    AFTER INSERT ON people.employment_separation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.employment_separation_shape();

-- =============================================================================================
-- identity: the employee-to-membership link and the access revocation
-- =============================================================================================
ALTER TABLE identity.tenant_membership
    ADD CONSTRAINT tenant_membership_tenant_id_unique UNIQUE (tenant_id, id),
    ADD CONSTRAINT tenant_membership_tenant_id_role_unique UNIQUE (tenant_id, id, role);

CREATE TABLE identity.employee_access_link (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    employee_id   uuid        NOT NULL,
    membership_id uuid        NOT NULL,
    linked_at     timestamptz NOT NULL,
    linked_by     text        NOT NULL,
    unlinked_at   timestamptz,
    unlinked_by   text,
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT employee_access_link_ownership_unique
        UNIQUE (tenant_id, id, employee_id, membership_id),
    CONSTRAINT employee_access_link_membership FOREIGN KEY (tenant_id, membership_id)
        REFERENCES identity.tenant_membership (tenant_id, id),
    -- Integrity exception (ADR 0008): a constraint on people's key, never a read.
    CONSTRAINT employee_access_link_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT employee_access_link_unlinked_pair
        CHECK ((unlinked_at IS NULL) = (unlinked_by IS NULL)),
    CONSTRAINT employee_access_link_linked_by_length
        CHECK (char_length(linked_by) BETWEEN 1 AND 255),
    CONSTRAINT employee_access_link_unlinked_by_length
        CHECK (unlinked_by IS NULL OR char_length(unlinked_by) BETWEEN 1 AND 255),
    CONSTRAINT employee_access_link_version_non_negative CHECK (version >= 0)
);

COMMENT ON TABLE identity.employee_access_link IS
    'MVP-022 explicit employee-to-membership links; never inferred from names or addresses.';

CREATE UNIQUE INDEX employee_access_link_employee_active
    ON identity.employee_access_link (employee_id) WHERE unlinked_at IS NULL;
CREATE UNIQUE INDEX employee_access_link_membership_active
    ON identity.employee_access_link (membership_id) WHERE unlinked_at IS NULL;
CREATE INDEX employee_access_link_tenant_employee
    ON identity.employee_access_link (tenant_id, employee_id);

CREATE TABLE identity.access_revocation (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL,
    membership_id       uuid        NOT NULL,
    membership_role     text        NOT NULL,
    link_id             uuid        NOT NULL,
    employee_id         uuid        NOT NULL,
    separation_id       uuid        NOT NULL,
    -- The instant DivalHR access ends; the membership gate compares it with the database clock.
    effective_at        timestamptz NOT NULL,
    state               text        NOT NULL,
    attempts            integer     NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz,
    lease_owner         uuid,
    lease_until         timestamptz,
    outcome_code        text,
    requested_at        timestamptz NOT NULL,
    effective_marked_at timestamptz,
    idp_completed_at    timestamptz,
    cancelled_at        timestamptz,
    cancelled_by        text,
    version             bigint      NOT NULL DEFAULT 0,
    -- Only an employee-role membership of the same tenant can ever be revoked (D22-6): a
    -- tenant administrator is never a target, so the last administrator cannot lose access here.
    CONSTRAINT access_revocation_membership FOREIGN KEY (tenant_id, membership_id, membership_role)
        REFERENCES identity.tenant_membership (tenant_id, id, role),
    CONSTRAINT access_revocation_membership_employee_role CHECK (membership_role = 'employee'),
    CONSTRAINT access_revocation_link FOREIGN KEY (tenant_id, link_id, employee_id, membership_id)
        REFERENCES identity.employee_access_link (tenant_id, id, employee_id, membership_id),
    -- Integrity exception (ADR 0008): a constraint on people's key, never a read.
    CONSTRAINT access_revocation_separation FOREIGN KEY (tenant_id, separation_id, employee_id)
        REFERENCES people.employment_separation (tenant_id, id, employee_id),
    CONSTRAINT access_revocation_state_valid CHECK (state IN
        ('SCHEDULED', 'IDP_PENDING', 'COMPLETED', 'MANUAL_INTERVENTION', 'CANCELLED')),
    CONSTRAINT access_revocation_attempts_range CHECK (attempts BETWEEN 0 AND 10),
    CONSTRAINT access_revocation_outcome_valid CHECK (outcome_code IS NULL OR outcome_code IN (
        'REVOKED', 'ABSENT', 'REFUSED', 'UNAVAILABLE', 'ATTEMPTS_EXHAUSTED', 'STALE_LINK',
        'MEMBERSHIP_CHANGED', 'TENANT_MISMATCH')),
    CONSTRAINT access_revocation_lease_pair
        CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT access_revocation_cancelled_by_length
        CHECK (cancelled_by IS NULL OR char_length(cancelled_by) BETWEEN 1 AND 255),
    CONSTRAINT access_revocation_version_non_negative CHECK (version >= 0),
    CONSTRAINT access_revocation_state_consistent CHECK (
        CASE state
            WHEN 'SCHEDULED' THEN effective_marked_at IS NULL AND next_attempt_at IS NULL
                AND lease_owner IS NULL AND idp_completed_at IS NULL AND cancelled_at IS NULL
                AND cancelled_by IS NULL AND attempts = 0 AND outcome_code IS NULL
            WHEN 'IDP_PENDING' THEN effective_marked_at IS NOT NULL AND next_attempt_at IS NOT NULL
                AND idp_completed_at IS NULL AND cancelled_at IS NULL
            WHEN 'COMPLETED' THEN effective_marked_at IS NOT NULL AND next_attempt_at IS NULL
                AND lease_owner IS NULL AND idp_completed_at IS NOT NULL AND cancelled_at IS NULL
                AND outcome_code IN ('REVOKED', 'ABSENT')
            WHEN 'MANUAL_INTERVENTION' THEN effective_marked_at IS NOT NULL
                AND next_attempt_at IS NULL AND lease_owner IS NULL AND idp_completed_at IS NULL
                AND cancelled_at IS NULL AND outcome_code IS NOT NULL
                AND outcome_code NOT IN ('REVOKED', 'ABSENT', 'UNAVAILABLE')
            WHEN 'CANCELLED' THEN effective_marked_at IS NULL AND next_attempt_at IS NULL
                AND lease_owner IS NULL AND idp_completed_at IS NULL AND cancelled_at IS NOT NULL
                AND cancelled_by IS NOT NULL AND attempts = 0 AND outcome_code IS NULL
            ELSE FALSE
        END)
);

COMMENT ON TABLE identity.access_revocation IS
    'MVP-022 DivalHR access revocation of a linked employee membership and its identity-provider '
    'retry state. No subject, address, token or error body is stored.';

-- One revocation that is not cancelled per membership (a membership ID belongs to one tenant
-- through the composite key), and the membership gate's index: the gate correlates the tenant and
-- the membership (R22-1).
CREATE UNIQUE INDEX access_revocation_one_open
    ON identity.access_revocation (tenant_id, membership_id) WHERE state <> 'CANCELLED';
CREATE UNIQUE INDEX access_revocation_one_per_separation
    ON identity.access_revocation (separation_id) WHERE state <> 'CANCELLED';
CREATE INDEX access_revocation_link ON identity.access_revocation (link_id);
CREATE INDEX access_revocation_due
    ON identity.access_revocation (effective_at) WHERE state = 'SCHEDULED';
CREATE INDEX access_revocation_pending
    ON identity.access_revocation (next_attempt_at) WHERE state = 'IDP_PENDING';
CREATE INDEX access_revocation_separation ON identity.access_revocation (separation_id);

CREATE FUNCTION identity.employee_access_link_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employee_access_link_immutable',
            MESSAGE = 'access links are never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.version <> 0 OR NEW.unlinked_at IS NOT NULL
            -- A membership whose access was revoked is never linked again.
            OR EXISTS (SELECT 1 FROM identity.access_revocation r
                WHERE r.membership_id = NEW.membership_id AND r.state <> 'CANCELLED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employee_access_link_immutable',
                MESSAGE = 'a link is inserted active, for a membership that was not revoked';
        END IF;
        RETURN NEW;
    END IF;
    -- The only transition: an active link is unlinked, once, while no revocation needs it.
    IF OLD.unlinked_at IS NOT NULL OR NEW.unlinked_at IS NULL
        OR NEW.version <> OLD.version + 1
        OR (to_jsonb(NEW) - 'unlinked_at' - 'unlinked_by' - 'version')
            IS DISTINCT FROM (to_jsonb(OLD) - 'unlinked_at' - 'unlinked_by' - 'version')
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employee_access_link_immutable',
            MESSAGE = 'an active link is only unlinked, once';
    END IF;
    IF EXISTS (SELECT 1 FROM identity.access_revocation r
        WHERE r.link_id = OLD.id AND r.state <> 'CANCELLED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employee_access_link_locked',
            MESSAGE = 'a link held by a separation cannot be removed';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER employee_access_link_guard
    BEFORE INSERT OR UPDATE OR DELETE ON identity.employee_access_link
    FOR EACH ROW EXECUTE FUNCTION identity.employee_access_link_guard();

CREATE FUNCTION identity.access_revocation_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_now timestamptz := statement_timestamp();
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'access_revocation_immutable',
            MESSAGE = 'access revocations are never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        -- Inserted SCHEDULED for a future instant, or IDP_PENDING once it has passed; always
        -- through the employee's active link to that membership.
        IF NEW.version <> 0 OR NEW.attempts <> 0 OR NEW.lease_owner IS NOT NULL
            OR NOT ((NEW.state = 'SCHEDULED' AND NEW.effective_at > v_now)
                OR (NEW.state = 'IDP_PENDING' AND NEW.effective_at <= v_now
                    AND NEW.outcome_code IS NULL))
            OR NOT EXISTS (SELECT 1 FROM identity.employee_access_link l
                WHERE l.id = NEW.link_id AND l.unlinked_at IS NULL) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'access_revocation_immutable',
                MESSAGE = 'a revocation starts SCHEDULED or IDP_PENDING on an active link';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.version <> OLD.version + 1
        OR NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id
        OR NEW.membership_id <> OLD.membership_id OR NEW.membership_role <> OLD.membership_role
        OR NEW.link_id <> OLD.link_id OR NEW.employee_id <> OLD.employee_id
        OR NEW.separation_id <> OLD.separation_id OR NEW.effective_at <> OLD.effective_at
        OR NEW.requested_at <> OLD.requested_at
        OR NOT (
            (OLD.state = 'SCHEDULED' AND NEW.state = 'CANCELLED' AND v_now < OLD.effective_at)
            OR (OLD.state = 'SCHEDULED' AND NEW.state = 'IDP_PENDING'
                AND OLD.effective_at <= v_now)
            OR (OLD.state = 'IDP_PENDING'
                AND NEW.state IN ('IDP_PENDING', 'COMPLETED', 'MANUAL_INTERVENTION')
                AND NEW.effective_marked_at = OLD.effective_marked_at)
            OR (OLD.state = 'MANUAL_INTERVENTION' AND NEW.state = 'IDP_PENDING'
                AND NEW.attempts = 0 AND NEW.effective_marked_at = OLD.effective_marked_at))
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'access_revocation_immutable',
            MESSAGE = 'an access revocation only moves through its allowed states';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER access_revocation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON identity.access_revocation
    FOR EACH ROW EXECUTE FUNCTION identity.access_revocation_guard();

CREATE FUNCTION identity.access_history_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'access_history_no_truncate',
        MESSAGE = 'access links and revocations are never truncated';
END
$fn$;

CREATE TRIGGER employee_access_link_no_truncate
    BEFORE TRUNCATE ON identity.employee_access_link
    FOR EACH STATEMENT EXECUTE FUNCTION identity.access_history_no_truncate();
CREATE TRIGGER access_revocation_no_truncate
    BEFORE TRUNCATE ON identity.access_revocation
    FOR EACH STATEMENT EXECUTE FUNCTION identity.access_history_no_truncate();
