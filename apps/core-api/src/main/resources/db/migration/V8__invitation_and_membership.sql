-- MVP-010 (Issue #25): invitations and tenant membership, owned by the identity module.
--
-- Compatibility: additive only. No existing table, row or constraint changes. The previous
-- application version keeps working against this schema.
-- Rollback: db/rollback/V8__rollback.sql (manual; never run by Flyway). Identities created in the
-- identity provider by acceptances are NOT removed by the SQL rollback (see the runbook note there).
--
-- Tenant integrity (architecture amendment A1): both tables reference tenant.organization, and an
-- accepted invitation and its membership reference each other through composite keys that repeat
-- the tenant (and, for the membership, the role and address lookup). A foreign key is an integrity
-- constraint, not a read of the tenant module's tables.
--
-- Personal data: identity.invitation.email is confidential. It exists only in this table and is
-- deleted with the row by the retention job. email_lookup is a keyed HMAC (never a plain hash) so
-- that the membership table can enforce uniqueness without storing the address.
-- Secrets: token_sha256 is the SHA-256 of a 256-bit random token; the token itself is never stored.

-- ---------------------------------------------------------------------------------------------
-- identity.invitation
-- ---------------------------------------------------------------------------------------------
CREATE TABLE identity.invitation (
    id                        uuid        PRIMARY KEY,
    tenant_id                 uuid        NOT NULL,
    email                     text        NOT NULL,
    email_lookup              bytea       NOT NULL,
    role                      text        NOT NULL,
    locale                    text        NOT NULL,
    state                     text        NOT NULL,
    token_sha256              bytea,
    token_issued_at           timestamptz NOT NULL,
    expires_at                timestamptz NOT NULL,
    -- Issuance number of the current link: 1 on creation, +1 on each resend (at most 3 resends).
    issue_count               integer     NOT NULL DEFAULT 1,
    -- Delivery of the current issuance (amendment A3). Updates are conditional on issue_count, so
    -- a late result for an older link can never overwrite the state of a newer one.
    delivery_state            text        NOT NULL DEFAULT 'QUEUED',
    delivery_updated_at       timestamptz NOT NULL,
    -- Acceptance lease: the worker that holds it (owner) is the only one allowed to complete it.
    acceptance_lease_owner    uuid,
    acceptance_lease_until    timestamptz,
    accepted_at               timestamptz,
    membership_id             uuid,
    -- "Choose your password" email requested from the identity provider after acceptance.
    credential_setup_state    text        NOT NULL DEFAULT 'NOT_APPLICABLE',
    credential_setup_attempts integer     NOT NULL DEFAULT 0,
    credential_setup_next_at  timestamptz,
    revoked_at                timestamptz,
    revoked_by                text,
    expired_at                timestamptz,
    terminal_at               timestamptz,
    created_at                timestamptz NOT NULL,
    created_by                text        NOT NULL,
    version                   bigint      NOT NULL DEFAULT 0,
    CONSTRAINT invitation_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant.organization (id),
    CONSTRAINT invitation_tenant_id_unique UNIQUE (tenant_id, id),
    -- Target of the membership's composite key: same tenant, same invitation, same role and address.
    CONSTRAINT invitation_membership_source_unique UNIQUE (tenant_id, id, role, email_lookup),
    CONSTRAINT invitation_token_unique UNIQUE (token_sha256),
    CONSTRAINT invitation_email_format CHECK (
        char_length(email) BETWEEN 3 AND 254
        AND email = lower(email)
        AND email = btrim(email)
        AND email ~ '^[^@[:space:][:cntrl:]]{1,64}@[^@[:space:][:cntrl:]]+$'),
    CONSTRAINT invitation_email_lookup_length CHECK (octet_length(email_lookup) = 32),
    -- platform-admin (or any other role) can never be stored, whatever the application does.
    CONSTRAINT invitation_role_assignable CHECK (role IN ('tenant-admin', 'employee')),
    CONSTRAINT invitation_locale_supported CHECK (locale IN ('fr', 'en')),
    CONSTRAINT invitation_state_valid
        CHECK (state IN ('PENDING', 'ACCEPTING', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT invitation_token_length CHECK (token_sha256 IS NULL OR octet_length(token_sha256) = 32),
    CONSTRAINT invitation_expiry_after_issue CHECK (expires_at > token_issued_at),
    CONSTRAINT invitation_issue_count_range CHECK (issue_count BETWEEN 1 AND 4),
    CONSTRAINT invitation_delivery_state_valid CHECK (delivery_state IN ('QUEUED', 'SENT', 'FAILED')),
    CONSTRAINT invitation_credential_setup_valid
        CHECK (credential_setup_state IN ('NOT_APPLICABLE', 'PENDING', 'SENT', 'FAILED')),
    CONSTRAINT invitation_credential_attempts_range CHECK (credential_setup_attempts BETWEEN 0 AND 10),
    CONSTRAINT invitation_created_by_length CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT invitation_revoked_by_length
        CHECK (revoked_by IS NULL OR char_length(revoked_by) BETWEEN 1 AND 255),
    CONSTRAINT invitation_version_non_negative CHECK (version >= 0),
    -- Every state has exactly the columns it needs: the token exists only while the link may still
    -- be used (PENDING, ACCEPTING); a lease exists only while accepting; terminal states record when
    -- and why they ended; only an accepted invitation has a membership and a credential setup.
    CONSTRAINT invitation_state_consistent CHECK (
        CASE state
            WHEN 'PENDING' THEN
                token_sha256 IS NOT NULL
                AND acceptance_lease_owner IS NULL AND acceptance_lease_until IS NULL
                AND accepted_at IS NULL AND membership_id IS NULL
                AND revoked_at IS NULL AND revoked_by IS NULL AND expired_at IS NULL
                AND terminal_at IS NULL
                AND credential_setup_state = 'NOT_APPLICABLE' AND credential_setup_next_at IS NULL
            WHEN 'ACCEPTING' THEN
                token_sha256 IS NOT NULL
                AND acceptance_lease_owner IS NOT NULL AND acceptance_lease_until IS NOT NULL
                AND accepted_at IS NULL AND membership_id IS NULL
                AND revoked_at IS NULL AND revoked_by IS NULL AND expired_at IS NULL
                AND terminal_at IS NULL
                AND credential_setup_state = 'NOT_APPLICABLE' AND credential_setup_next_at IS NULL
            WHEN 'ACCEPTED' THEN
                token_sha256 IS NULL
                AND acceptance_lease_owner IS NULL AND acceptance_lease_until IS NULL
                AND accepted_at IS NOT NULL AND membership_id IS NOT NULL
                AND revoked_at IS NULL AND revoked_by IS NULL AND expired_at IS NULL
                AND terminal_at IS NOT NULL
                AND credential_setup_state <> 'NOT_APPLICABLE'
                AND (credential_setup_state = 'PENDING') = (credential_setup_next_at IS NOT NULL)
            WHEN 'REVOKED' THEN
                token_sha256 IS NULL
                AND acceptance_lease_owner IS NULL AND acceptance_lease_until IS NULL
                AND accepted_at IS NULL AND membership_id IS NULL
                AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND expired_at IS NULL
                AND terminal_at IS NOT NULL
                AND credential_setup_state = 'NOT_APPLICABLE' AND credential_setup_next_at IS NULL
            WHEN 'EXPIRED' THEN
                token_sha256 IS NULL
                AND acceptance_lease_owner IS NULL AND acceptance_lease_until IS NULL
                AND accepted_at IS NULL AND membership_id IS NULL
                AND revoked_at IS NULL AND revoked_by IS NULL AND expired_at IS NOT NULL
                AND terminal_at IS NOT NULL
                AND credential_setup_state = 'NOT_APPLICABLE' AND credential_setup_next_at IS NULL
            ELSE FALSE
        END)
);

COMMENT ON TABLE identity.invitation IS
    'MVP-010 invitations. email is confidential personal data; the row is deleted after retention.';
COMMENT ON COLUMN identity.invitation.email_lookup IS
    'HMAC-SHA256(DIVALHR_EMAIL_LOOKUP_KEY, normalized email); never a plain hash of the address';
COMMENT ON COLUMN identity.invitation.token_sha256 IS
    'SHA-256 of the 256-bit invitation token; the token itself is never stored';
COMMENT ON COLUMN identity.invitation.created_by IS 'Verified JWT subject (opaque ID), not a name';

-- At most one open invitation per address in a tenant (case-insensitive through the lookup).
CREATE UNIQUE INDEX invitation_one_open_per_email
    ON identity.invitation (tenant_id, email_lookup)
    WHERE state IN ('PENDING', 'ACCEPTING');

-- Listing: newest first, keyset (created_at, id).
CREATE INDEX invitation_tenant_created_id
    ON identity.invitation (tenant_id, created_at DESC, id DESC);

-- Jobs.
CREATE INDEX invitation_pending_expiry ON identity.invitation (expires_at) WHERE state = 'PENDING';
CREATE INDEX invitation_retention ON identity.invitation (terminal_at) WHERE terminal_at IS NOT NULL;
CREATE INDEX invitation_accepting_lease
    ON identity.invitation (acceptance_lease_until) WHERE state = 'ACCEPTING';
CREATE INDEX invitation_delivery_queued
    ON identity.invitation (delivery_updated_at) WHERE delivery_state = 'QUEUED';
CREATE INDEX invitation_credential_setup_due
    ON identity.invitation (credential_setup_next_at) WHERE credential_setup_state = 'PENDING';

-- ---------------------------------------------------------------------------------------------
-- identity.tenant_membership: Core's authoritative record that a subject belongs to a tenant.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE identity.tenant_membership (
    id                   uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL,
    -- The identity provider's opaque user id, as it appears in the access token's sub.
    subject              text        NOT NULL,
    role                 text        NOT NULL,
    email_lookup         bytea       NOT NULL,
    -- Set to NULL (only this column) when the accepted invitation is deleted by retention.
    source_invitation_id uuid,
    created_at           timestamptz NOT NULL,
    CONSTRAINT tenant_membership_tenant_fk FOREIGN KEY (tenant_id)
        REFERENCES tenant.organization (id),
    -- MVP constraint (decision D-1): one identity belongs to exactly one tenant.
    CONSTRAINT tenant_membership_one_tenant_per_subject UNIQUE (subject),
    CONSTRAINT tenant_membership_email_unique UNIQUE (tenant_id, email_lookup),
    CONSTRAINT tenant_membership_source_unique UNIQUE (source_invitation_id),
    -- Target of the invitation's composite key.
    CONSTRAINT tenant_membership_tenant_id_source_unique UNIQUE (tenant_id, id, source_invitation_id),
    -- The source invitation belongs to the same tenant and carries the same role and address.
    CONSTRAINT tenant_membership_source_same_tenant
        FOREIGN KEY (tenant_id, source_invitation_id, role, email_lookup)
        REFERENCES identity.invitation (tenant_id, id, role, email_lookup)
        MATCH SIMPLE ON DELETE SET NULL (source_invitation_id),
    CONSTRAINT tenant_membership_role_assignable CHECK (role IN ('tenant-admin', 'employee')),
    CONSTRAINT tenant_membership_subject_length CHECK (char_length(subject) BETWEEN 1 AND 255),
    CONSTRAINT tenant_membership_email_lookup_length CHECK (octet_length(email_lookup) = 32)
);

COMMENT ON TABLE identity.tenant_membership IS
    'MVP-010 membership of an identity-provider subject in one tenant; no email address stored';

-- An accepted invitation names a membership of the same tenant whose source is this invitation.
-- Deferred: the pair is written in one transaction and only has to agree at commit.
ALTER TABLE identity.invitation
    ADD CONSTRAINT invitation_membership_same_tenant_and_source
        FOREIGN KEY (tenant_id, membership_id, id)
        REFERENCES identity.tenant_membership (tenant_id, id, source_invitation_id)
        MATCH SIMPLE DEFERRABLE INITIALLY DEFERRED;

-- ---------------------------------------------------------------------------------------------
-- Lifecycle: new invitations start PENDING with a first, queued issuance.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION identity.invitation_insert_initial() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.state <> 'PENDING' OR NEW.issue_count <> 1 OR NEW.delivery_state <> 'QUEUED'
        OR NEW.credential_setup_attempts <> 0 OR NEW.version <> 0 THEN
        RAISE EXCEPTION 'identity.invitation must be inserted as a new PENDING invitation'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'invitation_insert_initial';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invitation_insert_initial
    BEFORE INSERT ON identity.invitation
    FOR EACH ROW EXECUTE FUNCTION identity.invitation_insert_initial();

-- Allowed transitions and immutable columns.
--   PENDING   -> PENDING (reissue, delivery result) | ACCEPTING | REVOKED | EXPIRED
--   ACCEPTING -> ACCEPTING (lease takeover) | PENDING (released) | ACCEPTED | EXPIRED
--   ACCEPTED, REVOKED, EXPIRED are terminal: only the delivery result of the last issuance, the
--   credential setup of an accepted invitation, and version may still change.
CREATE FUNCTION identity.invitation_transition_allowed() RETURNS trigger
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

CREATE TRIGGER invitation_transition_allowed
    BEFORE UPDATE ON identity.invitation
    FOR EACH ROW EXECUTE FUNCTION identity.invitation_transition_allowed();

-- Memberships are immutable apart from losing their source when retention deletes the invitation.
CREATE FUNCTION identity.tenant_membership_immutable() RETURNS trigger
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

CREATE TRIGGER tenant_membership_immutable
    BEFORE UPDATE ON identity.tenant_membership
    FOR EACH ROW EXECUTE FUNCTION identity.tenant_membership_immutable();
