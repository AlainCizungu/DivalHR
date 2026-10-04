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

## MVP-010 delta (invitations and scoped roles)

New trust boundary: anonymous callers reach two public endpoints, and the Core API now holds a privileged identity-provider credential.

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| E9 | Tenant administrator (or a forged request) grants `platform-admin` or an unknown role | Closed `InvitationRole` in the API, `invitation_role_assignable` / `tenant_membership_role_assignable` in the database, and a provisioner that can only create users inside the `employee` and `tenant-admin` role groups (no role-mapping permission) | `onlyTenantRolesCanBeAssignedAndPlatformAdminIsJustAnUnknownValue`, `onlyTenantRolesCanEverBeStored`, `theProvisionerCanNeverGrantPlatformAdminOrTouchOtherIdentities` |
| E10 | Employee or platform administrator manages invitations | `@TenantAdminOperation` plus `@PreAuthorize`; the web page is shown to tenant administrators only | `onlyTenantAdministratorsWithSubjectAndTenantMayManageInvitations`, `MethodSecurityEnforcementIntegrationTest`, French E2E |
| T23 | Invitation or membership linked to another tenant, an organization that does not exist, or a mismatched invitation (A1) | Foreign keys to `tenant.organization`; tenant-aware composite keys between invitation and membership in both directions (one deferred) | `invitationsAndMembershipsBelongToAnExistingOrganization`, `anAcceptedInvitationAndItsMembershipAgreeOnTenantAndSource` (direct SQL) |
| S5 | Token guessed, replayed or leaked through URLs, logs, history, referrers or caches | 256-bit random token, only its hash stored and erased at the end; fragment link stripped by the page; POST bodies only; `no-store`; generic `404 INVITATION_INVALID`; single use; reissue invalidates the previous link | `everyUnusableTokenGivesTheSameAnswer`, `anonymousFlowLogsNeitherTokensNorAddresses`, web and E2E fragment tests |
| S6 | Public endpoints brute-forced; limiter evaded by forged `X-Forwarded-For` (A4) | Mandatory ingress/WAF limits in production; in-process per-client and global limits; forwarding headers only from exact trusted proxies | `untrustedForwardingHeadersAreIgnored`, `trustedProxiesYieldTheNearestUntrustedHop`, `limitsPerClientPerWindowWithRetryAfterAndResetsNextWindow` |
| S7 | A bearer token of another account influences an anonymous acceptance | Separate filter chain without a resource server; the web page uses a client that never attaches a token | `bearerTokensAreNeitherRequiredNorRead`, E2E request assertions |
| I13 | Invitee addresses exposed through logs, metrics, errors, audit, events, idempotency records or caches (A2) | Receipts without the address (replayed exactly); keyed HMAC lookups; redacted `toString`; `private, no-store`; no service-worker runtime caching | `logsMetricsAndErrorsCarryNoAddressTokenOrKey`, `receiptsCarryNoAddressAndPublicOperationsNeedNoToken`, workbox tests |
| I14 | Membership elsewhere or existing accounts inferred by an administrator or an invitee | Conflicts consult only the caller's tenant; `409 INVITATION_CANNOT_BE_ACCEPTED` without a reason; inspection reveals only role, locale and expiry | `aMemberOfTheTenantCannotBeInvitedAgainButAnotherTenantSeesNothing`, `anAddressWithAnIdentityCannotAcceptAndNothingIsDisclosed`, `inspectionRevealsOnlyRoleLocaleAndExpiry` |
| T24 | Delivery state misrepresented after a crash, a timeout or a late result (A3) | Conditional updates on invitation and issuance; stale `QUEUED` becomes `FAILED`, never retried; UI says "sent" only for `SENT` | The four A3 tests in `InvitationDeliveryAndJobsIntegrationTest`; web delivery-wording tests |
| T25 | Concurrent or interrupted acceptances create two identities or memberships; a worker with an expired lease commits | Lease with owner, T2 conditional on the owner, `UNIQUE(subject)`, reconciler and compensation by `divalhr_invitation_id` | `aWorkerWhoseLeaseWasTakenOverCanNeverComplete`, `parallelAcceptancesOfOneTokenYieldExactlyOneMembership`, `aCrashAfterProvisioningIsCompletedByTheReconcilerWithoutASecondIdentity` |
| D2 | Invitation flooding (mail bombing through a tenant) | 50 per tenant per hour, 500 open, 3 reissues at least 5 minutes apart | `hourlyQuotaIsEnforcedPerTenantWithRetryAfter`, `resendIssuesANewLinkInvalidatesTheOldOneAndIsLimited` |
| E11 | Provisioner credential stolen | Least-privilege FGAP v2 permissions scoped to the two role groups; secret from the environment, refused when `dev-only-` outside development | `theProvisionerSecretAndAdminTransportFailClosed`; residual risk accepted below |

Residual risks, accepted for MVP-010 and to be revisited before production: the provisioner fully manages members of the two role groups (credential reset, `tenant_id` edits; **closed by Issue #31**); an old invitation message may still sit in a mailbox after a reissue, though its link no longer works; SMTPUTF8 local parts are not supported.

New secrets and configuration: `DIVALHR_EMAIL_LOOKUP_KEY`, `DIVALHR_KEYCLOAK_PROVISIONER_SECRET`, mail credentials (`DIVALHR_MAIL_*`), `DIVALHR_TRUSTED_PROXIES`. New personal data: invitee email addresses (confidential) in `identity.invitation`, deleted 90 days after the invitation ends.

## Issue #27 delta (development identity provider over HTTP)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| S8 | The development realm's plain-HTTP relaxation (`sslRequired: none`) reaches a shared environment | Only a realm named exactly `divalhr-dev` may relax TLS; the Core API refuses that realm and any non-`https` issuer (parsed URI, normalized scheme) in staging and production | `DevelopmentRealmBoundaryTest.onlyTheDevelopmentRealmMayAcceptPlainHttp`, `DevelopmentSeedGuardTest` |
| I15 | Development credentials or sign-in traffic reachable over HTTP from the network | Keycloak published on `127.0.0.1` only; the Core API uses the internal `keycloak:8080` address | `composePublishesKeycloakOnLoopbackAndKeepsTheBrowserFacingSettings` |
| T26 | Relaxing TLS silently relaxes other client settings | PKCE S256, exact redirect URI and web origin, disabled password, implicit and device grants pinned by test | `theBrowserClientKeepsPkceExactRedirectsAndNoPasswordGrant` |
| T27 | A malformed or credential-bearing issuer slips through configuration | Issuer parsed as a URI; relative, opaque, host-less, user-info, query and fragment forms refused in every environment | `malformedRelativeOrUserInfoIssuersAreRejectedEverywhere` |

The `master` realm keeps `external`; local administration uses `kcadm.sh` inside the container. No secrets, tokens, addresses or passwords are logged; guard messages name the rule, never the configured value.


## MVP-011 delta (privileged MFA)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| S9 | Stolen password of a privileged user | The realm browser flow requires level 2 (TOTP) for holders of the marker role that `platform-admin` and `tenant-admin` imply; `divalhr-web` requests and enforces the `mfa` level as default and minimum; the Core requires `acr` `urn:divalhr:loa:mfa` on every privileged operation | `KeycloakMfaContainerTest.bothPrivilegedRolesNeedTheirTotpAndThenCarryTheMfaLevel`, `aPasswordLevelRequestCannotSkipTheTotpOfAPrivilegedUser`, `MfaAssuranceIntegrationTest` |
| S10 | An attacker who has only the password enrolls their own authenticator at first sign-in | Strict flow: a privileged user without an authenticator is denied with a bilingual message; enrollment happens only through an administrator-sent action link, which proves control of the mailbox | `aPrivilegedUserWithoutAnAuthenticatorIsDeniedInTheirLanguage`, `anInvitedTenantAdministratorSetsAPasswordAndAnAuthenticatorFromTheLink` |
| S11 | An authenticator is removed from a password-only session, then re-enrolled | A privileged session is always at level 2; Keycloak 26.7.4 refuses deleting a credential above the session's level (measured); the strict flow blocks re-enrollment at sign-in | Measured on Keycloak 26.7.4 (proposal, section 2); strict-flow test above |
| S12 | One-time codes guessed or replayed | 6 digits every 30 s, one period of skew, codes not reusable, temporary lockout after 5 failures (60 s increments, at most 15 min, never permanent) | `wrongAndReplayedCodesAreRejected`, `repeatedFailuresLockTheAccountTemporarilyWithoutPermanentLockout`, `DevelopmentRealmBoundaryTest.otpPolicyBruteForceEventsAndMessagesArePinned` |
| E12 | A privileged token without MFA evidence reaches the Core: misconfiguration, a promotion inside a password session, a refresh, a downgraded `acr_values`, another client | Fail-closed Core check of the exact scalar `acr`, after subject, role and tenant and before any argument, query or body; method security requires the `ASSURANCE_MFA` authority too; other clients' tokens lack the Core audience | `MfaAssuranceIntegrationTest`, `MethodSecurityEnforcementIntegrationTest`, `aPromotionInsideAPasswordSessionIsRefusedByTheCoreUntilStepUp`, `noOtherGrantOrClientYieldsATokenTheCoreAccepts` |
| E13 | An employee manufactures privileged assurance | Level 2 runs only for the marker role; the marker role grants nothing in the Core and never appears in its authorities, responses or logs | `anEmployeeSignsInWithAPasswordOnlyAndGetsPasswordAssurance`, `theMarkerRoleAndAmrNeverStandInForTheAcr`, `theSessionEndpointNeedsNoMfaAndNeverShowsAssurance` |
| E14 | Client-supplied assurance (header, frontend flag, user attribute, `amr`, enrollment state) | Only the signed `acr` claim counts; nothing else is read | `neverDerivesAssuranceFromAmrOrTheMarkerRole`, `AssuranceEvidenceTest` |
| E15 | Contract and handlers drift: a privileged operation without MFA, or MFA on a public one | `x-divalhr-required-assurance: mfa` and `PrivilegedForbidden` must match exactly the privileged handlers | `ApiContractDriftTest.requiredAssuranceMatchesPrivilegedHandlers` |
| E16 | `admin-cli` tokens used against the Core | Kept for the development `kcadm.sh` runbook (A1); its tokens carry no Core audience, roles, tenant or `acr`, and the realm's own `admin-cli` still enforces a privileged user's TOTP on password grants | `noOtherGrantOrClientYieldsATokenTheCoreAccepts` |
| T28 | The provisioner secret is used to delete the authenticator of an invited administrator (a role-group member), then the attacker enrolls one through a setup link | **Closed by Issue #31** (see the delta below): the provisioner holds no admin permission; the extension refuses setup links and deletion for an enrolled administrator | `theProvisionerCannotTakeOverAnEnrolledTenantAdministrator` |
| I16 | TOTP secrets or codes in logs, traces, screenshots, reports or failure snapshots | The Core never sees them; tests keep them in memory and type codes through the DOM; Playwright traces, screenshots, videos and failure page snapshots are off | `results carry rule names and outcomes only`, `playwright.config.ts`, host log scan for `dev-only-totp` |
| R4 | MFA failures and lockouts go unnoticed | Keycloak login and admin events enabled without representations; monitoring runbook (Keycloak README) | `login-and-admin-events` rule |
| D3 | Lockout used to deny service to administrators | Lockouts are temporary (at most 15 min) and never permanent | Realm boundary test and container test |

Residual risks, accepted for MVP-011: T28 (closed by Issue #31); a level-2 session lasts up to the SSO session (10 hours, 30 minutes idle) and the level's max age is enforced at the next authorization, not on refresh; there are no recovery codes, so a lost authenticator requires an administrator (runbook). Fresh step-up for sensitive operations and durable denial auditing (MVP-013) are future work.

No new application secret. New published development-only data: TOTP seeds of the three privileged seed users.

## Issue #31 delta (narrow Keycloak provisioning)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| T28 | (closed) Authenticator takeover with the provisioner secret | No admin permission; three invitation-keyed extension operations; setup and compensation refused for an enrolled administrator | `theProvisionerCannotTakeOverAnEnrolledTenantAdministrator` (real image, enrolled administrator, full negative Admin API matrix) |
| E17 | Provisioner secret used for any other Admin API action (credentials, attributes, groups, roles, disable, delete, impersonation, action emails, reads) | The service account holds only `divalhr-provisioning/provision-invitations`; every Admin API call answers 403 | Negative Admin API matrix in `KeycloakProvisioningExtensionContainerTest`; `theProvisionerHoldsOnlyTheCapability` |
| S13 | Another caller spoofs the provisioner at the extension (user tokens, `admin-cli`, master realm, another client with a forged audience, a provisioner without the audience or capability) | Audience, `azp`, live service-account link and live capability role checked before any input; capability revocation takes effect at once | `unauthorizedCallersGetOneAnswerWhateverThePathBodyOrIdentity`, `aProvisionerTokenWithoutTheAudienceIsUnauthorized`, `anotherClientOrAProvisionerWithoutTheCapabilityIsForbidden` |
| I17 | Unauthorized callers learn identity state through status or body differences | Authorization before path validation, body reading and lookup; one detail-free 401 or 403 | The same tests, over existing, unknown and malformed IDs and bodies |
| T29 | Input smuggling: privileged roles, arbitrary groups or attributes, credentials, flag overrides, cross-tenant replays, oversized or multiple JSON values | Strict allow-list; server-decided values; never updates an existing user; 2 KiB streaming limit; one object; `application/json` only | `bodiesAreBoundedStrictAndNeverEchoed`, `identicalConcurrentCreatesNeverConflictAndYieldOneIdentity`, extension unit tests |
| T30 | Two identities carry one invitation ID; an operation mutates the wrong one | Exact, bounded lookup; more than one match fails closed without change | `twoIdentitiesCarryingOneInvitationFailClosed` |
| R5 | A partial or drifted setup is reported as sent; compensation deletes a user whose setup started | Role-specific states (A1); `SETUP_STATE_INVALID` recorded as `FAILED` and alerted; compensation only for pristine identities | `employeeSetupStates`, `tenantAdministratorSetupStates`, `anInvalidSetupStateIsFailedAtOnceAndAlertedNeverSent`, `aRefusedCompensationIsAlertedAndNeverLoops` |
| T31 | Keycloak upgrade silently changes the internal SPI the extension uses | Exact pin; start-up guard refuses any other Keycloak version; consistency test across Dockerfile, tests and build; real-image tests on every bump | `anExtensionBuiltForAnotherKeycloakVersionStopsStartUp`, `KeycloakImageConsistencyTest`, `VersionGuardTest` |
| E18 | Broad rights remain after cut-over, directly or transitively (groups, composite roles, permission policies) | Final-mode realm verification checks effective roles, groups and policies; pre-cutover mode is never evidence | `pnpm realm:verify` (final), `verify-realm.test.mjs` |
| E19 | The caller chooses the setup role (for example asks for an employee's actions on a tenant administrator), or a new identity inherits realm default roles that later grow | Credential setup takes no body and derives the role from exactly one approved role group; identities are created without default roles or groups | `employeeSetupStates` (a role in the body is refused; no or both groups are invalid), `newIdentitiesHoldOnlyWhatTheirRoleGroupGrants` (with `platform-admin` added to the realm defaults); `realm:verify` rule `invitation-groups-least-privilege` |
| D4 | A harmless race between identical creates denies a legitimate invitation | The race is settled from committed state (200) or answered with a retryable `503 IDENTITY_BUSY`; the Core maps only a coded `IDENTITY_CONFLICT` to a conflict | `identicalConcurrentCreatesNeverConflictAndYieldOneIdentity`, `KeycloakIdentityDirectoryTest`, `aLostCreationRaceIsRetryableAndNeverDeniesTheInvitation`, `theReconcilerTreatsALostCreationRaceAsDeferredNeverAsAConflict` |
| I18 | Identity data leaks through extension logs, events or responses | Only operation, outcome and IDs recorded; responses carry codes and the subject only | `operationsLeaveSanitizedEvidence` |

New component: the DivalHR Keycloak image with the `divalhr-provisioning` extension (internal Keycloak SPI, ADR 0006). No new secret; no new personal data.

## MVP-014 delta (first tenant administrator, Issue #38)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| E21 | A platform administrator adds an administrator to an active tenant (privilege injection, insider or stolen session) | Only while no tenant-admin membership and no open tenant-admin invitation of any origin exists, checked under the organization lock; partial unique index; exact MFA; audit with the platform actor; per-actor limit | `bootstrapIsBlockedByAnAdministratorOrAnyOpenAdministratorInvitation`, `aTenantAdministratorMembershipBlocksBootstrapAndStatusSaysSo`, `onePlatformAdministratorCanBootstrapOnlyAFewOrganizationsPerHour` |
| E22 | Bootstrap used for impersonation or to set a known password | The invitee alone completes acceptance, password and TOTP in Keycloak; the narrow extension creates the identity without credentials (Issue #31) | `first-admin.spec.ts` (real Keycloak, password and TOTP) |
| T33 | Concurrent bootstraps, tenant-admin invitations or acceptances create two first administrators | One organization lock with one lock order on every tenant-admin path, predicates re-checked under it; acceptance supersedes a bootstrap before provisioning and again before commit | `everyTenantAdminPathWaitsForTheSameOrganizationLockAndEmployeesDoNot`, `eightConcurrentCreatesWithDifferentKeysYieldOneInvitationAndOneEmail`, `aTenantOriginAdministratorInvitationAndABootstrapNeverCrossTheirChecks`, `aBootstrapAcceptedAfterAnotherAdministratorNeverReachesTheIdentityProvider`, `anAdministratorAppearingWhileTheBootstrapIsProvisionedIsDetectedAndCompensated`, `theOpenBootstrapIndexHoldsEvenWithoutTheLock` |
| T34 | A key reused against another organization replays or overwrites a bootstrap | Fingerprint includes the organization and canonical payload | `aLostResponseRetryReplaysExactlyAndAKeyCannotMoveToAnotherOrganization` |
| I21 | The platform administrator learns tenant members, addresses or invitations | Receipts without addresses; status shows only the bootstrap invitation; identical 404 for malformed, missing and non-active organizations; no raw IDs in logs | `statusShowsOnlyTheOpenBootstrapReceipt`, `unknownMalformedAndOtherOrganizationsAreIndistinguishable` |
| S15 | Tenant-claim confusion (a platform administrator's `tenant_id` claim used as the target) | Target only from the path, resolved through `OrganizationDirectory`; the claim is ignored | `createsATenantAdminInvitationForThePathOrganizationOnly` (tokens carry an unrelated tenant claim) |
| R7 | Unattributed or partially recorded first-administrator creation | `invitation.bootstrap-*` audit with actor and correlation ID, committed with the invitation and outbox event or not at all; no email before commit | `createResendRevokeAndSupersessionFailClosedWhenTheAuditCannotCommit` |
| D5 | Lock contention blocks tenant administration | Local `lock_timeout` (default 5 s); a timeout writes nothing and sends nothing | `aLockHeldTooLongFailsSafelyWithoutWritesOrEmail` |

No new secret and no new personal data category. New configuration: `DIVALHR_TENANT_ADMIN_LOCK_TIMEOUT`, `DIVALHR_BOOTSTRAP_PER_ACTOR_PER_HOUR`.

## MVP-012A delta (membership authority, Issue #37)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| E20 | A role or `tenant_id` granted only in Keycloak (realm-administrator error, compromised mapping) gives Core access | Membership gate: token ∩ active membership, exact role, after MFA and before binding | `everyMembershipMismatchIsAnOrdinaryAccessDeniedBeforeTheBodyIsRead`, `membership-authority.spec.ts` (real Keycloak) |
| E23 | Role hierarchy confusion (a tenant-admin membership satisfying an employee operation, or a platform role satisfying a tenant one) | Exact equality, one central rule; `platform-admin` never derived from a membership | `roleLessTenantOperationsAcceptOnlyAMembershipRoleTheTokenAlsoHolds`, `aPlatformAdministratorWithATenantClaimGainsNoTenantAccess` |
| S16 | A member of tenant B presents a token claiming tenant A | The membership's tenant must equal the verified tenant; one tenant per subject | `aMemberOfAnotherTenantCannotUseAForgedTenantClaim` |
| I22 | Denials or the session reveal why access is missing | Identical `ACCESS_DENIED` bodies; session lists effective roles only; logs carry no subject, tenant or membership ID | Identical-body and log assertions; session matrix |
| T35 | A cached authorization outlives a membership change | No cross-request cache; committed state read per request | `aMembershipTakesEffectAtTheNextRequestWithoutAnyCache` |
| T36 | A new invitation-backed membership ends without its address, or with another one | V10 insert trigger anchored to the locked source invitation; immutability | `MembershipEmailMigrationIntegrationTest` (rolling writer included) |
| D6 | Membership lookup outage | Fail closed with `500` before the handler; nothing written | `aLookupFailureFailsClosedBeforeTheHandler` |
| E24 | An unnoticed Keycloak-only user loses or keeps access at rollout | Read-only preflight (counts and IDs only) and rollout gate; no runtime off switch | `membership-preflight.test.mjs`, CI and host preflight runs |

No new secret. New development-only data: fixed realm user IDs and four seed memberships (application seeder, development only).

## MVP-012B delta (access review, Issue #37)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| I19 | A compromised tenant-admin session harvests every member address | MFA and 12A gate, page size ≤ 50, 30 requests per minute per subject, durable audit per disclosure, no export, no prefix search | `thirtyReviewRequestsPerMinutePerSubjectAcrossTheThreeOperations`, `everySuccessfulDisclosureIsAuditedWithItsCanonicalDigest` |
| I20 | Addresses leak through URLs, logs, caches, metrics or analytics | POST lookup, no URL or storage state, `private, no-store` on every status, observability allow-lists | `logsAndMetricsCarryOnlyAllowListedValues`, `lookupNormalizesMatchesExactlyAndNeverEchoesTheInput`, Vitest URL and storage checks |
| S14 | A foreign legal-entity or site ID probes another tenant | Tenant-bound `OrganizationUnitDirectory`, identical 404s, no audit | `legalEntityAndSiteViewsShowEveryMemberAsInheritedFromTheOrganization` |
| T32 | A cursor is replayed across tenants, filters or page sizes, or forged | HMAC binding including role, unit and page size; verified before any query | `cursorsAreBoundToTenantRoleUnitAndPageSize` |
| R6 | A disclosure goes unaudited or its content cannot be established | Query and `access-review.read` in one transaction; body only after commit; digest v1 golden vectors | `noReviewDataLeavesWhenTheDisclosureAuditCannotCommit`, `AccessReviewDigestTest` |
| D7 | Expensive unbounded queries | V11 keyset indexes, page size ≤ 50, counts only in the summary | `AccessReviewIndexMigrationIntegrationTest` |

No new secret and no new personal data category. New configuration: `DIVALHR_ACCESS_REVIEW_REQUESTS_PER_MINUTE`.


## MVP-013 delta (authorization denial audit, Issue #43)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| R8 | A privileged denial cannot be attributed to the identity that attempted it | One append-only `platform.authorization_denial` row per eligible denial (stages 2-7), actor = verified `sub` only, written before the denial is thrown | `everyPrivilegedHandlerRecordsEveryApplicableStage`, `eachDurableStageWritesOneRowWithTheEffectiveTenantRule`, `denial-audit.spec.ts` (real Keycloak) |
| S17 | Anonymous or forged traffic manufactures actor evidence | Only a verified non-blank JWT `sub`; 401s and subjectless tokens are never durable | `anonymousInvalidAndSubjectlessTrafficIsNeverAttributedAndAllowedCallsNeverWrite` |
| I23 | Denial evidence leaks the actor, a foreign tenant or request data | Typed columns, no metadata; effective tenant only; no subject in logs or metrics (stage-2 log fixed); recorder errors logged by type only | `malformedBodiesQueriesAndPathTargetsNeitherChangeTheStageNorEnterTheEvidence`, `denialTelemetryCarriesOnlyBoundedServerOwnedLabels` |
| D8 | A valid session floods denial writes or starves the application pool | Per-actor and per-instance budgets checked before any connection; separate two-connection bulkhead with 1 s / 2 s timeouts; first rate-limit refusal per window only | `DenialAuditBoundsIntegrationTest`, `DenialAuditBudgetTest` |
| T37 | Evidence silently lost or rolled back with the rejected request | `REQUIRES_NEW` on the bulkhead's own transaction manager; failures keep the denial and raise the `failed` metric, error log and alert (no durability claim during an outage) | `theDenialRowSurvivesARolledBackOuterTransactionAndBusinessAuditStillNeedsOne`, `aStorageFailureKeepsTheNormalDenialAndOnlySignalsTheGap`, `aStorageFailureKeepsThe429AndItsRetryAfter` |
| E25 | Annotation or configuration drift lets method security and the interceptor disagree, or any `AccessDeniedException` is taken for drift | Provenance from Spring method security's own authorization event; drift row, metric and alert only then | `onlyAProvenMethodSecurityDenialIsDriftAndAPlainDenialIsNot` |
| T38 | Evidence deleted or altered | Append-only triggers; rollback refuses while rows exist; no deletion schedule until one is approved | `AuthorizationDenialMigrationIntegrationTest` |

No new secret and no new personal data category (the subject is already the audit actor). New configuration: `DIVALHR_DENIAL_AUDIT_PER_ACTOR_PER_MINUTE`, `DIVALHR_DENIAL_AUDIT_PER_INSTANCE_PER_MINUTE`. Residual risks: evidence gaps while the database is unavailable (signalled, not prevented); suppressed attempts are counted only in telemetry; no approved retention period.

## MVP-020 delta (employee import, Issue #45)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| S18 | A caller without the tenant-admin role, MFA or membership, or another tenant, uploads or reads an import | `@TenantAdminOperation` on all six operations, checked before any body byte is read; import IDs resolved in the verified tenant only (same 404 for foreign and unknown) | `unauthorizedCallersAreDeniedBeforeAnyByteIsReadAndOtherTenantsSeeNothing`, `AuthorizationDenialAuditIntegrationTest`, `employee-import.spec.ts` |
| D9 | Oversized, slow or pathological files exhaust memory, threads or the database | Multipart disabled; 2 MiB streamed cap; 30 s body deadline and 20 s idle timeout; line, column and row limits; statement and transaction timeouts; 3 open imports per tenant | `anEndlessBodyIsCutOffJustAfterTheCap`, `worstCaseFilesAtTheTransportCapStayWithinBoundedMemory`, `aSlowBodyTimesOutAndABrokenOneIsATimeoutToo`, `aDatabaseTimeoutRollsEverythingBackAndARetrySucceeds` |
| D10 | One administrator or one tenant floods uploads and commits | Per-subject (10/min) and per-tenant (30 uploads, 60 commits per 10 min) buckets, applied independently after authorization | `theSubjectBucketLimitsOneAdministratorAcrossUploadAndCommit`, `theTenantBucketsLimitUploadsAndCommitsIndependentlyOfSubjects`, `TenantRateLimiterTest` |
| T39 | A parser flaw or ambiguous CSV yields different rows than the administrator reviewed | Apache Commons CSV behind a port enforcing a strict subset; the commit repeats the preview digest and count; re-validation inside the commit transaction; fuzz tests | `randomInputIsEitherAFileOrAFileLevelProblem`, `uploadsReplayCommitsAreBoundToThePreviewAndStaleImportsCreateNothing` |
| T40 | A retried or concurrent commit creates duplicate employees or a partial import | Idempotency keys, row lock on the import, unique employee numbers per tenant, single transaction with rollback on any conflict | `concurrentCommitsOfOneImportCreateEachEmployeeOnce`, web `retries a lost commit with the same idempotency key` |
| T41 | Spreadsheet formula injection or markup through stored names, or generated CSV | Values starting with `=`, `+`, `-` or `@` rejected; names limited to letters, marks, space, apostrophes, period and hyphen, in the application and in PostgreSQL (`people.person_name_valid`, R20-1); generated CSV cells quoted and neutralized | `formulaPrefixesAndControlCharactersAreRejectedInEveryField`, `CsvCellsTest`, `v13ChecksMirrorTheApplicationRules`, `v13NamesFollowTheImportGrammarInStoredAndStagedRows`, `EmployeeNameGrammarDriftTest` |
| I24 | Personal data leaks through audit, outbox, logs, metrics, caches, URLs or invalid-row echoes | Counts and IDs only in audit and outbox; no employee IDs in results; invalid rows keep codes only; `private, no-store`; page state in memory only | `aFrenchSemicolonFileIsPreviewedAndItsValidRowsCommittedAtomically`, `metricsCarryOnlyBoundedLabels`, web URL and storage checks |
| I25 | Staged personal data outlives its purpose | Values erased at commit, discard or 2-hour expiry; import details deleted 30 days after closing | `openImportsAreCappedDiscardedExpiredAndTheirDetailsRetainedThirtyDays` |
| R9 | Employee creation cannot be attributed | One `employee.create` audit event per employee with a state hash, plus the import's create, commit, discard or expire event | `aFrenchSemicolonFileIsPreviewedAndItsValidRowsCommittedAtomically` |

New personal data: employee numbers and names (Confidential); employment dates and placements (Restricted HR); staged values inherit their field's class. New dependency: Apache Commons CSV 1.14.1 (its dependencies commons-io and commons-codec were already on the runtime classpath). New configuration: `DIVALHR_EMPLOYEE_IMPORT_REQUESTS_PER_MINUTE`, `DIVALHR_EMPLOYEE_IMPORT_TENANT_UPLOADS`, `DIVALHR_EMPLOYEE_IMPORT_TENANT_COMMITS`, `DIVALHR_EMPLOYEE_IMPORT_MAX_ROWS`, `DIVALHR_EMPLOYEE_IMPORT_STAGING_TTL`, `DIVALHR_EMPLOYEE_IMPORT_RESULT_RETENTION`, `DIVALHR_EMPLOYEE_IMPORT_JOBS_ENABLED`. Residual risks: in-process limits (cluster ceiling = value × instances); encryption at rest is a production gate; no malware scanning (CSV text is parsed, never stored or served back).

## MVP-021 delta (employment history, Issue #47)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| S19 | A caller without the tenant-admin role, MFA or membership, or another tenant, reads or changes an employee's history | `@TenantAdminOperation` on all nine operations before any argument or body is read; employee and change IDs resolved in the verified tenant only (same 404 for foreign, unknown and malformed) | `readsAreAuditedDisclosuresWithoutSearchTextOrEmployeeData`, `AuthorizationDenialAuditIntegrationTest` (40 operations), `employment-history.spec.ts` |
| T42 | History is rewritten or erased (direct SQL, a defect or a compromised service), including later writes in the name of a committed change | Immutable values and dates, single-move supersession pair, same-employment composite FKs, no delete or truncate, append-only change log, exact change shapes revalidated on every assignment insert and supersession (R21-1), all enforced by PostgreSQL | `historyIsImmutableAndEveryWriteHasItsExactShape`, `laterWritesInTheNameOfACommittedChangeAreRevalidated` (mutation-checked) |
| T43 | Two concurrent writes close a reporting loop or overlap rows | Deferred cycle trigger takes the tenant's manager-graph advisory lock itself and checks every interval; exclusion constraints; employment row lock and version check in the service | `concurrentManagerAssignmentsNeverCloseALoop` (mutation-checked: removing the lock fails it), `aConcurrentCancellationAndChangeCommitAtMostOne` |
| T44 | A commit writes something other than what the administrator previewed | Commit repeats the version and digest; full recomputation under lock; idempotent replays | `aScheduledChangeIsBoundToItsPreviewAndPublishedWithIdentifiersOnly` |
| T45 | A cancellation silently alters later changes or loses lineage | Restoration bounded by the next active row; dependents refused; cancelled change and rows kept; restored rows name what they restore | `EmploymentTimelineTest`, `cancellingRestoresTheReplacedValueAndNeverAltersLaterChanges`, `aChangeWithDependentsIsNotCancelledAndSeveralKindsCancelTogether` |
| T46 | Back-dated changes rewrite payroll-relevant history without intent | Retroactive changes need a reason and an explicit acknowledgement, within a configurable window | `retroactiveChangesNeedAReasonAnAcknowledgementAndTheWindow` |
| I26 | Restricted HR data leaks through audit, outbox, logs, caches, URLs or search text | Disclosure metadata `{view, page, resultCount, schemaVersion}`; write metadata counts and versions; outbox IDs only; search text in POST bodies only and never logged; `private, no-store`; page state in memory | `readsAreAuditedDisclosuresWithoutSearchTextOrEmployeeData`, `aScheduledChangeIsBoundToItsPreviewAndPublishedWithIdentifiersOnly`, web URL and storage checks |
| R10 | A read or change cannot be attributed | Every read and preview is an audited disclosure (fail-closed); every write has its audit event with a timeline digest | the same integration tests |
| E26 | A rollback destroys history or corrupts migration state | The rollback refuses once history exists, never edits `flyway_schema_history` and never drops `btree_gist` | `theRollbackRefusesOnceHistoryBeyondTheHireExists`, `theBackfillRecordsEachHireAndTheRollbackRestoresV13Exactly` |

New personal data: manager relationships, contract classifications, compensation bases (Restricted HR) and the name search key (Confidential). New database prerequisite: `btree_gist`. New configuration: `DIVALHR_EMPLOYMENT_RETROACTIVE_DAYS`, `DIVALHR_EMPLOYEE_READ_REQUESTS_PER_MINUTE`, `DIVALHR_EMPLOYMENT_CHANGE_REQUESTS_PER_MINUTE`, `DIVALHR_EMPLOYEE_SEARCH_TENANT_REQUESTS`, `DIVALHR_EMPLOYMENT_CHANGE_TENANT_WRITES`. Residual risks: in-process limits; reads by any tenant administrator are not scoped by unit (no HR role yet); encryption at rest remains a production gate.

## MVP-022 delta (separation and access revocation, Issue #49)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| S20 | A caller without the tenant-admin role, MFA or membership, or another tenant, separates an employee, links access or retries a revocation | `@TenantAdminOperation` on all eleven operations before any argument or body is read; employee, separation, task and link IDs resolved in the verified tenant only | `AuthorizationDenialAuditIntegrationTest` (every handler, every stage), `SeparationIntegrationTest`, `separation.spec.ts` (employee gets 403) |
| E27 | A separated employee keeps using DivalHR while Keycloak is slow or down | The membership gate denies from `effective_at` (database clock), independently of jobs and Keycloak; session, gate and access review share one predicate | `immediateRemovalDeniesAccessAtOnceWhateverTheProviderDoes`, `aScheduledSeparationClosesTheTimelineKeepsAccessUntilTheEndAndCanBeCancelled`, `separation.spec.ts` (mutation-checked: removing the predicate fails them) |
| E28 | A tenant administrator, a platform administrator or the caller's own access is revoked, or an attacker separates the last administrator | Separation refused for a linked tenant-admin membership and for the caller's own access; revocations accepted by the database only for `employee` memberships; the extension refuses any identity that is not an employee identity it created for the tenant | `aTenantAdministratorAndTheCallerThemselvesAreNeverSeparatedHere`, `linksAndRevocationsAreTenantBoundEmployeeOnlyAndMoveOnlyForward`, `RevocationPolicyTest`, `accessRevocationDisablesOnlyAnEmployeeIdentityOfTheTenant` |
| T47 | The worker disables an unrelated or later-linked identity (stale link, changed membership, tenant mismatch) | Revalidation under the revocation lock before every call, subject read from the membership at that moment; otherwise no call and `MANUAL_INTERVENTION` with a closed code (A22-5); extension-side tenant and role checks | `onlyTheOriginalActiveLinkAndEmployeeMembershipOfTheTenantMayReachTheProvider`, `theWorkerNeverDisablesAnIdentityWhoseBindingChanged`, `accessRevocationDisablesOnlyAnEmployeeIdentityOfTheTenant` |
| T48 | A separation commits partially (history ended but reports, reminders or revocation missing), or concurrent paths deadlock | One coordinator transaction, identity ports `MANDATORY`, global lock order, deferred checks | `AccessPortArchitectureTest`, `SeparationConcurrencyIntegrationTest` |
| T49 | Direct-report history is rewritten beyond the separated manager's intervals, or a later change is lost | One bound change per affected row, later intervals kept, exact shapes and reversal enforced by PostgreSQL, preview digest over every interval (A22-2) | `everyAffectedReportIntervalIsRewrittenAndRestoredExactly`, `aSeparatedManagerNeverKeepsAReportAndBoundChangesAreExact`, `SeparationPlannerTest` |
| T50 | A future change survives after the last day, or an employment end is set or cleared outside a separation | Blockers from the active timeline and lineage (A22-3); `employment_end_governed`; within-employment and manager-employed triggers | `futureEffectsAfterTheLastDayBlockUntilCancelledWhateverWroteThem`, `onlyASeparationEndsAnEmploymentAndItsShapeIsExact` |
| T51 | A commit or cancellation acts on a different state than previewed | Version and digest over rows, blockers, intervals, link and access instant; idempotent replays | `SeparationIntegrationTest` (stale preview and replay paths) |
| I27 | Separation data or the looked-up address leaks through audit, outbox, logs, Problem params, URLs or browser storage | Metadata allow-list, hashes in `after_state_sha256` (A22-6); outbox IDs only; address in POST bodies only; closed Problem params; `private, no-store`; in-memory page state | `logsCarryNoPersonalOrRestrictedValues`, `Separation.test.tsx`, `separation.spec.ts` evidence checks |
| R11 | A revocation outcome or a manual action cannot be attributed | Audit for every transition (request, effective, provider outcome, cancel, retry) with closed codes and attempts; durable state on the revocation row | `aRefusalNeedsInterventionAndARetryStartsAFreshBudget` |
| D11 | Keycloak outages or a backlog of revocations go unnoticed | Bounded retries with backoff, `MANUAL_INTERVENTION` after 10 attempts, gauges and alerts for manual and overdue revocations | `AccessRevocationJobsTest`, runbook in `SECURITY.md` |

New personal data: separation details and follow-up reminders (Restricted HR) and employee-to-membership links (personal-data references). New identity-provider operation: access revocation (disable, end sessions). New configuration: `DIVALHR_ACCESS_REVOCATION_BATCH_SIZE`, `DIVALHR_ACCESS_REVOCATION_JOBS_ENABLED`, `DIVALHR_SEPARATION_JOBS_ENABLED`.

## MVP-030 delta (contracts and acknowledgement, Issue #51)

| # | Threat | Mitigation | Verified by |
|---|---|---|---|
| S21 | A caller without the tenant-admin role, MFA or membership manages templates or issues, reads or voids contracts | `@TenantAdminOperation` on the 15 administrator operations before any argument or body is read; IDs resolved in the verified tenant | `AuthorizationDenialAuditIntegrationTest` (every handler, every stage), `ContractIntegrationTest` |
| S22 | An administrator, an unlinked member or another employee reads or acknowledges an employee's contract | `@EmployeeSelfOperation` (role `employee`, revocation-aware membership gate) before binding; every self-service query bound to the caller's own active link through the identity port; the same 404 for another employee's, another tenant's, malformed and unknown IDs; `403 EMPLOYEE_LINK_REQUIRED` without a link | `anotherEmployeesOrTenantsContractIsTheSameNotFound`, `anUnlinkedEmployeeIsRefusedAndAnAdministratorHasNoSelfServicePath`, `aSeparationEndsSelfServiceAtOnceAndBlocksNewIssues`, `contracts.spec.ts` (mutation-checked: removing the employee predicate fails them) |
| T52 | Stored or reflected markup, links, scripts or remote content reach a browser through a template or a value (A30-2) | Grammar v1 refuses URI schemes in any case or obfuscation, `//`, web addresses, encoded content, Markdown links and HTML at draft validation; parse before substitution, values literal; text nodes only in the web | `ForbiddenConstructsTest`, `TemplateGrammarTest`, `grammarRejectionsCarryAClosedReasonAndALineNeverTheText`, `Contracts.test.tsx` (inert `<b>`) |
| T53 | An issued contract, template text or evidence is changed afterwards, or evidence names another text | Immutability triggers; insert-only evidence whose composite key carries the snapshot digest and versions; no TRUNCATE; read-only daily integrity job and alert | `ContractMigrationIntegrationTest`, `theIntegrityJobReportsATamperedDigestAndRepairsNothing` |
| T54 | A digest of one kind is accepted as another, or a weaker version is replayed (A30-4) | Domain separators and versions in every preimage; server-side recomputation and constant-time comparison; `CHECK = 1` version columns; pinned statements and golden digests | `ContractDigestsTest`, `theEmployeeAcknowledgesTheirOwnContractOnceWithBoundEvidence` (changed digest 409, wrong version 400) |
| T55 | An issue uses stale data, overlaps another contract, lands after a separation, or concurrent paths deadlock | Preview digest recomputed under the employment `FOR SHARE` and guard `FOR UPDATE` locks (ADR 0009 lock order); exclusion constraint; separation check under lock | `ContractConcurrencyIntegrationTest` (issue × issue, issue × separation, acknowledge × acknowledge, acknowledge × void, acknowledge × unlink) |
| R12 | An acknowledgement is disputed, or misrepresented as a signature (A30-3) | Evidence binds contract, employee, membership, link, snapshot, statement and database time; fixed terminology; "signature" only in the negative disclaimer; statement wording pending counsel review (D16) | `ContractTerminologyTest`, `Contracts.test.tsx` terminology test |
| I28 | Contract text, names, dates, types or statements leak through logs, metrics, audit, outbox, Problem params, URLs or browser storage | Metadata allow-list; digests in `after_state_sha256`; outbox IDs only; closed Problem params; `private, no-store`; in-memory page state | `logsCarryNoNamesTemplateTextOrDigests`, `assertPrivate`, `contracts.spec.ts` storage and evidence checks |
| D12 | Repeated self-service denials flood durable denial evidence (A30-1) | Durable rows only for privileged operations; self-service denials on a bounded counter; per-subject `contract-self` limit | `employeeSelfServiceDenialsWriteNoRowAndAreCountedOnly` (50 calls, no row) |

New data: contract templates (Confidential), issued contract snapshots and acknowledgement evidence (Restricted HR). New configuration: `DIVALHR_CONTRACT_READ_REQUESTS_PER_MINUTE`, `DIVALHR_CONTRACT_WRITE_REQUESTS_PER_MINUTE`, `DIVALHR_CONTRACT_SELF_REQUESTS_PER_MINUTE`, `DIVALHR_CONTRACT_TENANT_WRITES`, `DIVALHR_CONTRACT_JOBS_ENABLED`. Residual risks: contract text in PostgreSQL relies on the encryption-at-rest production gate (ADR 0009); in-process limits; the v1 statement needs counsel review before production.
