-- MVP-096 (Issue #194, ADR 0039): immutable, balanced accounting-journal EXPORT from an approved payroll package.
-- An accounting mapping version holds five account codes per legal entity and currency, effective-dated and append-only.
-- A journal is generated once from the FROZEN approval evidence of one APPROVED package (payroll_package and its
-- payslip amounts) and one mapping version; its lines are immutable. It is an accounting-ready export: not posting, not
-- settlement, not proof of payment. Nothing here recalculates pay, converts a currency or touches payroll, payslips,
-- payment batches, submissions or reconciliations.
--
-- Classification: restricted financial data. NO employee name, number, e-mail, destination, phone, national identifier,
-- payslip detail or free text is stored. What is stored: account codes, directions, minor-unit amounts, digests, ids that
-- already exist on the package, timestamps.
--
--   * accounting_mapping: one immutable row per version (1..10 per tenant, entity and currency); effective_from is
--     strictly increasing with the version (no overlap); never updated or deleted.
--   * accounting_journal: the header; one per package (and per package, currency and version); balanced; the evidence
--     of the package, calculation and mapping it was generated from; the digest of its CSV.
--   * accounting_journal_line: immutable lines numbered 1..line_count.
--
-- Privileges: like V43 to V49 this migration issues no GRANT or REVOKE (the application role owns the schema).
-- Rollback: db/rollback/V50__rollback.sql refuses while any mapping, journal or line exists.

CREATE FUNCTION payroll.accounting_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = TG_ARGV[0],
        MESSAGE = 'accounting mapping and journal evidence is append-only: it is never updated, deleted or truncated';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Mapping versions
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.accounting_mapping (
    tenant_id                         uuid        NOT NULL,
    legal_entity_id                   uuid        NOT NULL,
    id                                uuid        NOT NULL,
    currency                          text        NOT NULL,
    version                           integer     NOT NULL,
    effective_from                    date        NOT NULL,
    salary_expense                    text        NOT NULL,
    employee_deduction_liability      text        NOT NULL,
    employer_contribution_expense     text        NOT NULL,
    employer_contribution_liability   text        NOT NULL,
    net_salary_payable                text        NOT NULL,
    mapping_digest                    char(64)    NOT NULL,
    created_at                        timestamptz NOT NULL,
    created_by_subject                text        NOT NULL,
    CONSTRAINT accounting_mapping_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT accounting_mapping_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT accounting_mapping_version_unique UNIQUE (tenant_id, legal_entity_id, currency, version),
    CONSTRAINT accounting_mapping_effective_unique UNIQUE (tenant_id, legal_entity_id, currency, effective_from),
    CONSTRAINT accounting_mapping_legal_entity FOREIGN KEY (tenant_id, legal_entity_id)
        REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT accounting_mapping_currency CHECK (currency IN ('CDF', 'USD')),
    CONSTRAINT accounting_mapping_version_range CHECK (version BETWEEN 1 AND 10),
    CONSTRAINT accounting_mapping_effective_range CHECK (effective_from BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT accounting_mapping_codes CHECK (
        salary_expense ~ '^[A-Z0-9][A-Z0-9._-]{0,39}$'
        AND employee_deduction_liability ~ '^[A-Z0-9][A-Z0-9._-]{0,39}$'
        AND employer_contribution_expense ~ '^[A-Z0-9][A-Z0-9._-]{0,39}$'
        AND employer_contribution_liability ~ '^[A-Z0-9][A-Z0-9._-]{0,39}$'
        AND net_salary_payable ~ '^[A-Z0-9][A-Z0-9._-]{0,39}$'),
    CONSTRAINT accounting_mapping_digest CHECK (mapping_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT accounting_mapping_subject CHECK (char_length(created_by_subject) BETWEEN 1 AND 255)
);

CREATE FUNCTION payroll.accounting_mapping_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    previous payroll.accounting_mapping%ROWTYPE;
BEGIN
    -- Serialises the version chain of one (tenant, legal entity, currency); the application takes the same lock first.
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'payroll.accounting-mapping:' || NEW.tenant_id::text || ':' || NEW.legal_entity_id::text || ':' || NEW.currency, 0));
    SELECT * INTO previous FROM payroll.accounting_mapping
        WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND currency = NEW.currency
        ORDER BY version DESC LIMIT 1;
    IF previous.id IS NULL THEN
        IF NEW.version <> 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_mapping_chain',
                MESSAGE = 'the first accounting mapping version of a currency is version 1';
        END IF;
    ELSE
        IF NEW.version <> previous.version + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_mapping_chain',
                MESSAGE = 'an accounting mapping version is the next in its chain';
        END IF;
        IF NEW.effective_from <= previous.effective_from THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_mapping_overlap',
                MESSAGE = 'an accounting mapping version takes effect strictly after the previous version';
        END IF;
        IF NEW.created_at < previous.created_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_mapping_time',
                MESSAGE = 'accounting mapping versions are appended forward in time';
        END IF;
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER accounting_mapping_guard BEFORE INSERT ON payroll.accounting_mapping
    FOR EACH ROW EXECUTE FUNCTION payroll.accounting_mapping_guard();
CREATE TRIGGER accounting_mapping_immutable BEFORE UPDATE OR DELETE ON payroll.accounting_mapping
    FOR EACH ROW EXECUTE FUNCTION payroll.accounting_immutable('accounting_mapping_immutable');
CREATE TRIGGER accounting_mapping_no_truncate BEFORE TRUNCATE ON payroll.accounting_mapping
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.accounting_immutable('accounting_mapping_immutable');

-- ---------------------------------------------------------------------------------------------
-- Journal header
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.accounting_journal (
    tenant_id                          uuid        NOT NULL,
    legal_entity_id                    uuid        NOT NULL,
    id                                 uuid        NOT NULL,
    package_id                         uuid        NOT NULL,
    currency                           text        NOT NULL,
    version                            integer     NOT NULL,
    period_start                       date        NOT NULL,
    legal_entity_code                  text        NOT NULL,
    package_version                    integer     NOT NULL,
    package_approval_digest            char(64)    NOT NULL,
    calculation_id                     uuid        NOT NULL,
    calculation_version                integer     NOT NULL,
    calculation_digest                 char(64)    NOT NULL,
    mapping_id                         uuid        NOT NULL,
    mapping_version                    integer     NOT NULL,
    mapping_digest                     char(64)    NOT NULL,
    payslip_count                      integer     NOT NULL,
    source_entry_count                 integer     NOT NULL,
    line_count                         integer     NOT NULL,
    gross_total_minor                  bigint      NOT NULL,
    employee_deductions_total_minor    bigint      NOT NULL,
    employer_contributions_total_minor bigint      NOT NULL,
    net_total_minor                    bigint      NOT NULL,
    total_debits_minor                 bigint      NOT NULL,
    total_credits_minor                bigint      NOT NULL,
    csv_sha256                         char(64)    NOT NULL,
    csv_bytes                          integer     NOT NULL,
    journal_digest                     char(64)    NOT NULL,
    schema_version                     smallint    NOT NULL,
    generated_at                       timestamptz NOT NULL,
    generated_by_subject               text        NOT NULL,
    CONSTRAINT accounting_journal_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT accounting_journal_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT accounting_journal_one_per_package UNIQUE (tenant_id, package_id),
    CONSTRAINT accounting_journal_version_unique UNIQUE (tenant_id, package_id, currency, version),
    CONSTRAINT accounting_journal_package FOREIGN KEY (tenant_id, legal_entity_id, package_id)
        REFERENCES payroll.payroll_package (tenant_id, legal_entity_id, id),
    CONSTRAINT accounting_journal_mapping FOREIGN KEY (tenant_id, legal_entity_id, mapping_id)
        REFERENCES payroll.accounting_mapping (tenant_id, legal_entity_id, id),
    CONSTRAINT accounting_journal_currency CHECK (currency IN ('CDF', 'USD')),
    CONSTRAINT accounting_journal_version_range CHECK (version BETWEEN 1 AND 25),
    CONSTRAINT accounting_journal_period CHECK (period_start = date_trunc('month', period_start)::date),
    CONSTRAINT accounting_journal_entity_code CHECK (legal_entity_code ~ '^[A-Z0-9_-]{2,20}$'),
    CONSTRAINT accounting_journal_package_version_range CHECK (package_version BETWEEN 1 AND 25),
    CONSTRAINT accounting_journal_calculation_version_range CHECK (calculation_version BETWEEN 1 AND 25),
    CONSTRAINT accounting_journal_mapping_version_range CHECK (mapping_version BETWEEN 1 AND 10),
    CONSTRAINT accounting_journal_counts CHECK (
        payslip_count BETWEEN 1 AND 5000
        AND source_entry_count BETWEEN 1 AND 10000
        AND line_count BETWEEN 1 AND 2000
        AND line_count <= source_entry_count),
    CONSTRAINT accounting_journal_amounts CHECK (
        gross_total_minor BETWEEN 1 AND 5000000000000
        AND employee_deductions_total_minor BETWEEN 0 AND gross_total_minor
        AND employer_contributions_total_minor >= 0
        AND net_total_minor = gross_total_minor - employee_deductions_total_minor),
    -- The balance: debits are the gross and the employer contributions; credits are the employee deductions, the
    -- employer contributions and the net. Exact in minor units.
    CONSTRAINT accounting_journal_balanced CHECK (
        total_debits_minor = total_credits_minor
        AND total_debits_minor = gross_total_minor + employer_contributions_total_minor
        AND total_credits_minor = employee_deductions_total_minor + employer_contributions_total_minor + net_total_minor
        AND total_debits_minor > 0),
    CONSTRAINT accounting_journal_digests CHECK (
        package_approval_digest ~ '^[0-9a-f]{64}$' AND calculation_digest ~ '^[0-9a-f]{64}$'
        AND mapping_digest ~ '^[0-9a-f]{64}$' AND csv_sha256 ~ '^[0-9a-f]{64}$' AND journal_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT accounting_journal_csv_size CHECK (csv_bytes BETWEEN 1 AND 2000000),
    CONSTRAINT accounting_journal_schema CHECK (schema_version = 1),
    CONSTRAINT accounting_journal_subject CHECK (char_length(generated_by_subject) BETWEEN 1 AND 255)
);

-- Journal history of an entity, newest first (keyset: generated_at, id); the package look-up uses the unique key.
CREATE INDEX accounting_journal_history
    ON payroll.accounting_journal (tenant_id, legal_entity_id, generated_at DESC, id);
-- Backs the mapping foreign key.
CREATE INDEX accounting_journal_mapping_fk ON payroll.accounting_journal (tenant_id, legal_entity_id, mapping_id);

-- ---------------------------------------------------------------------------------------------
-- Journal lines
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.accounting_journal_line (
    tenant_id        uuid    NOT NULL,
    journal_id       uuid    NOT NULL,
    line_number      integer NOT NULL,
    account_code     text    NOT NULL,
    direction        text    NOT NULL,
    amount_minor     bigint  NOT NULL,
    cost_center_code text,
    site_code        text,
    CONSTRAINT accounting_journal_line_pkey PRIMARY KEY (tenant_id, journal_id, line_number),
    CONSTRAINT accounting_journal_line_journal FOREIGN KEY (tenant_id, journal_id)
        REFERENCES payroll.accounting_journal (tenant_id, id),
    CONSTRAINT accounting_journal_line_number CHECK (line_number BETWEEN 1 AND 2000),
    CONSTRAINT accounting_journal_line_account CHECK (account_code ~ '^[A-Z0-9][A-Z0-9._-]{0,39}$'),
    CONSTRAINT accounting_journal_line_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT accounting_journal_line_amount CHECK (amount_minor BETWEEN 1 AND 100000000000000),
    CONSTRAINT accounting_journal_line_cost_center CHECK (
        cost_center_code IS NULL OR cost_center_code ~ '^[A-Z0-9][A-Z0-9._-]{0,39}$'),
    CONSTRAINT accounting_journal_line_site CHECK (site_code IS NULL OR site_code ~ '^[A-Z0-9][A-Z0-9._-]{0,39}$')
);

-- Header guard: the journal is exactly what the frozen package, calculation and mapping say.
CREATE FUNCTION payroll.accounting_journal_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    pkg     payroll.payroll_package%ROWTYPE;
    map     payroll.accounting_mapping%ROWTYPE;
    applies uuid;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'payroll.accounting-journal:' || NEW.tenant_id::text || ':' || NEW.package_id::text, 0));
    SELECT * INTO pkg FROM payroll.payroll_package
        WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.package_id FOR SHARE;
    IF pkg.id IS NULL THEN
        RETURN NEW; -- a missing package is refused by its foreign key
    END IF;
    IF pkg.status <> 'APPROVED' OR pkg.voided_at IS NOT NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_journal_source_state',
            MESSAGE = 'an accounting journal is generated only from an APPROVED, non-voided payroll package';
    END IF;
    IF pkg.currency <> NEW.currency OR pkg.period_start <> NEW.period_start OR pkg.version <> NEW.package_version
        OR pkg.approval_digest <> NEW.package_approval_digest OR pkg.calculation_id <> NEW.calculation_id
        OR pkg.calculation_version <> NEW.calculation_version OR pkg.calculation_digest <> NEW.calculation_digest
        OR pkg.payslip_count <> NEW.payslip_count THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_journal_source_exact',
            MESSAGE = 'an accounting journal records exactly the package and calculation evidence it was generated from';
    END IF;
    IF pkg.gross_total_minor <> NEW.gross_total_minor
        OR pkg.employee_deductions_total_minor <> NEW.employee_deductions_total_minor
        OR pkg.employer_contributions_total_minor <> NEW.employer_contributions_total_minor
        OR pkg.net_total_minor <> NEW.net_total_minor THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_journal_totals_exact',
            MESSAGE = 'an accounting journal carries exactly the frozen package totals';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tenant.legal_entity
            WHERE tenant_id = NEW.tenant_id AND id = NEW.legal_entity_id AND code = NEW.legal_entity_code) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_journal_entity_exact',
            MESSAGE = 'an accounting journal records its legal entity code';
    END IF;
    SELECT * INTO map FROM payroll.accounting_mapping
        WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.mapping_id;
    IF map.id IS NULL THEN
        RETURN NEW; -- a missing mapping is refused by its foreign key
    END IF;
    SELECT m.id INTO applies FROM payroll.accounting_mapping m
        WHERE m.tenant_id = NEW.tenant_id AND m.legal_entity_id = NEW.legal_entity_id AND m.currency = NEW.currency
          AND m.effective_from <= NEW.period_start
        ORDER BY m.effective_from DESC LIMIT 1;
    IF map.currency <> NEW.currency OR map.version <> NEW.mapping_version OR map.mapping_digest <> NEW.mapping_digest
        OR applies IS DISTINCT FROM map.id THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_journal_mapping_exact',
            MESSAGE = 'an accounting journal uses the mapping version that applies to its package month and currency';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER accounting_journal_guard BEFORE INSERT ON payroll.accounting_journal
    FOR EACH ROW EXECUTE FUNCTION payroll.accounting_journal_guard();
CREATE TRIGGER accounting_journal_immutable BEFORE UPDATE OR DELETE ON payroll.accounting_journal
    FOR EACH ROW EXECUTE FUNCTION payroll.accounting_immutable('accounting_journal_immutable');
CREATE TRIGGER accounting_journal_no_truncate BEFORE TRUNCATE ON payroll.accounting_journal
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.accounting_immutable('accounting_journal_immutable');

-- Line guard: a line uses one of the five mapped codes of its journal's mapping and never exceeds the declared count.
CREATE FUNCTION payroll.accounting_journal_line_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    jr  payroll.accounting_journal%ROWTYPE;
    map payroll.accounting_mapping%ROWTYPE;
    n   integer;
BEGIN
    SELECT * INTO jr FROM payroll.accounting_journal WHERE tenant_id = NEW.tenant_id AND id = NEW.journal_id;
    IF jr.id IS NULL THEN
        RETURN NEW; -- a missing journal is refused by its foreign key
    END IF;
    SELECT * INTO map FROM payroll.accounting_mapping
        WHERE tenant_id = jr.tenant_id AND legal_entity_id = jr.legal_entity_id AND id = jr.mapping_id;
    IF NEW.account_code NOT IN (map.salary_expense, map.employee_deduction_liability, map.employer_contribution_expense,
            map.employer_contribution_liability, map.net_salary_payable) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_journal_line_mapped',
            MESSAGE = 'an accounting journal line uses an account code of its mapping';
    END IF;
    IF NEW.line_number > jr.line_count THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_journal_line_count',
            MESSAGE = 'an accounting journal never has more lines than it declares';
    END IF;
    SELECT count(*) INTO n FROM payroll.accounting_journal_line WHERE tenant_id = NEW.tenant_id AND journal_id = NEW.journal_id;
    IF n >= jr.line_count THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_journal_line_count',
            MESSAGE = 'an accounting journal never has more lines than it declares';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER accounting_journal_line_guard BEFORE INSERT ON payroll.accounting_journal_line
    FOR EACH ROW EXECUTE FUNCTION payroll.accounting_journal_line_guard();
CREATE TRIGGER accounting_journal_line_immutable BEFORE UPDATE OR DELETE ON payroll.accounting_journal_line
    FOR EACH ROW EXECUTE FUNCTION payroll.accounting_immutable('accounting_journal_line_immutable');
CREATE TRIGGER accounting_journal_line_no_truncate BEFORE TRUNCATE ON payroll.accounting_journal_line
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.accounting_immutable('accounting_journal_line_immutable');

-- Deferred exactness: at commit every journal has exactly its declared lines, numbered 1..line_count, and the lines
-- balance exactly and equal the header totals.
CREATE FUNCTION payroll.accounting_journal_check(p_tenant uuid, p_journal uuid) RETURNS void
LANGUAGE plpgsql AS
$fn$
DECLARE
    jr      payroll.accounting_journal%ROWTYPE;
    n       integer;
    maxn    integer;
    debits  bigint;
    credits bigint;
BEGIN
    SELECT * INTO jr FROM payroll.accounting_journal WHERE tenant_id = p_tenant AND id = p_journal;
    IF jr.id IS NULL THEN
        RETURN;
    END IF;
    SELECT count(*), coalesce(max(line_number), 0),
           coalesce(sum(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           coalesce(sum(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0)
        INTO n, maxn, debits, credits
        FROM payroll.accounting_journal_line WHERE tenant_id = p_tenant AND journal_id = p_journal;
    IF n <> jr.line_count OR maxn <> jr.line_count THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_journal_lines_exact',
            MESSAGE = 'an accounting journal has exactly its declared lines, numbered without gaps';
    END IF;
    IF debits <> jr.total_debits_minor OR credits <> jr.total_credits_minor OR debits <> credits THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'accounting_journal_lines_balanced',
            MESSAGE = 'the lines of an accounting journal balance exactly and equal its totals';
    END IF;
END
$fn$;

CREATE FUNCTION payroll.accounting_journal_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    PERFORM payroll.accounting_journal_check(NEW.tenant_id, NEW.id);
    RETURN NULL;
END
$fn$;

CREATE FUNCTION payroll.accounting_journal_line_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    PERFORM payroll.accounting_journal_check(NEW.tenant_id, NEW.journal_id);
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER accounting_journal_exact AFTER INSERT ON payroll.accounting_journal
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.accounting_journal_exact();
CREATE CONSTRAINT TRIGGER accounting_journal_line_exact AFTER INSERT ON payroll.accounting_journal_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.accounting_journal_line_exact();

COMMENT ON TABLE payroll.accounting_mapping IS
    'MVP-096 append-only, effective-dated accounting mapping version (five account codes per legal entity and currency); restricted financial data';
COMMENT ON TABLE payroll.accounting_journal IS
    'MVP-096 immutable balanced accounting-journal header generated from one approved payroll package (an accounting-ready export: not posted, not proof of payment); restricted financial data';
COMMENT ON TABLE payroll.accounting_journal_line IS
    'MVP-096 immutable accounting-journal line (account code, direction, minor-unit amount, optional cost center and site code); no employee data';
