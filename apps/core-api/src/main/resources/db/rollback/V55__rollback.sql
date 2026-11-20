-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V55__partner_applications.sql (MVP-120D, Issue #215). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V54 schema exactly, but it REFUSES once any
-- partner-application business row exists: applications are immutable commercial records and nothing here deletes
-- them, there is no override. V55 changed no earlier row.
--
-- It does NOT edit flyway_schema_history: removing the V55 row is a separate, controlled operator step, taken only when
-- the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE integrations.partner_application, integrations.partner_application_category,
    integrations.partner_application_capability, integrations.partner_application_consent,
    integrations.partner_application_check, integrations.partner_application_event,
    integrations.partner_application_submission IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM integrations.partner_application)
        OR EXISTS (SELECT 1 FROM integrations.partner_application_category)
        OR EXISTS (SELECT 1 FROM integrations.partner_application_capability)
        OR EXISTS (SELECT 1 FROM integrations.partner_application_consent)
        OR EXISTS (SELECT 1 FROM integrations.partner_application_check)
        OR EXISTS (SELECT 1 FROM integrations.partner_application_event)
        OR EXISTS (SELECT 1 FROM integrations.partner_application_submission) THEN
        RAISE EXCEPTION 'V55 rollback refused: partner-application rows exist';
    END IF;
END
$$;

DROP TABLE integrations.partner_application_submission;
DROP TABLE integrations.partner_application_event;
DROP TABLE integrations.partner_application_check;
DROP TABLE integrations.partner_application_consent;
DROP TABLE integrations.partner_application_capability;
DROP TABLE integrations.partner_application_category;
DROP TABLE integrations.partner_application;
DROP FUNCTION integrations.partner_application_complete();
DROP FUNCTION integrations.partner_application_event_guard();
DROP FUNCTION integrations.partner_application_check_guard();
DROP FUNCTION integrations.partner_application_guard();
DROP FUNCTION integrations.partner_application_immutable();
COMMIT;
