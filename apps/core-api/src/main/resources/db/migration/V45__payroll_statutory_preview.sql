-- MVP-094B (Issue #179): a source-versioned DRC statutory payroll PREVIEW over a frozen MVP-094A run
-- (decisions D94B-1 to D94B-14, ADR 0034). Additive to V44; the fourth increment of the payroll module.
-- A preview is not a payslip, a payment, an approval, a filing or a claim of legal compliance.
--
-- Ownership: the payroll module; only the bounded com.divalhr.core.payroll package reads or writes these
-- objects. The keys to tenant.legal_entity, tenant.organization and people.employee are the documented
-- modular-monolith integrity exception (constraints, never reads), like V37, V43, V44. The calculation
-- refers to payroll.pay_run and payroll.pay_run_line (same module) by composite foreign keys.
--
-- Classification: Restricted financial and HR data. Amounts, employee numbers, profile flags and
-- classifications, digests and traces never leave this schema except in authorized API responses; audit
-- metadata, outbox payloads, idempotency records, cursors, metrics, logs and error responses never carry
-- them (D94B-11). Rule-pack sources are public; reviewer identity and the attestation are restricted
-- operational evidence.
--
-- Shape:
--   * statutory_rule_pack: platform-owned, immutable, effective-dated rule pack (no tenant column).
--     Created DRAFT; DRAFT -> VERIFIED -> RETIRED only; rules, interval and identity never change; the
--     verification evidence is set once, by the DRAFT -> VERIFIED update. rules_digest is GENERATED
--     (sha256 of the jsonb text) so it can never disagree with the rules. A VERIFIED or RETIRED pack
--     needs reviewer, organization, review date, attestation, at least one source, every source hashed
--     (a DEFERRABLE INITIALLY DEFERRED constraint trigger), and every component's source key among its
--     sources. No delete, no truncate. SEED: exactly one pack, CD-SALARY-PREVIEW v1, DRAFT, from the
--     published reference values; no sha256, no reviewer, never VERIFIED here.
--   * statutory_rule_pack_source: the official sources of a pack (closed keys, https on an allow-listed
--     official host only). Immutable; sources can only be added while the pack is DRAFT. A hash cannot be
--     added later, so a verified pack is a new version whose sources carry their sha256.
--   * statutory_activation: append-only activation of a VERIFIED pack for a legal entity from a month.
--     One increasing sequence per entity (at most 60); one activation per (entity, from month).
--   * statutory_profile: append-only effective-dated employee profile (country CD, classification,
--     CNSS and IRPP subject flags, evidence code). One increasing sequence per employee (at most 100);
--     an UNSUPPORTED profile has both flags false. No identifier, no free text.
--   * statutory_calculation: one version of a calculation for (source run, pack). The sole legal update
--     is DRAFT -> SUPERSEDED by the next version. At most one DRAFT per (tenant, run, pack) (partial
--     unique index), at most 25 versions. CDF only. Totals and counts are checked against the lines
--     and the source run at commit (deferred).
--   * statutory_calculation_line / statutory_calculation_component: immutable evidence per employee and
--     rule; the arithmetic net = gross - deductions and employer cost = gross + contributions, the
--     component sums and the identity of every component with the pack's rule are enforced.
--
-- Indexes (queries of ADR 0034 sections 4 to 6):
--   * activation in effect for an entity and month (greatest from_month <= M, then greatest sequence):
--     served backwards by the unique key (tenant_id, legal_entity_id, from_month); the entity's
--     activation count/version by its sequence key; payroll_statutory_activation_pack for the pack FK.
--   * profile in force on a day (greatest effective_from <= day, then greatest sequence) and the
--     timeline of an employee: payroll_statutory_profile_timeline.
--   * calculation chain of a run and history paging (version DESC, id): payroll_statutory_calculation_history;
--     the current draft: payroll_statutory_calculation_one_draft; payroll_statutory_calculation_pack and
--     payroll_statutory_calculation_activation back their foreign keys.
--   * line paging by employee number in byte order, then id: payroll_statutory_calculation_line_page.
--
-- Privileges: like V43 and V44 this migration issues no GRANT or REVOKE (the application role owns the
-- schema objects). The authority of the tables is enforced by the triggers below (the rule pack and its
-- sources can only be inserted as DRAFT and moved forward by the operational procedure; evidence is never
-- updated or deleted) and the application has no write path to the rule pack tables (ADR 0034 section 2).
--
-- Rollback: db/rollback/V45__rollback.sql (manual, never run by Flyway) restores V44 exactly only while
-- no activation, profile or calculation row exists and the rule pack tables hold nothing but the
-- unverified seed; it never deletes evidence; no override, no retention decision.

CREATE FUNCTION payroll.statutory_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_immutable',
        MESSAGE = 'statutory preview evidence is never deleted, truncated or updated';
END
$fn$;

-- The digest of a pack's rules: lower-case hex SHA-256 of the canonical jsonb text. IMMUTABLE so that it can
-- generate a stored column: the textual form of a jsonb value is deterministic for one PostgreSQL major version
-- and the column is generated again by any dump and restore.
CREATE FUNCTION payroll.statutory_rules_digest(rules jsonb) RETURNS char(64)
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$fn$
    SELECT encode(sha256(convert_to(rules::text, 'UTF8')), 'hex')::char(64)
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Rule pack (platform-owned, immutable)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_rule_pack (
    id                    uuid        NOT NULL,
    code                  text        NOT NULL,
    version               integer     NOT NULL,
    country               text        NOT NULL,
    currency              text        NOT NULL,
    status                text        NOT NULL,
    effective_from        date        NOT NULL,
    effective_to          date,
    method_version        text        NOT NULL,
    rules                 jsonb       NOT NULL,
    rules_digest          char(64)    GENERATED ALWAYS AS (payroll.statutory_rules_digest(rules)) STORED,
    reviewer_name         text,
    reviewer_organization text,
    reviewed_on           date,
    attested              boolean     NOT NULL DEFAULT false,
    verified_at           timestamptz,
    retired_at            timestamptz,
    created_at            timestamptz NOT NULL,
    CONSTRAINT payroll_statutory_rule_pack_pkey PRIMARY KEY (id),
    CONSTRAINT payroll_statutory_rule_pack_code_version_unique UNIQUE (code, version),
    CONSTRAINT payroll_statutory_rule_pack_code CHECK (code ~ '^[A-Z][A-Z0-9-]{2,63}$'),
    CONSTRAINT payroll_statutory_rule_pack_version_range CHECK (version BETWEEN 1 AND 1000),
    CONSTRAINT payroll_statutory_rule_pack_country CHECK (country = 'CD'),
    CONSTRAINT payroll_statutory_rule_pack_currency CHECK (currency = 'CDF'),
    CONSTRAINT payroll_statutory_rule_pack_status CHECK (status IN ('DRAFT', 'VERIFIED', 'RETIRED')),
    CONSTRAINT payroll_statutory_rule_pack_interval CHECK (
        effective_from BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL
             OR (effective_to >= effective_from AND effective_to <= DATE '2999-12-31'))),
    CONSTRAINT payroll_statutory_rule_pack_method CHECK (method_version = '1'),
    CONSTRAINT payroll_statutory_rule_pack_rules CHECK (coalesce(
        jsonb_typeof(rules) = 'object'
        AND rules -> 'schemaVersion' = '1'::jsonb
        AND jsonb_typeof(rules -> 'components') = 'array'
        AND jsonb_array_length(rules -> 'components') BETWEEN 1 AND 12, false)),
    -- Every branch tests the evidence for NULL explicitly: a CHECK that evaluates to NULL would pass.
    CONSTRAINT payroll_statutory_rule_pack_evidence CHECK (
        (status = 'DRAFT'
            AND reviewer_name IS NULL AND reviewer_organization IS NULL AND reviewed_on IS NULL
            AND NOT attested AND verified_at IS NULL AND retired_at IS NULL)
        OR (status IN ('VERIFIED', 'RETIRED')
            AND reviewer_name IS NOT NULL AND char_length(reviewer_name) BETWEEN 1 AND 200
            AND reviewer_organization IS NOT NULL AND char_length(reviewer_organization) BETWEEN 1 AND 200
            AND reviewed_on IS NOT NULL AND reviewed_on BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
            AND attested AND verified_at IS NOT NULL
            AND ((status = 'VERIFIED' AND retired_at IS NULL)
                 OR (status = 'RETIRED' AND retired_at IS NOT NULL))))
);

CREATE FUNCTION payroll.statutory_rule_pack_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_forward',
                MESSAGE = 'a rule pack is created DRAFT and moves DRAFT -> VERIFIED -> RETIRED only';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: identity, interval, method, rules and creation never change.
    IF NEW.id <> OLD.id OR NEW.code <> OLD.code OR NEW.version <> OLD.version
        OR NEW.country <> OLD.country OR NEW.currency <> OLD.currency
        OR NEW.effective_from <> OLD.effective_from
        OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
        OR NEW.method_version <> OLD.method_version
        OR NEW.rules IS DISTINCT FROM OLD.rules OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_immutable',
            MESSAGE = 'a rule pack''s identity, interval, method and rules never change: correct it as a new version';
    END IF;
    -- The verification evidence is set by DRAFT -> VERIFIED only and never changes afterwards.
    IF OLD.status IN ('VERIFIED', 'RETIRED') AND (
        NEW.reviewer_name IS DISTINCT FROM OLD.reviewer_name
        OR NEW.reviewer_organization IS DISTINCT FROM OLD.reviewer_organization
        OR NEW.reviewed_on IS DISTINCT FROM OLD.reviewed_on
        OR NEW.attested <> OLD.attested
        OR NEW.verified_at IS DISTINCT FROM OLD.verified_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'payroll_statutory_rule_pack_evidence_immutable',
            MESSAGE = 'the verification evidence of a rule pack never changes once VERIFIED';
    END IF;
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status = 'VERIFIED')
            OR (OLD.status = 'VERIFIED' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_forward',
            MESSAGE = 'a rule pack moves DRAFT -> VERIFIED -> RETIRED only';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_statutory_rule_pack_guard BEFORE INSERT OR UPDATE ON payroll.statutory_rule_pack
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_rule_pack_guard();
CREATE TRIGGER payroll_statutory_rule_pack_no_delete BEFORE DELETE ON payroll.statutory_rule_pack
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_immutable();
CREATE TRIGGER payroll_statutory_rule_pack_no_truncate BEFORE TRUNCATE ON payroll.statutory_rule_pack
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_immutable();

COMMENT ON TABLE payroll.statutory_rule_pack IS
    'MVP-094B platform-owned immutable DRC statutory rule pack (DRAFT, VERIFIED, RETIRED); reviewer evidence is restricted';

-- ---------------------------------------------------------------------------------------------
-- Official sources of a pack
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_rule_pack_source (
    pack_id      uuid     NOT NULL,
    seq          smallint NOT NULL,
    source_key   text     NOT NULL,
    title        text     NOT NULL,
    url          text     NOT NULL,
    sha256       char(64),
    retrieved_on date     NOT NULL,
    CONSTRAINT payroll_statutory_rule_pack_source_pkey PRIMARY KEY (pack_id, seq),
    CONSTRAINT payroll_statutory_rule_pack_source_key_unique UNIQUE (pack_id, source_key),
    CONSTRAINT payroll_statutory_rule_pack_source_pack
        FOREIGN KEY (pack_id) REFERENCES payroll.statutory_rule_pack (id),
    CONSTRAINT payroll_statutory_rule_pack_source_seq CHECK (seq BETWEEN 1 AND 6),
    CONSTRAINT payroll_statutory_rule_pack_source_key CHECK (source_key IN
        ('DGI_IRPP', 'DGI_NOTICE_2026', 'DGI_PORTAL', 'CNSS_RATES', 'CNSS_DECLARATION', 'BUDGET_2026')),
    CONSTRAINT payroll_statutory_rule_pack_source_title
        CHECK (char_length(title) BETWEEN 1 AND 200 AND title !~ '[[:cntrl:]]'),
    -- https only, no user information, no port, on one of the allow-listed official hosts.
    CONSTRAINT payroll_statutory_rule_pack_source_url CHECK (
        char_length(url) <= 500
        AND url ~ '^https://(dgi\.gouv\.cd|desec\.dgi\.gouv\.cd|cnss\.cd|budget\.gouv\.cd)(/[^[:space:][:cntrl:]\\]*)?$'),
    CONSTRAINT payroll_statutory_rule_pack_source_sha256 CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_statutory_rule_pack_source_retrieved
        CHECK (retrieved_on BETWEEN DATE '2000-01-01' AND DATE '2999-12-31')
);

CREATE FUNCTION payroll.statutory_rule_pack_source_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    pack_status text;
BEGIN
    SELECT status INTO pack_status FROM payroll.statutory_rule_pack WHERE id = NEW.pack_id;
    -- An unknown pack is left to the foreign key.
    IF FOUND AND pack_status <> 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_source_state',
            MESSAGE = 'sources are recorded while the rule pack is DRAFT; a correction is a new pack version';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_statutory_rule_pack_source_guard BEFORE INSERT ON payroll.statutory_rule_pack_source
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_rule_pack_source_guard();
CREATE TRIGGER payroll_statutory_rule_pack_source_immutable
    BEFORE UPDATE OR DELETE ON payroll.statutory_rule_pack_source
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_immutable();
CREATE TRIGGER payroll_statutory_rule_pack_source_no_truncate
    BEFORE TRUNCATE ON payroll.statutory_rule_pack_source
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_immutable();

-- A pack is judged with its sources at commit: VERIFIED or RETIRED needs at least one source, every source
-- with a sha256 (the retrieval date is NOT NULL), and every component's source key among its sources.
CREATE FUNCTION payroll.statutory_rule_pack_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    sources   integer;
    unhashed  integer;
    unknown   integer;
BEGIN
    IF NEW.status = 'DRAFT' THEN
        RETURN NULL;
    END IF;
    SELECT count(*), count(*) FILTER (WHERE sha256 IS NULL OR retrieved_on IS NULL)
        INTO sources, unhashed FROM payroll.statutory_rule_pack_source WHERE pack_id = NEW.id;
    IF sources = 0 OR unhashed <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_sources',
            MESSAGE = 'a verified rule pack has at least one source and every source has a sha256 and a retrieval date';
    END IF;
    SELECT count(*) INTO unknown FROM jsonb_array_elements(NEW.rules -> 'components') component
        WHERE NOT EXISTS (SELECT 1 FROM payroll.statutory_rule_pack_source s
                          WHERE s.pack_id = NEW.id AND s.source_key = component ->> 'sourceKey');
    IF unknown <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_source_keys',
            MESSAGE = 'every component of a verified rule pack cites one of its sources';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_statutory_rule_pack_exact AFTER INSERT OR UPDATE ON payroll.statutory_rule_pack
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.statutory_rule_pack_exact();

COMMENT ON TABLE payroll.statutory_rule_pack_source IS
    'MVP-094B official source of a rule pack (allow-listed https host, sha256 and retrieval date); immutable';

-- ---------------------------------------------------------------------------------------------
-- Activation of a pack for a legal entity (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_activation (
    tenant_id       uuid        NOT NULL,
    legal_entity_id uuid        NOT NULL,
    id              uuid        NOT NULL,
    sequence        integer     NOT NULL,
    pack_id         uuid        NOT NULL,
    from_month      date        NOT NULL,
    acknowledged    boolean     NOT NULL,
    activated_at    timestamptz NOT NULL,
    activated_by    text        NOT NULL,
    CONSTRAINT payroll_statutory_activation_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_statutory_activation_sequence_unique UNIQUE (tenant_id, legal_entity_id, sequence),
    CONSTRAINT payroll_statutory_activation_month_unique UNIQUE (tenant_id, legal_entity_id, from_month),
    CONSTRAINT payroll_statutory_activation_legal_entity
        FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT payroll_statutory_activation_pack
        FOREIGN KEY (pack_id) REFERENCES payroll.statutory_rule_pack (id),
    CONSTRAINT payroll_statutory_activation_sequence_range CHECK (sequence BETWEEN 1 AND 60),
    CONSTRAINT payroll_statutory_activation_month CHECK (
        from_month = date_trunc('month', from_month)::date
        AND from_month BETWEEN DATE '2000-01-01' AND DATE '2999-12-01'),
    CONSTRAINT payroll_statutory_activation_acknowledged CHECK (acknowledged),
    CONSTRAINT payroll_statutory_activation_activated_by_length
        CHECK (char_length(activated_by) BETWEEN 1 AND 255)
);

CREATE INDEX payroll_statutory_activation_pack ON payroll.statutory_activation (pack_id);

CREATE FUNCTION payroll.statutory_activation_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
    pack   payroll.statutory_rule_pack%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'payroll.statutory-activation:' || NEW.tenant_id::text || ':' || NEW.legal_entity_id::text, 0));
    SELECT max(sequence) INTO latest FROM payroll.statutory_activation
        WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id;
    IF NEW.sequence <> coalesce(latest, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_activation_sequence',
            MESSAGE = 'a statutory activation is appended with the next sequence of its legal entity';
    END IF;
    SELECT * INTO pack FROM payroll.statutory_rule_pack WHERE id = NEW.pack_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key refuses an unknown pack
    END IF;
    IF pack.status <> 'VERIFIED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_activation_pack_verified',
            MESSAGE = 'only a VERIFIED rule pack can be activated';
    END IF;
    IF NEW.from_month < pack.effective_from
        OR (pack.effective_to IS NOT NULL AND NEW.from_month > pack.effective_to) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_activation_interval',
            MESSAGE = 'the first month of an activation lies inside the interval of the rule pack';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_statutory_activation_guard BEFORE INSERT ON payroll.statutory_activation
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_activation_guard();
CREATE TRIGGER payroll_statutory_activation_immutable BEFORE UPDATE OR DELETE ON payroll.statutory_activation
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_immutable();
CREATE TRIGGER payroll_statutory_activation_no_truncate BEFORE TRUNCATE ON payroll.statutory_activation
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_immutable();

COMMENT ON TABLE payroll.statutory_activation IS
    'MVP-094B append-only activation of a VERIFIED statutory rule pack for a legal entity from a month; the employer remains responsible for legal review';

-- ---------------------------------------------------------------------------------------------
-- Employee statutory profile (append-only, effective-dated)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_profile (
    tenant_id      uuid        NOT NULL,
    employee_id    uuid        NOT NULL,
    id             uuid        NOT NULL,
    sequence       integer     NOT NULL,
    effective_from date        NOT NULL,
    country        text        NOT NULL,
    classification text        NOT NULL,
    cnss_subject   boolean     NOT NULL,
    irpp_subject   boolean     NOT NULL,
    evidence_code  text        NOT NULL,
    created_at     timestamptz NOT NULL,
    created_by     text        NOT NULL,
    CONSTRAINT payroll_statutory_profile_pkey PRIMARY KEY (tenant_id, employee_id, id),
    CONSTRAINT payroll_statutory_profile_sequence_unique UNIQUE (tenant_id, employee_id, sequence),
    CONSTRAINT payroll_statutory_profile_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT payroll_statutory_profile_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT payroll_statutory_profile_sequence_range CHECK (sequence BETWEEN 1 AND 100),
    CONSTRAINT payroll_statutory_profile_effective_range
        CHECK (effective_from BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT payroll_statutory_profile_country CHECK (country = 'CD'),
    CONSTRAINT payroll_statutory_profile_classification
        CHECK (classification IN ('STANDARD_RESIDENT', 'UNSUPPORTED')),
    CONSTRAINT payroll_statutory_profile_unsupported
        CHECK (classification <> 'UNSUPPORTED' OR (NOT cnss_subject AND NOT irpp_subject)),
    CONSTRAINT payroll_statutory_profile_evidence CHECK (evidence_code IN
        ('EMPLOYMENT_CONTRACT', 'PAYROLL_FILE_REVIEW', 'EMPLOYER_DECLARATION', 'PROFESSIONAL_REVIEW')),
    CONSTRAINT payroll_statutory_profile_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255)
);

-- Profile in force on a day: the greatest effective_from not after it, then the greatest sequence.
CREATE INDEX payroll_statutory_profile_timeline
    ON payroll.statutory_profile (tenant_id, employee_id, effective_from DESC, sequence DESC);

CREATE FUNCTION payroll.statutory_profile_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest integer;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'payroll.statutory-profile:' || NEW.tenant_id::text || ':' || NEW.employee_id::text, 0));
    SELECT max(sequence) INTO latest FROM payroll.statutory_profile
        WHERE tenant_id = NEW.tenant_id AND employee_id = NEW.employee_id;
    IF NEW.sequence <> coalesce(latest, 0) + 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_profile_sequence',
            MESSAGE = 'a statutory profile is appended with the next sequence of its employee';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_statutory_profile_guard BEFORE INSERT ON payroll.statutory_profile
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_profile_guard();
CREATE TRIGGER payroll_statutory_profile_immutable BEFORE UPDATE OR DELETE ON payroll.statutory_profile
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_immutable();
CREATE TRIGGER payroll_statutory_profile_no_truncate BEFORE TRUNCATE ON payroll.statutory_profile
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_immutable();

COMMENT ON TABLE payroll.statutory_profile IS
    'MVP-094B append-only effective-dated employee statutory profile (classification, CNSS and IRPP flags); restricted HR data, no identifiers or free text';

-- ---------------------------------------------------------------------------------------------
-- Calculation (one version of a chain)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_calculation (
    tenant_id                        uuid        NOT NULL,
    legal_entity_id                  uuid        NOT NULL,
    id                               uuid        NOT NULL,
    run_id                           uuid        NOT NULL,
    run_version                      integer     NOT NULL,
    period_start                     date        NOT NULL,
    currency                         text        NOT NULL,
    pack_id                          uuid        NOT NULL,
    activation_id                    uuid        NOT NULL,
    version                          integer     NOT NULL,
    status                           text        NOT NULL,
    supported_count                  integer     NOT NULL,
    excluded_count                   integer     NOT NULL,
    blocked_count                    integer     NOT NULL DEFAULT 0,
    gross_total_minor                bigint      NOT NULL,
    employee_deductions_total_minor  bigint      NOT NULL,
    employer_contributions_total_minor bigint    NOT NULL,
    preview_net_total_minor          bigint      NOT NULL,
    employer_cost_total_minor        bigint      NOT NULL,
    source_run_digest                char(64)    NOT NULL,
    profile_digest                   char(64)    NOT NULL,
    rule_pack_digest                 char(64)    NOT NULL,
    calculation_digest               char(64)    NOT NULL,
    created_at                       timestamptz NOT NULL,
    created_by                       text        NOT NULL,
    superseded_at                    timestamptz,
    superseded_by_id                 uuid,
    CONSTRAINT payroll_statutory_calculation_pkey PRIMARY KEY (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_statutory_calculation_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT payroll_statutory_calculation_version_unique UNIQUE (tenant_id, run_id, pack_id, version),
    CONSTRAINT payroll_statutory_calculation_legal_entity
        FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT payroll_statutory_calculation_run FOREIGN KEY (tenant_id, legal_entity_id, run_id)
        REFERENCES payroll.pay_run (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_statutory_calculation_pack
        FOREIGN KEY (pack_id) REFERENCES payroll.statutory_rule_pack (id),
    CONSTRAINT payroll_statutory_calculation_activation FOREIGN KEY (tenant_id, legal_entity_id, activation_id)
        REFERENCES payroll.statutory_activation (tenant_id, legal_entity_id, id),
    CONSTRAINT payroll_statutory_calculation_successor FOREIGN KEY (tenant_id, legal_entity_id, superseded_by_id)
        REFERENCES payroll.statutory_calculation (tenant_id, legal_entity_id, id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT payroll_statutory_calculation_month CHECK (
        period_start = date_trunc('month', period_start)::date
        AND period_start BETWEEN DATE '2000-01-01' AND DATE '2999-12-01'),
    CONSTRAINT payroll_statutory_calculation_currency CHECK (currency = 'CDF'),
    CONSTRAINT payroll_statutory_calculation_run_version_range CHECK (run_version BETWEEN 1 AND 25),
    CONSTRAINT payroll_statutory_calculation_version_range CHECK (version BETWEEN 1 AND 25),
    CONSTRAINT payroll_statutory_calculation_status CHECK (status IN ('DRAFT', 'SUPERSEDED')),
    CONSTRAINT payroll_statutory_calculation_supersession CHECK (
        (status = 'DRAFT' AND superseded_at IS NULL AND superseded_by_id IS NULL)
        OR (status = 'SUPERSEDED' AND superseded_at IS NOT NULL AND superseded_by_id IS NOT NULL
            AND superseded_by_id <> id)),
    -- A calculation with a blocked employee is never committed: lines are the supported and excluded ones.
    CONSTRAINT payroll_statutory_calculation_counts CHECK (
        supported_count BETWEEN 0 AND 5000 AND excluded_count BETWEEN 0 AND 5000
        AND blocked_count = 0 AND supported_count + excluded_count BETWEEN 1 AND 5000),
    CONSTRAINT payroll_statutory_calculation_totals CHECK (
        gross_total_minor BETWEEN 0 AND 5000000000000
        AND employee_deductions_total_minor BETWEEN 0 AND gross_total_minor
        AND employer_contributions_total_minor >= 0),
    CONSTRAINT payroll_statutory_calculation_arithmetic CHECK (
        preview_net_total_minor = gross_total_minor - employee_deductions_total_minor
        AND employer_cost_total_minor = gross_total_minor + employer_contributions_total_minor),
    CONSTRAINT payroll_statutory_calculation_digests CHECK (
        source_run_digest ~ '^[0-9a-f]{64}$' AND profile_digest ~ '^[0-9a-f]{64}$'
        AND rule_pack_digest ~ '^[0-9a-f]{64}$' AND calculation_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_statutory_calculation_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255)
);

-- Exactly one current draft per source run and pack. A recalculation first supersedes the current draft
-- (naming the successor id, whose foreign key is checked at commit), then inserts the successor.
CREATE UNIQUE INDEX payroll_statutory_calculation_one_draft
    ON payroll.statutory_calculation (tenant_id, run_id, pack_id) WHERE status = 'DRAFT';
-- History of a run's calculations, newest version first (keyset: version DESC, id).
CREATE INDEX payroll_statutory_calculation_history
    ON payroll.statutory_calculation (tenant_id, legal_entity_id, run_id, version DESC, id);
CREATE INDEX payroll_statutory_calculation_pack ON payroll.statutory_calculation (pack_id);
CREATE INDEX payroll_statutory_calculation_activation
    ON payroll.statutory_calculation (tenant_id, legal_entity_id, activation_id);

CREATE FUNCTION payroll.statutory_calculation_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    latest   integer;
    run      payroll.pay_run%ROWTYPE;
    pack     payroll.statutory_rule_pack%ROWTYPE;
    act      payroll.statutory_activation%ROWTYPE;
    last_day date;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(
            'payroll.statutory-calculation:' || NEW.tenant_id::text || ':' || NEW.legal_entity_id::text, 0));
        SELECT max(version) INTO latest FROM payroll.statutory_calculation
            WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id AND pack_id = NEW.pack_id;
        IF NEW.status <> 'DRAFT' OR NEW.version <> coalesce(latest, 0) + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_version',
                MESSAGE = 'a statutory calculation is created as the next DRAFT version of its chain';
        END IF;
        SELECT * INTO run FROM payroll.pay_run WHERE tenant_id = NEW.tenant_id AND id = NEW.run_id;
        SELECT * INTO pack FROM payroll.statutory_rule_pack WHERE id = NEW.pack_id;
        SELECT * INTO act FROM payroll.statutory_activation
            WHERE tenant_id = NEW.tenant_id AND legal_entity_id = NEW.legal_entity_id AND id = NEW.activation_id;
        IF NOT (FOUND AND run.id IS NOT NULL AND pack.id IS NOT NULL) THEN
            RETURN NEW; -- a missing run, pack or activation is refused by its foreign key
        END IF;
        -- The source is the current DRAFT of its chain, of this tenant and entity, in CDF.
        IF run.legal_entity_id <> NEW.legal_entity_id OR run.status <> 'DRAFT'
            OR run.version <> NEW.run_version OR run.period_start <> NEW.period_start
            OR run.currency <> NEW.currency THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_run_state',
                MESSAGE = 'a statutory calculation is made on the current CDF DRAFT run of its legal entity';
        END IF;
        IF pack.status NOT IN ('VERIFIED', 'RETIRED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_pack_state',
                MESSAGE = 'a statutory calculation uses a VERIFIED or RETIRED rule pack, never a DRAFT one';
        END IF;
        last_day := (NEW.period_start + INTERVAL '1 month' - INTERVAL '1 day')::date;
        IF NEW.period_start < pack.effective_from
            OR (pack.effective_to IS NOT NULL AND last_day > pack.effective_to) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_pack_interval',
                MESSAGE = 'the rule pack covers the whole month of the calculation';
        END IF;
        IF act.pack_id <> NEW.pack_id OR act.from_month > NEW.period_start THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_activation_rule',
                MESSAGE = 'the activation names the pack and starts on or before the month of the calculation';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: the sole legal update supersedes a DRAFT by its successor, nothing else moves.
    IF OLD.status <> 'DRAFT' OR NEW.status <> 'SUPERSEDED'
        OR NEW.tenant_id <> OLD.tenant_id OR NEW.legal_entity_id <> OLD.legal_entity_id
        OR NEW.id <> OLD.id OR NEW.run_id <> OLD.run_id OR NEW.run_version <> OLD.run_version
        OR NEW.period_start <> OLD.period_start OR NEW.currency <> OLD.currency
        OR NEW.pack_id <> OLD.pack_id OR NEW.activation_id <> OLD.activation_id
        OR NEW.version <> OLD.version
        OR NEW.supported_count <> OLD.supported_count OR NEW.excluded_count <> OLD.excluded_count
        OR NEW.blocked_count <> OLD.blocked_count
        OR NEW.gross_total_minor <> OLD.gross_total_minor
        OR NEW.employee_deductions_total_minor <> OLD.employee_deductions_total_minor
        OR NEW.employer_contributions_total_minor <> OLD.employer_contributions_total_minor
        OR NEW.preview_net_total_minor <> OLD.preview_net_total_minor
        OR NEW.employer_cost_total_minor <> OLD.employer_cost_total_minor
        OR NEW.source_run_digest <> OLD.source_run_digest OR NEW.profile_digest <> OLD.profile_digest
        OR NEW.rule_pack_digest <> OLD.rule_pack_digest
        OR NEW.calculation_digest <> OLD.calculation_digest
        OR NEW.created_at <> OLD.created_at OR NEW.created_by <> OLD.created_by THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_supersession_only',
            MESSAGE = 'a statutory calculation is only ever superseded, once, by its successor';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_statutory_calculation_guard BEFORE INSERT OR UPDATE ON payroll.statutory_calculation
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_calculation_guard();
CREATE TRIGGER payroll_statutory_calculation_no_delete BEFORE DELETE ON payroll.statutory_calculation
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_immutable();
CREATE TRIGGER payroll_statutory_calculation_no_truncate BEFORE TRUNCATE ON payroll.statutory_calculation
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_immutable();

COMMENT ON TABLE payroll.statutory_calculation IS
    'MVP-094B one version of a DRC statutory preview of a frozen payroll run (not a payslip or payment); restricted financial data';

-- ---------------------------------------------------------------------------------------------
-- Line (immutable) and component (immutable)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_calculation_line (
    tenant_id                    uuid    NOT NULL,
    calculation_id               uuid    NOT NULL,
    id                           uuid    NOT NULL,
    run_id                       uuid    NOT NULL,
    source_line_id               uuid    NOT NULL,
    employee_id                  uuid    NOT NULL,
    employee_number              text    NOT NULL,
    gross_minor                  bigint  NOT NULL,
    employee_deductions_minor    bigint  NOT NULL,
    employer_contributions_minor bigint  NOT NULL,
    preview_net_minor            bigint  NOT NULL,
    employer_cost_minor          bigint  NOT NULL,
    excluded                     boolean NOT NULL,
    CONSTRAINT payroll_statutory_calculation_line_pkey PRIMARY KEY (tenant_id, calculation_id, id),
    CONSTRAINT payroll_statutory_calculation_line_source_unique UNIQUE (tenant_id, calculation_id, source_line_id),
    CONSTRAINT payroll_statutory_calculation_line_employee_unique UNIQUE (tenant_id, calculation_id, employee_id),
    CONSTRAINT payroll_statutory_calculation_line_calculation FOREIGN KEY (tenant_id, calculation_id)
        REFERENCES payroll.statutory_calculation (tenant_id, id),
    CONSTRAINT payroll_statutory_calculation_line_source FOREIGN KEY (tenant_id, run_id, source_line_id)
        REFERENCES payroll.pay_run_line (tenant_id, run_id, id),
    CONSTRAINT payroll_statutory_calculation_line_number
        CHECK (char_length(employee_number) BETWEEN 1 AND 64 AND employee_number !~ '[[:cntrl:]]'),
    CONSTRAINT payroll_statutory_calculation_line_amounts CHECK (
        gross_minor BETWEEN 0 AND 1000000000
        AND employee_deductions_minor BETWEEN 0 AND gross_minor
        AND employer_contributions_minor >= 0),
    CONSTRAINT payroll_statutory_calculation_line_arithmetic CHECK (
        preview_net_minor = gross_minor - employee_deductions_minor
        AND employer_cost_minor = gross_minor + employer_contributions_minor),
    CONSTRAINT payroll_statutory_calculation_line_excluded CHECK (
        NOT excluded OR (employee_deductions_minor = 0 AND employer_contributions_minor = 0))
);

CREATE INDEX payroll_statutory_calculation_line_page
    ON payroll.statutory_calculation_line (tenant_id, calculation_id, (employee_number COLLATE "C"), id);

CREATE FUNCTION payroll.statutory_calculation_line_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    calculation payroll.statutory_calculation%ROWTYPE;
    source      payroll.pay_run_line%ROWTYPE;
BEGIN
    SELECT * INTO calculation FROM payroll.statutory_calculation
        WHERE tenant_id = NEW.tenant_id AND id = NEW.calculation_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key refuses an unknown calculation
    END IF;
    IF calculation.status <> 'DRAFT' OR calculation.run_id <> NEW.run_id THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_line_state',
            MESSAGE = 'a statutory line belongs to a draft calculation of the same source run';
    END IF;
    SELECT * INTO source FROM payroll.pay_run_line
        WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id AND id = NEW.source_line_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key refuses an unknown source line
    END IF;
    IF source.gross_minor <> NEW.gross_minor OR source.employee_id <> NEW.employee_id
        OR source.employee_number <> NEW.employee_number THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_line_gross',
            MESSAGE = 'a statutory line carries the employee and the contractual gross frozen in its source line';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_statutory_calculation_line_guard BEFORE INSERT ON payroll.statutory_calculation_line
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_calculation_line_guard();
CREATE TRIGGER payroll_statutory_calculation_line_immutable
    BEFORE UPDATE OR DELETE ON payroll.statutory_calculation_line
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_immutable();
CREATE TRIGGER payroll_statutory_calculation_line_no_truncate BEFORE TRUNCATE ON payroll.statutory_calculation_line
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_immutable();

COMMENT ON TABLE payroll.statutory_calculation_line IS
    'MVP-094B immutable statutory preview line per employee; gross frozen from the source line; restricted financial and HR data';

CREATE TABLE payroll.statutory_calculation_component (
    tenant_id         uuid     NOT NULL,
    calculation_id    uuid     NOT NULL,
    line_id           uuid     NOT NULL,
    seq               smallint NOT NULL,
    component_id      text     NOT NULL,
    party             text     NOT NULL,
    category          text     NOT NULL,
    base_minor        bigint   NOT NULL,
    amount_minor      bigint   NOT NULL,
    explanation_codes text[]   NOT NULL,
    trace             jsonb,
    CONSTRAINT payroll_statutory_calculation_component_pkey
        PRIMARY KEY (tenant_id, calculation_id, line_id, seq),
    CONSTRAINT payroll_statutory_calculation_component_id_unique
        UNIQUE (tenant_id, calculation_id, line_id, component_id),
    CONSTRAINT payroll_statutory_calculation_component_line FOREIGN KEY (tenant_id, calculation_id, line_id)
        REFERENCES payroll.statutory_calculation_line (tenant_id, calculation_id, id),
    CONSTRAINT payroll_statutory_calculation_component_seq CHECK (seq BETWEEN 1 AND 12),
    CONSTRAINT payroll_statutory_calculation_component_id_format CHECK (component_id ~ '^[A-Z_]{3,48}$'),
    CONSTRAINT payroll_statutory_calculation_component_party CHECK (party IN ('EMPLOYEE', 'EMPLOYER')),
    CONSTRAINT payroll_statutory_calculation_component_category
        CHECK (category IN ('SOCIAL_CONTRIBUTION', 'INCOME_TAX')),
    CONSTRAINT payroll_statutory_calculation_component_amounts CHECK (
        base_minor BETWEEN 0 AND 1000000000 AND amount_minor BETWEEN 0 AND base_minor),
    CONSTRAINT payroll_statutory_calculation_component_explanation CHECK (
        cardinality(explanation_codes) BETWEEN 1 AND 4
        AND explanation_codes <@ ARRAY['RATE_APPLIED', 'PROGRESSIVE_APPLIED', 'CAP_APPLIED', 'NOT_SUBJECT',
            'BASE_GROSS', 'BASE_GROSS_LESS_EMPLOYEE_SOCIAL']::text[]),
    CONSTRAINT payroll_statutory_calculation_component_not_subject CHECK (
        NOT ('NOT_SUBJECT' = ANY (explanation_codes)) OR amount_minor = 0),
    CONSTRAINT payroll_statutory_calculation_component_trace
        CHECK (trace IS NULL OR jsonb_typeof(trace) = 'object')
);

CREATE FUNCTION payroll.statutory_calculation_component_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    rules      jsonb;
    rule       jsonb;
    is_excluded boolean;
BEGIN
    SELECT p.rules INTO rules FROM payroll.statutory_calculation c
        JOIN payroll.statutory_rule_pack p ON p.id = c.pack_id
        WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.calculation_id;
    SELECT excluded INTO is_excluded FROM payroll.statutory_calculation_line
        WHERE tenant_id = NEW.tenant_id AND calculation_id = NEW.calculation_id AND id = NEW.line_id;
    IF rules IS NULL OR is_excluded IS NULL THEN
        RETURN NEW; -- the foreign key refuses an unknown line
    END IF;
    rule := rules -> 'components' -> (NEW.seq - 1)::integer;
    IF is_excluded OR rule IS NULL OR rule ->> 'id' <> NEW.component_id
        OR rule ->> 'party' <> NEW.party OR rule ->> 'category' <> NEW.category THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_component_rule',
            MESSAGE = 'a component is the rule of the pack at its position, on a line that is not excluded';
    END IF;
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_statutory_calculation_component_guard
    BEFORE INSERT ON payroll.statutory_calculation_component
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_calculation_component_guard();
CREATE TRIGGER payroll_statutory_calculation_component_immutable
    BEFORE UPDATE OR DELETE ON payroll.statutory_calculation_component
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_immutable();
CREATE TRIGGER payroll_statutory_calculation_component_no_truncate
    BEFORE TRUNCATE ON payroll.statutory_calculation_component
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_immutable();

COMMENT ON TABLE payroll.statutory_calculation_component IS
    'MVP-094B immutable component of a statutory preview line (rule id, party, base, amount, explanation codes, trace); restricted financial data';

-- A line is judged with its components at commit: an excluded line has none, any other has exactly one per
-- rule of the pack, numbered from 1, at least one of them applicable, and the line's deductions
-- (employee components) and contributions (employer components) equal the component sums.
CREATE FUNCTION payroll.statutory_calculation_line_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    expected    integer;
    components  integer;
    max_seq     integer;
    applicable  integer;
    deductions  bigint;
    employer    bigint;
BEGIN
    SELECT jsonb_array_length(p.rules -> 'components') INTO expected
        FROM payroll.statutory_calculation c JOIN payroll.statutory_rule_pack p ON p.id = c.pack_id
        WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.calculation_id;
    SELECT count(*), coalesce(max(seq), 0),
           count(*) FILTER (WHERE NOT ('NOT_SUBJECT' = ANY (explanation_codes))),
           coalesce(sum(amount_minor) FILTER (WHERE party = 'EMPLOYEE'), 0),
           coalesce(sum(amount_minor) FILTER (WHERE party = 'EMPLOYER'), 0)
        INTO components, max_seq, applicable, deductions, employer
        FROM payroll.statutory_calculation_component
        WHERE tenant_id = NEW.tenant_id AND calculation_id = NEW.calculation_id AND line_id = NEW.id;
    IF NEW.excluded THEN
        IF components <> 0 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_line_components',
                MESSAGE = 'an excluded statutory line has no component';
        END IF;
    ELSIF components <> expected OR max_seq <> components OR applicable < 1 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_line_components',
            MESSAGE = 'a supported statutory line has one component per rule, numbered from 1, at least one applicable';
    END IF;
    IF deductions <> NEW.employee_deductions_minor OR employer <> NEW.employer_contributions_minor THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_line_sums',
            MESSAGE = 'the deductions and contributions of a statutory line equal the sums of its components';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_statutory_calculation_line_exact
    AFTER INSERT ON payroll.statutory_calculation_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.statutory_calculation_line_exact();

-- A calculation is judged with its lines and its source run at commit: counts and totals equal its lines',
-- every employee of the source run has a line (so the gross total is the run's), and the latest version of
-- the chain is the only DRAFT while every earlier version is SUPERSEDED by its successor.
CREATE FUNCTION payroll.statutory_calculation_exact() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    n_supported integer;
    n_excluded  integer;
    gross      bigint;
    deductions bigint;
    employer   bigint;
    run        payroll.pay_run%ROWTYPE;
    latest     integer;
    broken     integer;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT count(*) FILTER (WHERE NOT excluded), count(*) FILTER (WHERE excluded),
               coalesce(sum(gross_minor), 0), coalesce(sum(employee_deductions_minor), 0),
               coalesce(sum(employer_contributions_minor), 0)
            INTO n_supported, n_excluded, gross, deductions, employer
            FROM payroll.statutory_calculation_line
            WHERE tenant_id = NEW.tenant_id AND calculation_id = NEW.id;
        IF n_supported <> NEW.supported_count OR n_excluded <> NEW.excluded_count
            OR gross <> NEW.gross_total_minor OR deductions <> NEW.employee_deductions_total_minor
            OR employer <> NEW.employer_contributions_total_minor THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_exact',
                MESSAGE = 'a statutory calculation''s counts and totals equal those of its lines';
        END IF;
        SELECT * INTO run FROM payroll.pay_run WHERE tenant_id = NEW.tenant_id AND id = NEW.run_id;
        IF FOUND AND (run.employee_count <> n_supported + n_excluded OR run.total_gross_minor <> gross) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_source_total',
                MESSAGE = 'a statutory calculation has one line for every line of its source run';
        END IF;
    END IF;
    SELECT max(version) INTO latest FROM payroll.statutory_calculation
        WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id AND pack_id = NEW.pack_id;
    SELECT count(*) INTO broken FROM payroll.statutory_calculation r
        LEFT JOIN payroll.statutory_calculation s ON s.tenant_id = r.tenant_id AND s.run_id = r.run_id
            AND s.pack_id = r.pack_id AND s.version = r.version + 1
        WHERE r.tenant_id = NEW.tenant_id AND r.run_id = NEW.run_id AND r.pack_id = NEW.pack_id
          AND ((r.version = latest AND r.status <> 'DRAFT')
            OR (r.version < latest AND (r.status <> 'SUPERSEDED' OR r.superseded_by_id IS DISTINCT FROM s.id)));
    IF broken <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_calculation_chain',
            MESSAGE = 'a statutory calculation chain has one DRAFT, its latest version, and supersedes every earlier one';
    END IF;
    RETURN NULL;
END
$fn$;

CREATE CONSTRAINT TRIGGER payroll_statutory_calculation_exact
    AFTER INSERT OR UPDATE ON payroll.statutory_calculation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payroll.statutory_calculation_exact();

-- ---------------------------------------------------------------------------------------------
-- Seed: exactly one DRAFT rule pack of published reference values (not verified, not hashed).
-- ---------------------------------------------------------------------------------------------
-- The values are the ones published by the CNSS (Decree 18/04) and the DGI on the pages cited below.
-- The IRPP base, the periodization of the annual bands and the cap are NOT confirmed by a professional
-- review, which is why the pack is DRAFT: it never produces an amount. No source has a sha256 and no
-- reviewer data exists. A VERIFIED pack is a later version supplied with the professional approval evidence.

INSERT INTO payroll.statutory_rule_pack
    (id, code, version, country, currency, status, effective_from, effective_to, method_version, rules, created_at)
VALUES ('4f6c2d1a-0945-4a94-8d3b-5d0a3b0c0d94', 'CD-SALARY-PREVIEW', 1, 'CD', 'CDF', 'DRAFT',
        DATE '2026-01-01', DATE '2026-12-31', '1',
        $rules${
  "schemaVersion": 1,
  "components": [
    {"id": "CNSS_EMPLOYEE_PENSION", "party": "EMPLOYEE", "category": "SOCIAL_CONTRIBUTION",
     "order": 1, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 500}, "rounding": "HALF_UP"},
    {"id": "CNSS_EMPLOYER_PENSION", "party": "EMPLOYER", "category": "SOCIAL_CONTRIBUTION",
     "order": 2, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 500}, "rounding": "HALF_UP"},
    {"id": "CNSS_EMPLOYER_OCCUPATIONAL_RISK", "party": "EMPLOYER", "category": "SOCIAL_CONTRIBUTION",
     "order": 3, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 150}, "rounding": "HALF_UP"},
    {"id": "CNSS_EMPLOYER_FAMILY_BENEFITS", "party": "EMPLOYER", "category": "SOCIAL_CONTRIBUTION",
     "order": 4, "requires": "CNSS_SUBJECT", "base": "GROSS", "sourceKey": "CNSS_RATES",
     "method": {"type": "RATE", "rateBp": 650}, "rounding": "HALF_UP"},
    {"id": "IRPP_SALARY_WITHHOLDING", "party": "EMPLOYEE", "category": "INCOME_TAX",
     "order": 5, "requires": "IRPP_SUBJECT", "base": "GROSS", "sourceKey": "DGI_IRPP",
     "method": {"type": "PROGRESSIVE", "periodsPerYear": 12, "capBp": 3000,
                "brackets": [{"upToAnnual": 1944000, "rateBp": 300},
                             {"upToAnnual": 21600000, "rateBp": 1500},
                             {"upToAnnual": 43200000, "rateBp": 3000},
                             {"upToAnnual": null, "rateBp": 4000}]},
     "rounding": "HALF_UP"}
  ]
}$rules$::jsonb, now());

INSERT INTO payroll.statutory_rule_pack_source (pack_id, seq, source_key, title, url, sha256, retrieved_on)
VALUES
    ('4f6c2d1a-0945-4a94-8d3b-5d0a3b0c0d94', 1, 'DGI_IRPP',
     'DGI: personal income tax (IRPP), overview and progressive annual bands',
     'https://dgi.gouv.cd/impot-sur-le-revenu-des-personnes-physiques-irpp/', NULL, DATE '2026-10-29'),
    ('4f6c2d1a-0945-4a94-8d3b-5d0a3b0c0d94', 2, 'DGI_NOTICE_2026',
     'DGI: 2026 notice on the monthly salary-withholding declaration (15 February 2026)',
     'https://dgi.gouv.cd/wp-content/uploads/2026/02/Communique%CC%81-du-15-fe%CC%81vrier-2026.pdf', NULL,
     DATE '2026-10-29'),
    ('4f6c2d1a-0945-4a94-8d3b-5d0a3b0c0d94', 3, 'DGI_PORTAL',
     'DGI: official online declarations portal (IRPPDR1, IRPPDR11)',
     'https://desec.dgi.gouv.cd/', NULL, DATE '2026-10-29'),
    ('4f6c2d1a-0945-4a94-8d3b-5d0a3b0c0d94', 4, 'CNSS_RATES',
     'CNSS: contribution rates under Decree 18/04',
     'https://cnss.cd/?page_id=1388', NULL, DATE '2026-10-29'),
    ('4f6c2d1a-0945-4a94-8d3b-5d0a3b0c0d94', 5, 'CNSS_DECLARATION',
     'CNSS: monthly declaration requirements',
     'https://cnss.cd/?page_id=1698', NULL, DATE '2026-10-29'),
    ('4f6c2d1a-0945-4a94-8d3b-5d0a3b0c0d94', 6, 'BUDGET_2026',
     'Ministry of Budget: 2026 budget materials',
     'https://budget.gouv.cd/budget-2026/', NULL, DATE '2026-10-29');
