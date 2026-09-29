-- MVP-002 Increment 3B (Issue #23): teams beneath exactly one department or cost center.
-- Organization -> Legal entity -> [Region] -> Site -> Department | Cost center -> Team.
--
-- Compatibility: no existing row changes and no default team is created. The only DDL on existing
-- tables is one composite unique key on each of tenant.department and tenant.cost_center (always
-- satisfied: id is already the primary key) and one period backstop trigger on each, which only
-- fires on period updates. The previous application version keeps working against this schema.
-- Rollback: db/rollback/V7__rollback.sql (manual; never run by Flyway). It loses every team.

-- ---------------------------------------------------------------------------------------------
-- Composite keys on the parents, so a team's (tenant, site, parent) is proven by one foreign key.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE tenant.department
    ADD CONSTRAINT department_tenant_site_id_unique UNIQUE (tenant_id, site_id, id);

ALTER TABLE tenant.cost_center
    ADD CONSTRAINT cost_center_tenant_site_id_unique UNIQUE (tenant_id, site_id, id);

-- ---------------------------------------------------------------------------------------------
-- tenant.team
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenant.team (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenant.organization (id),
    -- Derived from the parent; never accepted from a request.
    site_id        uuid        NOT NULL,
    department_id  uuid,
    cost_center_id uuid,
    code           text        NOT NULL,
    name           text        NOT NULL,
    effective_from date        NOT NULL,
    effective_to   date,
    created_at     timestamptz NOT NULL,
    created_by     text        NOT NULL,
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT team_tenant_id_unique UNIQUE (tenant_id, id),
    -- Exactly one parent: never both, never neither.
    CONSTRAINT team_exactly_one_parent CHECK (num_nonnulls(department_id, cost_center_id) = 1),
    CONSTRAINT team_site_same_tenant FOREIGN KEY (tenant_id, site_id)
        REFERENCES tenant.site (tenant_id, id),
    -- MATCH SIMPLE: the NULL parent column is not checked; with team_exactly_one_parent exactly
    -- one of these two keys is always enforced, proving tenant, site and parent belong together.
    CONSTRAINT team_department_same_tenant_and_site
        FOREIGN KEY (tenant_id, site_id, department_id)
        REFERENCES tenant.department (tenant_id, site_id, id) MATCH SIMPLE,
    CONSTRAINT team_cost_center_same_tenant_and_site
        FOREIGN KEY (tenant_id, site_id, cost_center_id)
        REFERENCES tenant.cost_center (tenant_id, site_id, id) MATCH SIMPLE,
    CONSTRAINT team_code_format CHECK (code ~ '^[A-Z0-9_-]{2,20}$'),
    CONSTRAINT team_name_trimmed_length
        CHECK (name = btrim(name) AND char_length(name) BETWEEN 2 AND 160),
    CONSTRAINT team_name_no_control_chars CHECK (name !~ '[[:cntrl:]]'),
    CONSTRAINT team_effective_order CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT team_effective_range CHECK (
        effective_from BETWEEN DATE '1900-01-01' AND DATE '2999-12-31'
        AND (effective_to IS NULL OR effective_to BETWEEN DATE '1900-01-01' AND DATE '2999-12-31')),
    CONSTRAINT team_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT team_version_non_negative CHECK (version >= 0)
);

-- Codes are unique per tenant across every parent, regardless of letter case. The API maps a
-- violation of exactly this index to DUPLICATE_TEAM_CODE.
CREATE UNIQUE INDEX team_code_ci_unique ON tenant.team (tenant_id, upper(code));
-- Keyset pagination beneath one parent: (code, id) in byte order, one partial index per type.
CREATE INDEX team_tenant_department_code_id
    ON tenant.team (tenant_id, department_id, code COLLATE "C", id)
    WHERE department_id IS NOT NULL;
CREATE INDEX team_tenant_cost_center_code_id
    ON tenant.team (tenant_id, cost_center_id, code COLLATE "C", id)
    WHERE cost_center_id IS NOT NULL;
-- Site-level team retrieval for future stories.
CREATE INDEX team_tenant_site_code_id ON tenant.team (tenant_id, site_id, code COLLATE "C", id);

COMMENT ON TABLE tenant.team IS
    'Teams; exactly one department or cost center parent, period within the parent period';

CREATE TRIGGER team_ownership_immutable
    BEFORE UPDATE ON tenant.team
    FOR EACH ROW EXECUTE FUNCTION tenant.reject_ownership_change();

-- A team never changes its site, its parent type or its parent.
CREATE FUNCTION tenant.reject_team_parent_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.site_id IS DISTINCT FROM OLD.site_id
       OR NEW.department_id IS DISTINCT FROM OLD.department_id
       OR NEW.cost_center_id IS DISTINCT FROM OLD.cost_center_id THEN
        RAISE EXCEPTION 'team parent is immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER team_parent_immutable
    BEFORE UPDATE OF site_id, department_id, cost_center_id ON tenant.team
    FOR EACH ROW EXECUTE FUNCTION tenant.reject_team_parent_change();

-- ---------------------------------------------------------------------------------------------
-- Team period within its parent (backstop for the transactional application check). An
-- open-ended team requires an open-ended parent. The parent is locked FOR SHARE.
--
-- BEFORE triggers run before CHECK constraints: with an invalid parent cardinality (both or
-- neither) this trigger does not choose a parent or dereference NULL; it returns the row so that
-- team_exactly_one_parent rejects it deterministically.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION tenant.team_period_within_parent() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    parent_from date;
    parent_to   date;
    constraint_name text;
BEGIN
    IF num_nonnulls(NEW.department_id, NEW.cost_center_id) <> 1 THEN
        RETURN NEW; -- team_exactly_one_parent reports the invalid cardinality
    END IF;
    IF NEW.department_id IS NOT NULL THEN
        SELECT effective_from, effective_to
          INTO parent_from, parent_to
          FROM tenant.department
         WHERE tenant_id = NEW.tenant_id AND site_id = NEW.site_id AND id = NEW.department_id
           FOR SHARE;
        constraint_name := 'team_period_within_department';
    ELSE
        SELECT effective_from, effective_to
          INTO parent_from, parent_to
          FROM tenant.cost_center
         WHERE tenant_id = NEW.tenant_id AND site_id = NEW.site_id AND id = NEW.cost_center_id
           FOR SHARE;
        constraint_name := 'team_period_within_cost_center';
    END IF;
    IF NOT FOUND THEN
        RETURN NEW; -- the composite foreign key reports a missing, foreign or other-site parent
    END IF;
    IF NEW.effective_from < parent_from
       OR (parent_to IS NOT NULL AND (NEW.effective_to IS NULL OR NEW.effective_to > parent_to)) THEN
        RAISE EXCEPTION 'team period must lie within its parent period'
            USING ERRCODE = 'check_violation', CONSTRAINT = constraint_name;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER team_period_within_parent
    BEFORE INSERT OR UPDATE OF effective_from, effective_to, department_id, cost_center_id, site_id
    ON tenant.team
    FOR EACH ROW EXECUTE FUNCTION tenant.team_period_within_parent();

-- ---------------------------------------------------------------------------------------------
-- Parent backstops: a department or cost center can never be narrowed below its teams. One
-- function; the statement is fixed per table name (no dynamic SQL).
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION tenant.site_unit_period_covers_teams() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_TABLE_NAME = 'department' THEN
        IF EXISTS (SELECT 1
                     FROM tenant.team t
                    WHERE t.tenant_id = NEW.tenant_id
                      AND t.department_id = NEW.id
                      AND (t.effective_from < NEW.effective_from
                           OR (NEW.effective_to IS NOT NULL
                               AND (t.effective_to IS NULL OR t.effective_to > NEW.effective_to)))) THEN
            RAISE EXCEPTION 'department period must cover its teams'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'department_period_covers_teams';
        END IF;
    ELSIF TG_TABLE_NAME = 'cost_center' THEN
        IF EXISTS (SELECT 1
                     FROM tenant.team t
                    WHERE t.tenant_id = NEW.tenant_id
                      AND t.cost_center_id = NEW.id
                      AND (t.effective_from < NEW.effective_from
                           OR (NEW.effective_to IS NOT NULL
                               AND (t.effective_to IS NULL OR t.effective_to > NEW.effective_to)))) THEN
            RAISE EXCEPTION 'cost center period must cover its teams'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'cost_center_period_covers_teams';
        END IF;
    ELSE
        RAISE EXCEPTION 'unexpected table for site_unit_period_covers_teams'
            USING ERRCODE = 'internal_error';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER department_period_covers_teams
    BEFORE UPDATE OF effective_from, effective_to ON tenant.department
    FOR EACH ROW EXECUTE FUNCTION tenant.site_unit_period_covers_teams();

CREATE TRIGGER cost_center_period_covers_teams
    BEFORE UPDATE OF effective_from, effective_to ON tenant.cost_center
    FOR EACH ROW EXECUTE FUNCTION tenant.site_unit_period_covers_teams();
