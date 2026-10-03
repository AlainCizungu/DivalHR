-- MVP-020 (Issue #45, architect decision E1-E16 and amendments A20-1..A20-6): employees, their
-- first employment, and the employee import that creates them. The first tables of the people
-- module; only the people module reads or writes them.
--
-- Hierarchy units (legal entity, site, department, cost center, team) are referenced by ID and
-- validated through the tenant module's OrganizationPlacementDirectory port, inside the writing
-- transaction; there are deliberately no foreign keys into tenant unit tables (E13: the people
-- schema stays private and extractable; units cannot be deleted or moved today). As for the
-- identity schema, tenant_id references the tenant root tenant.organization.
--
-- Text limits count Unicode code points of NFC-normalized text (A20-6): char_length on a UTF-8
-- database counts code points, and IS NFC NORMALIZED rejects any other normal form.
--
-- Personal data: employee number and names (Confidential), start date and placement (Restricted
-- HR). Import staging holds normalized values of valid rows only, and only while the import is
-- open (VALIDATED). Import rows never keep a link to the employee they created (A20-2).
--
-- Rollback: db/rollback/V13__rollback.sql (manual; never run by Flyway; refuses while any
-- employee or import row exists).

-- ---------------------------------------------------------------------------------------------
-- people.employee: a worker known to one tenant (E3, E4)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE people.employee (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    employee_number text        NOT NULL,
    given_names     text        NOT NULL,
    family_name     text        NOT NULL,
    created_at      timestamptz NOT NULL,
    created_by      text        NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT employee_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT employee_tenant_id_unique UNIQUE (tenant_id, id),
    -- Normalized (upper-case) employee numbers are unique per tenant (E8).
    CONSTRAINT employee_number_unique UNIQUE (tenant_id, employee_number),
    CONSTRAINT employee_number_format CHECK (employee_number ~ '^[A-Z0-9][A-Z0-9._/-]{0,31}$'),
    CONSTRAINT employee_given_names_valid CHECK (
        given_names IS NFC NORMALIZED
        AND char_length(given_names) BETWEEN 1 AND 100
        AND given_names = btrim(given_names)
        AND given_names !~ '[[:cntrl:]]'
        AND given_names !~ '  '
        AND given_names !~ '^[=+@-]'),
    CONSTRAINT employee_family_name_valid CHECK (
        family_name IS NFC NORMALIZED
        AND char_length(family_name) BETWEEN 1 AND 100
        AND family_name = btrim(family_name)
        AND family_name !~ '[[:cntrl:]]'
        AND family_name !~ '  '
        AND family_name !~ '^[=+@-]'),
    CONSTRAINT employee_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT employee_version_non_negative CHECK (version >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- people.employment: an effective-dated placement (E3, section 10). MVP-020 creates the first,
-- open-ended employment; history changes belong to MVP-021 and ends to MVP-022.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE people.employment (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    employee_id     uuid        NOT NULL,
    legal_entity_id uuid        NOT NULL,
    site_id         uuid        NOT NULL,
    department_id   uuid,
    cost_center_id  uuid,
    team_id         uuid,
    effective_from  date        NOT NULL,
    effective_to    date,
    created_at      timestamptz NOT NULL,
    created_by      text        NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT employment_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT employment_employee_same_tenant FOREIGN KEY (tenant_id, employee_id)
        REFERENCES people.employee (tenant_id, id),
    -- A department or a cost center, never both (like teams).
    CONSTRAINT employment_one_site_unit CHECK (num_nonnulls(department_id, cost_center_id) <= 1),
    -- A team always sits under a department or a cost center (derived from the team if omitted).
    CONSTRAINT employment_team_has_parent
        CHECK (team_id IS NULL OR num_nonnulls(department_id, cost_center_id) = 1),
    CONSTRAINT employment_effective_order
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT employment_effective_range CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL OR effective_to BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')),
    CONSTRAINT employment_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT employment_version_non_negative CHECK (version >= 0)
);

CREATE INDEX employment_employee ON people.employment (tenant_id, employee_id);

-- ---------------------------------------------------------------------------------------------
-- people.employee_import: one upload, its counts and lifecycle (E5-E9, A20-2)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE people.employee_import (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    status             text        NOT NULL,
    created_at         timestamptz NOT NULL,
    created_by         text        NOT NULL,
    expires_at         timestamptz NOT NULL,
    file_sha256        char(64)    NOT NULL,
    preview_digest     char(64)    NOT NULL,
    delimiter          text        NOT NULL,
    header_language    text        NOT NULL,
    total_rows         integer     NOT NULL,
    valid_rows         integer     NOT NULL,
    invalid_rows       integer     NOT NULL,
    created_count      integer,
    committed_at       timestamptz,
    committed_by       text,
    closed_at          timestamptz,
    CONSTRAINT employee_import_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES tenant.organization (id),
    CONSTRAINT employee_import_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT employee_import_status_valid
        CHECK (status IN ('VALIDATED', 'COMMITTED', 'DISCARDED', 'EXPIRED')),
    CONSTRAINT employee_import_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT employee_import_expiry_after_creation CHECK (expires_at > created_at),
    CONSTRAINT employee_import_file_sha256_format CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT employee_import_preview_digest_format CHECK (preview_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT employee_import_delimiter_valid CHECK (delimiter IN ('COMMA', 'SEMICOLON')),
    CONSTRAINT employee_import_header_language_valid
        CHECK (header_language IN ('fr', 'en', 'mixed')),
    -- Hard cap above the configurable row limit (100-5,000).
    CONSTRAINT employee_import_counts_valid CHECK (
        total_rows BETWEEN 1 AND 5000
        AND valid_rows >= 0 AND invalid_rows >= 0
        AND valid_rows + invalid_rows = total_rows),
    -- Commit fields exist exactly for committed imports.
    CONSTRAINT employee_import_commit_fields CHECK (
        (status = 'COMMITTED')
            = (created_count IS NOT NULL AND committed_at IS NOT NULL AND committed_by IS NOT NULL)),
    CONSTRAINT employee_import_created_count_valid
        CHECK (created_count IS NULL OR (created_count >= 1 AND created_count = valid_rows)),
    CONSTRAINT employee_import_committed_by_length
        CHECK (committed_by IS NULL OR char_length(committed_by) BETWEEN 1 AND 255),
    -- Only open imports have no closing time.
    CONSTRAINT employee_import_closed_at CHECK ((status = 'VALIDATED') = (closed_at IS NULL))
);

-- Open-import cap per tenant, the expiry job and the retention job.
CREATE INDEX employee_import_tenant_status ON people.employee_import (tenant_id, status, expires_at);
CREATE INDEX employee_import_closed ON people.employee_import (closed_at) WHERE closed_at IS NOT NULL;
CREATE INDEX employee_import_open_expiry ON people.employee_import (expires_at)
    WHERE status = 'VALIDATED';

-- ---------------------------------------------------------------------------------------------
-- people.employee_import_row: per-row outcome; staged values only for valid rows of an open
-- import (E6). No link to the created employee is kept (A20-2).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE people.employee_import_row (
    tenant_id         uuid    NOT NULL,
    import_id         uuid    NOT NULL,
    row_number        integer NOT NULL,
    status            text    NOT NULL,
    error_columns     text[]  NOT NULL DEFAULT '{}',
    error_codes       text[]  NOT NULL DEFAULT '{}',
    employee_number   text,
    given_names       text,
    family_name       text,
    start_date        date,
    legal_entity_code text,
    site_code         text,
    department_code   text,
    cost_center_code  text,
    team_code         text,
    CONSTRAINT employee_import_row_pk PRIMARY KEY (tenant_id, import_id, row_number),
    CONSTRAINT employee_import_row_import_same_tenant FOREIGN KEY (tenant_id, import_id)
        REFERENCES people.employee_import (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT employee_import_row_number_range CHECK (row_number BETWEEN 1 AND 5000),
    CONSTRAINT employee_import_row_status_valid
        CHECK (status IN ('VALID', 'INVALID', 'CREATED', 'NOT_IMPORTED')),
    CONSTRAINT employee_import_row_errors_paired CHECK (
        cardinality(error_codes) = cardinality(error_columns) AND cardinality(error_codes) <= 10),
    -- INVALID rows carry their errors; VALID and CREATED rows none. When an import closes, VALID
    -- rows become CREATED (commit) or NOT_IMPORTED without errors (discard, expiry), and INVALID
    -- rows become NOT_IMPORTED keeping their codes.
    CONSTRAINT employee_import_row_errors_by_status CHECK (
        (status <> 'INVALID' OR cardinality(error_codes) > 0)
        AND (status NOT IN ('VALID', 'CREATED') OR cardinality(error_codes) = 0)),
    CONSTRAINT employee_import_row_error_codes_known CHECK (error_codes <@ ARRAY[
        'ROW_SHAPE', 'ROW_REQUIRED', 'ROW_TOO_LONG', 'ROW_CONTROL_CHARACTER', 'ROW_FORMAT',
        'ROW_DATE_FORMAT', 'ROW_DATE_RANGE', 'ROW_EMPLOYEE_NUMBER_REPEATED',
        'ROW_EMPLOYEE_NUMBER_EXISTS', 'ROW_UNIT_NOT_FOUND', 'ROW_UNIT_MISMATCH',
        'ROW_UNIT_NOT_EFFECTIVE', 'ROW_PARENT_AMBIGUOUS']::text[]),
    CONSTRAINT employee_import_row_error_columns_known CHECK (error_columns <@ ARRAY[
        'employee_number', 'given_names', 'family_name', 'start_date', 'legal_entity_code',
        'site_code', 'department_code', 'cost_center_code', 'team_code']::text[]),
    -- Staged values: all required values exactly for VALID rows; none otherwise (E6, A20-2).
    CONSTRAINT employee_import_row_values_only_when_valid CHECK (
        (status = 'VALID') = (employee_number IS NOT NULL AND given_names IS NOT NULL
            AND family_name IS NOT NULL AND start_date IS NOT NULL
            AND legal_entity_code IS NOT NULL AND site_code IS NOT NULL)
        AND (status = 'VALID' OR num_nonnulls(employee_number, given_names, family_name,
            start_date, legal_entity_code, site_code, department_code, cost_center_code,
            team_code) = 0)),
    CONSTRAINT employee_import_row_number_format
        CHECK (employee_number IS NULL OR employee_number ~ '^[A-Z0-9][A-Z0-9._/-]{0,31}$'),
    CONSTRAINT employee_import_row_names_valid CHECK (
        (given_names IS NULL OR (given_names IS NFC NORMALIZED
            AND char_length(given_names) BETWEEN 1 AND 100 AND given_names !~ '[[:cntrl:]]'))
        AND (family_name IS NULL OR (family_name IS NFC NORMALIZED
            AND char_length(family_name) BETWEEN 1 AND 100 AND family_name !~ '[[:cntrl:]]'))),
    CONSTRAINT employee_import_row_unit_codes_format CHECK (
        (legal_entity_code IS NULL OR legal_entity_code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$')
        AND (site_code IS NULL OR site_code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$')
        AND (department_code IS NULL OR department_code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$')
        AND (cost_center_code IS NULL OR cost_center_code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$')
        AND (team_code IS NULL OR team_code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$')),
    CONSTRAINT employee_import_row_start_date_range CHECK (
        start_date IS NULL OR start_date BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')
);
