-- MVP-002 Increment 2 (Issue #19): departments and cost centers beneath sites.
-- Organization -> Legal entity -> Site -> Department | Cost center.
-- Every row carries an immutable tenant_id taken from the verified access token; composite keys
-- make cross-tenant parent links impossible. V1-V4 are unchanged.
-- Rollback: db/rollback/V5__rollback.sql (manual; never run by Flyway).

-- ---------------------------------------------------------------------------------------------
-- Shared trigger functions for site children (departments and cost centers).
-- ---------------------------------------------------------------------------------------------

-- A department or cost center never moves to another site.
CREATE FUNCTION tenant.reject_site_unit_parent_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.site_id IS DISTINCT FROM OLD.site_id THEN
        RAISE EXCEPTION '% parent is immutable', TG_TABLE_NAME
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- Backstop for the transactional check in the application: the child period must lie within
-- its site's inclusive period; an open-ended child requires an open-ended site. The parent row
-- is locked FOR SHARE while checking. The raised constraint is <table>_period_within_site.
CREATE FUNCTION tenant.site_unit_period_within_site() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    parent_from date;
    parent_to   date;
BEGIN
    SELECT effective_from, effective_to
      INTO parent_from, parent_to
      FROM tenant.site
     WHERE tenant_id = NEW.tenant_id AND id = NEW.site_id
       FOR SHARE;
    IF NOT FOUND THEN
        RETURN NEW; -- the composite foreign key reports the missing parent
    END IF;
    IF NEW.effective_from < parent_from
       OR (parent_to IS NOT NULL AND (NEW.effective_to IS NULL OR NEW.effective_to > parent_to)) THEN
        RAISE EXCEPTION '% period must lie within its site period', TG_TABLE_NAME
            USING ERRCODE = 'check_violation', CONSTRAINT = TG_TABLE_NAME || '_period_within_site';
    END IF;
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- tenant.department
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenant.department (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenant.organization (id),
    site_id        uuid        NOT NULL,
    code           text        NOT NULL,
    name           text        NOT NULL,
    effective_from date        NOT NULL,
    effective_to   date,
    created_at     timestamptz NOT NULL,
    created_by     text        NOT NULL,
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT department_tenant_id_unique UNIQUE (tenant_id, id),
    -- Tenant-aware parent: a department can only reference a site of its own tenant.
    CONSTRAINT department_site_same_tenant FOREIGN KEY (tenant_id, site_id)
        REFERENCES tenant.site (tenant_id, id),
    CONSTRAINT department_code_format CHECK (code ~ '^[A-Z0-9_-]{2,20}$'),
    CONSTRAINT department_name_trimmed_length
        CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 160),
    CONSTRAINT department_name_no_control_chars CHECK (name !~ '[[:cntrl:]]'),
    CONSTRAINT department_effective_order CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT department_effective_range CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL OR effective_to BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')),
    CONSTRAINT department_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT department_version_non_negative CHECK (version >= 0)
);

-- Codes are unique per tenant per resource type, regardless of letter case.
CREATE UNIQUE INDEX department_code_ci_unique ON tenant.department (tenant_id, upper(code));
-- Keyset pagination within a site: (code, id) in byte order.
CREATE INDEX department_tenant_site_code_id ON tenant.department (tenant_id, site_id, code COLLATE "C", id);

COMMENT ON TABLE tenant.department IS 'Departments; period must lie within the parent site period';

CREATE TRIGGER department_ownership_immutable
    BEFORE UPDATE ON tenant.department
    FOR EACH ROW EXECUTE FUNCTION tenant.reject_ownership_change();

CREATE TRIGGER department_parent_immutable
    BEFORE UPDATE OF site_id ON tenant.department
    FOR EACH ROW EXECUTE FUNCTION tenant.reject_site_unit_parent_change();

CREATE TRIGGER department_period_within_site
    BEFORE INSERT OR UPDATE OF effective_from, effective_to, site_id ON tenant.department
    FOR EACH ROW EXECUTE FUNCTION tenant.site_unit_period_within_site();

-- ---------------------------------------------------------------------------------------------
-- tenant.cost_center
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenant.cost_center (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenant.organization (id),
    site_id        uuid        NOT NULL,
    code           text        NOT NULL,
    name           text        NOT NULL,
    effective_from date        NOT NULL,
    effective_to   date,
    created_at     timestamptz NOT NULL,
    created_by     text        NOT NULL,
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT cost_center_tenant_id_unique UNIQUE (tenant_id, id),
    -- Tenant-aware parent: a cost center can only reference a site of its own tenant.
    CONSTRAINT cost_center_site_same_tenant FOREIGN KEY (tenant_id, site_id)
        REFERENCES tenant.site (tenant_id, id),
    CONSTRAINT cost_center_code_format CHECK (code ~ '^[A-Z0-9_-]{2,20}$'),
    CONSTRAINT cost_center_name_trimmed_length
        CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 160),
    CONSTRAINT cost_center_name_no_control_chars CHECK (name !~ '[[:cntrl:]]'),
    CONSTRAINT cost_center_effective_order CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT cost_center_effective_range CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL OR effective_to BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')),
    CONSTRAINT cost_center_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT cost_center_version_non_negative CHECK (version >= 0)
);

-- Codes are unique per tenant per resource type, regardless of letter case.
CREATE UNIQUE INDEX cost_center_code_ci_unique ON tenant.cost_center (tenant_id, upper(code));
-- Keyset pagination within a site: (code, id) in byte order.
CREATE INDEX cost_center_tenant_site_code_id ON tenant.cost_center (tenant_id, site_id, code COLLATE "C", id);

COMMENT ON TABLE tenant.cost_center IS 'Cost centers; period must lie within the parent site period';

CREATE TRIGGER cost_center_ownership_immutable
    BEFORE UPDATE ON tenant.cost_center
    FOR EACH ROW EXECUTE FUNCTION tenant.reject_ownership_change();

CREATE TRIGGER cost_center_parent_immutable
    BEFORE UPDATE OF site_id ON tenant.cost_center
    FOR EACH ROW EXECUTE FUNCTION tenant.reject_site_unit_parent_change();

CREATE TRIGGER cost_center_period_within_site
    BEFORE INSERT OR UPDATE OF effective_from, effective_to, site_id ON tenant.cost_center
    FOR EACH ROW EXECUTE FUNCTION tenant.site_unit_period_within_site();

-- ---------------------------------------------------------------------------------------------
-- Site backstop: a site's period can never be narrowed below its departments or cost centers.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION tenant.site_period_covers_units() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1
                 FROM tenant.department d
                WHERE d.tenant_id = NEW.tenant_id
                  AND d.site_id = NEW.id
                  AND (d.effective_from < NEW.effective_from
                       OR (NEW.effective_to IS NOT NULL
                           AND (d.effective_to IS NULL OR d.effective_to > NEW.effective_to))))
       OR EXISTS (SELECT 1
                    FROM tenant.cost_center c
                   WHERE c.tenant_id = NEW.tenant_id
                     AND c.site_id = NEW.id
                     AND (c.effective_from < NEW.effective_from
                          OR (NEW.effective_to IS NOT NULL
                              AND (c.effective_to IS NULL OR c.effective_to > NEW.effective_to)))) THEN
        RAISE EXCEPTION 'site period must cover its departments and cost centers'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'site_period_covers_units';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER site_period_covers_units
    BEFORE UPDATE OF effective_from, effective_to ON tenant.site
    FOR EACH ROW EXECUTE FUNCTION tenant.site_period_covers_units();
