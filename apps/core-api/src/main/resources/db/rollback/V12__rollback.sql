-- Manual rollback for V12__authorization_denial.sql (MVP-013, Issue #43). NEVER run by Flyway.
--
-- Prefer rolling back only the application: an earlier version simply stops writing to the table.
-- This script restores the V11 schema exactly, but it REFUSES while the table holds any row
-- (architect decision A13-4): denial rows are security evidence and are never dropped by an
-- ordinary application rollback. There is deliberately no override flag.
--
-- Removing a table that holds evidence is outside normal rollback. It needs the documented operator
-- procedure in docs/SECURITY.md ("Authorization denial audit", destructive removal): a verified
-- export, a recorded change approval, and a separate reviewed change. Run in one transaction, then
-- remove the V12 row from flyway_schema_history only if the application is also rolled back.
BEGIN;

LOCK TABLE platform.authorization_denial IN ACCESS EXCLUSIVE MODE;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM platform.authorization_denial) THEN
        RAISE EXCEPTION 'V12 rollback refused: platform.authorization_denial holds denial evidence';
    END IF;
END
$$;

DROP TABLE platform.authorization_denial;
DROP FUNCTION platform.authorization_denial_append_only();

COMMIT;
