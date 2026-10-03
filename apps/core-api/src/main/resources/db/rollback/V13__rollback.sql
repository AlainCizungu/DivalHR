-- Manual rollback for V13__employee_import.sql (MVP-020, Issue #45). NEVER run by Flyway.
--
-- Prefer rolling back only the application: an earlier version simply ignores the people tables.
-- This script restores the V12 schema exactly, but it REFUSES while any employee, employment or
-- import row exists (architect decision E15): employee personal data and import results are never
-- dropped by an ordinary application rollback, and there is deliberately no override flag.
-- Removing them needs the documented operator procedure in docs/SECURITY.md (verified export,
-- recorded change approval, separate reviewed change). Audit and outbox rows in the platform
-- schema are untouched either way. Run in one transaction, then remove the V13 row from
-- flyway_schema_history only if the application is also rolled back.
BEGIN;

LOCK TABLE people.employee, people.employment, people.employee_import,
    people.employee_import_row IN ACCESS EXCLUSIVE MODE;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM people.employee)
        OR EXISTS (SELECT 1 FROM people.employment)
        OR EXISTS (SELECT 1 FROM people.employee_import)
        OR EXISTS (SELECT 1 FROM people.employee_import_row) THEN
        RAISE EXCEPTION 'V13 rollback refused: the people schema holds employee or import data';
    END IF;
END
$$;

DROP TABLE people.employee_import_row;
DROP TABLE people.employee_import;
DROP TABLE people.employment;
DROP TABLE people.employee;

COMMIT;
