-- MVP-041A (Issue #87): employees submit leave requests and see their own. This slice creates
-- pending requests only: no approval, rejection, cancellation, balance, working-day, holiday or
-- payroll calculation.
--
-- Ownership: the people module owns the table; only the bounded com.divalhr.core.people.leave
-- package reads or writes it. Its keys reference people's own employment, employee and leave
-- policy version rows and the tenant root; there is no cross-module reference.
--
-- Classification: Restricted HR (an employee's absence plan). Dates and amount are personal data
-- and never enter audit metadata, logs or errors.
--
-- Database authority (V19): a request is inserted PENDING and never changes; it is never updated,
-- deleted or truncated. Pending requests of one employee never overlap (a btree_gist exclusion,
-- enforced under concurrency by PostgreSQL). MVP-041B may replace the insert-only state rule with
-- a separately reviewed transition and history model.
--
-- Rollback: db/rollback/V19__rollback.sql (manual, never run by Flyway). It refuses once a
-- request exists, with no override, and never touches flyway_schema_history.

CREATE FUNCTION people.leave_request_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_immutable',
        MESSAGE = 'leave requests are never changed or deleted in V19';
END
$fn$;

CREATE FUNCTION people.leave_request_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'leave_request_no_truncate',
        MESSAGE = 'leave requests are never truncated';
END
$fn$;

CREATE TABLE people.leave_request (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    employee_id       uuid        NOT NULL,
    employment_id     uuid        NOT NULL,
    policy_version_id uuid        NOT NULL,
    start_date        date        NOT NULL,
    end_date          date        NOT NULL,
    requested_amount  numeric     NOT NULL,
    state             text        NOT NULL,
    submitted_at      timestamptz NOT NULL,
    submitted_by      text        NOT NULL,
    CONSTRAINT leave_request_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT leave_request_tenant_id_unique UNIQUE (tenant_id, id),
    -- The employment belongs to the employee, in the same tenant.
    CONSTRAINT leave_request_employment FOREIGN KEY (tenant_id, employment_id, employee_id)
        REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT leave_request_policy_version FOREIGN KEY (tenant_id, policy_version_id)
        REFERENCES people.leave_policy_version (tenant_id, id),
    CONSTRAINT leave_request_period_valid CHECK (
        start_date BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND end_date BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND end_date >= start_date),
    CONSTRAINT leave_request_amount_valid CHECK (
        requested_amount > 0 AND requested_amount <= 10000
        AND requested_amount = trunc(requested_amount, 2)),
    -- V19: PENDING only. MVP-041B reviews any other state separately.
    CONSTRAINT leave_request_state_valid CHECK (state = 'PENDING'),
    CONSTRAINT leave_request_submitted_by_length
        CHECK (char_length(submitted_by) BETWEEN 1 AND 255),
    -- Pending requests of one employee never overlap (both ends inclusive).
    CONSTRAINT leave_request_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, employee_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&) WHERE (state = 'PENDING')
);

COMMENT ON TABLE people.leave_request IS
    'MVP-041A leave request (Restricted HR). Inserted PENDING; insert-only in V19.';

-- The employee's own history, newest first.
CREATE INDEX leave_request_self_order
    ON people.leave_request (tenant_id, employee_id, submitted_at DESC, id DESC);

CREATE TRIGGER leave_request_immutable
    BEFORE UPDATE OR DELETE ON people.leave_request
    FOR EACH ROW EXECUTE FUNCTION people.leave_request_immutable();
CREATE TRIGGER leave_request_no_truncate
    BEFORE TRUNCATE ON people.leave_request
    FOR EACH STATEMENT EXECUTE FUNCTION people.leave_request_no_truncate();
