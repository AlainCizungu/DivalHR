-- MVP-120A (Issue #204): consented salary-advance requests (decisions D120A-1 to D120A-16, ADR 0041).
-- Additive to V51. DivalHR is workflow software only: it is not the lender, borrower, guarantor, credit bureau or
-- payment provider. This migration creates NO loan, credit decision, price, partner submission, disbursement,
-- repayment or payroll deduction, and contains no real partner, institution, endpoint or credential. The only
-- partner code the schema accepts is the synthetic development partner SYNTHETIC-TEST (D120A-2).
--
-- Ownership: the payroll module; only the bounded com.divalhr.core.payroll package reads or writes these objects.
-- The keys to tenant.legal_entity and people.employee are the documented modular-monolith integrity exception
-- (constraints, never reads), like V43 to V51. The guards below read payroll.payroll_package, payroll.payroll_payslip,
-- payroll.statutory_pack_governance (same module) and people.employment, people.employment_assignment and
-- identity.employee_access_link/tenant_membership only to PROVE the frozen evidence the application recorded; that
-- is the same documented exception, never an application read path.
--
-- Classification: Restricted financial and HR data. Requested amounts, reference net, ceilings, employment dates,
-- consent details and employer notes never leave this schema except in authorized API responses (the employer note
-- never leaves it at all); audit metadata, outbox payloads, idempotency records, cursors, metrics, logs and error
-- responses never carry them (D120A-12). Amounts follow the payroll convention: integer minor units, access
-- restricted to the payroll module, not separately encrypted (like payroll_payslip).
--
-- Shape:
--   * salary_advance_policy: append-only, effective-dated employer participation policy of one legal entity and
--     currency. The only update ever allowed is the one-time cancellation of a version that has not taken effect
--     (cancelled_at/cancelled_by); nothing else is edited or deleted. Non-overlapping periods (partial exclusion
--     constraint over non-cancelled versions), at most 25 versions per (entity, currency), no interest, fee,
--     repayment term or credit criterion column exists.
--   * salary_advance_request: one header per submitted request with FROZEN policy, payroll and consent evidence.
--     The reference is the latest APPROVED, non-voided MVP-094C payslip net of the employee in that legal entity and
--     currency, produced under a non-LEGACY, non-DRAFT governed pack (R202-1). The database recomputes the
--     eligibility facts (tenure, ceiling, bounds, open limit, rolling 12-month limit, waiting period). Only the
--     status and row_version may change, only along the closed lifecycle, never to a state the schema does not
--     name: there is no partner-submission, approved, funded, disbursed, paid or repaid state.
--   * salary_advance_consent: one immutable consent per request (version, language, instant, canonical disclosure
--     SHA-256). No IP address, user agent or rendered disclosure text is stored.
--   * salary_advance_employer_decision: one immutable employer participation decision (CONFIRMED or REFUSED with one
--     closed reason code and an optional internal note of at most 500 characters).
--   * salary_advance_request_event: append-only lifecycle history; header status always equals the last event.
--
-- Privileges: like V43 to V51 this migration issues no GRANT or REVOKE; the authority is in the triggers below.
-- Seeds nothing.
--
-- Rollback: db/rollback/V52__rollback.sql (manual, never run by Flyway) refuses while any row exists in these
-- tables; it never deletes employee financial evidence silently.

CREATE FUNCTION payroll.salary_advance_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_immutable',
        MESSAGE = 'salary-advance evidence is never deleted, truncated or updated';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Policy (append-only, effective-dated; one-time cancellation before taking effect)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.salary_advance_policy (
    tenant_id             uuid        NOT NULL,
    legal_entity_id       uuid        NOT NULL,
    id                    uuid        NOT NULL,
    currency              text        NOT NULL,
    sequence              integer     NOT NULL,
    code                  text        NOT NULL,
    effective_from        date        NOT NULL,
    effective_to          date,
    period                daterange   GENERATED ALWAYS AS (daterange(effective_from, effective_to, '[]')) STORED,
    enabled               boolean     NOT NULL,
    min_tenure_months     smallint    NOT NULL,
    max_percent           smallint    NOT NULL,
    absolute_max_minor    bigint,
    min_request_minor     bigint      NOT NULL,
    max_open_requests     smallint    NOT NULL,
    waiting_days          smallint    NOT NULL,
    consent_version       text        NOT NULL,
    partner_code          text        NOT NULL,
    created_at            timestamptz NOT NULL,
    created_by            text        NOT NULL,
    cancelled_at          timestamptz,
    cancelled_by          text,
    CONSTRAINT payroll_salary_advance_policy_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_salary_advance_policy_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT payroll_salary_advance_policy_sequence_unique
        UNIQUE (tenant_id, legal_entity_id, currency, sequence),
    CONSTRAINT payroll_salary_advance_policy_legal_entity
        FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT payroll_salary_advance_policy_currency CHECK (currency IN ('CDF', 'USD')),
    CONSTRAINT payroll_salary_advance_policy_sequence_range CHECK (sequence BETWEEN 1 AND 25),
    CONSTRAINT payroll_salary_advance_policy_code CHECK (code ~ '^[A-Z][A-Z0-9-]{2,31}$'),
    CONSTRAINT payroll_salary_advance_policy_effective_range CHECK (
        effective_from BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL OR (effective_to >= effective_from AND effective_to <= DATE '2999-12-31'))),
    CONSTRAINT payroll_salary_advance_policy_tenure CHECK (min_tenure_months BETWEEN 0 AND 60),
    CONSTRAINT payroll_salary_advance_policy_percent CHECK (max_percent BETWEEN 1 AND 50),
    CONSTRAINT payroll_salary_advance_policy_amounts CHECK (
        (currency = 'CDF' AND min_request_minor BETWEEN 1 AND 1000000000
            AND (absolute_max_minor IS NULL
                 OR absolute_max_minor BETWEEN min_request_minor AND 1000000000))
        OR (currency = 'USD' AND min_request_minor BETWEEN 1 AND 10000000
            AND (absolute_max_minor IS NULL
                 OR absolute_max_minor BETWEEN min_request_minor AND 10000000))),
    CONSTRAINT payroll_salary_advance_policy_open_limit CHECK (max_open_requests BETWEEN 1 AND 3),
    CONSTRAINT payroll_salary_advance_policy_waiting CHECK (waiting_days BETWEEN 0 AND 90),
    CONSTRAINT payroll_salary_advance_policy_consent_version
        CHECK (consent_version ~ '^[A-Za-z0-9._-]{1,32}$'),
    CONSTRAINT payroll_salary_advance_policy_partner CHECK (partner_code = 'SYNTHETIC-TEST'),
    CONSTRAINT payroll_salary_advance_policy_created_by CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT payroll_salary_advance_policy_cancel_shape CHECK (
        (cancelled_at IS NULL) = (cancelled_by IS NULL)
        AND (cancelled_by IS NULL OR char_length(cancelled_by) BETWEEN 1 AND 255)),
    CONSTRAINT payroll_salary_advance_policy_period_overlap EXCLUDE USING gist (
        tenant_id WITH =, legal_entity_id WITH =, currency WITH =, period WITH &&)
        WHERE (cancelled_at IS NULL)
);

COMMENT ON TABLE payroll.salary_advance_policy IS
    'MVP-120A employer participation policy (append-only; Restricted). Not a credit product: no interest, fee, '
    'repayment term or credit criterion exists.';

CREATE FUNCTION payroll.salary_advance_policy_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    last_sequence integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- One version chain per (tenant, entity, currency); serialised so that the sequence has no gap or fork.
        PERFORM pg_advisory_xact_lock(
            hashtextextended('salary-advance-policy:' || NEW.tenant_id || ':' || NEW.legal_entity_id
                             || ':' || NEW.currency, 0));
        SELECT coalesce(max(p.sequence), 0) INTO last_sequence
          FROM payroll.salary_advance_policy p
         WHERE p.tenant_id = NEW.tenant_id AND p.legal_entity_id = NEW.legal_entity_id
           AND p.currency = NEW.currency;
        IF NEW.sequence <> last_sequence + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_policy_sequence',
                MESSAGE = 'the policy version must follow the last version';
        END IF;
        IF NEW.cancelled_at IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_policy_sequence',
                MESSAGE = 'a policy version is recorded active, never already cancelled';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: only the one-time cancellation of a version that has not taken effect (UTC bound; the application
    -- applies the exact tenant business date). No request may reference a cancelled version.
    IF OLD.cancelled_at IS NOT NULL
        OR NEW.cancelled_at IS NULL
        OR (to_jsonb(NEW) - 'cancelled_at' - 'cancelled_by' - 'period')
            IS DISTINCT FROM (to_jsonb(OLD) - 'cancelled_at' - 'cancelled_by' - 'period')
        OR NEW.effective_from <= (NEW.cancelled_at AT TIME ZONE 'UTC')::date - 1
        OR EXISTS (SELECT 1 FROM payroll.salary_advance_request r
                    WHERE r.tenant_id = OLD.tenant_id AND r.policy_id = OLD.id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_policy_immutable',
            MESSAGE = 'a policy version is only cancelled once, before it takes effect';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_policy_guard BEFORE INSERT OR UPDATE ON payroll.salary_advance_policy
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_policy_guard();
CREATE TRIGGER payroll_salary_advance_policy_no_delete BEFORE DELETE ON payroll.salary_advance_policy
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_policy_no_truncate BEFORE TRUNCATE ON payroll.salary_advance_policy
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();

CREATE INDEX payroll_salary_advance_policy_timeline
    ON payroll.salary_advance_policy (tenant_id, legal_entity_id, currency, effective_from DESC, sequence DESC);

-- ---------------------------------------------------------------------------------------------
-- Request header (frozen evidence; status and row_version are the only mutable columns)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.salary_advance_request (
    tenant_id              uuid        NOT NULL,
    id                     uuid        NOT NULL,
    employee_id            uuid        NOT NULL,
    legal_entity_id        uuid        NOT NULL,
    currency               text        NOT NULL,
    status                 text        NOT NULL,
    row_version            integer     NOT NULL,
    policy_id              uuid        NOT NULL,
    policy_sequence        integer     NOT NULL,
    partner_code           text        NOT NULL,
    package_id             uuid        NOT NULL,
    payslip_id             uuid        NOT NULL,
    reference_period_start date        NOT NULL,
    reference_net_minor    bigint      NOT NULL,
    ceiling_minor          bigint      NOT NULL,
    requested_minor        bigint      NOT NULL,
    employment_start       date        NOT NULL,
    tenure_months          smallint    NOT NULL,
    requested_on           date        NOT NULL,
    submitted_at           timestamptz NOT NULL,
    submitted_by           text        NOT NULL,
    CONSTRAINT payroll_salary_advance_request_pkey PRIMARY KEY (tenant_id, id),
    CONSTRAINT payroll_salary_advance_request_owner_unique UNIQUE (tenant_id, employee_id, id),
    CONSTRAINT payroll_salary_advance_request_employee
        FOREIGN KEY (tenant_id, employee_id) REFERENCES people.employee (tenant_id, id),
    CONSTRAINT payroll_salary_advance_request_policy FOREIGN KEY (tenant_id, legal_entity_id, policy_id)
        REFERENCES payroll.salary_advance_policy (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_salary_advance_request_payslip FOREIGN KEY (tenant_id, package_id, payslip_id)
        REFERENCES payroll.payroll_payslip (tenant_id, package_id, id),
    CONSTRAINT payroll_salary_advance_request_status
        CHECK (status IN ('SUBMITTED', 'EMPLOYER_CONFIRMED', 'EMPLOYER_REFUSED', 'WITHDRAWN')),
    CONSTRAINT payroll_salary_advance_request_currency CHECK (currency IN ('CDF', 'USD')),
    CONSTRAINT payroll_salary_advance_request_version CHECK (row_version BETWEEN 1 AND 3),
    CONSTRAINT payroll_salary_advance_request_partner CHECK (partner_code = 'SYNTHETIC-TEST'),
    CONSTRAINT payroll_salary_advance_request_amounts CHECK (
        reference_net_minor BETWEEN 1 AND 1000000000
        AND ceiling_minor BETWEEN 1 AND reference_net_minor
        AND requested_minor BETWEEN 1 AND ceiling_minor),
    CONSTRAINT payroll_salary_advance_request_tenure CHECK (tenure_months BETWEEN 0 AND 1200),
    CONSTRAINT payroll_salary_advance_request_dates CHECK (
        employment_start <= requested_on
        AND requested_on BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
        AND reference_period_start = date_trunc('month', reference_period_start)::date),
    CONSTRAINT payroll_salary_advance_request_submitted_by CHECK (char_length(submitted_by) BETWEEN 1 AND 255)
);

COMMENT ON TABLE payroll.salary_advance_request IS
    'MVP-120A employee salary-advance request to the EMPLOYER (Restricted). Employer participation only: no credit '
    'decision, partner handoff, funding or repayment exists in this schema.';

CREATE INDEX payroll_salary_advance_request_tenant_page
    ON payroll.salary_advance_request (tenant_id, submitted_at DESC, id DESC);
CREATE INDEX payroll_salary_advance_request_employee_page
    ON payroll.salary_advance_request (tenant_id, employee_id, submitted_at DESC, id DESC);
CREATE INDEX payroll_salary_advance_request_open
    ON payroll.salary_advance_request (tenant_id, employee_id)
    WHERE status IN ('SUBMITTED', 'EMPLOYER_CONFIRMED');

-- Consent, decision and history -----------------------------------------------------------------

CREATE TABLE payroll.salary_advance_consent (
    tenant_id         uuid        NOT NULL,
    request_id        uuid        NOT NULL,
    consent_version   text        NOT NULL,
    language          text        NOT NULL,
    consented_at      timestamptz NOT NULL,
    disclosure_sha256 char(64)    NOT NULL,
    affirmed          boolean     NOT NULL,
    CONSTRAINT payroll_salary_advance_consent_pkey PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT payroll_salary_advance_consent_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES payroll.salary_advance_request (tenant_id, id),
    CONSTRAINT payroll_salary_advance_consent_version CHECK (consent_version ~ '^[A-Za-z0-9._-]{1,32}$'),
    CONSTRAINT payroll_salary_advance_consent_language CHECK (language IN ('en', 'fr')),
    CONSTRAINT payroll_salary_advance_consent_digest CHECK (disclosure_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_salary_advance_consent_affirmed CHECK (affirmed)
);

CREATE TABLE payroll.salary_advance_employer_decision (
    tenant_id     uuid        NOT NULL,
    request_id    uuid        NOT NULL,
    decision      text        NOT NULL,
    reason_code   text,
    internal_note text,
    decided_by    text        NOT NULL,
    decided_at    timestamptz NOT NULL,
    mfa_verified  boolean     NOT NULL,
    confirmed     boolean     NOT NULL,
    CONSTRAINT payroll_salary_advance_decision_pkey PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT payroll_salary_advance_decision_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES payroll.salary_advance_request (tenant_id, id),
    CONSTRAINT payroll_salary_advance_decision_value CHECK (decision IN ('CONFIRMED', 'REFUSED')),
    CONSTRAINT payroll_salary_advance_decision_reason CHECK (
        (decision = 'CONFIRMED' AND reason_code IS NULL)
        OR (decision = 'REFUSED' AND reason_code IS NOT NULL AND reason_code IN (
            'POLICY_NO_LONGER_APPLIES', 'EMPLOYMENT_ENDING', 'PAYROLL_REFERENCE_INVALID',
            'OPEN_REQUEST_LIMIT', 'EMPLOYER_NOT_PARTICIPATING', 'OTHER_POLICY_REASON'))),
    CONSTRAINT payroll_salary_advance_decision_note CHECK (
        internal_note IS NULL
        OR (char_length(internal_note) BETWEEN 1 AND 500 AND internal_note !~ '[[:cntrl:]]')),
    CONSTRAINT payroll_salary_advance_decision_by CHECK (char_length(decided_by) BETWEEN 1 AND 255),
    CONSTRAINT payroll_salary_advance_decision_evidence CHECK (mfa_verified AND confirmed)
);

CREATE TABLE payroll.salary_advance_request_event (
    tenant_id     uuid        NOT NULL,
    request_id    uuid        NOT NULL,
    sequence      integer     NOT NULL,
    from_status   text,
    to_status     text        NOT NULL,
    actor_role    text        NOT NULL,
    actor_subject text        NOT NULL,
    occurred_at   timestamptz NOT NULL,
    occurred_on   date        NOT NULL,
    reason_code   text,
    CONSTRAINT payroll_salary_advance_event_pkey PRIMARY KEY (tenant_id, request_id, sequence),
    CONSTRAINT payroll_salary_advance_event_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES payroll.salary_advance_request (tenant_id, id),
    CONSTRAINT payroll_salary_advance_event_sequence CHECK (sequence BETWEEN 1 AND 3),
    CONSTRAINT payroll_salary_advance_event_status CHECK (
        to_status IN ('SUBMITTED', 'EMPLOYER_CONFIRMED', 'EMPLOYER_REFUSED', 'WITHDRAWN')
        AND (from_status IS NULL
             OR from_status IN ('SUBMITTED', 'EMPLOYER_CONFIRMED', 'EMPLOYER_REFUSED', 'WITHDRAWN'))),
    CONSTRAINT payroll_salary_advance_event_transition CHECK (
        (from_status IS NULL AND to_status = 'SUBMITTED' AND actor_role = 'EMPLOYEE')
        OR (from_status = 'SUBMITTED' AND to_status IN ('EMPLOYER_CONFIRMED', 'EMPLOYER_REFUSED')
            AND actor_role = 'TENANT_ADMIN')
        OR (from_status IN ('SUBMITTED', 'EMPLOYER_CONFIRMED') AND to_status = 'WITHDRAWN'
            AND actor_role = 'EMPLOYEE')),
    CONSTRAINT payroll_salary_advance_event_sequence_shape CHECK ((sequence = 1) = (from_status IS NULL)),
    CONSTRAINT payroll_salary_advance_event_reason CHECK (
        reason_code IS NULL OR reason_code IN (
            'POLICY_NO_LONGER_APPLIES', 'EMPLOYMENT_ENDING', 'PAYROLL_REFERENCE_INVALID',
            'OPEN_REQUEST_LIMIT', 'EMPLOYER_NOT_PARTICIPATING', 'OTHER_POLICY_REASON')),
    CONSTRAINT payroll_salary_advance_event_actor CHECK (char_length(actor_subject) BETWEEN 1 AND 255)
);

CREATE INDEX payroll_salary_advance_event_waiting
    ON payroll.salary_advance_request_event (tenant_id, to_status, occurred_on DESC)
    WHERE to_status IN ('WITHDRAWN', 'EMPLOYER_REFUSED');

CREATE TRIGGER payroll_salary_advance_consent_immutable BEFORE UPDATE OR DELETE ON payroll.salary_advance_consent
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_consent_no_truncate BEFORE TRUNCATE ON payroll.salary_advance_consent
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_decision_immutable
    BEFORE UPDATE OR DELETE ON payroll.salary_advance_employer_decision
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_decision_no_truncate
    BEFORE TRUNCATE ON payroll.salary_advance_employer_decision
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_event_immutable BEFORE UPDATE OR DELETE ON payroll.salary_advance_request_event
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_event_no_truncate BEFORE TRUNCATE ON payroll.salary_advance_request_event
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_request_no_delete BEFORE DELETE ON payroll.salary_advance_request
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_immutable();
CREATE TRIGGER payroll_salary_advance_request_no_truncate BEFORE TRUNCATE ON payroll.salary_advance_request
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.salary_advance_immutable();

-- A decision is recorded only for a SUBMITTED request (before the header moves, in the same transaction).
CREATE FUNCTION payroll.salary_advance_decision_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    current_status text;
    requester      text;
BEGIN
    SELECT r.status, r.submitted_by INTO current_status, requester FROM payroll.salary_advance_request r
     WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.request_id FOR UPDATE;
    IF current_status IS DISTINCT FROM 'SUBMITTED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_decision_state',
            MESSAGE = 'an employer decision applies to a SUBMITTED request only';
    END IF;
    -- R205-2: the reviewer is never the immutable submitting subject.
    IF NEW.decided_by = requester THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_decision_self_review',
            MESSAGE = 'a request is never decided by the subject who submitted it';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_decision_guard BEFORE INSERT ON payroll.salary_advance_employer_decision
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_decision_guard();

-- Event guard: the legal chain, the actor and the order. The header row is locked first so that two commands on one
-- request are serialised and exactly one wins.
CREATE FUNCTION payroll.salary_advance_event_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    last_sequence integer;
    last_status   text;
    last_at       timestamptz;
BEGIN
    PERFORM 1 FROM payroll.salary_advance_request r
     WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.request_id FOR UPDATE;
    SELECT e.sequence, e.to_status, e.occurred_at INTO last_sequence, last_status, last_at
      FROM payroll.salary_advance_request_event e
     WHERE e.tenant_id = NEW.tenant_id AND e.request_id = NEW.request_id
     ORDER BY e.sequence DESC LIMIT 1;
    IF NEW.sequence <> coalesce(last_sequence, 0) + 1
        OR NEW.from_status IS DISTINCT FROM last_status
        OR (last_at IS NOT NULL AND NEW.occurred_at < last_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_event_transition',
            MESSAGE = 'the lifecycle event does not follow the request history';
    END IF;
    IF NEW.to_status IN ('EMPLOYER_CONFIRMED', 'EMPLOYER_REFUSED') AND NOT EXISTS (
            SELECT 1 FROM payroll.salary_advance_employer_decision d
             WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.request_id
               AND d.decision = CASE NEW.to_status WHEN 'EMPLOYER_CONFIRMED' THEN 'CONFIRMED' ELSE 'REFUSED' END
               AND d.decided_by = NEW.actor_subject
               AND d.reason_code IS NOT DISTINCT FROM NEW.reason_code) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_event_transition',
            MESSAGE = 'an employer outcome needs its recorded decision';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_event_guard BEFORE INSERT ON payroll.salary_advance_request_event
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_event_guard();

-- Header guard: INSERT proves every frozen fact against the source rows; UPDATE moves status and row_version only.
CREATE FUNCTION payroll.salary_advance_request_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    pol           payroll.salary_advance_policy%ROWTYPE;
    pkg           payroll.payroll_package%ROWTYPE;
    slip          payroll.payroll_payslip%ROWTYPE;
    start_date    date;
    months        integer;
    computed_cap  bigint;
    open_count    integer;
    recent_count  integer;
    last_blocked  date;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (to_jsonb(NEW) - 'status' - 'row_version') IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'row_version')
            OR NEW.row_version <> OLD.row_version + 1
            OR NOT ((OLD.status = 'SUBMITTED' AND NEW.status IN ('EMPLOYER_CONFIRMED', 'EMPLOYER_REFUSED', 'WITHDRAWN'))
                    OR (OLD.status = 'EMPLOYER_CONFIRMED' AND NEW.status = 'WITHDRAWN')) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_transition',
                MESSAGE = 'a salary-advance request only follows the closed lifecycle';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status <> 'SUBMITTED' OR NEW.row_version <> 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_transition',
            MESSAGE = 'a salary-advance request is recorded SUBMITTED at version 1';
    END IF;

    -- Serialise the employee's limits.
    PERFORM pg_advisory_xact_lock(
        hashtextextended('salary-advance-employee:' || NEW.tenant_id || ':' || NEW.employee_id, 0));

    -- The submitting subject is the employee's own active linked membership.
    IF NOT EXISTS (
            SELECT 1 FROM identity.employee_access_link l
              JOIN identity.tenant_membership m ON m.tenant_id = l.tenant_id AND m.id = l.membership_id
             WHERE l.tenant_id = NEW.tenant_id AND l.employee_id = NEW.employee_id
               AND l.unlinked_at IS NULL AND m.subject = NEW.submitted_by) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_owner',
            MESSAGE = 'a request is submitted by the employee''s own linked subject';
    END IF;

    -- The policy: this tenant, entity and currency, enabled, not cancelled, in force on the request date.
    SELECT * INTO pol FROM payroll.salary_advance_policy p
     WHERE p.tenant_id = NEW.tenant_id AND p.legal_entity_id = NEW.legal_entity_id AND p.id = NEW.policy_id;
    IF NOT FOUND OR pol.currency <> NEW.currency OR pol.sequence <> NEW.policy_sequence
        OR pol.partner_code <> NEW.partner_code OR NOT pol.enabled OR pol.cancelled_at IS NOT NULL
        OR NOT pol.period @> NEW.requested_on THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_policy',
            MESSAGE = 'the request names a policy that does not apply';
    END IF;

    -- The payroll reference: the employee's own payslip of an APPROVED, non-voided package of this legal entity and
    -- currency, produced under a governed pack that is neither DRAFT nor LEGACY (R202-1), and the latest such.
    SELECT * INTO slip FROM payroll.payroll_payslip s
     WHERE s.tenant_id = NEW.tenant_id AND s.package_id = NEW.package_id AND s.id = NEW.payslip_id;
    SELECT * INTO pkg FROM payroll.payroll_package k
     WHERE k.tenant_id = NEW.tenant_id AND k.legal_entity_id = NEW.legal_entity_id AND k.id = NEW.package_id;
    IF slip.id IS NULL OR pkg.id IS NULL OR slip.employee_id <> NEW.employee_id OR slip.excluded
        OR slip.legal_entity_id <> NEW.legal_entity_id
        OR pkg.status <> 'APPROVED' OR pkg.voided_at IS NOT NULL OR pkg.currency <> NEW.currency
        OR slip.period_start <> NEW.reference_period_start OR slip.net_minor <> NEW.reference_net_minor
        OR slip.net_minor < 1
        OR NOT EXISTS (SELECT 1 FROM payroll.statutory_pack_governance g
                        WHERE g.pack_id = pkg.pack_id AND g.state IN ('ACTIVE', 'RETIRED'))
        OR EXISTS (
            SELECT 1 FROM payroll.payroll_payslip s2
              JOIN payroll.payroll_package k2 ON k2.tenant_id = s2.tenant_id
                   AND k2.legal_entity_id = s2.legal_entity_id AND k2.id = s2.package_id
             WHERE s2.tenant_id = NEW.tenant_id AND s2.employee_id = NEW.employee_id
               AND s2.legal_entity_id = NEW.legal_entity_id AND k2.currency = NEW.currency
               AND k2.status = 'APPROVED' AND k2.voided_at IS NULL AND NOT s2.excluded
               AND s2.period_start > NEW.reference_period_start) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_reference',
            MESSAGE = 'the request is not backed by the latest approved, operational payroll reference';
    END IF;

    -- Employment: active on the request date, placed in the policy legal entity; tenure in completed months.
    SELECT e.effective_from INTO start_date FROM people.employment e
     WHERE e.tenant_id = NEW.tenant_id AND e.employee_id = NEW.employee_id
       AND e.effective_from <= NEW.requested_on
       AND (e.effective_to IS NULL OR e.effective_to >= NEW.requested_on)
     ORDER BY e.effective_from DESC LIMIT 1;
    IF start_date IS NULL OR start_date <> NEW.employment_start OR NOT EXISTS (
            SELECT 1 FROM people.employment_assignment a
             WHERE a.tenant_id = NEW.tenant_id AND a.employee_id = NEW.employee_id AND a.kind = 'PLACEMENT'
               AND a.legal_entity_id = NEW.legal_entity_id AND a.superseded_by_change_id IS NULL
               AND a.period @> NEW.requested_on) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_employment',
            MESSAGE = 'the employee is not actively employed in the policy legal entity';
    END IF;
    -- R205-1: several legal-entity placements on the date are ambiguous; none is chosen, the request fails closed.
    IF (SELECT count(*) FROM people.employment_assignment a
         WHERE a.tenant_id = NEW.tenant_id AND a.employee_id = NEW.employee_id AND a.kind = 'PLACEMENT'
           AND a.legal_entity_id IS NOT NULL AND a.superseded_by_change_id IS NULL
           AND a.period @> NEW.requested_on) > 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_placement',
            MESSAGE = 'more than one active placement covers the request date';
    END IF;
    months := (extract(year FROM age(NEW.requested_on, start_date)) * 12
               + extract(month FROM age(NEW.requested_on, start_date)))::integer;
    IF months <> NEW.tenure_months OR months < pol.min_tenure_months THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_tenure',
            MESSAGE = 'the completed employment months do not meet the policy';
    END IF;

    -- Ceiling: whole minor units, rounded DOWN, never a float; then the bounds.
    computed_cap := (slip.net_minor * pol.max_percent) / 100;
    IF pol.absolute_max_minor IS NOT NULL THEN
        computed_cap := least(computed_cap, pol.absolute_max_minor);
    END IF;
    IF computed_cap <> NEW.ceiling_minor OR NEW.requested_minor < pol.min_request_minor
        OR NEW.requested_minor > computed_cap THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_amount',
            MESSAGE = 'the amount is outside the employer policy bounds';
    END IF;

    -- Limits: open requests, 10 per rolling 12 months, and the waiting period after a withdrawal or refusal.
    SELECT count(*) INTO open_count FROM payroll.salary_advance_request r
     WHERE r.tenant_id = NEW.tenant_id AND r.employee_id = NEW.employee_id
       AND r.status IN ('SUBMITTED', 'EMPLOYER_CONFIRMED');
    IF open_count >= least(3, pol.max_open_requests) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_open_limit',
            MESSAGE = 'the open request limit is reached';
    END IF;
    SELECT count(*) INTO recent_count FROM payroll.salary_advance_request r
     WHERE r.tenant_id = NEW.tenant_id AND r.employee_id = NEW.employee_id
       AND r.submitted_at > NEW.submitted_at - interval '12 months';
    IF recent_count >= 10 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_rolling_limit',
            MESSAGE = 'the rolling twelve-month request limit is reached';
    END IF;
    SELECT max(ev.occurred_on) INTO last_blocked
      FROM payroll.salary_advance_request_event ev
      JOIN payroll.salary_advance_request r ON r.tenant_id = ev.tenant_id AND r.id = ev.request_id
     WHERE r.tenant_id = NEW.tenant_id AND r.employee_id = NEW.employee_id
       AND r.legal_entity_id = NEW.legal_entity_id AND r.currency = NEW.currency
       AND ev.to_status IN ('WITHDRAWN', 'EMPLOYER_REFUSED');
    IF last_blocked IS NOT NULL AND NEW.requested_on < last_blocked + pol.waiting_days THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_waiting',
            MESSAGE = 'the waiting period has not ended';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_salary_advance_request_guard BEFORE INSERT OR UPDATE ON payroll.salary_advance_request
    FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_request_guard();

-- Deferred completeness: at commit every request has its consent, a first SUBMITTED event, and a last event equal to
-- the header status and version (the lifecycle is one history, never a free status edit). SUBMITTED needs the consent
-- in the same version-1 transaction; the consent version equals the policy's current consent document.
CREATE FUNCTION payroll.salary_advance_request_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM payroll.salary_advance_consent c
              JOIN payroll.salary_advance_policy p ON p.tenant_id = c.tenant_id AND p.id = NEW.policy_id
             WHERE c.tenant_id = NEW.tenant_id AND c.request_id = NEW.id
               AND c.consent_version = p.consent_version AND c.consented_at <= NEW.submitted_at)
        OR NOT EXISTS (
            SELECT 1 FROM payroll.salary_advance_request_event e
             WHERE e.tenant_id = NEW.tenant_id AND e.request_id = NEW.id AND e.sequence = 1
               AND e.to_status = 'SUBMITTED' AND e.actor_subject = NEW.submitted_by)
        OR NOT EXISTS (
            SELECT 1 FROM payroll.salary_advance_request_event e
             WHERE e.tenant_id = NEW.tenant_id AND e.request_id = NEW.id
               AND e.sequence = CASE NEW.status WHEN 'SUBMITTED' THEN 1 ELSE NEW.row_version END
               AND e.to_status = NEW.status)
        OR (NEW.status IN ('EMPLOYER_CONFIRMED', 'EMPLOYER_REFUSED') AND NOT EXISTS (
                SELECT 1 FROM payroll.salary_advance_employer_decision d
                 WHERE d.tenant_id = NEW.tenant_id AND d.request_id = NEW.id)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_salary_advance_request_incomplete',
            MESSAGE = 'a salary-advance request needs its consent, history and decision evidence';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_salary_advance_request_complete
    AFTER INSERT OR UPDATE ON payroll.salary_advance_request
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.salary_advance_request_complete();
