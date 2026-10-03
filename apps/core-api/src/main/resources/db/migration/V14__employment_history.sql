-- MVP-021 (Issue #47; proposal H1-H20 with amendments M21-1..M21-5): effective-dated employment
-- history. The people module owns it; hierarchy units are still validated through the tenant
-- module's port, never read here.
--
-- Model (H1): people.employment is the employment relationship (start and end dates). Facts that
-- change over time are rows of people.employment_assignment, one timeline per kind:
--   PLACEMENT    legal entity, site, department or cost center, team (always covers the employment)
--   MANAGER      the manager's employee (gaps mean "no manager")
--   CONTRACT     descriptive contract classification (gaps mean "not recorded")
--   COMPENSATION compensation basis, never an amount (gaps mean "not recorded")
-- Every write is a row of people.employment_change (HIRE, CHANGE, CORRECTION, CANCELLATION).
--
-- Transaction-time history (M21-5): an assignment's business values and effective dates never
-- change. Its supersession pair (superseded_by_change_id, superseded_at) moves exactly once, from
-- both null to both set, when a later change replaces it; the replaced row is kept. Nothing is
-- deleted or truncated. A change row never changes either, except state ACTIVE -> CANCELLED when
-- its cancellation is recorded.
--
-- Dates are calendar dates with inclusive bounds; effective_to IS NULL means open-ended. The
-- generated period column is daterange(effective_from, effective_to, '[]').
--
-- Personal data (approved classification): employee number, names and the derived search key
-- are Confidential; employment and assignment dates, placement, manager, contract
-- classification, compensation basis, and change dates, kinds, timing and reason codes are
-- Restricted HR. Employee, employment, assignment and change IDs are personal-data references.
--
-- Database authority (H3, H4, H6; M21-4, M21-5): exclusion constraints (btree_gist) forbid
-- overlapping active rows of one kind and overlapping employments; deferred constraint triggers
-- require gap-free placement, reject manager cycles (taking the per-tenant manager-graph lock
-- themselves) and check the exact shape of every recorded change. Trigger failures carry a stable
-- constraint name so the application maps them narrowly.
--
-- Rollback: db/rollback/V14__rollback.sql (manual, never run by Flyway). It refuses once any
-- history beyond the hire exists, never touches flyway_schema_history and leaves btree_gist
-- installed (M21-3).

-- ---------------------------------------------------------------------------------------------
-- Prerequisite (M21-3). btree_gist is provisioned as a database prerequisite (the compose init
-- script installs it); it is created here only where it is missing. It is a trusted extension
-- (PostgreSQL 13+), so the database owner may create it. No rollback ever drops it.
-- ---------------------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------------------------
-- people.employee: search key (Confidential, derived from the names; filled by V14_1, made
-- mandatory by V14_2). Maintained only by the application, with the same normalizer.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE people.employee ADD COLUMN search_key text;

-- ---------------------------------------------------------------------------------------------
-- people.employment: the employment relationship
-- ---------------------------------------------------------------------------------------------
ALTER TABLE people.employment
    ADD CONSTRAINT employment_tenant_id_employee_unique UNIQUE (tenant_id, id, employee_id),
    -- One employee's employments never overlap (rehire needs an ended employment, MVP-022).
    ADD CONSTRAINT employment_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, employee_id WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&);

-- ---------------------------------------------------------------------------------------------
-- people.employment_change: the append-only command log
-- ---------------------------------------------------------------------------------------------
CREATE TABLE people.employment_change (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    employee_id       uuid        NOT NULL,
    employment_id     uuid        NOT NULL,
    type              text        NOT NULL,
    effective_from    date        NOT NULL,
    kinds             text[]      NOT NULL,
    reason_code       text,
    timing            text,
    cancels_change_id uuid,
    state             text        NOT NULL DEFAULT 'ACTIVE',
    recorded_at       timestamptz NOT NULL,
    recorded_by       text        NOT NULL,
    version_after     bigint      NOT NULL,
    CONSTRAINT employment_change_ownership_unique
        UNIQUE (tenant_id, id, employee_id, employment_id),
    CONSTRAINT employment_change_employment FOREIGN KEY (tenant_id, employment_id, employee_id)
        REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT employment_change_cancels FOREIGN KEY
        (tenant_id, cancels_change_id, employee_id, employment_id)
        REFERENCES people.employment_change (tenant_id, id, employee_id, employment_id),
    -- A change is cancelled at most once.
    CONSTRAINT employment_change_cancelled_once UNIQUE (cancels_change_id),
    CONSTRAINT employment_change_type_valid
        CHECK (type IN ('HIRE', 'CHANGE', 'CORRECTION', 'CANCELLATION')),
    CONSTRAINT employment_change_kinds_valid CHECK (
        cardinality(kinds) BETWEEN 1 AND 4
        AND kinds <@ ARRAY['PLACEMENT', 'MANAGER', 'CONTRACT', 'COMPENSATION']::text[]),
    CONSTRAINT employment_change_hire_kinds
        CHECK (type <> 'HIRE' OR kinds = ARRAY['PLACEMENT']::text[]),
    CONSTRAINT employment_change_correction_kinds
        CHECK (type <> 'CORRECTION' OR cardinality(kinds) = 1),
    CONSTRAINT employment_change_reason_valid CHECK (
        (type IN ('HIRE', 'CANCELLATION') AND reason_code IS NULL)
        OR (type = 'CHANGE' AND (reason_code IS NULL OR reason_code IN (
            'LATE_NOTIFICATION', 'REORGANIZATION', 'CONTRACT_CHANGE', 'OTHER_BUSINESS_CHANGE')))
        OR (type = 'CORRECTION' AND reason_code IN (
            'DATA_ENTRY_ERROR', 'IMPORT_ERROR', 'DOCUMENT_RECEIVED'))),
    -- A retroactive change always carries a reason.
    CONSTRAINT employment_change_retroactive_reason
        CHECK (type <> 'CHANGE' OR timing <> 'RETROACTIVE' OR reason_code IS NOT NULL),
    CONSTRAINT employment_change_timing_valid CHECK (
        (type = 'HIRE' AND timing IS NULL)
        OR (type <> 'HIRE' AND timing IN ('SCHEDULED', 'CURRENT', 'RETROACTIVE'))),
    CONSTRAINT employment_change_cancels_valid
        CHECK ((type = 'CANCELLATION') = (cancels_change_id IS NOT NULL)),
    CONSTRAINT employment_change_cancellation_scheduled
        CHECK (type <> 'CANCELLATION' OR timing = 'SCHEDULED'),
    -- Only business changes can be cancelled.
    CONSTRAINT employment_change_state_valid CHECK (
        state IN ('ACTIVE', 'CANCELLED') AND (state = 'ACTIVE' OR type = 'CHANGE')),
    CONSTRAINT employment_change_effective_range
        CHECK (effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'),
    CONSTRAINT employment_change_recorded_by_length
        CHECK (char_length(recorded_by) BETWEEN 1 AND 255),
    CONSTRAINT employment_change_version_non_negative CHECK (version_after >= 0)
);

CREATE INDEX employment_change_log
    ON people.employment_change (tenant_id, employee_id, recorded_at DESC, id DESC);

-- ---------------------------------------------------------------------------------------------
-- people.employment_assignment: effective-dated facts with transaction-time supersession
-- ---------------------------------------------------------------------------------------------
CREATE TABLE people.employment_assignment (
    id                      uuid        PRIMARY KEY,
    tenant_id               uuid        NOT NULL,
    employee_id             uuid        NOT NULL,
    employment_id           uuid        NOT NULL,
    kind                    text        NOT NULL,
    effective_from          date        NOT NULL,
    effective_to            date,
    period                  daterange   NOT NULL
        GENERATED ALWAYS AS (daterange(effective_from, effective_to, '[]')) STORED,
    legal_entity_id         uuid,
    site_id                 uuid,
    department_id           uuid,
    cost_center_id          uuid,
    team_id                 uuid,
    manager_employee_id     uuid,
    contract_code           text,
    compensation_basis_code text,
    -- Lineage: the change that wrote the row, the change whose value it carries (a row copied
    -- when a later change split its period keeps its origin), and, for a row restored by a
    -- cancellation, the row the cancelled change had replaced.
    created_by_change_id    uuid        NOT NULL,
    origin_change_id        uuid        NOT NULL,
    restores_assignment_id  uuid,
    superseded_by_change_id uuid,
    superseded_at           timestamptz,
    CONSTRAINT employment_assignment_ownership_unique
        UNIQUE (tenant_id, id, employment_id, kind),
    CONSTRAINT employment_assignment_employment FOREIGN KEY (tenant_id, employment_id, employee_id)
        REFERENCES people.employment (tenant_id, id, employee_id),
    -- Every referenced change belongs to the same tenant, employee and employment (M21-5).
    CONSTRAINT employment_assignment_created_by FOREIGN KEY
        (tenant_id, created_by_change_id, employee_id, employment_id)
        REFERENCES people.employment_change (tenant_id, id, employee_id, employment_id),
    CONSTRAINT employment_assignment_origin FOREIGN KEY
        (tenant_id, origin_change_id, employee_id, employment_id)
        REFERENCES people.employment_change (tenant_id, id, employee_id, employment_id),
    CONSTRAINT employment_assignment_superseded_by FOREIGN KEY
        (tenant_id, superseded_by_change_id, employee_id, employment_id)
        REFERENCES people.employment_change (tenant_id, id, employee_id, employment_id),
    CONSTRAINT employment_assignment_restores FOREIGN KEY
        (tenant_id, restores_assignment_id, employment_id, kind)
        REFERENCES people.employment_assignment (tenant_id, id, employment_id, kind),
    CONSTRAINT employment_assignment_manager FOREIGN KEY (tenant_id, manager_employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT employment_assignment_kind_valid
        CHECK (kind IN ('PLACEMENT', 'MANAGER', 'CONTRACT', 'COMPENSATION')),
    -- Exactly the value columns of the row's kind are set.
    CONSTRAINT employment_assignment_values_by_kind CHECK (
        (kind = 'PLACEMENT'
            AND legal_entity_id IS NOT NULL AND site_id IS NOT NULL
            AND num_nonnulls(department_id, cost_center_id) <= 1
            AND (team_id IS NULL OR num_nonnulls(department_id, cost_center_id) = 1)
            AND num_nonnulls(manager_employee_id, contract_code, compensation_basis_code) = 0)
        OR (kind = 'MANAGER' AND manager_employee_id IS NOT NULL
            AND num_nonnulls(legal_entity_id, site_id, department_id, cost_center_id, team_id,
                contract_code, compensation_basis_code) = 0)
        OR (kind = 'CONTRACT' AND contract_code IS NOT NULL
            AND num_nonnulls(legal_entity_id, site_id, department_id, cost_center_id, team_id,
                manager_employee_id, compensation_basis_code) = 0)
        OR (kind = 'COMPENSATION' AND compensation_basis_code IS NOT NULL
            AND num_nonnulls(legal_entity_id, site_id, department_id, cost_center_id, team_id,
                manager_employee_id, contract_code) = 0)),
    -- Descriptive codes only: no legal classification, never an amount (H13).
    CONSTRAINT employment_assignment_contract_code CHECK (contract_code IS NULL OR contract_code
        IN ('PERMANENT', 'FIXED_TERM', 'APPRENTICESHIP', 'INTERNSHIP', 'DAILY')),
    CONSTRAINT employment_assignment_compensation_code CHECK (compensation_basis_code IS NULL
        OR compensation_basis_code IN ('MONTHLY', 'HOURLY', 'DAILY', 'PIECE_RATE')),
    CONSTRAINT employment_assignment_not_self CHECK (manager_employee_id <> employee_id),
    CONSTRAINT employment_assignment_effective_order
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT employment_assignment_effective_range CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL OR effective_to BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')),
    -- The supersession pair is set together, by another change than the writer (M21-5).
    CONSTRAINT employment_assignment_supersession_pair
        CHECK ((superseded_by_change_id IS NULL) = (superseded_at IS NULL)),
    CONSTRAINT employment_assignment_superseded_by_other
        CHECK (superseded_by_change_id IS NULL OR superseded_by_change_id <> created_by_change_id),
    -- Active rows of one kind never overlap (H3).
    CONSTRAINT employment_assignment_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, employment_id WITH =, kind WITH =, period WITH &&)
        WHERE (superseded_by_change_id IS NULL)
);

CREATE INDEX employment_assignment_active
    ON people.employment_assignment (tenant_id, employment_id, kind, effective_from)
    WHERE superseded_by_change_id IS NULL;
CREATE INDEX employment_assignment_history
    ON people.employment_assignment (tenant_id, employee_id, kind, effective_from, id);
CREATE INDEX employment_assignment_manager_graph
    ON people.employment_assignment (tenant_id, employee_id)
    WHERE kind = 'MANAGER' AND superseded_by_change_id IS NULL;
CREATE INDEX employment_assignment_reports
    ON people.employment_assignment (tenant_id, manager_employee_id)
    WHERE kind = 'MANAGER' AND superseded_by_change_id IS NULL;
CREATE INDEX employment_assignment_created_by
    ON people.employment_assignment (created_by_change_id);
CREATE INDEX employment_assignment_superseded_by
    ON people.employment_assignment (superseded_by_change_id)
    WHERE superseded_by_change_id IS NOT NULL;
CREATE INDEX employment_assignment_origin
    ON people.employment_assignment (origin_change_id);

-- ---------------------------------------------------------------------------------------------
-- Backfill (H2): each V13 employment becomes a HIRE change and its first PLACEMENT row; the V13
-- placement columns are then dropped, so the timeline is the only source of truth.
-- ---------------------------------------------------------------------------------------------
INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,
        effective_from, kinds, recorded_at, recorded_by, version_after)
    SELECT gen_random_uuid(), e.tenant_id, e.employee_id, e.id, 'HIRE', e.effective_from,
        ARRAY['PLACEMENT'], e.created_at, e.created_by, e.version
    FROM people.employment e;

INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id, kind,
        effective_from, effective_to, legal_entity_id, site_id, department_id, cost_center_id,
        team_id, created_by_change_id, origin_change_id)
    SELECT gen_random_uuid(), e.tenant_id, e.employee_id, e.id, 'PLACEMENT', e.effective_from,
        e.effective_to, e.legal_entity_id, e.site_id, e.department_id, e.cost_center_id,
        e.team_id, c.id, c.id
    FROM people.employment e
    JOIN people.employment_change c ON c.employment_id = e.id AND c.type = 'HIRE';

DO
$$
BEGIN
    IF (SELECT count(*) FROM people.employment)
        <> (SELECT count(*) FROM people.employment_assignment WHERE kind = 'PLACEMENT')
        OR (SELECT count(*) FROM people.employment)
        <> (SELECT count(*) FROM people.employment_change WHERE type = 'HIRE') THEN
        RAISE EXCEPTION 'V14 backfill count mismatch';
    END IF;
END
$$;

-- Dropping the columns also drops the V13 checks that use them (employment_one_site_unit,
-- employment_team_has_parent); the rollback restores both.
ALTER TABLE people.employment
    DROP COLUMN legal_entity_id,
    DROP COLUMN site_id,
    DROP COLUMN department_id,
    DROP COLUMN cost_center_id,
    DROP COLUMN team_id;

-- ---------------------------------------------------------------------------------------------
-- Immutability guards (M21-5)
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION people.employment_assignment_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_assignment_immutable',
            MESSAGE = 'employment assignments are never deleted';
    END IF;
    -- Business values, dates, ownership and lineage never change; the supersession pair moves
    -- once, from null to set. (period is generated and not yet computed in BEFORE triggers.)
    IF OLD.superseded_by_change_id IS NOT NULL
        OR NEW.superseded_by_change_id IS NULL
        OR NEW.superseded_at IS NULL
        OR (to_jsonb(NEW) - 'superseded_by_change_id' - 'superseded_at' - 'period')
            IS DISTINCT FROM (to_jsonb(OLD) - 'superseded_by_change_id' - 'superseded_at' - 'period')
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_assignment_immutable',
            MESSAGE = 'only an active assignment can be superseded, once';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER employment_assignment_guard
    BEFORE UPDATE OR DELETE ON people.employment_assignment
    FOR EACH ROW EXECUTE FUNCTION people.employment_assignment_guard();

CREATE FUNCTION people.employment_change_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_change_immutable',
            MESSAGE = 'employment changes are never deleted';
    END IF;
    -- The only transition: a change's state becomes CANCELLED once its cancellation exists.
    IF OLD.state <> 'ACTIVE' OR NEW.state <> 'CANCELLED'
        OR (to_jsonb(NEW) - 'state') IS DISTINCT FROM (to_jsonb(OLD) - 'state')
        OR NOT EXISTS (SELECT 1 FROM people.employment_change c
            WHERE c.cancels_change_id = OLD.id AND c.tenant_id = OLD.tenant_id)
    THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_change_immutable',
            MESSAGE = 'only an active change with a recorded cancellation becomes cancelled';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER employment_change_guard
    BEFORE UPDATE OR DELETE ON people.employment_change
    FOR EACH ROW EXECUTE FUNCTION people.employment_change_guard();

CREATE FUNCTION people.employment_history_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_history_no_truncate',
        MESSAGE = 'employment history is never truncated';
END
$fn$;

CREATE TRIGGER employment_assignment_no_truncate
    BEFORE TRUNCATE ON people.employment_assignment
    FOR EACH STATEMENT EXECUTE FUNCTION people.employment_history_no_truncate();
CREATE TRIGGER employment_change_no_truncate
    BEFORE TRUNCATE ON people.employment_change
    FOR EACH STATEMENT EXECUTE FUNCTION people.employment_history_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Gap-free placement (H4): at commit, the active PLACEMENT rows of every touched employment
-- cover exactly [employment start, employment end].
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION people.employment_placement_covered(p_employment uuid) RETURNS boolean
    LANGUAGE plpgsql STABLE
AS $fn$
DECLARE
    v_start date;
    v_end   date;
    v_next  date;
    v_open  boolean := false;
    r       record;
BEGIN
    SELECT effective_from, effective_to INTO v_start, v_end
        FROM people.employment WHERE id = p_employment;
    IF NOT FOUND THEN
        RETURN true;
    END IF;
    v_next := v_start;
    FOR r IN SELECT effective_from, effective_to FROM people.employment_assignment
        WHERE employment_id = p_employment AND kind = 'PLACEMENT'
            AND superseded_by_change_id IS NULL
        ORDER BY effective_from
    LOOP
        IF v_open OR r.effective_from <> v_next THEN
            RETURN false;
        END IF;
        IF r.effective_to IS NULL THEN
            v_open := true;
        ELSE
            v_next := r.effective_to + 1;
        END IF;
    END LOOP;
    IF v_end IS NULL THEN
        RETURN v_open;
    END IF;
    RETURN NOT v_open AND v_next = v_end + 1;
END
$fn$;

CREATE FUNCTION people.employment_placement_coverage() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_employment uuid;
BEGIN
    -- Separate branches: each record type has only its own fields.
    IF TG_TABLE_NAME = 'employment' THEN
        v_employment := NEW.id;
    ELSE
        v_employment := NEW.employment_id;
    END IF;
    IF NOT people.employment_placement_covered(v_employment) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_placement_coverage',
            MESSAGE = 'placement must cover the employment period without gaps';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER employment_placement_coverage
    AFTER INSERT OR UPDATE ON people.employment_assignment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.kind = 'PLACEMENT')
    EXECUTE FUNCTION people.employment_placement_coverage();
CREATE CONSTRAINT TRIGGER employment_placement_coverage_on_employment
    AFTER INSERT OR UPDATE ON people.employment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.employment_placement_coverage();

-- ---------------------------------------------------------------------------------------------
-- Manager cycles (H6, M21-4): at commit, under the tenant's manager-graph lock (taken here, not
-- left to the caller), a new active MANAGER row must not close a reporting loop on any day of its
-- period, and chains are at most 50 levels. Every interval is checked by intersecting periods.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION people.employment_manager_acyclic() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_row   people.employment_assignment%ROWTYPE;
    v_cycle boolean;
    v_deep  boolean;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('people.manager-graph:' || NEW.tenant_id, 0));
    -- Re-read the row after the lock: it may have been superseded later in this transaction.
    SELECT * INTO v_row FROM people.employment_assignment WHERE id = NEW.id;
    IF v_row.superseded_by_change_id IS NOT NULL THEN
        RETURN NULL;
    END IF;
    WITH RECURSIVE walk (person, period, depth) AS (
        SELECT v_row.manager_employee_id, v_row.period, 1
        UNION ALL
        SELECT a.manager_employee_id, w.period * a.period, w.depth + 1
        FROM walk w
        JOIN people.employment_assignment a
            ON a.tenant_id = v_row.tenant_id AND a.employee_id = w.person
            AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL
            AND a.period && w.period
        WHERE w.depth <= 50 AND w.person <> v_row.employee_id
    )
    SELECT bool_or(person = v_row.employee_id), bool_or(depth > 50)
        INTO v_cycle, v_deep FROM walk;
    IF v_cycle THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'employment_assignment_manager_acyclic',
            MESSAGE = 'a manager relationship would close a reporting loop';
    END IF;
    IF v_deep THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'employment_assignment_manager_depth',
            MESSAGE = 'a reporting chain would exceed 50 levels';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER employment_manager_acyclic
    AFTER INSERT ON people.employment_assignment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.kind = 'MANAGER')
    EXECUTE FUNCTION people.employment_manager_acyclic();

-- ---------------------------------------------------------------------------------------------
-- Change shape (M21-5): at commit, every recorded change wrote exactly what its type allows.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION people.employment_assignment_value(a people.employment_assignment) RETURNS jsonb
    LANGUAGE sql IMMUTABLE
AS $fn$
SELECT jsonb_build_array(a.kind, a.legal_entity_id, a.site_id, a.department_id, a.cost_center_id,
    a.team_id, a.manager_employee_id, a.contract_code, a.compensation_basis_code)
$fn$;

CREATE FUNCTION people.employment_change_shape() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    c          people.employment_change%ROWTYPE;
    v_cancel   people.employment_change%ROWTYPE;
    v_employ   people.employment%ROWTYPE;
    v_bad      boolean;
    v_created  integer;
    v_replaced integer;
BEGIN
    SELECT * INTO c FROM people.employment_change WHERE id = NEW.id;
    SELECT count(*) INTO v_created FROM people.employment_assignment
        WHERE created_by_change_id = c.id;
    SELECT count(*) INTO v_replaced FROM people.employment_assignment
        WHERE superseded_by_change_id = c.id;

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
            -- Only the change's kinds are touched, at most one replaced row per kind.
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE (a.created_by_change_id = c.id OR a.superseded_by_change_id = c.id)
                    AND NOT (a.kind = ANY (c.kinds)))
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

    ELSIF c.type = 'CANCELLATION' THEN
        SELECT * INTO v_cancel FROM people.employment_change WHERE id = c.cancels_change_id;
        v_bad := v_cancel.type <> 'CHANGE' OR v_cancel.state <> 'CANCELLED'
            OR c.kinds <> v_cancel.kinds OR c.effective_from <> v_cancel.effective_from
            OR v_replaced = 0
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
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER employment_change_shape
    AFTER INSERT ON people.employment_change
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.employment_change_shape();
