-- Manual rollback for V40__whatsapp_messaging_connector.sql (MVP-111, Issue #163). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V39 schema exactly, but it
-- REFUSES once any MVP-111 data exists: connectors, recipients, deliveries, attempts, callbacks and
-- the fan-out ledger are the record of what was sent to customers' staff and are never erased by a
-- schema rollback. There is no override and no deletion. Nothing outside the V40 tables, triggers and
-- functions is touched; V40 changed no earlier object.
--
-- It does NOT edit flyway_schema_history: removing the V40 row is a separate, controlled operator
-- step, taken only when the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE integrations.messaging_status_callback, integrations.messaging_delivery_attempt,
    integrations.messaging_delivery, integrations.messaging_recipient, integrations.messaging_credential,
    integrations.messaging_template, integrations.messaging_connector, integrations.messaging_fanout_event,
    integrations.messaging_fanout_state IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM integrations.messaging_connector)
        OR EXISTS (SELECT 1 FROM integrations.messaging_recipient)
        OR EXISTS (SELECT 1 FROM integrations.messaging_delivery)
        OR EXISTS (SELECT 1 FROM integrations.messaging_fanout_event) THEN
        RAISE EXCEPTION 'V40 rollback refused: messaging connector, recipient, delivery or fan-out evidence exists';
    END IF;
END
$$;

DROP TABLE integrations.messaging_status_callback;
DROP TABLE integrations.messaging_delivery_attempt;
DROP TABLE integrations.messaging_delivery;
DROP TABLE integrations.messaging_recipient;
DROP TABLE integrations.messaging_credential;
DROP TABLE integrations.messaging_template;
DROP TABLE integrations.messaging_connector;
DROP TABLE integrations.messaging_fanout_event;
DROP TABLE integrations.messaging_fanout_state;
DROP FUNCTION integrations.messaging_delivery_exact();
DROP FUNCTION integrations.messaging_attempt_guard();
DROP FUNCTION integrations.messaging_delivery_guard();
DROP FUNCTION integrations.messaging_delivery_rank(text);
DROP FUNCTION integrations.messaging_recipient_guard();
DROP FUNCTION integrations.messaging_credential_exact();
DROP FUNCTION integrations.messaging_credential_guard();
DROP FUNCTION integrations.messaging_connector_guard();
DROP FUNCTION integrations.messaging_append_only();
COMMIT;
