# ADR 0006 Narrow Keycloak provisioning extension

## Status

Accepted (Issue #31, architect decision with amendments A1–A3).

## Context

MVP-010 provisions invitees through the Keycloak Admin REST API with the confidential client
`divalhr-core-provisioner`. Fine-grained admin permissions v2 let it create users only inside the
two role groups, but they also let it fully manage those members: delete credentials (including
the TOTP authenticator required by MVP-011), reset passwords, change attributes and send action
emails. A stolen provisioner secret, combined with the victim's mailbox, therefore allowed an
authenticator takeover of an invited tenant administrator (Issue #31, MVP-011 decision D6a).

Options considered:

1. **Event listener** that reacts to forbidden operations: rejected, because it observes an
   operation after it has happened.
2. **Custom admin-permission evaluator** (FGAP v2 policy provider): internal SPI as well, and the
   provisioner would keep broad Admin API reach governed by a custom policy.
3. **Narrow REST extension** exposing exactly the three MVP-010 operations, so the provisioner
   needs no admin permission at all.

## Decision

Implement option 3: the Keycloak extension `divalhr-provisioning` (`apps/keycloak-provisioning`),
contract `packages/shared-contracts/openapi/keycloak-provisioning.yaml`.

- **Operations**, keyed by invitation ID, acting only on the single user carrying that exact
  `divalhr_invitation_id`: create or confirm the identity; send the setup email or confirm that
  setup is complete; delete a pristine identity (compensation). There is no read, search or
  by-subject operation.
- **Authorization before input (A2):** token signature, type, expiry, issuer and audience
  `divalhr-provisioning`; `azp` = the enabled confidential client `divalhr-core-provisioner`;
  the caller is that client's live, enabled service account; that account currently holds the
  client role `divalhr-provisioning/provision-invitations` (live model check, never token claims
  alone); the realm is enabled in the extension's configuration. Only then is the path validated,
  the body read (creation only: at most 2048 bytes while streaming, `application/json`, one
  object, strict fields; the other two operations take no body) or any identity looked up.
- **Server-decided values:** group from a closed role mapping, attributes, `enabled`,
  `emailVerified`, required actions, action-email client, redirect URI and lifespan. Callers
  cannot supply groups, roles, attributes, credentials, flags or actions. New identities get no
  realm default role or default group (PR #32 review), and credential setup derives the role from
  the identity's single approved role group, never from the caller.
- **Concurrency:** identical concurrent creates resolve to the same identity (a call that loses
  the race settles from committed state in a fresh transaction) or, when that state cannot yet
  show the outcome, answer the retryable `503 IDENTITY_BUSY`. `IDENTITY_CONFLICT` means a proven
  differing binding only.
- **Setup states (A1):** completed only for the proven role-specific terminal state; a consistent
  in-progress state sends another email; every other state is `SETUP_STATE_INVALID`, which the
  Core alerts on and never records as sent.
- **Permissions:** the provisioner holds only `divalhr-provisioning/provision-invitations` (plus
  Keycloak's default realm roles). No `realm-management` role, no admin-permission policy.
- **Audit:** a Keycloak admin event per operation (operation, outcome, tenant and correlation
  details, target user ID once resolved, never a representation) and a sanitized structured log.
- **Rollout (A3):** additive extension and capability, then the Core cut-over, then removal of the
  broad rights. `realm:verify` has a `pre-cutover` mode that reports the removal as pending and is
  never release evidence; the default `final` mode requires that every broad right is gone,
  including effective (composite and group-inherited) roles and any permission policy that names
  the provisioner.

## Consequences

- **Internal SPI.** `realm-restapi-extension` and the helpers used
  (`AppAuthManager.BearerTokenAuthenticator`, `ExecuteActionsActionToken`,
  `LoginActionsService.actionTokenProcessor`, `EmailTemplateProvider`, `AdminEvent`,
  `EventStoreProvider`) are internal to Keycloak and may change without notice. Keycloak logs
  `KC-SERVICES0047` for the extension; this warning is expected.
- **Pin and guard.** The extension compiles against exactly the Keycloak version of the image
  (`keycloakVersion` in its build). Its factory refuses to start on any other running version, so
  Keycloak fails fast. A test keeps the Dockerfile base image, the Testcontainers image and the
  build version equal.
- **Owned image.** DivalHR builds its Keycloak image (`infrastructure/docker/keycloak/Dockerfile`)
  for development and production.
- **Maintenance cost.** About 1,000 lines including tests; a full real-image test run and a review
  of the release notes for the internal APIs above on every Keycloak upgrade (checklist in the
  Keycloak README); possible rework when Keycloak changes the SPI.
- **Rollback** is ordered (re-grant, then Core, then image) and fails closed in every
  intermediate state: invitations answer `IDENTITY_PROVIDER_UNAVAILABLE` rather than regaining
  broad rights.
