-- MVP-012A (Issue #37): the tenant membership becomes the Core-side authority for tenant access and
-- keeps the member's normalized address for the access review (D3, A3, A12A-2).
--
-- Personal data: tenant_membership.email is confidential. It is written only from the membership's
-- own source invitation, never logged, and immutable once stored.
--
-- Rules after V10:
--   * A membership inserted with a source invitation ends the insert with a non-null email equal to
--     that exact invitation's email, in the same tenant, with the same role and email_lookup. The
--     application writes email and lookup from one validated EmailAddress; if a writer omits the
--     email (the previous application version during a rolling deployment), the trigger copies it
--     from the locked source-invitation row before validating. It never consults any other row.
--   * A membership without a source invitation (development seed rows) cannot carry an email: an
--     unanchored address is rejected.
--   * Existing memberships are backfilled from their own source invitation only; rows whose
--     invitation was already deleted by retention keep NULL (historical, irrecoverable).
--   * The email is immutable. Retention may still clear source_invitation_id and keep the email.
--
-- No development-only data. Rollback: db/rollback/V10__rollback.sql (manual; never run by Flyway).

ALTER TABLE identity.tenant_membership
    ADD COLUMN email text;

COMMENT ON COLUMN identity.tenant_membership.email IS
    'Confidential normalized address copied from the source invitation at acceptance (MVP-012A). '
    'NULL only for historical rows whose invitation was purged and for development seed rows.';
COMMENT ON TABLE identity.tenant_membership IS
    'Membership of an identity-provider subject in one tenant; the Core-side authority for tenant '
    'access (MVP-012A). email is confidential personal data.';

ALTER TABLE identity.tenant_membership
    ADD CONSTRAINT tenant_membership_email_format CHECK (
        email IS NULL OR (
            char_length(email) BETWEEN 3 AND 254
            AND email = lower(email)
            AND email = btrim(email)
            AND email ~ '^[^@[:space:][:cntrl:]]{1,64}@[^@[:space:][:cntrl:]]+$'));

-- Controlled backfill from each row's own source invitation. The V8 immutability trigger does not
-- cover the new column, so no trigger is disabled.
UPDATE identity.tenant_membership m
SET email = i.email
FROM identity.invitation i
WHERE i.id = m.source_invitation_id
  AND i.tenant_id = m.tenant_id
  AND i.role = m.role
  AND i.email_lookup = m.email_lookup;

-- Insert invariant (A12A-2).
CREATE FUNCTION identity.tenant_membership_email_from_source() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    source_email  text;
    source_lookup bytea;
    source_role   text;
BEGIN
    IF NEW.source_invitation_id IS NULL THEN
        IF NEW.email IS NOT NULL THEN
            RAISE EXCEPTION 'a membership address must come from its source invitation'
                USING ERRCODE = 'check_violation',
                      CONSTRAINT = 'tenant_membership_email_from_source';
        END IF;
        RETURN NEW;
    END IF;
    SELECT i.email, i.email_lookup, i.role
    INTO source_email, source_lookup, source_role
    FROM identity.invitation i
    WHERE i.id = NEW.source_invitation_id AND i.tenant_id = NEW.tenant_id
    FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'the source invitation does not exist in this tenant'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tenant_membership_email_from_source';
    END IF;
    IF NEW.email IS NULL THEN
        -- Rolling compatibility: a writer that predates V10 omits the address.
        NEW.email := source_email;
    END IF;
    IF NEW.email IS DISTINCT FROM source_email
        OR NEW.email_lookup IS DISTINCT FROM source_lookup
        OR NEW.role IS DISTINCT FROM source_role THEN
        RAISE EXCEPTION 'the membership address does not match its source invitation'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tenant_membership_email_from_source';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tenant_membership_email_from_source
    BEFORE INSERT ON identity.tenant_membership
    FOR EACH ROW EXECUTE FUNCTION identity.tenant_membership_email_from_source();

-- Immutability: the V8 rules plus the address.
CREATE OR REPLACE FUNCTION identity.tenant_membership_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.subject IS DISTINCT FROM OLD.subject OR NEW.role IS DISTINCT FROM OLD.role
        OR NEW.email_lookup IS DISTINCT FROM OLD.email_lookup
        OR NEW.email IS DISTINCT FROM OLD.email
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR (NEW.source_invitation_id IS DISTINCT FROM OLD.source_invitation_id
            AND NEW.source_invitation_id IS NOT NULL) THEN
        RAISE EXCEPTION 'tenant memberships are immutable'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tenant_membership_immutable';
    END IF;
    RETURN NEW;
END;
$$;
