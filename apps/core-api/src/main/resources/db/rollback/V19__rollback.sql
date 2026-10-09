-- Manual rollback for V19__leave_request.sql (MVP-041A, Issue #87). NEVER run by Flyway.
--
-- Prefer rolling back only the application: the table is harmless to earlier versions. This
-- script restores the V18 schema exactly (signature-tested), but it REFUSES once any leave request
-- exists. Employees' submitted requests must never be erased by a schema rollback, so there is no
-- override flag.
--
-- It does NOT edit flyway_schema_history: removing the V19 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/SECURITY.md).
--
-- Run in one transaction.
BEGIN;

LOCK TABLE people.leave_request IN ACCESS EXCLUSIVE MODE;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.leave_request) THEN
        RAISE EXCEPTION 'V19 rollback refused: leave request data exists';
    END IF;
END
$$;

DROP TABLE people.leave_request;
DROP FUNCTION people.leave_request_no_truncate();
DROP FUNCTION people.leave_request_immutable();

COMMIT;
