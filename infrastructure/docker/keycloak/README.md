# Keycloak development realm — DEVELOPMENT ONLY

`realm-divalhr-dev.json` is imported by `infrastructure/docker/compose.yaml` on start-up
(`start-dev --import-realm`). Everything in it is **development-only** and must never be imported
into, or reused by, any shared, staging or production environment:

- The realm is named `divalhr-dev`; the Core API refuses to start outside `development` and
  `test` if its issuer points at a `divalhr-dev` realm or does not use `https`.
- Seed tenant IDs are low-entropy placeholders (`00000000-0000-4000-8000-00000000000a`, `…0b`).
- Seed passwords and the TOTP seeds of the privileged seed users all start with `dev-only-` and
  are published in this repository.
- Tenants A and B exist as organizations only in `development`: the Core API loads the
  `db/dev-seed` fixtures ("DEV-ONLY Fixture Tenant A/B") solely when
  `DIVALHR_ENVIRONMENT=development`.

| Username | Tenant | Role | Password |
|---|---|---|---|
| `dev-admin-a` | A (`…000a`) | tenant-admin | `dev-only-Admin-A-2026` |
| `dev-admin-b` | B (`…000b`) | tenant-admin | `dev-only-Admin-B-2026` |
| `dev-employee-a` | A (`…000a`) | employee | `dev-only-Employee-A-2026` |
| `dev-employee-b` | B (`…000b`) | employee | `dev-only-Employee-B-2026` |
| `dev-platform-admin` | A (`…000a`) | platform-admin | `dev-only-Platform-2026` |

The bootstrap administrator credentials in `.env.example` are also development-only.

## Plain HTTP on loopback (Issue #27)

The stack serves Keycloak over plain HTTP on `http://localhost:8180`, published on **127.0.0.1
only**. The `divalhr-dev` realm therefore sets `"sslRequired": "none"`.

- **Why:** with Keycloak's default `external`, plain HTTP is accepted only from loopback and private
  (site-local, link-local, unique-local) source addresses. Docker Desktop for macOS forwards
  published ports from an address outside those ranges, so every browser sign-in failed with
  `HTTPS required`. Linux Docker forwards from the private bridge gateway and was unaffected.
- **Boundary:** only a realm named exactly `divalhr-dev` may set `none`
  (`DevelopmentRealmBoundaryTest` inspects every realm import in the repository). Compose publishes
  Keycloak on loopback only, and the Core API refuses both the `divalhr-dev` realm and any
  non-`https` issuer in staging and production (`DevelopmentSeedGuard`). Real realms must use
  `sslRequired` `external` or `all` behind HTTPS.
- **Unchanged:** PKCE, the exact redirect URI and web origin, disabled password and implicit
  grants, token lifetimes, audience and roles.
- **`master` realm:** Keycloak creates it itself and it keeps `external`, so the admin console at
  <http://localhost:8180/admin> is refused over HTTP on macOS. Administer the local realm from the
  command line inside the container, where requests come from loopback:

  ```bash
  docker compose -f infrastructure/docker/compose.yaml --env-file .env.example exec keycloak \
    /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master \
    --user dev-kc-admin --password dev-only-keycloak-admin --config /tmp/kcadm.config
  docker compose -f infrastructure/docker/compose.yaml --env-file .env.example exec keycloak \
    /opt/keycloak/bin/kcadm.sh get users -r divalhr-dev --fields username --config /tmp/kcadm.config
  ```

- **Rollback:** revert the change, then recreate the container so the previous realm file is
  imported again (Keycloak keeps no volume in this stack):
  `docker compose -f infrastructure/docker/compose.yaml --env-file .env.example up -d --force-recreate keycloak`.

## What the realm configures

- Public client `divalhr-web`: Authorization Code + PKCE (S256) only; implicit, password and
  device grants disabled; exact redirect URI and web origin (`http://localhost:5173`).
- Access tokens: 5-minute lifetime, audience `divalhr-core-api`, `tenant_id` claim from an
  admin-only user attribute, realm roles restricted to the three roles
  (`fullScopeAllowed: false`). No `profile`/`email` scopes, so tokens carry no names or e-mails.
- Refresh tokens rotate and cannot be reused; no offline access.
- Login pages in French (default) and English; the web app passes `ui_locales`.

## Invitation provisioning (MVP-010, narrowed by Issue #31)

- Confidential client `divalhr-core-provisioner` (client credentials only, secret
  `dev-only-provisioner-secret-2026` = `DIVALHR_KEYCLOAK_PROVISIONER_SECRET` in `.env.example`).
  It holds **no admin permission**: its service account has only the client role
  `divalhr-provisioning/provision-invitations`, and an audience mapper adds `divalhr-provisioning`
  to its tokens. Fine-grained admin permissions are off (`adminPermissionsEnabled: false`).
- The Core API calls only the extension `divalhr-provisioning`
  (`/realms/divalhr-dev/divalhr-provisioning/v1/invitations/{invitationId}/identity`), which creates
  each user directly inside one role group (`divalhr-role-employee` or
  `divalhr-role-tenant-admin`), sends the setup email and compensates. See `docs/SECURITY.md`,
  ADR 0006 and `packages/shared-contracts/openapi/keycloak-provisioning.yaml`.
- Admin-only user-profile attribute `divalhr_invitation_id` links an identity to its invitation.
- Access revocation (MVP-022, ADR 0008): when a separation ends an employee's DivalHR access, the
  Core API's job calls
  `PUT /realms/divalhr-dev/divalhr-provisioning/v1/tenants/{tenantId}/identities/{subject}/access-revocation`
  (empty body, `X-Revocation-Id`). The extension disables the user, removes its online and
  offline sessions and sets not-before, only for an employee identity it created for that tenant
  (one invitation attribute, the same `tenant_id`, one group, the employee role, no service
  account or platform role); anything else is `409 REVOCATION_REFUSED` and left unchanged. A
  replay returns the same `200 {"state":"REVOKED"}`; an unknown subject is `404
  IDENTITY_NOT_FOUND`. The admin event carries `divalhr.revocationId`, never an address. Reverting
  a disablement is a deliberate administrator action in the admin console.
- `loginWithEmailAllowed`: invitees sign in with their email address (their username).
- SMTP goes to the `mailpit` service; open <http://127.0.0.1:8025> to read every email of the
  stack (invitations and password setup). Nothing leaves the machine.

### The extension and the image (Issue #31)

Compose builds `divalhr/keycloak:dev` from `Dockerfile` (repository root as context): the pinned
Keycloak image plus `apps/keycloak-provisioning`, built with the Core API's Gradle wrapper.

| Option (environment variable) | Meaning |
|---|---|
| `KC_SPI_REALM_RESTAPI_EXTENSION__DIVALHR_PROVISIONING__REALMS` | Comma-separated realms where the extension answers; empty means nowhere (fail closed) |
| `KC_SPI_REALM_RESTAPI_EXTENSION__DIVALHR_PROVISIONING__WEB_REDIRECT_URI` | Where the setup-email link returns (required when a realm is enabled) |
| `…__WEB_CLIENT_ID` | Client of the setup-email link (default `divalhr-web`) |
| `…__ACTION_LIFESPAN_SECONDS` | Validity of the setup-email link (default 86400, at most 7 days) |
| `…__PROVISIONER_CLIENT_ID` | The only allowed caller (default `divalhr-core-provisioner`) |

Keycloak logs `KC-SERVICES0047 … is implementing the internal SPI realm-restapi-extension`: this
is expected (ADR 0006). Every extension operation is logged as one `divalhr.provisioning` line
(operation, outcome and IDs only) and recorded as an admin event.

**Monitoring.** Alert on any `divalhr.provisioning` refusal with status 401 or 403 (a
configuration fault or an attack), on `error` admin events whose `divalhr.outcome` is `refused`
with `SETUP_STATE_INVALID`, `IDENTITY_AMBIGUOUS` or `COMPENSATION_REFUSED`, on a sustained rate
of `outcome=refused_after_race` lines (one alone is a harmless race; `confirmed_after_race` is
normal under concurrency), on the Core API's
`invitation_credential_setup_invalid_state` and `invitation_accept_compensation_refused` logs, and
on any `identity.revoke` refusal (the Core API then shows the revocation as needing an
administrator; see the separation runbook in `docs/SECURITY.md`), and
on **any** Admin API event whose actor is the provisioner client (it has no admin permission, so
any such event means the configuration drifted).

### Shared environments: rollout (A3)

Each step is verified before the next. Invitations keep working throughout; the window in which the
provisioner still holds its old broad rights is deliberate and ends at step C.

| Step | Change | Verify |
|---|---|---|
| A | Deploy the DivalHR Keycloak image with the extension options; add the client `divalhr-provisioning` with the role `provision-invitations`, grant it to the provisioner's service account, and add the audience mapper to the provisioner client | `pnpm realm:verify:pre-cutover` (prints `MODE pre-cutover … NOT release or sign-off evidence` and `PENDING provisioner-has-no-broad-admin-rights`) |
| B | Deploy the Core API version that calls the extension | One invitation accepted end to end; admin events from the provisioner show only `divalhr.operation` details |
| C | Remove the provisioner's `realm-management` roles, any fine-grained admin permission or policy naming it, and any group or composite role that gives it one | `pnpm realm:verify` (**final** mode): every rule passes. The deployment is incomplete until it does |

Pre-cutover mode is never acceptable as release, pilot or sign-off evidence.

**Rollback**, in reverse order, without ever leaving invitations working with broad rights by
accident:

1. If the Core API must go back to the Admin API version, first re-grant the old rights (a realm
   administrator, with `kcadm.sh`), then deploy the old Core API. Until both are done, invitations
   fail closed with `IDENTITY_PROVIDER_UNAVAILABLE` and the reconciler and the durable setup retries
   resume afterwards.
2. Only then remove the extension (previous image). With the new Core API, an image without the
   extension also fails closed; it never restores broad rights.
3. Run `pnpm realm:verify` again: final mode fails while the old rights exist, as intended.

### Upgrading Keycloak

The extension compiles against exactly one Keycloak release and refuses to start on any other.

1. Change together: the `FROM quay.io/keycloak/keycloak:<version>@sha256:<digest>` line of
   `Dockerfile`, `keycloakVersion` in `apps/keycloak-provisioning/build.gradle.kts`,
   `apps/keycloak-provisioning/src/main/resources/META-INF/divalhr-provisioning.properties`, and
   `KEYCLOAK_IMAGE` in `KeycloakTestStack` (`KeycloakImageConsistencyTest` fails otherwise; a
   Dependabot bump of the image alone fails CI by design).
2. Read the release notes and migration guide for changes to: the `realm-restapi-extension` SPI
   (`RealmResourceProvider`), `AppAuthManager.BearerTokenAuthenticator`,
   `ExecuteActionsActionToken`, `LoginActionsService.actionTokenProcessor`,
   `EmailTemplateProvider`, `AdminEvent`/`EventStoreProvider`, `UserProvider`/`GroupProvider`,
   service accounts and the audience mapper.
3. Run the full container suite (`KeycloakProvisioningExtensionContainerTest`,
   `KeycloakProvisioningContainerTest`, `KeycloakMfaContainerTest`) and `scripts/dev/verify-on-host.sh
   all`. Any warning other than `KC-SERVICES0047` blocks the upgrade.
4. Bump the extension's build number (`<keycloak>-divalhr.<n>`).

## Privileged MFA (MVP-011)

`platform-admin` and `tenant-admin` sign in with a password **and** a TOTP code; employees with a
password only. The Core API accepts privileged requests only when the access token's `acr` is
exactly `urn:divalhr:loa:mfa` (see `docs/SECURITY.md`).

### Development authenticators (published, DEVELOPMENT ONLY)

The privileged seed users carry TOTP seeds labelled "DEV-ONLY published seed authenticator". Add
one to an authenticator app with the base32 key (type *time-based*, SHA-1, 6 digits, 30 s):

| Username | Raw seed (in the realm file) | Base32 key for an app |
|---|---|---|
| `dev-admin-a` | `dev-only-totp-admin-a-2026` | `MRSXMLLPNZWHSLLUN52HALLBMRWWS3RNMEWTEMBSGY` |
| `dev-admin-b` | `dev-only-totp-admin-b-2026` | `MRSXMLLPNZWHSLLUN52HALLBMRWWS3RNMIWTEMBSGY` |
| `dev-platform-admin` | `dev-only-totp-platform-2026` | `MRSXMLLPNZWHSLLUN52HALLQNRQXIZTPOJWS2MRQGI3A` |

Employees have none. A code works once; the previous and next 30-second periods are also
accepted. Five failures lock the account for 60 seconds, growing to at most 15 minutes. To unlock
everyone locally: `kcadm.sh delete attack-detection/brute-force/users -r divalhr-dev` (with the
`kcadm.sh` session shown above).

### What the realm configures

- **Browser flow `divalhr browser`** (bound as the realm browser flow):

  ```
  Cookie                                                             ALTERNATIVE
  divalhr browser forms                                              ALTERNATIVE
    level 1 password                                                 CONDITIONAL
      Condition - Level of Authentication (1, max age 36000 s)       REQUIRED
      Username Password Form                                         REQUIRED
    level 2 otp                                                      CONDITIONAL
      Condition - Level of Authentication (2, max age 36000 s)       REQUIRED
      Condition - user role (divalhr-privileged-mfa)                 REQUIRED
      level 2 enrolled                                               CONDITIONAL
        Condition - user configured                                  REQUIRED
        OTP Form                                                     REQUIRED
      level 2 not enrolled                                           CONDITIONAL
        Condition - sub-flow executed ("enrolled", not executed)     REQUIRED
        Deny access (divalhrMfaEnrollmentRequired)                   REQUIRED
  ```

- **Marker role `divalhr-privileged-mfa`:** a composite child of `platform-admin` and
  `tenant-admin`, so the level-2 condition covers both, also through the role groups. It is an
  internal marker only: never an authorization or assurance signal, and the Core ignores it.
- **`divalhr-web`:** `acr.loa.map` `{"urn:divalhr:loa:pwd":1,"urn:divalhr:loa:mfa":2}`,
  `default.acr.values` and `minimum.acr.value` `urn:divalhr:loa:mfa`. Employees still end at level
  1 because level 2 is role-conditional.
- **TOTP policy:** HmacSHA1, 6 digits, 30 s, look-ahead 1, codes not reusable.
- **Brute force:** failure factor 5, wait increment 60 s, maximum wait 900 s, no permanent lockout.
- **Events:** login events (7 days) and admin events, without representations.
- **Messages:** `divalhrMfaEnrollmentRequired` in French and English; Keycloak's own OTP and
  setup pages are used as shipped (keycloak.v2, bilingual). Their accessibility is Keycloak's;
  findings are recorded in the MVP-011 completion report, and there is no custom theme.
- **Enrollment:** only through an administrator-sent action link. Invited tenant administrators
  receive one link for `UPDATE_PASSWORD` and `CONFIGURE_TOTP`. A privileged user without an
  authenticator who signs in is denied, even if `CONFIGURE_TOTP` is pending.

**Level lifetime (guardrail 6).** After a level-2 sign-in, refreshed tokens keep `acr`
`urn:divalhr:loa:mfa` for the SSO session (at most 10 hours, 30 minutes idle). The level's max age
is checked at the next authorization request, never retroactively on refresh.

**`admin-cli` (A1).** It stays enabled so the `kcadm.sh` runbook works. Its tokens carry no
`divalhr-core-api` audience, roles, `tenant_id` or `acr`, and the Core rejects them
(`KeycloakMfaContainerTest.noOtherGrantOrClientYieldsATokenTheCoreAccepts`). The realm's own
`admin-cli` still asks privileged users for their code on password grants.

### Verify a realm (read-only, A4)

```bash
# Development stack (runs kcadm.sh inside the container; works on macOS):
KEYCLOAK_VERIFY_TRANSPORT=compose KEYCLOAK_ADMIN_USER=dev-kc-admin \
  KEYCLOAK_ADMIN_PASSWORD=dev-only-keycloak-admin pnpm realm:verify
# Another realm, with a short-lived token of an administrator who may view it:
KEYCLOAK_URL=https://id.example KEYCLOAK_REALM=<realm> KEYCLOAK_ADMIN_TOKEN=<token> pnpm realm:verify
```

It issues GET requests only and prints `PASS <rule>` or `FAIL <rule>` for: the bound flow and its
executions, the LoA levels, the marker-role condition, the denial of unenrolled users, the ACR map
and client minimum, the TOTP policy, no self-enrollment, the marker-role composition, PKCE without
password or device grants, brute force, locales and messages, and login and admin events. It never
prints values, credentials, secrets, tokens, subjects or personal data. Exit code 0 means every
rule passed, 1 a rule failed, 2 the realm could not be read.

### Runbooks

**Membership authority (MVP-012A).** The Core grants tenant access only to users whose token
role matches their DivalHR membership, so a role or `tenant_id` set in Keycloak alone is not
enough. Before enabling it in a shared environment, run the read-only preflight with credentials
in the environment only (never as arguments):

```bash
KEYCLOAK_URL=… KEYCLOAK_REALM=… KEYCLOAK_ADMIN_TOKEN=… PREFLIGHT_DB_TRANSPORT=psql \
  PGHOST=… PGDATABASE=… PGUSER=… pnpm membership:preflight
```

It prints counts per tenant and category and membership IDs, never addresses, usernames or
subjects, and exits non-zero when an enabled user with a tenant role is in `MISSING_MEMBERSHIP`,
`TENANT_MISMATCH`, `ROLE_MISMATCH` or `MISSING_TENANT_CLAIM` (disabled users are informational).
For a missing membership, find the users in Keycloak by tenant and role and use a supported path
(MVP-014 bootstrap for a first administrator, after removing a conflicting hand-created identity;
an MVP-010 invitation otherwise). For `MISSING_TENANT_CLAIM`, list the enabled users holding
`employee` or `tenant-admin` (directly or through the `divalhr-role-*` groups) whose `tenant_id`
attribute is missing or not a UUID; set it to their organization and give them a membership, or
remove the tenant role. The preflight never prints which users these are. Emergency removal of a person: disable the user and sign out their sessions
here; do not delete membership rows. The development realm's seed users have fixed IDs: recreate
old local volumes (`down -v`) after updating.


**Lost or replaced authenticator (A2).** Only an authorized Keycloak realm administrator does this;
it is a Keycloak administration task, not a DivalHR platform operation.

1. Verify the person's identity through an independent channel (for example a call back to a
   known number or confirmation by their manager). Never on the strength of an email alone.
2. Users → the user → Credentials: delete the OTP credential.
3. Send an action link for `CONFIGURE_TOTP` (Users → Actions → *Send email*, or
   `PUT /admin/realms/<realm>/users/<id>/execute-actions-email` with `["CONFIGURE_TOTP"]`).
4. Sign the user out of every session (Users → Sessions → *Sign out*).
5. If compromise is suspected, also require `UPDATE_PASSWORD` in the same link.
6. The admin events record the change; review them with the monitoring below.

An in-app or delegated reset needs a separate story. There are no recovery codes.

**First platform administrator.** The realm administrator creates the user, grants
`platform-admin` and sends one action link for `UPDATE_PASSWORD` and `CONFIGURE_TOTP`. Until the
link is used, that user cannot sign in with a privileged role.

**First tenant administrator (MVP-014).** Never created by hand in Keycloak. A platform
administrator invites them from the DivalHR web app (after creating the organization, or under
"First administrator"); the invitee sets their password and authenticator from the setup link.

**Break-glass.** None inside DivalHR: no account is exempt from MFA. Recovery goes through the
Keycloak administrator, whose own account has MFA in shared environments.

**Time synchronization.** Keycloak hosts must run NTP: a clock off by more than about 30 seconds
rejects valid codes. Tell users to set their phone's date and time automatically (the web app's
MFA-required page says so).

**Monitoring (no codes or secrets are ever in events).**

| Keycloak event | Meaning | Suggested alert |
|---|---|---|
| `LOGIN_ERROR` `invalid_user_credentials` with `selected_credential_id` | Wrong one-time code | Several per user within minutes |
| `LOGIN_ERROR` `invalid_user_credentials` with `username` | Wrong password | Existing password-spray alerts |
| `LOGIN_ERROR` `user_temporarily_disabled` | Brute-force lockout | Any privileged user |
| `LOGIN_ERROR` `access_denied` | Privileged user without an authenticator denied | Any occurrence (setup link needed) |
| Admin event `DELETE` on `users/<id>/credentials/<id>` | Authenticator removed | Every occurrence; the provisioner can no longer do this (Issue #31), so its client as actor means drift |

**When enforcement starts.** Existing sessions carry a password-level `acr`, so the first
privileged request answers `MFA_REQUIRED` and the web app steps up once: enrolled administrators
enter a code; administrators without an authenticator are denied and need a setup link first.
Employees are unaffected.

### Production checklist

1. Create the marker role and add it to `platform-admin` and `tenant-admin` as a composite.
2. Create the browser flow above and bind it as the realm browser flow.
3. Set the TOTP policy, brute-force settings, `divalhrMfaEnrollmentRequired` in French and English,
   and enable login and admin events (admin representations off).
4. Set the three `acr` attributes on the web client.
5. Protect Keycloak administration itself with MFA, a restricted network and monitored admin
   events.
6. Enroll every privileged user through setup links before enabling the Core check.
7. Run `pnpm realm:verify` (final mode) against the realm: every rule must pass, including the
   Issue #31 provisioning rules.

**Rollback.** Development: revert and recreate the Keycloak container. Shared environments: bind
the previous browser flow and remove the web client's `acr` minimum, *together with* reverting
the Core change; with only the realm rolled back, the Core keeps denying privileged requests.
