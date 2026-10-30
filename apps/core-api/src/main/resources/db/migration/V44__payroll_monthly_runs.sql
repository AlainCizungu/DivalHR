-- MVP-094A (Issue #177): a tenant administrator configures a legal entity's monthly payroll policy and
-- creates reproducible DRAFT payroll runs whose contractual gross comes from the effective-dated monthly
-- compensation of MVP-093A (decisions D94A-1 to D94A-13, ADR 0033). Additive to V43; the third increment
-- of the payroll module. First story of the payroll release train (094A, 094B rule pack, 094C approval).
--
-- Ownership: the payroll module; only the bounded com.divalhr.core.payroll package reads or writes these
-- objects. The keys to tenant.legal_entity are the documented modular-monolith integrity exception
-- (constraints, never reads), like V37, V43. Sources of a line (employment, placement, compensation)
-- are recorded by id and version only, with NO foreign key across the module boundary.
--
-- Classification: Restricted financial and HR data. Amounts, employee numbers with day counts, ranges
-- and digests never leave this schema except in authorized API responses; audit metadata, outbox
-- payloads, idempotency records, cursors, metrics, logs and error responses never carry them (D94A-9).
--
-- Shape:
--   * run_policy: append-only, effective-dated policy per legal entity (pay day, proration method).
--     One increasing sequence per entity (at most 60); distinct effective dates. Never updated or deleted.
--   * pay_run: one version of a run for (legal entity, closed calendar month, currency). Money in
--     integer minor units (CDF exponent 0, USD exponent 2); never floating point. Frozen copy of the
--     policy applied. Status DRAFT or SUPERSEDED; the sole legal update is DRAFT -> SUPERSEDED by the
--     next version of the same chain. Exactly one DRAFT per chain (partial unique index), at most 25
--     versions per chain. Never deleted or truncated.
--   * pay_run_line: one immutable line per employee. The database RECOMPUTES the contractual gross
--     from the monthly amount, the eligible days, the run's method and the month (HALF_UP, once), so a
--     line that disagrees with the policy cannot exist.
--   * pay_run_line_range: the maximal eligible date ranges of a line; their days equal eligible_days.
--   * pay_run_line_source: normalized source evidence (employment, placement, compensation) by id and
--     version.
--   * A deferred check makes the run's employee count and total equal its lines', and each chain
--     consistent (the latest version is the DRAFT, every earlier version is SUPERSEDED by its successor).
--
-- Rollback: db/rollback/V44__rollback.sql (manual, never run by Flyway) restores V43 exactly only while no
-- policy or run row exists; no override, no retention decision.

CREATE FUNCTION payroll.run_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_run_immutable',
        MESSAGE = 'payroll policy and run evidence is never deleted, truncated or updated';
END
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Policy (append-only, effective-dated)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.run_policy (
    tenant_id        uuid        NOT NULL,
    legal_entity_id  uuid        NOT NULL,
    id               uuid        NOT NULL,
    sequence         integer     NOT NULL,
    effective_from   date        NOT NULL,
    pay_day_kind     text        NOT NULL,
    pay_day          smallint,
    proration_method text        NOT NULL,
    created_at       timestamptz NOT NULL,
    created_by       text        NOT NULL,
    CONSTRAINT payroll_run_policy_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_run_policy_sequence_unique UNIQUE (tenant_id, legal_entity_id, sequence),
    CONSTRAINT payroll_run_policy_effective_unique UNIQUE (tenant_id, legal_entity_id, effective_from),
    CONSTRAINT payroll_run_policy_legal_entity
        FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT payroll_run_policy_sequence_range CHECK (sequence BETWEEN 1 AND 60),
    CONSTRAINT payroll_run_policy_effective_range
        CHECK (effective_from BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT payroll_run_policy_pay_day CHECK (
        (pay_day_kind = 'DAY' AND pay_day BETWEEN 1 AND 28)
        OR (pay_day_kind = 'LAST_DAY' AND pay_day IS NULL)),
    CONSTRAINT payroll_run_policy_method
        CHECK (proration_method IN ('CALENDAR_DAYS', 'FIXED_30_DAYS', 'NONE')),
    CONSTRAINT payroll_run_policy_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255)
);

CREATE FUNCTION payroll.run_policy_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
BEGIN
    -- One policy or run command per legal entity at a time keeps the sequence and the limit exact.
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'payroll.run:' || NEW.tenant_id::text || ':' || NEW.legal_entity_id::text, 0));
    SELECT max(sequence) INTO latest FROM payroll.run_policy
        WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id;
    IF NEW.sequence <> coalesce(latest, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_run_policy_sequence',
            MESSAGE = 'a payroll policy is appended with the next sequence of its legal entity';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_run_policy_guard BEFORE INSERT ON payroll.run_policy
    FOR EACH ROW EXECUTE FUNCTION payroll.run_policy_guard();
CREATE TRIGGER payroll_run_policy_immutable BEFORE UPDATE OR DELETE ON payroll.run_policy
    FOR EACH ROW EXECUTE FUNCTION payroll.run_immutable();
CREATE TRIGGER payroll_run_policy_no_truncate BEFORE TRUNCATE ON payroll.run_policy
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.run_immutable();

COMMENT ON TABLE payroll.run_policy IS
    'MVP-094A append-only effective-dated monthly payroll policy per legal entity (employer policy, not a compliance claim)';

-- ---------------------------------------------------------------------------------------------
-- Run (one version of a chain)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.pay_run (
    tenant_id           uuid        NOT NULL,
    legal_entity_id     uuid        NOT NULL,
    id                  uuid        NOT NULL,
    period_start        date        NOT NULL,
    currency            text        NOT NULL,
    version             integer     NOT NULL,
    status              text        NOT NULL,
    policy_id           uuid        NOT NULL,
    policy_sequence     integer     NOT NULL,
    pay_day_kind        text        NOT NULL,
    pay_day             smallint,
    proration_method    text        NOT NULL,
    employee_count      integer     NOT NULL,
    excluded_other_currency_count integer NOT NULL DEFAULT 0,
    total_gross_minor   bigint      NOT NULL,
    input_digest        char(64)    NOT NULL,
    created_at          timestamptz NOT NULL,
    created_by          text        NOT NULL,
    superseded_at       timestamptz,
    superseded_by_run_id uuid,
    CONSTRAINT payroll_pay_run_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_pay_run_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT payroll_pay_run_version_unique
        UNIQUE (tenant_id, legal_entity_id, period_start, currency, version),
    CONSTRAINT payroll_pay_run_legal_entity
        FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT payroll_pay_run_policy FOREIGN KEY (tenant_id, legal_entity_id, policy_id)
        REFERENCES payroll.run_policy (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_pay_run_successor FOREIGN KEY (tenant_id, legal_entity_id, superseded_by_run_id)
        REFERENCES payroll.pay_run (tenant_id, legal_entity_id, id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT payroll_pay_run_month CHECK (
        period_start = date_trunc('month', period_start)::date
        AND period_start BETWEEN DATE '2000-01-01' AND DATE '2999-12-01'),
    CONSTRAINT payroll_pay_run_currency CHECK (currency IN ('CDF', 'USD')),
    CONSTRAINT payroll_pay_run_version_range CHECK (version BETWEEN 1 AND 25),
    CONSTRAINT payroll_pay_run_status CHECK (status IN ('DRAFT', 'SUPERSEDED')),
    CONSTRAINT payroll_pay_run_supersession CHECK (
        (status = 'DRAFT' AND superseded_at IS NULL AND superseded_by_run_id IS NULL)
        OR (status = 'SUPERSEDED' AND superseded_at IS NOT NULL AND superseded_by_run_id IS NOT NULL
            AND superseded_by_run_id <> id)),
    CONSTRAINT payroll_pay_run_pay_day CHECK (
        (pay_day_kind = 'DAY' AND pay_day BETWEEN 1 AND 28)
        OR (pay_day_kind = 'LAST_DAY' AND pay_day IS NULL)),
    CONSTRAINT payroll_pay_run_method
        CHECK (proration_method IN ('CALENDAR_DAYS', 'FIXED_30_DAYS', 'NONE')),
    CONSTRAINT payroll_pay_run_employee_count CHECK (employee_count BETWEEN 1 AND 5000),
    CONSTRAINT payroll_pay_run_excluded_count CHECK (excluded_other_currency_count BETWEEN 0 AND 5000),
    -- 5,000 employees at the largest monthly amount of the currency (CDF 1,000,000,000; USD 10,000,000 minor).
    CONSTRAINT payroll_pay_run_total CHECK (
        total_gross_minor >= 0
        AND ((currency = 'CDF' AND total_gross_minor <= 5000000000000)
          OR (currency = 'USD' AND total_gross_minor <= 50000000000))),
    CONSTRAINT payroll_pay_run_digest CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_pay_run_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255)
);

-- Exactly one current draft per legal entity, month and currency. A recalculation first supersedes the
-- current draft (naming the successor id, whose foreign key is checked at commit), then inserts the successor.

CREATE UNIQUE INDEX payroll_pay_run_one_draft
    ON payroll.pay_run (tenant_id, legal_entity_id, period_start, currency) WHERE status = 'DRAFT';
-- History, newest period first (keyset: period_start DESC, currency, version DESC, id).
CREATE INDEX payroll_pay_run_history
    ON payroll.pay_run (tenant_id, legal_entity_id, period_start DESC, currency, version DESC, id);

CREATE FUNCTION payroll.pay_run_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest   integer;
    policy   payroll.run_policy%ROWTYPE;
    last_day date;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'payroll.run:' || NEW.tenant_id::text || ':' || NEW.legal_entity_id::text, 0));
        SELECT max(version) INTO latest FROM payroll.pay_run
            WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id
              AND period_start = NEW.period_start AND currency = NEW.currency;
        IF NEW.status <> 'DRAFT' OR NEW.version <> coalesce(latest, 0) + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_version',
                MESSAGE = 'a payroll run is created as the next DRAFT version of its chain';
        END IF;
        -- The frozen policy is the one in force on the last day of the month.
        last_day := (NEW.period_start + INTERVAL '1 month' - INTERVAL '1 day')::date;
        SELECT * INTO policy FROM payroll.run_policy
            WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id
              AND effective_from <= last_day
            ORDER BY effective_from DESC LIMIT 1;
        IF NOT FOUND OR policy.id <> NEW.policy_id OR policy.sequence <> NEW.policy_sequence
            OR policy.pay_day_kind <> NEW.pay_day_kind
            OR policy.pay_day IS DISTINCT FROM NEW.pay_day
            OR policy.proration_method <> NEW.proration_method THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_policy_in_force',
                MESSAGE = 'a payroll run freezes the policy in force on the last day of its month';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: the sole legal update supersedes a DRAFT by its successor, nothing else moves.
    IF OLD.status <> 'DRAFT' OR NEW.status <> 'SUPERSEDED'
        OR NEW.tenant_id <> OLD.tenant_id OR NEW.legal_entity_id <> OLD.legal_entity_id
        OR NEW.id <> OLD.id OR NEW.period_start <> OLD.period_start OR NEW.currency <> OLD.currency
        OR NEW.version <> OLD.version OR NEW.policy_id <> OLD.policy_id
        OR NEW.policy_sequence <> OLD.policy_sequence OR NEW.pay_day_kind <> OLD.pay_day_kind
        OR NEW.pay_day IS DISTINCT FROM OLD.pay_day OR NEW.proration_method <> OLD.proration_method
        OR NEW.employee_count <> OLD.employee_count
        OR NEW.excluded_other_currency_count <> OLD.excluded_other_currency_count
        OR NEW.total_gross_minor <> OLD.total_gross_minor
        OR NEW.input_digest <> OLD.input_digest OR NEW.created_at <> OLD.created_at
        OR NEW.created_by <> OLD.created_by THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_supersession_only',
            MESSAGE = 'a payroll run is only ever superseded, once, by its successor';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_pay_run_guard BEFORE INSERT OR UPDATE ON payroll.pay_run
    FOR EACH ROW EXECUTE FUNCTION payroll.pay_run_guard();
CREATE TRIGGER payroll_pay_run_no_delete BEFORE DELETE ON payroll.pay_run
    FOR EACH ROW EXECUTE FUNCTION payroll.run_immutable();
CREATE TRIGGER payroll_pay_run_no_truncate BEFORE TRUNCATE ON payroll.pay_run
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.run_immutable();

COMMENT ON TABLE payroll.pay_run IS
    'MVP-094A one version of a monthly payroll run (DRAFT contractual gross, not statutory payroll); restricted financial data';

-- ---------------------------------------------------------------------------------------------
-- Line (immutable; the database recomputes the contractual gross)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.pay_run_line (
    tenant_id         uuid     NOT NULL,
    run_id            uuid     NOT NULL,
    id                uuid     NOT NULL,
    employee_id       uuid     NOT NULL,
    employee_number   text     NOT NULL,
    eligible_days     smallint NOT NULL,
    denominator       smallint,
    full_month        boolean  NOT NULL,
    monthly_minor     bigint   NOT NULL,
    gross_minor       bigint   NOT NULL,
    explanation_codes text[]   NOT NULL,
    CONSTRAINT payroll_pay_run_line_pkey PRIMARY KEY (tenant_id, run_id, id),
    CONSTRAINT payroll_pay_run_line_employee_unique UNIQUE (tenant_id, run_id, employee_id),
    CONSTRAINT payroll_pay_run_line_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES payroll.pay_run (tenant_id, id),
    CONSTRAINT payroll_pay_run_line_days CHECK (eligible_days BETWEEN 1 AND 31),
    CONSTRAINT payroll_pay_run_line_denominator CHECK (
        (full_month AND denominator IS NULL) OR (NOT full_month AND denominator BETWEEN 28 AND 31)),
    CONSTRAINT payroll_pay_run_line_amounts CHECK (
        monthly_minor BETWEEN 1 AND 1000000000 AND gross_minor BETWEEN 0 AND monthly_minor
        AND (NOT full_month OR gross_minor = monthly_minor)),
    CONSTRAINT payroll_pay_run_line_number
        CHECK (char_length(employee_number) BETWEEN 1 AND 64 AND employee_number !~ '[[:cntrl:]]'),
    CONSTRAINT payroll_pay_run_line_explanation CHECK (
        cardinality(explanation_codes) BETWEEN 1 AND 7
        AND explanation_codes <@ ARRAY['FULL_MONTH', 'PRORATED_CALENDAR_DAYS', 'PRORATED_FIXED_30_DAYS',
            'CAPPED_AT_FULL_MONTH', 'STARTED_IN_MONTH', 'ENDED_IN_MONTH', 'MULTIPLE_PERIODS']::text[])
);

-- Keyset order of the run detail: employee number in byte order, then id.
CREATE INDEX payroll_pay_run_line_page
    ON payroll.pay_run_line (tenant_id, run_id, (employee_number COLLATE "C"), id);

CREATE FUNCTION payroll.pay_run_line_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    run      payroll.pay_run%ROWTYPE;
    dim      integer;
    capped   integer;
    den      integer;
    expected bigint;
BEGIN
    SELECT * INTO run FROM payroll.pay_run WHERE tenant_id = NEW.tenant_id AND id = NEW.run_id;
    IF NOT FOUND OR run.status <> 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_line_run_state',
            MESSAGE = 'a payroll line belongs to a run that is being created';
    END IF;
    dim := EXTRACT(DAY FROM (run.period_start + INTERVAL '1 month' - INTERVAL '1 day'))::integer;
    IF NEW.eligible_days > dim THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_line_month',
            MESSAGE = 'eligible days exceed the days of the month';
    END IF;
    -- Currency scale: CDF 1 to 1,000,000,000 francs; USD 1 to 10,000,000 minor units (0.01 to 100,000.00).
    IF run.currency = 'USD' AND NEW.monthly_minor > 10000000 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_line_scale',
            MESSAGE = 'monthly amount exceeds the USD range';
    END IF;
    IF NEW.full_month <> (NEW.eligible_days = dim) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_line_full_month',
            MESSAGE = 'a line is a full month exactly when every day of the month is eligible';
    END IF;
    IF NEW.full_month THEN
        expected := NEW.monthly_minor;
    ELSIF run.proration_method = 'CALENDAR_DAYS' THEN
        den := dim;
        expected := (2 * NEW.monthly_minor * NEW.eligible_days + den) / (2 * den);
    ELSIF run.proration_method = 'FIXED_30_DAYS' THEN
        den := 30;
        capped := LEAST(NEW.eligible_days, 30);
        expected := (2 * NEW.monthly_minor * capped + den) / (2 * den);
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_line_partial_none',
            MESSAGE = 'a partial month is not allowed under the NONE proration method';
    END IF;
    IF NOT NEW.full_month AND NEW.denominator IS DISTINCT FROM den THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_line_denominator_rule',
            MESSAGE = 'the denominator is the days of the month, or 30 under FIXED_30_DAYS';
    END IF;
    IF NEW.gross_minor <> expected THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_line_gross',
            MESSAGE = 'the contractual gross is the monthly amount prorated once, rounded HALF_UP';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_pay_run_line_guard BEFORE INSERT ON payroll.pay_run_line
    FOR EACH ROW EXECUTE FUNCTION payroll.pay_run_line_guard();
CREATE TRIGGER payroll_pay_run_line_immutable BEFORE UPDATE OR DELETE ON payroll.pay_run_line
    FOR EACH ROW EXECUTE FUNCTION payroll.run_immutable();
CREATE TRIGGER payroll_pay_run_line_no_truncate BEFORE TRUNCATE ON payroll.pay_run_line
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.run_immutable();

COMMENT ON TABLE payroll.pay_run_line IS
    'MVP-094A immutable contractual-gross line per employee; the gross is recomputed by a trigger; restricted financial and HR data';

-- ---------------------------------------------------------------------------------------------
-- Eligible ranges and source evidence of a line
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.pay_run_line_range (
    tenant_id  uuid     NOT NULL,
    run_id     uuid     NOT NULL,
    line_id    uuid     NOT NULL,
    seq        smallint NOT NULL,
    range_from date     NOT NULL,
    range_to   date     NOT NULL,
    CONSTRAINT payroll_pay_run_line_range_pkey PRIMARY KEY (tenant_id, run_id, line_id, seq),
    CONSTRAINT payroll_pay_run_line_range_line FOREIGN KEY (tenant_id, run_id, line_id)
        REFERENCES payroll.pay_run_line (tenant_id, run_id, id),
    CONSTRAINT payroll_pay_run_line_range_order CHECK (seq BETWEEN 1 AND 16 AND range_from <= range_to)
);

CREATE TABLE payroll.pay_run_line_source (
    tenant_id      uuid     NOT NULL,
    run_id         uuid     NOT NULL,
    line_id        uuid     NOT NULL,
    seq            smallint NOT NULL,
    kind           text     NOT NULL,
    source_id      uuid     NOT NULL,
    source_version bigint   NOT NULL,
    valid_from     date     NOT NULL,
    valid_to       date,
    CONSTRAINT payroll_pay_run_line_source_pkey PRIMARY KEY (tenant_id, run_id, line_id, seq),
    CONSTRAINT payroll_pay_run_line_source_line FOREIGN KEY (tenant_id, run_id, line_id)
        REFERENCES payroll.pay_run_line (tenant_id, run_id, id),
    CONSTRAINT payroll_pay_run_line_source_kind CHECK (kind IN ('EMPLOYMENT', 'PLACEMENT', 'COMPENSATION')),
    CONSTRAINT payroll_pay_run_line_source_shape CHECK (
        seq BETWEEN 1 AND 64 AND source_version >= 0 AND (valid_to IS NULL OR valid_to >= valid_from))
);

CREATE TRIGGER payroll_pay_run_line_range_immutable BEFORE UPDATE OR DELETE ON payroll.pay_run_line_range
    FOR EACH ROW EXECUTE FUNCTION payroll.run_immutable();
CREATE TRIGGER payroll_pay_run_line_range_no_truncate BEFORE TRUNCATE ON payroll.pay_run_line_range
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.run_immutable();
CREATE TRIGGER payroll_pay_run_line_source_immutable BEFORE UPDATE OR DELETE ON payroll.pay_run_line_source
    FOR EACH ROW EXECUTE FUNCTION payroll.run_immutable();
CREATE TRIGGER payroll_pay_run_line_source_no_truncate BEFORE TRUNCATE ON payroll.pay_run_line_source
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.run_immutable();

-- A line is judged with its ranges and sources at commit: the ranges are the maximal runs of the eligible
-- days (ordered, inside the month, separated by at least one day) and add up to eligible_days; there is
-- at least one employment, one placement and one compensation source.
CREATE FUNCTION payroll.pay_run_line_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    run        payroll.pay_run%ROWTYPE;
    first_day  date;
    last_day   date;
    total_days integer;
    ranges     integer;
    max_seq    integer;
    bad_order  integer;
BEGIN
    SELECT * INTO run FROM payroll.pay_run WHERE tenant_id = NEW.tenant_id AND id = NEW.run_id;
    first_day := run.period_start;
    last_day := (run.period_start + INTERVAL '1 month' - INTERVAL '1 day')::date;
    SELECT count(*), coalesce(max(seq), 0), coalesce(sum(range_to - range_from + 1), 0)
        INTO ranges, max_seq, total_days
        FROM payroll.pay_run_line_range
        WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id AND line_id = NEW.id;
    IF ranges = 0 OR max_seq <> ranges OR total_days <> NEW.eligible_days THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_line_ranges_exact',
            MESSAGE = 'the eligible ranges of a line must be numbered from 1 and add up to its eligible days';
    END IF;
    SELECT count(*) INTO bad_order FROM (
        SELECT range_from, range_to, seq,
               lag(range_to) OVER (ORDER BY seq) AS previous_to
        FROM payroll.pay_run_line_range
        WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id AND line_id = NEW.id) r
        WHERE range_from < first_day OR range_to > last_day
           OR (previous_to IS NOT NULL AND range_from <= previous_to + 1);
    IF bad_order <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_line_ranges_order',
            MESSAGE = 'eligible ranges lie inside the month, in order, separated by at least one day';
    END IF;
    IF (SELECT count(DISTINCT kind) FROM payroll.pay_run_line_source
            WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id AND line_id = NEW.id) <> 3 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_line_sources_exact',
            MESSAGE = 'a line records at least one employment, one placement and one compensation source';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_pay_run_line_exact AFTER INSERT ON payroll.pay_run_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.pay_run_line_exact();

-- A run is judged with its lines and its chain at commit: counts and total equal its lines'; the latest
-- version of the chain is the only DRAFT and every earlier version is SUPERSEDED by its successor.
CREATE FUNCTION payroll.pay_run_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    lines      integer;
    total      bigint;
    latest     integer;
    broken     integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT count(*), coalesce(sum(gross_minor), 0) INTO lines, total FROM payroll.pay_run_line
            WHERE tenant_id = NEW.tenant_id AND run_id = NEW.id;
        IF lines <> NEW.employee_count OR total <> NEW.total_gross_minor THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_exact',
                MESSAGE = 'a payroll run''s employee count and total equal those of its lines';
        END IF;
    END IF;
    SELECT max(version) INTO latest FROM payroll.pay_run
        WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id
          AND period_start = NEW.period_start AND currency = NEW.currency;
    SELECT count(*) INTO broken FROM payroll.pay_run r
        LEFT JOIN payroll.pay_run s ON s.tenant_id = r.tenant_id AND s.legal_entity_id = r.legal_entity_id
            AND s.period_start = r.period_start AND s.currency = r.currency AND s.version = r.version + 1
        WHERE r.tenant_id = NEW.tenant_id AND r.legal_entity_id = NEW.legal_entity_id
          AND r.period_start = NEW.period_start AND r.currency = NEW.currency
          AND ((r.version = latest AND r.status <> 'DRAFT')
            OR (r.version < latest AND (r.status <> 'SUPERSEDED' OR r.superseded_by_run_id IS DISTINCT FROM s.id)));
    IF broken <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_pay_run_chain',
            MESSAGE = 'a payroll run chain has one DRAFT, its latest version, and supersedes every earlier one';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_pay_run_exact AFTER INSERT OR UPDATE ON payroll.pay_run
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.pay_run_exact();
