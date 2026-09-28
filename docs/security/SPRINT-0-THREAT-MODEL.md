# Sprint 0 threat model: authentication and service topology

Status: Sprint 0 baseline (Issue #3). Scope is the local development stack only. Review again
before any shared environment and before ADR 0005 is decided.

## Topology and trust boundaries

```
Browser (React PWA, localhost:5173)
  │  ① OIDC Authorization Code + PKCE (S256)          ┌──────────────────────────────┐
  ├──────────────────────────────────────────────────▶│ Keycloak 26 (localhost:8180) │
  │                                                    └──────────────┬───────────────┘
  │  ② Bearer access token (in memory)                                │ JWKS (internal)
  ├──────────────▶ Core API :8080 ─────── JDBC (internal) ─▶ PostgreSQL 17 (not published)
  │                  └ management :8081 (probes; not published)
  │  ③ Anonymous GET /api/v1/system/status only
  └──────────────▶ AI Service :8090  (no browser access to any other path)
```

Boundaries: browser ↔ services (untrusted client); services ↔ Keycloak (token issuer);
Core API ↔ PostgreSQL (data); tenant ↔ tenant (logical, enforced in the Core API).

## Assets

Access/refresh/ID tokens; tenant scope (`tenant_id` claim); future HR data in PostgreSQL;
Keycloak admin and seed credentials (development-only); logs.

## Threats and mitigations (STRIDE)

| # | Threat | Mitigation in Sprint 0 | Verified by |
|---|---|---|---|
| S1 | Forged or replayed token accepted by Core API | Signature via Keycloak JWKS; exact `iss`; required `aud=divalhr-core-api`; `exp`/`nbf` checks; 5-minute access tokens | `AuthenticationIntegrationTest` (wrong audience, issuer, expiry, tampering) |
| S2 | Authorization code interception | Public client with PKCE S256 only; exact redirect URI; implicit, password and device grants disabled | Realm config; e2e asserts `code_challenge_method=S256` |
| S3 | Development realm or seed users trusted elsewhere | Realm `divalhr-dev`, `dev-only-` passwords; Core API refuses to start outside development/test when trusting that realm; `DIVALHR_ENVIRONMENT` has no default | `DevelopmentSeedGuardTest` |
| T1 | Caller supplies another tenant's ID (path or body) | Tenant scope comes only from the verified token; `TenantAccessGuard` compares resource tenant to token tenant | `TenantIsolationIntegrationTest` (negative cross-tenant read and body-supplied tenant) |
| T2 | User edits own `tenant_id` or roles | `tenant_id` is an admin-only user-profile attribute; roles limited by `fullScopeAllowed: false` and an allow-list in the API | Realm config; `KeycloakRealmRoleConverterTest` |
| R1 | Actions cannot be traced | Correlation ID on every request and log line, echoed to clients and in error bodies | Core/AI correlation tests |
| I1 | Token theft via XSS or storage | Tokens kept in memory only (never localStorage); sessionStorage holds only transient PKCE state; strict CSP (`script-src 'self'`, explicit `connect-src`); lint rule blocks storage access | `oidc.test.ts`; e2e storage assertion; ESLint rule |
| I2 | Secrets or personal data in logs | No header/body/query logging; AI Service redacts sensitive keys; tokens carry no name/e-mail (no `profile`/`email` scopes); host verification scans compose logs for JWTs and seed passwords | `test_logging.py`; `verify-on-host.sh stack` |
| I3 | Error responses leak internals | Problem Details with stable codes; no stack traces or messages; generic 500 | `PlatformIntegrationTest` |
| I4 | Cross-origin reads | Explicit CORS origins, no credentials, wildcard rejected at start-up; AI Service CORS applies to the status path only | CORS tests (Core, AI) |
| I5 | Public status endpoints disclose topology | Status returns service name, UP/DOWN, version and time only; health details hidden; probes on internal port | Contract tests |
| D1 | Brute-force login | Keycloak brute-force detection; password policy length ≥ 12 | Realm config |
| E1 | Privileged access without MFA | Documented conditional-OTP flow for `platform-admin`/`tenant-admin` (MVP-011); **not enforced locally** | Required before any shared environment |
| E2 | AI Service used as a general backend | Only `GET /api/v1/system/status` is browser-callable; all future AI operations enter through the Core API boundary | `test_cors.py` |

## Accepted Sprint 0 risks (must be closed before real HR data)

1. **Browser-held tokens** (development only). ADR 0005 must decide BFF versus hardened SPA.
2. **HTTP on localhost**. TLS is required in every non-local environment.
3. **Keycloak `start-dev`** with an embedded database. Production uses a hardened deployment.
4. **Privileged MFA not enforced locally** (see E1).
5. **No audit-event store yet**. Sensitive actions arrive with their stories and must emit audit events.
