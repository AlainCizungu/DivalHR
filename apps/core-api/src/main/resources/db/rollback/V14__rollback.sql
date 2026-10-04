-- Manual rollback for V14, V14_1 and V14_2 (MVP-021, Issue #47). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V13 application schema
-- exactly (signature-tested), but it REFUSES once any employment history beyond the hire exists:
-- any CHANGE, CORRECTION or CANCELLATION, any assignment other than the hire placement, or any
-- superseded row (architect amendment M21-3). Employee history, audit and outbox rows are never
-- removed by an application rollback; there is no override flag.
--
-- It does NOT edit flyway_schema_history: removing the V14, V14.1 and V14.2 rows is a separate,
-- controlled operator step, taken only when the application is rolled back too (runbook in
-- docs/SECURITY.md). It does NOT drop the btree_gist extension: the extension is a database
-- prerequisite that may be shared, and V12/V13 ignore it (documented infrastructure difference).
--
-- Run in one transaction.
BEGIN;

LOCK TABLE people.employee, people.employment, people.employment_change,
    people.employment_assignment IN ACCESS EXCLUSIVE MODE;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.employment_change WHERE type <> 'HIRE')
        OR EXISTS (SELECT 1 FROM people.employment_assignment a
            WHERE a.kind <> 'PLACEMENT' OR a.superseded_by_change_id IS NOT NULL
                OR a.restores_assignment_id IS NOT NULL
                OR a.origin_change_id <> a.created_by_change_id
                OR NOT EXISTS (SELECT 1 FROM people.employment_change c
                    WHERE c.id = a.created_by_change_id AND c.type = 'HIRE'))
        OR EXISTS (SELECT 1 FROM people.employment e
            WHERE (SELECT count(*) FROM people.employment_assignment a
                WHERE a.employment_id = e.id) <> 1) THEN
        RAISE EXCEPTION 'V14 rollback refused: employment history beyond the hire exists';
    END IF;
END
$$;

-- The coverage trigger on employment goes first: copying the columns back must not queue its
-- deferred check.
DROP TRIGGER employment_placement_coverage_on_employment ON people.employment;

-- Restore the V13 placement columns from the single hire placement row of each employment.
ALTER TABLE people.employment
    ADD COLUMN legal_entity_id uuid,
    ADD COLUMN site_id uuid,
    ADD COLUMN department_id uuid,
    ADD COLUMN cost_center_id uuid,
    ADD COLUMN team_id uuid;

UPDATE people.employment e
SET legal_entity_id = a.legal_entity_id, site_id = a.site_id, department_id = a.department_id,
    cost_center_id = a.cost_center_id, team_id = a.team_id
FROM people.employment_assignment a
WHERE a.employment_id = e.id;

-- Drop the V14 history and its triggers and functions (dropping the tables drops their triggers).
-- This helper takes the assignment row type, so it goes before the table.
DROP FUNCTION people.employment_assignment_value(people.employment_assignment);
DROP TABLE people.employment_assignment;
DROP TABLE people.employment_change;
DROP FUNCTION people.employment_change_shape();
DROP FUNCTION people.employment_assignment_change_shape();
DROP FUNCTION people.employment_change_shape_check(uuid);
DROP FUNCTION people.employment_manager_acyclic();
DROP FUNCTION people.employment_placement_coverage();
DROP FUNCTION people.employment_placement_covered(uuid);
DROP FUNCTION people.employment_history_no_truncate();
DROP FUNCTION people.employment_change_guard();
DROP FUNCTION people.employment_assignment_guard();

ALTER TABLE people.employment
    DROP CONSTRAINT employment_no_overlap,
    DROP CONSTRAINT employment_tenant_id_employee_unique;

ALTER TABLE people.employee
    DROP CONSTRAINT employee_search_key_format,
    DROP COLUMN search_key;

-- Restore the V13 columns' constraints exactly (names and definitions as in V13). The columns
-- are appended at the end of the table; the application addresses them by name.
ALTER TABLE people.employment
    ALTER COLUMN legal_entity_id SET NOT NULL,
    ALTER COLUMN site_id SET NOT NULL,
    ADD CONSTRAINT employment_one_site_unit
        CHECK (num_nonnulls(department_id, cost_center_id) <= 1),
    ADD CONSTRAINT employment_team_has_parent
        CHECK (team_id IS NULL OR num_nonnulls(department_id, cost_center_id) = 1);

COMMIT;
