-- MVP-014 (Issue #38): the origin of an invitation, and the platform bootstrap of an
-- organization's first tenant administrator.
--
-- Compatibility: existing invitations become origin TENANT_ADMIN (the column default, which also
-- keeps the previous application version's inserts valid). No other existing row changes. The
-- transition trigger keeps every V8 rule and adds two: origin is immutable, and a
-- PLATFORM_BOOTSTRAP invitation being accepted may end as REVOKED by the system marker
-- 'system:bootstrap-superseded' when another tenant administrator appeared first (A2).
-- No development-only data. Rollback: db/rollback/V9__rollback.sql (manual; never run by Flyway).

ALTER TABLE identity.invitation
    ADD COLUMN origin text NOT NULL DEFAULT 'TENANT_ADMIN';

COMMENT ON COLUMN identity.invitation.origin IS
    'TENANT_ADMIN (MVP-010) or PLATFORM_BOOTSTRAP (MVP-014: a platform administrator invited the '
    'organization''s first tenant administrator). Immutable.';

ALTER TABLE identity.invitation
    ADD CONSTRAINT invitation_origin_valid CHECK (origin IN ('TENANT_ADMIN', 'PLATFORM_BOOTSTRAP')),
    -- A platform administrator can only ever bootstrap a tenant administrator.
    ADD CONSTRAINT invitation_bootstrap_is_tenant_admin
        CHECK (origin <> 'PLATFORM_BOOTSTRAP' OR role = 'tenant-admin'),
    -- The supersession marker belongs to bootstrap invitations only.
    ADD CONSTRAINT invitation_superseded_is_bootstrap
        CHECK (revoked_by IS DISTINCT FROM 'system:bootstrap-superseded'
               OR origin = 'PLATFORM_BOOTSTRAP');

-- Secondary invariant (the shared organization lock is the primary one): at most one open
-- bootstrap invitation per organization.
CREATE UNIQUE INDEX invitation_one_open_bootstrap
    ON identity.invitation (tenant_id)
    WHERE origin = 'PLATFORM_BOOTSTRAP' AND state IN ('PENDING', 'ACCEPTING');

-- The bootstrap rule asks whether an organization has an open tenant-admin invitation.
CREATE INDEX invitation_open_tenant_admin
    ON identity.invitation (tenant_id)
    WHERE role = 'tenant-admin' AND state IN ('PENDING', 'ACCEPTING');

-- Allowed transitions and immutable columns (V8 rules, plus origin and the bootstrap supersession):
--   PENDING   -> PENDING (reissue, delivery result) | ACCEPTING | REVOKED | EXPIRED
--   ACCEPTING -> ACCEPTING (lease takeover) | PENDING (released) | ACCEPTED | EXPIRED
--                | REVOKED (PLATFORM_BOOTSTRAP only, revoked_by 'system:bootstrap-superseded')
--   ACCEPTED, REVOKED, EXPIRED are terminal.
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
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
        OR NEW.origin IS DISTINCT FROM OLD.origin THEN
        RAISE EXCEPTION 'invitation ownership, address, role, locale and origin are immutable'
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
        -- MVP-014 A2: a bootstrap acceptance superseded by another tenant administrator ends here.
        OR (OLD.state = 'ACCEPTING' AND NEW.state = 'REVOKED'
            AND NEW.origin = 'PLATFORM_BOOTSTRAP'
            AND NEW.revoked_by = 'system:bootstrap-superseded')
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
