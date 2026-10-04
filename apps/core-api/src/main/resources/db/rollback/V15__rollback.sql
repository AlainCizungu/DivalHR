-- Manual rollback for V15 (MVP-022, Issue #49). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V14.2 schema exactly
-- (signature-tested), but it REFUSES once any MVP-022 business data exists: a separation, a
-- separation task, an access link, an access revocation, a SEPARATION change or a change bound to
-- a separation, or an employment with an end date. A rollback must never reactivate a separated
-- user, erase employment history, lose checklist evidence or forget a revocation, so there is no
-- override flag.
--
-- It does NOT edit flyway_schema_history: removing the V15 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md). It never
-- touches the identity provider: identities disabled by MVP-022 stay disabled.
--
-- Run in one transaction.
BEGIN;

LOCK TABLE people.employment, people.employment_change, people.employment_assignment,
    people.employment_separation, people.separation_task, people.separation_task_event,
    identity.tenant_membership, identity.employee_access_link, identity.access_revocation
    IN ACCESS EXCLUSIVE MODE;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.employment_separation)
        OR EXISTS (SELECT 1 FROM people.separation_task)
        OR EXISTS (SELECT 1 FROM people.separation_task_event)
        OR EXISTS (SELECT 1 FROM identity.employee_access_link)
        OR EXISTS (SELECT 1 FROM identity.access_revocation)
        OR EXISTS (SELECT 1 FROM people.employment_change
            WHERE type = 'SEPARATION' OR separation_id IS NOT NULL
                OR reason_code = 'MANAGER_SEPARATED')
        OR EXISTS (SELECT 1 FROM people.employment WHERE effective_to IS NOT NULL) THEN
        RAISE EXCEPTION 'V15 rollback refused: separation or access-revocation data exists';
    END IF;
END
$$;

-- identity
DROP TABLE identity.access_revocation;
DROP TABLE identity.employee_access_link;
DROP FUNCTION identity.access_revocation_guard();
DROP FUNCTION identity.employee_access_link_guard();
DROP FUNCTION identity.access_history_no_truncate();
ALTER TABLE identity.tenant_membership
    DROP CONSTRAINT tenant_membership_tenant_id_role_unique,
    DROP CONSTRAINT tenant_membership_tenant_id_unique;

-- people: triggers on V14 tables first, then the new tables and functions.
DROP TRIGGER employment_end_governed ON people.employment;
DROP TRIGGER employment_no_truncate ON people.employment;
DROP TRIGGER employment_within_employment ON people.employment;
DROP TRIGGER employment_manager_employed_on_employment ON people.employment;
DROP TRIGGER employment_assignment_within_employment ON people.employment_assignment;
DROP TRIGGER employment_manager_employed ON people.employment_assignment;
ALTER TABLE people.employment_change DROP CONSTRAINT employment_change_separation;
DROP TABLE people.separation_task_event;
DROP TABLE people.separation_task;
DROP TABLE people.employment_separation;
DROP FUNCTION people.employment_separation_shape();
DROP FUNCTION people.employment_separation_guard();
DROP FUNCTION people.separation_task_guard();
DROP FUNCTION people.separation_task_history();
DROP FUNCTION people.separation_task_event_guard();
DROP FUNCTION people.employment_end_governed();
DROP FUNCTION people.employment_assignment_within_employment();
DROP FUNCTION people.employment_rows_within(uuid);
DROP FUNCTION people.employment_manager_employed();
DROP FUNCTION people.employment_manager_covered(uuid, uuid, daterange);

-- The change log: V14 constraints exactly (names and definitions as in V14).
DROP INDEX people.employment_change_separation;
ALTER TABLE people.employment_change
    DROP CONSTRAINT employment_change_separation_valid,
    DROP CONSTRAINT employment_change_type_valid,
    ADD CONSTRAINT employment_change_type_valid
        CHECK (type IN ('HIRE', 'CHANGE', 'CORRECTION', 'CANCELLATION')),
    DROP CONSTRAINT employment_change_reason_valid,
    ADD CONSTRAINT employment_change_reason_valid CHECK (
        (type IN ('HIRE', 'CANCELLATION') AND reason_code IS NULL)
        OR (type = 'CHANGE' AND (reason_code IS NULL OR reason_code IN (
            'LATE_NOTIFICATION', 'REORGANIZATION', 'CONTRACT_CHANGE', 'OTHER_BUSINESS_CHANGE')))
        OR (type = 'CORRECTION' AND reason_code IN (
            'DATA_ENTRY_ERROR', 'IMPORT_ERROR', 'DOCUMENT_RECEIVED'))),
    DROP CONSTRAINT employment_change_state_valid,
    ADD CONSTRAINT employment_change_state_valid CHECK (
        state IN ('ACTIVE', 'CANCELLED') AND (state = 'ACTIVE' OR type = 'CHANGE')),
    DROP COLUMN separation_id;

-- The V14 change-shape validator, verbatim.
CREATE OR REPLACE FUNCTION people.employment_change_shape_check(p_change uuid) RETURNS void
    LANGUAGE plpgsql
AS $fn$
DECLARE
    c          people.employment_change%ROWTYPE;
    v_cancel   people.employment_change%ROWTYPE;
    v_employ   people.employment%ROWTYPE;
    v_bad      boolean;
    v_created  integer;
    v_replaced integer;
    v_touched  text[];
    v_kinds    text[];
BEGIN
    SELECT * INTO c FROM people.employment_change WHERE id = p_change;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT count(*) INTO v_created FROM people.employment_assignment
        WHERE created_by_change_id = c.id;
    SELECT count(*) INTO v_replaced FROM people.employment_assignment
        WHERE superseded_by_change_id = c.id;
    -- The distinct kinds the change actually touched, and its normalized kinds[].
    SELECT coalesce(array_agg(DISTINCT kind ORDER BY kind), ARRAY[]::text[]) INTO v_touched
        FROM people.employment_assignment
        WHERE created_by_change_id = c.id OR superseded_by_change_id = c.id;
    SELECT array_agg(DISTINCT k ORDER BY k) INTO v_kinds FROM unnest(c.kinds) AS k;

    IF c.type = 'HIRE' THEN
        -- One open placement row over the whole employment; nothing replaced.
        SELECT * INTO v_employ FROM people.employment WHERE id = c.employment_id;
        v_bad := v_replaced <> 0 OR v_created <> 1 OR NOT EXISTS (
            SELECT 1 FROM people.employment_assignment a
            WHERE a.created_by_change_id = c.id AND a.kind = 'PLACEMENT'
                AND a.origin_change_id = c.id AND a.restores_assignment_id IS NULL
                AND a.effective_from = v_employ.effective_from
                AND a.effective_to IS NOT DISTINCT FROM v_employ.effective_to);

    ELSIF c.type = 'CHANGE' THEN
        v_bad := v_created + v_replaced = 0
            -- Exactly the change's kinds are touched (R21-1), at most one replaced row per kind.
            OR v_touched <> v_kinds
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.superseded_by_change_id = c.id
                GROUP BY a.kind HAVING count(*) > 1)
            -- New values start on the effective date; nothing is restored.
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.created_by_change_id = c.id AND (a.restores_assignment_id IS NOT NULL
                    OR (a.origin_change_id = c.id AND a.effective_from <> c.effective_from)))
            -- A copied row keeps the replaced row's value, origin and start, and ends the day
            -- before the effective date.
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.created_by_change_id = c.id AND a.origin_change_id <> c.id
                    AND NOT EXISTS (SELECT 1 FROM people.employment_assignment r
                        WHERE r.superseded_by_change_id = c.id AND r.kind = a.kind
                            AND r.origin_change_id = a.origin_change_id
                            AND r.effective_from = a.effective_from
                            AND a.effective_to = c.effective_from - 1
                            AND people.employment_assignment_value(r)
                                = people.employment_assignment_value(a)));

    ELSIF c.type = 'CORRECTION' THEN
        -- Exactly one active row of the one kind replaced, by exactly one row with identical
        -- dates and the correction as its origin.
        v_bad := v_replaced <> 1 OR v_created <> 1 OR NOT EXISTS (
            SELECT 1 FROM people.employment_assignment n
            JOIN people.employment_assignment o ON o.superseded_by_change_id = c.id
            WHERE n.created_by_change_id = c.id AND n.kind = o.kind AND n.kind = c.kinds[1]
                AND n.origin_change_id = c.id AND n.restores_assignment_id IS NULL
                AND n.effective_from = o.effective_from
                AND n.effective_to IS NOT DISTINCT FROM o.effective_to
                AND o.effective_from = c.effective_from);

    ELSIF c.type = 'CANCELLATION' THEN
        SELECT * INTO v_cancel FROM people.employment_change WHERE id = c.cancels_change_id;
        v_bad := v_cancel.type <> 'CHANGE' OR v_cancel.state <> 'CANCELLED'
            OR c.kinds <> v_cancel.kinds OR c.effective_from <> v_cancel.effective_from
            OR v_replaced = 0
            -- Every kind of the cancelled change takes part (R21-1): no partial cancellation.
            OR v_touched <> v_kinds
            -- It replaces only rows written by, or carrying the value of, the cancelled change,
            -- and the earlier rows its restored rows extend.
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.superseded_by_change_id = c.id
                    AND a.created_by_change_id <> v_cancel.id
                    AND a.origin_change_id <> v_cancel.id
                    AND NOT EXISTS (SELECT 1 FROM people.employment_assignment n
                        WHERE n.created_by_change_id = c.id
                            AND n.restores_assignment_id = a.id))
            -- Every restored row names the row whose value and origin it carries: a row the
            -- cancelled change replaced, or an earlier restored row this cancellation extends
            -- (lineage to the cancelled change).
            OR EXISTS (SELECT 1 FROM people.employment_assignment a
                WHERE a.created_by_change_id = c.id AND NOT EXISTS (
                    SELECT 1 FROM people.employment_assignment r
                    WHERE r.id = a.restores_assignment_id
                        AND (r.superseded_by_change_id = v_cancel.id
                            OR (r.superseded_by_change_id = c.id
                                AND r.restores_assignment_id IS NOT NULL))
                        AND r.origin_change_id = a.origin_change_id
                        AND people.employment_assignment_value(r)
                            = people.employment_assignment_value(a)));
    END IF;

    IF v_bad THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'employment_change_shape',
            MESSAGE = 'an employment change wrote rows its type does not allow';
    END IF;
END
$fn$;

COMMIT;
