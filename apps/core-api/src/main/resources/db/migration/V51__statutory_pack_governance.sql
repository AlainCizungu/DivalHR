-- MVP-097A (Issue #200): governed lifecycle, independent review and offline validation of statutory payroll
-- packs (decisions D97A-1 to D97A-14, ADR 0040). Additive to V45. It invents, infers and approves NO statutory
-- content: no rate, threshold, contribution, exemption, formula or filing rule is introduced, changed or
-- activated here. Technical validation is not legal approval, and passing tests is not legal compliance.
--
-- Ownership: the payroll module. Every table is platform-owned (no tenant column): a tenant row can neither
-- own nor mutate a pack. Tenants read only the applicable ACTIVE pack and its public provenance.
--
-- Classification: platform evidence. Reviewer subjects and internal notes are restricted operational data.
-- No document is stored: the source URL or reference id is informational and runtime never fetches it.
--
-- Shape (extends payroll.statutory_rule_pack, never a parallel source of truth):
--   * statutory_pack_governance: exactly one row per pack version, created DRAFT by a trigger on the pack.
--     The closed lifecycle is DRAFT -> VALIDATED -> REVIEWED -> SCHEDULED -> ACTIVE -> RETIRED. The legacy
--     pack status follows it and can only be moved by this table's guard (DRAFT -> VERIFIED at ACTIVE,
--     VERIFIED -> RETIRED at RETIRED): V45's status VERIFIED means "governed ACTIVE".
--   * statutory_reference_case: the immutable reviewed reference cases of a version.
--   * statutory_pack_validation_run: append-only offline validation results with a bounded summary.
--   * statutory_pack_review: the independent approval or rejection (one per version).
--   * statutory_pack_transition: append-only lifecycle history, written by the guard itself.
-- Constraints and triggers enforce: closed states, append-only evidence and history, immutable digests after
-- validation, no self-approval, valid effective periods, no overlap of SCHEDULED or ACTIVE versions of one
-- country and applicability, and at most 25 versions per pack code.
--
-- Legacy quarantine (R200-1, Issue #200): V51 must apply to a database that already holds VERIFIED or RETIRED packs
-- created before governance existed (hr-dev holds such synthetic test packs). Each such pack gets the governance
-- state LEGACY: a quarantine, not a lifecycle step. A LEGACY version keeps its legacy status and every column
-- unchanged (so existing activations and frozen calculations keep resolving it), is readable forever, and is never
-- validated, reviewed, scheduled, activated, retired or edited through governance. It carries no evidence, no digests
-- and no history, because none exists: nothing is invented, inferred or backfilled, and LEGACY is not a statement
-- that the pack is correct or legally approved. It is never selected as the applicable ACTIVE version, so no new
-- employer activation can rest on it. A correction is a new governed version. Only a pack that is still DRAFT gets a
-- DRAFT governance row.
--
-- Rollback: db/rollback/V51__rollback.sql (manual, never run by Flyway) restores V50 exactly and refuses while
-- any governance row beyond the untouched DRAFT or LEGACY seed, or any case, run, review or transition row, exists.

-- R200-1: there is no precondition on the status of existing packs. A pack that is not DRAFT is quarantined as
-- LEGACY below; it is neither refused nor promoted.

-- ---------------------------------------------------------------------------------------------
-- Governance: one row per pack version
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_pack_governance (
    pack_id                uuid        NOT NULL,
    state                  text        NOT NULL DEFAULT 'DRAFT',
    row_version            integer     NOT NULL DEFAULT 1,
    semantic_version       text        NOT NULL,
    applicability          text        NOT NULL DEFAULT 'GENERAL',
    supersedes_pack_id     uuid,
    source_authority       text,
    source_title           text,
    source_published_on    date,
    source_reference       text,
    source_bundle_sha256   char(64),
    evidence_schema_version smallint,
    pack_sha256            char(64),
    fixture_sha256         char(64),
    validated_at           timestamptz,
    submitted_by           text,
    submitted_at           timestamptz,
    scheduled_at           timestamptz,
    activated_at           timestamptz,
    activated_by           text,
    active_from            date,
    active_to              date,
    retired_at             timestamptz,
    retired_by             text,
    retire_reason          text,
    -- Written by the application with every update; the guard turns them into one history row.
    transition_actor       text,
    transition_at          timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payroll_statutory_pack_governance_pkey PRIMARY KEY (pack_id),
    CONSTRAINT payroll_statutory_pack_governance_pack
        FOREIGN KEY (pack_id) REFERENCES payroll.statutory_rule_pack (id),
    CONSTRAINT payroll_statutory_pack_governance_supersedes
        FOREIGN KEY (supersedes_pack_id) REFERENCES payroll.statutory_rule_pack (id),
    CONSTRAINT payroll_statutory_pack_governance_state CHECK (state IN
        ('DRAFT', 'VALIDATED', 'REVIEWED', 'SCHEDULED', 'ACTIVE', 'RETIRED', 'LEGACY')),
    CONSTRAINT payroll_statutory_pack_governance_row_version CHECK (row_version BETWEEN 1 AND 1000000),
    CONSTRAINT payroll_statutory_pack_governance_semver
        CHECK (semantic_version ~ '^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})$'),
    CONSTRAINT payroll_statutory_pack_governance_applicability CHECK (applicability ~ '^[A-Z][A-Z0-9_-]{1,31}$'),
    CONSTRAINT payroll_statutory_pack_governance_authority
        CHECK (source_authority IS NULL OR (char_length(source_authority) BETWEEN 1 AND 200
            AND source_authority !~ '[[:cntrl:]]')),
    CONSTRAINT payroll_statutory_pack_governance_title
        CHECK (source_title IS NULL OR (char_length(source_title) BETWEEN 1 AND 300
            AND source_title !~ '[[:cntrl:]]')),
    CONSTRAINT payroll_statutory_pack_governance_published
        CHECK (source_published_on IS NULL
            OR source_published_on BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'),
    CONSTRAINT payroll_statutory_pack_governance_reference
        CHECK (source_reference IS NULL OR (char_length(source_reference) BETWEEN 1 AND 500
            AND source_reference !~ '[[:space:][:cntrl:]]'
            AND (source_reference !~* '^[a-z][a-z0-9+.-]*:' OR source_reference ~ '^https://'))),
    CONSTRAINT payroll_statutory_pack_governance_bundle
        CHECK (source_bundle_sha256 IS NULL OR source_bundle_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_statutory_pack_governance_evidence_version
        CHECK (evidence_schema_version IS NULL OR evidence_schema_version = 1),
    CONSTRAINT payroll_statutory_pack_governance_pack_sha
        CHECK (pack_sha256 IS NULL OR pack_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_statutory_pack_governance_fixture_sha
        CHECK (fixture_sha256 IS NULL OR fixture_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_statutory_pack_governance_actors CHECK (
        (submitted_by IS NULL OR char_length(submitted_by) BETWEEN 1 AND 255)
        AND (activated_by IS NULL OR char_length(activated_by) BETWEEN 1 AND 255)
        AND (retired_by IS NULL OR char_length(retired_by) BETWEEN 1 AND 255)
        AND (transition_actor IS NULL OR char_length(transition_actor) BETWEEN 1 AND 255)),
    CONSTRAINT payroll_statutory_pack_governance_retire_reason CHECK (retire_reason IS NULL
        OR retire_reason IN ('REJECTED', 'SUPERSEDED', 'WITHDRAWN', 'LEGAL_CHANGE', 'ERROR')),
    CONSTRAINT payroll_statutory_pack_governance_period CHECK (
        (active_from IS NULL AND active_to IS NULL)
        OR (active_from IS NOT NULL
            AND active_from BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
            AND (active_to IS NULL OR (active_to >= active_from AND active_to <= DATE '2999-12-31')))),
    -- Every branch is explicit about NULL: a CHECK that evaluates to NULL would pass.
    CONSTRAINT payroll_statutory_pack_governance_shape CHECK (
        (state = 'DRAFT'
            AND pack_sha256 IS NULL AND fixture_sha256 IS NULL AND validated_at IS NULL
            AND submitted_by IS NULL AND submitted_at IS NULL AND scheduled_at IS NULL
            AND activated_at IS NULL AND activated_by IS NULL AND active_from IS NULL
            AND retired_at IS NULL AND retired_by IS NULL AND retire_reason IS NULL)
        OR (state IN ('VALIDATED', 'REVIEWED')
            AND pack_sha256 IS NOT NULL AND fixture_sha256 IS NOT NULL AND validated_at IS NOT NULL
            AND scheduled_at IS NULL AND activated_at IS NULL AND activated_by IS NULL
            AND active_from IS NULL AND retired_at IS NULL AND retired_by IS NULL
            AND retire_reason IS NULL
            AND ((submitted_by IS NULL AND submitted_at IS NULL)
                 OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL))
            AND (state = 'VALIDATED' OR submitted_by IS NOT NULL))
        OR (state = 'SCHEDULED'
            AND pack_sha256 IS NOT NULL AND fixture_sha256 IS NOT NULL AND validated_at IS NOT NULL
            AND submitted_by IS NOT NULL AND scheduled_at IS NOT NULL AND active_from IS NOT NULL
            AND activated_at IS NULL AND activated_by IS NULL AND retired_at IS NULL)
        OR (state = 'ACTIVE'
            AND pack_sha256 IS NOT NULL AND fixture_sha256 IS NOT NULL AND validated_at IS NOT NULL
            AND submitted_by IS NOT NULL AND active_from IS NOT NULL
            AND activated_at IS NOT NULL AND activated_by IS NOT NULL AND retired_at IS NULL)
        OR (state = 'RETIRED'
            AND retired_at IS NOT NULL AND retired_by IS NOT NULL AND retire_reason IS NOT NULL)
        OR (state = 'LEGACY'
            AND pack_sha256 IS NULL AND fixture_sha256 IS NULL AND validated_at IS NULL
            AND submitted_by IS NULL AND submitted_at IS NULL AND scheduled_at IS NULL
            AND activated_at IS NULL AND activated_by IS NULL AND active_from IS NULL
            AND active_to IS NULL AND retired_at IS NULL AND retired_by IS NULL AND retire_reason IS NULL
            AND source_authority IS NULL AND source_title IS NULL AND source_published_on IS NULL
            AND source_reference IS NULL AND source_bundle_sha256 IS NULL
            AND evidence_schema_version IS NULL AND supersedes_pack_id IS NULL))
);

CREATE INDEX payroll_statutory_pack_governance_applicable
    ON payroll.statutory_pack_governance (state, active_from, active_to)
    WHERE state IN ('SCHEDULED', 'ACTIVE');

COMMENT ON TABLE payroll.statutory_pack_governance IS
    'MVP-097A governed lifecycle of a statutory pack version; VALIDATED is not legal approval';

-- ---------------------------------------------------------------------------------------------
-- Reviewed reference cases (immutable)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_reference_case (
    pack_id   uuid     NOT NULL,
    seq       smallint NOT NULL,
    case_key  text     NOT NULL,
    inputs    jsonb    NOT NULL,
    expected  jsonb    NOT NULL,
    CONSTRAINT payroll_statutory_reference_case_pkey PRIMARY KEY (pack_id, seq),
    CONSTRAINT payroll_statutory_reference_case_key_unique UNIQUE (pack_id, case_key),
    CONSTRAINT payroll_statutory_reference_case_pack
        FOREIGN KEY (pack_id) REFERENCES payroll.statutory_rule_pack (id),
    CONSTRAINT payroll_statutory_reference_case_seq CHECK (seq BETWEEN 1 AND 100),
    CONSTRAINT payroll_statutory_reference_case_key CHECK (case_key ~ '^[A-Z][A-Z0-9_-]{1,63}$'),
    CONSTRAINT payroll_statutory_reference_case_inputs CHECK (jsonb_typeof(inputs) = 'object'),
    CONSTRAINT payroll_statutory_reference_case_expected CHECK (jsonb_typeof(expected) = 'object'),
    CONSTRAINT payroll_statutory_reference_case_size
        CHECK (octet_length(inputs::text) + octet_length(expected::text) <= 16384)
);

-- ---------------------------------------------------------------------------------------------
-- Validation runs (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_pack_validation_run (
    id             uuid        NOT NULL,
    pack_id        uuid        NOT NULL,
    run_by         text        NOT NULL,
    run_at         timestamptz NOT NULL,
    outcome        text        NOT NULL,
    check_count    smallint    NOT NULL,
    failed_count   smallint    NOT NULL,
    summary        jsonb       NOT NULL,
    pack_sha256    char(64)    NOT NULL,
    fixture_sha256 char(64)    NOT NULL,
    engine_version text        NOT NULL,
    CONSTRAINT payroll_statutory_pack_validation_run_pkey PRIMARY KEY (id),
    CONSTRAINT payroll_statutory_pack_validation_run_pack
        FOREIGN KEY (pack_id) REFERENCES payroll.statutory_rule_pack (id),
    CONSTRAINT payroll_statutory_pack_validation_run_actor CHECK (char_length(run_by) BETWEEN 1 AND 255),
    CONSTRAINT payroll_statutory_pack_validation_run_outcome CHECK (outcome IN ('PASSED', 'FAILED')),
    CONSTRAINT payroll_statutory_pack_validation_run_counts CHECK (
        check_count BETWEEN 1 AND 400 AND failed_count BETWEEN 0 AND check_count
        AND ((outcome = 'PASSED') = (failed_count = 0))),
    CONSTRAINT payroll_statutory_pack_validation_run_summary CHECK (
        jsonb_typeof(summary) = 'array' AND jsonb_array_length(summary) BETWEEN 1 AND 400
        AND octet_length(summary::text) <= 32768),
    CONSTRAINT payroll_statutory_pack_validation_run_digests
        CHECK (pack_sha256 ~ '^[0-9a-f]{64}$' AND fixture_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_statutory_pack_validation_run_engine CHECK (engine_version ~ '^[A-Za-z0-9._-]{1,40}$')
);

CREATE INDEX payroll_statutory_pack_validation_run_pack
    ON payroll.statutory_pack_validation_run (pack_id, run_at DESC, id DESC);

-- ---------------------------------------------------------------------------------------------
-- Independent review (one decision per version, append-only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_pack_review (
    pack_id          uuid        NOT NULL,
    decision         text        NOT NULL,
    reviewer_subject text        NOT NULL,
    decided_at       timestamptz NOT NULL,
    reason_code      text,
    internal_note    text,
    confirmed        boolean     NOT NULL,
    mfa_verified     boolean     NOT NULL,
    CONSTRAINT payroll_statutory_pack_review_pkey PRIMARY KEY (pack_id),
    CONSTRAINT payroll_statutory_pack_review_pack
        FOREIGN KEY (pack_id) REFERENCES payroll.statutory_rule_pack (id),
    CONSTRAINT payroll_statutory_pack_review_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT payroll_statutory_pack_review_subject CHECK (char_length(reviewer_subject) BETWEEN 1 AND 255),
    CONSTRAINT payroll_statutory_pack_review_reason CHECK (
        (decision = 'APPROVED' AND reason_code IS NULL AND internal_note IS NULL)
        OR (decision = 'REJECTED' AND reason_code IN
            ('SOURCE_MISMATCH', 'VALUE_ERROR', 'EVIDENCE_INSUFFICIENT', 'SCOPE_ERROR', 'OTHER'))),
    CONSTRAINT payroll_statutory_pack_review_note CHECK (
        internal_note IS NULL OR (char_length(internal_note) BETWEEN 1 AND 500
            AND internal_note !~ '[[:cntrl:]]')),
    CONSTRAINT payroll_statutory_pack_review_confirmed CHECK (confirmed AND mfa_verified)
);

-- ---------------------------------------------------------------------------------------------
-- Lifecycle history (append-only, written by the governance guard)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payroll.statutory_pack_transition (
    pack_id    uuid        NOT NULL,
    seq        integer     NOT NULL,
    kind       text        NOT NULL,
    from_state text        NOT NULL,
    to_state   text        NOT NULL,
    actor      text        NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT payroll_statutory_pack_transition_pkey PRIMARY KEY (pack_id, seq),
    CONSTRAINT payroll_statutory_pack_transition_pack
        FOREIGN KEY (pack_id) REFERENCES payroll.statutory_rule_pack (id),
    CONSTRAINT payroll_statutory_pack_transition_kind CHECK (kind IN
        ('VALIDATE', 'SUBMIT', 'APPROVE', 'REJECT', 'SCHEDULE', 'ACTIVATE', 'RETIRE')),
    CONSTRAINT payroll_statutory_pack_transition_states CHECK (
        from_state IN ('DRAFT', 'VALIDATED', 'REVIEWED', 'SCHEDULED', 'ACTIVE', 'RETIRED')
        AND to_state IN ('DRAFT', 'VALIDATED', 'REVIEWED', 'SCHEDULED', 'ACTIVE', 'RETIRED')),
    CONSTRAINT payroll_statutory_pack_transition_actor CHECK (char_length(actor) BETWEEN 1 AND 255)
);

-- ---------------------------------------------------------------------------------------------
-- Append-only guards
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.statutory_pack_evidence_immutable() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_evidence_immutable',
        MESSAGE = 'statutory pack governance evidence and history are append-only';
END
$fn$;

CREATE TRIGGER payroll_statutory_reference_case_immutable
    BEFORE UPDATE OR DELETE ON payroll.statutory_reference_case
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_pack_evidence_immutable();
CREATE TRIGGER payroll_statutory_reference_case_no_truncate
    BEFORE TRUNCATE ON payroll.statutory_reference_case
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_pack_evidence_immutable();
CREATE TRIGGER payroll_statutory_pack_validation_run_immutable
    BEFORE UPDATE OR DELETE ON payroll.statutory_pack_validation_run
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_pack_evidence_immutable();
CREATE TRIGGER payroll_statutory_pack_validation_run_no_truncate
    BEFORE TRUNCATE ON payroll.statutory_pack_validation_run
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_pack_evidence_immutable();
CREATE TRIGGER payroll_statutory_pack_review_immutable
    BEFORE UPDATE OR DELETE ON payroll.statutory_pack_review
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_pack_evidence_immutable();
CREATE TRIGGER payroll_statutory_pack_review_no_truncate
    BEFORE TRUNCATE ON payroll.statutory_pack_review
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_pack_evidence_immutable();
CREATE TRIGGER payroll_statutory_pack_transition_immutable
    BEFORE UPDATE OR DELETE ON payroll.statutory_pack_transition
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_pack_evidence_immutable();
CREATE TRIGGER payroll_statutory_pack_transition_no_truncate
    BEFORE TRUNCATE ON payroll.statutory_pack_transition
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_pack_evidence_immutable();
CREATE TRIGGER payroll_statutory_pack_governance_no_delete
    BEFORE DELETE ON payroll.statutory_pack_governance
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_pack_evidence_immutable();
CREATE TRIGGER payroll_statutory_pack_governance_no_truncate
    BEFORE TRUNCATE ON payroll.statutory_pack_governance
    FOR EACH STATEMENT EXECUTE FUNCTION payroll.statutory_pack_evidence_immutable();

-- Cases are recorded only while the version is DRAFT.
CREATE FUNCTION payroll.statutory_reference_case_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    current_state text;
BEGIN
    SELECT state INTO current_state FROM payroll.statutory_pack_governance WHERE pack_id = NEW.pack_id;
    IF FOUND AND current_state <> 'DRAFT' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_reference_case_state',
            MESSAGE = 'reference cases are recorded while the pack version is DRAFT; a correction is a new version';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER payroll_statutory_reference_case_guard BEFORE INSERT ON payroll.statutory_reference_case
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_reference_case_guard();

-- A validation run is recorded for a DRAFT or VALIDATED version, bound to its current pack digest.
CREATE FUNCTION payroll.statutory_pack_validation_run_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    current_state text;
    digest        text;
BEGIN
    SELECT g.state, p.rules_digest INTO current_state, digest
        FROM payroll.statutory_pack_governance g JOIN payroll.statutory_rule_pack p ON p.id = g.pack_id
        WHERE g.pack_id = NEW.pack_id;
    IF FOUND AND current_state NOT IN ('DRAFT', 'VALIDATED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_validation_run_state',
            MESSAGE = 'a validation run is recorded for a DRAFT or VALIDATED pack version only';
    END IF;
    IF FOUND AND NEW.pack_sha256 <> digest THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_validation_run_digest',
            MESSAGE = 'a validation run carries the digest of the pack it validated';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER payroll_statutory_pack_validation_run_guard BEFORE INSERT ON payroll.statutory_pack_validation_run
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_pack_validation_run_guard();

-- The review is independent: the reviewer is never the submitter, and the version is submitted and VALIDATED.
CREATE FUNCTION payroll.statutory_pack_review_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    gov payroll.statutory_pack_governance%ROWTYPE;
BEGIN
    SELECT * INTO gov FROM payroll.statutory_pack_governance WHERE pack_id = NEW.pack_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key refuses an unknown pack
    END IF;
    IF gov.state <> 'VALIDATED' OR gov.submitted_by IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_review_state',
            MESSAGE = 'a review decides a VALIDATED pack version that was submitted for review';
    END IF;
    IF NEW.reviewer_subject = gov.submitted_by THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_review_independent',
            MESSAGE = 'the reviewer is never the submitter';
    END IF;
    RETURN NEW;
END
$fn$;
CREATE TRIGGER payroll_statutory_pack_review_guard BEFORE INSERT ON payroll.statutory_pack_review
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_pack_review_guard();

-- ---------------------------------------------------------------------------------------------
-- A governance row is born with every pack version (DRAFT); at most 25 versions per code
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.statutory_pack_governance_birth() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    IF (SELECT count(*) FROM payroll.statutory_rule_pack WHERE code = NEW.code) > 25 THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_version_limit',
            MESSAGE = 'at most 25 versions exist per statutory pack code';
    END IF;
    INSERT INTO payroll.statutory_pack_governance (pack_id, semantic_version, created_at)
        VALUES (NEW.id, NEW.version::text || '.0.0', NEW.created_at);
    RETURN NULL;
END
$fn$;
CREATE TRIGGER payroll_statutory_rule_pack_governance_birth AFTER INSERT ON payroll.statutory_rule_pack
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_pack_governance_birth();

-- Existing packs: DRAFT stays DRAFT; a VERIFIED or RETIRED pack predates governance and is quarantined as LEGACY
-- (R200-1). No other column of the governance row is set and no history row is written.
INSERT INTO payroll.statutory_pack_governance (pack_id, state, semantic_version, created_at)
    SELECT id, CASE WHEN status = 'DRAFT' THEN 'DRAFT' ELSE 'LEGACY' END, version::text || '.0.0', created_at
    FROM payroll.statutory_rule_pack;

-- After the backfill, no row is ever INSERTED as LEGACY: the quarantine belongs to this migration alone.
CREATE FUNCTION payroll.statutory_pack_governance_no_legacy_insert() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_legacy',
        MESSAGE = 'LEGACY is the quarantine of packs that predate governance and is never assigned to a new version';
END
$fn$;
CREATE TRIGGER payroll_statutory_pack_governance_no_legacy_insert
    BEFORE INSERT ON payroll.statutory_pack_governance
    FOR EACH ROW WHEN (NEW.state = 'LEGACY')
    EXECUTE FUNCTION payroll.statutory_pack_governance_no_legacy_insert();

-- ---------------------------------------------------------------------------------------------
-- The governance guard: the closed lifecycle, evidence, digests, independence and no overlap
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION payroll.statutory_pack_evidence_complete(g payroll.statutory_pack_governance)
    RETURNS boolean LANGUAGE sql IMMUTABLE AS
$fn$
    SELECT g.source_authority IS NOT NULL AND g.source_title IS NOT NULL
        AND g.source_published_on IS NOT NULL AND g.source_reference IS NOT NULL
        AND g.source_bundle_sha256 IS NOT NULL AND g.evidence_schema_version = 1
$fn$;

CREATE FUNCTION payroll.statutory_pack_governance_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    pack     payroll.statutory_rule_pack%ROWTYPE;
    move     text;
    cases    integer;
    clash    integer;
    the_end  date;
BEGIN
    SELECT * INTO pack FROM payroll.statutory_rule_pack WHERE id = OLD.pack_id;
    IF NEW.pack_id <> OLD.pack_id OR NEW.semantic_version <> OLD.semantic_version
        OR NEW.applicability <> OLD.applicability OR NEW.created_at <> OLD.created_at
        OR (OLD.supersedes_pack_id IS NOT NULL AND NEW.supersedes_pack_id IS DISTINCT FROM OLD.supersedes_pack_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_identity',
            MESSAGE = 'the identity of a pack version never changes: correct it as a new version';
    END IF;
    IF OLD.state = 'LEGACY' OR NEW.state = 'LEGACY' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_legacy',
            MESSAGE = 'a LEGACY pack version is quarantined: it is readable forever and never changed through governance; correct it as a new version';
    END IF;
    IF OLD.state = 'RETIRED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_retired',
            MESSAGE = 'a RETIRED pack version is readable forever and is never changed or reactivated';
    END IF;
    IF NEW.row_version <> OLD.row_version + 1 OR NEW.transition_actor IS NULL OR NEW.transition_at IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_version',
            MESSAGE = 'every governance change advances the row version and names its actor and time';
    END IF;
    -- Source evidence is editable only while DRAFT; it is immutable from VALIDATED on.
    IF OLD.state <> 'DRAFT' AND (
        NEW.source_authority IS DISTINCT FROM OLD.source_authority
        OR NEW.source_title IS DISTINCT FROM OLD.source_title
        OR NEW.source_published_on IS DISTINCT FROM OLD.source_published_on
        OR NEW.source_reference IS DISTINCT FROM OLD.source_reference
        OR NEW.source_bundle_sha256 IS DISTINCT FROM OLD.source_bundle_sha256
        OR NEW.evidence_schema_version IS DISTINCT FROM OLD.evidence_schema_version
        OR NEW.supersedes_pack_id IS DISTINCT FROM OLD.supersedes_pack_id) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_evidence',
            MESSAGE = 'the source evidence of a pack version is immutable once it is validated';
    END IF;
    -- Digests are set once, by DRAFT -> VALIDATED, and never change.
    IF OLD.state <> 'DRAFT' AND (NEW.pack_sha256 IS DISTINCT FROM OLD.pack_sha256
        OR NEW.fixture_sha256 IS DISTINCT FROM OLD.fixture_sha256
        OR NEW.validated_at IS DISTINCT FROM OLD.validated_at) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_digest',
            MESSAGE = 'the digests of a validated pack version never change';
    END IF;

    IF NEW.state = OLD.state THEN
        IF OLD.state = 'DRAFT' THEN
            RETURN NEW; -- evidence and supersession are recorded while DRAFT; no history row
        END IF;
        IF OLD.state = 'VALIDATED' AND OLD.submitted_by IS NULL AND NEW.submitted_by IS NOT NULL THEN
            IF NOT payroll.statutory_pack_evidence_complete(NEW) THEN
                RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_incomplete',
                    MESSAGE = 'the source evidence is complete before a pack version is submitted for review';
            END IF;
            move := 'SUBMIT';
        ELSE
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_transition',
                MESSAGE = 'only a submission changes a pack version without changing its state';
        END IF;
    ELSIF OLD.state = 'DRAFT' AND NEW.state = 'VALIDATED' THEN
        move := 'VALIDATE';
        SELECT count(*) INTO cases FROM payroll.statutory_reference_case WHERE pack_id = OLD.pack_id;
        IF cases = 0 OR NEW.pack_sha256 IS DISTINCT FROM pack.rules_digest
            OR NOT EXISTS (SELECT 1 FROM payroll.statutory_pack_validation_run r
                           WHERE r.pack_id = OLD.pack_id AND r.outcome = 'PASSED'
                             AND r.pack_sha256 = NEW.pack_sha256 AND r.fixture_sha256 = NEW.fixture_sha256) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_validation',
                MESSAGE = 'a pack version is VALIDATED only by a passing validation run bound to its digests';
        END IF;
    ELSIF OLD.state = 'VALIDATED' AND NEW.state = 'REVIEWED' THEN
        move := 'APPROVE';
        IF NOT EXISTS (SELECT 1 FROM payroll.statutory_pack_review r
                       WHERE r.pack_id = OLD.pack_id AND r.decision = 'APPROVED'
                         AND r.reviewer_subject <> OLD.submitted_by) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_review',
                MESSAGE = 'a pack version is REVIEWED only by an independent approval';
        END IF;
    ELSIF OLD.state = 'VALIDATED' AND NEW.state = 'RETIRED' THEN
        move := 'REJECT';
        IF NEW.retire_reason <> 'REJECTED' OR NOT EXISTS (SELECT 1 FROM payroll.statutory_pack_review r
                       WHERE r.pack_id = OLD.pack_id AND r.decision = 'REJECTED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_review',
                MESSAGE = 'a VALIDATED pack version is retired only by a recorded rejection';
        END IF;
    ELSIF OLD.state = 'REVIEWED' AND NEW.state IN ('SCHEDULED', 'ACTIVE') THEN
        move := CASE NEW.state WHEN 'SCHEDULED' THEN 'SCHEDULE' ELSE 'ACTIVATE' END;
    ELSIF OLD.state = 'SCHEDULED' AND NEW.state = 'ACTIVE' THEN
        move := 'ACTIVATE';
        IF NEW.active_from IS DISTINCT FROM OLD.active_from OR NEW.active_to IS DISTINCT FROM OLD.active_to
            OR NEW.active_from > (NEW.activated_at AT TIME ZONE 'UTC')::date THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_period',
                MESSAGE = 'a scheduled pack version is activated on or after its effective date, unchanged';
        END IF;
    ELSIF OLD.state IN ('REVIEWED', 'SCHEDULED', 'ACTIVE') AND NEW.state = 'RETIRED' THEN
        move := 'RETIRE';
        IF NEW.retire_reason = 'REJECTED' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_transition',
                MESSAGE = 'REJECTED is the reason of a rejection only';
        END IF;
    ELSE
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_transition',
            MESSAGE = 'a pack version moves DRAFT, VALIDATED, REVIEWED, SCHEDULED, ACTIVE, RETIRED only';
    END IF;

    -- Applicability: inside the pack interval, one SCHEDULED or ACTIVE version per country and period.
    IF NEW.state IN ('SCHEDULED', 'ACTIVE') AND OLD.state = 'REVIEWED' THEN
        the_end := pack.effective_to;
        IF NEW.active_from < pack.effective_from
            OR (pack.effective_to IS NOT NULL AND NEW.active_from > pack.effective_to)
            OR NEW.active_to IS DISTINCT FROM the_end THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_period',
                MESSAGE = 'the effective period lies inside the interval of the pack version';
        END IF;
        IF NEW.state = 'SCHEDULED'
            AND NEW.active_from <= (NEW.scheduled_at AT TIME ZONE 'UTC')::date THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_governance_period',
                MESSAGE = 'a scheduled pack version has a future effective date';
        END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended('payroll.statutory-pack:' || pack.country, 0));
        SELECT count(*) INTO clash
            FROM payroll.statutory_pack_governance o
            JOIN payroll.statutory_rule_pack op ON op.id = o.pack_id
            WHERE o.pack_id <> OLD.pack_id AND op.country = pack.country
              AND o.applicability = OLD.applicability AND o.state IN ('SCHEDULED', 'ACTIVE')
              AND daterange(o.active_from, o.active_to, '[]') && daterange(NEW.active_from, NEW.active_to, '[]');
        IF clash > 0 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_pack_period_overlap',
                MESSAGE = 'another SCHEDULED or ACTIVE version already covers part of this country and period';
        END IF;
    END IF;

    INSERT INTO payroll.statutory_pack_transition (pack_id, seq, kind, from_state, to_state, actor, occurred_at)
        VALUES (OLD.pack_id,
                coalesce((SELECT max(seq) FROM payroll.statutory_pack_transition WHERE pack_id = OLD.pack_id), 0) + 1,
                move, OLD.state, NEW.state, NEW.transition_actor, NEW.transition_at);
    RETURN NEW;
END
$fn$;

CREATE TRIGGER payroll_statutory_pack_governance_guard BEFORE UPDATE ON payroll.statutory_pack_governance
    FOR EACH ROW EXECUTE FUNCTION payroll.statutory_pack_governance_guard();


-- The legacy status follows the governed lifecycle. It runs AFTER the governance row changed, so the legacy
-- guard (trigger depth 2) reads the new governed state.
CREATE FUNCTION payroll.statutory_pack_governance_follow() RETURNS trigger LANGUAGE plpgsql AS
$fn$
BEGIN
    -- The legacy status follows the governed lifecycle and can move only from here (trigger depth 2).
    IF NEW.state = 'ACTIVE' THEN
        UPDATE payroll.statutory_rule_pack SET status = 'VERIFIED',
            reviewer_name = 'Independent platform review', reviewer_organization = 'DivalHR platform governance',
            reviewed_on = (SELECT (decided_at AT TIME ZONE 'UTC')::date
                           FROM payroll.statutory_pack_review WHERE pack_id = NEW.pack_id),
            attested = true, verified_at = NEW.activated_at
            WHERE id = NEW.pack_id;
    ELSIF NEW.state = 'RETIRED' AND OLD.state = 'ACTIVE' THEN
        UPDATE payroll.statutory_rule_pack SET status = 'RETIRED', retired_at = NEW.retired_at
            WHERE id = NEW.pack_id;
    END IF;

    RETURN NULL;
END
$fn$;

CREATE TRIGGER payroll_statutory_pack_governance_follow AFTER UPDATE ON payroll.statutory_pack_governance
    FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state)
    EXECUTE FUNCTION payroll.statutory_pack_governance_follow();

-- ---------------------------------------------------------------------------------------------
-- The legacy pack guard (V45), replaced: DRAFT -> VERIFIED and VERIFIED -> RETIRED move only from the
-- governance guard (trigger depth 2) and only for a governed ACTIVE or RETIRED version.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION payroll.statutory_rule_pack_guard() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    governed text;
    expected text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_forward',
                MESSAGE = 'a rule pack is created DRAFT and moves DRAFT -> VERIFIED -> RETIRED only';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.id <> OLD.id OR NEW.code <> OLD.code OR NEW.version <> OLD.version
        OR NEW.country <> OLD.country OR NEW.currency <> OLD.currency
        OR NEW.effective_from <> OLD.effective_from
        OR NEW.effective_to IS DISTINCT FROM OLD.effective_to
        OR NEW.method_version <> OLD.method_version
        OR NEW.rules IS DISTINCT FROM OLD.rules OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_immutable',
            MESSAGE = 'a rule pack''s identity, interval, method and rules never change: correct it as a new version';
    END IF;
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
    SELECT state INTO governed FROM payroll.statutory_pack_governance WHERE pack_id = NEW.id;
    expected := CASE NEW.status WHEN 'VERIFIED' THEN 'ACTIVE' ELSE 'RETIRED' END;
    IF pg_trigger_depth() < 2 OR governed IS DISTINCT FROM expected THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'payroll_statutory_rule_pack_governed',
            MESSAGE = 'a rule pack is verified or retired only through the governed statutory pack lifecycle';
    END IF;
    RETURN NEW;
END
$fn$;

COMMENT ON TABLE payroll.statutory_reference_case IS
    'MVP-097A immutable reviewed reference case of a pack version (synthetic fixtures prove the mechanism only)';
COMMENT ON TABLE payroll.statutory_pack_validation_run IS
    'MVP-097A append-only offline technical validation result; technical validation is not legal approval';
COMMENT ON TABLE payroll.statutory_pack_review IS
    'MVP-097A independent review decision of a pack version; the reviewer is never the submitter';
COMMENT ON TABLE payroll.statutory_pack_transition IS
    'MVP-097A append-only lifecycle history of a pack version';
