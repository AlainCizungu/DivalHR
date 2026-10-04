-- MVP-030 (Issue #51): contract templates, issued contract snapshots and acknowledgement evidence.
--
-- Ownership (ADR 0009): the documents module owns every table below. The cross-schema foreign
-- keys (documents -> people.employee and people.employment, and documents ->
-- identity.employee_access_link) are a deliberate modular-monolith integrity exception, as in
-- ADR 0008: they are constraints, not reads. Application code reaches people and identity only
-- through the platform.access ports.
--
-- Classification (approved): template titles and bodies are Confidential organization text; the
-- rendered snapshot, contract type, language, period, state and void reason, and the statement
-- code, version, language and time are Restricted HR; every ID is a personal-data reference; the
-- membership and link IDs in the evidence are Confidential; digests are integrity data. No name,
-- subject, address, IP address or user agent is stored outside the rendered snapshot.
--
-- Database authority: approved template versions and issued snapshots never change; contracts
-- move only ISSUED -> ACKNOWLEDGED (with its evidence row) or ISSUED -> VOID; evidence is
-- insert-only and bound by key to the exact snapshot digest and versions it confirms; non-void
-- contracts of one employment never overlap; every digest, grammar, renderer and statement
-- version is pinned to 1 (A30-4: a new version needs a new migration and ADR). Nothing here can
-- be truncated. Trigger failures carry stable constraint names.
--
-- Rollback: db/rollback/V16__rollback.sql (manual, never run by Flyway). It refuses once any
-- template, version, contract, acknowledgement or guard row exists, and never touches
-- flyway_schema_history.

CREATE FUNCTION documents.contract_no_truncate() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_no_truncate',
        MESSAGE = 'contract data is never truncated';
END
$fn$;

-- =============================================================================================
-- Templates and their immutable versions
-- =============================================================================================
CREATE TABLE documents.contract_template (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    code          text        NOT NULL,
    name          text        NOT NULL,
    contract_type text        NOT NULL,
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT contract_template_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES tenant.organization (id),
    CONSTRAINT contract_template_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT contract_template_code_unique UNIQUE (tenant_id, code),
    CONSTRAINT contract_template_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT contract_template_name_valid
        CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 160),
    CONSTRAINT contract_template_type_valid CHECK (contract_type IN
        ('PERMANENT', 'FIXED_TERM', 'APPRENTICESHIP', 'INTERNSHIP', 'DAILY')),
    CONSTRAINT contract_template_created_by_length
        CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT contract_template_version_non_negative CHECK (version >= 0)
);

COMMENT ON TABLE documents.contract_template IS
    'MVP-030 contract template identity (Confidential organization data). Code and type never change.';

CREATE TABLE documents.contract_template_version (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    template_id     uuid        NOT NULL,
    locale          text        NOT NULL,
    version_number  integer     NOT NULL,
    state           text        NOT NULL,
    title           text        NOT NULL,
    body            text        NOT NULL,
    placeholders    text[]      NOT NULL,
    body_sha256     text        NOT NULL,
    grammar_version smallint    NOT NULL,
    digest_version  smallint    NOT NULL,
    created_at      timestamptz NOT NULL,
    created_by      text        NOT NULL,
    updated_at      timestamptz NOT NULL,
    updated_by      text        NOT NULL,
    approved_at     timestamptz,
    approved_by     text,
    retired_at      timestamptz,
    retired_by      text,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT contract_template_version_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES documents.contract_template (tenant_id, id),
    CONSTRAINT contract_template_version_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT contract_template_version_owner_unique UNIQUE (tenant_id, template_id, id),
    CONSTRAINT contract_template_version_number_unique UNIQUE (template_id, locale, version_number),
    CONSTRAINT contract_template_version_locale_valid CHECK (locale IN ('fr', 'en')),
    CONSTRAINT contract_template_version_number_positive CHECK (version_number >= 1),
    CONSTRAINT contract_template_version_state_valid
        CHECK (state IN ('DRAFT', 'APPROVED', 'RETIRED')),
    CONSTRAINT contract_template_version_title_length
        CHECK (char_length(title) BETWEEN 1 AND 160),
    CONSTRAINT contract_template_version_body_length
        CHECK (char_length(body) BETWEEN 1 AND 40000),
    CONSTRAINT contract_template_version_placeholders_valid CHECK (placeholders <@ ARRAY[
        'employee.givenNames', 'employee.familyName', 'employee.fullName', 'employee.number',
        'organization.name', 'legalEntity.name', 'site.name', 'employment.startDate',
        'contract.type', 'contract.startDate', 'contract.endDate', 'issue.date']::text[]),
    CONSTRAINT contract_template_version_digest_format CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    -- A30-4: grammar and digest semantics are pinned; a new version is a new migration and ADR.
    CONSTRAINT contract_template_version_grammar_v1 CHECK (grammar_version = 1),
    CONSTRAINT contract_template_version_digest_v1 CHECK (digest_version = 1),
    CONSTRAINT contract_template_version_approval_pair
        CHECK ((approved_at IS NULL) = (approved_by IS NULL)
            AND (retired_at IS NULL) = (retired_by IS NULL)
            AND (state = 'DRAFT') = (approved_at IS NULL)
            AND (state = 'RETIRED') = (retired_at IS NOT NULL)),
    CONSTRAINT contract_template_version_actor_length CHECK (
        char_length(created_by) BETWEEN 1 AND 255 AND char_length(updated_by) BETWEEN 1 AND 255
        AND (approved_by IS NULL OR char_length(approved_by) BETWEEN 1 AND 255)
        AND (retired_by IS NULL OR char_length(retired_by) BETWEEN 1 AND 255)),
    CONSTRAINT contract_template_version_version_non_negative CHECK (version >= 0)
);

COMMENT ON TABLE documents.contract_template_version IS
    'MVP-030 single-language template text (grammar v1). Only DRAFT rows change; APPROVED and '
    'RETIRED rows are frozen.';

-- One approved and one draft version per template and language (D8).
CREATE UNIQUE INDEX contract_template_version_one_approved
    ON documents.contract_template_version (template_id, locale) WHERE state = 'APPROVED';
CREATE UNIQUE INDEX contract_template_version_one_draft
    ON documents.contract_template_version (template_id, locale) WHERE state = 'DRAFT';

CREATE FUNCTION documents.contract_template_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.version <> 0 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_template_immutable',
                MESSAGE = 'a template is inserted at version 0';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_template_immutable',
        MESSAGE = 'templates are never changed or deleted';
END
$fn$;

CREATE TRIGGER contract_template_guard
    BEFORE INSERT OR UPDATE OR DELETE ON documents.contract_template
    FOR EACH ROW EXECUTE FUNCTION documents.contract_template_guard();

CREATE FUNCTION documents.contract_template_version_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
DECLARE
    v_fixed jsonb;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'DRAFT' OR NEW.version <> 0 THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'contract_template_version_immutable',
                MESSAGE = 'a version is inserted as a DRAFT at version 0';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        -- Only a never-approved draft is deleted (every DRAFT is one: approval is irreversible).
        IF OLD.state <> 'DRAFT' THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'contract_template_version_immutable',
                MESSAGE = 'only a never-approved draft is deleted';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id
        OR NEW.template_id <> OLD.template_id OR NEW.locale <> OLD.locale
        OR NEW.version_number <> OLD.version_number OR NEW.created_at <> OLD.created_at
        OR NEW.created_by <> OLD.created_by THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_template_version_immutable',
            MESSAGE = 'identity columns never change';
    END IF;
    v_fixed := to_jsonb(OLD) - 'version';
    IF OLD.state = 'DRAFT' AND NEW.state = 'DRAFT' THEN
        -- Editing a draft: its text, digest, placeholders and update stamp only.
        IF (to_jsonb(NEW) - 'version' - 'title' - 'body' - 'body_sha256' - 'placeholders'
                - 'updated_at' - 'updated_by')
            IS DISTINCT FROM (v_fixed - 'title' - 'body' - 'body_sha256' - 'placeholders'
                - 'updated_at' - 'updated_by') THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'contract_template_version_immutable',
                MESSAGE = 'a draft edit changes its text only';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state = 'DRAFT' AND NEW.state = 'APPROVED' THEN
        IF (to_jsonb(NEW) - 'version' - 'state' - 'approved_at' - 'approved_by')
            IS DISTINCT FROM (v_fixed - 'state' - 'approved_at' - 'approved_by') THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'contract_template_version_immutable',
                MESSAGE = 'approval changes the state and approval stamp only';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.state = 'APPROVED' AND NEW.state = 'RETIRED' THEN
        IF (to_jsonb(NEW) - 'version' - 'state' - 'retired_at' - 'retired_by')
            IS DISTINCT FROM (v_fixed - 'state' - 'retired_at' - 'retired_by') THEN
            RAISE EXCEPTION USING ERRCODE = '23514',
                CONSTRAINT = 'contract_template_version_immutable',
                MESSAGE = 'retirement changes the state and retirement stamp only';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_template_version_immutable',
        MESSAGE = 'versions move only DRAFT -> APPROVED -> RETIRED';
END
$fn$;

CREATE TRIGGER contract_template_version_guard
    BEFORE INSERT OR UPDATE OR DELETE ON documents.contract_template_version
    FOR EACH ROW EXECUTE FUNCTION documents.contract_template_version_guard();

-- =============================================================================================
-- Issued contracts
-- =============================================================================================

-- One row per employment that ever had a contract: issue takes it FOR UPDATE (lock order step
-- 5c, ADR 0009) so two issues for one employment are serialized before the overlap check.
CREATE TABLE documents.contract_employment_guard (
    tenant_id     uuid NOT NULL,
    employment_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, employment_id),
    -- Integrity exception (ADR 0009): a constraint on people's key, never a read.
    CONSTRAINT contract_employment_guard_employment FOREIGN KEY (tenant_id, employment_id)
        REFERENCES people.employment (tenant_id, id)
);

CREATE FUNCTION documents.contract_employment_guard_immutable() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_employment_guard_immutable',
        MESSAGE = 'contract guard rows are never changed or deleted';
END
$fn$;

CREATE TRIGGER contract_employment_guard_immutable
    BEFORE UPDATE OR DELETE ON documents.contract_employment_guard
    FOR EACH ROW EXECUTE FUNCTION documents.contract_employment_guard_immutable();

CREATE TABLE documents.contract (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL,
    employee_id         uuid        NOT NULL,
    employment_id       uuid        NOT NULL,
    template_id         uuid        NOT NULL,
    template_version_id uuid        NOT NULL,
    contract_type       text        NOT NULL,
    locale              text        NOT NULL,
    start_date          date        NOT NULL,
    end_date            date,
    snapshot            jsonb       NOT NULL,
    snapshot_canonical  text        NOT NULL,
    snapshot_sha256     text        NOT NULL,
    digest_version      smallint    NOT NULL,
    grammar_version     smallint    NOT NULL,
    renderer_version    smallint    NOT NULL,
    state               text        NOT NULL,
    issued_at           timestamptz NOT NULL,
    issued_by           text        NOT NULL,
    acknowledged_at     timestamptz,
    void_reason         text,
    voided_at           timestamptz,
    voided_by           text,
    version             bigint      NOT NULL DEFAULT 0,
    CONSTRAINT contract_tenant_id_unique UNIQUE (tenant_id, id),
    -- The acknowledgement's key: evidence can only name this exact snapshot and its versions.
    CONSTRAINT contract_evidence_target_unique UNIQUE (tenant_id, id, employee_id, snapshot_sha256,
        digest_version, grammar_version, renderer_version),
    CONSTRAINT contract_template_version FOREIGN KEY (tenant_id, template_id, template_version_id)
        REFERENCES documents.contract_template_version (tenant_id, template_id, id),
    -- Integrity exception (ADR 0009): constraints on people's keys, never reads.
    CONSTRAINT contract_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    CONSTRAINT contract_employment FOREIGN KEY (tenant_id, employment_id, employee_id)
        REFERENCES people.employment (tenant_id, id, employee_id),
    CONSTRAINT contract_guard FOREIGN KEY (tenant_id, employment_id)
        REFERENCES documents.contract_employment_guard (tenant_id, employment_id),
    CONSTRAINT contract_type_valid CHECK (contract_type IN
        ('PERMANENT', 'FIXED_TERM', 'APPRENTICESHIP', 'INTERNSHIP', 'DAILY')),
    CONSTRAINT contract_locale_valid CHECK (locale IN ('fr', 'en')),
    CONSTRAINT contract_period_valid CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT contract_state_valid CHECK (state IN ('ISSUED', 'ACKNOWLEDGED', 'VOID')),
    CONSTRAINT contract_snapshot_size CHECK (octet_length(snapshot_canonical) <= 65536),
    -- The structured snapshot is exactly the canonical text the digest covers.
    CONSTRAINT contract_snapshot_canonical CHECK (snapshot = snapshot_canonical::jsonb),
    CONSTRAINT contract_digest_format CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    -- A30-4: pinned semantics.
    CONSTRAINT contract_digest_v1 CHECK (digest_version = 1),
    CONSTRAINT contract_grammar_v1 CHECK (grammar_version = 1),
    CONSTRAINT contract_renderer_v1 CHECK (renderer_version = 1),
    CONSTRAINT contract_state_stamps CHECK (
        (state = 'ACKNOWLEDGED') = (acknowledged_at IS NOT NULL)
        AND (state = 'VOID') = (voided_at IS NOT NULL)
        AND (voided_at IS NULL) = (voided_by IS NULL)
        AND (voided_at IS NULL) = (void_reason IS NULL)),
    CONSTRAINT contract_void_reason_valid CHECK (void_reason IS NULL OR void_reason IN
        ('ISSUED_IN_ERROR', 'WRONG_TEMPLATE', 'WRONG_DATA', 'OTHER')),
    CONSTRAINT contract_actor_length CHECK (char_length(issued_by) BETWEEN 1 AND 255
        AND (voided_by IS NULL OR char_length(voided_by) BETWEEN 1 AND 255)),
    CONSTRAINT contract_version_non_negative CHECK (version >= 0),
    -- D11: non-void contracts of one employment never overlap (open end = unbounded).
    CONSTRAINT contract_no_overlap EXCLUDE USING gist (
        tenant_id WITH =, employment_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&) WHERE (state <> 'VOID')
);

COMMENT ON TABLE documents.contract IS
    'MVP-030 issued contract: an immutable rendered snapshot (Restricted HR) and its lifecycle.';

CREATE INDEX contract_employee_issued
    ON documents.contract (tenant_id, employee_id, issued_at DESC, id DESC);
CREATE INDEX contract_template_version_ref ON documents.contract (template_version_id);

CREATE FUNCTION documents.contract_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_immutable',
            MESSAGE = 'contracts are never deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        -- Issued ISSUED at version 0, from an APPROVED version whose language and type it carries.
        IF NEW.state <> 'ISSUED' OR NEW.version <> 0
            OR NOT EXISTS (SELECT 1 FROM documents.contract_template_version v
                JOIN documents.contract_template t
                    ON t.tenant_id = v.tenant_id AND t.id = v.template_id
                WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.template_version_id
                    AND v.template_id = NEW.template_id AND v.state = 'APPROVED'
                    AND v.locale = NEW.locale AND t.contract_type = NEW.contract_type) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_immutable',
                MESSAGE = 'a contract is issued from an approved version of its language and type';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.version <> OLD.version + 1 OR OLD.state <> 'ISSUED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_immutable',
            MESSAGE = 'only an issued contract changes, one version at a time';
    END IF;
    IF NEW.state = 'ACKNOWLEDGED' THEN
        IF (to_jsonb(NEW) - 'version' - 'state' - 'acknowledged_at')
                IS DISTINCT FROM (to_jsonb(OLD) - 'version' - 'state' - 'acknowledged_at')
            OR NOT EXISTS (SELECT 1 FROM documents.contract_acknowledgement a
                WHERE a.tenant_id = NEW.tenant_id AND a.contract_id = NEW.id
                    AND a.acknowledged_at = NEW.acknowledged_at) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_immutable',
                MESSAGE = 'a contract is acknowledged only with its evidence';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.state = 'VOID' THEN
        IF (to_jsonb(NEW) - 'version' - 'state' - 'void_reason' - 'voided_at' - 'voided_by')
            IS DISTINCT FROM
            (to_jsonb(OLD) - 'version' - 'state' - 'void_reason' - 'voided_at' - 'voided_by') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_immutable',
                MESSAGE = 'voiding changes the state and void stamp only';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_immutable',
        MESSAGE = 'contracts move only ISSUED -> ACKNOWLEDGED or ISSUED -> VOID';
END
$fn$;

CREATE TRIGGER contract_guard
    BEFORE INSERT OR UPDATE OR DELETE ON documents.contract
    FOR EACH ROW EXECUTE FUNCTION documents.contract_guard();

-- =============================================================================================
-- Acknowledgement evidence (insert-only)
-- =============================================================================================
CREATE TABLE documents.contract_acknowledgement (
    id                      uuid        PRIMARY KEY,
    tenant_id               uuid        NOT NULL,
    contract_id             uuid        NOT NULL,
    employee_id             uuid        NOT NULL,
    membership_id           uuid        NOT NULL,
    link_id                 uuid        NOT NULL,
    snapshot_sha256         text        NOT NULL,
    snapshot_digest_version smallint    NOT NULL,
    grammar_version         smallint    NOT NULL,
    renderer_version        smallint    NOT NULL,
    statement_code          text        NOT NULL,
    statement_version       smallint    NOT NULL,
    statement_locale        text        NOT NULL,
    statement_sha256        text        NOT NULL,
    evidence_sha256         text        NOT NULL,
    acknowledged_at         timestamptz NOT NULL,
    correlation_id          text        NOT NULL,
    CONSTRAINT contract_acknowledgement_once UNIQUE (tenant_id, contract_id),
    -- The evidence names exactly the snapshot digest and versions of its contract (A30-4).
    CONSTRAINT contract_acknowledgement_contract FOREIGN KEY (tenant_id, contract_id, employee_id,
        snapshot_sha256, snapshot_digest_version, grammar_version, renderer_version)
        REFERENCES documents.contract (tenant_id, id, employee_id, snapshot_sha256,
            digest_version, grammar_version, renderer_version),
    -- Integrity exception (ADR 0009): the employee's own link to that membership.
    CONSTRAINT contract_acknowledgement_link FOREIGN KEY (tenant_id, link_id, employee_id,
        membership_id)
        REFERENCES identity.employee_access_link (tenant_id, id, employee_id, membership_id),
    CONSTRAINT contract_acknowledgement_statement_v1 CHECK (statement_code = 'RECEIVED_AND_REVIEWED'
        AND statement_version = 1),
    CONSTRAINT contract_acknowledgement_versions_v1 CHECK (snapshot_digest_version = 1
        AND grammar_version = 1 AND renderer_version = 1),
    CONSTRAINT contract_acknowledgement_locale_valid CHECK (statement_locale IN ('fr', 'en')),
    CONSTRAINT contract_acknowledgement_digest_format CHECK (
        snapshot_sha256 ~ '^[0-9a-f]{64}$' AND statement_sha256 ~ '^[0-9a-f]{64}$'
        AND evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT contract_acknowledgement_correlation_length
        CHECK (char_length(correlation_id) BETWEEN 1 AND 128)
);

COMMENT ON TABLE documents.contract_acknowledgement IS
    'MVP-030 evidence that the authenticated employee made the stated confirmation of the '
    'displayed snapshot. Not an electronic signature. No IP address or user agent.';

CREATE FUNCTION documents.contract_acknowledgement_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM documents.contract c
            WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.contract_id
                AND c.state = 'ISSUED') THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_acknowledgement_immutable',
                MESSAGE = 'only an issued contract is acknowledged';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'contract_acknowledgement_immutable',
        MESSAGE = 'acknowledgement evidence is never changed or deleted';
END
$fn$;

CREATE TRIGGER contract_acknowledgement_guard
    BEFORE INSERT OR UPDATE OR DELETE ON documents.contract_acknowledgement
    FOR EACH ROW EXECUTE FUNCTION documents.contract_acknowledgement_guard();

CREATE TRIGGER contract_template_no_truncate
    BEFORE TRUNCATE ON documents.contract_template
    FOR EACH STATEMENT EXECUTE FUNCTION documents.contract_no_truncate();
CREATE TRIGGER contract_template_version_no_truncate
    BEFORE TRUNCATE ON documents.contract_template_version
    FOR EACH STATEMENT EXECUTE FUNCTION documents.contract_no_truncate();
CREATE TRIGGER contract_employment_guard_no_truncate
    BEFORE TRUNCATE ON documents.contract_employment_guard
    FOR EACH STATEMENT EXECUTE FUNCTION documents.contract_no_truncate();
CREATE TRIGGER contract_no_truncate
    BEFORE TRUNCATE ON documents.contract
    FOR EACH STATEMENT EXECUTE FUNCTION documents.contract_no_truncate();
CREATE TRIGGER contract_acknowledgement_no_truncate
    BEFORE TRUNCATE ON documents.contract_acknowledgement
    FOR EACH STATEMENT EXECUTE FUNCTION documents.contract_no_truncate();
