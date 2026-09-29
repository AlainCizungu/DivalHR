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

## MVP-001 delta (organization provisioning)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| E3 | Tenant admin or employee provisions tenants | `@PlatformScoped`: interceptor before body parsing plus `@PreAuthorize` | `CreateOrganizationApiIntegrationTest`, `MethodSecurityEnforcementIntegrationTest` |
| T3 | Caller chooses the new tenant ID | Server-generated UUID; unknown body properties (`id`, `tenantId`) rejected | `callerCannotChooseTheIdOrTenant` |
| T4 | Duplicate tenants from retries or races | Transactional idempotency keyed on verified `sub`; PostgreSQL unique-insert wait | Concurrency integration tests |
| R2 | Provisioning not traceable | Append-only audit event with correlation ID in the same transaction | Audit assertions, DB trigger tests |
| I6 | Customer names leak via logs, errors, events or metrics | Names excluded from logs, Problem params, audit metadata, event data and metric labels | Log-capture and payload assertions |

## MVP-002 delta (legal entities and sites)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| E4 | Employee or platform administrator manages a tenant's hierarchy | `@TenantAdminOperation`: interceptor before body parsing plus `@PreAuthorize("hasRole('tenant-admin')")`; no implicit platform access | `LegalEntityApiIntegrationTest`, `SiteApiIntegrationTest`, `HierarchyListingIntegrationTest`, `MethodSecurityEnforcementIntegrationTest`, French E2E |
| T5 | Caller writes into or reads another tenant by supplying a tenant ID | Tenant only from the verified token; `tenantId` body property rejected; headers and query parameters ignored | `callerCannotChooseTheTenant`, `tenantParametersAndHeadersAreIgnored` |
| T6 | Site attached to another tenant's legal entity | Parent read with the tenant predicate; composite foreign key `(tenant_id, legal_entity_id)` | `foreignAndMissingParentsAreIndistinguishable`, `siteCannotReferenceAnotherTenantsLegalEntity` |
| I7 | Existence of another tenant's legal entity inferred from responses | Identical `404 LEGAL_ENTITY_NOT_FOUND` bodies (apart from `correlationId`) for missing and foreign parents | Body-equality assertions on create and list |
| T7 | Forged, replayed or cross-scope pagination cursors | HMAC-SHA256 over a versioned, allow-listed payload bound to operation, tenant and filters; size and shape checked before decoding; constant-time comparison | `CursorCodecTest`, `cursorsAreBoundToTenantOperationAndParent` |
| I8 | Cursor key disclosure or weak development key in shared environments | Fail-closed start-up guard (missing, short, or `dev-only-` outside development); key and cursors never logged | `CursorSigningKeyGuardTest`, log-capture assertions |
| T8 | Site period outside its legal entity via races or direct SQL | Service check under `FOR SHARE` lock plus database triggers on both tables | Containment API and direct-SQL tests |
| I9 | Codes, names or IDs leak via errors, logs or metric labels | Problem params carry only field names; metric labels limited to operation and outcome | Log and meter assertions |
| E5 | Development fixtures present outside development | Flyway fixture location added only when `divalhr.environment=development` | `DevelopmentSeedFlywayCustomizerTest`, `DevelopmentSeedAbsenceIntegrationTest` |

Accepted for this increment: no PostgreSQL row-level security (tenant predicates, composite keys and tests instead) and a single, non-rotating cursor key.

## Issue #17 delta (hardening)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| S4 | Token without a usable `sub` reaches request parsing, so error precedence differs and actions could lack an accountable actor | Interceptor requires a JWT with non-blank `sub` for every scoped handler before role, tenant, query, argument or body processing; `403 ACCESS_DENIED`, safe log, one `denied` metric | `SubjectRequiredIntegrationTest` |
| T9 | Malformed operation or audit-action names accepted by the database (e.g. `x.---`) | V4 strict grammar on both checks, shared Java `OperationName`, atomic pre-flight | `OperationNameConstraintsIntegrationTest`, `OperationNameTest` |

## MVP-002 Increment 2 delta (departments and cost centers)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| E6 | Employee or platform administrator manages departments or cost centers | `@TenantAdminOperation` (subject, role and tenant checked before parsing) plus `@PreAuthorize` | `SiteUnitApiIntegrationTest`, `SiteUnitListingIntegrationTest`, `MethodSecurityEnforcementIntegrationTest`, French E2E |
| T10 | Department or cost center attached to another tenant's site | Site read with the tenant predicate and locked `FOR SHARE`; composite foreign keys `(tenant_id, site_id)` | `foreignAndMissingSitesAreIndistinguishable`, `parentMustBelongToTheSameTenant` |
| I10 | Existence of another tenant's site inferred from responses | Identical `404 SITE_NOT_FOUND` bodies (apart from `correlationId`) on create and list; an existing empty site returns `200` | Body-equality and empty-list tests |
| T11 | Caller supplies identity or hierarchy fields (`tenantId`, `organizationId`, `legalEntityId`, `id`, `createdBy`) | `additionalProperties: false`; unknown properties rejected; tenant only from the token | `validationUsesStableCodesAndRejectsCallerChosenIdentity` |
| T12 | Child period outside its site, or a site narrowed below its children | Service check under `FOR SHARE`, `site_unit_period_within_site` and `site_period_covers_units` triggers | Containment matrix and direct-SQL tests |
| T13 | Cursor replayed across tenant, operation or site | Signed cursors bound to `department.list` / `cost-center.list`, tenant and `siteId` | `cursorsAreBoundToTenantOperationAndSite` |

## MVP-002 Increment 3A delta (regions and optional site assignment)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| E7 | Employee or platform administrator creates or lists regions, or assigns a site | `@TenantAdminOperation` (subject, role and tenant checked before parsing) plus `@PreAuthorize` | `RegionApiIntegrationTest`, `RegionListingIntegrationTest`, `SiteRegionApiIntegrationTest`, `MethodSecurityEnforcementIntegrationTest`, French E2E |
| T14 | Site linked to a region of another tenant or another legal entity (new object references: `regionId` in bodies, `siteId` in the path) | Tenant-predicated lookups; region must share the site's legal entity; composite `(tenant_id, legal_entity_id, region_id)` foreign key with `MATCH SIMPLE` | Assignment and site-creation tests; `aSiteRegionMustShareTheSiteTenantAndLegalEntity` |
| I11 | Existence of another tenant's site or region inferred from responses | Identical `404 SITE_NOT_FOUND` / `REGION_NOT_FOUND` bodies apart from `correlationId`; the path's site is reported first. `SITE_REGION_LEGAL_ENTITY_MISMATCH`, `SITE_REGION_ALREADY_ASSIGNED` and the same-region `200` only reveal relationships inside the caller's own tenant, which a tenant administrator can already list | Body-equality tests |
| T15 | A site's region changed or cleared, or a region or legal entity narrowed to strand sites | First assignment only (service and `site_region_assigned_once`); containment and backstop triggers; parent-before-child locks | Constraint, concurrency and race tests |
| R3 | Repeated assignments inflate the audit trail | Same region with a new key returns `200` with no audit or outbox record; same key replays | `sameRegionWithANewKeyReturnsTheSiteAndRecordsNothingNew`, parallel same-region test |
| T16 | Caller supplies tenant, legal-entity or server identifiers in the assignment | Body is exactly `{regionId}` (`additionalProperties: false`); tenant and legal entity never taken from the request | `validationUsesStableCodesRejectsExtraPropertiesAndConsumesNoKey` |
| T17 | Region cursor replayed across tenant, operation or legal entity | Signed cursors bound to `region.list`, tenant and `legalEntityId` (a `site.list` cursor for the same legal entity is rejected) | `cursorsAreBoundToTenantOperationAndLegalEntity` |

No new secrets, configuration or personal data. Region names are customer data and never appear in logs, metrics, errors or event data.

## MVP-002 Increment 3B delta (teams)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| E8 | Employee or platform administrator creates or lists teams | `@TenantAdminOperation` (subject, role and tenant checked before parsing) plus `@PreAuthorize` | `onlyTenantAdministratorsMayCreateTeams`, `onlyTenantAdministratorsMayList`, `MethodSecurityEnforcementIntegrationTest`, French E2E |
| T18 | Team linked to another tenant's parent, to a parent of another site, or to both or neither parent | Tenant-predicated parent lookup; `site_id` derived from the locked parent row; composite `(tenant_id, site_id, parent_id)` foreign keys with `MATCH SIMPLE`; `team_exactly_one_parent` check (which wins over the containment trigger for invalid shapes) | `tenantSiteAndParentMustBelongTogether`, `exactlyOneParentCheckRejectsNeitherAndBoth`, `exactlyOneParentIsRequired` |
| T19 | Request-controlled SQL through the parent type | Parent type is a closed `TeamParentKind`; API field, domain kind and table are mapped by exhaustive `switch` statements to fixed SQL | Code review; ArchUnit tenant-predicate rule; listing and constraint tests for both kinds |
| T20 | Caller supplies tenant, site or server fields | `CreateTeam` has `additionalProperties: false`; tenant from the token and site from the parent only | `siteTenantAndServerFieldsCannotBeSupplied` |
| I12 | Existence of another tenant's department or cost center inferred from responses | Identical `404 DEPARTMENT_NOT_FOUND` / `COST_CENTER_NOT_FOUND` bodies apart from `correlationId`, for create and list | `missingAndForeignParentsAreIndistinguishable` (create and list) |
| T21 | Parent narrowed to strand a team, or a team's parent changed | Containment trigger, parent backstops, parent-before-child locks, immutable parent columns | `creationRacingAParentNarrowingNeverStrandsATeam`, `parentsCannotBeNarrowedBelowTheirTeams`, `ownershipSiteAndParentAreImmutableAndFieldsChecked` |
| T22 | Team cursor replayed across tenant, operation, parent type or parent | Signed cursors bound to `team.list`, tenant, parent type and parent id | `cursorsAreBoundToTenantOperationParentTypeAndParentId` |

No new secrets, configuration or personal data. Team names are customer data and never appear in logs, metrics, errors or event data.
