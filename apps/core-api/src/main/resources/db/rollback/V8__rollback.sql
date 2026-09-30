-- Manual rollback for V8__invitation_and_membership.sql (Issue #25). NEVER run by Flyway.
--
-- Prefer rolling back the APPLICATION only: V8 is additive and the previous application version
-- works unchanged against it, so no schema rollback is needed to undo a release.
--
-- WARNING: this permanently deletes every invitation (including the invitee email addresses) and
-- every tenant membership. Audit and outbox records of invitation.* remain (audit is append-only;
-- neither contains email addresses or tokens) and would then reference removed invitations.
--
-- Idempotency records of invitation.create and invitation.resend are purged in the same transaction:
-- otherwise an unexpired key would replay a receipt for an invitation that no longer exists.
--
-- IDENTITY PROVIDER: identities created by accepted invitations are NOT removed by this script. They
-- keep their tenant_id attribute and role group, so they can still sign in. Before running it, list
-- them in Keycloak (members of the groups divalhr-role-employee and divalhr-role-tenant-admin that
-- carry a divalhr_invitation_id attribute) and decide with the tenant whether to disable them.
--
-- Run in one transaction, then remove the V8 row from flyway_schema_history only if the application
-- is also rolled back.
BEGIN;

DELETE FROM platform.idempotency_record
    WHERE operation IN ('invitation.create', 'invitation.resend');

ALTER TABLE identity.invitation DROP CONSTRAINT IF EXISTS invitation_membership_same_tenant_and_source;
DROP TABLE IF EXISTS identity.tenant_membership;
DROP TABLE IF EXISTS identity.invitation;
DROP FUNCTION IF EXISTS identity.tenant_membership_immutable();
DROP FUNCTION IF EXISTS identity.invitation_transition_allowed();
DROP FUNCTION IF EXISTS identity.invitation_insert_initial();

COMMIT;
