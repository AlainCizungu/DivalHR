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

## Invitation provisioning (MVP-010)

- Confidential client `divalhr-core-provisioner` (client credentials only, secret
  `dev-only-provisioner-secret-2026` = `DIVALHR_KEYCLOAK_PROVISIONER_SECRET` in `.env.example`).
  Its service account has `query-users` and `query-groups` only.
- Fine-grained admin permissions v2 (`adminPermissionsEnabled`): a user policy on the service
  account grants `view`, `view-members`, `manage-members` and `manage-membership` on exactly two
  groups, `divalhr-role-employee` (realm role `employee`) and `divalhr-role-tenant-admin` (realm
  role `tenant-admin`). The provisioner has **no** role-mapping permission: it creates each user
  directly inside one role group, so it can never grant `platform-admin`.
- Residual risk: it fully manages members of those two groups (see `docs/SECURITY.md`).
- Admin-only user-profile attribute `divalhr_invitation_id` links an identity to its invitation.
- `loginWithEmailAllowed`: invitees sign in with their email address (their username).
- SMTP goes to the `mailpit` service; open <http://127.0.0.1:8025> to read every email of the
  stack (invitations and password setup). Nothing leaves the machine.

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
| Admin event `DELETE` on `users/<id>/credentials/<id>` | Authenticator removed | Every occurrence, and always when the actor is `divalhr-core-provisioner` (D6) |

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
7. Run `pnpm realm:verify` against the realm: every rule must pass.

**Rollback.** Development: revert and recreate the Keycloak container. Shared environments: bind
the previous browser flow and remove the web client's `acr` minimum, *together with* reverting
the Core change; with only the realm rolled back, the Core keeps denying privileged requests.
