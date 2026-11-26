-- ---------------------------------------------------------------------------------------------
-- Manual rollback for V58__privacy_governance.sql (LAUNCH-001). NEVER run by Flyway.
--
-- Prefer rolling back only the application. This script restores the V57 schema exactly, but it REFUSES once any
-- LAUNCH-001 governance evidence exists (a policy version, a request, a hold, a preview, an application, a purge, an
-- access package, residue, or a referral stamped with a policy): that evidence is append-only and nothing here deletes
-- it, there is no override. Only the derived POLICY_PENDING stamps, which carry no decision, are dropped.
--
-- It does NOT edit flyway_schema_history: removing the V58 row is a separate, controlled operator step, taken only when
-- the application is rolled back too (runbook in docs/OPS-HR-DEV.md).
--
-- Run in one transaction.
BEGIN;
LOCK TABLE integrations.privacy_policy, integrations.privacy_policy_rule, integrations.privacy_policy_checklist,
    integrations.privacy_policy_event, integrations.privacy_preview, integrations.privacy_policy_application,
    integrations.privacy_policy_application_item, integrations.privacy_preview_item, integrations.privacy_purge, integrations.privacy_purge_item,
    integrations.privacy_referral_residue, integrations.privacy_referral_stamp, integrations.privacy_referral_stamp_rule,
    integrations.privacy_hold, integrations.privacy_hold_event, integrations.privacy_request,
    integrations.privacy_request_event, integrations.privacy_export, integrations.privacy_export_event,
    integrations.partner_referral, integrations.partner_referral_disclosure, integrations.partner_referral_consent,
    integrations.partner_referral_event, integrations.partner_referral_attempt, integrations.partner_referral_ack IN ACCESS EXCLUSIVE MODE;
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM integrations.privacy_policy)
        OR EXISTS (SELECT 1 FROM integrations.privacy_preview)
        OR EXISTS (SELECT 1 FROM integrations.privacy_policy_application)
        OR EXISTS (SELECT 1 FROM integrations.privacy_purge)
        OR EXISTS (SELECT 1 FROM integrations.privacy_purge_item)
        OR EXISTS (SELECT 1 FROM integrations.privacy_referral_residue)
        OR EXISTS (SELECT 1 FROM integrations.privacy_hold)
        OR EXISTS (SELECT 1 FROM integrations.privacy_request)
        OR EXISTS (SELECT 1 FROM integrations.privacy_export)
        OR EXISTS (SELECT 1 FROM integrations.privacy_referral_stamp WHERE status <> 'POLICY_PENDING') THEN
        RAISE EXCEPTION 'V58 rollback refused: LAUNCH-001 governance evidence exists';
    END IF;
END
$$;

-- Restore the V57 evidence triggers and the V57 completeness rule exactly.
DROP TRIGGER integrations_partner_referral_disclosure_immutable ON integrations.partner_referral_disclosure;
DROP TRIGGER integrations_partner_referral_consent_immutable ON integrations.partner_referral_consent;
DROP TRIGGER integrations_partner_referral_attempt_immutable ON integrations.partner_referral_attempt;
DROP TRIGGER integrations_partner_referral_ack_immutable ON integrations.partner_referral_ack;
DROP TRIGGER integrations_partner_referral_event_immutable ON integrations.partner_referral_event;
CREATE TRIGGER integrations_partner_referral_event_immutable
    BEFORE UPDATE OR DELETE ON integrations.partner_referral_event
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_disclosure_immutable
    BEFORE UPDATE OR DELETE ON integrations.partner_referral_disclosure
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_consent_immutable
    BEFORE UPDATE OR DELETE ON integrations.partner_referral_consent
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_attempt_immutable
    BEFORE UPDATE OR DELETE ON integrations.partner_referral_attempt
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
CREATE TRIGGER integrations_partner_referral_ack_immutable
    BEFORE UPDATE OR DELETE ON integrations.partner_referral_ack
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_immutable();
DROP FUNCTION integrations.partner_referral_evidence_guard();

-- R227-1: restore the V57 referral guard and the not-null identity columns. No lifecycle tombstone can exist here: a
-- tombstone needs a purge item, and the check above refuses once any purge item exists.
DROP TRIGGER integrations_partner_referral_scrub ON integrations.partner_referral;
DROP FUNCTION integrations.partner_referral_scrub_guard();
DROP TRIGGER integrations_partner_referral_guard ON integrations.partner_referral;
DROP TRIGGER integrations_partner_referral_guard_insert ON integrations.partner_referral;
CREATE TRIGGER integrations_partner_referral_guard BEFORE INSERT OR UPDATE ON integrations.partner_referral
    FOR EACH ROW EXECUTE FUNCTION integrations.partner_referral_guard();
ALTER TABLE integrations.partner_referral DROP CONSTRAINT integrations_partner_referral_tombstone;
ALTER TABLE integrations.partner_referral
    ALTER COLUMN employee_id SET NOT NULL,
    ALTER COLUMN partner_id SET NOT NULL,
    ALTER COLUMN product_id SET NOT NULL,
    ALTER COLUMN version_id SET NOT NULL,
    ALTER COLUMN partner_code SET NOT NULL,
    ALTER COLUMN language SET NOT NULL,
    ALTER COLUMN preview_sha256 SET NOT NULL,
    ALTER COLUMN submitted_by SET NOT NULL;

CREATE OR REPLACE FUNCTION integrations.partner_referral_complete() RETURNS trigger LANGUAGE plpgsql AS
$fn$
DECLARE
    r          integrations.partner_referral%ROWTYPE;
    n_events   integer;
    last_state text;
    n_attempts integer;
BEGIN
    IF TG_TABLE_NAME = 'partner_referral' THEN
        r := NEW;
    ELSE
        SELECT * INTO r FROM integrations.partner_referral x
         WHERE x.tenant_id = NEW.tenant_id AND x.id = NEW.referral_id;
        IF NOT FOUND THEN
            RETURN NULL;
        END IF;
    END IF;
    SELECT count(*), (array_agg(e.to_state ORDER BY e.sequence DESC))[1] INTO n_events, last_state
      FROM integrations.partner_referral_event e WHERE e.tenant_id = r.tenant_id AND e.referral_id = r.id;
    SELECT count(*) INTO n_attempts
      FROM integrations.partner_referral_attempt a WHERE a.tenant_id = r.tenant_id AND a.referral_id = r.id;
    IF NOT EXISTS (SELECT 1 FROM integrations.partner_referral_disclosure d
                    WHERE d.tenant_id = r.tenant_id AND d.referral_id = r.id)
        OR NOT EXISTS (SELECT 1 FROM integrations.partner_referral_consent c
                        WHERE c.tenant_id = r.tenant_id AND c.referral_id = r.id)
        OR n_events <> r.row_version OR last_state IS DISTINCT FROM r.state
        OR n_attempts <> r.attempt_count - (CASE WHEN r.lease_until IS NOT NULL THEN 1 ELSE 0 END) THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'integrations_partner_referral_complete',
            MESSAGE = 'a referral carries its disclosure, consent, history and attempts exactly';
    END IF;
    RETURN NULL;
END
$fn$;

DROP TRIGGER integrations_privacy_stamp_new_referral ON integrations.partner_referral;

-- Only pending stamps remain; they carry no decision. The immutability guards must not block this clean-up.
ALTER TABLE integrations.privacy_referral_stamp DISABLE TRIGGER integrations_privacy_referral_stamp_no_delete;
DELETE FROM integrations.privacy_referral_stamp;
ALTER TABLE integrations.privacy_referral_stamp ENABLE TRIGGER integrations_privacy_referral_stamp_no_delete;

DROP TABLE integrations.privacy_export_event;
DROP TABLE integrations.privacy_export;
DROP TABLE integrations.privacy_request_event;
DROP TABLE integrations.privacy_request;
DROP TABLE integrations.privacy_hold_event;
DROP TABLE integrations.privacy_hold;
DROP TABLE integrations.privacy_referral_residue;
DROP TABLE integrations.privacy_purge_item;
DROP TABLE integrations.privacy_purge;
DROP TABLE integrations.privacy_policy_application_item;
DROP TABLE integrations.privacy_policy_application;
DROP TABLE integrations.privacy_preview_item;
DROP TABLE integrations.privacy_preview;
DROP TABLE integrations.privacy_referral_stamp_rule;
DROP TABLE integrations.privacy_referral_stamp;
DROP TABLE integrations.privacy_policy_event;
DROP TABLE integrations.privacy_policy_checklist;
DROP TABLE integrations.privacy_policy_rule;
DROP TABLE integrations.privacy_policy;

DROP FUNCTION integrations.privacy_purge_complete();
DROP FUNCTION integrations.privacy_purge_item_guard();
DROP FUNCTION integrations.privacy_residue_guard();
DROP FUNCTION integrations.privacy_residue_valid(text, jsonb);
DROP FUNCTION integrations.privacy_purge_guard();
DROP FUNCTION integrations.privacy_preview_guard();
DROP FUNCTION integrations.privacy_export_guard();
DROP FUNCTION integrations.privacy_request_complete();
DROP FUNCTION integrations.privacy_request_event_guard();
DROP FUNCTION integrations.privacy_request_guard();
DROP FUNCTION integrations.privacy_hold_complete();
DROP FUNCTION integrations.privacy_hold_guard();
DROP FUNCTION integrations.privacy_referral_stamp_complete();
DROP FUNCTION integrations.privacy_referral_stamp_guard();
DROP FUNCTION integrations.privacy_stamp_new_referral();
DROP FUNCTION integrations.privacy_copy_rules(uuid, uuid, uuid);
DROP FUNCTION integrations.privacy_rules_digest(uuid);
DROP FUNCTION integrations.privacy_policy_complete();
DROP FUNCTION integrations.privacy_policy_event_guard();
DROP FUNCTION integrations.privacy_policy_guard();
DROP FUNCTION integrations.privacy_policy_checklist_guard();
DROP FUNCTION integrations.privacy_immutable();
COMMIT;
