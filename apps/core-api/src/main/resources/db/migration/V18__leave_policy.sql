-- MVP-040A: basic leave policy configuration (configuration only: no requests, balances or
-- approvals).
--
-- Ownership: the people module owns both tables (docs/DATA-MODEL.md, People: LeavePolicy). Only
-- the bounded com.divalhr.core.people.leave package reads or writes them. The tenant foreign key
-- to tenant.organization is the same tenant-root constraint every tenant table carries; there is
-- no other cross-module reference.
--
-- Classification: Confidential organization data. No employee personal data.
--
-- Model: people.leave_policy is the immutable identity (normalized code, unique per tenant).
-- people.leave_policy_version holds the bilingual names and the configuration; this story writes
-- version 1 only (a later version needs a new migration). Versions of one policy never overlap in
-- time. No legal or statutory defaults are stored: every value is the organization's own.
--
-- Database authority: both tables are insert-only (never updated, deleted or truncated); a policy
-- commits only with its version 1 (deferred check). Trigger failures carry stable constraint names.
--
-- Rollback: db/rollback/V18__rollback.sql (manual, never run by Flyway). It refuses once either
-- table holds a row, with no override, and never touches flyway_schema_history.

CREATE FUNCTION people.leave_policy_name_valid(name text) RETURNS boolean
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $fn$
SELECT name IS NFC NORMALIZED
    AND name = btrim(name)
    AND char_length(name) BETWEEN 2 AND 100
    AND name !~ '[[:cntrl:]]'
$fn$;

CREATE FUNCTION people.leave_policy_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_policy_no_truncate',
        MESSAGE = 'leave policy data is never truncated';
END
$fn$;

CREATE FUNCTION people.leave_policy_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_ARGV[0],
        MESSAGE = 'leave policies and their versions are never changed or deleted';
END
$fn$;

-- =============================================================================================
-- Identity
-- =============================================================================================
CREATE TABLE people.leave_policy (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL,
    code       text        NOT NULL,
    created_at timestamptz NOT NULL,
    created_by text        NOT NULL,
    CONSTRAINT leave_policy_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT leave_policy_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT leave_policy_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT leave_policy_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$'),
    CONSTRAINT leave_policy_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE people.leave_policy IS
    'MVP-040A leave policy identity (Confidential organization data). Insert-only.';

-- Keyset order of the list (code, then id) within one tenant.
CREATE INDEX leave_policy_list_order ON people.leave_policy (tenant_id, code, id);

-- =============================================================================================
-- Versions (configuration)
-- =============================================================================================
CREATE TABLE people.leave_policy_version (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL,
    policy_id            uuid        NOT NULL,
    version_number       integer     NOT NULL,
    name_en              text        NOT NULL,
    name_fr              text        NOT NULL,
    unit                 text        NOT NULL,
    balance_mode         text        NOT NULL,
    annual_entitlement   numeric,
    minimum_service_days integer     NOT NULL,
    approval_route       text        NOT NULL,
    payroll_effect       text        NOT NULL,
    effective_from       date        NOT NULL,
    effective_to         date,
    created_at           timestamptz NOT NULL,
    created_by           text        NOT NULL,
    CONSTRAINT leave_policy_version_policy FOREIGN KEY (tenant_id, policy_id)
        REFERENCES people.leave_policy (tenant_id, id),
    CONSTRAINT leave_policy_version_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT leave_policy_version_number_unique UNIQUE (tenant_id, policy_id, version_number),
    -- MVP-040A writes version 1 only; a later version needs a new migration.
    CONSTRAINT leave_policy_version_v1 CHECK (version_number = 1),
    CONSTRAINT leave_policy_version_name_en_valid CHECK (people.leave_policy_name_valid(name_en)),
    CONSTRAINT leave_policy_version_name_fr_valid CHECK (people.leave_policy_name_valid(name_fr)),
    CONSTRAINT leave_policy_version_unit_valid CHECK (unit IN ('DAYS', 'HOURS')),
    CONSTRAINT leave_policy_version_balance_mode_valid
        CHECK (balance_mode IN ('TRACKED', 'UNTRACKED')),
    -- Tracked: an entitlement above zero, at most 10000.00, two decimals at most. Untracked: none.
    CONSTRAINT leave_policy_version_entitlement_valid CHECK (
        (balance_mode = 'TRACKED' AND annual_entitlement IS NOT NULL
            AND annual_entitlement > 0 AND annual_entitlement <= 10000
            AND annual_entitlement = trunc(annual_entitlement, 2))
        OR (balance_mode = 'UNTRACKED' AND annual_entitlement IS NULL)),
    CONSTRAINT leave_policy_version_service_days_range
        CHECK (minimum_service_days BETWEEN 0 AND 3650),
    CONSTRAINT leave_policy_version_approval_route_valid
        CHECK (approval_route IN ('MANAGER', 'TENANT_ADMIN')),
    CONSTRAINT leave_policy_version_payroll_effect_valid
        CHECK (payroll_effect IN ('PAID', 'UNPAID')),
    CONSTRAINT leave_policy_version_period_valid CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL
            OR (effective_to >= effective_from AND effective_to <= DATE '2999-12-31'))),
    CONSTRAINT leave_policy_version_created_by_length
        CHECK (char_length(created_by) BETWEEN 1 AND 255),
    -- Versions of one policy never overlap (open end = unbounded).
    CONSTRAINT leave_policy_version_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, policy_id WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&)
);

COMMENT ON TABLE people.leave_policy_version IS
    'MVP-040A leave policy configuration (Confidential organization data). Insert-only; the '
    'values are the organization''s own, never statutory defaults.';

-- =============================================================================================
-- Insert-only, never truncated; a policy commits only with its version 1
-- =============================================================================================
CREATE TRIGGER leave_policy_immutable
    BEFORE UPDATE OR DELETE ON people.leave_policy
    FOR EACH ROW EXECUTE FUNCTION people.leave_policy_immutable('leave_policy_immutable');
CREATE TRIGGER leave_policy_version_immutable
    BEFORE UPDATE OR DELETE ON people.leave_policy_version
    FOR EACH ROW EXECUTE FUNCTION people.leave_policy_immutable('leave_policy_version_immutable');
CREATE TRIGGER leave_policy_no_truncate
    BEFORE TRUNCATE ON people.leave_policy
    FOR EACH STATEMENT EXECUTE FUNCTION people.leave_policy_no_truncate();
CREATE TRIGGER leave_policy_version_no_truncate
    BEFORE TRUNCATE ON people.leave_policy_version
    FOR EACH STATEMENT EXECUTE FUNCTION people.leave_policy_no_truncate();

CREATE FUNCTION people.leave_policy_has_version() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM people.leave_policy_version v
        WHERE v.tenant_id = NEW.tenant_id AND v.policy_id = NEW.id AND v.version_number = 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_policy_has_version',
            MESSAGE = 'a leave policy commits with its version 1';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER leave_policy_has_version
    AFTER INSERT ON people.leave_policy
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION people.leave_policy_has_version();
