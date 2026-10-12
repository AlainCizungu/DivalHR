-- MVP-051 (Issue #105): an employee requests a substitute for one of their own future published
-- shifts; their effective manager approves it by choosing an eligible direct report, or rejects it,
-- each with a reason. Approval changes who operationally works the shift; the published schedule
-- and slot never change.
--
-- Ownership: the people module owns every table here; only the bounded
-- com.divalhr.core.people.schedule package reads or writes them (ADR 0014, ADR 0015). The guards
-- read the module's own employment, assignment and separation tables (same module).
--
-- Classification: requests, decisions and their reasons are Restricted HR/Operations. The verified
-- subjects are evidence only, never returned.
--
-- Database authority (V27):
--   * people.work_schedule and people.work_schedule_slot keep every V25 and V26 rule;
--   * requests and decisions are append-only evidence (work_schedule_substitution_immutable,
--     _no_truncate); reasons follow the decision-reason grammar people.leave_reason_valid (V20);
--   * a request names a slot originally assigned to its employee (_request_slot), one employment
--     of that employee (_request_employment) that covers every local date the shift occupies, is
--     placed at the schedule's site and has an active MANAGER line on the shift's site-local start
--     date (_request_employment_qualifies, _request_manager); it is recorded only while the
--     schedule has no terminal action, before the shift starts, with no approved substitution and
--     with the original employee's own coverage row in place (_request_window);
--   * a decision is the only decision of its request (_decision_request_unique), APPROVED with a
--     substitute employee and employment, or REJECTED with neither (_decision_shape); the manager
--     is never the original employee, the substitute neither of them; at most one approval per slot
--     (_decision_one_approval). It is recorded only while the request is pending, the schedule has
--     no terminal action and the shift has not started (_decision_window), by the request
--     employment's active MANAGER on the shift's site-local start date (_decision_authority); an
--     approval's substitute employment belongs to the substitute, covers every occupied date, is
--     placed at the site and reports to the same manager on that date, and the substitute has no
--     scheduled or effective separation before the shift ends (_decision_substitute);
--   * people.work_schedule_substitution_pending is a guarded projection: one pending request per
--     slot (_pending_slot_unique); never updated or truncated; inserted only for an undecided
--     request of an operational, unsubstituted slot; deleted only once its request is decided or
--     its schedule has a terminal action (work_schedule_substitution_pending_guard);
--   * people.work_schedule_slot_coverage rows are bound either to the original published employee
--     (decision_id NULL: work_schedule_slot_coverage_published, through the generated
--     published_employee_id) or to the approved substitute (work_schedule_slot_coverage_substitution
--     to the decision's slot, schedule and substitute); always to the immutable slot, schedule and
--     instants (work_schedule_slot_coverage_slot). An original row is inserted only while the slot
--     has no approval and deleted only once it has one or the schedule is terminal; a substitute row
--     only while the schedule is operational (work_schedule_slot_coverage_guard). The V26
--     exclusion work_schedule_slot_employee_no_overlap stays authoritative for every effective
--     assignment;
--   * deferred (work_schedule_substitution_exact, per schedule): no request is both pending and
--     decided; a request neither pending nor decided belongs to a terminal schedule; a terminal
--     schedule has no pending request; every coverage row of an operational schedule is bound to
--     its slot's approval, or to nothing when there is none. The V26 counts
--     (work_schedule_coverage_exact) still hold: one coverage row per assigned slot.
-- No backfill: every V26 coverage row is an original row (decision_id NULL).
--
-- Indexes: work_schedule_substitution_request_employee (the employee's history, newest first);
-- work_schedule_substitution_request_employment (the manager inbox through the MANAGER lines);
-- work_schedule_substitution_request_slot; work_schedule_substitution_pending_schedule (the
-- terminal action's deletes); work_schedule_substitution_decision_one_approval (a slot's approval);
-- work_schedule_slot_coverage_employee (an employee's operational shifts in start order).
--
-- Rollback: db/rollback/V27__rollback.sql (manual, never run by Flyway). It restores V26 exactly
-- only while no request, decision or pending row exists and every coverage row is again the
-- original published assignment; there is no override, and it never touches
-- flyway_schema_history.

-- ---------------------------------------------------------------------------------------------
-- Keys of the immutable slot (new indexes; no row is rewritten)
-- ---------------------------------------------------------------------------------------------

ALTER TABLE people.work_schedule_slot
    ADD CONSTRAINT work_schedule_slot_original_key
        UNIQUE (tenant_id, id, schedule_id, assigned_employee_id);
ALTER TABLE people.work_schedule_slot
    ADD CONSTRAINT work_schedule_slot_instants_key
        UNIQUE (tenant_id, id, schedule_id, start_at, end_at);

-- The last local date a slot occupies: a slot ending at midnight ends on the date before.
CREATE FUNCTION people.work_schedule_slot_last_date(p_start timestamp, p_end timestamp)
    RETURNS date
    LANGUAGE sql IMMUTABLE
AS $fn$
SELECT CASE WHEN p_end::time = TIME '00:00' AND p_end::date > p_start::date
    THEN p_end::date - 1 ELSE p_end::date END
$fn$;

-- Whether an employment covers the dates, is placed at the site and has the manager (or, with
-- p_manager NULL, any manager other than its employee) on the first date.
CREATE FUNCTION people.work_schedule_substitution_employment_qualifies(
    p_tenant uuid, p_employment uuid, p_employee uuid, p_site uuid, p_first date, p_last date,
    p_manager uuid) RETURNS boolean
    LANGUAGE sql STABLE
AS $fn$
SELECT EXISTS (SELECT 1 FROM people.employment e
        WHERE e.tenant_id = p_tenant AND e.id = p_employment AND e.employee_id = p_employee
            AND e.effective_from <= p_first
            AND (e.effective_to IS NULL OR e.effective_to >= p_last))
    AND EXISTS (SELECT 1 FROM people.employment_assignment a
        WHERE a.tenant_id = p_tenant AND a.employment_id = p_employment
            AND a.kind = 'PLACEMENT' AND a.superseded_by_change_id IS NULL
            AND a.site_id = p_site AND a.effective_from <= p_first
            AND (a.effective_to IS NULL OR a.effective_to >= p_first))
    AND EXISTS (SELECT 1 FROM people.employment_assignment a
        WHERE a.tenant_id = p_tenant AND a.employment_id = p_employment
            AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL
            AND a.manager_employee_id IS NOT NULL
            AND (p_manager IS NULL OR a.manager_employee_id = p_manager)
            AND a.effective_from <= p_first
            AND (a.effective_to IS NULL OR a.effective_to >= p_first))
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Evidence: requests and decisions (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.work_schedule_substitution_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_immutable',
        MESSAGE = 'work schedule substitution evidence is never changed or deleted';
END
$fn$;

CREATE FUNCTION people.work_schedule_substitution_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_no_truncate',
        MESSAGE = 'work schedule substitution data is never truncated';
END
$fn$;

CREATE TABLE people.work_schedule_substitution_request (
    tenant_id     uuid        NOT NULL,
    id            uuid        NOT NULL,
    schedule_id   uuid        NOT NULL,
    slot_id       uuid        NOT NULL,
    employee_id   uuid        NOT NULL,
    employment_id uuid        NOT NULL,
    reason_locale text        NOT NULL,
    reason        text        NOT NULL,
    requested_at  timestamptz NOT NULL,
    requested_by  text        NOT NULL,
    CONSTRAINT work_schedule_substitution_request_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT work_schedule_substitution_request_key
        UNIQUE (tenant_id, id, slot_id, schedule_id, employee_id),
    CONSTRAINT work_schedule_substitution_request_pending_key
        UNIQUE (tenant_id, id, slot_id, schedule_id),
    -- The slot as originally published to this employee (never an uncovered slot).
    CONSTRAINT work_schedule_substitution_request_slot
        FOREIGN KEY (tenant_id, slot_id, schedule_id, employee_id)
            REFERENCES people.work_schedule_slot (tenant_id, id, schedule_id, assigned_employee_id),
    CONSTRAINT work_schedule_substitution_request_employment
        FOREIGN KEY (tenant_id, employment_id, employee_id)
            REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT work_schedule_substitution_request_locale CHECK (reason_locale IN ('en', 'fr')),
    CONSTRAINT work_schedule_substitution_request_reason_valid
        CHECK (people.leave_reason_valid(reason)),
    CONSTRAINT work_schedule_substitution_request_by_length
        CHECK (char_length(requested_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.work_schedule_substitution_request IS
    'MVP-051 request for a substitute on a future published shift (Restricted HR reason). Append-only evidence; the manager is never stored, it is evaluated.';

CREATE INDEX work_schedule_substitution_request_employee
    ON people.work_schedule_substitution_request (tenant_id, employee_id, requested_at DESC, id DESC);
CREATE INDEX work_schedule_substitution_request_employment
    ON people.work_schedule_substitution_request (tenant_id, employment_id);
CREATE INDEX work_schedule_substitution_request_slot
    ON people.work_schedule_substitution_request (tenant_id, slot_id);

CREATE TABLE people.work_schedule_substitution_decision (
    tenant_id                 uuid        NOT NULL,
    id                        uuid        NOT NULL,
    request_id                uuid        NOT NULL,
    schedule_id               uuid        NOT NULL,
    slot_id                   uuid        NOT NULL,
    original_employee_id      uuid        NOT NULL,
    outcome                   text        NOT NULL,
    manager_employee_id       uuid        NOT NULL,
    replacement_employee_id   uuid,
    replacement_employment_id uuid,
    reason_locale             text        NOT NULL,
    reason                    text        NOT NULL,
    decided_at                timestamptz NOT NULL,
    decided_by                text        NOT NULL,
    CONSTRAINT work_schedule_substitution_decision_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT work_schedule_substitution_decision_request_unique UNIQUE (tenant_id, request_id),
    -- The target of an approved substitute's coverage row.
    CONSTRAINT work_schedule_substitution_decision_coverage_key
        UNIQUE (tenant_id, id, slot_id, schedule_id, replacement_employee_id),
    CONSTRAINT work_schedule_substitution_decision_request
        FOREIGN KEY (tenant_id, request_id, slot_id, schedule_id, original_employee_id)
            REFERENCES people.work_schedule_substitution_request
                (tenant_id, id, slot_id, schedule_id, employee_id),
    CONSTRAINT work_schedule_substitution_decision_manager
        FOREIGN KEY (tenant_id, manager_employee_id) REFERENCES people.employee (tenant_id, id),
    CONSTRAINT work_schedule_substitution_decision_replacement
        FOREIGN KEY (tenant_id, replacement_employment_id, replacement_employee_id)
            REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT work_schedule_substitution_decision_shape CHECK (
        manager_employee_id <> original_employee_id
        AND ((outcome = 'APPROVED'
                AND replacement_employee_id IS NOT NULL
                AND replacement_employment_id IS NOT NULL
                AND replacement_employee_id <> original_employee_id
                AND replacement_employee_id <> manager_employee_id)
            OR (outcome = 'REJECTED'
                AND replacement_employee_id IS NULL
                AND replacement_employment_id IS NULL))),
    CONSTRAINT work_schedule_substitution_decision_locale CHECK (reason_locale IN ('en', 'fr')),
    CONSTRAINT work_schedule_substitution_decision_reason_valid
        CHECK (people.leave_reason_valid(reason)),
    CONSTRAINT work_schedule_substitution_decision_by_length
        CHECK (char_length(decided_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.work_schedule_substitution_decision IS
    'MVP-051 manager decision on a shift-substitution request (Restricted HR reason). Append-only evidence; one decision per request, one approval per slot.';

-- At most one approved substitution per slot: no re-substitution, no chain.
CREATE UNIQUE INDEX work_schedule_substitution_decision_one_approval
    ON people.work_schedule_substitution_decision (tenant_id, slot_id)
    WHERE outcome = 'APPROVED';

CREATE TRIGGER work_schedule_substitution_request_immutable
    BEFORE UPDATE OR DELETE ON people.work_schedule_substitution_request
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_immutable();
CREATE TRIGGER work_schedule_substitution_request_no_truncate
    BEFORE TRUNCATE ON people.work_schedule_substitution_request
    FOR EACH STATEMENT EXECUTE FUNCTION people.work_schedule_substitution_no_truncate();
CREATE TRIGGER work_schedule_substitution_decision_immutable
    BEFORE UPDATE OR DELETE ON people.work_schedule_substitution_decision
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_immutable();
CREATE TRIGGER work_schedule_substitution_decision_no_truncate
    BEFORE TRUNCATE ON people.work_schedule_substitution_decision
    FOR EACH STATEMENT EXECUTE FUNCTION people.work_schedule_substitution_no_truncate();

-- Whether a slot has an approved substitution.
CREATE FUNCTION people.work_schedule_slot_substituted(p_tenant uuid, p_slot uuid) RETURNS boolean
    LANGUAGE sql STABLE
AS $fn$
SELECT EXISTS (SELECT 1 FROM people.work_schedule_substitution_decision d
    WHERE d.tenant_id = p_tenant AND d.slot_id = p_slot AND d.outcome = 'APPROVED')
$fn$;

-- A request: operational schedule, future shift, no approval, the requester's own original
-- coverage, and a qualifying employment with a manager.
CREATE FUNCTION people.work_schedule_substitution_request_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_site     uuid;
    v_start_at timestamptz;
    v_first    date;
    v_last     date;
BEGIN
    -- Serializes with terminal actions and decisions of the schedule, whatever the caller.
    SELECT s.site_id INTO v_site FROM people.work_schedule s
        WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.schedule_id
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    SELECT l.start_at, l.start_local::date,
            people.work_schedule_slot_last_date(l.start_local, l.end_local)
        INTO v_start_at, v_first, v_last
        FROM people.work_schedule_slot l
        WHERE l.tenant_id = NEW.tenant_id AND l.id = NEW.slot_id
            AND l.schedule_id = NEW.schedule_id AND l.assigned_employee_id = NEW.employee_id;
    IF NOT FOUND THEN
        -- Not this employee's slot: left to the foreign key.
        RETURN NEW;
    END IF;
    IF people.work_schedule_terminal(NEW.tenant_id, NEW.schedule_id)
        OR NEW.requested_at >= v_start_at
        OR people.work_schedule_slot_substituted(NEW.tenant_id, NEW.slot_id)
        OR NOT EXISTS (SELECT 1 FROM people.work_schedule_slot_coverage c
            WHERE c.tenant_id = NEW.tenant_id AND c.slot_id = NEW.slot_id
                AND c.employee_id = NEW.employee_id AND c.decision_id IS NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'work_schedule_substitution_request_window',
            MESSAGE = 'a substitution is requested for an operational future shift still covered by its employee';
    END IF;
    IF NOT people.work_schedule_substitution_employment_qualifies(NEW.tenant_id,
            NEW.employment_id, NEW.employee_id, v_site, v_first, v_last, NULL) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'work_schedule_substitution_request_employment_qualifies',
            MESSAGE = 'a substitution request names a qualifying employment with a manager';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER work_schedule_substitution_request_guard
    BEFORE INSERT ON people.work_schedule_substitution_request
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_request_guard();

-- A decision: pending request, operational schedule, shift not started, decided by the request
-- employment's manager; an approval's substitute qualifies under the same manager and is not
-- separated before the shift ends.
CREATE FUNCTION people.work_schedule_substitution_decision_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_site         uuid;
    v_start_at     timestamptz;
    v_end_at       timestamptz;
    v_first        date;
    v_last         date;
    v_employment   uuid;
    v_requested_at timestamptz;
BEGIN
    SELECT s.site_id INTO v_site FROM people.work_schedule s
        WHERE s.tenant_id = NEW.tenant_id AND s.id = NEW.schedule_id
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    SELECT r.employment_id, r.requested_at, l.start_at, l.end_at, l.start_local::date,
            people.work_schedule_slot_last_date(l.start_local, l.end_local)
        INTO v_employment, v_requested_at, v_start_at, v_end_at, v_first, v_last
        FROM people.work_schedule_substitution_request r
        JOIN people.work_schedule_slot l ON l.tenant_id = r.tenant_id AND l.id = r.slot_id
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.request_id
            AND r.slot_id = NEW.slot_id AND r.schedule_id = NEW.schedule_id;
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    IF people.work_schedule_terminal(NEW.tenant_id, NEW.schedule_id)
        OR NEW.decided_at >= v_start_at OR NEW.decided_at < v_requested_at
        OR NOT EXISTS (SELECT 1 FROM people.work_schedule_substitution_pending p
            WHERE p.tenant_id = NEW.tenant_id AND p.request_id = NEW.request_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'work_schedule_substitution_decision_window',
            MESSAGE = 'a substitution is decided while pending, operational and not started';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM people.employment_assignment a
            WHERE a.tenant_id = NEW.tenant_id AND a.employment_id = v_employment
                AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL
                AND a.manager_employee_id = NEW.manager_employee_id
                AND a.effective_from <= v_first
                AND (a.effective_to IS NULL OR a.effective_to >= v_first)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'work_schedule_substitution_decision_authority',
            MESSAGE = 'a substitution is decided by the request employment''s manager';
    END IF;
    IF NEW.outcome = 'APPROVED' AND (
            NOT people.work_schedule_substitution_employment_qualifies(NEW.tenant_id,
                NEW.replacement_employment_id, NEW.replacement_employee_id, v_site, v_first,
                v_last, NEW.manager_employee_id)
            OR EXISTS (SELECT 1 FROM people.employment_separation x
                WHERE x.tenant_id = NEW.tenant_id AND x.employee_id = NEW.replacement_employee_id
                    AND x.state IN ('SCHEDULED', 'EFFECTIVE') AND x.effective_at < v_end_at)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'work_schedule_substitution_decision_substitute',
            MESSAGE = 'an approved substitute qualifies under the same manager';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER work_schedule_substitution_decision_guard
    BEFORE INSERT ON people.work_schedule_substitution_decision
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_decision_guard();

-- ---------------------------------------------------------------------------------------------
-- The pending projection (one pending request per slot)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.work_schedule_substitution_pending (
    tenant_id   uuid NOT NULL,
    request_id  uuid NOT NULL,
    slot_id     uuid NOT NULL,
    schedule_id uuid NOT NULL,
    CONSTRAINT work_schedule_substitution_pending_pkey PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT work_schedule_substitution_pending_slot_unique UNIQUE (tenant_id, slot_id),
    CONSTRAINT work_schedule_substitution_pending_request
        FOREIGN KEY (tenant_id, request_id, slot_id, schedule_id)
            REFERENCES people.work_schedule_substitution_request
                (tenant_id, id, slot_id, schedule_id)
);

COMMENT ON TABLE people.work_schedule_substitution_pending IS
    'MVP-051 operational projection: one row per undecided request of an operational schedule, removed by its decision or by the schedule''s cancellation or replacement. Never the source of state.';

CREATE INDEX work_schedule_substitution_pending_schedule
    ON people.work_schedule_substitution_pending (tenant_id, schedule_id);

CREATE FUNCTION people.work_schedule_substitution_pending_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_decided boolean;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'work_schedule_substitution_pending_guard',
            MESSAGE = 'a pending substitution is never changed';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT EXISTS (SELECT 1 FROM people.work_schedule_substitution_decision d
                WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.request_id)
            INTO v_decided;
        IF v_decided OR people.work_schedule_terminal(NEW.tenant_id, NEW.schedule_id)
            OR people.work_schedule_slot_substituted(NEW.tenant_id, NEW.slot_id) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'work_schedule_substitution_pending_guard',
                MESSAGE = 'only an undecided request of an operational, unsubstituted slot is pending';
        END IF;
        RETURN NEW;
    END IF;
    SELECT EXISTS (SELECT 1 FROM people.work_schedule_substitution_decision d
            WHERE d.tenant_id = OLD.tenant_id AND d.request_id = OLD.request_id)
        INTO v_decided;
    IF NOT v_decided AND NOT people.work_schedule_terminal(OLD.tenant_id, OLD.schedule_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'work_schedule_substitution_pending_guard',
            MESSAGE = 'a pending substitution ends only with its decision or its schedule';
    END IF;
    RETURN OLD;
END
$fn$;

CREATE TRIGGER work_schedule_substitution_pending_guard
    BEFORE INSERT OR UPDATE OR DELETE ON people.work_schedule_substitution_pending
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_pending_guard();
CREATE TRIGGER work_schedule_substitution_pending_no_truncate
    BEFORE TRUNCATE ON people.work_schedule_substitution_pending
    FOR EACH STATEMENT EXECUTE FUNCTION people.work_schedule_substitution_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Coverage: bound to the original employee or to the approved substitute
-- ---------------------------------------------------------------------------------------------

ALTER TABLE people.work_schedule_slot_coverage ADD COLUMN decision_id uuid;
ALTER TABLE people.work_schedule_slot_coverage
    ADD COLUMN published_employee_id uuid
        GENERATED ALWAYS AS (CASE WHEN decision_id IS NULL THEN employee_id END) STORED;

-- The slot, schedule and instants stay exact for every row; the employee is the original
-- assignee (no decision) or the approved decision's substitute.
ALTER TABLE people.work_schedule_slot_coverage DROP CONSTRAINT work_schedule_slot_coverage_slot;
ALTER TABLE people.work_schedule_slot_coverage
    ADD CONSTRAINT work_schedule_slot_coverage_slot
        FOREIGN KEY (tenant_id, slot_id, schedule_id, start_at, end_at)
            REFERENCES people.work_schedule_slot (tenant_id, id, schedule_id, start_at, end_at);
ALTER TABLE people.work_schedule_slot_coverage
    ADD CONSTRAINT work_schedule_slot_coverage_published
        FOREIGN KEY (tenant_id, slot_id, schedule_id, published_employee_id)
            REFERENCES people.work_schedule_slot (tenant_id, id, schedule_id, assigned_employee_id);
ALTER TABLE people.work_schedule_slot_coverage
    ADD CONSTRAINT work_schedule_slot_coverage_substitution
        FOREIGN KEY (tenant_id, decision_id, slot_id, schedule_id, employee_id)
            REFERENCES people.work_schedule_substitution_decision
                (tenant_id, id, slot_id, schedule_id, replacement_employee_id);
ALTER TABLE people.work_schedule_slot_coverage
    ADD CONSTRAINT work_schedule_slot_coverage_employee
        FOREIGN KEY (tenant_id, employee_id) REFERENCES people.employee (tenant_id, id);

CREATE INDEX work_schedule_slot_coverage_employee
    ON people.work_schedule_slot_coverage (tenant_id, employee_id, start_at, slot_id);

-- Never updated. An original row: inserted only while the schedule is operational and the slot
-- has no approval; deleted once the schedule is terminal or the slot approved. A substitute row:
-- inserted only while the schedule is operational; deleted once it is terminal.
CREATE FUNCTION people.work_schedule_slot_coverage_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_slot_coverage_guard',
            MESSAGE = 'work schedule coverage is never changed';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF people.work_schedule_terminal(NEW.tenant_id, NEW.schedule_id)
            OR (NEW.decision_id IS NULL
                AND people.work_schedule_slot_substituted(NEW.tenant_id, NEW.slot_id)) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'work_schedule_slot_coverage_guard',
                MESSAGE = 'coverage is added only to an operational schedule, once per binding';
        END IF;
        RETURN NEW;
    END IF;
    IF people.work_schedule_terminal(OLD.tenant_id, OLD.schedule_id)
        OR (OLD.decision_id IS NULL
            AND people.work_schedule_slot_substituted(OLD.tenant_id, OLD.slot_id)) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'work_schedule_slot_coverage_guard',
        MESSAGE = 'coverage is removed only by a terminal action or an approved substitution';
END
$fn$;

DROP TRIGGER work_schedule_slot_coverage_guard ON people.work_schedule_slot_coverage;
CREATE TRIGGER work_schedule_slot_coverage_guard
    BEFORE INSERT OR UPDATE OR DELETE ON people.work_schedule_slot_coverage
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_slot_coverage_guard();

-- ---------------------------------------------------------------------------------------------
-- Exactness (deferred, per schedule)
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.work_schedule_substitution_exact() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_tenant   uuid;
    v_schedule uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_tenant := OLD.tenant_id;
        v_schedule := OLD.schedule_id;
    ELSE
        v_tenant := NEW.tenant_id;
        v_schedule := NEW.schedule_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM people.work_schedule s
            WHERE s.tenant_id = v_tenant AND s.id = v_schedule) THEN
        RETURN NULL;
    END IF;
    -- No request is both pending and decided.
    IF EXISTS (SELECT 1 FROM people.work_schedule_substitution_pending p
            JOIN people.work_schedule_substitution_decision d
                ON d.tenant_id = p.tenant_id AND d.request_id = p.request_id
            WHERE p.tenant_id = v_tenant AND p.schedule_id = v_schedule) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'work_schedule_substitution_exact',
            MESSAGE = 'a decided substitution is no longer pending';
    END IF;
    IF people.work_schedule_terminal(v_tenant, v_schedule) THEN
        IF EXISTS (SELECT 1 FROM people.work_schedule_substitution_pending p
                WHERE p.tenant_id = v_tenant AND p.schedule_id = v_schedule) THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'work_schedule_substitution_exact',
                MESSAGE = 'a cancelled or replaced work schedule has no pending substitution';
        END IF;
        RETURN NULL;
    END IF;
    -- An operational schedule: every request is pending or decided, and every coverage row is
    -- bound to its slot's approval, or to nothing when there is none.
    IF EXISTS (SELECT 1 FROM people.work_schedule_substitution_request r
            WHERE r.tenant_id = v_tenant AND r.schedule_id = v_schedule
                AND NOT EXISTS (SELECT 1 FROM people.work_schedule_substitution_pending p
                    WHERE p.tenant_id = r.tenant_id AND p.request_id = r.id)
                AND NOT EXISTS (SELECT 1 FROM people.work_schedule_substitution_decision d
                    WHERE d.tenant_id = r.tenant_id AND d.request_id = r.id))
        OR EXISTS (SELECT 1 FROM people.work_schedule_slot_coverage c
            LEFT JOIN people.work_schedule_substitution_decision d
                ON d.tenant_id = c.tenant_id AND d.slot_id = c.slot_id AND d.outcome = 'APPROVED'
            WHERE c.tenant_id = v_tenant AND c.schedule_id = v_schedule
                AND c.decision_id IS DISTINCT FROM d.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'work_schedule_substitution_exact',
            MESSAGE = 'an operational schedule''s substitutions and coverage agree';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER work_schedule_substitution_request_exact
    AFTER INSERT ON people.work_schedule_substitution_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_exact();
CREATE CONSTRAINT TRIGGER work_schedule_substitution_decision_exact
    AFTER INSERT ON people.work_schedule_substitution_decision
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_exact();
CREATE CONSTRAINT TRIGGER work_schedule_substitution_pending_exact
    AFTER INSERT OR DELETE ON people.work_schedule_substitution_pending
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_exact();
CREATE CONSTRAINT TRIGGER work_schedule_slot_coverage_bound
    AFTER INSERT OR DELETE ON people.work_schedule_slot_coverage
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_exact();
CREATE CONSTRAINT TRIGGER work_schedule_cancellation_unpending
    AFTER INSERT ON people.work_schedule_cancellation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_exact();
CREATE CONSTRAINT TRIGGER work_schedule_replacement_unpending
    AFTER INSERT ON people.work_schedule_replacement
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.work_schedule_substitution_exact();
