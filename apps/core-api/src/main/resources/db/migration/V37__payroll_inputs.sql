-- MVP-090A (Issue #141): a tenant administrator with exact MFA generates immutable, traceable
-- time-based payroll inputs for one legal entity and one inclusive date range, from locked
-- timesheet periods only. The first increment of the payroll preparation release train (MVP-090A,
-- MVP-091, MVP-092). Additive to V36; the first objects of the payroll schema.
--
-- Ownership: the payroll module; only the bounded com.divalhr.core.payroll package reads or writes
-- these objects (ADR 0023). The people module's locked approved time reaches payroll through the
-- platform port only; no payroll object references a people table (extraction-ready). The keys to
-- tenant.legal_entity and tenant.site are the documented integrity exception (ADR 0014, 0018, 0021,
-- 0022).
--
-- Classification: Restricted HR/Payroll (identifiers, dates, counts and approved worked minutes of
-- one legal entity's period). No name, employee number, address, subject, pay amount or note is
-- stored here, and nothing of this schema leaves through audit metadata, events, logs or errors
-- except identifiers, dates, versions, counts and bands.
--
-- Shape:
--   * payroll.input_run: one generated run of one legal entity and period (version 1 in this
--     increment; a superseding version is a later story): the period, the business time zone, the
--     counts and total worked minutes, the source evidence digest, the generating actor and instant.
--   * payroll.input_source_week: the locked weekly generations the run was built from, one per
--     generation: the site, the week, the generation and its version, the LOCKED period action and
--     lock version, the lock's evidence digest and the week's counts within the period.
--   * payroll.input_employee: one row per employee and employment with approved time in the period:
--     the covered dates, the counts and the worked minutes.
--   * payroll.input_source_row: one row per approved timesheet row (shift) within the period, bound
--     to its employee row and source week: the site, the cost center, the local work date, the
--     shift, the timesheet, its revision, the current APPROVED action and cycle, the lock action
--     and the worked minutes.
--   * Every table is append-only evidence: never updated, deleted or truncated.
--   * payroll_input_run_exact (deferred, at commit): the run's counts and total equal its rows',
--     every employee row's counts and total equal its rows', every source week's counts and total
--     equal its rows', every row's work date lies within the period and every employee row and
--     source week has at least one row. A run is never partial.
--
-- Indexes: payroll_input_run_history (newest first per tenant and legal entity); the primary keys.
--
-- Rollback: db/rollback/V37__rollback.sql (manual, never run by Flyway) restores V36 exactly only
-- while no payroll-input run exists; there is no override.

-- ---------------------------------------------------------------------------------------------
-- The run
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.input_run (
    tenant_id          uuid        NOT NULL,
    id                 uuid        NOT NULL,
    legal_entity_id    uuid        NOT NULL,
    version            integer     NOT NULL,
    status             text        NOT NULL,
    schema_version     integer     NOT NULL,
    period_start       date        NOT NULL,
    period_end         date        NOT NULL,
    business_timezone  text        NOT NULL,
    week_count         integer     NOT NULL,
    employee_count     integer     NOT NULL,
    timesheet_count    integer     NOT NULL,
    row_count          integer     NOT NULL,
    worked_minutes     integer     NOT NULL,
    excluded_rows      integer     NOT NULL,
    excluded_timesheets integer    NOT NULL,
    evidence_digest    text        NOT NULL,
    generated_at       timestamptz NOT NULL,
    generated_by       text        NOT NULL,
    CONSTRAINT payroll_input_run_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT payroll_input_run_version_unique
        UNIQUE (tenant_id, legal_entity_id, period_start, period_end, version),
    CONSTRAINT payroll_input_run_legal_entity
        FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT payroll_input_run_status CHECK (status = 'GENERATED'),
    CONSTRAINT payroll_input_run_version_positive CHECK (version >= 1),
    CONSTRAINT payroll_input_run_schema CHECK (schema_version = 1),
    -- The supported pilot boundaries: an inclusive range of one to 31 days, not before 2026.
    CONSTRAINT payroll_input_run_period
        CHECK (period_end >= period_start AND period_end - period_start < 31
            AND period_start >= DATE '2026-01-01'),
    CONSTRAINT payroll_input_run_timezone_format
        CHECK (business_timezone ~ '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}$'
            AND char_length(business_timezone) BETWEEN 3 AND 64),
    CONSTRAINT payroll_input_run_counts
        CHECK (week_count >= 1 AND employee_count >= 1 AND timesheet_count >= employee_count
            AND row_count >= timesheet_count AND worked_minutes >= 0
            AND excluded_rows >= 0 AND excluded_timesheets >= 0),
    CONSTRAINT payroll_input_run_digest CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_input_run_actor CHECK (char_length(generated_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE payroll.input_run IS
    'MVP-090A immutable payroll-input run of one legal entity and inclusive period (Restricted HR/Payroll): counts, approved worked minutes and the source evidence digest; never calculated pay.';

CREATE INDEX payroll_input_run_history
    ON payroll.input_run (tenant_id, legal_entity_id, generated_at DESC, id DESC);

-- ---------------------------------------------------------------------------------------------
-- The locked weeks the run was built from
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.input_source_week (
    tenant_id          uuid    NOT NULL,
    run_id             uuid    NOT NULL,
    generation_id      uuid    NOT NULL,
    site_id            uuid    NOT NULL,
    week_start         date    NOT NULL,
    site_timezone      text    NOT NULL,
    generation_version integer NOT NULL,
    lock_action_id     uuid    NOT NULL,
    lock_version       integer NOT NULL,
    lock_evidence_digest text  NOT NULL,
    timesheet_count    integer NOT NULL,
    row_count          integer NOT NULL,
    worked_minutes     integer NOT NULL,
    CONSTRAINT payroll_input_source_week_pkey PRIMARY KEY (tenant_id, run_id, generation_id),
    CONSTRAINT payroll_input_source_week_run
        FOREIGN KEY (tenant_id, run_id) REFERENCES payroll.input_run (tenant_id, id),
    CONSTRAINT payroll_input_source_week_site
        FOREIGN KEY (tenant_id, site_id) REFERENCES tenant.site (tenant_id, id),
    CONSTRAINT payroll_input_source_week_site_week_unique UNIQUE (tenant_id, run_id, site_id, week_start),
    CONSTRAINT payroll_input_source_week_monday CHECK (EXTRACT(ISODOW FROM week_start) = 1),
    CONSTRAINT payroll_input_source_week_timezone_format
        CHECK (site_timezone ~ '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}$'
            AND char_length(site_timezone) BETWEEN 3 AND 64),
    -- Odd lock versions are locks.
    CONSTRAINT payroll_input_source_week_lock
        CHECK (generation_version >= 1 AND lock_version >= 1 AND lock_version % 2 = 1),
    CONSTRAINT payroll_input_source_week_digest CHECK (lock_evidence_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_input_source_week_counts
        CHECK (timesheet_count >= 1 AND row_count >= timesheet_count AND worked_minutes >= 0)
);

COMMENT ON TABLE payroll.input_source_week IS
    'MVP-090A the locked weekly generations a payroll-input run was built from: generation, version, LOCKED action, lock version and evidence digest; counts within the period.';

-- ---------------------------------------------------------------------------------------------
-- The employee inputs
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.input_employee (
    tenant_id        uuid    NOT NULL,
    run_id           uuid    NOT NULL,
    employee_id      uuid    NOT NULL,
    employment_id    uuid    NOT NULL,
    first_work_date  date    NOT NULL,
    last_work_date   date    NOT NULL,
    timesheet_count  integer NOT NULL,
    row_count        integer NOT NULL,
    worked_minutes   integer NOT NULL,
    CONSTRAINT payroll_input_employee_pkey PRIMARY KEY (tenant_id, run_id, employee_id, employment_id),
    CONSTRAINT payroll_input_employee_run
        FOREIGN KEY (tenant_id, run_id) REFERENCES payroll.input_run (tenant_id, id),
    CONSTRAINT payroll_input_employee_dates CHECK (last_work_date >= first_work_date),
    CONSTRAINT payroll_input_employee_counts
        CHECK (timesheet_count >= 1 AND row_count >= timesheet_count AND worked_minutes >= 0)
);

COMMENT ON TABLE payroll.input_employee IS
    'MVP-090A one employee and employment''s approved time within a payroll-input run: covered dates, counts and worked minutes; identifiers only.';

-- ---------------------------------------------------------------------------------------------
-- The source rows
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.input_source_row (
    tenant_id          uuid    NOT NULL,
    run_id             uuid    NOT NULL,
    line               integer NOT NULL,
    employee_id        uuid    NOT NULL,
    employment_id      uuid    NOT NULL,
    generation_id      uuid    NOT NULL,
    site_id            uuid    NOT NULL,
    cost_center_id     uuid,
    work_date          date    NOT NULL,
    shift_id           uuid    NOT NULL,
    timesheet_id       uuid    NOT NULL,
    timesheet_revision integer NOT NULL,
    approval_action_id uuid    NOT NULL,
    approval_cycle     integer NOT NULL,
    lock_action_id     uuid    NOT NULL,
    worked_minutes     integer NOT NULL,
    CONSTRAINT payroll_input_source_row_pkey PRIMARY KEY (tenant_id, run_id, line),
    CONSTRAINT payroll_input_source_row_employee
        FOREIGN KEY (tenant_id, run_id, employee_id, employment_id)
            REFERENCES payroll.input_employee (tenant_id, run_id, employee_id, employment_id),
    CONSTRAINT payroll_input_source_row_week
        FOREIGN KEY (tenant_id, run_id, generation_id)
            REFERENCES payroll.input_source_week (tenant_id, run_id, generation_id),
    -- One shift of one timesheet is counted once per run.
    CONSTRAINT payroll_input_source_row_shift_unique UNIQUE (tenant_id, run_id, timesheet_id, shift_id),
    CONSTRAINT payroll_input_source_row_line CHECK (line >= 1),
    CONSTRAINT payroll_input_source_row_numbers
        CHECK (timesheet_revision >= 1 AND approval_cycle >= 1 AND worked_minutes >= 0)
);

COMMENT ON TABLE payroll.input_source_row IS
    'MVP-090A one approved timesheet row (shift) of a payroll-input run, traceable to its timesheet, revision, current APPROVED action and cycle, locked generation and LOCKED action; identifiers, dates and minutes only.';

-- ---------------------------------------------------------------------------------------------
-- Append-only
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.input_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_immutable',
        MESSAGE = 'payroll-input evidence is never updated or deleted';
END
$fn$;

CREATE FUNCTION payroll.input_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_no_truncate',
        MESSAGE = 'payroll-input evidence is never truncated';
END
$fn$;

CREATE TRIGGER payroll_input_run_immutable
    BEFORE UPDATE OR DELETE ON payroll.input_run
    FOR EACH ROW EXECUTE FUNCTION payroll.input_immutable();
CREATE TRIGGER payroll_input_run_no_truncate
    BEFORE TRUNCATE ON payroll.input_run
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.input_no_truncate();
CREATE TRIGGER payroll_input_source_week_immutable
    BEFORE UPDATE OR DELETE ON payroll.input_source_week
    FOR EACH ROW EXECUTE FUNCTION payroll.input_immutable();
CREATE TRIGGER payroll_input_source_week_no_truncate
    BEFORE TRUNCATE ON payroll.input_source_week
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.input_no_truncate();
CREATE TRIGGER payroll_input_employee_immutable
    BEFORE UPDATE OR DELETE ON payroll.input_employee
    FOR EACH ROW EXECUTE FUNCTION payroll.input_immutable();
CREATE TRIGGER payroll_input_employee_no_truncate
    BEFORE TRUNCATE ON payroll.input_employee
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.input_no_truncate();
CREATE TRIGGER payroll_input_source_row_immutable
    BEFORE UPDATE OR DELETE ON payroll.input_source_row
    FOR EACH ROW EXECUTE FUNCTION payroll.input_immutable();
CREATE TRIGGER payroll_input_source_row_no_truncate
    BEFORE TRUNCATE ON payroll.input_source_row
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.input_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Exactness (deferred to commit): a run is never partial and reconciles to its rows
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.input_run_exact() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_run payroll.input_run%ROWTYPE;
    v_bad integer;
BEGIN
    SELECT * INTO v_run FROM payroll.input_run
    WHERE tenant_id = NEW.tenant_id AND id = NEW.id;
    -- The run's counts and total are its rows'.
    SELECT count(*) INTO v_bad FROM (
        SELECT count(*) AS rows, count(DISTINCT (r.employee_id, r.employment_id)) AS employees,
               count(DISTINCT r.timesheet_id) AS timesheets,
               count(DISTINCT r.generation_id) AS weeks, coalesce(sum(r.worked_minutes), 0) AS minutes
        FROM payroll.input_source_row r
        WHERE r.tenant_id = v_run.tenant_id AND r.run_id = v_run.id
    ) s WHERE s.rows <> v_run.row_count OR s.employees <> v_run.employee_count
        OR s.timesheets <> v_run.timesheet_count OR s.weeks <> v_run.week_count
        OR s.minutes <> v_run.worked_minutes;
    IF v_bad > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_run_exact',
            MESSAGE = 'a payroll-input run counts exactly its rows';
    END IF;
    -- Every employee row's counts and total are its rows'; every employee row has rows.
    SELECT count(*) INTO v_bad FROM payroll.input_employee e
    LEFT JOIN LATERAL (
        SELECT count(*) AS rows, count(DISTINCT r.timesheet_id) AS timesheets,
               coalesce(sum(r.worked_minutes), 0) AS minutes,
               min(r.work_date) AS first_day, max(r.work_date) AS last_day
        FROM payroll.input_source_row r
        WHERE r.tenant_id = e.tenant_id AND r.run_id = e.run_id
            AND r.employee_id = e.employee_id AND r.employment_id = e.employment_id
    ) s ON TRUE
    WHERE e.tenant_id = v_run.tenant_id AND e.run_id = v_run.id
        AND (s.rows = 0 OR s.rows <> e.row_count OR s.timesheets <> e.timesheet_count
            OR s.minutes <> e.worked_minutes OR s.first_day <> e.first_work_date
            OR s.last_day <> e.last_work_date);
    IF v_bad > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_run_exact',
            MESSAGE = 'a payroll-input employee row counts exactly its rows';
    END IF;
    -- Every source week's counts and total are its rows'; every source week has rows, and its rows
    -- are at its site and lock.
    SELECT count(*) INTO v_bad FROM payroll.input_source_week k
    LEFT JOIN LATERAL (
        SELECT count(*) AS rows, count(DISTINCT r.timesheet_id) AS timesheets,
               coalesce(sum(r.worked_minutes), 0) AS minutes,
               count(*) FILTER (WHERE r.site_id <> k.site_id OR r.lock_action_id <> k.lock_action_id
                   OR r.work_date < k.week_start OR r.work_date >= k.week_start + 7) AS astray
        FROM payroll.input_source_row r
        WHERE r.tenant_id = k.tenant_id AND r.run_id = k.run_id AND r.generation_id = k.generation_id
    ) s ON TRUE
    WHERE k.tenant_id = v_run.tenant_id AND k.run_id = v_run.id
        AND (s.rows = 0 OR s.rows <> k.row_count OR s.timesheets <> k.timesheet_count
            OR s.minutes <> k.worked_minutes OR s.astray > 0);
    IF v_bad > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_run_exact',
            MESSAGE = 'a payroll-input source week counts exactly its rows at its site, week and lock';
    END IF;
    -- Every row's work date lies within the period; lines are 1..n.
    SELECT count(*) INTO v_bad FROM payroll.input_source_row r
    WHERE r.tenant_id = v_run.tenant_id AND r.run_id = v_run.id
        AND (r.work_date < v_run.period_start OR r.work_date > v_run.period_end);
    IF v_bad > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_run_exact',
            MESSAGE = 'a payroll-input row lies within the run''s period';
    END IF;
    SELECT count(*) INTO v_bad FROM (
        SELECT max(line) AS top, count(*) AS n FROM payroll.input_source_row r
        WHERE r.tenant_id = v_run.tenant_id AND r.run_id = v_run.id
    ) s WHERE s.top <> s.n;
    IF v_bad > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_run_exact',
            MESSAGE = 'payroll-input rows are numbered 1 to n';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_input_run_exact
    AFTER INSERT ON payroll.input_run
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payroll.input_run_exact();
