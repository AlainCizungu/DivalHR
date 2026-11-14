-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V50__payroll_accounting_journal.sql (MVP-096, Issue #194). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V49 schema exactly, but it REFUSES once any
-- MVP-096 business row exists: a mapping version, a journal or a journal line is accounting evidence of what an
-- administrator exported from an approved payroll package. None of it is erased by a schema rollback; there is no
-- override, no deletion and no retention decision. V50 seeded nothing and changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V50 row is a separate, controlled operator step, taken only
-- when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
-- ---------------------------------------------------------------------------------------------
BEGIN;
LOCK TABLE payroll.accounting_journal_line, payroll.accounting_journal, payroll.accounting_mapping IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM payroll.accounting_mapping)
        OR EXISTS (SELECT 1 FROM payroll.accounting_journal)
        OR EXISTS (SELECT 1 FROM payroll.accounting_journal_line) THEN
        RAISE EXCEPTION 'V50 rollback refused: accounting mapping, journal or line rows exist';
    END IF;
END
$$;

DROP TABLE payroll.accounting_journal_line;
DROP TABLE payroll.accounting_journal;
DROP TABLE payroll.accounting_mapping;
DROP FUNCTION payroll.accounting_journal_line_exact();
DROP FUNCTION payroll.accounting_journal_exact();
DROP FUNCTION payroll.accounting_journal_check(uuid, uuid);
DROP FUNCTION payroll.accounting_journal_line_guard();
DROP FUNCTION payroll.accounting_journal_guard();
DROP FUNCTION payroll.accounting_mapping_guard();
DROP FUNCTION payroll.accounting_immutable();
COMMIT;
