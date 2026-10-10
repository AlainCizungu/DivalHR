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
--   * the periods of one manager's appointments never overlap
--     (leave_approval_substitution_no_overlap, btree_gist), so at most one substitute covers a
--     manager on any date. A revocation does not shorten the stored period: a revoked appointment
--     keeps its dates for the overlap rule;
--   * revocations are append-only, one per appointment (leave_approval_substitution_revocation_*);
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
-- Indexes: leave_approval_substitution_no_overlap (tenant, manager, period) is the manager lookup;
-- leave_approval_substitution_substitute is the substitute's active-appointment lookup;
-- leave_approval_substitution_list_order is the administrators' newest-first keyset;
-- leave_approval_substitution_revocation_substitution_unique is the revocation lookup. A
-- substitute decision's replay reads V20's leave_request_decision_request_unique.
--
-- Rollback: db/rollback/V24__rollback.sql (manual, never run by Flyway). It restores V23 exactly
-- only while no appointment, no revocation and no MANAGER_SUBSTITUTE decision exists; there is no
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
    -- One appointment per manager on any date (both ends inclusive), revoked or not.
    CONSTRAINT leave_approval_substitution_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, manager_employee_id WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&)
);

COMMENT ON TABLE people.leave_approval_substitution IS
    'MVP-041G effective-dated leave approval substitute of a manager (Restricted HR). Append-only; one per manager per date.';

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
