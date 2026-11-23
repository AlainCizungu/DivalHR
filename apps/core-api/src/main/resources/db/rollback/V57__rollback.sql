-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V57__partner_referrals.sql (MVP-120F). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V56 schema exactly, but it REFUSES once any
-- referral, disclosure, consent, delivery attempt, receipt or history row exists: a referral is consent evidence and
-- nothing here deletes it, there is no override. V57 changed no earlier row and no V56 object.
--
-- It does NOT edit flyway_schema_history: removing the V57 row is a separate, controlled operator step, taken only when
-- the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE integrations.partner_referral, integrations.partner_referral_disclosure,
    integrations.partner_referral_consent, integrations.partner_referral_event,
    integrations.partner_referral_attempt, integrations.partner_referral_ack IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM integrations.partner_referral)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_disclosure)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_consent)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_event)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_attempt)
        OR EXISTS (SELECT 1 FROM integrations.partner_referral_ack) THEN
        RAISE EXCEPTION 'V57 rollback refused: partner-referral rows exist';
    END IF;
END
$$;

DROP TABLE integrations.partner_referral_ack;
DROP TABLE integrations.partner_referral_attempt;
DROP TABLE integrations.partner_referral_event;
DROP TABLE integrations.partner_referral_consent;
DROP TABLE integrations.partner_referral_disclosure;
DROP TABLE integrations.partner_referral;

DROP FUNCTION integrations.partner_referral_complete();
DROP FUNCTION integrations.partner_referral_ack_guard();
DROP FUNCTION integrations.partner_referral_attempt_guard();
DROP FUNCTION integrations.partner_referral_event_guard();
DROP FUNCTION integrations.partner_referral_consent_guard();
DROP FUNCTION integrations.partner_referral_disclosure_guard();
DROP FUNCTION integrations.partner_referral_guard();
DROP FUNCTION integrations.partner_referral_immutable();
COMMIT;
