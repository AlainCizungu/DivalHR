-- MVP-050B (Issue #101): a tenant administrator cancels or fully replaces an entirely future
-- published work schedule. The published schedule and its slots stay immutable evidence; the
-- schedule's state (PUBLISHED, CANCELLED, REPLACED) is derived from append-only terminal evidence.
--
-- Ownership: the people module owns every table here; only the bounded
-- com.divalhr.core.people.schedule package reads or writes them (ADR 0014).
--
-- Classification: cancellation and replacement reasons are Restricted HR; schedules and
-- assignments stay Restricted HR/Operations. The actor subject is evidence only, never returned.
--
-- Database authority (V26):
--   * people.work_schedule and people.work_schedule_slot keep every V25 rule (no update, delete or
--     truncate; no status column);
--   * terminal evidence is append-only (work_schedule_cancellation, work_schedule_replacement:
--     _immutable, _no_truncate); a schedule has at most one terminal action, cancellation or
--     replacement, never both (work_schedule_terminal_guard, which locks the original schedule
--     row); a terminal action is recorded only while every original slot starts strictly after
--     the action's recorded instant (the change window); reasons follow the decision-reason
--     grammar people.leave_reason_valid (V20);
--   * a replacement names a different schedule of the tenant with the same site and inclusive
--     period, published at the replacement's instant (so strictly later than the original:
--     chains A -> B -> C have no cycle), with no terminal action and no predecessor of its own
--     (unique successor: no branch), whose slots all start after that instant
--     (work_schedule_terminal_guard);
--   * the V25 exclusions move from the evidence to two guarded operational projections (the
--     R98-1 pattern of ADR 0013): people.work_schedule_site_coverage (one row per operational
--     schedule; work_schedule_site_no_overlap) and people.work_schedule_slot_coverage (one row per
--     assigned slot of an operational schedule; work_schedule_slot_employee_no_overlap). Each row
--     is bound to its exact evidence by a composite foreign key; a row is never updated or
--     truncated, inserted only for a schedule without terminal action and deleted only once its
--     schedule has one (work_schedule_site_coverage_guard, work_schedule_slot_coverage_guard);
--     deferred constraint triggers require every schedule without terminal action to have exactly
--     its site row and one row per assigned slot, and every terminal schedule to have none
--     (work_schedule_coverage_exact). The projections enforce conflicts; they are never the source
--     of state or authorization.
-- Every V25 schedule and assigned slot is backfilled into the projections.
--
-- Indexes: work_schedule_site_no_overlap (tenant, site, period) and
-- work_schedule_slot_employee_no_overlap (tenant, employee, instants) are the conflict checks;
-- work_schedule_site_coverage_pkey and work_schedule_slot_coverage_schedule the terminal action's
-- deletes and the exactness counts; work_schedule_replacement_successor (unique) the predecessor
-- lookup;
-- work_schedule_coverage_key and work_schedule_slot_coverage_key the projections' composite keys.
--
-- Rollback: db/rollback/V26__rollback.sql (manual, never run by Flyway). It restores V25 exactly
-- only while no cancellation or replacement exists and the projections still match every schedule
-- exactly; there is no override, and it never touches flyway_schema_history.

-- ---------------------------------------------------------------------------------------------
-- The V25 exclusions leave the evidence (they are recreated on the projections below)
-- ---------------------------------------------------------------------------------------------

ALTER TABLE people.work_schedule DROP CONSTRAINT work_schedule_site_no_overlap;
ALTER TABLE people.work_schedule_slot DROP CONSTRAINT work_schedule_slot_employee_no_overlap;

-- The targets of the projections' composite keys (new indexes; no row is rewritten).
ALTER TABLE people.work_schedule
    ADD CONSTRAINT work_schedule_coverage_key
        UNIQUE (tenant_id, id, site_id, first_date, last_date);
ALTER TABLE people.work_schedule_slot
    ADD CONSTRAINT work_schedule_slot_coverage_key
        UNIQUE (tenant_id, id, schedule_id, assigned_employee_id, start_at, end_at);

-- ---------------------------------------------------------------------------------------------
-- Terminal evidence: cancellation and replacement (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.work_schedule_terminal_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_immutable',
        MESSAGE = 'work schedule terminal evidence is never changed or deleted';
END
$fn$;

CREATE FUNCTION people.work_schedule_terminal_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_no_truncate',
        MESSAGE = 'work schedule terminal evidence is never truncated';
END
$fn$;

CREATE TABLE people.work_schedule_cancellation (
    tenant_id    uuid        NOT NULL,
    schedule_id  uuid        NOT NULL,
    reason       text        NOT NULL,
    cancelled_at timestamptz NOT NULL,
    cancelled_by text        NOT NULL,
    CONSTRAINT work_schedule_cancellation_pkey PRIMARY KEY (tenant_id, schedule_id),
    CONSTRAINT work_schedule_cancellation_schedule FOREIGN KEY (tenant_id, schedule_id)
        REFERENCES people.work_schedule (tenant_id, id),
    CONSTRAINT work_schedule_cancellation_reason_valid CHECK (people.leave_reason_valid(reason)),
    CONSTRAINT work_schedule_cancellation_by_length
        CHECK (char_length(cancelled_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.work_schedule_cancellation IS
    'MVP-050B cancellation of a future published work schedule (Restricted HR reason). Append-only evidence; at most one terminal action per schedule.';

CREATE TABLE people.work_schedule_replacement (
    tenant_id               uuid        NOT NULL,
    schedule_id             uuid        NOT NULL,
    replacement_schedule_id uuid        NOT NULL,
    reason                  text        NOT NULL,
    replaced_at             timestamptz NOT NULL,
    replaced_by             text        NOT NULL,
    CONSTRAINT work_schedule_replacement_pkey PRIMARY KEY (tenant_id, schedule_id),
    CONSTRAINT work_schedule_replacement_successor UNIQUE (tenant_id, replacement_schedule_id),
    CONSTRAINT work_schedule_replacement_schedule FOREIGN KEY (tenant_id, schedule_id)
        REFERENCES people.work_schedule (tenant_id, id),
    CONSTRAINT work_schedule_replacement_successor_schedule
        FOREIGN KEY (tenant_id, replacement_schedule_id)
        REFERENCES people.work_schedule (tenant_id, id),
    CONSTRAINT work_schedule_replacement_distinct CHECK (replacement_schedule_id <> schedule_id),
    CONSTRAINT work_schedule_replacement_reason_valid CHECK (people.leave_reason_valid(reason)),
    CONSTRAINT work_schedule_replacement_by_length
        CHECK (char_length(replaced_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.work_schedule_replacement IS
    'MVP-050B full replacement of a future published work schedule by a new schedule of the same site and period (Restricted HR reason). Append-only evidence; one successor per schedule, one predecessor per successor.';

CREATE TRIGGER work_schedule_cancellation_immutable
    BEFORE UPDATE OR DELETE ON people.work_schedule_cancellation
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_terminal_immutable();
CREATE TRIGGER work_schedule_cancellation_no_truncate
    BEFORE TRUNCATE ON people.work_schedule_cancellation
    FOR EACH STATEMENT EXECUTE FUNCTION people.work_schedule_terminal_no_truncate();
CREATE TRIGGER work_schedule_replacement_immutable
    BEFORE UPDATE OR DELETE ON people.work_schedule_replacement
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_terminal_immutable();
CREATE TRIGGER work_schedule_replacement_no_truncate
    BEFORE TRUNCATE ON people.work_schedule_replacement
    FOR EACH STATEMENT EXECUTE FUNCTION people.work_schedule_terminal_no_truncate();

-- Whether a schedule has a terminal action.
CREATE FUNCTION people.work_schedule_terminal(p_tenant uuid, p_schedule uuid) RETURNS boolean
    LANGUAGE sql STABLE
AS $fn$
SELECT EXISTS (SELECT 1 FROM people.work_schedule_cancellation c
            WHERE c.tenant_id = p_tenant AND c.schedule_id = p_schedule)
    OR EXISTS (SELECT 1 FROM people.work_schedule_replacement r
            WHERE r.tenant_id = p_tenant AND r.schedule_id = p_schedule)
$fn$;

-- One terminal action per schedule, only while every original slot starts after the action's
-- instant; a replacement's successor has the original's site and period, was published at the
-- replacement's instant, has no terminal action and only slots starting after that instant.
CREATE FUNCTION people.work_schedule_terminal_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_original  people.work_schedule%ROWTYPE;
    v_successor people.work_schedule%ROWTYPE;
    v_at        timestamptz;
BEGIN
    -- Serializes every terminal action of the schedule, whatever the caller.
    SELECT * INTO v_original FROM people.work_schedule s
        WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.schedule_id
        FOR UPDATE;
    IF NOT FOUND THEN
        -- No schedule of this tenant: left to the foreign key.
        RETURN NEW;
    END IF;
    IF people.work_schedule_terminal(NEW.tenant_id, NEW.schedule_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_single_terminal',
            MESSAGE = 'a work schedule has at most one terminal action';
    END IF;
    IF TG_TABLE_NAME = 'work_schedule_cancellation' THEN
        v_at := NEW.cancelled_at;
    ELSE
        v_at := NEW.replaced_at;
    END IF;
    IF EXISTS (SELECT 1 FROM people.work_schedule_slot l
            WHERE l.tenant_id = NEW.tenant_id AND l.schedule_id = NEW.schedule_id
                AND l.start_at <= v_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_change_window',
            MESSAGE = 'a work schedule changes only while every slot starts later';
    END IF;
    IF TG_TABLE_NAME = 'work_schedule_replacement' THEN
        SELECT * INTO v_successor FROM people.work_schedule s
            WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.replacement_schedule_id;
        IF FOUND AND (v_successor.site_id <> v_original.site_id
                OR v_successor.first_date <> v_original.first_date
                OR v_successor.last_date <> v_original.last_date
                OR v_successor.published_at <> NEW.replaced_at
                OR v_successor.published_at <= v_original.published_at
                OR people.work_schedule_terminal(NEW.tenant_id, NEW.replacement_schedule_id)
                OR EXISTS (SELECT 1 FROM people.work_schedule_slot l
                    WHERE l.tenant_id = NEW.tenant_id
                        AND l.schedule_id = NEW.replacement_schedule_id
                        AND l.start_at <= NEW.replaced_at)) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'work_schedule_replacement_shape',
                MESSAGE = 'a replacement has the same site and period, is new and lies ahead';
        END IF;
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER work_schedule_cancellation_guard
    BEFORE INSERT ON people.work_schedule_cancellation
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_terminal_guard();
CREATE TRIGGER work_schedule_replacement_guard
    BEFORE INSERT ON people.work_schedule_replacement
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_terminal_guard();

-- ---------------------------------------------------------------------------------------------
-- Operational coverage projections (the conflict checks)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.work_schedule_site_coverage (
    tenant_id   uuid NOT NULL,
    schedule_id uuid NOT NULL,
    site_id     uuid NOT NULL,
    first_date  date NOT NULL,
    last_date   date NOT NULL,
    CONSTRAINT work_schedule_site_coverage_pkey PRIMARY KEY (tenant_id, schedule_id),
    -- Exactly its schedule's site and period: a mismatched row cannot exist.
    CONSTRAINT work_schedule_site_coverage_schedule
        FOREIGN KEY (tenant_id, schedule_id, site_id, first_date, last_date)
            REFERENCES people.work_schedule (tenant_id, id, site_id, first_date, last_date),
    -- One operational schedule per site and date (both ends inclusive).
    CONSTRAINT work_schedule_site_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, site_id WITH =, daterange(first_date, last_date, '[]') WITH &&)
);

COMMENT ON TABLE people.work_schedule_site_coverage IS
    'MVP-050B operational projection: one row per schedule without terminal action, removed by its cancellation or replacement. Never the source of state.';

CREATE TABLE people.work_schedule_slot_coverage (
    tenant_id   uuid        NOT NULL,
    slot_id     uuid        NOT NULL,
    schedule_id uuid        NOT NULL,
    employee_id uuid        NOT NULL,
    start_at    timestamptz NOT NULL,
    end_at      timestamptz NOT NULL,
    CONSTRAINT work_schedule_slot_coverage_pkey PRIMARY KEY (tenant_id, slot_id),
    -- Exactly its slot's schedule, assigned employee and instants.
    CONSTRAINT work_schedule_slot_coverage_slot
        FOREIGN KEY (tenant_id, slot_id, schedule_id, employee_id, start_at, end_at)
            REFERENCES people.work_schedule_slot
                (tenant_id, id, schedule_id, assigned_employee_id, start_at, end_at),
    -- No overlapping operational assignment of an employee, any site (half-open instants).
    CONSTRAINT work_schedule_slot_employee_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, employee_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&)
);

COMMENT ON TABLE people.work_schedule_slot_coverage IS
    'MVP-050B operational projection: one row per assigned slot of a schedule without terminal action, removed by its cancellation or replacement. Never the source of state.';

CREATE INDEX work_schedule_slot_coverage_schedule
    ON people.work_schedule_slot_coverage (tenant_id, schedule_id);

-- Never updated; inserted only for a schedule without terminal action; deleted only once it has
-- one.
CREATE FUNCTION people.work_schedule_coverage_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_tenant   uuid;
    v_schedule uuid;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_guard',
            MESSAGE = 'work schedule coverage is never changed';
    END IF;
    IF TG_OP = 'DELETE' THEN
        v_tenant := OLD.tenant_id;
        v_schedule := OLD.schedule_id;
    ELSE
        v_tenant := NEW.tenant_id;
        v_schedule := NEW.schedule_id;
    END IF;
    IF (TG_OP = 'DELETE') <> people.work_schedule_terminal(v_tenant, v_schedule) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_guard',
            MESSAGE = 'coverage exists exactly while its schedule has no terminal action';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$fn$;

CREATE FUNCTION people.work_schedule_coverage_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_no_truncate',
        MESSAGE = 'work schedule coverage is never truncated';
END
$fn$;

-- The projections start from every V25 schedule and assigned slot (no terminal action exists).
INSERT INTO people.work_schedule_site_coverage (tenant_id, schedule_id, site_id, first_date, last_date)
SELECT tenant_id, id, site_id, first_date, last_date FROM people.work_schedule;
INSERT INTO people.work_schedule_slot_coverage
    (tenant_id, slot_id, schedule_id, employee_id, start_at, end_at)
SELECT tenant_id, id, schedule_id, assigned_employee_id, start_at, end_at
    FROM people.work_schedule_slot WHERE assigned_employee_id IS NOT NULL;

CREATE TRIGGER work_schedule_site_coverage_guard
    BEFORE INSERT OR UPDATE OR DELETE ON people.work_schedule_site_coverage
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_coverage_guard();
CREATE TRIGGER work_schedule_site_coverage_no_truncate
    BEFORE TRUNCATE ON people.work_schedule_site_coverage
    FOR EACH STATEMENT EXECUTE FUNCTION people.work_schedule_coverage_no_truncate();
CREATE TRIGGER work_schedule_slot_coverage_guard
    BEFORE INSERT OR UPDATE OR DELETE ON people.work_schedule_slot_coverage
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_coverage_guard();
CREATE TRIGGER work_schedule_slot_coverage_no_truncate
    BEFORE TRUNCATE ON people.work_schedule_slot_coverage
    FOR EACH STATEMENT EXECUTE FUNCTION people.work_schedule_coverage_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Exactness (deferred): coverage exactly while a schedule has no terminal action
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.work_schedule_coverage_exact() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_tenant   uuid;
    v_schedule uuid;
    v_assigned integer;
    v_sites    integer;
    v_slots    integer;
    v_matching integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_tenant := OLD.tenant_id;
        v_schedule := OLD.schedule_id;
    ELSIF TG_TABLE_NAME = 'work_schedule' THEN
        v_tenant := NEW.tenant_id;
        v_schedule := NEW.id;
    ELSE
        v_tenant := NEW.tenant_id;
        v_schedule := NEW.schedule_id;
    END IF;
    SELECT s.assigned_count INTO v_assigned FROM people.work_schedule s
        WHERE s.tenant_id = v_tenant AND s.id = v_schedule;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT count(*) INTO v_sites FROM people.work_schedule_site_coverage c
        WHERE c.tenant_id = v_tenant AND c.schedule_id = v_schedule;
    SELECT count(*) INTO v_slots FROM people.work_schedule_slot_coverage c
        WHERE c.tenant_id = v_tenant AND c.schedule_id = v_schedule;
    IF people.work_schedule_terminal(v_tenant, v_schedule) THEN
        IF v_sites <> 0 OR v_slots <> 0 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_coverage_exact',
                MESSAGE = 'a cancelled or replaced work schedule has no coverage';
        END IF;
    ELSE
        SELECT count(*) INTO v_matching FROM people.work_schedule_slot l
            WHERE l.tenant_id = v_tenant AND l.schedule_id = v_schedule
                AND l.assigned_employee_id IS NOT NULL;
        IF v_sites <> 1 OR v_slots <> v_assigned OR v_slots <> v_matching THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_coverage_exact',
                MESSAGE = 'an operational work schedule has exactly its coverage';
        END IF;
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER work_schedule_covered
    AFTER INSERT ON people.work_schedule
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_coverage_exact();
CREATE CONSTRAINT TRIGGER work_schedule_cancellation_uncovered
    AFTER INSERT ON people.work_schedule_cancellation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_coverage_exact();
CREATE CONSTRAINT TRIGGER work_schedule_replacement_uncovered
    AFTER INSERT ON people.work_schedule_replacement
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_coverage_exact();
CREATE CONSTRAINT TRIGGER work_schedule_site_coverage_exact
    AFTER INSERT OR DELETE ON people.work_schedule_site_coverage
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_coverage_exact();
CREATE CONSTRAINT TRIGGER work_schedule_slot_coverage_exact
    AFTER INSERT OR DELETE ON people.work_schedule_slot_coverage
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_coverage_exact();
