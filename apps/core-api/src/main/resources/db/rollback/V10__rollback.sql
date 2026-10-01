-- Manual rollback for V10__membership_email.sql (MVP-012A, Issue #37). NEVER run by Flyway.
--
-- Prefer rolling back the APPLICATION only: V10 keeps the previous application version working
-- (its membership inserts omit the address and the trigger copies it from the source invitation).
-- Rolling the application back to token-only authorization is a security downgrade that needs an
-- incident-style change approval (architect decision on #37, A7).
--
-- This script restores the V9 schema exactly: it drops the address column, its check and the insert
-- trigger, and re-creates the V8/V9 immutability function verbatim. Memberships are kept; only the
-- stored addresses are lost (they remain on any invitation still within retention).
--
-- Run in one transaction, then remove the V10 row from flyway_schema_history only if the
-- application is also rolled back.
BEGIN;

DROP TRIGGER tenant_membership_email_from_source ON identity.tenant_membership;
DROP FUNCTION identity.tenant_membership_email_from_source();

CREATE OR REPLACE FUNCTION identity.tenant_membership_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.subject IS DISTINCT FROM OLD.subject OR NEW.role IS DISTINCT FROM OLD.role
        OR NEW.email_lookup IS DISTINCT FROM OLD.email_lookup
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR (NEW.source_invitation_id IS DISTINCT FROM OLD.source_invitation_id
            AND NEW.source_invitation_id IS NOT NULL) THEN
        RAISE EXCEPTION 'tenant memberships are immutable'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tenant_membership_immutable';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE identity.tenant_membership
    DROP CONSTRAINT tenant_membership_email_format,
    DROP COLUMN email;

COMMENT ON TABLE identity.tenant_membership IS
    'MVP-010 membership of an identity-provider subject in one tenant; no email address stored';

COMMIT;
