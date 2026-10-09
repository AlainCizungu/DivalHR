-- Manual rollback for V18__leave_policy.sql (MVP-040A). NEVER run by Flyway.
--
-- Prefer rolling back only the application: the tables are harmless to earlier versions. This
-- script restores the V17 schema exactly (signature-tested), but it REFUSES once either leave
-- policy table holds a row. Configured policies must never be erased by a schema rollback, so
-- there is no override flag.
--
-- It does NOT edit flyway_schema_history: removing the V18 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;

LOCK TABLE people.leave_policy, people.leave_policy_version IN ACCESS EXCLUSIVE MODE;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.leave_policy)
        OR EXISTS (SELECT 1 FROM people.leave_policy_version) THEN
        RAISE EXCEPTION 'V18 rollback refused: leave policy data exists';
    END IF;
END
$$;

DROP TABLE people.leave_policy_version;
DROP TABLE people.leave_policy;
DROP FUNCTION people.leave_policy_has_version();
DROP FUNCTION people.leave_policy_immutable();
DROP FUNCTION people.leave_policy_no_truncate();
DROP FUNCTION people.leave_policy_name_valid(text);

COMMIT;
