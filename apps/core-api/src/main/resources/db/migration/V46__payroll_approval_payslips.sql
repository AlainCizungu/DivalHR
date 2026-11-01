-- MVP-094C (Issue #181): a tenant administrator approves a frozen MVP-094A payroll run together with its
-- current MVP-094B statutory calculation as an immutable payroll PACKAGE, and publishes one immutable
-- canonical payslip snapshot per employee; an approved package may be VOIDED (decisions D94C-1 to D94C-16,
-- ADR 0035). Additive to V45; the fifth increment of the payroll module and the last of the release train.
-- Approval is not a payment, a filing, an accounting entry or a claim of legal compliance; a payslip is not
-- proof of payment.
--
-- Ownership: the payroll module; only the bounded com.divalhr.core.payroll package reads or writes these
-- objects. The keys to tenant.legal_entity and people.employee are the documented modular-monolith
-- integrity exception (constraints, never reads), like V37, V43, V44, V45. The package refers to
-- payroll.pay_run, payroll.pay_run_line, payroll.run_policy, payroll.statutory_calculation,
-- payroll.statutory_calculation_line, payroll.statutory_calculation_component and
-- payroll.statutory_rule_pack (same module) by composite foreign keys.
--
-- Classification: Restricted financial and HR data. Amounts, employee numbers, snapshots, digests, void
-- explanations and the approver subject never leave this schema except in authorized API responses
-- (the approver subject and void explanation never leave it at all); audit metadata, outbox payloads,
-- idempotency records, cursors, metrics, logs and error responses never carry them (D94C-12).
--
-- Shape:
--   * payroll_package: one approval of (legal entity, closed month, CDF) joining exactly one frozen
--     pay_run version and one statutory_calculation version, with the verified rule pack, the policy, the
--     aggregate totals, the approver subject (restricted), the three acknowledgements (all true), the
--     same-preparer fact (checked against pay_run.created_by and statutory_calculation.created_by) and the
--     approval digest (RECOMPUTED by the database from the package and its payslip digests). Lifecycle
--     APPROVED -> VOIDED once, never back; no other column ever changes. Chain version per
--     (tenant, entity, month, currency), at most 25. At most one APPROVED package per chain, per run and
--     per calculation (partial unique indexes). A (run, calculation) pair that was ever in a VOIDED package
--     can never be approved again.
--   * payroll_package_source: append-only binding evidence of the two sources (RUN, CALCULATION) with their
--     version and digest. A source with a binding row whose package is APPROVED is LOCKED: the supersession
--     of the pay_run or statutory_calculation row is refused by the triggers payroll_pay_run_package_lock and
--     payroll_statutory_calculation_package_lock (SQLSTATE 23514, constraint
--     payroll_package_source_locked). A voided package releases the lock; nothing is edited.
--   * payroll_package_void: append-only void evidence (closed reason code, explanation only for
--     OTHER_CONTROLLED, no-payment acknowledgement, instant, subject). One per package.
--   * payroll_payslip: one append-only canonical snapshot per included employee (the canonical JSON text is
--     authoritative; snapshot is generated from it; snapshot_digest is GENERATED as the SHA-256 of the text).
--     The database checks every snapshot field against the columns, against the statutory calculation line
--     and its components, and the package totals against the payslip rows (deferred).
--
-- Triggers refuse (SQLSTATE 23514, constraint names prefixed payroll_package_ / payroll_payslip_): a package
-- whose run or calculation is not the current DRAFT of its chain or differs from the frozen digests, version,
-- period or tenant/entity; a non-CDF currency; a pack that is not VERIFIED; a wrong chain version; a wrong
-- same-preparer fact; a package without exactly the two source rows, the exact payslip set, matching totals
-- or the recomputed approval digest (deferred); any update of a package other than APPROVED -> VOIDED with
-- its void evidence; any update or delete of a snapshot, void or source row; any truncate.
--
-- Indexes (queries of ADR 0035 sections 6 to 8):
--   * package list per entity, newest period first, then version: payroll_package_history;
--   * the APPROVED package of a chain: payroll_package_one_approved (also the uniqueness rule);
--   * source lock look-ups and the voided-pair check: payroll_package_source_lookup, payroll_package_pair;
--   * employee payslip list newest first: payroll_payslip_employee_page (tenant, employee, approved_at DESC,
--     id DESC); package payslip list by employee number in byte order: payroll_payslip_package_page.
--
-- Privileges: like V43 to V45 this migration issues no GRANT or REVOKE (the application role owns the schema
-- objects); the authority is in the triggers below. Seeds nothing.
--
-- Rollback: db/rollback/V46__rollback.sql (manual, never run by Flyway) restores V45 exactly only while no
-- package, source, void or payslip row exists; no override, no retention decision.

CREATE FUNCTION payroll.payroll_package_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_immutable',
        MESSAGE = 'payroll package evidence is never deleted, truncated or updated';
END
$fn$;

CREATE FUNCTION payroll.payroll_payslip_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payslip_immutable',
        MESSAGE = 'a payslip snapshot is never deleted, truncated or updated';
END
$fn$;

-- The digest of a snapshot: lower-case hex SHA-256 of the canonical UTF-8 text. IMMUTABLE so that it can
-- generate a stored column.
CREATE FUNCTION payroll.payroll_snapshot_digest(snapshot_text text) RETURNS char(64)
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$fn$
    SELECT encode(sha256(convert_to(snapshot_text, 'UTF8')), 'hex')::char(64)
$fn$;

CREATE FUNCTION payroll.payroll_snapshot_json(snapshot_text text) RETURNS jsonb
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$fn$
    SELECT snapshot_text::jsonb
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Package
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.payroll_package (
    tenant_id                          uuid        NOT NULL,
    legal_entity_id                    uuid        NOT NULL,
    id                                 uuid        NOT NULL,
    period_start                       date        NOT NULL,
    currency                           text        NOT NULL,
    version                            integer     NOT NULL,
    status                             text        NOT NULL,
    run_id                             uuid        NOT NULL,
    run_version                        integer     NOT NULL,
    run_digest                         char(64)    NOT NULL,
    calculation_id                     uuid        NOT NULL,
    calculation_version                integer     NOT NULL,
    calculation_digest                 char(64)    NOT NULL,
    calculation_source_run_digest      char(64)    NOT NULL,
    calculation_profile_digest         char(64)    NOT NULL,
    calculation_rule_pack_digest       char(64)    NOT NULL,
    pack_id                            uuid        NOT NULL,
    pack_code                          text        NOT NULL,
    pack_version                       integer     NOT NULL,
    rules_digest                       char(64)    NOT NULL,
    synthetic                          boolean     GENERATED ALWAYS AS (left(pack_code, 14) = 'SYNTHETIC-TEST') STORED,
    policy_id                          uuid        NOT NULL,
    policy_sequence                    integer     NOT NULL,
    payslip_count                      integer     NOT NULL,
    gross_total_minor                  bigint      NOT NULL,
    employee_deductions_total_minor    bigint      NOT NULL,
    employer_contributions_total_minor bigint      NOT NULL,
    net_total_minor                    bigint      NOT NULL,
    employer_cost_total_minor          bigint      NOT NULL,
    reviewed_totals                    boolean     NOT NULL,
    reviewed_exceptions                boolean     NOT NULL,
    understands_no_payment             boolean     NOT NULL,
    same_preparer                      boolean     NOT NULL,
    approval_digest                    char(64)    NOT NULL,
    approved_at                        timestamptz NOT NULL,
    approved_by_subject                text        NOT NULL,
    voided_at                          timestamptz,
    CONSTRAINT payroll_package_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_package_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT payroll_package_version_unique
        UNIQUE (tenant_id, legal_entity_id, period_start, currency, version),
    CONSTRAINT payroll_package_legal_entity
        FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT payroll_package_run FOREIGN KEY (tenant_id, legal_entity_id, run_id)
        REFERENCES payroll.pay_run (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_package_calculation FOREIGN KEY (tenant_id, legal_entity_id, calculation_id)
        REFERENCES payroll.statutory_calculation (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_package_pack FOREIGN KEY (pack_id) REFERENCES payroll.statutory_rule_pack (id),
    CONSTRAINT payroll_package_policy FOREIGN KEY (tenant_id, legal_entity_id, policy_id)
        REFERENCES payroll.run_policy (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_package_month CHECK (
        period_start = date_trunc('month', period_start)::date
        AND period_start BETWEEN DATE '2000-01-01' AND DATE '2999-12-01'),
    CONSTRAINT payroll_package_currency CHECK (currency = 'CDF'),
    CONSTRAINT payroll_package_version_range CHECK (version BETWEEN 1 AND 25),
    CONSTRAINT payroll_package_status CHECK (status IN ('APPROVED', 'VOIDED')),
    CONSTRAINT payroll_package_lifecycle CHECK (
        (status = 'APPROVED' AND voided_at IS NULL)
        OR (status = 'VOIDED' AND voided_at IS NOT NULL AND voided_at >= approved_at)),
    CONSTRAINT payroll_package_run_version_range CHECK (run_version BETWEEN 1 AND 25),
    CONSTRAINT payroll_package_calculation_version_range CHECK (calculation_version BETWEEN 1 AND 25),
    CONSTRAINT payroll_package_pack_code CHECK (pack_code ~ '^[A-Z][A-Z0-9-]{2,63}$'),
    CONSTRAINT payroll_package_pack_version_range CHECK (pack_version BETWEEN 1 AND 1000),
    CONSTRAINT payroll_package_policy_sequence_range CHECK (policy_sequence BETWEEN 1 AND 60),
    CONSTRAINT payroll_package_payslip_count CHECK (payslip_count BETWEEN 1 AND 5000),
    CONSTRAINT payroll_package_totals CHECK (
        gross_total_minor BETWEEN 0 AND 5000000000000
        AND employee_deductions_total_minor BETWEEN 0 AND gross_total_minor
        AND employer_contributions_total_minor >= 0),
    CONSTRAINT payroll_package_arithmetic CHECK (
        net_total_minor = gross_total_minor - employee_deductions_total_minor
        AND employer_cost_total_minor = gross_total_minor + employer_contributions_total_minor),
    CONSTRAINT payroll_package_acknowledgement CHECK (
        reviewed_totals AND reviewed_exceptions AND understands_no_payment),
    CONSTRAINT payroll_package_digests CHECK (
        run_digest ~ '^[0-9a-f]{64}$' AND calculation_digest ~ '^[0-9a-f]{64}$'
        AND calculation_source_run_digest ~ '^[0-9a-f]{64}$' AND calculation_profile_digest ~ '^[0-9a-f]{64}$'
        AND calculation_rule_pack_digest ~ '^[0-9a-f]{64}$' AND rules_digest ~ '^[0-9a-f]{64}$'
        AND approval_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_package_approved_by_length CHECK (char_length(approved_by_subject) BETWEEN 1 AND 255)
);

-- At most one APPROVED package per legal entity, month and currency; also per run and per calculation.
CREATE UNIQUE INDEX payroll_package_one_approved
    ON payroll.payroll_package (tenant_id, legal_entity_id, period_start, currency) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX payroll_package_one_approved_run
    ON payroll.payroll_package (tenant_id, run_id) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX payroll_package_one_approved_calculation
    ON payroll.payroll_package (tenant_id, calculation_id) WHERE status = 'APPROVED';
-- Package list of an entity: newest period first, then version descending (keyset: period_start, version, id).
CREATE INDEX payroll_package_history
    ON payroll.payroll_package (tenant_id, legal_entity_id, period_start DESC, version DESC, id);
-- The voided-pair check and the packages of a run or calculation; backs the foreign keys.
CREATE INDEX payroll_package_pair ON payroll.payroll_package (tenant_id, run_id, calculation_id);
CREATE INDEX payroll_package_calculation_fk ON payroll.payroll_package (tenant_id, legal_entity_id, calculation_id);
CREATE INDEX payroll_package_pack ON payroll.payroll_package (pack_id);
CREATE INDEX payroll_package_policy_fk ON payroll.payroll_package (tenant_id, legal_entity_id, policy_id);

-- ---------------------------------------------------------------------------------------------
-- Void evidence (append-only, one per package)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.payroll_package_void (
    tenant_id              uuid        NOT NULL,
    legal_entity_id        uuid        NOT NULL,
    package_id             uuid        NOT NULL,
    id                     uuid        NOT NULL,
    reason_code            text        NOT NULL,
    explanation            text,
    no_payment_acknowledged boolean    NOT NULL,
    approval_digest        char(64)    NOT NULL,
    voided_at              timestamptz NOT NULL,
    voided_by_subject      text        NOT NULL,
    CONSTRAINT payroll_package_void_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_package_void_package_unique UNIQUE (tenant_id, package_id),
    CONSTRAINT payroll_package_void_package FOREIGN KEY (tenant_id, legal_entity_id, package_id)
        REFERENCES payroll.payroll_package (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_package_void_reason CHECK (reason_code IN
        ('SOURCE_CORRECTION', 'PROFILE_CORRECTION', 'RULE_PACK_CORRECTION', 'ADMINISTRATIVE_ERROR',
         'OTHER_CONTROLLED')),
    CONSTRAINT payroll_package_void_explanation CHECK (
        (reason_code = 'OTHER_CONTROLLED' AND explanation IS NOT NULL
            AND char_length(explanation) BETWEEN 10 AND 500
            AND explanation = btrim(explanation) AND explanation !~ '[[:cntrl:]]')
        OR (reason_code <> 'OTHER_CONTROLLED' AND explanation IS NULL)),
    CONSTRAINT payroll_package_void_acknowledgement CHECK (no_payment_acknowledged),
    CONSTRAINT payroll_package_void_digest CHECK (approval_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_package_void_by_length CHECK (char_length(voided_by_subject) BETWEEN 1 AND 255)
);

-- ---------------------------------------------------------------------------------------------
-- Source binding (append-only; the lock evidence)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.payroll_package_source (
    tenant_id       uuid     NOT NULL,
    package_id      uuid     NOT NULL,
    kind            text     NOT NULL,
    source_id       uuid     NOT NULL,
    source_version  integer  NOT NULL,
    source_digest   char(64) NOT NULL,
    CONSTRAINT payroll_package_source_pkey PRIMARY KEY (tenant_id, package_id, kind),
    CONSTRAINT payroll_package_source_package FOREIGN KEY (tenant_id, package_id)
        REFERENCES payroll.payroll_package (tenant_id, id),
    CONSTRAINT payroll_package_source_kind CHECK (kind IN ('RUN', 'CALCULATION')),
    CONSTRAINT payroll_package_source_version_range CHECK (source_version BETWEEN 1 AND 25),
    CONSTRAINT payroll_package_source_digest CHECK (source_digest ~ '^[0-9a-f]{64}$')
);

-- The lock look-up: is this run or calculation bound to a package?
CREATE INDEX payroll_package_source_lookup ON payroll.payroll_package_source (tenant_id, kind, source_id);

-- ---------------------------------------------------------------------------------------------
-- Payslip snapshot (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.payroll_payslip (
    tenant_id                    uuid        NOT NULL,
    legal_entity_id              uuid        NOT NULL,
    package_id                   uuid        NOT NULL,
    id                           uuid        NOT NULL,
    period_start                 date        NOT NULL,
    approved_at                  timestamptz NOT NULL,
    employee_id                  uuid        NOT NULL,
    employee_number              text        NOT NULL,
    calculation_id               uuid        NOT NULL,
    calculation_line_id          uuid        NOT NULL,
    excluded                     boolean     NOT NULL,
    gross_minor                  bigint      NOT NULL,
    employee_deductions_minor    bigint      NOT NULL,
    employer_contributions_minor bigint      NOT NULL,
    net_minor                    bigint      NOT NULL,
    employer_cost_minor          bigint      NOT NULL,
    snapshot_version             smallint    NOT NULL,
    snapshot_text                text        NOT NULL,
    snapshot                     jsonb       GENERATED ALWAYS AS (payroll.payroll_snapshot_json(snapshot_text)) STORED,
    snapshot_digest              char(64)    GENERATED ALWAYS AS (payroll.payroll_snapshot_digest(snapshot_text)) STORED,
    CONSTRAINT payroll_payslip_pkey PRIMARY KEY (tenant_id, package_id, id),
    CONSTRAINT payroll_payslip_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT payroll_payslip_employee_unique UNIQUE (tenant_id, package_id, employee_id),
    CONSTRAINT payroll_payslip_line_unique UNIQUE (tenant_id, package_id, calculation_line_id),
    CONSTRAINT payroll_payslip_package FOREIGN KEY (tenant_id, legal_entity_id, package_id)
        REFERENCES payroll.payroll_package (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_payslip_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT payroll_payslip_line FOREIGN KEY (tenant_id, calculation_id, calculation_line_id)
        REFERENCES payroll.statutory_calculation_line (tenant_id, calculation_id, id),
    CONSTRAINT payroll_payslip_number
        CHECK (char_length(employee_number) BETWEEN 1 AND 64 AND employee_number !~ '[[:cntrl:]]'),
    CONSTRAINT payroll_payslip_amounts CHECK (
        gross_minor BETWEEN 0 AND 1000000000
        AND employee_deductions_minor BETWEEN 0 AND gross_minor
        AND employer_contributions_minor >= 0),
    CONSTRAINT payroll_payslip_arithmetic CHECK (
        net_minor = gross_minor - employee_deductions_minor
        AND employer_cost_minor = gross_minor + employer_contributions_minor),
    CONSTRAINT payroll_payslip_excluded CHECK (
        NOT excluded OR (employee_deductions_minor = 0 AND employer_contributions_minor = 0)),
    CONSTRAINT payroll_payslip_snapshot_version CHECK (snapshot_version = 1),
    CONSTRAINT payroll_payslip_snapshot_size CHECK (char_length(snapshot_text) BETWEEN 2 AND 16384),
    CONSTRAINT payroll_payslip_snapshot_object CHECK (jsonb_typeof(snapshot) = 'object'),
    CONSTRAINT payroll_payslip_digest CHECK (snapshot_digest ~ '^[0-9a-f]{64}$')
);

-- Employee payslip list, newest first (keyset: approved_at DESC, id DESC); the tenant and the verified
-- employee link are the predicates.
CREATE INDEX payroll_payslip_employee_page
    ON payroll.payroll_payslip (tenant_id, employee_id, approved_at DESC, id DESC);
-- Package payslip list by employee number in byte order, then id.
CREATE INDEX payroll_payslip_package_page
    ON payroll.payroll_payslip (tenant_id, package_id, (employee_number COLLATE "C"), id);
CREATE INDEX payroll_payslip_line_fk ON payroll.payroll_payslip (tenant_id, calculation_id, calculation_line_id);
CREATE INDEX payroll_payslip_package_fk ON payroll.payroll_payslip (tenant_id, legal_entity_id, package_id);

-- ---------------------------------------------------------------------------------------------
-- Package guard (insert: the sources are current and exactly what the package says; update: the one
-- lifecycle step)
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.payroll_package_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
    run    payroll.pay_run%ROWTYPE;
    calc   payroll.statutory_calculation%ROWTYPE;
    pack   payroll.statutory_rule_pack%ROWTYPE;
    void   payroll.payroll_package_void%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Last in the global lock order (tenant, entity, run, calculation, package): the application already
        -- holds it; taking it again is free and protects direct writers.
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'payroll.package:' || NEW.tenant_id::text || ':' || NEW.legal_entity_id::text, 0));
        SELECT max(version) INTO latest FROM payroll.payroll_package
            WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id
              AND period_start = NEW.period_start AND currency = NEW.currency;
        IF NEW.status <> 'APPROVED' OR NEW.voided_at IS NOT NULL OR NEW.version <> coalesce(latest, 0) + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_version',
                MESSAGE = 'a payroll package is created APPROVED as the next version of its chain';
        END IF;
        -- FOR SHARE: a concurrent supersession of the run or calculation either committed first (then this
        -- re-read sees it) or waits for this transaction and meets the source lock.
        SELECT * INTO run FROM payroll.pay_run
            WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.run_id
            FOR SHARE;
        SELECT * INTO calc FROM payroll.statutory_calculation
            WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.calculation_id
            FOR SHARE;
        SELECT * INTO pack FROM payroll.statutory_rule_pack WHERE id = NEW.pack_id;
        IF NOT (run.id IS NOT NULL AND calc.id IS NOT NULL AND pack.id IS NOT NULL) THEN
            RETURN NEW; -- a missing run, calculation or pack is refused by its foreign key
        END IF;
        IF run.status <> 'DRAFT' OR run.version <> NEW.run_version OR run.input_digest <> NEW.run_digest
            OR run.period_start <> NEW.period_start OR run.currency <> NEW.currency
            OR run.policy_id <> NEW.policy_id OR run.policy_sequence <> NEW.policy_sequence THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_run_state',
                MESSAGE = 'a payroll package approves the current CDF DRAFT run of its legal entity and month';
        END IF;
        IF calc.status <> 'DRAFT' OR calc.run_id <> NEW.run_id OR calc.run_version <> NEW.run_version
            OR calc.version <> NEW.calculation_version OR calc.pack_id <> NEW.pack_id
            OR calc.period_start <> NEW.period_start OR calc.currency <> NEW.currency
            OR calc.calculation_digest <> NEW.calculation_digest
            OR calc.source_run_digest <> NEW.calculation_source_run_digest
            OR calc.profile_digest <> NEW.calculation_profile_digest
            OR calc.rule_pack_digest <> NEW.calculation_rule_pack_digest THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_calculation_state',
                MESSAGE = 'a payroll package approves the current statutory calculation of its run';
        END IF;
        IF pack.status <> 'VERIFIED' OR pack.code <> NEW.pack_code OR pack.version <> NEW.pack_version
            OR pack.rules_digest <> NEW.rules_digest THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_pack_state',
                MESSAGE = 'a payroll package is approved only on a VERIFIED rule pack, recorded exactly';
        END IF;
        IF NEW.payslip_count <> calc.supported_count + calc.excluded_count
            OR NEW.gross_total_minor <> calc.gross_total_minor
            OR NEW.employee_deductions_total_minor <> calc.employee_deductions_total_minor
            OR NEW.employer_contributions_total_minor <> calc.employer_contributions_total_minor
            OR NEW.net_total_minor <> calc.preview_net_total_minor
            OR NEW.employer_cost_total_minor <> calc.employer_cost_total_minor THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_totals_source',
                MESSAGE = 'the totals and the count of a payroll package equal those of its statutory calculation';
        END IF;
        IF NEW.approved_at < calc.created_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_approved_at',
                MESSAGE = 'a payroll package is approved after its statutory calculation was created';
        END IF;
        IF NEW.same_preparer IS DISTINCT FROM (NEW.approved_by_subject IN (run.created_by, calc.created_by)) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_same_preparer',
                MESSAGE = 'the same-preparer fact is the approver being the creator of the run or the calculation';
        END IF;
        -- A run and calculation that were ever in a VOIDED package can never be approved again: a corrected
        -- chain is required.
        IF EXISTS (SELECT 1 FROM payroll.payroll_package p
                    WHERE p.tenant_id = NEW.tenant_id AND p.run_id = NEW.run_id
                      AND p.calculation_id = NEW.calculation_id AND p.status = 'VOIDED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_source_voided',
                MESSAGE = 'a run and calculation that were in a voided package are never approved again';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: the sole legal update is APPROVED -> VOIDED, once, with its void evidence; nothing else moves.
    IF OLD.status <> 'APPROVED' OR NEW.status <> 'VOIDED'
        OR NEW.tenant_id <> OLD.tenant_id OR NEW.legal_entity_id <> OLD.legal_entity_id
        OR NEW.id <> OLD.id OR NEW.period_start <> OLD.period_start OR NEW.currency <> OLD.currency
        OR NEW.version <> OLD.version
        OR NEW.run_id <> OLD.run_id OR NEW.run_version <> OLD.run_version OR NEW.run_digest <> OLD.run_digest
        OR NEW.calculation_id <> OLD.calculation_id OR NEW.calculation_version <> OLD.calculation_version
        OR NEW.calculation_digest <> OLD.calculation_digest
        OR NEW.calculation_source_run_digest <> OLD.calculation_source_run_digest
        OR NEW.calculation_profile_digest <> OLD.calculation_profile_digest
        OR NEW.calculation_rule_pack_digest <> OLD.calculation_rule_pack_digest
        OR NEW.pack_id <> OLD.pack_id OR NEW.pack_code <> OLD.pack_code OR NEW.pack_version <> OLD.pack_version
        OR NEW.rules_digest <> OLD.rules_digest
        OR NEW.policy_id <> OLD.policy_id OR NEW.policy_sequence <> OLD.policy_sequence
        OR NEW.payslip_count <> OLD.payslip_count
        OR NEW.gross_total_minor <> OLD.gross_total_minor
        OR NEW.employee_deductions_total_minor <> OLD.employee_deductions_total_minor
        OR NEW.employer_contributions_total_minor <> OLD.employer_contributions_total_minor
        OR NEW.net_total_minor <> OLD.net_total_minor
        OR NEW.employer_cost_total_minor <> OLD.employer_cost_total_minor
        OR NEW.reviewed_totals <> OLD.reviewed_totals OR NEW.reviewed_exceptions <> OLD.reviewed_exceptions
        OR NEW.understands_no_payment <> OLD.understands_no_payment
        OR NEW.same_preparer <> OLD.same_preparer OR NEW.approval_digest <> OLD.approval_digest
        OR NEW.approved_at <> OLD.approved_at OR NEW.approved_by_subject <> OLD.approved_by_subject THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_lifecycle_only',
            MESSAGE = 'a payroll package is only ever voided, once, and nothing else about it changes';
    END IF;
    SELECT * INTO void FROM payroll.payroll_package_void
        WHERE tenant_id = OLD.tenant_id AND package_id = OLD.id;
    IF NOT FOUND OR void.voided_at <> NEW.voided_at OR void.approval_digest <> OLD.approval_digest THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_void_evidence',
            MESSAGE = 'a payroll package is voided only with its void evidence, recorded first';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_package_guard BEFORE INSERT OR UPDATE ON payroll.payroll_package
    FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_guard();
CREATE TRIGGER payroll_package_no_delete BEFORE DELETE ON payroll.payroll_package
    FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_immutable();
CREATE TRIGGER payroll_package_no_truncate BEFORE TRUNCATE ON payroll.payroll_package
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payroll_package_immutable();

COMMENT ON TABLE payroll.payroll_package IS
    'MVP-094C approved payroll package joining a frozen payroll run and statutory calculation (not a payment, filing or compliance claim); restricted financial data';

-- Void evidence guard.
CREATE FUNCTION payroll.payroll_package_void_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    pkg payroll.payroll_package%ROWTYPE;
BEGIN
    SELECT * INTO pkg FROM payroll.payroll_package
        WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.package_id
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key refuses an unknown package
    END IF;
    IF pkg.status <> 'APPROVED' OR pkg.approval_digest <> NEW.approval_digest
        OR NEW.voided_at < pkg.approved_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_void_state',
            MESSAGE = 'void evidence is recorded for an APPROVED package, with its approval digest, after approval';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_package_void_guard BEFORE INSERT ON payroll.payroll_package_void
    FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_void_guard();
CREATE TRIGGER payroll_package_void_immutable BEFORE UPDATE OR DELETE ON payroll.payroll_package_void
    FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_immutable();
CREATE TRIGGER payroll_package_void_no_truncate BEFORE TRUNCATE ON payroll.payroll_package_void
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payroll_package_immutable();

COMMENT ON TABLE payroll.payroll_package_void IS
    'MVP-094C append-only void evidence of a payroll package (closed reason code; the explanation of OTHER_CONTROLLED is restricted)';

-- Source binding guard.
CREATE FUNCTION payroll.payroll_package_source_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    pkg payroll.payroll_package%ROWTYPE;
BEGIN
    SELECT * INTO pkg FROM payroll.payroll_package WHERE tenant_id = NEW.tenant_id AND id = NEW.package_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key refuses an unknown package
    END IF;
    IF pkg.status <> 'APPROVED' OR
        (NEW.kind = 'RUN' AND (NEW.source_id <> pkg.run_id OR NEW.source_version <> pkg.run_version
            OR NEW.source_digest <> pkg.run_digest))
        OR (NEW.kind = 'CALCULATION' AND (NEW.source_id <> pkg.calculation_id
            OR NEW.source_version <> pkg.calculation_version OR NEW.source_digest <> pkg.calculation_digest)) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_source_binding',
            MESSAGE = 'a source binding names the run or calculation, version and digest of its APPROVED package';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_package_source_guard BEFORE INSERT ON payroll.payroll_package_source
    FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_source_guard();
CREATE TRIGGER payroll_package_source_immutable BEFORE UPDATE OR DELETE ON payroll.payroll_package_source
    FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_immutable();
CREATE TRIGGER payroll_package_source_no_truncate BEFORE TRUNCATE ON payroll.payroll_package_source
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payroll_package_immutable();

COMMENT ON TABLE payroll.payroll_package_source IS
    'MVP-094C append-only evidence binding an approved package to its payroll run and statutory calculation (the source lock)';

-- ---------------------------------------------------------------------------------------------
-- The source lock: the supersession of a run or calculation bound to an APPROVED package is refused.
-- A voided package releases the lock (its binding rows stay as evidence).
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.payroll_package_source_lock() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.payroll_package_source s
                JOIN payroll.payroll_package p ON p.tenant_id = s.tenant_id AND p.id = s.package_id
                WHERE s.tenant_id = OLD.tenant_id AND s.kind = TG_ARGV[0] AND s.source_id = OLD.id
                  AND p.status = 'APPROVED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_source_locked',
            MESSAGE = 'a payroll run or statutory calculation bound to an APPROVED package cannot be superseded';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_pay_run_package_lock BEFORE UPDATE ON payroll.pay_run
    FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_source_lock('RUN');
CREATE TRIGGER payroll_statutory_calculation_package_lock BEFORE UPDATE ON payroll.statutory_calculation
    FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_source_lock('CALCULATION');

-- ---------------------------------------------------------------------------------------------
-- Payslip guard: every field of the snapshot equals the columns, the statutory line and its components
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.payroll_payslip_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    pkg        payroll.payroll_package%ROWTYPE;
    line       payroll.statutory_calculation_line%ROWTYPE;
    snap       jsonb;
    cnss       bigint;
    irpp       bigint;
    employer   bigint;
    n_comp     integer;
    n_expected integer;
    mismatched integer;
BEGIN
    SELECT * INTO pkg FROM payroll.payroll_package
        WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.package_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key refuses an unknown package
    END IF;
    IF pkg.status <> 'APPROVED' OR pkg.period_start <> NEW.period_start OR pkg.approved_at <> NEW.approved_at
        OR pkg.calculation_id <> NEW.calculation_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payslip_package_state',
            MESSAGE = 'a payslip belongs to an APPROVED package, its month, approval instant and calculation';
    END IF;
    SELECT * INTO line FROM payroll.statutory_calculation_line
        WHERE tenant_id = NEW.tenant_id AND calculation_id = NEW.calculation_id AND id = NEW.calculation_line_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key refuses an unknown line
    END IF;
    IF line.employee_id <> NEW.employee_id OR line.employee_number <> NEW.employee_number
        OR line.excluded <> NEW.excluded OR line.gross_minor <> NEW.gross_minor
        OR line.employee_deductions_minor <> NEW.employee_deductions_minor
        OR line.employer_contributions_minor <> NEW.employer_contributions_minor
        OR line.preview_net_minor <> NEW.net_minor OR line.employer_cost_minor <> NEW.employer_cost_minor THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payslip_source_line',
            MESSAGE = 'a payslip carries the employee, the flags and every amount of its statutory calculation line';
    END IF;
    -- Stored generated columns are not yet computed in a BEFORE trigger: parse the canonical text itself.
    snap := payroll.payroll_snapshot_json(NEW.snapshot_text);
    IF (SELECT array_agg(k ORDER BY k COLLATE "C") FROM jsonb_object_keys(snap) k) IS DISTINCT FROM ARRAY[
        'approvedAt', 'components', 'contractualGrossMinor', 'currency', 'employeeCnssMinor',
        'employeeDeductionsMinor', 'employeeId', 'employeeNumber', 'employerContributionsMinor',
        'excluded', 'explanationCodes', 'irppWithholdingMinor', 'legalEntityId', 'netMinor', 'packageId',
        'period', 'rulePack', 'schemaVersion', 'status']::text[] THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payslip_snapshot_fields',
            MESSAGE = 'a payslip snapshot has exactly the canonical fields, no more and no fewer';
    END IF;
    IF snap -> 'schemaVersion' IS DISTINCT FROM '1'::jsonb
        OR snap ->> 'status' IS DISTINCT FROM 'APPROVED'
        OR snap ->> 'packageId' IS DISTINCT FROM NEW.package_id::text
        OR snap ->> 'legalEntityId' IS DISTINCT FROM NEW.legal_entity_id::text
        OR snap ->> 'period' IS DISTINCT FROM to_char(NEW.period_start, 'YYYY-MM')
        OR snap ->> 'currency' IS DISTINCT FROM pkg.currency
        OR snap ->> 'employeeId' IS DISTINCT FROM NEW.employee_id::text
        OR snap ->> 'employeeNumber' IS DISTINCT FROM NEW.employee_number
        OR snap ->> 'approvedAt' IS DISTINCT FROM
            to_char(NEW.approved_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
        OR snap -> 'excluded' IS DISTINCT FROM to_jsonb(NEW.excluded)
        OR snap -> 'rulePack' IS DISTINCT FROM
            jsonb_build_object('code', pkg.pack_code, 'version', pkg.pack_version)
        OR snap ->> 'contractualGrossMinor' IS DISTINCT FROM NEW.gross_minor::text
        OR snap ->> 'employeeDeductionsMinor' IS DISTINCT FROM NEW.employee_deductions_minor::text
        OR snap ->> 'netMinor' IS DISTINCT FROM NEW.net_minor::text
        OR snap ->> 'employerContributionsMinor' IS DISTINCT FROM NEW.employer_contributions_minor::text THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payslip_snapshot_values',
            MESSAGE = 'every value of a payslip snapshot equals the payslip, its package and its calculation line';
    END IF;
    IF jsonb_typeof(snap -> 'components') IS DISTINCT FROM 'array'
        OR jsonb_typeof(snap -> 'explanationCodes') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payslip_snapshot_components',
            MESSAGE = 'the components and explanation codes of a payslip snapshot are arrays';
    END IF;
    -- The employee CNSS contribution and the IRPP withholding are the employee components by category;
    -- employer components are shown separately and are never part of the employee deductions or of the net.
    SELECT coalesce(sum((c ->> 'amountMinor')::bigint)
                FILTER (WHERE c ->> 'party' = 'EMPLOYEE' AND c ->> 'category' = 'SOCIAL_CONTRIBUTION'), 0),
           coalesce(sum((c ->> 'amountMinor')::bigint)
                FILTER (WHERE c ->> 'party' = 'EMPLOYEE' AND c ->> 'category' = 'INCOME_TAX'), 0),
           coalesce(sum((c ->> 'amountMinor')::bigint) FILTER (WHERE c ->> 'party' = 'EMPLOYER'), 0),
           count(*)
        INTO cnss, irpp, employer, n_comp
        FROM jsonb_array_elements(snap -> 'components') c;
    IF snap ->> 'employeeCnssMinor' IS DISTINCT FROM cnss::text
        OR snap ->> 'irppWithholdingMinor' IS DISTINCT FROM irpp::text
        OR cnss + irpp <> NEW.employee_deductions_minor
        OR employer <> NEW.employer_contributions_minor THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payslip_snapshot_sums',
            MESSAGE = 'the CNSS contribution and the IRPP withholding add up to the employee deductions; employer contributions stay separate';
    END IF;
    SELECT count(*) INTO n_expected FROM payroll.statutory_calculation_component
        WHERE tenant_id = NEW.tenant_id AND calculation_id = NEW.calculation_id AND line_id = NEW.calculation_line_id;
    SELECT count(*) INTO mismatched FROM payroll.statutory_calculation_component k
        WHERE k.tenant_id = NEW.tenant_id AND k.calculation_id = NEW.calculation_id
          AND k.line_id = NEW.calculation_line_id
          AND NOT coalesce(((snap -> 'components') -> (k.seq - 1)::integer) = jsonb_build_object(
                'seq', k.seq, 'id', k.component_id, 'party', k.party, 'category', k.category,
                'baseMinor', k.base_minor::text, 'amountMinor', k.amount_minor::text,
                'explanationCodes', to_jsonb(k.explanation_codes)), false);
    IF n_comp <> n_expected OR mismatched <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payslip_snapshot_components',
            MESSAGE = 'the components of a payslip snapshot are those of its statutory calculation line, in order';
    END IF;
    IF (snap -> 'explanationCodes') IS DISTINCT FROM
        coalesce((SELECT jsonb_agg(code ORDER BY code COLLATE "C")
                    FROM (SELECT DISTINCT jsonb_array_elements_text(c -> 'explanationCodes') AS code
                            FROM jsonb_array_elements(snap -> 'components') c) u), '[]'::jsonb) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payslip_snapshot_explanations',
            MESSAGE = 'the explanation codes of a payslip snapshot are the sorted distinct codes of its components';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_payslip_guard BEFORE INSERT ON payroll.payroll_payslip
    FOR EACH ROW EXECUTE FUNCTION payroll.payroll_payslip_guard();
CREATE TRIGGER payroll_payslip_immutable BEFORE UPDATE OR DELETE ON payroll.payroll_payslip
    FOR EACH ROW EXECUTE FUNCTION payroll.payroll_payslip_immutable();
CREATE TRIGGER payroll_payslip_no_truncate BEFORE TRUNCATE ON payroll.payroll_payslip
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.payroll_payslip_immutable();

COMMENT ON TABLE payroll.payroll_payslip IS
    'MVP-094C immutable canonical payslip snapshot per employee (digest = SHA-256 of the canonical text); restricted financial and HR data; not proof of payment';

-- ---------------------------------------------------------------------------------------------
-- The approval digest, recomputed by the database (ADR 0035 section 4):
--   payroll-package-approval:v1 LF, then run, calculation, pack, policy, totals lines, then one line
--   employee id|employee number|snapshot digest per payslip in employee-number byte order, then id.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.payroll_package_approval_digest(p_tenant uuid, p_package uuid) RETURNS char(64)
    LANGUAGE sql STABLE AS
$fn$
    SELECT encode(sha256(convert_to(
        E'payroll-package-approval:v1\n'
        || 'tenant=' || pkg.tenant_id::text || E'\n'
        || 'entity=' || pkg.legal_entity_id::text || E'\n'
        || 'period=' || to_char(pkg.period_start, 'YYYY-MM') || E'\n'
        || 'currency=' || pkg.currency || E'\n'
        || 'run=' || pkg.run_id::text || '|' || pkg.run_version::text || '|' || pkg.run_digest || E'\n'
        || 'calculation=' || pkg.calculation_id::text || '|' || pkg.calculation_version::text || '|'
            || pkg.calculation_digest || '|' || pkg.calculation_source_run_digest || '|'
            || pkg.calculation_profile_digest || '|' || pkg.calculation_rule_pack_digest || E'\n'
        || 'pack=' || pkg.pack_id::text || '|' || pkg.rules_digest || E'\n'
        || 'policy=' || pkg.policy_id::text || '|' || pkg.policy_sequence::text || E'\n'
        || 'totals=' || pkg.gross_total_minor::text || '|' || pkg.employee_deductions_total_minor::text || '|'
            || pkg.employer_contributions_total_minor::text || '|' || pkg.net_total_minor::text || '|'
            || pkg.employer_cost_total_minor::text || '|' || pkg.payslip_count::text || E'\n'
        || coalesce((SELECT string_agg(s.employee_id::text || '|' || s.employee_number || '|' || s.snapshot_digest
                                       || E'\n', '' ORDER BY s.employee_number COLLATE "C", s.employee_id)
                       FROM payroll.payroll_payslip s
                      WHERE s.tenant_id = pkg.tenant_id AND s.package_id = pkg.id), ''),
        'UTF8')), 'hex')::char(64)
    FROM payroll.payroll_package pkg
    WHERE pkg.tenant_id = p_tenant AND pkg.id = p_package
$fn$;

-- A package is judged with its payslips, sources and void evidence at commit.
CREATE FUNCTION payroll.payroll_package_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    n_slips   integer;
    gross      bigint;
    deductions bigint;
    employer   bigint;
    net        bigint;
    cost       bigint;
    n_sources  integer;
    n_voids    integer;
BEGIN
    SELECT count(*) INTO n_voids FROM payroll.payroll_package_void
        WHERE tenant_id = NEW.tenant_id AND package_id = NEW.id;
    IF (NEW.status = 'APPROVED' AND n_voids <> 0) OR (NEW.status = 'VOIDED' AND n_voids <> 1) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_void_evidence',
            MESSAGE = 'a VOIDED package has exactly one void evidence row and an APPROVED package has none';
    END IF;
    SELECT count(*), coalesce(sum(gross_minor), 0), coalesce(sum(employee_deductions_minor), 0),
           coalesce(sum(employer_contributions_minor), 0), coalesce(sum(net_minor), 0),
           coalesce(sum(employer_cost_minor), 0)
        INTO n_slips, gross, deductions, employer, net, cost
        FROM payroll.payroll_payslip WHERE tenant_id = NEW.tenant_id AND package_id = NEW.id;
    IF n_slips <> NEW.payslip_count
        OR n_slips <> (SELECT supported_count + excluded_count FROM payroll.statutory_calculation
                        WHERE tenant_id = NEW.tenant_id AND id = NEW.calculation_id)
        OR gross <> NEW.gross_total_minor OR deductions <> NEW.employee_deductions_total_minor
        OR employer <> NEW.employer_contributions_total_minor OR net <> NEW.net_total_minor
        OR cost <> NEW.employer_cost_total_minor THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_exact',
            MESSAGE = 'a payroll package has one payslip per calculation line and totals equal to their sums';
    END IF;
    SELECT count(*) INTO n_sources FROM payroll.payroll_package_source
        WHERE tenant_id = NEW.tenant_id AND package_id = NEW.id;
    IF n_sources <> 2 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_sources_exact',
            MESSAGE = 'a payroll package is bound to exactly its run and its calculation';
    END IF;
    IF payroll.payroll_package_approval_digest(NEW.tenant_id, NEW.id) IS DISTINCT FROM NEW.approval_digest THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_approval_digest',
            MESSAGE = 'the approval digest of a payroll package is the digest of its sources, pack, policy, totals and payslips';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_package_exact AFTER INSERT OR UPDATE ON payroll.payroll_package
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_exact();

-- A void row and a payslip row are judged with their package at commit as well, so that rows added to an
-- existing package in a later transaction are refused.
CREATE FUNCTION payroll.payroll_package_child_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    pkg payroll.payroll_package%ROWTYPE;
BEGIN
    SELECT * INTO pkg FROM payroll.payroll_package WHERE tenant_id = NEW.tenant_id AND id = NEW.package_id;
    IF TG_TABLE_NAME = 'payroll_payslip' THEN
        IF (SELECT count(*) FROM payroll.payroll_payslip
                WHERE tenant_id = NEW.tenant_id AND package_id = NEW.package_id) <> pkg.payslip_count THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_payslip_set_exact',
                MESSAGE = 'payslips are added only with their package, exactly one per calculation line';
        END IF;
    ELSIF TG_TABLE_NAME = 'payroll_package_source' THEN
        IF (SELECT count(*) FROM payroll.payroll_package_source
                WHERE tenant_id = NEW.tenant_id AND package_id = NEW.package_id) <> 2 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_sources_exact',
                MESSAGE = 'a payroll package is bound to exactly its run and its calculation';
        END IF;
    ELSE
        IF pkg.status <> 'VOIDED' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_package_void_evidence',
                MESSAGE = 'void evidence is recorded together with the VOIDED status of its package';
        END IF;
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_payslip_exact AFTER INSERT ON payroll.payroll_payslip
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_child_exact();
CREATE CONSTRAINT TRIGGER payroll_package_source_exact AFTER INSERT ON payroll.payroll_package_source
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_child_exact();
CREATE CONSTRAINT TRIGGER payroll_package_void_exact AFTER INSERT ON payroll.payroll_package_void
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.payroll_package_child_exact();
