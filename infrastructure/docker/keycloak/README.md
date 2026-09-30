# Keycloak development realm — DEVELOPMENT ONLY

`realm-divalhr-dev.json` is imported by `infrastructure/docker/compose.yaml` on start-up
(`start-dev --import-realm`). Everything in it is **development-only** and must never be imported
into, or reused by, any shared, staging or production environment:

- The realm is named `divalhr-dev`; the Core API refuses to start outside `development` if its
  issuer points at a `divalhr-dev` realm.
- Seed tenant IDs are low-entropy placeholders (`00000000-0000-4000-8000-00000000000a`, `…0b`).
- Seed passwords all start with `dev-only-` and are published in this repository.
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

The Keycloak admin console (<http://localhost:8180/admin>) uses the bootstrap credentials from
`.env.example`, also development-only.

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

Sprint 0 documents MFA but does not enforce it locally. Before any shared environment:

1. Authentication → duplicate the *browser* flow.
2. In the conditional OTP sub-flow, replace *Condition – user configured* with
   *Condition – user role* for `platform-admin`, and add a second one for `tenant-admin`.
3. Set *OTP Form* to **Required** and bind the new flow as the browser flow.
4. Verify that privileged users must enrol TOTP on next login; non-privileged users are unaffected.
