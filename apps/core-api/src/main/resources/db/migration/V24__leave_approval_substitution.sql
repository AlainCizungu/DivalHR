-- MVP-041G (Issue #97): a tenant administrator appoints one effective-dated substitute for a
-- manager, and revokes the appointment without erasing it; while it is effective, the substitute
-- may decide the manager-routed requests of that manager's reports beside the manager. No
-- self-delegation, chain, sub-delegation, concurrent substitutes, absence calendar, automatic
-- activation, escalation, notification, site/department limit, balance or payroll effect.
--
-- Ownership: the people module owns the new tables and column; only the bounded
-- com.divalhr.core.people.leave package reads or writes them. Keys reference people's own employee
-- and request rows and the tenant root; there is no cross-module reference.
--
-- Classification: Restricted HR. Appointment and revocation reasons are personal data; they are
-- returned only to tenant administrators and never enter audit metadata, events, logs or errors.
-- The actors (created_by, revoked_by) are never returned by the API. Decision authority and the
-- delegating manager are never returned to the requesting employee.
--
-- Database authority (V24):
--   * appointments are append-only (leave_approval_substitution_immutable, _no_truncate): one
--     manager, one different substitute (_distinct), both employees of the tenant, inclusive
--     bounded dates (_dates_valid), the shared reason grammar version 1 (people.leave_reason_valid).
--     There is no mutable status: an appointment is effective on a business date inside its period
--     when no revocation exists; SCHEDULED, ACTIVE, EXPIRED and REVOKED are derived;
--   * revocations are append-only, one per appointment (leave_approval_substitution_revocation_*);
--   * at most one NON-REVOKED appointment covers a manager on any date (R98-1). The exclusion
--     (leave_approval_substitution_coverage_no_overlap, btree_gist) sits on the operational
--     projection people.leave_approval_substitution_coverage, never on the historical rows: one
--     coverage row per unrevoked appointment, keyed to the appointment's exact tenant, manager and
--     inclusive dates (leave_approval_substitution_coverage_appointment, a composite foreign key),
--     inserted with the appointment and deleted with its revocation, in one transaction. The
--     projection is never the source of truth for authority, which always reads the appointment
--     and the absence of a revocation. Safeguards: a coverage row is never updated or truncated,
--     is deleted only once its appointment is revoked, and is never inserted for a revoked
--     appointment (leave_approval_substitution_coverage_guard); an appointment commits only with
--     its coverage or its revocation (leave_approval_substitution_covered, deferred); a revocation
--     commits only once the coverage is gone (leave_approval_substitution_revocation_uncovered,
--     deferred). A revoked appointment keeps its original dates and reason as evidence; its
--     remaining dates are free for a replacement;
--   * a decision names its authority: MANAGER (the deciding primary manager), MANAGER_SUBSTITUTE
--     (the deciding substitute in manager_employee_id and the primary manager it was delegated
--     from in delegated_from_manager_employee_id, never the same employee), TENANT_ADMIN_OVERRIDE
--     (no manager) under the MANAGER route, or TENANT_ADMIN (no manager) under the TENANT_ADMIN
--     route; no other shape (leave_request_decision_authority_shape, replaced). Existing decisions
--     keep a null delegated_from_manager_employee_id;
--   * a MANAGER_SUBSTITUTE decision commits only when the delegating manager is the request's
--     active MANAGER line on its first day, an unrevoked appointment of that manager names the
--     deciding substitute, and the substitute is not the requesting employee
--     (leave_request_decision_consistent, replaced; deferred). Whether the appointment covers the
--     organization's business date is the application's rule, checked under the tenant's
--     manager-graph lock, which appointment creation and revocation also take.
--
-- Indexes: leave_approval_substitution_coverage_no_overlap (tenant, manager, period) is the
-- overlap check; leave_approval_substitution_coverage_pkey the revocation's delete;
-- leave_approval_substitution_coverage_key the projection's composite key;
-- leave_approval_substitution_substitute is the substitute's active-appointment lookup;
-- leave_approval_substitution_list_order is the administrators' newest-first keyset;
-- leave_approval_substitution_revocation_substitution_unique is the revocation lookup. A
-- substitute decision's replay reads V20's leave_request_decision_request_unique.
--
-- Rollback: db/rollback/V24__rollback.sql (manual, never run by Flyway). It restores V23 exactly
-- only while no appointment, coverage, revocation or MANAGER_SUBSTITUTE decision exists; there is no
-- override, and it never touches flyway_schema_history.

-- ---------------------------------------------------------------------------------------------
-- Appointments: append-only, one per manager per date
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.leave_approval_substitution_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_approval_substitution_immutable',
        MESSAGE = 'leave approval substitutions are never changed or deleted';
END
$fn$;

CREATE FUNCTION people.leave_approval_substitution_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514',
        CONSTRAINT = 'leave_approval_substitution_no_truncate',
        MESSAGE = 'leave approval substitutions are never truncated';
END
$fn$;

CREATE TABLE people.leave_approval_substitution (
    id                     uuid        PRIMARY KEY,
    tenant_id              uuid        NOT NULL,
    manager_employee_id    uuid        NOT NULL,
    substitute_employee_id uuid        NOT NULL,
    effective_from         date        NOT NULL,
    effective_to           date        NOT NULL,
    reason_locale          text        NOT NULL,
    reason_text            text        NOT NULL,
    created_at             timestamptz NOT NULL,
    created_by             text        NOT NULL,
    CONSTRAINT leave_approval_substitution_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT leave_approval_substitution_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT leave_approval_substitution_manager
        FOREIGN KEY (tenant_id, manager_employee_id) REFERENCES people.employee (tenant_id, id),
    CONSTRAINT leave_approval_substitution_substitute
        FOREIGN KEY (tenant_id, substitute_employee_id) REFERENCES people.employee (tenant_id, id),
    CONSTRAINT leave_approval_substitution_distinct
        CHECK (manager_employee_id <> substitute_employee_id),
    CONSTRAINT leave_approval_substitution_dates_valid CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND effective_to BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND effective_to >= effective_from),
    CONSTRAINT leave_approval_substitution_locale_valid CHECK (reason_locale IN ('en', 'fr')),
    CONSTRAINT leave_approval_substitution_reason_valid
        CHECK (people.leave_reason_valid(reason_text)),
    CONSTRAINT leave_approval_substitution_created_by_length
        CHECK (char_length(created_by) BETWEEN 1 AND 255),
    -- The target of the coverage projection's composite key (exact tenant, manager and dates).
    CONSTRAINT leave_approval_substitution_coverage_key
        UNIQUE (id, tenant_id, manager_employee_id, effective_from, effective_to)
);

COMMENT ON TABLE people.leave_approval_substitution IS
    'MVP-041G effective-dated leave approval substitute of a manager (Restricted HR). Append-only evidence; one unrevoked per manager per date (see the coverage projection).';

-- The substitute's appointments (manager inbox and decision).
CREATE INDEX leave_approval_substitution_substitute
    ON people.leave_approval_substitution (tenant_id, substitute_employee_id, effective_from);
-- The administrators' list, newest first.
CREATE INDEX leave_approval_substitution_list_order
    ON people.leave_approval_substitution (tenant_id, created_at DESC, id DESC);

CREATE TRIGGER leave_approval_substitution_immutable
    BEFORE UPDATE OR DELETE ON people.leave_approval_substitution
    FOR EACH ROW EXECUTE FUNCTION people.leave_approval_substitution_immutable();
CREATE TRIGGER leave_approval_substitution_no_truncate
    BEFORE TRUNCATE ON people.leave_approval_substitution
    FOR EACH STATEMENT EXECUTE FUNCTION people.leave_approval_substitution_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Revocations: append-only, one per appointment
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION people.leave_approval_substitution_revocation_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514',
        CONSTRAINT = 'leave_approval_substitution_revocation_immutable',
        MESSAGE = 'leave approval substitution revocations are never changed or deleted';
END
$fn$;

CREATE FUNCTION people.leave_approval_substitution_revocation_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514',
        CONSTRAINT = 'leave_approval_substitution_revocation_no_truncate',
        MESSAGE = 'leave approval substitution revocations are never truncated';
END
$fn$;

CREATE TABLE people.leave_approval_substitution_revocation (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    substitution_id uuid        NOT NULL,
    reason_locale   text        NOT NULL,
    reason_text     text        NOT NULL,
    revoked_at      timestamptz NOT NULL,
    revoked_by      text        NOT NULL,
    CONSTRAINT leave_approval_substitution_revocation_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT leave_approval_substitution_revocation_substitution
        FOREIGN KEY (tenant_id, substitution_id)
            REFERENCES people.leave_approval_substitution (tenant_id, id),
    -- One revocation per appointment; also the access path of every effectiveness check.
    CONSTRAINT leave_approval_substitution_revocation_substitution_unique
        UNIQUE (tenant_id, substitution_id),
    CONSTRAINT leave_approval_substitution_revocation_locale_valid
        CHECK (reason_locale IN ('en', 'fr')),
    CONSTRAINT leave_approval_substitution_revocation_reason_valid
        CHECK (people.leave_reason_valid(reason_text)),
    CONSTRAINT leave_approval_substitution_revocation_revoked_by_length
        CHECK (char_length(revoked_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.leave_approval_substitution_revocation IS
    'MVP-041G revocation of a leave approval substitute (Restricted HR). One per appointment; append-only.';

CREATE TRIGGER leave_approval_substitution_revocation_immutable
    BEFORE UPDATE OR DELETE ON people.leave_approval_substitution_revocation
    FOR EACH ROW EXECUTE FUNCTION people.leave_approval_substitution_revocation_immutable();
CREATE TRIGGER leave_approval_substitution_revocation_no_truncate
    BEFORE TRUNCATE ON people.leave_approval_substitution_revocation
    FOR EACH STATEMENT EXECUTE FUNCTION people.leave_approval_substitution_revocation_no_truncate();

-- ---------------------------------------------------------------------------------------------
-- Coverage: the operational projection of unrevoked appointments (R98-1)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE people.leave_approval_substitution_coverage (
    tenant_id           uuid NOT NULL,
    substitution_id     uuid NOT NULL,
    manager_employee_id uuid NOT NULL,
    effective_from      date NOT NULL,
    effective_to        date NOT NULL,
    CONSTRAINT leave_approval_substitution_coverage_pkey PRIMARY KEY (tenant_id, substitution_id),
    -- Exactly the appointment's tenant, manager and dates: a mismatched row cannot exist.
    CONSTRAINT leave_approval_substitution_coverage_appointment
        FOREIGN KEY (tenant_id, substitution_id, manager_employee_id, effective_from, effective_to)
            REFERENCES people.leave_approval_substitution
                (tenant_id, id, manager_employee_id, effective_from, effective_to),
    -- At most one unrevoked appointment per manager on any date (both ends inclusive).
    CONSTRAINT leave_approval_substitution_coverage_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, manager_employee_id WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&)
);

COMMENT ON TABLE people.leave_approval_substitution_coverage IS
    'MVP-041G operational projection: one row per unrevoked leave approval substitute appointment, removed with its revocation. Never the source of truth for authority.';

-- Never updated or truncated; deleted only once revoked; never inserted for a revoked appointment.
CREATE FUNCTION people.leave_approval_substitution_coverage_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_row people.leave_approval_substitution_coverage%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'leave_approval_substitution_coverage_guard',
            MESSAGE = 'leave approval substitution coverage is never changed';
    END IF;
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF (TG_OP = 'DELETE') <> EXISTS (SELECT 1 FROM people.leave_approval_substitution_revocation x
            WHERE x.tenant_id = v_row.tenant_id AND x.substitution_id = v_row.substitution_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'leave_approval_substitution_coverage_guard',
            MESSAGE = 'coverage exists exactly while its appointment is not revoked';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$fn$;

CREATE FUNCTION people.leave_approval_substitution_coverage_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514',
        CONSTRAINT = 'leave_approval_substitution_coverage_no_truncate',
        MESSAGE = 'leave approval substitution coverage is never truncated';
END
$fn$;

CREATE TRIGGER leave_approval_substitution_coverage_guard
    BEFORE INSERT OR UPDATE OR DELETE ON people.leave_approval_substitution_coverage
    FOR EACH ROW EXECUTE FUNCTION people.leave_approval_substitution_coverage_guard();
CREATE TRIGGER leave_approval_substitution_coverage_no_truncate
    BEFORE TRUNCATE ON people.leave_approval_substitution_coverage
    FOR EACH STATEMENT EXECUTE FUNCTION people.leave_approval_substitution_coverage_no_truncate();

-- An appointment commits only with its coverage (or, created and revoked at once, its revocation).
CREATE FUNCTION people.leave_approval_substitution_covered() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM people.leave_approval_substitution_coverage c
            WHERE c.tenant_id = NEW.tenant_id AND c.substitution_id = NEW.id)
        AND NOT EXISTS (SELECT 1 FROM people.leave_approval_substitution_revocation x
            WHERE x.tenant_id = NEW.tenant_id AND x.substitution_id = NEW.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'leave_approval_substitution_covered',
            MESSAGE = 'an unrevoked appointment commits with its coverage';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER leave_approval_substitution_covered
    AFTER INSERT ON people.leave_approval_substitution
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.leave_approval_substitution_covered();

-- A revocation commits only once its appointment's coverage is gone.
CREATE FUNCTION people.leave_approval_substitution_revocation_uncovered() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF EXISTS (SELECT 1 FROM people.leave_approval_substitution_coverage c
            WHERE c.tenant_id = NEW.tenant_id AND c.substitution_id = NEW.substitution_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'leave_approval_substitution_revocation_uncovered',
            MESSAGE = 'a revocation commits with its coverage removed';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER leave_approval_substitution_revocation_uncovered
    AFTER INSERT ON people.leave_approval_substitution_revocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.leave_approval_substitution_revocation_uncovered();

-- ---------------------------------------------------------------------------------------------
-- Decisions: the substitute authority and the manager it was delegated from
-- ---------------------------------------------------------------------------------------------

-- Existing decisions were never delegated: the column is added null, without rewriting a row.
ALTER TABLE people.leave_request_decision
    ADD COLUMN delegated_from_manager_employee_id uuid,
    ADD CONSTRAINT leave_request_decision_delegated_from
        FOREIGN KEY (tenant_id, delegated_from_manager_employee_id)
            REFERENCES people.employee (tenant_id, id),
    DROP CONSTRAINT leave_request_decision_authority_shape,
    ADD CONSTRAINT leave_request_decision_authority_shape CHECK (
        (approval_route = 'MANAGER' AND decision_authority = 'MANAGER'
            AND manager_employee_id IS NOT NULL
            AND delegated_from_manager_employee_id IS NULL)
        OR (approval_route = 'MANAGER' AND decision_authority = 'MANAGER_SUBSTITUTE'
            AND manager_employee_id IS NOT NULL
            AND delegated_from_manager_employee_id IS NOT NULL
            AND delegated_from_manager_employee_id <> manager_employee_id)
        OR (approval_route = 'TENANT_ADMIN' AND decision_authority = 'TENANT_ADMIN'
            AND manager_employee_id IS NULL
            AND delegated_from_manager_employee_id IS NULL)
        OR (approval_route = 'MANAGER' AND decision_authority = 'TENANT_ADMIN_OVERRIDE'
            AND manager_employee_id IS NULL
            AND delegated_from_manager_employee_id IS NULL));

-- A decision commits only with its request in the decided state and under the route of the
-- request's policy version (V22); a substitute decision also only with the delegating manager as
-- the request's active MANAGER line on its first day, an unrevoked appointment of that manager
-- naming the substitute, and a substitute who is not the requesting employee.
CREATE OR REPLACE FUNCTION people.leave_request_decision_consistent() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_state      text;
    v_route      text;
    v_employee   uuid;
    v_employment uuid;
    v_start      date;
BEGIN
    SELECT r.state, v.approval_route, r.employee_id, r.employment_id, r.start_date
        INTO v_state, v_route, v_employee, v_employment, v_start
        FROM people.leave_request r
        JOIN people.leave_policy_version v
            ON v.tenant_id = r.tenant_id AND v.id = r.policy_version_id
        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.request_id;
    IF v_state IS DISTINCT FROM NEW.outcome OR v_route IS DISTINCT FROM NEW.approval_route
        OR (NEW.decision_authority = 'MANAGER_SUBSTITUTE' AND (
            NEW.manager_employee_id = v_employee
            OR NOT EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.tenant_id = NEW.tenant_id AND a.employment_id = v_employment
                    AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL
                    AND a.manager_employee_id = NEW.delegated_from_manager_employee_id
                    AND a.effective_from <= v_start
                    AND (a.effective_to IS NULL OR a.effective_to >= v_start))
            OR NOT EXISTS (SELECT 1 FROM people.leave_approval_substitution s
                WHERE s.tenant_id = NEW.tenant_id
                    AND s.manager_employee_id = NEW.delegated_from_manager_employee_id
                    AND s.substitute_employee_id = NEW.manager_employee_id
                    AND NOT EXISTS (SELECT 1 FROM people.leave_approval_substitution_revocation x
                        WHERE x.tenant_id = s.tenant_id AND x.substitution_id = s.id)))) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_decision_consistent',
            MESSAGE = 'a decision matches its request state, its policy route and its authority';
    END IF;
    RETURN NULL;
END
$fn$;
