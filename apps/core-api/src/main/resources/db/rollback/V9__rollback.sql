-- Manual rollback for V9__invitation_origin.sql (Issue #38). NEVER run by Flyway.
--
-- Prefer rolling back the APPLICATION only: V9 is additive (the origin column has a default) and the
-- previous application version works unchanged against it.
--
-- This script restores the V8 schema exactly: it drops the origin column, its checks and indexes,
-- and re-creates the V8 transition function verbatim. Bootstrap invitations then become ordinary
-- tenant-admin invitations (their origin is lost). It refuses to run while a bootstrap invitation is
-- open, because without V9 its acceptance would no longer be re-checked as the organization's first
-- administrator: revoke it or let it expire first. Superseded bootstrap invitations stay as ordinary
-- REVOKED rows, which V8 represents; their system marker becomes
-- 'system:bootstrap-superseded-rolled-back' so that V9 can be applied again (its check reserves the
-- original marker for bootstrap invitations, and re-applied rows are TENANT_ADMIN). Idempotency records of the bootstrap operations are purged so that no key
-- replays a receipt for an operation that no longer exists.
--
-- Run in one transaction, then remove the V9 row from flyway_schema_history only if the application
-- is also rolled back.
BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM identity.invitation
               WHERE origin = 'PLATFORM_BOOTSTRAP' AND state IN ('PENDING', 'ACCEPTING')) THEN
        RAISE EXCEPTION 'V9 rollback refused: open bootstrap invitations exist';
    END IF;
END;
$$;

DELETE FROM platform.idempotency_record
    WHERE operation IN ('tenant-admin-bootstrap.create', 'tenant-admin-bootstrap.resend');

ALTER TABLE identity.invitation DISABLE TRIGGER invitation_transition_allowed;
UPDATE identity.invitation SET revoked_by = 'system:bootstrap-superseded-rolled-back'
    WHERE revoked_by = 'system:bootstrap-superseded';
ALTER TABLE identity.invitation ENABLE TRIGGER invitation_transition_allowed;

DROP INDEX IF EXISTS identity.invitation_open_tenant_admin;
DROP INDEX IF EXISTS identity.invitation_one_open_bootstrap;
ALTER TABLE identity.invitation
    DROP CONSTRAINT IF EXISTS invitation_superseded_is_bootstrap,
    DROP CONSTRAINT IF EXISTS invitation_bootstrap_is_tenant_admin,
    DROP CONSTRAINT IF EXISTS invitation_origin_valid;
ALTER TABLE identity.invitation DROP COLUMN IF EXISTS origin;

-- The V8 function, verbatim.
CREATE OR REPLACE FUNCTION identity.invitation_transition_allowed() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    mutable_in_terminal CONSTANT text[] := ARRAY['delivery_state', 'delivery_updated_at',
        'credential_setup_state', 'credential_setup_attempts', 'credential_setup_next_at',
        'version'];
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.email IS DISTINCT FROM OLD.email OR NEW.email_lookup IS DISTINCT FROM OLD.email_lookup
        OR NEW.role IS DISTINCT FROM OLD.role OR NEW.locale IS DISTINCT FROM OLD.locale
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'invitation ownership, address, role and locale are immutable'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'invitation_ownership_immutable';
    END IF;
    IF NEW.issue_count < OLD.issue_count OR NEW.issue_count > OLD.issue_count + 1 THEN
        RAISE EXCEPTION 'invitation issue_count only grows by one'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'invitation_transition_allowed';
    END IF;
    IF OLD.state IN ('ACCEPTED', 'REVOKED', 'EXPIRED') THEN
        IF NEW.state <> OLD.state
            OR (to_jsonb(NEW) - mutable_in_terminal) <> (to_jsonb(OLD) - mutable_in_terminal) THEN
            RAISE EXCEPTION 'terminal invitations are immutable'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'invitation_transition_allowed';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT (
        (OLD.state = 'PENDING' AND NEW.state IN ('PENDING', 'ACCEPTING', 'REVOKED', 'EXPIRED'))
        OR (OLD.state = 'ACCEPTING' AND NEW.state IN ('ACCEPTING', 'PENDING', 'ACCEPTED', 'EXPIRED'))
    ) THEN
        RAISE EXCEPTION 'invitation transition % -> % is not allowed', OLD.state, NEW.state
            USING ERRCODE = 'check_violation', CONSTRAINT = 'invitation_transition_allowed';
    END IF;
    -- A new issuance only happens while pending, and always with a new token and a queued delivery.
    IF NEW.issue_count = OLD.issue_count + 1 AND NOT (
        OLD.state = 'PENDING' AND NEW.state = 'PENDING'
        AND NEW.token_sha256 IS DISTINCT FROM OLD.token_sha256
        AND NEW.token_issued_at > OLD.token_issued_at
        AND NEW.delivery_state = 'QUEUED') THEN
        RAISE EXCEPTION 'invitation reissue requires a pending invitation, a new token and a queued delivery'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'invitation_transition_allowed';
    END IF;
    -- Without a new issuance the token never changes while it exists.
    IF NEW.issue_count = OLD.issue_count AND NEW.token_sha256 IS NOT NULL
        AND NEW.token_sha256 IS DISTINCT FROM OLD.token_sha256 THEN
        RAISE EXCEPTION 'invitation token changes only through a reissue'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'invitation_transition_allowed';
    END IF;
    RETURN NEW;
END;
$$;

COMMIT;
