-- Manual rollback for V41__operations_task_lifecycle.sql (MVP-080, Issue #167). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V40 schema exactly, but it
-- REFUSES once any MVP-080 data exists: tasks and their actions are the record of what was assigned,
-- done and cancelled, and are never erased by a schema rollback. There is no override, no deletion
-- and no retention decision. Nothing outside the V41 tables, triggers and functions is touched;
-- V41 changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V41 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE operations.task_action, operations.task IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM operations.task) OR EXISTS (SELECT 1 FROM operations.task_action) THEN
        RAISE EXCEPTION 'V41 rollback refused: task or task-action rows exist';
    END IF;
END
$$;

DROP TABLE operations.task_action;
DROP TABLE operations.task;
DROP FUNCTION operations.task_action_exact();
DROP FUNCTION operations.task_exact();
DROP FUNCTION operations.task_action_guard();
DROP FUNCTION operations.task_guard();
DROP FUNCTION operations.task_append_only();
DROP FUNCTION operations.task_text_valid(text, integer, integer);
COMMIT;
