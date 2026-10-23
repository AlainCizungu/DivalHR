-- Manual rollback for V39__outbound_webhooks.sql (MVP-110, Issue #161). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V38 schema exactly, but it
-- REFUSES once any webhook exists: webhooks, their secrets and their delivery history are the
-- record of what was sent to customer endpoints and are never erased by a schema rollback. There
-- is no override and no deletion. Nothing outside the V39 tables, triggers and functions is
-- touched; V39 changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V39 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE integrations.delivery_attempt, integrations.delivery, integrations.webhook_secret,
    integrations.webhook, integrations.fanout_event, integrations.fanout_state IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM integrations.webhook)
        OR EXISTS (SELECT 1 FROM integrations.delivery)
        OR EXISTS (SELECT 1 FROM integrations.fanout_event) THEN
        RAISE EXCEPTION 'V39 rollback refused: webhook, delivery or fan-out evidence exists';
    END IF;
END
$$;

DROP TABLE integrations.delivery_attempt;
DROP TABLE integrations.delivery;
DROP TABLE integrations.webhook_secret;
DROP TABLE integrations.webhook;
DROP TABLE integrations.fanout_event;
DROP TABLE integrations.fanout_state;
DROP FUNCTION integrations.delivery_exact();
DROP FUNCTION integrations.delivery_attempt_guard();
DROP FUNCTION integrations.delivery_guard();
DROP FUNCTION integrations.webhook_secret_exact();
DROP FUNCTION integrations.webhook_secret_guard();
DROP FUNCTION integrations.webhook_guard();
DROP FUNCTION integrations.append_only();
COMMIT;
