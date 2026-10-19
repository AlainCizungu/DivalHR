-- MVP-092 (Issue #149): immutable replacement versions of a payroll-input run, the final payroll-input
-- lock and the worked-time export. The last increment of the payroll preparation release train
-- (MVP-090A, MVP-091, MVP-092). Additive to V37: no V37 object is altered, rewritten or dropped.
--
-- Monthly-salary boundary: everything here is approved worked-time EVIDENCE for payroll preparation.
-- No column holds a rate, a salary, an amount, an overtime figure or a note; worked minutes never
-- calculate or change salary, overtime, deductions or payment.
--
-- Ownership: the payroll module (ADR 0025). No object references a people table; the only keys out of
-- the payroll schema are V37's own. Classification: Restricted HR/Payroll (identifiers, dates, counts
-- and approved worked minutes); nothing of this schema leaves through audit metadata, events, logs or
-- errors except identifiers, dates, versions, counts and bands.
--
-- Shape:
--   * payroll.input_run_supersession: one row for every run of version 2 or more: the run it replaces
--     (exactly the previous version of the same tenant, legal entity and period), the reason code and,
--     for OTHER only, a bounded plain-text explanation (no markup, link, address or control character).
--   * payroll.input_run_lock: the append-only final LOCKED action of a run. One per run, ever (nothing
--     unlocks a run in this MVP); only the latest version of a scope can be locked; a locked run can
--     never be superseded.
--   * payroll.input_export and payroll.input_export_row: the immutable worked-time export of a locked
--     run (one logical export per run). The rows reproduce all and only the run's source rows, in the
--     deterministic CSV order, with counts and minutes that reconcile at commit.
--   * Every new table is append-only: never updated, deleted or truncated (V37's guard functions).
--
-- Concurrency: a supersession and a lock of one scope serialize on a transaction-level advisory lock
-- keyed by the scope and re-check under it, so one valid transition wins and no partial evidence
-- remains, even without the application's scheduling lock.
--
-- Rollback: db/rollback/V38__rollback.sql (manual, never run by Flyway) restores V37 exactly only
-- while no supersession, lock or export exists; there is no override and no deletion.

-- ---------------------------------------------------------------------------------------------
-- The scope key (one advisory lock per tenant, legal entity and period)
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.input_scope_lock(p_tenant uuid, p_entity uuid, p_start date, p_end date)
    RETURNS void
    LANGUAGE plpgsql
AS $fn$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('payroll-input-scope:' || p_tenant::text || ':' || p_entity::text || ':'
            || p_start::text || ':' || p_end::text, 0));
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Supersession: the version chain
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.input_run_supersession (
    tenant_id          uuid        NOT NULL,
    run_id             uuid        NOT NULL,
    supersedes_run_id  uuid        NOT NULL,
    reason_code        text        NOT NULL,
    explanation        text,
    created_at         timestamptz NOT NULL,
    created_by         text        NOT NULL,
    CONSTRAINT payroll_input_supersession_pkey PRIMARY KEY (tenant_id, run_id),
    -- A run is superseded at most once: the chain has no fork.
    CONSTRAINT payroll_input_supersession_once UNIQUE (tenant_id, supersedes_run_id),
    CONSTRAINT payroll_input_supersession_run
        FOREIGN KEY (tenant_id, run_id) REFERENCES payroll.input_run (tenant_id, id),
    CONSTRAINT payroll_input_supersession_previous
        FOREIGN KEY (tenant_id, supersedes_run_id) REFERENCES payroll.input_run (tenant_id, id),
    CONSTRAINT payroll_input_supersession_distinct CHECK (run_id <> supersedes_run_id),
    CONSTRAINT payroll_input_supersession_reason
        CHECK (reason_code IN ('UPSTREAM_CORRECTION', 'UPSTREAM_REAPPROVAL', 'SCOPE_CORRECTION',
            'OTHER')),
    -- OTHER requires an explanation; the others carry none. Plain text only: no markup, no
    -- address, no link, no control character.
    CONSTRAINT payroll_input_supersession_explanation
        CHECK ((reason_code = 'OTHER') = (explanation IS NOT NULL)
            AND (explanation IS NULL
                OR (char_length(explanation) BETWEEN 1 AND 200
                    AND explanation !~ '[<>@&"\\[:cntrl:]]'
                    AND explanation !~* '(https?:|www\.|://)'))),
    CONSTRAINT payroll_input_supersession_actor CHECK (char_length(created_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE payroll.input_run_supersession IS
    'MVP-092 the immutable link from a payroll-input run of version 2 or more to the run it replaces, with a reason code (Restricted HR/Payroll); never an edit of the replaced run.';

CREATE FUNCTION payroll.input_supersession_valid() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_run  payroll.input_run%ROWTYPE;
    v_prev payroll.input_run%ROWTYPE;
BEGIN
    SELECT * INTO v_run FROM payroll.input_run WHERE tenant_id = NEW.tenant_id AND id = NEW.run_id;
    SELECT * INTO v_prev FROM payroll.input_run
    WHERE tenant_id = NEW.tenant_id AND id = NEW.supersedes_run_id;
    PERFORM payroll.input_scope_lock(v_run.tenant_id, v_run.legal_entity_id, v_run.period_start,
        v_run.period_end);
    -- The same tenant, legal entity and period, and exactly the next version.
    IF v_prev.legal_entity_id <> v_run.legal_entity_id OR v_prev.period_start <> v_run.period_start
        OR v_prev.period_end <> v_run.period_end OR v_run.version <> v_prev.version + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_supersession_valid',
            MESSAGE = 'a payroll-input version replaces exactly the previous version of its scope';
    END IF;
    -- Only the latest version is replaced, and never a locked one (checked under the scope lock,
    -- with a fresh snapshot, after any concurrent lock committed).
    IF EXISTS (SELECT 1 FROM payroll.input_run x
               WHERE x.tenant_id = v_run.tenant_id AND x.legal_entity_id = v_run.legal_entity_id
                   AND x.period_start = v_run.period_start AND x.period_end = v_run.period_end
                   AND x.version > v_run.version) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_supersession_valid',
            MESSAGE = 'only the latest payroll-input version is replaced';
    END IF;
    IF EXISTS (SELECT 1 FROM payroll.input_run_lock l
               WHERE l.tenant_id = NEW.tenant_id AND l.run_id = NEW.supersedes_run_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_supersession_locked',
            MESSAGE = 'a locked payroll-input run is never superseded';
    END IF;
    RETURN NEW;
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- The final lock
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.input_run_lock (
    tenant_id        uuid        NOT NULL,
    id               uuid        NOT NULL,
    run_id           uuid        NOT NULL,
    legal_entity_id  uuid        NOT NULL,
    period_start     date        NOT NULL,
    period_end       date        NOT NULL,
    run_version      integer     NOT NULL,
    action           text        NOT NULL,
    lock_version     integer     NOT NULL,
    evidence_digest  text        NOT NULL,
    locked_at        timestamptz NOT NULL,
    locked_by        text        NOT NULL,
    CONSTRAINT payroll_input_lock_pkey PRIMARY KEY (tenant_id, id),
    -- At most one current lock action for a run; nothing unlocks a run in this MVP.
    CONSTRAINT payroll_input_lock_one UNIQUE (tenant_id, run_id),
    CONSTRAINT payroll_input_lock_run
        FOREIGN KEY (tenant_id, run_id) REFERENCES payroll.input_run (tenant_id, id),
    CONSTRAINT payroll_input_lock_action CHECK (action = 'LOCKED' AND lock_version = 1),
    CONSTRAINT payroll_input_lock_digest CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_input_lock_actor CHECK (char_length(locked_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE payroll.input_run_lock IS
    'MVP-092 the append-only final LOCKED action of a payroll-input run (Restricted HR/Payroll): only the latest version of a scope, once; worked-time evidence is final, never calculated pay.';

CREATE FUNCTION payroll.input_lock_valid() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_run payroll.input_run%ROWTYPE;
BEGIN
    SELECT * INTO v_run FROM payroll.input_run WHERE tenant_id = NEW.tenant_id AND id = NEW.run_id;
    PERFORM payroll.input_scope_lock(v_run.tenant_id, v_run.legal_entity_id, v_run.period_start,
        v_run.period_end);
    IF NEW.legal_entity_id <> v_run.legal_entity_id OR NEW.period_start <> v_run.period_start
        OR NEW.period_end <> v_run.period_end OR NEW.run_version <> v_run.version
        OR NEW.evidence_digest <> v_run.evidence_digest THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_lock_valid',
            MESSAGE = 'a payroll-input lock records exactly the run it locks';
    END IF;
    -- Only the latest version of the scope can be locked (checked under the scope lock, after any
    -- concurrent supersession committed).
    IF EXISTS (SELECT 1 FROM payroll.input_run x
               WHERE x.tenant_id = v_run.tenant_id AND x.legal_entity_id = v_run.legal_entity_id
                   AND x.period_start = v_run.period_start AND x.period_end = v_run.period_end
                   AND x.version > v_run.version) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_lock_latest',
            MESSAGE = 'only the latest payroll-input version can be locked';
    END IF;
    RETURN NEW;
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- The export
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.input_export (
    tenant_id        uuid        NOT NULL,
    id               uuid        NOT NULL,
    run_id           uuid        NOT NULL,
    lock_id          uuid        NOT NULL,
    schema_version   integer     NOT NULL,
    legal_entity_id  uuid        NOT NULL,
    period_start     date        NOT NULL,
    period_end       date        NOT NULL,
    run_version      integer     NOT NULL,
    employee_count   integer     NOT NULL,
    row_count        integer     NOT NULL,
    worked_minutes   integer     NOT NULL,
    csv_sha256       text        NOT NULL,
    created_at       timestamptz NOT NULL,
    created_by       text        NOT NULL,
    CONSTRAINT payroll_input_export_pkey PRIMARY KEY (tenant_id, id),
    -- One logical export per locked run, ever; only a locked run can own an export.
    CONSTRAINT payroll_input_export_one UNIQUE (tenant_id, run_id),
    CONSTRAINT payroll_input_export_lock
        FOREIGN KEY (tenant_id, run_id) REFERENCES payroll.input_run_lock (tenant_id, run_id),
    CONSTRAINT payroll_input_export_lock_id
        FOREIGN KEY (tenant_id, lock_id) REFERENCES payroll.input_run_lock (tenant_id, id),
    CONSTRAINT payroll_input_export_schema CHECK (schema_version = 1),
    CONSTRAINT payroll_input_export_counts
        CHECK (employee_count >= 1 AND row_count >= employee_count AND worked_minutes >= 0),
    CONSTRAINT payroll_input_export_digest CHECK (csv_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_input_export_actor CHECK (char_length(created_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE payroll.input_export IS
    'MVP-092 the immutable worked-time export of a locked payroll-input run (Restricted HR/Payroll): counts, approved worked minutes and the SHA-256 of the CSV bytes; never a salary, pay or payment file.';

CREATE TABLE payroll.input_export_row (
    tenant_id                   uuid    NOT NULL,
    export_id                   uuid    NOT NULL,
    line                        integer NOT NULL,
    source_line                 integer NOT NULL,
    employee_id                 uuid    NOT NULL,
    employment_id               uuid    NOT NULL,
    legal_entity_id             uuid    NOT NULL,
    site_id                     uuid    NOT NULL,
    cost_center_id              uuid,
    work_date                   date    NOT NULL,
    approved_worked_minutes     integer NOT NULL,
    timesheet_id                uuid    NOT NULL,
    timesheet_revision          integer NOT NULL,
    approval_action_id          uuid    NOT NULL,
    approval_cycle              integer NOT NULL,
    source_week_start           date    NOT NULL,
    source_generation_id        uuid    NOT NULL,
    source_generation_version   integer NOT NULL,
    source_lock_action_id       uuid    NOT NULL,
    source_lock_version         integer NOT NULL,
    payroll_input_run_id        uuid    NOT NULL,
    payroll_input_run_version   integer NOT NULL,
    CONSTRAINT payroll_input_export_row_pkey PRIMARY KEY (tenant_id, export_id, line),
    CONSTRAINT payroll_input_export_row_source_unique UNIQUE (tenant_id, export_id, source_line),
    CONSTRAINT payroll_input_export_row_export
        FOREIGN KEY (tenant_id, export_id) REFERENCES payroll.input_export (tenant_id, id),
    -- Every export row is one immutable source row of the run.
    CONSTRAINT payroll_input_export_row_source
        FOREIGN KEY (tenant_id, payroll_input_run_id, source_line)
            REFERENCES payroll.input_source_row (tenant_id, run_id, line),
    CONSTRAINT payroll_input_export_row_numbers
        CHECK (line >= 1 AND source_line >= 1 AND approved_worked_minutes >= 0
            AND timesheet_revision >= 1 AND approval_cycle >= 1 AND source_generation_version >= 1
            AND source_lock_version >= 1 AND payroll_input_run_version >= 1)
);

COMMENT ON TABLE payroll.input_export_row IS
    'MVP-092 one row of a worked-time export: exactly one immutable source row of the locked run, in CSV order; identifiers, dates and minutes only.';

-- ---------------------------------------------------------------------------------------------
-- Append-only (V37's guard functions)
-- ---------------------------------------------------------------------------------------------

CREATE TRIGGER payroll_input_supersession_immutable
    BEFORE UPDATE OR DELETE ON payroll.input_run_supersession
    FOR EACH ROW EXECUTE FUNCTION payroll.input_immutable();
CREATE TRIGGER payroll_input_supersession_no_truncate
    BEFORE TRUNCATE ON payroll.input_run_supersession
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.input_no_truncate();
CREATE TRIGGER payroll_input_lock_immutable
    BEFORE UPDATE OR DELETE ON payroll.input_run_lock
    FOR EACH ROW EXECUTE FUNCTION payroll.input_immutable();
CREATE TRIGGER payroll_input_lock_no_truncate
    BEFORE TRUNCATE ON payroll.input_run_lock
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.input_no_truncate();
CREATE TRIGGER payroll_input_export_immutable
    BEFORE UPDATE OR DELETE ON payroll.input_export
    FOR EACH ROW EXECUTE FUNCTION payroll.input_immutable();
CREATE TRIGGER payroll_input_export_no_truncate
    BEFORE TRUNCATE ON payroll.input_export
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.input_no_truncate();
CREATE TRIGGER payroll_input_export_row_immutable
    BEFORE UPDATE OR DELETE ON payroll.input_export_row
    FOR EACH ROW EXECUTE FUNCTION payroll.input_immutable();
CREATE TRIGGER payroll_input_export_row_no_truncate
    BEFORE TRUNCATE ON payroll.input_export_row
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.input_no_truncate();

CREATE TRIGGER payroll_input_supersession_valid
    BEFORE INSERT ON payroll.input_run_supersession
    FOR EACH ROW EXECUTE FUNCTION payroll.input_supersession_valid();
CREATE TRIGGER payroll_input_lock_valid
    BEFORE INSERT ON payroll.input_run_lock
    FOR EACH ROW EXECUTE FUNCTION payroll.input_lock_valid();

-- ---------------------------------------------------------------------------------------------
-- The chain is gap-free (deferred, at commit): a run of version 2 or more is committed together with
-- its supersession of exactly the previous version; version 1 never has one.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.input_run_chain() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF NEW.version > 1 AND NOT EXISTS (
        SELECT 1 FROM payroll.input_run_supersession s
        JOIN payroll.input_run p ON p.tenant_id = s.tenant_id AND p.id = s.supersedes_run_id
        WHERE s.tenant_id = NEW.tenant_id AND s.run_id = NEW.id AND p.version = NEW.version - 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_run_chain',
            MESSAGE = 'a payroll-input version above 1 supersedes exactly the previous version';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_input_run_chain
    AFTER INSERT ON payroll.input_run
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payroll.input_run_chain();

-- ---------------------------------------------------------------------------------------------
-- Export exactness (deferred, at commit): all and only the run's source rows, in the CSV order,
-- with reconciling counts and minutes
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.input_export_exact() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_run payroll.input_run%ROWTYPE;
    v_bad integer;
BEGIN
    SELECT * INTO v_run FROM payroll.input_run WHERE tenant_id = NEW.tenant_id AND id = NEW.run_id;
    IF v_run.legal_entity_id <> NEW.legal_entity_id OR v_run.period_start <> NEW.period_start
        OR v_run.period_end <> NEW.period_end OR v_run.version <> NEW.run_version
        OR v_run.employee_count <> NEW.employee_count OR v_run.row_count <> NEW.row_count
        OR v_run.worked_minutes <> NEW.worked_minutes THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_export_exact',
            MESSAGE = 'a payroll-input export counts exactly its run';
    END IF;
    -- The export rows are all and only the run's source rows: as many as the run has, each one a
    -- distinct source row (the key), and their minutes and employees reconcile.
    SELECT count(*) INTO v_bad FROM (
        SELECT count(*) AS rows, coalesce(sum(x.approved_worked_minutes), 0) AS minutes,
               count(DISTINCT (x.employee_id, x.employment_id)) AS employees,
               min(x.line) AS first_line, max(x.line) AS last_line
        FROM payroll.input_export_row x
        WHERE x.tenant_id = NEW.tenant_id AND x.export_id = NEW.id
    ) s WHERE s.rows <> v_run.row_count OR s.minutes <> v_run.worked_minutes
        OR s.employees <> v_run.employee_count OR s.first_line <> 1 OR s.last_line <> s.rows;
    IF v_bad > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_export_exact',
            MESSAGE = 'a payroll-input export holds all and only its run''s rows';
    END IF;
    -- Every export row equals its source row, its source week and the run.
    SELECT count(*) INTO v_bad
    FROM payroll.input_export_row x
    JOIN payroll.input_source_row r
        ON r.tenant_id = x.tenant_id AND r.run_id = x.payroll_input_run_id AND r.line = x.source_line
    JOIN payroll.input_source_week w
        ON w.tenant_id = r.tenant_id AND w.run_id = r.run_id AND w.generation_id = r.generation_id
    WHERE x.tenant_id = NEW.tenant_id AND x.export_id = NEW.id
        AND (x.payroll_input_run_id <> NEW.run_id OR x.payroll_input_run_version <> v_run.version
            OR x.legal_entity_id <> v_run.legal_entity_id
            OR x.employee_id <> r.employee_id OR x.employment_id <> r.employment_id
            OR x.site_id <> r.site_id OR x.cost_center_id IS DISTINCT FROM r.cost_center_id
            OR x.work_date <> r.work_date OR x.approved_worked_minutes <> r.worked_minutes
            OR x.timesheet_id <> r.timesheet_id OR x.timesheet_revision <> r.timesheet_revision
            OR x.approval_action_id <> r.approval_action_id OR x.approval_cycle <> r.approval_cycle
            OR x.source_week_start <> w.week_start OR x.source_generation_id <> r.generation_id
            OR x.source_generation_version <> w.generation_version
            OR x.source_lock_action_id <> r.lock_action_id OR x.source_lock_version <> w.lock_version);
    IF v_bad > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_export_exact',
            MESSAGE = 'a payroll-input export row equals its immutable source row';
    END IF;
    -- The rows are numbered 1..n in the deterministic CSV order: employee, employment, work date,
    -- site, then the source row identity (byte order of the lowercase identifiers).
    SELECT count(*) INTO v_bad FROM (
        SELECT x.line, row_number() OVER (
            ORDER BY x.employee_id::text COLLATE "C", x.employment_id::text COLLATE "C",
                x.work_date, x.site_id::text COLLATE "C", x.source_line) AS expected
        FROM payroll.input_export_row x
        WHERE x.tenant_id = NEW.tenant_id AND x.export_id = NEW.id
    ) o WHERE o.line <> o.expected;
    IF v_bad > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_input_export_exact',
            MESSAGE = 'payroll-input export rows are in the deterministic order';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_input_export_exact
    AFTER INSERT ON payroll.input_export
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payroll.input_export_exact();
