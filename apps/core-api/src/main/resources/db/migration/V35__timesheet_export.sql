-- MVP-070E (Issue #134): a tenant administrator with exact MFA exports the current, valid approval
-- of one site's generated week as an immutable, traceable CSV package for payroll or accounting.
-- Time facts only: no pay, rate, currency or calculation. Additive to V34.
--
-- Ownership: the people module; only the bounded com.divalhr.core.people.schedule package reads
-- or writes these objects (ADR 0021).
--
-- Classification: Restricted HR/Operations (worked time per employee and employee numbers). No
-- name, label, attendance instant, subject or row-level fact leaves this schema through audit
-- metadata, events, logs or errors.
--
-- Shape (T70E-3, T70E-8):
--   * people.timesheet_export: one immutable header per export, READY from the moment it exists:
--     the tenant, the legal entity and its site (the site's legal entity at creation), the
--     generated week with its time zone and exact UTC boundaries (a composite key to the
--     generation), the generation version, the counts and total worked minutes, the SHA-256 of
--     the eligible evidence (one export per site, week and evidence: an identical request reuses
--     the existing export) and of the CSV, the export schema version, the creation instant and
--     the verified creator.
--   * people.timesheet_export_row: one immutable row per exported timesheet row, numbered 1, 2, ...
--     in the CSV order (employee number, local work date, shift ID), bound to the exact READY row
--     of the timesheet's current revision (a composite key to the row), to the timesheet's
--     current APPROVED action and cycle (a composite key to the action), and to the employee and
--     employment; with the employee number, the cost center, the local work date and the worked
--     minutes as exported.
--   * timesheet_export_bind / timesheet_export_row_bind (at insert): the header matches its
--     generation's current version, zone and boundaries and the site's legal entity; every row
--     matches the timesheet that is APPROVED now with exactly that revision, action and cycle,
--     the row's employee, employment, cost center, worked minutes and site-local date, and the
--     employee's number.
--   * deferred (timesheet_export_exact): at commit the header's counts and total are exactly its
--     rows', every exported timesheet's current revision is exported whole, and the line numbers
--     are 1..n in the deterministic CSV order.
--   * Both tables are append-only (no update, delete or truncate): an export is never edited,
--     replaced, voided or deleted. Later corrections, reopenings and refreshes affect only future
--     exports.
--
-- Indexes: timesheet_export_history (newest first per tenant); timesheet_export_site_week (a site's
-- week, newest first); timesheet_export_evidence_unique (reuse of identical evidence); the row
-- primary key (an export's rows in CSV order).
--
-- Rollback: db/rollback/V35__rollback.sql (manual, never run by Flyway) restores V34 exactly only
-- while no export exists; there is no override.

-- ---------------------------------------------------------------------------------------------
-- Exports (append-only headers)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.timesheet_export (
    tenant_id          uuid        NOT NULL,
    id                 uuid        NOT NULL,
    schema_version     integer     NOT NULL,
    status             text        NOT NULL,
    legal_entity_id    uuid        NOT NULL,
    site_id            uuid        NOT NULL,
    generation_id      uuid        NOT NULL,
    generation_version integer     NOT NULL,
    week_start         date        NOT NULL,
    week_end           date        NOT NULL,
    site_timezone      text        NOT NULL,
    period_start_at    timestamptz NOT NULL,
    period_end_at      timestamptz NOT NULL,
    timesheet_count    integer     NOT NULL,
    employee_count     integer     NOT NULL,
    row_count          integer     NOT NULL,
    worked_minutes     integer     NOT NULL,
    evidence_digest    text        NOT NULL,
    csv_sha256         text        NOT NULL,
    created_at         timestamptz NOT NULL,
    created_by         text        NOT NULL,
    CONSTRAINT timesheet_export_pkey PRIMARY KEY (tenant_id, id),
    -- One export per site, week and eligible evidence: an identical request returns it.
    CONSTRAINT timesheet_export_evidence_unique
        UNIQUE (tenant_id, site_id, week_start, evidence_digest),
    CONSTRAINT timesheet_export_generation
        FOREIGN KEY (tenant_id, generation_id, site_id, week_start)
            REFERENCES people.timesheet_generation (tenant_id, id, site_id, week_start),
    -- ADR 0021: the database-level site and legal-entity keys are the documented integrity
    -- exception (as ADR 0014 and ADR 0018); the application reads units only through its port.
    CONSTRAINT timesheet_export_site
        FOREIGN KEY (tenant_id, site_id) REFERENCES tenant.site (tenant_id, id),
    CONSTRAINT timesheet_export_legal_entity
        FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT timesheet_export_schema CHECK (schema_version = 1),
    CONSTRAINT timesheet_export_status CHECK (status = 'READY'),
    CONSTRAINT timesheet_export_week
        CHECK (EXTRACT(ISODOW FROM week_start) = 1 AND week_end = week_start + 7
            AND week_start BETWEEN DATE '2000-01-03' AND DATE '2999-12-30'),
    CONSTRAINT timesheet_export_timezone_format
        CHECK (site_timezone ~ '^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}$'
            AND char_length(site_timezone) BETWEEN 3 AND 64),
    CONSTRAINT timesheet_export_period
        CHECK (period_end_at - period_start_at BETWEEN INTERVAL '166 hours' AND INTERVAL '170 hours'),
    CONSTRAINT timesheet_export_counts
        CHECK (generation_version >= 1 AND timesheet_count >= 1
            AND employee_count = timesheet_count AND row_count >= timesheet_count
            AND worked_minutes >= 0),
    CONSTRAINT timesheet_export_digests
        CHECK (evidence_digest ~ '^[0-9a-f]{64}$' AND csv_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT timesheet_export_creator CHECK (char_length(created_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.timesheet_export IS
    'MVP-070E immutable export of the current approved time of one site''s generated week (Restricted HR/Operations): scope, exact week boundaries, generation version, counts, total worked minutes, evidence and CSV SHA-256, schema version, creation instant and verified creator; READY only.';

CREATE INDEX timesheet_export_history
    ON people.timesheet_export (tenant_id, created_at DESC, id DESC);
CREATE INDEX timesheet_export_site_week
    ON people.timesheet_export (tenant_id, site_id, week_start, created_at DESC, id DESC);

-- ---------------------------------------------------------------------------------------------
-- Export rows (append-only, one per exported timesheet row, in CSV order)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.timesheet_export_row (
    tenant_id          uuid    NOT NULL,
    export_id          uuid    NOT NULL,
    line               integer NOT NULL,
    timesheet_id       uuid    NOT NULL,
    timesheet_revision integer NOT NULL,
    approval_action_id uuid    NOT NULL,
    approval_cycle     integer NOT NULL,
    employee_id        uuid    NOT NULL,
    employee_number    text    NOT NULL,
    employment_id      uuid    NOT NULL,
    cost_center_id     uuid,
    work_date          date    NOT NULL,
    shift_id           uuid    NOT NULL,
    worked_minutes     integer NOT NULL,
    CONSTRAINT timesheet_export_row_pkey PRIMARY KEY (tenant_id, export_id, line),
    CONSTRAINT timesheet_export_row_unique UNIQUE (tenant_id, export_id, timesheet_id, shift_id),
    CONSTRAINT timesheet_export_row_export
        FOREIGN KEY (tenant_id, export_id) REFERENCES people.timesheet_export (tenant_id, id),
    -- The exact row of the exported revision.
    CONSTRAINT timesheet_export_row_source
        FOREIGN KEY (tenant_id, timesheet_id, timesheet_revision, shift_id)
            REFERENCES people.timesheet_row (tenant_id, timesheet_id, revision, slot_id),
    -- The exported approval and its cycle, of that timesheet.
    CONSTRAINT timesheet_export_row_approval
        FOREIGN KEY (tenant_id, timesheet_id, approval_action_id, approval_cycle)
            REFERENCES people.timesheet_action (tenant_id, timesheet_id, id, cycle),
    CONSTRAINT timesheet_export_row_employment
        FOREIGN KEY (tenant_id, employment_id, employee_id)
            REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT timesheet_export_row_numbers
        CHECK (line >= 1 AND timesheet_revision >= 1 AND approval_cycle >= 1
            AND worked_minutes >= 0),
    CONSTRAINT timesheet_export_row_employee_number
        CHECK (employee_number ~ '^[A-Z0-9][A-Z0-9._/-]{0,31}$')
);

COMMENT ON TABLE people.timesheet_export_row IS
    'MVP-070E immutable exported row (Restricted HR/Operations): one READY row of an APPROVED timesheet''s current revision with its approval action and cycle, employee number, employment, cost center, local work date and worked minutes; numbered in CSV order.';

-- ---------------------------------------------------------------------------------------------
-- Append-only
-- ---------------------------------------------------------------------------------------------

CREATE TRIGGER timesheet_export_immutable
    BEFORE UPDATE OR DELETE ON people.timesheet_export
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_immutable();
CREATE TRIGGER timesheet_export_row_immutable
    BEFORE UPDATE OR DELETE ON people.timesheet_export_row
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_immutable();
CREATE TRIGGER timesheet_export_no_truncate
    BEFORE TRUNCATE ON people.timesheet_export
    FOR EACH STATEMENT EXECUTE FUNCTION people.timesheet_no_truncate();
CREATE TRIGGER timesheet_export_row_no_truncate
    BEFORE TRUNCATE ON people.timesheet_export_row
    FOR EACH STATEMENT EXECUTE FUNCTION people.timesheet_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Binding at insert
-- ---------------------------------------------------------------------------------------------

-- The header is the generation's current version, zone and boundaries; the site belongs to the
-- legal entity now.
CREATE FUNCTION people.timesheet_export_bind() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM people.timesheet_generation g
        WHERE g.tenant_id = NEW.tenant_id AND g.id = NEW.generation_id
            AND g.site_id = NEW.site_id AND g.week_start = NEW.week_start
            AND g.week_end = NEW.week_end AND g.site_timezone = NEW.site_timezone
            AND g.period_start_at = NEW.period_start_at AND g.period_end_at = NEW.period_end_at
            AND g.version = NEW.generation_version)
        OR NOT EXISTS (
            SELECT 1 FROM tenant.site s
            WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.site_id
                AND s.legal_entity_id = NEW.legal_entity_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_export_bind',
            MESSAGE = 'an export binds the current generation of its site and legal entity';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER timesheet_export_bind
    BEFORE INSERT ON people.timesheet_export
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_export_bind();

-- A row is exactly a READY row of the current revision of a timesheet of the export's generation
-- that is APPROVED now by exactly this action and cycle, with its employee's number.
CREATE FUNCTION people.timesheet_export_row_bind() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM people.timesheet_export e
        JOIN people.timesheet t
            ON t.tenant_id = e.tenant_id AND t.generation_id = e.generation_id
        JOIN people.timesheet_action a
            ON a.tenant_id = t.tenant_id AND a.id = t.current_action_id
        JOIN people.timesheet_row r
            ON r.tenant_id = t.tenant_id AND r.timesheet_id = t.id
            AND r.revision = t.current_revision
        JOIN people.employee p ON p.tenant_id = t.tenant_id AND p.id = t.employee_id
        WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.export_id
            AND t.id = NEW.timesheet_id AND t.status = 'APPROVED'
            AND t.current_revision = NEW.timesheet_revision
            AND t.current_action_id = NEW.approval_action_id
            AND t.submission_cycle = NEW.approval_cycle
            AND t.employee_id = NEW.employee_id
            AND a.timesheet_id = t.id AND a.action = 'APPROVED'
            AND a.cycle = NEW.approval_cycle AND a.revision = NEW.timesheet_revision
            AND a.employment_id = NEW.employment_id
            AND r.slot_id = NEW.shift_id AND r.state = 'READY'
            AND r.employee_id = NEW.employee_id AND r.employment_id = NEW.employment_id
            AND r.cost_center_id IS NOT DISTINCT FROM NEW.cost_center_id
            AND r.worked_minutes = NEW.worked_minutes
            AND (r.scheduled_start_at AT TIME ZONE e.site_timezone)::date = NEW.work_date
            AND p.employee_number = NEW.employee_number) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_export_row_binding',
            MESSAGE = 'an export row binds the current approved revision of its timesheet';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER timesheet_export_row_bind
    BEFORE INSERT ON people.timesheet_export_row
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_export_row_bind();

-- ---------------------------------------------------------------------------------------------
-- Exactness at commit
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.timesheet_export_exact() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    -- A header's own ID, or a row's export.
    v_export_id uuid :=
        coalesce((to_jsonb(NEW) ->> 'export_id')::uuid, (to_jsonb(NEW) ->> 'id')::uuid);
    v_header people.timesheet_export%ROWTYPE;
    v_checked text := coalesce(current_setting('divalhr.timesheet_export_checked', true), '');
BEGIN
    -- Checked once per export and transaction: every row of a new export is present at commit.
    IF position(v_export_id::text IN v_checked) > 0 THEN
        RETURN NULL;
    END IF;
    SELECT * INTO v_header FROM people.timesheet_export
        WHERE tenant_id = NEW.tenant_id AND id = v_export_id;
    IF NOT FOUND
        -- The header's counts and total are exactly its rows'.
        OR (SELECT count(*) FROM people.timesheet_export_row x
                WHERE x.tenant_id = v_header.tenant_id AND x.export_id = v_header.id)
            <> v_header.row_count
        OR (SELECT count(DISTINCT x.timesheet_id) FROM people.timesheet_export_row x
                WHERE x.tenant_id = v_header.tenant_id AND x.export_id = v_header.id)
            <> v_header.timesheet_count
        OR (SELECT count(DISTINCT x.employee_id) FROM people.timesheet_export_row x
                WHERE x.tenant_id = v_header.tenant_id AND x.export_id = v_header.id)
            <> v_header.employee_count
        OR (SELECT coalesce(sum(x.worked_minutes), 0) FROM people.timesheet_export_row x
                WHERE x.tenant_id = v_header.tenant_id AND x.export_id = v_header.id)
            <> v_header.worked_minutes
        -- Every exported timesheet's revision is exported whole.
        OR EXISTS (
            SELECT 1 FROM (
                SELECT x.timesheet_id, x.timesheet_revision, count(*) AS n
                FROM people.timesheet_export_row x
                WHERE x.tenant_id = v_header.tenant_id AND x.export_id = v_header.id
                GROUP BY x.timesheet_id, x.timesheet_revision) q
            WHERE q.n <> (SELECT count(*) FROM people.timesheet_row r
                WHERE r.tenant_id = v_header.tenant_id AND r.timesheet_id = q.timesheet_id
                    AND r.revision = q.timesheet_revision))
        -- Lines are 1..n in the CSV order: employee number, local work date, shift ID.
        OR EXISTS (
            SELECT 1 FROM (
                SELECT x.line, row_number() OVER (
                    ORDER BY x.employee_number COLLATE "C", x.work_date, x.shift_id) AS expected
                FROM people.timesheet_export_row x
                WHERE x.tenant_id = v_header.tenant_id AND x.export_id = v_header.id) o
            WHERE o.line <> o.expected) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'timesheet_export_exact',
            MESSAGE = 'an export holds exactly its counted rows in CSV order';
    END IF;
    PERFORM set_config('divalhr.timesheet_export_checked', v_checked || v_export_id::text || ',',
        true);
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER timesheet_export_exact
    AFTER INSERT ON people.timesheet_export
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_export_exact();
CREATE CONSTRAINT TRIGGER timesheet_export_row_exact
    AFTER INSERT ON people.timesheet_export_row
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.timesheet_export_exact();
