-- MVP-002 (Issue #12): legal entities and sites, the first tenant-owned hierarchy.
-- Organization -> Legal entity -> Site. Every row carries an immutable tenant_id taken from the
-- verified access token; composite keys make cross-tenant parent links impossible.
-- Rollback: db/rollback/V3__rollback.sql (manual; never run by Flyway).

-- ---------------------------------------------------------------------------------------------
-- Forward-only platform-schema evolution (approved in the Issue #12 review).
-- Operation and action names may now contain hyphenated segments ("legal-entity.create"). The new
-- expressions are strict supersets of the V2 ones, so every existing row still satisfies them.
-- These checks are NOT narrowed on hierarchy rollback: committed hierarchy idempotency and audit
-- rows would violate the old expression.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE platform.idempotency_record
    DROP CONSTRAINT idempotency_operation_format,
    ADD CONSTRAINT idempotency_operation_format
        CHECK (operation ~ '^[a-z]+(-[a-z]+)*(\.[a-z-]+)+$');

ALTER TABLE platform.audit_event
    DROP CONSTRAINT audit_action_format,
    ADD CONSTRAINT audit_action_format
        CHECK (action ~ '^[a-z]+(-[a-z]+)*(\.[a-z-]+)+$');

-- ---------------------------------------------------------------------------------------------
-- tenant.legal_entity
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenant.legal_entity (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenant.organization (id),
    code           text        NOT NULL,
    name           text        NOT NULL,
    country_code   char(2)     NOT NULL,
    effective_from date        NOT NULL,
    effective_to   date,
    created_at     timestamptz NOT NULL,
    created_by     text        NOT NULL,
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT legal_entity_tenant_id_unique UNIQUE (tenant_id, id),
    CONSTRAINT legal_entity_code_format CHECK (code ~ '^[A-Z0-9_-]{2,20}$'),
    CONSTRAINT legal_entity_name_trimmed_length
        CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 160),
    CONSTRAINT legal_entity_name_no_control_chars CHECK (name !~ '[[:cntrl:]]'),
    CONSTRAINT legal_entity_country_code_format CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT legal_entity_effective_order
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT legal_entity_effective_range CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL OR effective_to BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')),
    CONSTRAINT legal_entity_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT legal_entity_version_non_negative CHECK (version >= 0)
);

-- Case-insensitive uniqueness per tenant, enforced by the database. The API maps a violation of
-- exactly this index to DUPLICATE_LEGAL_ENTITY_CODE.
CREATE UNIQUE INDEX legal_entity_code_ci_unique ON tenant.legal_entity (tenant_id, upper(code));
-- Keyset pagination: (code, id) in byte order.
CREATE INDEX legal_entity_tenant_code_id ON tenant.legal_entity (tenant_id, code COLLATE "C", id);

COMMENT ON TABLE tenant.legal_entity IS 'Legal entities; tenant_id comes only from the verified token';

-- ---------------------------------------------------------------------------------------------
-- tenant.site
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenant.site (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant.organization (id),
    legal_entity_id uuid        NOT NULL,
    code            text        NOT NULL,
    name            text        NOT NULL,
    timezone        text        NOT NULL,
    effective_from  date        NOT NULL,
    effective_to    date,
    created_at      timestamptz NOT NULL,
    created_by      text        NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT site_tenant_id_unique UNIQUE (tenant_id, id),
    -- Tenant-aware parent: a site can only reference a legal entity of its own tenant.
    CONSTRAINT site_legal_entity_same_tenant FOREIGN KEY (tenant_id, legal_entity_id)
        REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT site_code_format CHECK (code ~ '^[A-Z0-9_-]{2,20}$'),
    CONSTRAINT site_name_trimmed_length
        CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 160),
    CONSTRAINT site_name_no_control_chars CHECK (name !~ '[[:cntrl:]]'),
    CONSTRAINT site_timezone_length CHECK (char_length(timezone) BETWEEN 3 AND 64),
    CONSTRAINT site_effective_order CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT site_effective_range CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL OR effective_to BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')),
    CONSTRAINT site_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT site_version_non_negative CHECK (version >= 0)
);

CREATE UNIQUE INDEX site_code_ci_unique ON tenant.site (tenant_id, upper(code));
CREATE INDEX site_tenant_legal_entity_code_id
    ON tenant.site (tenant_id, legal_entity_id, code COLLATE "C", id);

COMMENT ON TABLE tenant.site IS 'Sites; period must lie within the parent legal entity period';

-- ---------------------------------------------------------------------------------------------
-- Immutable ownership: tenant_id and id never change.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION tenant.reject_ownership_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION '% ownership is immutable', TG_TABLE_NAME
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER legal_entity_ownership_immutable
    BEFORE UPDATE ON tenant.legal_entity
    FOR EACH ROW EXECUTE FUNCTION tenant.reject_ownership_change();

CREATE TRIGGER site_ownership_immutable
    BEFORE UPDATE ON tenant.site
    FOR EACH ROW EXECUTE FUNCTION tenant.reject_ownership_change();

-- ---------------------------------------------------------------------------------------------
-- Effective-period containment (backstop for the transactional check in the application).
-- A site period must lie within its legal entity's inclusive period; an open-ended site requires
-- an open-ended legal entity. The parent row is locked FOR SHARE while checking.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION tenant.site_period_within_legal_entity() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    parent_from date;
    parent_to   date;
BEGIN
    SELECT effective_from, effective_to
      INTO parent_from, parent_to
      FROM tenant.legal_entity
     WHERE tenant_id = NEW.tenant_id AND id = NEW.legal_entity_id
       FOR SHARE;
    IF NOT FOUND THEN
        RETURN NEW; -- the composite foreign key reports the missing parent
    END IF;
    IF NEW.effective_from < parent_from
       OR (parent_to IS NOT NULL AND (NEW.effective_to IS NULL OR NEW.effective_to > parent_to)) THEN
        RAISE EXCEPTION 'site period must lie within its legal entity period'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'site_period_within_legal_entity';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER site_period_within_legal_entity
    BEFORE INSERT OR UPDATE OF effective_from, effective_to, legal_entity_id ON tenant.site
    FOR EACH ROW EXECUTE FUNCTION tenant.site_period_within_legal_entity();

CREATE FUNCTION tenant.legal_entity_period_covers_sites() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1
                 FROM tenant.site s
                WHERE s.tenant_id = NEW.tenant_id
                  AND s.legal_entity_id = NEW.id
                  AND (s.effective_from < NEW.effective_from
                       OR (NEW.effective_to IS NOT NULL
                           AND (s.effective_to IS NULL OR s.effective_to > NEW.effective_to)))) THEN
        RAISE EXCEPTION 'legal entity period must cover its sites'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'legal_entity_period_covers_sites';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER legal_entity_period_covers_sites
    BEFORE UPDATE OF effective_from, effective_to ON tenant.legal_entity
    FOR EACH ROW EXECUTE FUNCTION tenant.legal_entity_period_covers_sites();
