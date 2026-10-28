-- Manual rollback for V42__operations_expense_lifecycle.sql (MVP-081, Issue #169). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V41 schema exactly, but it
-- REFUSES once any MVP-081 data exists: expenses, revisions, receipts and actions are the record of
-- what employees claimed and what managers decided, and are never erased by a schema rollback.
-- There is no override, no deletion and no retention decision. Nothing outside the V42 tables,
-- triggers and functions is touched; V42 changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V42 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE operations.expense_action, operations.expense_revision, operations.expense_receipt,
    operations.expense IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM operations.expense) OR EXISTS (SELECT 1 FROM operations.expense_revision)
        OR EXISTS (SELECT 1 FROM operations.expense_receipt)
        OR EXISTS (SELECT 1 FROM operations.expense_action) THEN
        RAISE EXCEPTION 'V42 rollback refused: expense, revision, receipt or action rows exist';
    END IF;
END
$$;

DROP TABLE operations.expense_action;
DROP TABLE operations.expense_revision;
DROP TABLE operations.expense_receipt;
DROP TABLE operations.expense;
DROP FUNCTION operations.expense_children_exact();
DROP FUNCTION operations.expense_exact();
DROP FUNCTION operations.expense_action_guard();
DROP FUNCTION operations.expense_receipt_guard();
DROP FUNCTION operations.expense_revision_guard();
DROP FUNCTION operations.expense_guard();
DROP FUNCTION operations.expense_immutable();
COMMIT;
