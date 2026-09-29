-- MVP-002 Increment 3A (Issue #21): regions beneath a legal entity and optional site assignment.
-- Organization -> Legal entity -> [Region] -> Site -> Department | Cost center.
--
-- Compatibility: this migration only adds objects. tenant.site gains a nullable region_id with no
-- default and no backfill, so every existing site stays valid and unassigned (region_id IS NULL);
-- no default region is ever created. No existing column, constraint or trigger changes, and the
-- previous application version keeps working against this schema (its statements list columns).
-- Rollback: db/rollback/V6__rollback.sql (manual; never run by Flyway). It loses every region and
-- every site-region assignment.

-- ---------------------------------------------------------------------------------------------
-- tenant.region
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenant.region (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant.organization (id),
    legal_entity_id uuid        NOT NULL,
    code            text        NOT NULL,
    name            text        NOT NULL,
    effective_from  date        NOT NULL,
    effective_to    date,
    created_at      timestamptz NOT NULL,
    created_by      text        NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT region_tenant_id_unique UNIQUE (tenant_id, id),
    -- Referenced by tenant.site: a site's region must share its tenant and its legal entity.
    CONSTRAINT region_tenant_legal_entity_id_unique UNIQUE (tenant_id, legal_entity_id, id),
    -- Tenant-aware parent: a region can only reference a legal entity of its own tenant.
    CONSTRAINT region_legal_entity_same_tenant FOREIGN KEY (tenant_id, legal_entity_id)
        REFERENCES tenant.legal_entity (tenant_id, id),
    CONSTRAINT region_code_format CHECK (code ~ '^[A-Z0-9_-]{2,20}$'),
    CONSTRAINT region_name_trimmed_length
        CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 160),
    CONSTRAINT region_name_no_control_chars CHECK (name !~ '[[:cntrl:]]'),
    CONSTRAINT region_effective_order CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT region_effective_range CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL OR effective_to BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')),
    CONSTRAINT region_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT region_version_non_negative CHECK (version >= 0)
);

-- Codes are unique per tenant (across legal entities), regardless of letter case. The API maps a
-- violation of exactly this index to DUPLICATE_REGION_CODE.
CREATE UNIQUE INDEX region_code_ci_unique ON tenant.region (tenant_id, upper(code));
-- Keyset pagination within a legal entity: (code, id) in byte order.
CREATE INDEX region_tenant_legal_entity_code_id
    ON tenant.region (tenant_id, legal_entity_id, code COLLATE "C", id);

COMMENT ON TABLE tenant.region IS
    'Regions (optional site grouping); period must lie within the parent legal entity period';

CREATE TRIGGER region_ownership_immutable
    BEFORE UPDATE ON tenant.region
    FOR EACH ROW EXECUTE FUNCTION tenant.reject_ownership_change();

-- A row whose parent is a legal entity never moves to another legal entity.
CREATE FUNCTION tenant.reject_legal_entity_parent_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id THEN
        RAISE EXCEPTION '% parent is immutable', TG_TABLE_NAME
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER region_parent_immutable
    BEFORE UPDATE OF legal_entity_id ON tenant.region
    FOR EACH ROW EXECUTE FUNCTION tenant.reject_legal_entity_parent_change();

-- ---------------------------------------------------------------------------------------------
-- Region period within its legal entity (backstop for the transactional application check). An
-- open-ended region requires an open-ended legal entity. The parent is locked FOR SHARE.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION tenant.region_period_within_legal_entity() RETURNS trigger
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
        RAISE EXCEPTION 'region period must lie within its legal entity period'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'region_period_within_legal_entity';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER region_period_within_legal_entity
    BEFORE INSERT OR UPDATE OF effective_from, effective_to, legal_entity_id ON tenant.region
    FOR EACH ROW EXECUTE FUNCTION tenant.region_period_within_legal_entity();

-- A legal entity can never be narrowed below its regions. The V3 trigger
-- legal_entity_period_covers_sites keeps protecting every site, assigned or not.
CREATE FUNCTION tenant.legal_entity_period_covers_regions() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1
                 FROM tenant.region r
                WHERE r.tenant_id = NEW.tenant_id
                  AND r.legal_entity_id = NEW.id
                  AND (r.effective_from < NEW.effective_from
                       OR (NEW.effective_to IS NOT NULL
                           AND (r.effective_to IS NULL OR r.effective_to > NEW.effective_to)))) THEN
        RAISE EXCEPTION 'legal entity period must cover its regions'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'legal_entity_period_covers_regions';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER legal_entity_period_covers_regions
    BEFORE UPDATE OF effective_from, effective_to ON tenant.legal_entity
    FOR EACH ROW EXECUTE FUNCTION tenant.legal_entity_period_covers_regions();

-- ---------------------------------------------------------------------------------------------
-- tenant.site: optional region.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE tenant.site ADD COLUMN region_id uuid;

-- MATCH SIMPLE (the default): a NULL region_id is not checked, so unassigned sites stay valid. A
-- non-NULL region must belong to the site's own tenant AND to the site's own legal entity, so
-- direct SQL cannot link a site to a region of another tenant or another legal entity. MATCH FULL
-- would reject every existing (unassigned) site and is deliberately not used.
ALTER TABLE tenant.site
    ADD CONSTRAINT site_region_same_tenant_and_legal_entity
        FOREIGN KEY (tenant_id, legal_entity_id, region_id)
        REFERENCES tenant.region (tenant_id, legal_entity_id, id) MATCH SIMPLE;

-- Serves the region backstop below and foreign-key checks from tenant.region.
CREATE INDEX site_tenant_region ON tenant.site (tenant_id, region_id) WHERE region_id IS NOT NULL;

COMMENT ON COLUMN tenant.site.region_id IS
    'Optional region of the same tenant and legal entity; NULL for a site without a region';

-- First assignment only (Issue #21): NULL -> region is allowed, and so is a no-op update to the
-- same region; changing or clearing an assigned region waits for the hierarchy-editing increment.
CREATE FUNCTION tenant.site_region_assigned_once() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.region_id IS NOT NULL AND NEW.region_id IS DISTINCT FROM OLD.region_id THEN
        RAISE EXCEPTION 'an assigned site region cannot be changed or cleared'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'site_region_assigned_once';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER site_region_assigned_once
    BEFORE UPDATE OF region_id ON tenant.site
    FOR EACH ROW EXECUTE FUNCTION tenant.site_region_assigned_once();

-- An assigned site's period must lie within its region's period. The region is locked FOR SHARE.
-- The site's period within its legal entity stays enforced by V3 site_period_within_legal_entity.
CREATE FUNCTION tenant.site_period_within_region() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    region_from date;
    region_to   date;
BEGIN
    IF NEW.region_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT effective_from, effective_to
      INTO region_from, region_to
      FROM tenant.region
     WHERE tenant_id = NEW.tenant_id
       AND legal_entity_id = NEW.legal_entity_id
       AND id = NEW.region_id
       FOR SHARE;
    IF NOT FOUND THEN
        -- The composite foreign key reports a missing region, a region of another tenant and a
        -- region of another legal entity.
        RETURN NEW;
    END IF;
    IF NEW.effective_from < region_from
       OR (region_to IS NOT NULL AND (NEW.effective_to IS NULL OR NEW.effective_to > region_to)) THEN
        RAISE EXCEPTION 'site period must lie within its region period'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'site_period_within_region';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER site_period_within_region
    BEFORE INSERT OR UPDATE OF region_id, effective_from, effective_to ON tenant.site
    FOR EACH ROW EXECUTE FUNCTION tenant.site_period_within_region();

-- A region can never be narrowed below an assigned site.
CREATE FUNCTION tenant.region_period_covers_sites() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1
                 FROM tenant.site s
                WHERE s.tenant_id = NEW.tenant_id
                  AND s.region_id = NEW.id
                  AND (s.effective_from < NEW.effective_from
                       OR (NEW.effective_to IS NOT NULL
                           AND (s.effective_to IS NULL OR s.effective_to > NEW.effective_to)))) THEN
        RAISE EXCEPTION 'region period must cover its sites'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'region_period_covers_sites';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER region_period_covers_sites
    BEFORE UPDATE OF effective_from, effective_to ON tenant.region
    FOR EACH ROW EXECUTE FUNCTION tenant.region_period_covers_sites();
