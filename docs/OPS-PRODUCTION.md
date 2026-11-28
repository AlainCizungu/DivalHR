# DivalHR production controlled-pilot environment: operations (LAUNCH-002)

Status: **built and tested as code; not provisioned, not deployed, no real customer data.** This is the
operator's runbook for the environment decided in [ADR 0048](DECISIONS/0048-controlled-pilot-production-topology.md)
(Issue #230). Threat model: [LAUNCH-002-PRODUCTION-THREAT-MODEL](security/LAUNCH-002-PRODUCTION-THREAT-MODEL.md).
Incident handling: [LAUNCH-002-INCIDENT-RUNBOOK](security/LAUNCH-002-INCIDENT-RUNBOOK.md). Readiness and
onboarding-lock checklists: [LAUNCH-002-READINESS-CHECKLIST](security/LAUNCH-002-READINESS-CHECKLIST.md). Requirement
to evidence map: [LAUNCH-002-EVIDENCE-MAP](security/LAUNCH-002-EVIDENCE-MAP.md). The test environment's runbook is
[OPS-HR-DEV.md](OPS-HR-DEV.md); production reuses its proven mechanisms and never shares anything with it.

## 1. Boundary and shape

A controlled pilot is **one host in one availability zone**: an internet-facing Application Load Balancer (ACM
certificate) with AWS WAF, in front of one private EC2 host that runs the Compose project `divalhr-prod` (Caddy,
PostgreSQL, Keycloak, Core API, AI service, web). It is **not** high availability, not multi-region, not a Kubernetes or
ECS platform, and not covered by any SLA or certification. The recovery targets (**RPO at most 24 hours, RTO at most 8
hours in a staffed exercise**) are internal engineering targets that a restore drill measures; they are not promises.

| Name                       | Role                                                                                                   |
| -------------------------- | ------------------------------------------------------------------------------------------------------ |
| `https://divalhr.com`      | Commercial site: `/`, `/legal/privacy`, `/legal/terms` and static assets only; everything else 308 to the app |
| `https://www.divalhr.com`  | Redirects (301, at the load balancer) to `https://divalhr.com`                                          |
| `https://app.divalhr.com`  | The application, with `/api`, `/identity`, `/ai` and the auth routes beneath it                         |
| `hr-dev.dival.ai`          | Unchanged, synthetic-only test environment. Production never uses, names or depends on it.              |

There is no `hr.dival.ai`. Nothing in this repository creates DNS records, requests a certificate or provisions a
resource. **Everything that touches AWS, DNS, ACM or an e-mail provider is a separately approved execution step**
(section 4). The environment stays behind the **launch lock** (section 12) until the founder lifts it.

## 2. External prerequisites and the host image

Until each of these exists, the corresponding boundary **fails closed** and nothing weaker is substituted:

| Prerequisite (operator, outside Git)                                              | If missing                                                                 |
| --------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| AWS account, one region, a named operator identity with the `operator-deploy` policy | Nothing can be provisioned; the Terraform has no default region            |
| DNS zone for `divalhr.com`; ACM certificate (DNS-validated) for the three names    | No load-balancer listener; `certificate_arn` has no default                |
| Transactional e-mail provider, its SPF/DKIM/DMARC DNS, SMTP credentials            | `config/mail.env` absent: Core cannot start, **onboarding is refused**; mail leaves only through the SES SMTP VPC endpoint |
| Alarm destination `contact@dival.ai` confirms the SNS subscription                 | Alarms fire into an unconfirmed subscription: acceptance item fails        |
| A host image (AMI) with Docker Engine and the Compose plugin, AWS CLI v2, git, python3, curl, openssl, Node.js 24 (the repository engine line, >=24.21.0 <25), the SSM agent | Deployment stops at the first missing tool (final realm verification needs Node) |
| A build machine with Docker, the AWS CLI, skopeo, Node/Gradle for the Keycloak extension | Images cannot be built or mirrored; the host never builds                  |
| A full verification run of the exact release (`ops/hr-dev` rehearsal evidence)     | `deploy.sh` and `remote-deploy.sh` refuse: hr-dev evidence of another tree never counts |

## 3. Storage and the encrypted data volume

State lives on one encrypted EBS data volume mounted at `/srv/divalhr-prod`: `postgres/`, `keycloak/`, `caddy/`,
`secrets/`, `config/`, `state/`, `backups/`, `evidence/`, `releases/`. Every production script refuses to run unless that
path is its own mounted filesystem **and** the operator recorded that this very volume is encrypted
(`prepare-data-volume.sh --confirm-encrypted` stores the volume serial; the scripts compare it). After provisioning, the
operator confirms in AWS that the volume is encrypted with the environment key
(`aws ec2 describe-volumes --volume-ids <id> --query 'Volumes[].[Encrypted,KmsKeyId]'`), then confirms it on the host.
Nightly EBS snapshots (14 kept) come from the Data Lifecycle Manager; they are additional disaster-recovery protection, not
the application-consistent backup (section 9).

## 4. One-time setup sequence (each step separately approved)

Not part of LAUNCH-002. Listed so the first execution is mechanical and every step leaves evidence:

1. **Provision** (`infrastructure/production/terraform`, operator credentials): `terraform init/plan/apply` with the
   variables `region`, `ami_id`, `certificate_arn` and `email_submission_port` if not 587. Review the plan with the
   founder first. The launch-lock parameter is created `CLOSED`.
2. **DNS and certificate** (separately approved): validate the ACM certificate; point `divalhr.com`, `www` and `app` at
   the load balancer. `manage_dns` stays `false` unless the founder approves Route 53 management.
3. **Confirm the data volume**: `sudo ops/production/prepare-data-volume.sh --volume-id <vol-...> --format`, the AWS
   encryption check (section 3), then `--confirm-encrypted`.
4. **Host configuration** (root-only files in `/srv/divalhr-prod/config/`, written through Session Manager; one value
   per file, never in Git): `region`, `registry` (the private ECR host), `vpc-cidr` (the VPC range), `backup-bucket`.
5. **Secrets**: `ops/production/secrets-bootstrap.sh --region <region>` from the workstation creates every generated secret
   in Secrets Manager (never shown, never overwritten). The operator stores the e-mail provider's SMTP password as the
   secret `divalhr/production/mail/DIVALHR_MAIL_PASSWORD` from the provider's console.
6. **E-mail (Amazon SES)**: verify the sending identity and SPF/DKIM/DMARC, obtain SES production access, create the SMTP
   credentials. Write `config/mail.env` with `PROD_MAIL_HOST=email-smtp.<region>.amazonaws.com` (the VPC SMTP endpoint, private
   DNS; any other host is refused), `PROD_MAIL_PORT` (587 unless `email_submission_port` was changed), `PROD_MAIL_FROM`,
   `PROD_MAIL_USERNAME` (non-secret values). Until this file exists the deployment refuses.
7. **Images**: `ops/production/mirror-images.sh` (Caddy and PostgreSQL by digest), then
   `ops/production/build-push.sh <sha>` for the release.
8. **Verification evidence**: run the full verification of the exact release on the verification host (the
   `ops/hr-dev` rehearsal), keeping its checksummed run directory.
9. **First deployment**: `bash ops/production/deploy.sh <sha> --evidence-run <run dir> --approval <reference> --first-run`.
10. **Restore drill** (section 9): the first production deployment is **not accepted** until one complete drill passes.
11. **Acceptance**: `bash ops/production/accept.sh --evidence-dir <dir>` with the launch lock closed (section 12). It needs
    the synthetic-tenant inputs of [LAUNCH-002-ACCEPTANCE-TENANT](security/LAUNCH-002-ACCEPTANCE-TENANT.md) and stops before
    the browser journeys when any is missing. The run ends `PENDING_CLEANUP` and exits 3, never PASS: the operator then disables
    the synthetic first-administrator identity in the realm console and runs `accept.sh --evidence-dir <dir> --finalize`,
    which verifies it read-only and re-checks the launch lock; only then is `result=PASS` written.

## 5. Deploying a release

`ops/production/deploy.sh` runs on the operator's workstation and fails closed in this order: the SHA is a full 40-character
commit reachable from `origin/main`; every GitHub check on it is green and every required check
(`ops/hr-dev/required-checks.txt`) ran (`provenance.py`); a qualifying, checksummed, read-only **full** verification of
exactly this tree exists and finished within `PROD_EVIDENCE_MAX_AGE_HOURS` (72); the four images of the SHA exist in the
private registry; and the operator gives the founder's written approval reference **and** types
`DEPLOY PRODUCTION <first 12 characters>` at a terminal (never non-interactive). Only then is the bundle (whose only head
is the SHA) uploaded to the evidence bucket and run on the host through Systems Manager (no SSH).

`remote-deploy.sh` then re-checks the bundle head and the evidence on the host, takes the **host-wide lock**, unpacks the
release with `git archive` (never a working tree), refuses without the e-mail provider configuration, materialises secrets,
pulls the images, takes a **pre-deploy backup** (which includes the off-host copy), starts the stack and runs: Keycloak
operator setup (replaces the bootstrap administrator and removes its password), the Core to AI client, **final-mode realm
verification**, HTTP checks, the OIDC back-channel check, the AI capability check and the timer installation. If anything
fails, `rollback.sh` restores the previous release **and both databases** from the pre-deploy dumps. Evidence (redacted)
is kept in `.git/divalhr-deploy-production/<run>/` and uploaded under `evidence/<run>/`. Deploying never lifts the launch
lock; it records the lock state in `state/last-deploy`.

## 6. Secrets and rotation

Source of truth: AWS Secrets Manager under `divalhr/production/`, encrypted with the environment key. On the host a secret
exists only as a root-controlled file under `/srv/divalhr-prod/secrets` (0400, owned by the container user that reads it),
mounted read-only. No secret is an environment variable, a Compose value, a CI value, an image layer, evidence, chat or shell
history. Production values are generated fresh and are never copied from hr-dev. Backups contain only the two dumps, their
checksums and a manifest; the secret store is never part of a backup. The operator identity can create secrets but can
never read one; only the host role reads them.

| Rotation class                              | Secrets                                                                                       | Procedure                                                                                     |
| ------------------------------------------- | --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| Rotatable with a restart                    | Database role passwords, provisioner secret, AI client secret, mail password, cursor signing key | Create the new value as a new secret version, update the dependent (`ALTER ROLE`, Keycloak client credential, provider console), run `init-secrets.sh --refresh` for the names rotated inside a maintenance window, restart the affected services, verify with the acceptance checks |
| Rotatable with partner coordination         | Messaging verify token and app secret                                                         | Coordinate with the provider; rotate in both places; verify the callback                        |
| **Not rotatable in place** (stored data is sealed or keyed with them) | E-mail lookup key; webhook, messaging and payment encryption keys; payment-submission and partner handoff/callback signing keys | A change needs a data migration designed and approved for the purpose. Treat loss as an incident. |

A suspected exposure of any secret is a SEV-1 incident (incident runbook): rotate immediately where possible, and record the
decision for the non-rotatable ones. `init-secrets.sh` refuses to overwrite a differing file without `--refresh`.

## 7. Operator access

There is **no SSH**: no inbound rule, no key pair. Administration is Session Manager (shell and port-forwarding) and Run
Command, both authenticated by the operator's AWS identity and audited by CloudTrail and the session logs. The Keycloak
administration console is reachable only at `http://localhost:18180/identity/admin/` on the host through a Session Manager
port-forwarding session; the public names return 404 for it. The permanent Keycloak operator (`divalhr-operator`) is created
by the first deployment; its password is a root-only file. The break-glass path for WAF rate rules is the time-boxed
`emergency_allow_cidrs` variable (empty by default, never a permanent bypass): set it for the incident, then clear it.

## 8. Rollback and recovery

**Automatic.** A release that fails any post-start check is rolled back by `rollback.sh`: the application containers stop,
**both** pre-deploy dumps (Core and Keycloak) are restored into empty databases, and the previous release starts and must pass
the HTTP and back-channel checks. Without a pre-deploy dump for the failed release the rollback refuses and changes nothing.
Data written after that dump is discarded; this is intended for a release that failed within minutes of starting, while the
launch lock is closed or before customers write data. After onboarding opens, a rollback is a founder decision (data loss
window) and the incident runbook says how to decide.

**Manual recovery from a dump** (after a failed rollback, host loss or corruption): with the host lock held, stop the
application, `hr_restore_backup` the chosen backup directory (nightly local, or downloaded from
`s3://<backup bucket>/backups/nightly/<stamp>/`; checksums are verified first), start the release recorded in the backup's
MANIFEST, run `http-checks.sh`, `backchannel-check.sh` and `realm-verify.sh`.

**Host loss.** Provision a new host from the same Terraform (the data volume is re-created empty), restore configuration,
re-materialise secrets from Secrets Manager (`init-secrets.sh --first-run`), pull the images, restore the newest verified
off-host dumps, deploy the same release. The EBS snapshots can shorten this if the volume itself survives. The restore drill
measures the dump-restore part of this path; the rest is bounded by provisioning time and is why the target is 8 hours.

## 9. Backups and the restore drill

`ops/production/backup.sh` runs nightly at 02:15 UTC (timer) and before every deployment. It writes `pg_dump -Fc` of
`divalhr` and `keycloak`, a `SHA256SUMS` and a `MANIFEST` (release, creation time, Flyway entry count), copies them to the
versioned, KMS-encrypted backup bucket under `backups/<reason>/<stamp>/` and **verifies the off-host copy by listing it**.
A backup counts as successful (and resets the age alarm) only after that verification. The host keeps 7 nightly and 3
pre-deploy sets; the bucket keeps overwritten versions for 35 days. These are engineering settings pending legal review of
retention, not retention promises, and no purge runs.

**Restore drill** (`sudo ops/production/restore-drill.sh`): downloads the newest nightly set from the off-host bucket and
verifies its checksums, restores both dumps into a fresh, isolated Compose project (`divalhr-prod-drill`, its own networks,
loopback-only ports, fresh storage, a copy of the secrets, **no outbound e-mail** because its mail host is an unresolvable
name), starts the release, and proves: Flyway validated the restored history and the entry count equals the manifest; the
restored migration set equals the running release's; the production realm exists; the HTTP and back-channel checks pass; and
production answered healthy every five seconds throughout. It writes `state/last-restore-drill` with the measured restore
and recovery seconds. Record every drill with the
[evidence template](security/LAUNCH-002-RESTORE-DRILL-TEMPLATE.md). Cadence: once before first acceptance, then **quarterly**;
`RestoreDrillAgeDays` alarms above 100 days. A drill needs spare memory and disk on the single host and should run in a quiet
window.

## 10. Monitoring and alarms

Alarms do not depend on the host being healthy. They notify the SNS topic subscribed by `contact@dival.ai` (the operator
confirms the subscription; the acceptance fails otherwise).

| Signal                                  | Source                                      | Alarm                                                         |
| --------------------------------------- | ------------------------------------------- | ------------------------------------------------------------- |
| Public availability, certificate expiry | Route 53 health check, ACM `DaysToExpiry`    | `external-https`, `certificate-expiry`; also `host-status-check` (EC2) |
| Target 5xx, unhealthy host, WAF blocks  | ALB and WAF metrics                          | `alb-5xx`, `unhealthy-host`, `waf-block-rate`          |
| Host stopped reporting                  | `HostHeartbeat` (missing data is breaching)  | `heartbeat`                                                   |
| Disk, memory, CPU, containers           | `DiskUsedPercent`, `MemoryUsedPercent`, EC2 CPU, `UnhealthyContainers` | `disk-used-percent` (80%), `memory-used-percent` (90%), CPU (85%), `unhealthy-containers` |
| Backup age, drill age                   | `BackupAgeHours`, `RestoreDrillAgeDays`      | `backup-age-hours` (26 h), `restore-drill-age` (100 days)     |
| Deploy, watchdog                        | `DeployFailed`, `WatchdogRestarts`           | `deploy-failed`, `watchdog-restarts`                          |
| Application                             | `Application5xx`, `IdentityFailures`, `WorkerFailures` | `application-5xx`, `identity-failures`, `worker-failures` |

`metrics.sh` publishes the host metrics every five minutes in the namespace `Divalhr/Production` with **no dimensions**: no
tenant, employee, e-mail, partner, token, code or free text can become a label. Counts come from container state, file ages
and the number of matching log lines; no log content is read into a metric. Every request carries `X-Request-Id`
(generated at the edge, never taken from the client) so an edge record joins an application log line; no body, header value or
query string is logged. The ALB access log is intentionally off because OIDC query strings would land in it. Severities,
response times and communication templates are in the [incident runbook](security/LAUNCH-002-INCIDENT-RUNBOOK.md).
`log-hygiene.sh --since <time>` proves the logs hold no secret, token, cookie, OIDC code or credential shape (counts only).

## 11. Capacity and performance baseline

Baseline notes, not a performance promise. Provisioned: `m6i.large` (2 vCPU, 8 GiB), a 40 GiB root and a 100 GiB data volume
(gp3). The hr-dev environment runs the same containers on a comparable single host and passed its rehearsals with the synthetic
suites; no production load exists yet. Expectations to verify during acceptance, not assumptions: the Keycloak and Core JVMs
dominate memory; PostgreSQL holds both databases; the restore drill and a deployment briefly need roughly double the
application memory (two stacks), so they are scheduled away from peak use. The commercial pages ship the budgets already
measured for LAUNCH-004 (`COMMERCIAL-PERFORMANCE-REPORT.md`). **Before opening onboarding (LAUNCH-003 territory)** the
operator records, from the acceptance run and the metrics: memory and CPU headroom at idle and during a deployment, the
database size after the synthetic tenant, backup duration and size, and the measured drill times. The 80% disk and 90% memory
alarms and the single-volume layout are the early warnings; the scale path is in ADR 0048 section 5.

## 12. Launch lock and customer onboarding

The **launch lock** is closed by default and fails closed: `state/launch-lock` reads `OPEN` only for that exact word, any
missing, empty or other content is `CLOSED`. The authoritative value is the SSM parameter
`/divalhr/production/launch-lock`, created `CLOSED` with later changes ignored by Terraform; the host mirrors it read-only
every five minutes (`launch-lock.sh sync`). Only the unattached `launch-lock-lift` IAM policy can change the parameter, and
**no script in this repository lifts the lock**. While it is closed: no real organization, no real employee invitation, no
real data import, no statutory pack, no retention policy, no real provider or partner egress, no real payment, credit,
insurance or telecom workflow. `launch-lock.sh status` prints that state, and reports the e-mail prerequisite
(`NOT_CONFIGURED` until `config/mail.env` exists; onboarding stays refused regardless of the lock).

The synthetic acceptance tenant is the **only** tenant allowed while the lock is closed. It is created through the normal
invitation flow, never seeded, with a synthetic mailbox the operator controls. `accept.sh` runs only while the lock is closed
and leaves its own evidence; lifting is a recorded founder authorization after LAUNCH-003 and is not part of LAUNCH-002.
Use the [onboarding-lock checklist](security/LAUNCH-002-READINESS-CHECKLIST.md) before any lift.

## 13. Known limitations, costs and out of scope

Single host and zone (a failure is a recovery of up to the 8 h target, not a failover); database on the host; short
maintenance windows at deployments; no blue/green; the load balancer to host hop is TLS to an internal-CA certificate the
load balancer does not validate, inside private subnets; images and the Keycloak extension are built off-host; the first
drill uses the same host as production. **Cost drivers** (an unverified planning estimate of roughly USD 200 to 300 per
month in a US region; produce a real estimate with the AWS Pricing Calculator before provisioning): the EC2 host, the load
balancer, WAF, nine interface VPC endpoints (the largest fixed item after the host), EBS and snapshots, Secrets Manager,
CloudWatch alarms and logs, the Route 53 health check, S3 and ECR storage, and the e-mail provider. **Out of scope**:
customer onboarding, retention activation, statutory packs, real payment or partner integrations, native apps, AI
decisions, multi-region, SLA or certification, LAUNCH-003.
