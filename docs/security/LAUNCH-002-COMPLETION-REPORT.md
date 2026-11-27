# LAUNCH-002 completion report (Issue #230)

Production controlled-pilot environment and operational readiness, implemented directly from the architect-approved
contract and ADR 0048. **Not provisioned, not deployed, no real customer data.**

## 1. Summary and controlled-pilot boundary

The PR adds the production platform as reviewed code and tests: a Terraform stack, a production Compose project and Caddy
edge, a production Keycloak realm renderer, secrets tooling, gated deployment and rollback, backup and restore-drill
tooling, monitoring, the incident and recovery runbooks, the launch lock and the acceptance checks. The pilot is one host in
one availability zone, behind a launch lock that is closed by default and that nothing here lifts. No AWS resource, DNS
record, ACM certificate, provider account or deployment was created; no AWS verification ran; nothing is merged or deployed.
`hr-dev.dival.ai` is unchanged and is neither used nor named by production; `hr.dival.ai` is not used.

## 2. Files and resources

- ADR `docs/DECISIONS/0048-controlled-pilot-production-topology.md`; threat model, incident runbook, readiness and
  onboarding-lock checklists, restore-drill template, evidence map and this report under `docs/security/`; runbook
  `docs/OPS-PRODUCTION.md`; BACKLOG entry for LAUNCH-002 (not deployed) and the carried LAUNCH-004 closure (Issue #230 item 10).
- IaC `infrastructure/production/terraform/*.tf` (network, security groups, KMS, compute, ALB, WAF, storage, IAM, monitoring,
  launch lock); runtime `infrastructure/production/{compose.yaml,caddy/Caddyfile,postgres/10-init.sh}`.
- Tooling `ops/production/*` (secrets, deploy, rollback, backup, drill, metrics, watchdog, units, checks, launch lock,
  acceptance, exposure, image build and mirror, data volume).
- Web: two-origin support (`origins.tsx`, `main.tsx`, `runtime.ts`, `40-divalhr-config.sh`, three commercial files),
  `playwright.production.config.ts`, `e2e/production/{public,tenant}.spec.ts`.
- Tests: `scripts/ops/production-policy.test.mjs` (83), `origins.test.tsx`.
- Resources that **will** exist only after separately approved provisioning: one VPC, ALB and WAF, one EC2 host, encrypted
  EBS volumes and snapshots, KMS key, two S3 buckets, four ECR repositories plus a mirror repository, IAM roles and policies,
  Secrets Manager secrets, SSM parameter, CloudWatch alarms and SNS topics, a Route 53 health check, nine VPC endpoints.

## 3. Decisions and single-host limitations

Decisions L2-1 to L2-12 of ADR 0048 (domains, single host, network, images by registry because the host has no internet, WAF,
secrets, realm, e-mail, data, backup, release, observability, launch lock). Limitations: one host and zone (a failure is a
recovery within the 8 h target, not a failover); database on the host; deployment windows; no blue/green; the ALB-to-host
hop is TLS to an internal-CA certificate that the ALB does not validate, inside private subnets; images are built off-host;
the first drill runs on the production host. Scale path in ADR 0048 section 5.

## 4. Test results

Run in the sandbox (no Terraform, Docker, shellcheck or AWS available there):

- `node --test scripts/ops/production-policy.test.mjs`: **92 passed, 0 failed** (after the R231 round).
- All of `pnpm ops:test`'s files: **284 passed, 0 failed** (sandbox, Linux), including the BACKLOG test.
- `tsc` (app and node projects), `eslint` and `prettier --check` clean on the web changes and the new specs; every shell
  script passes `bash -n`; `render-realm.py` compiles and renders in the tests.
- Not run in the sandbox, to be run on the Mac and in CI: the vitest suite (`origins.test.tsx` and the existing web tests),
  `terraform fmt -check` and `terraform validate` (now also a normal-CI job). `shellcheck -x -S warning` passes locally (0.11.0). **Live acceptance, the two browser journeys, the restore
  drill and any AWS verification were not run, as the issue forbids.**

## 5. Secrets, network, identity and isolation evidence

Static, test-enforced evidence (see the evidence map): only the load balancer is public (443 and the 80 redirect); the host
accepts one listener port from the load balancer only and has no public address, key pair or SSH rule; egress is VPC
endpoints, S3 and, once approved, the e-mail submission range; all storage is encrypted, buckets block public access, are
versioned and TLS-only, the evidence bucket is Object-Locked; IAM is least privilege (the host cannot change the lock or
delete backups, the operator cannot read a secret, the lock-lift policy is attached to nobody); no secret is an environment
value, in Git, or in output; the production realm has no accounts, exact redirect and web origins and the privileged-MFA
marker; no Mailpit, seed account, synthetic provider, test receiver or hr-dev name exists in production configuration. Live
proof (exposure scan, host evidence, final realm verification, log hygiene) is produced by `accept.sh` after approval.

## 6. Backup and restore evidence and measured recovery

Built: nightly encrypted logical dumps of Core and Keycloak with checksums and manifest, a verified off-host copy, EBS
snapshots, age alarms, and an isolated restore drill that proves release and migration state with production watched
throughout and outbound e-mail neutralised. **No recovery time or point is measured yet, because nothing is provisioned.**
The internal targets (RPO 24 h, RTO 8 h) are therefore unproven until the first drill, which is a precondition of acceptance.

## 7. Observability and incident routing

Eleven host metrics (no dimensions) plus ALB, WAF, EC2, certificate and Route 53 alarms notify `contact@dival.ai`
through an SNS topic whose subscription the operator must confirm. A missing heartbeat, backup or drill raises an alarm.
Correlation by `X-Request-Id`; no tenant, employee, e-mail, token or free text in any metric or log. Severity table,
incident log and customer communication templates are in the incident runbook.

## 8. Localization and accessibility

No new user-facing copy: the two-origin change reuses the existing commercial strings. The two live journeys run in English
and French and the public journey runs the axe check used by LAUNCH-004. The commercial site is indexable on `divalhr.com`
and carries no test badge; the application origin is noindex.

## 8A. Correction round R231-1 to R231-7 (architecture review of PR #231)

| Item | Change | Evidence |
| --- | --- | --- |
| R231-1 SMTP route | The CIDR egress rule and `email_provider_cidrs` are removed. Mail leaves only through an Amazon SES SMTP interface VPC endpoint (`email-smtp`, private DNS, endpoint SG with the submission port from the host SG only). `email_submission_port` is limited to the SES ports. `prod_load_mail` pins `PROD_MAIL_HOST` to `email-smtp.<region>.amazonaws.com` of the deployment region; the drill host `drill-no-mail.invalid` is accepted only under `PROD_DRILL_MODE=1`. | policy tests: host egress is exactly endpoints, S3 and the SMTP endpoint; endpoint routable with SG both ways; endpoint list closed (no partner or payment service); host pinned |
| R231-2 deploy gate | New `ops/production/verify-record.py`, used by `deploy.sh` and `remote-deploy.sh`: decision on a copy holding only the chosen run, which must be the accepted one; `profile=full`; complete classification; all 11 rehearsal steps and the three stages PASS; zero Core failures or errors; zero browser failed, flaky, retried or not-run; hygiene PASS; checksum, 0444 mode and freshness from the record timestamps. The workstation sends the record's SHA-256 through SSM; the host verifies it, restores 0444 (lost in the S3 round trip) and re-runs every rule. Only the chosen run is uploaded and downloaded. | 5 new tests in `evidence.test.mjs` (full accepted; pr rejected with a good sibling present; incomplete class, missing step, flaky, retried, not-run, hygiene, Core failure rejected; other tree; stale; host checksum and mode restore) |
| R231-3 acceptance | `accept.sh` fails before Playwright when any credential, origin or mail command is missing, reads a JSON report and `verify-journeys.py` requires exactly two expected tests with none failed, skipped, flaky, retried or not run. | policy tests (static order check; five report fixtures) |
| R231-4 synthetic tenant | `tenant.spec.ts` creates one `ACCEPTANCE-SYNTHETIC` organization through the platform path, invites the first administrator and two employees through production e-mail (read only by the operator's `PROD_ACCEPTANCE_MAIL_CMD`), runs import (fr), leave (fr/en), shift and attendance, timesheet, payroll preparation (en), then separates the employees and waits for access `COMPLETED`. Cleanup of the first administrator identity and the organization is an operator step: `docs/security/LAUNCH-002-ACCEPTANCE-TENANT.md`. Lock stays closed; no pack, no partner, no database edit. | static (eslint, tsc, prettier); **not executed: it needs the production stack, which this story may not touch** |
| R231-5 service workers | Config now `serviceWorkers: 'allow'`; the public journey installs the worker on the first load, reloads, asserts the controller, then proves Sign in reaches the production realm and `/identity/`, `/api/` and `/ai/` are not answered with the application shell. | static; runs at acceptance |
| R231-6 two tests | `public.spec.ts` has one test and `tenant.spec.ts` one; redirect and origin rules stay in unit and policy tests. | policy test counts exactly two, no skip or retry |
| R231-7 CI and Node | `.github/workflows/production-infra.yml`: pinned Terraform 1.9.8 (zip checked against the release SHA256SUMS), `fmt -check`, `init -backend=false`, `validate`, `shellcheck -x -P SCRIPTDIR -S warning` on every `ops/production/*.sh`, and the policy and evidence suites. Host prerequisite is Node.js 24 in the docs and in `realm-verify.sh`; the repository engine is unchanged. One shellcheck warning in `lib.sh` was fixed. | policy tests; local shellcheck clean |

Residual risk: the synthetic tenant journey, the SES endpoint path and the Terraform job in CI have not run against real
infrastructure; the first two are exercised at AWS verification, which this round does not start.

## 9. Limitations, costs and prerequisites

Limitations as in section 3. Planning estimate of roughly USD 200 to 300 per month in a US region, unverified; the nine
interface endpoints are the largest fixed item after the host (run the AWS Pricing Calculator before provisioning).
Prerequisites (operator, outside Git): AWS account and region, DNS zone and ACM certificate, the transactional e-mail provider
(Amazon SES: verified identity, SPF/DKIM/DMARC, production access, SMTP credentials), the confirmed alarm destination, a host image with Docker, Compose, AWS CLI, git,
python3 and Node.js 24, a build machine, and a full verification of the exact release. Until the e-mail provider exists Core
cannot start and onboarding is refused (fail closed).

## 10. Exact next step

Architecture review of this PR. After approval and merge, each execution step in `docs/OPS-PRODUCTION.md` section 4 needs its
own approval, in order: provision, DNS and certificate, data-volume confirmation, secrets, e-mail provider, images, full
verification, first deployment, restore drill, acceptance, with the launch lock still closed.

**Not provisioned, not deployed, no real customer data.**
