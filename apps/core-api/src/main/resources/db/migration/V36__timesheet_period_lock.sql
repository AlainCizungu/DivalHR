-- MVP-071 (Issue #139): a tenant administrator with exact MFA locks one site's generated week once
-- every timesheet of it is currently APPROVED, as stable payroll-source evidence; a locked week
-- refuses attendance corrections, refreshes and every timesheet transition until the administrator
-- deliberately unlocks it with a closed reason and an explanation. Additive to V35.
--
-- Ownership: the people module; only the bounded com.divalhr.core.people.schedule package reads
-- or writes these objects (ADR 0022).
--
-- Classification: Restricted HR/Operations (the unlock explanation, counts and worked time of one
-- site's week). No name, employee number, subject or explanation leaves this schema through audit
-- metadata, events, logs or errors.
--
-- Shape (L71-1, L71-8):
--   * people.timesheet_period_lock: the current lock state of one generation (one row per
--     generation, created by its first lock): OPEN or LOCKED, the lock version (the number of
--     transitions so far) and the current period action.
--   * people.timesheet_period_action: append-only LOCKED | UNLOCKED evidence of one generation,
--     numbered 1, 2, ... (the version after the transition); the exact scope and week boundaries,
--     the generation version, the approval evidence digest (every timesheet's current approval,
--     revision and cycle), the counts and total worked minutes, the verified actor and the instant;
--     an UNLOCKED action names the LOCKED action it reverses and carries the closed reason code and
--     the explanation (the attendance-correction explanation grammar).
--   * timesheet_period_action_transition (at insert): the sequence follows the latest action,
--     LOCKED and UNLOCKED alternate from LOCKED, the instant does not go backwards, the scope is
--     exactly the generation's and its site's legal entity, the generation version is the current
--     one; a LOCKED action binds a fully approved current generation (at least one timesheet, every
--     timesheet APPROVED by its current action at its current revision and cycle, with exactly the
--     counted timesheets, employees, rows and minutes); an
--     UNLOCKED action reverses the current LOCKED action.
--   * timesheet_period_lock_guard: the projection is inserted LOCKED at version 1 with its action,
--     changes only to the next version with the opposite state and the matching new action, and is
--     never deleted.
--   * deferred (timesheet_period_lock_current): at commit the projection shows the latest action.
--   * Guards while LOCKED (L71-5): no timesheet of the generation changes (no revision, no
--     transition), no timesheet action is appended, the generation is not refreshed, and no
--     attendance correction is committed for a slot whose site-local date lies in the locked week.
--     The application's shared guard refuses first with a stable code; these triggers make the
--     boundary hold against every write path.
--   * Both tables are append-only in evidence: actions are never updated, deleted or truncated;
--     the projection is never deleted or truncated.
--
-- Indexes: timesheet_period_action_history (newest first per generation); the projection's primary
-- key (the guard's lookup); timesheet_period_lock_site_week (the correction guard's lookup).
--
-- Rollback: db/rollback/V36__rollback.sql (manual, never run by Flyway) restores V35 exactly only
-- while no period action and no lock projection exists; there is no override.

-- ---------------------------------------------------------------------------------------------
-- The current lock state of a generation
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.timesheet_period_lock (
    tenant_id         uuid        NOT NULL,
    generation_id     uuid        NOT NULL,
    site_id           uuid        NOT NULL,
    week_start        date        NOT NULL,
    state             text        NOT NULL,
    version           integer     NOT NULL,
    current_action_id uuid        NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT timesheet_period_lock_pkey PRIMARY KEY (tenant_id, generation_id),
    CONSTRAINT timesheet_period_lock_generation
        FOREIGN KEY (tenant_id, generation_id, site_id, week_start)
            REFERENCES people.timesheet_generation (tenant_id, id, site_id, week_start),
    CONSTRAINT timesheet_period_lock_state CHECK (state IN ('OPEN', 'LOCKED')),
    CONSTRAINT timesheet_period_lock_version CHECK (version >= 1),
    -- Odd versions are locks, even versions unlocks.
    CONSTRAINT timesheet_period_lock_parity
        CHECK ((state = 'LOCKED') = (version % 2 = 1))
);

COMMENT ON TABLE people.timesheet_period_lock IS
    'MVP-071 current lock state of one site''s generated week (Restricted HR/Operations): OPEN or LOCKED, the lock version and the current LOCKED or UNLOCKED action.';

CREATE INDEX timesheet_period_lock_site_week
    ON people.timesheet_period_lock (tenant_id, site_id, week_start) WHERE state = 'LOCKED';

-- ---------------------------------------------------------------------------------------------
-- The append-only lock and unlock evidence
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.timesheet_period_action (
    tenant_id          uuid        NOT NULL,
    id                 uuid        NOT NULL,
    generation_id      uuid        NOT NULL,
    sequence           integer     NOT NULL,
    action             text        NOT NULL,
    legal_entity_id    uuid        NOT NULL,
    site_id            uuid        NOT NULL,
    week_start         date        NOT NULL,
    week_end           date        NOT NULL,
    site_timezone      text        NOT NULL,
    period_start_at    timestamptz NOT NULL,
    period_end_at      timestamptz NOT NULL,
    generation_version integer     NOT NULL,
    timesheet_count    integer     NOT NULL,
    employee_count     integer     NOT NULL,
    row_count          integer     NOT NULL,
    worked_minutes     integer     NOT NULL,
    evidence_digest    text        NOT NULL,
    locked_action_id   uuid,
    unlock_reason      text,
    unlock_explanation text,
    actor_subject      text        NOT NULL,
    occurred_at        timestamptz NOT NULL,
    CONSTRAINT timesheet_period_action_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT timesheet_period_action_sequence_unique UNIQUE (tenant_id, generation_id, sequence),
    CONSTRAINT timesheet_period_action_generation_key UNIQUE (tenant_id, id, generation_id),
    CONSTRAINT timesheet_period_action_generation
        FOREIGN KEY (tenant_id, generation_id, site_id, week_start)
            REFERENCES people.timesheet_generation (tenant_id, id, site_id, week_start),
    -- ADR 0022: the database-level site and legal-entity keys are the documented integrity
    -- exception (as ADR 0014, ADR 0018 and ADR 0021); the application reads units through its port.
    CONSTRAINT timesheet_period_action_site
        FOREIGN KEY (tenant_id, site_id) REFERENCES tenant.site (tenant_id, id),
    CONSTRAINT timesheet_period_action_legal_entity
        FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES tenant.legal_entity (tenant_id, id),
    -- An unlock reverses a LOCKED action of the same generation.
    CONSTRAINT timesheet_period_action_locked
        FOREIGN KEY (tenant_id, locked_action_id, generation_id)
            REFERENCES people.timesheet_period_action (tenant_id, id, generation_id),
    CONSTRAINT timesheet_period_action_kind CHECK (action IN ('LOCKED', 'UNLOCKED')),
    -- Odd sequences lock, even sequences unlock.
    CONSTRAINT timesheet_period_action_parity
        CHECK (sequence >= 1 AND (action = 'LOCKED') = (sequence % 2 = 1)),
    CONSTRAINT timesheet_period_action_week
        CHECK (EXTRACT(ISODOW FROM week_start) = 1 AND week_end = week_start + 7
            AND week_start BETWEEN DATE '2000-01-03' AND DATE '2999-12-30'),
    CONSTRAINT timesheet_period_action_timezone_format
        CHECK (site_timezone ~ '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}$'
            AND char_length(site_timezone) BETWEEN 3 AND 64),
    CONSTRAINT timesheet_period_action_period
        CHECK (period_end_at - period_start_at BETWEEN INTERVAL '166 hours' AND INTERVAL '170 hours'),
    CONSTRAINT timesheet_period_action_counts
        CHECK (generation_version >= 1 AND timesheet_count >= 1
            AND employee_count = timesheet_count AND row_count >= timesheet_count
            AND worked_minutes >= 0),
    CONSTRAINT timesheet_period_action_digest CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT timesheet_period_action_subject CHECK (char_length(actor_subject) BETWEEN 1 AND 255),
    CONSTRAINT timesheet_period_action_reason
        CHECK (unlock_reason IS NULL OR unlock_reason IN ('ATTENDANCE_CORRECTION_REQUIRED',
            'TIMESHEET_EVIDENCE_ERROR', 'ORGANIZATION_REQUEST', 'OTHER')),
    -- The same explanation grammar as an attendance correction's (10 to 500 trimmed characters,
    -- no control character, markup or link).
    CONSTRAINT timesheet_period_action_explanation
        CHECK (unlock_explanation IS NULL
            OR people.attendance_correction_explanation_valid(unlock_explanation)),
    CONSTRAINT timesheet_period_action_shape CHECK (CASE action
        WHEN 'LOCKED' THEN locked_action_id IS NULL AND unlock_reason IS NULL
            AND unlock_explanation IS NULL
        ELSE locked_action_id IS NOT NULL AND unlock_reason IS NOT NULL
            AND unlock_explanation IS NOT NULL
        END)
);

COMMENT ON TABLE people.timesheet_period_action IS
    'MVP-071 append-only lock and unlock evidence of one site''s generated week (Restricted HR/Operations): the scope and exact boundaries, the generation version, the approval evidence digest, counts and worked minutes, the reversed lock, the unlock reason and explanation, the verified actor and the instant.';

CREATE INDEX timesheet_period_action_history
    ON people.timesheet_period_action (tenant_id, generation_id, sequence DESC);

-- ---------------------------------------------------------------------------------------------
-- Append-only
-- ---------------------------------------------------------------------------------------------

CREATE TRIGGER timesheet_period_action_immutable
    BEFORE UPDATE OR DELETE ON people.timesheet_period_action
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_immutable();
CREATE TRIGGER timesheet_period_action_no_truncate
    BEFORE TRUNCATE ON people.timesheet_period_action
    FOR EACH STATEMENT EXECUTE FUNCTION people.timesheet_no_truncate();
CREATE TRIGGER timesheet_period_lock_no_truncate
    BEFORE TRUNCATE ON people.timesheet_period_lock
    FOR EACH STATEMENT EXECUTE FUNCTION people.timesheet_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Transitions
-- ---------------------------------------------------------------------------------------------

-- Whether a generation is fully approved now with exactly these counts: at least one timesheet,
-- every timesheet APPROVED by its current action at its current revision and cycle (an approval
-- binds the generation version of its day; a refresh never rewrites an approved timesheet, so that
-- version may be older than the generation's and is recorded, not required), and the counted
-- timesheets, employees, rows and worked minutes.
CREATE FUNCTION people.timesheet_period_fully_approved(
    p_tenant uuid, p_generation uuid, p_timesheets integer, p_employees integer,
    p_rows integer, p_minutes integer) RETURNS boolean
    LANGUAGE sql STABLE
AS $fn$
SELECT p_timesheets >= 1
    AND NOT EXISTS (
        SELECT 1 FROM people.timesheet t
        LEFT JOIN people.timesheet_action a
            ON a.tenant_id = t.tenant_id AND a.id = t.current_action_id AND a.timesheet_id = t.id
        WHERE t.tenant_id = p_tenant AND t.generation_id = p_generation
            AND (t.status <> 'APPROVED' OR a.id IS NULL OR a.action <> 'APPROVED'
                OR a.revision <> t.current_revision OR a.cycle <> t.submission_cycle
                OR a.row_count <> t.row_count OR a.worked_minutes <> t.worked_minutes))
    AND (SELECT count(*) FROM people.timesheet t
            WHERE t.tenant_id = p_tenant AND t.generation_id = p_generation) = p_timesheets
    AND (SELECT count(DISTINCT t.employee_id) FROM people.timesheet t
            WHERE t.tenant_id = p_tenant AND t.generation_id = p_generation) = p_employees
    AND (SELECT coalesce(sum(t.row_count), 0) FROM people.timesheet t
            WHERE t.tenant_id = p_tenant AND t.generation_id = p_generation) = p_rows
    AND (SELECT coalesce(sum(t.worked_minutes), 0) FROM people.timesheet t
            WHERE t.tenant_id = p_tenant AND t.generation_id = p_generation) = p_minutes
$fn$;

CREATE FUNCTION people.timesheet_period_action_transition() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_generation people.timesheet_generation%ROWTYPE;
    v_latest     people.timesheet_period_action%ROWTYPE;
BEGIN
    SELECT * INTO v_generation FROM people.timesheet_generation
    WHERE tenant_id = NEW.tenant_id AND id = NEW.generation_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23503', CONSTRAINT = 'timesheet_period_action_generation',
            MESSAGE = 'a period action needs its generation';
    END IF;
    SELECT * INTO v_latest FROM people.timesheet_period_action
    WHERE tenant_id = NEW.tenant_id AND generation_id = NEW.generation_id
    ORDER BY sequence DESC LIMIT 1;
    IF NEW.sequence IS DISTINCT FROM coalesce(v_latest.sequence, 0) + 1
        OR (v_latest.id IS NOT NULL AND NEW.occurred_at < v_latest.occurred_at)
        OR (v_latest.id IS NULL AND NEW.action <> 'LOCKED')
        OR (v_latest.id IS NOT NULL AND NEW.action = v_latest.action) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_action_sequence',
            MESSAGE = 'a period action follows the latest one; locks and unlocks alternate';
    END IF;
    IF NEW.site_id <> v_generation.site_id OR NEW.week_start <> v_generation.week_start
        OR NEW.week_end <> v_generation.week_end
        OR NEW.site_timezone <> v_generation.site_timezone
        OR NEW.period_start_at <> v_generation.period_start_at
        OR NEW.period_end_at <> v_generation.period_end_at
        OR NEW.generation_version <> v_generation.version
        OR NOT EXISTS (SELECT 1 FROM tenant.site s
            WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.site_id
                AND s.legal_entity_id = NEW.legal_entity_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_action_scope',
            MESSAGE = 'a period action binds its generation''s exact scope and current version';
    END IF;
    IF NEW.action = 'LOCKED' THEN
        IF NOT people.timesheet_period_fully_approved(NEW.tenant_id, NEW.generation_id,
                NEW.timesheet_count, NEW.employee_count, NEW.row_count, NEW.worked_minutes) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_action_approved',
                MESSAGE = 'a lock binds a fully approved generation with exactly its counts';
        END IF;
    ELSIF NEW.locked_action_id IS DISTINCT FROM v_latest.id
        OR NEW.timesheet_count <> v_latest.timesheet_count
        OR NEW.employee_count <> v_latest.employee_count
        OR NEW.row_count <> v_latest.row_count
        OR NEW.worked_minutes <> v_latest.worked_minutes
        OR NEW.evidence_digest <> v_latest.evidence_digest THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_action_unlock',
            MESSAGE = 'an unlock reverses the current lock and carries its evidence';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER timesheet_period_action_transition
    BEFORE INSERT ON people.timesheet_period_action
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_period_action_transition();

-- The projection starts LOCKED at version 1 with its action; it changes only to its next version
-- with the opposite state and the new action of that sequence; never deleted.
CREATE FUNCTION people.timesheet_period_lock_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_action people.timesheet_period_action%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_lock_immutable',
            MESSAGE = 'a period lock projection is never deleted';
    END IF;
    SELECT * INTO v_action FROM people.timesheet_period_action
    WHERE tenant_id = NEW.tenant_id AND id = NEW.current_action_id
        AND generation_id = NEW.generation_id;
    IF v_action.id IS NULL OR v_action.sequence <> NEW.version
        OR (v_action.action = 'LOCKED') <> (NEW.state = 'LOCKED')
        OR v_action.site_id <> NEW.site_id OR v_action.week_start <> NEW.week_start
        OR NEW.updated_at <> v_action.occurred_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_lock_guard',
            MESSAGE = 'a period lock projection shows its action';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.version <> 1 OR NEW.state <> 'LOCKED' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_lock_guard',
                MESSAGE = 'a period lock projection starts locked at version 1';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.generation_id IS DISTINCT FROM OLD.generation_id
        OR NEW.site_id IS DISTINCT FROM OLD.site_id
        OR NEW.week_start IS DISTINCT FROM OLD.week_start
        OR NEW.version <> OLD.version + 1 OR NEW.state = OLD.state
        OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_lock_guard',
            MESSAGE = 'a period lock projection changes only by its next transition';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER timesheet_period_lock_guard
    BEFORE INSERT OR UPDATE OR DELETE ON people.timesheet_period_lock
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_period_lock_guard();

-- Deferred: the projection shows the generation's latest period action.
CREATE FUNCTION people.timesheet_period_lock_current() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_lock   people.timesheet_period_lock%ROWTYPE;
    v_latest people.timesheet_period_action%ROWTYPE;
BEGIN
    SELECT * INTO v_lock FROM people.timesheet_period_lock
    WHERE tenant_id = NEW.tenant_id AND generation_id = NEW.generation_id;
    SELECT * INTO v_latest FROM people.timesheet_period_action
    WHERE tenant_id = NEW.tenant_id AND generation_id = NEW.generation_id
    ORDER BY sequence DESC LIMIT 1;
    IF v_lock.generation_id IS NULL OR v_latest.id IS NULL
        OR v_lock.current_action_id IS DISTINCT FROM v_latest.id
        OR v_lock.version <> v_latest.sequence
        OR (v_lock.state = 'LOCKED') <> (v_latest.action = 'LOCKED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_lock_current',
            MESSAGE = 'a period lock projection shows the latest period action';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER timesheet_period_lock_current
    AFTER INSERT OR UPDATE ON people.timesheet_period_lock
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_period_lock_current();
CREATE CONSTRAINT TRIGGER timesheet_period_action_current
    AFTER INSERT ON people.timesheet_period_action
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_period_lock_current();

-- ---------------------------------------------------------------------------------------------
-- Guards while locked (L71-5)
-- ---------------------------------------------------------------------------------------------

-- Whether the generation is LOCKED now.
CREATE FUNCTION people.timesheet_period_is_locked(p_tenant uuid, p_generation uuid)
    RETURNS boolean
    LANGUAGE sql STABLE
AS $fn$
SELECT EXISTS (SELECT 1 FROM people.timesheet_period_lock k
    WHERE k.tenant_id = p_tenant AND k.generation_id = p_generation AND k.state = 'LOCKED')
$fn$;

-- No timesheet of a locked generation changes (no revision, no state, no action).
CREATE FUNCTION people.timesheet_period_guard_timesheet() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF people.timesheet_period_is_locked(OLD.tenant_id, OLD.generation_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_locked',
            MESSAGE = 'a timesheet of a locked period does not change';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER timesheet_period_guard_timesheet
    BEFORE UPDATE ON people.timesheet
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_period_guard_timesheet();

-- No action is appended to a timesheet of a locked generation.
CREATE FUNCTION people.timesheet_period_guard_action() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF EXISTS (SELECT 1 FROM people.timesheet t
        WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.timesheet_id
            AND people.timesheet_period_is_locked(t.tenant_id, t.generation_id)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_locked',
            MESSAGE = 'a timesheet of a locked period receives no action';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER timesheet_period_guard_action
    BEFORE INSERT ON people.timesheet_action
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_period_guard_action();

-- A locked generation is not refreshed.
CREATE FUNCTION people.timesheet_period_guard_generation() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF people.timesheet_period_is_locked(OLD.tenant_id, OLD.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_locked',
            MESSAGE = 'a locked period is not refreshed';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER timesheet_period_guard_generation
    BEFORE UPDATE ON people.timesheet_generation
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_period_guard_generation();

-- No attendance correction is committed for a slot whose site-local date lies in a locked week of
-- the slot's site (the generation's zone decides the local date).
CREATE FUNCTION people.timesheet_period_guard_correction() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF EXISTS (
        SELECT 1 FROM people.work_schedule s
        JOIN people.timesheet_period_lock k
            ON k.tenant_id = s.tenant_id AND k.site_id = s.site_id AND k.state = 'LOCKED'
        JOIN people.timesheet_generation g
            ON g.tenant_id = k.tenant_id AND g.id = k.generation_id
        WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.schedule_id
            AND (NEW.slot_start_at AT TIME ZONE g.site_timezone)::date >= g.week_start
            AND (NEW.slot_start_at AT TIME ZONE g.site_timezone)::date < g.week_end) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_period_locked',
            MESSAGE = 'a shift of a locked period is not corrected';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER timesheet_period_guard_correction
    BEFORE INSERT ON people.attendance_correction
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_period_guard_correction();
