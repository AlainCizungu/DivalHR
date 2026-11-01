# Test environment: hr-dev.dival.ai (OPS-001)

**TEST ENVIRONMENT. Synthetic data only.** `https://hr-dev.dival.ai` runs one released commit of `main` on the DivalHR AWS instance for demonstrations and acceptance. It is not production: no customer data, no real employees, no real payroll. Issue #65 holds the approved proposal (decisions D1 to D13) and the architect's amendments A65-1 to A65-7.

Everything below is done by the owner. Claude prepares and reviews; it never changes AWS, the security group, volumes or the running host.

## 1. Shape

```
Internet ── 80/443 ──► Caddy (edge) ──► web            (/)
                                   ├──► Core API       (/api/*)
                                   ├──► AI service     (/ai/api/v1/system/*)
                                   └──► Keycloak       (/identity/realms/divalhr-test/*, /identity/resources/*, /identity/js/*)
Core API ── idp-internal:8081 ──► Caddy (private route) ──► Keycloak   (token, certs, divalhr-provisioning only)
Owner ── SSH tunnel ── 127.0.0.1:18180 ──► Caddy (operator site) ──► Keycloak admin console, Mailpit
```

| Item | Value |
| --- | --- |
| Compose project | `divalhr-test` (`infrastructure/hr-dev/compose.yaml`), images `divalhr-test/<service>:<full SHA>` |
| Networks | `edge` 10.71.0.0/24 (Caddy only), internal `app` .1, `idp` .2, `core-data` .3, `idp-data` .4, `mail` .5 |
| Published | Caddy 80 and 443 on all addresses; Caddy 18180 on `127.0.0.1` only. Nothing else. |
| Public issuer | `https://hr-dev.dival.ai/identity/realms/divalhr-test` (`sslRequired: all`) |
| Keycloak admin URL | `http://localhost:18180/identity` (`KC_HOSTNAME_ADMIN`, master realm `frontendUrl`) through the SSH tunnel |
| Refused publicly (404, noindex) | `/identity/admin*`, master realm, account console, health, metrics, `divalhr-provisioning`, actuator, `/ai/*` except status |
| Headers | `X-Robots-Tag: noindex, nofollow, noarchive` on every response; `Strict-Transport-Security: max-age=86400` (one day, no subdomains, no preload, D10); `robots.txt` disallows everything |
| Mail | Mailpit catches every message (nothing leaves the instance); UI at `http://localhost:18180/mail/` through the tunnel |
| Environment | Core `DIVALHR_ENVIRONMENT=test`, web badge "Test environment" / « Environnement de test » |

Keycloak trusts `X-Forwarded-*` only from Caddy's address on the `idp` network (`KC_PROXY_TRUSTED_ADDRESSES`), and only Caddy can reach Keycloak (A65-2). Caddy overwrites `X-Forwarded-Proto`, `Host` and `X-Forwarded-Port` on the public and private routes and strips `Forwarded`. Access logs drop query strings and delete the `Authorization`, `Cookie`, `Referer`, response `Location` and `Set-Cookie` fields (SEC-001).

## 2. Storage (A65-1)

All state lives on a dedicated encrypted gp3 EBS volume mounted at `/srv/divalhr-test` (bind mounts only; no Docker named volumes, which would land on the unencrypted root disk):

| Path | Content | Owner |
| --- | --- | --- |
| `postgres/` | PostgreSQL 17 data (`divalhr`, `keycloak`) | 999 |
| `secrets/postgres`, `secrets/core`, `secrets/keycloak`, `secrets/ops` | generated secrets, one file each (section 3) | container user / root |
| `caddy/data`, `caddy/config` | certificates and Caddy state | root |
| `mailpit/` | caught mail | root |
| `keycloak/import/` | rendered test realm (first start only) | 1000 |
| `backups/nightly`, `backups/pre-deploy` | logical dumps with SHA-256 sums and manifest | root, 0700 |
| `releases/<sha>`, `current` | unpacked releases, symlink to the running one | root |
| `state/` | current, previous and failed release, last rollback, last restore drill, watchdog counters | root |
| `.volume-encryption-verified` | the confirmed volume's serial | root |

Every operation refuses to run unless `/srv/divalhr-test` is a separate mount whose volume serial matches the confirmation marker. EBS encryption by default is enabled for the region; a Data Lifecycle Manager policy (`ops/hr-dev/aws/dlm-policy.json`) snapshots volumes tagged `divalhr-backup=hr-dev-data` daily at 03:00 UTC and keeps 7. Snapshots of an encrypted volume are encrypted. **The nightly logical dumps (02:17 UTC) are the authoritative backup; EBS snapshots are additional disaster recovery.** The root volume stays unencrypted and holds only the operating system, Docker images and container logs (which contain no personal data, section 8).

## 3. Secrets

`ops/hr-dev/init-secrets.sh` generates every value once with `openssl` on the instance and never prints it: PostgreSQL superuser and the two application roles, Core's cursor-signing and e-mail-lookup keys, the provisioner client secret and Keycloak's `keycloak.conf` (database password and one-time bootstrap administrator password). Files are `0400`, owned by the one container user that reads them; directories `0500`. Core reads its secrets through Spring's config tree (`/run/secrets/core`), PostgreSQL through `*_FILE`, Keycloak through `keycloak.conf`. **No secret is passed as an environment variable**, so none is stored in a container's configuration. The realm template contains placeholders only; `render-realm.py` writes the rendered copy (`0400`).

After the first start, `keycloak-admin-setup.sh` creates the permanent master-realm operator `divalhr-operator` (password in `secrets/ops/kc-admin.env`, root `0600`), sets the master realm's `frontendUrl` to the admin URL, deletes the bootstrap administrator and removes its username and password from `keycloak.conf`. Read the operator password on the instance with `sudo cat` when needed; never paste it into chat, issues or files.

Do not rotate `DIVALHR_EMAIL_LOOKUP_KEY` in place (stored invitation lookups are keyed with it).

## 4. Host lock (A65-5)

`/run/lock/divalhr-host.lock`, owner `root`, group `divalhr-ops`, mode `0660`, recreated at boot by systemd-tmpfiles (`ops/host/tmpfiles-divalhr.conf`, installed by `bash ops/aws/aws-dev-setup.sh host-lock`). The `ubuntu` user is in `divalhr-ops`. Every aws-verify run (for its whole duration), deploy, rollback, backup, restore drill, secret initialisation, admin setup, evidence collection and watchdog restart takes it with `flock -n`. A second operation stops at once with exit status **75** and a message naming the holder, before changing anything. The watchdog skips its whole run while the lock is busy.

## 5. One-time setup (the approved deployment)

Run in this order, posting each redacted output on Issue #65:

1. Mac: `bash ops/aws/hr-dev-aws.sh evidence` (baseline; read-only).
2. Mac: `bash ops/aws/hr-dev-aws.sh encryption-default --yes`, then `data-volume --yes` (20 GiB gp3, encrypted, tagged, attached), then `snapshots --yes` (DLM policy).
3. Mac: `bash ops/aws/aws-dev-setup.sh host-lock`.
4. Instance: `sudo ops/hr-dev/prepare-data-volume.sh --volume-id <vol-…> --format` (from a checkout of the release; the volume ID printed by `data-volume`, never posted). The disk is identified by that ID (its NVMe serial), whether found automatically or named with `--device`, and must be a whole EBS disk that is not the root disk, backs no mount or swap, and has no partition, holder or signature; only the tool's own ext4 filesystem (label `divalhr-test`) may be reused. fstab by UUID with `nofail`, then mounted (R66-1).
5. Mac: `bash ops/aws/hr-dev-aws.sh evidence` must show `PASS data volume is encrypted`. Instance: `sudo ops/hr-dev/prepare-data-volume.sh --confirm-encrypted`.
6. Instance: the ACME contact (D13): `sudo install -d -m 0700 /srv/divalhr-test/config` and write a monitored address to `/srv/divalhr-test/config/acme-email`.
7. Mac: `bash ops/aws/hr-dev-aws.sh open-web --yes` (TCP 80 and 443, IPv4 and IPv6, needed for the certificate). The ports go into a dedicated security group (tag `divalhr-sg=hr-dev-web`) added to this instance's interface; the existing group is never edited, and the command refuses if the dedicated group is attached to anything else (R66-3).
8. Mac: `bash ops/hr-dev/deploy.sh <full sha> --first-run`.
9. Evidence: `bash ops/aws/hr-dev-aws.sh evidence`, the **hr-dev exposure** workflow (Actions, run manually), `sudo /srv/divalhr-test/current/ops/hr-dev/evidence-host.sh`, and the inventory diff that deploy.sh saved.

## 6. Deploying a release (A65-7)

```
bash ops/hr-dev/deploy.sh <full 40-character SHA of a commit on main>
```

It fails closed unless the SHA is reachable from `origin/main`; every GitHub check run on the SHA is green and every check in `ops/hr-dev/required-checks.txt` succeeded (`provenance.py`); and the instance holds **qualifying verification evidence** (DEVX-001B, below). It ships a bundle whose only head is the SHA; the instance unpacks it with `git archive` into `releases/<sha>`, builds `divalhr-test/*:<sha>`, takes a **pre-deploy dump** of both databases, starts the release, runs the admin setup, `http-checks.sh` and `backchannel-check.sh`, and installs the timers. The release is recorded as current only after all of these succeed, timer installation included; `install-units.sh` restores the previous unit files and enablement when it fails (R66-4). A failure triggers the automatic rollback (section 9). The Mac keeps redacted evidence in `.git/divalhr-deploy/<run>/`: provenance decisions, inventory before and after with their diff, the remote log and the host evidence.

**Verification evidence and its reuse (DEVX-001B, Issue #75, A75-6 and A75B-2 to A75B-6).** `remote-deploy.sh` runs `ops/hr-dev/evidence.py decide` from the release being deployed. It considers every run directory in `~ubuntu/divalhr-runs`. A run qualifies only if **all** of these hold:

1. Its record is intact:
   - `evidence.json` and `evidence.sha256` are regular files, mode `0444`, and the checksum matches;
   - the record's run ID is its directory, and the directory names the record's commit;
   - `exit` is `0`;
   - the profile is `pr` or `full` (`all` is recorded as `full`). `changed`, `core`, `stack`, `hrdev` and any diagnostic override never qualify.
2. Its retained `verify.bundle` is unchanged: it hashes to the recorded value and has the record's full commit SHA as its single head.
3. The tree of that commit, recomputed from that bundle, equals the tree of the release, recomputed from the release bundle.
4. Its stages include `PASS core-check`, `PASS e2e` and `PASS hrdev-rehearsal`, and the record is complete (R79-2):
   - valid `startedAt` and `finishedAt`;
   - timings for `core-check`, `e2e` and `hrdev-rehearsal`;
   - a classification with its reasons;
   - more than zero Core tests, with no failures or errors;
   - more than zero passed browser tests, with none failed, flaky, retried or not run;
   - a passed credential-hygiene check.
5. Every rehearsal step that the **release's own class** requires is recorded as `PASS`:
   - the class is recomputed from the deployed release to the release SHA, never taken from the record;
   - a docs-only release still requires the `app` steps, since there is no docs-only bypass;
   - a first deployment is `complete`.

The newest qualifying run is used. Otherwise the deployment stops and names the verification to run on the release itself (A75B-6):
- `aws-verify.sh <sha> pr` for a release classified `app`, `migration`, `identity` or `migration+identity`;
- `aws-verify.sh <sha> full` for everything else.

The common case therefore needs no second run after merge: the merge commit of an approved PR has the same tree as the verified head, and that head's record qualifies.

**What the records are.** They are checksummed and read-only, not cryptographically immutable. They detect accidental change, truncation, failed or incomplete runs and mismatched code. They **trust the controlled host and its operator**: anyone who can write to `~ubuntu/divalhr-runs` as `ubuntu` or root can forge one. They are not protection against a compromised host. Production, when it exists, requires its own fresh complete qualification.

## 7. Operator access

```
ssh -i ~/.ssh/divalhr-dev.pem -L 18180:127.0.0.1:18180 ubuntu@hr-dev.dival.ai
```

Then `http://localhost:18180/identity/admin/master/console/` (Keycloak, as `divalhr-operator`) and `http://localhost:18180/mail/` (Mailpit). Synthetic users are created through the product's invitation flow, never seeded. Platform and organization administrators must enrol an authenticator on first sign-in (MVP-011).

## 8. Health and monitoring (A65-3, D9)

* Docker's restart policy (`unless-stopped`) acts only when a container **exits**.
* `divalhr-hrdev-watchdog.timer` (every 60 s) runs `watchdog.sh`, the only manager of **running but unhealthy** containers: restart after 3 consecutive unhealthy observations, at least 10 minutes between restarts of a service, at most 3 per service per hour, then "operator attention required" and no further action. It never touches exited or restarting containers.
* `divalhr-hrdev-backup.timer` runs the nightly dump at 02:17 UTC.
* Local monitoring: `journalctl -u divalhr-hrdev-watchdog -u divalhr-hrdev-backup`, `systemctl list-timers 'divalhr-hrdev-*'`, `docker ps`, `sudo /srv/divalhr-test/current/ops/hr-dev/http-checks.sh`.
* Logs are Docker `json-file`, 10 MB × 5 per container. Applications log no tokens, credentials or personal data; Caddy drops query strings and deletes the credential, referrer, `Idempotency-Key` (SEC-002), receipt file name, disposition, entity-tag, digest and webhook-signature (SEC-003) and redirect-target fields; the web container's nginx logs method, path without query, protocol, status, size and time only (SEC-001).
* Log hygiene (SEC-001): `sudo /srv/divalhr-test/current/ops/hr-dev/log-hygiene.sh --since <UTC time>` checks every service's log since that time for the secret values under `secrets/` and for token, cookie, code, credential and `Idempotency-Key` header-value shapes, printing counts only. The rehearsal and the drill run it after their browser suites; run it after the live acceptance of a deployment. Never truncate an active Docker log file: recreating a container removes its old log files.

## 9. Backup, restore and rollback (A65-6)

* `backup.sh --reason nightly|pre-deploy`: `pg_dump -Fc --no-owner --no-privileges` of `divalhr` and `keycloak`, SHA-256 sums, a manifest with the release and the Flyway history count. Keeps 7 nightly and 3 pre-deploy.
* `restore-drill.sh`: restores the newest dump into a **separate** project (`divalhr-test-drill`), networks (10.72), loopback ports (29080/29443/29180), Caddy's internal CA and fresh storage under `drill/<stamp>`, never the live `postgres/`. Both dumps go into empty databases (checksums, `DROP … WITH (FORCE)`, owners, `btree_gist`, `pg_restore --role=<owner>` without the extension entries). Core must start (Flyway validates) with the same history count; the realm must exist; HTTP and back-channel checks run against the drill; the live stack is probed every 5 s throughout and any failure fails the drill. Times are measured and written to `state/last-restore-drill`. The drill is removed afterwards.
* `rollback.sh --to <previous sha> [--from <sha>]`: always stops the application containers, restores **both** pre-deploy dumps of the failed release (Core and Keycloak) into empty databases, and only then starts the target's images (R66-2: a newer Keycloak image migrates its own database, which Core's Flyway history does not show). Data written by the failed release is discarded. Without pre-deploy dumps for that release it refuses and changes nothing. `state/last-rollback` records the measured times. A rollback that fails its checks stops for manual recovery.

Manual recovery: stop the application (`docker stop divalhr-test-core-api-1 divalhr-test-keycloak-1 divalhr-test-ai-service-1 divalhr-test-web-1`), restore a chosen backup with the functions in `lib.sh` (`hr_restore_backup <running sha> <backup dir>`) under the host lock, start the release whose migrations match, and run `http-checks.sh`. If the volume itself is lost, create a volume from the newest DLM snapshot, attach it, mount it and restore the newest dump.

## 10. Verification and rehearsal

Every complete `aws-verify` (`bash ops/aws/aws-verify.sh <ref> all`) ends with the stage `hrdev`: `pnpm ops:test` and `ops/hr-dev/rehearse.sh`, which deploys the commit into the throw-away project `divalhr-rehearsal` (10.73, `127.0.0.1:28080/28443/28180`, internal CA, `/var/tmp`), then runs the browser acceptance suite (`apps/web/e2e/hr-dev`: landing EN/FR, discovery and keys, authorization code with PKCE, callback, refresh, logout, the web app round trip, the sign-in redirect with the web app's service worker installed (the rest of the suite blocks service workers), a privileged first sign-in with password and authenticator from the setup e-mail, the admin console through the admin URL, identity assets), `verify-realm.mjs` in final mode, a backup, the host-lock concurrency test, a watchdog failure injection (Core's process stopped with SIGSTOP), the isolated restore drill with the browser suite on the restored stack, a first deployment and an upgrade whose timer installation fails (nothing recorded as current; the upgrade returns to the previous release), and rollbacks of failing releases: one that changes only the identity database (the pre-deploy Keycloak dump must be restored) and one that adds a Core migration. It removes everything afterwards. The same browser suite runs against the live environment with `HR_DEV_BASE_URL=https://hr-dev.dival.ai`, the tunnel's admin URL and, for the employee smoke test, `HR_DEV_EMPLOYEE_USER` / `HR_DEV_EMPLOYEE_PASSWORD` of a synthetic employee.

**Risk classes and records (DEVX-001B, A75-5, A75B-1 to A75B-5).** `ops/hr-dev/evidence.py` is the single source of the classes and of the rehearsal step matrix.

`classify` reads `git diff -z --name-only --no-renames` (NUL-delimited, both sides of renames and deletions, no shell). A path takes the first matching group, in this order:
- `complete`: Dockerfiles, Compose files, `.github/`, `ops/`, `scripts/`, deployment infrastructure, build, dependency and toolchain files, web server and build configuration, the hr-dev suite;
- `identity`: the realms, the provisioning extension, Core `identity/`, `platform/security/`, `platform/tenancy/`, `AuthenticatedCaller.java` and `application*.yaml`, and the web `auth/` and `pwa/` code;
- `migration`: Core `db/`;
- `app`: application code, tests and shared packages;
- `docs-only`: documentation.

Anything unmatched, empty, unreadable, or not descended from the deployed release is `complete`.

The `pr` profile classifies both the PR's own diff and the deployed release to the candidate, and takes the broader class. The base can only widen the class. The deployed release is read through `sudo -n ops/hr-dev/current-release.sh`.

What `pr` runs per class:

| Class | Checks and rehearsal steps |
| --- | --- |
| `docs-only` | documentation checks only, no stack |
| `app` | Core, stack, `deploy-first`, `browser-suite`, `realm-verify-final` |
| `migration` | the `app` steps plus `backup`, `restore-drill`, `rollback-migration` |
| `identity` | the `app` steps plus `rollback-identity-change` |
| `complete` | every step |

`full` always runs `complete`. Every step a class does not require appears as `SKIP <step> (class <c>)`. There is no environment override.

Every finished run, failed or not, gets a record:
- `evidence.json`: profile, stage, commit, tree, base, bundle hash, `startedAt` and `finishedAt`, stage timings (canonical names and seconds, never commands), the classification and its reasons, the recorded class, step and stage results, Core totals and browser totals (passed, skipped, failed, flaky, not run, retried, hygiene). It is written through a temporary file, `fsync` and `rename`, then made `0444`.
- `evidence.sha256`: the record's checksum.

`ops/hr-dev/finish-run.sh` writes the record and then the exit file. If the record cannot be written, the run **fails**: `FAIL evidence-record` goes to the summary and the log, and the exit file holds a nonzero status (R79-1). An interrupted run has no record and never qualifies. Paths that are not valid UTF-8 make the classification `complete` (R79-3). The current release counts only when the whole state file, trimmed, is exactly one SHA (R79-4).

**Verification profiles (DEVX-001A, Issue #75).** `aws-verify.sh <ref> <profile>` accepts `changed` (only what the diff from the merge-base with `main` needs, widening to `pr` or `full` when a path requires it), `pr` (Core checks and the full stack with the whole browser suite) and `full` (the complete run above). `full` is recorded as stage `all`; the older stages `all`, `core`, `stack` and `hrdev` keep working. Which runs qualify a release is described in section 6 (DEVX-001B); a `changed` run never does. The browser suite of the stack stage runs `apps/web/e2e/run-suite.sh`: `features` on two workers reusing one MFA sign-in per privileged seed role, then the `identity` tests alone, then a credential-hygiene check; both projects are required. Each run writes `<stage>.timings` (one `TIME <phase> <s>s` line per phase) next to its logs.

## 11. Known limitations

* One instance, one availability zone; no high availability. Recovery point: the last nightly dump (and the last daily snapshot).
* The root volume is unencrypted (operating system, images, logs).
* Keycloak and the identity origin share `hr-dev.dival.ai`; a dedicated identity origin remains a production requirement (D1).
* The operator account has no second factor of its own; it is reachable only through SSH from the owner's address.
* Certificates come from Let's Encrypt for `hr-dev.dival.ai` only.

## MVP-101 daily briefing in the test environment (R157-1)

The deterministic briefing is enabled on hr-dev. No external model provider, provider URL or API
key exists in the environment: the AI service runs its deterministic planner only
(`DIVALHR_AI_SUMMARY_PROVIDER_MODE=deterministic`).

| Piece | Where |
| --- | --- |
| Keycloak client `divalhr-core-ai` (client credentials only, no browser flow, restricted scope, 120 s tokens, audience `divalhr-ai-service`, one capability role `generate-operational-summary`) | created and reconciled on every deployment by `keycloak-ai-client.sh` / `keycloak_ai_client.py` through the operator site (the realm is imported on the first start only, so an existing environment gains it here) |
| Client secret | generated once by `init-secrets.sh`: `secrets/ops/ai-service-client-secret` (root, 0400) and `secrets/core/DIVALHR_AI_SERVICE_CLIENT_SECRET` (Core's uid, 0400, read through the configtree). Never an environment value, never printed, never in the repository or an image; the AI service and the browser never hold it |
| Core -> Keycloak | `http://idp-internal:8081/.../token`, Caddy's internal allow-listed route (already permitted); the issuer is the public one |
| Core -> AI service | `http://ai-service:8090` on the private app network only |
| AI service authentication | issuer `${HR_DEV_ORIGIN}/identity/realms/divalhr-test`, keys from the internal route; signature, issuer, audience, authorized party and capability role are checked before the body is read |
| Public exposure | unchanged: Caddy forwards only `/ai/api/v1/system/*`; `/ai/api/v1/internal/operational-summary` is a 404 (added to `http-checks.sh`) |

`remote-deploy.sh` provisions the client right after `keycloak-admin-setup.sh` and, after the
existing checks, runs `ai-capability-check.sh`: inside the AI service container it obtains a real
`divalhr-core-ai` token and verifies its issuer, audience, authorized party, single role and
lifetime, that the internal operation refuses a request without a token, and that it answers a
synthetic all-zero snapshot with the deterministic briefing (`FALLBACK`). A failure rolls the
release back like any other failed check. An existing environment gets its new secret from
`init-secrets.sh` automatically at the next deployment (existing files are never overwritten).

Rotation: delete `secrets/ops/ai-service-client-secret` and `secrets/core/DIVALHR_AI_SERVICE_CLIENT_SECRET`,
run `init-secrets.sh`, then redeploy; `keycloak-ai-client.sh` writes the new value into Keycloak and
Core restarts with it. Disabling the capability: remove the Core secret file (Core then answers
`503 OPERATIONAL_SUMMARY_UNAVAILABLE` and sends nothing) and disable the client in Keycloak.

## MVP-110 outbound webhooks in the test environment

- **Key.** `init-secrets.sh` generates `DIVALHR_WEBHOOK_ENCRYPTION_KEY` once as a root-only file read by Core through the configtree. Existing files are never overwritten. **Never rotate or delete it** once webhooks exist: stored secrets become unreadable (recreate or rotate each webhook). Include the file in the secrets backup.
- **Receiver.** A synthetic HTTPS receiver (`infrastructure/docker/webhook-receiver`) runs on a new `internal: true` network `hooks` shared with Core only. The receiver is reached as `receiver.hooks.test` (a network alias on `hooks`; customer URLs must be dotted host names, and the certificate names that host). Core's receiver exception (`DIVALHR_WEBHOOK_TEST_RECEIVER_HOST`, `_PORT`, `_CA_FILE`) names that one host and port, with its throwaway CA as the only extra trust. No other private destination is allowed. Read what it received with `docker compose exec webhook-receiver wget -qO- http://127.0.0.1:8099/control/received` (`RECEIVER_CONTROL_PORT`); the control listener is never routed publicly.
- **Egress gap.** hr-dev has no general outbound internet egress, so customer endpoints on the internet cannot be reached there. This is a production-readiness gap, not part of MVP-110; `internal: true` networking is not weakened.
- **Rollback.** `db/rollback/V39__rollback.sql` refuses while webhooks, deliveries or attempts exist. To return to a pre-V39 release, restore the pre-deploy database backup (section 9) instead.

## MVP-080 operational tasks

- **Migration.** V41 adds `operations.task` and `operations.task_action`. No configuration, secret or external dependency; no notifications are sent.
- **Rollback.** `db/rollback/V41__rollback.sql` refuses while any task or action exists and has no override. To return to a pre-V41 release once tasks exist, roll back the application only (V41 changed no earlier object) or restore the pre-deploy database backup. Removing the V41 row from `flyway_schema_history` is a separate, controlled operator step taken only with the application rollback.

## MVP-081 employee expenses

- **Migration.** V42 adds `operations.expense`, `expense_revision`, `expense_receipt` and `expense_action`. No configuration, secret or external dependency; no notification is sent.
- **Rollback.** `db/rollback/V42__rollback.sql` refuses while any expense, revision, receipt or action exists and has no override. Once expenses exist, roll back the application only (V42 changed no earlier object) or restore the pre-deploy backup; removing the V42 row from `flyway_schema_history` is a separate operator step taken only with the application rollback.
- **Receipt storage (pilot only).** Receipts (at most 2 MiB each, 100 open expenses per employee, 5,000 per tenant) are stored in PostgreSQL on the encrypted data volume and included in database backups. This is a pilot decision. **Follow-up before production:** move receipt content to private, encrypted object storage behind the same repository port, with retention and malware-scanning decisions; no public URLs and no new infrastructure were added by MVP-081.
- **Limits.** Hourly quotas of 60 writes and 30 receipt changes per employee, 120 decisions per manager; burst buckets `DIVALHR_EXPENSE_*_REQUESTS_PER_MINUTE`.

## MVP-111 WhatsApp notifications in the test environment

Acceptance uses a **synthetic Meta provider**. Nothing contacts Meta and no real WhatsApp account,
number or token is ever used in hr-dev.

- **Secrets.** `init-secrets.sh` generates, once and never overwriting, three root-only files read by
  Core through the configtree: `secrets/core/DIVALHR_MESSAGING_ENCRYPTION_KEY` (key material for the
  token and recipient-number encryption), `DIVALHR_MESSAGING_APP_SECRET` (signs callbacks) and
  `DIVALHR_MESSAGING_VERIFY_TOKEN` (callback handshake). None is an environment value or a repository
  value. The synthetic provider (container user 1000) holds copies of the last two in
  `secrets/provider/app-secret` and `verify-token`, so it can sign and verify like Meta. An existing
  environment gains the files on its next deployment (`remote-deploy.sh` runs `init-secrets.sh` when
  any is missing). **Never rotate or delete the encryption key** once connectors exist: stored tokens
  and numbers become unreadable (rotate the token and re-register the recipients). Include
  `secrets/` in the secrets backup; the values are never printed or logged.
- **Provider.** `infrastructure/docker/meta-provider` runs as `meta-provider` on the existing
  `internal: true` network `hooks`, shared with Core only, with the alias `graph.meta.test`, no published
  port and no route out. It creates a throwaway CA on every start and shares only the CA certificate
  (`$HR_DEV_DATA/meta-provider`). Core's test-provider exception (`DIVALHR_MESSAGING_GRAPH_BASE_URL`,
  `DIVALHR_MESSAGING_TEST_PROVIDER_HOST`, `_PORT`, `_CA_FILE`) names that one host and port with TLS and
  host-name verification; Core refuses it outside development and test, and production accepts only
  `https://graph.facebook.com`. The deep-link origin is the existing `DIVALHR_WEB_BASE_URL`.
- **Driving it.** Control endpoint on `127.0.0.1:8099` inside the container, for example
  `docker compose exec meta-provider wget -qO- http://127.0.0.1:8099/control/sends`. Scenario, template
  and callback paths are listed in `docs/WHATSAPP.md`. Callbacks go to Core's callback URL only
  (fixed at start); Caddy does not route the callback publicly in this environment.
- **Callback exposure.** `/api/v1/public/messaging/whatsapp/callback` is on the public chain. Do not
  publish it to the internet for a real Meta app without edge rate limiting and the review in ADR 0029.
- **Rollback.** `db/rollback/V40__rollback.sql` refuses while connectors, recipients, deliveries or
  fan-out data exist. To return to a pre-V40 release, restore the pre-deploy database backup
  (section 9) instead.

## MVP-093A monthly compensation

- Six operations (`getEmployeeCompensation`, `previewEmployeeCompensation`, `commitEmployeeCompensation`,
  `previewEmployeeCompensationCancellation`, `cancelEmployeeCompensationChange`, `getMyCompensation`).
  Buckets: `compensation-read`/`-self-read` 60, `-preview` 20, `-write` 10 per subject per minute;
  tenant preview 600 and write 300 per hour (`application.yaml`).
- The preview quota (60 per hour per subject) is an in-process counter per instance; commands are
  counted from the evidence table. Timeouts surface as `503 EMPLOYMENT_CHANGE_TIMEOUT`.
- Mutation checks: `scripts/dev/compensation-mutations.sh [n ...]` (needs Gradle and Docker); a
  surviving mutation exits non-zero. Dev data contains no real salary; never paste amounts into
  issues, logs or support tickets.

## MVP-094A monthly payroll runs

- Seven operations (`getPayrollPolicy`, `previewPayrollPolicy`, `commitPayrollPolicy`,
  `previewPayrollRun`, `listPayrollRuns`, `commitPayrollRun`, `getPayrollRun`). Subject buckets per
  minute (`application.yaml`, environment `DIVALHR_PAYROLL_*_REQUESTS_PER_MINUTE`): `payroll-policy-read`
  60, `-preview` 10, `-write` 5; `payroll-run-read` 60, `-preview` 5, `-create` 2. Tenant buckets per
  hour: `payroll-run-preview` 60, `payroll-run-create` 20. There is no scheduled job.
- Limits: 5,000 employees per run (`409 PAYROLL_RUN_LIMIT_REACHED` before anything is calculated),
  25 versions per month and currency, 60 policies per legal entity. Timeouts surface as
  `503 EMPLOYMENT_CHANGE_TIMEOUT`.
- Migration V44 is additive. Rollback `db/rollback/V44__rollback.sql` is manual, never run by Flyway,
  and refuses while any policy or run row exists (there is no override); it does not edit
  `flyway_schema_history`. Prefer rolling back only the application.
- Mutation checks: `scripts/dev/payroll-run-mutations.sh [n ...]` (needs Gradle; 4 needs Docker):
  1 hours-style scaling, 2 wrong denominator, 3 early rounding (`PayrollCalculatorTest`,
  `PayrollRunApiIntegrationTest`), 4 removed source-drift check (`PayrollRunApiIntegrationTest`),
  5 removed tenant predicate (`JdbcPayrollRunRepositoryTenantTest`), 6 and 7 currency rules of
  the calculator (R178-1: other-currency staff excluded, a switch inside the month blocked), 8 and 9
  `NO_CHANGE` (`PayrollRunApiIntegrationTest`). A surviving mutation exits
  non-zero. Dev data contains no real salary; never paste amounts into issues, logs or tickets.

## MVP-094B statutory preview

- Eleven operations (`listStatutoryRulePacks`, `getStatutoryRulePack`, `getStatutoryActivation`,
  `activateStatutoryRulePack`, `getStatutoryProfile`, `previewStatutoryProfile`,
  `commitStatutoryProfile`, `previewStatutoryCalculation`, `commitStatutoryCalculation`,
  `listStatutoryCalculations`, `getStatutoryCalculation`). Subject buckets per minute
  (`application.yaml`, environment `DIVALHR_STATUTORY_*_REQUESTS_PER_MINUTE`): `statutory-read` 60,
  `statutory-calculation-preview` 5, `-create` 2, `statutory-profile-preview` 10, `-write` 5,
  `statutory-activation-write` 2. Tenant buckets per hour: `statutory-calculation-preview` 60,
  `-create` 20. There is no scheduled job. Timeouts surface as `503 EMPLOYMENT_CHANGE_TIMEOUT`.
- Limits: 5,000 source lines, 25 versions per run and pack, 60 activations per legal entity, 100
  profile entries per employee (`409 STATUTORY_LIMIT_REACHED`).
- **The seeded pack is a DRAFT and stays one.** hr-dev and every dev data script contain only
  `CD-SALARY-PREVIEW` v1 (DRAFT). Without a VERIFIED pack every preview is `BLOCKED` with
  `RULE_PACK_MISSING` or `RULE_PACK_NOT_VERIFIED` ("Professional verification required"). Never add
  a VERIFIED pack to a seed, migration or dev script.
- **Migration V45 is additive.** Rollback `db/rollback/V45__rollback.sql` is manual, never run by
  Flyway, and refuses while any activation, profile or calculation exists, while any pack other than
  the unverified seed exists, while any source carries a hash, or while any source of another pack
  exists (`V45 rollback refused: ...`; there is no override). Restoring V44 exactly is therefore
  possible only on an empty data set. It does not edit `flyway_schema_history`. Prefer rolling back
  only the application: V45 tables are not read by the previous release.
- **Verifying a pack with evidence (manual, outside the application).** The application has no
  verify endpoint, by design. A pack is made VERIFIED only after a professional review:
  1. Obtain the written review of the rules against the official sources (an accountant or labour
     lawyer qualified in the DRC): reviewer name, organization, review date and an attestation that
     the rules, the base, the periodization of the annual bands and the cap match the sources.
  2. Retrieve every source the rules cite from its official address (`dgi.gouv.cd`,
     `desec.dgi.gouv.cd`, `cnss.cd`, `budget.gouv.cd`), keep the document with the evidence and
     compute its SHA-256 (`sha256sum`).
  3. As the database owner, in one transaction: insert the new pack (a new `code`/`version`, never an
     update of a verified one) as DRAFT with its rules, interval and `method_version`; insert its
     sources with their hashes and retrieval dates; then `UPDATE ... SET status = 'VERIFIED'` with
     `reviewer_name`, `reviewer_organization`, `reviewed_on`, `attested = true` and `verified_at`.
     The deferred checks refuse the commit if a source is missing or unhashed, or a cited key is
     absent.
  4. Read it back with `getStatutoryRulePack` (rules digest, sources, verification) and file the
     evidence with the change record. A later correction is a new pack version; the old one is
     retired (`status = 'RETIRED'`), never edited. Calculations already created keep the pack they
     used.
  5. A tenant administrator then activates it for a legal entity from a month
     (`activateStatutoryRulePack`).
  Synthetic packs (`SYNTHETIC-TEST-*`, reviewer "Synthetic Test Reviewer") exist only in tests and
  browser journeys (`apps/web/e2e/synthetic-verified-rule-pack.sql`) and must never be created in
  hr-dev or production.
- Mutation checks: `scripts/dev/statutory-mutations.sh [n ...]` (needs Gradle; 7, 8 and 9 need Docker):
  1 employee CNSS removed, 2 employer contribution deducted from the employee, 3 wrong IRPP bracket,
  4 cap removed, 5 early rounding (`StatutoryEngineTest`, `StatutoryApiIntegrationTest`), 6 DRAFT pack
  accepted (`StatutoryApiIntegrationTest`), 7 source drift ignored (digest comparison removed), 8
  tenant predicate removed (`JdbcStatutoryRepositoryTenantTest`), 9 `NO_CHANGE` reported as stale
  (`StatutoryApiIntegrationTest`), 10 profile-uniformity check removed. A surviving mutation exits
  non-zero. Dev data contains no real salary; never paste amounts into issues, logs or tickets.


## MVP-094C payroll approval and payslips

- Twelve operations (`previewPayrollApproval`, `commitPayrollApproval`, `listPayrollPackages`,
  `getPayrollPackage`, `previewPayrollVoid`, `commitPayrollVoid`, `listPackagePayslips`,
  `getPackagePayslip`, `downloadPackagePayslipPdf`, `listMyPayslips`, `getMyPayslip`,
  `downloadMyPayslipPdf`). Subject buckets per minute (`application.yaml`, environment
  `DIVALHR_PAYROLL_*` / `DIVALHR_PAYSLIP_*`): `payroll-package-read` 60, `payroll-approval-preview` 5
  (approval and void previews), `payroll-approval-write` 2 (approval and void), `payslip-download` 30.
  Tenant buckets per hour: 60, 20 and 300. No scheduled job. Timeouts surface as
  `503 EMPLOYMENT_CHANGE_TIMEOUT`; a PDF that cannot be rendered within 5 s or 256 KiB is
  `500 PAYSLIP_RENDER_FAILED`.
- **Migration V46 is additive** (tables `payroll_package`, `payroll_package_source`,
  `payroll_package_void`, `payroll_payslip`; two triggers on `payroll.pay_run` and
  `payroll.statutory_calculation`). Rollback `db/rollback/V46__rollback.sql` is manual, never run by
  Flyway, and refuses while any package, source, void or payslip row exists
  (`V46 rollback refused: ...`; no override). It does not edit `flyway_schema_history`. Prefer rolling
  back only the application: V46 tables are not read by the previous release.
- **Source lock.** While a package is APPROVED the database refuses to supersede its payroll run or
  statutory calculation (`payroll_package_source_locked`), so 094A/094B recalculation fails with a
  conflict until the package is voided. A voided run/calculation pair is never approved again; a
  correction needs a changed run (a new 094A version) and a new calculation.
- **Synthetic hr-dev acceptance (no real pack, no real people).** The real pack `CD-SALARY-PREVIEW`
  stays DRAFT. For acceptance only, as the database owner and exactly as for MVP-094B, run
  `apps/web/e2e/synthetic-verified-rule-pack.sql` with `psql -v pack_id=<new uuid> -v
  pack_code=SYNTHETIC-TEST-<DATE>` (it inserts a DRAFT pack with fake hashes and moves it to VERIFIED in
  one transaction; reviewer "Synthetic Test Reviewer", organization "Test Only"), then activate it for a
  synthetic legal entity with `activateStatutoryRulePack` and use synthetic employees with
  `statutory profiles`. Packages built on it show `synthetic: true` and the test badge. Record in the
  acceptance evidence the pack id and code, the activation, and either the cleanup or that the
  synthetic rows are preserved in synthetic hr-dev data; a voided package, its payslips and the pack
  cannot be deleted (append-only), so acceptance data is retired by marking the tenant synthetic, not by
  deleting. Never create a `SYNTHETIC-TEST-*` pack in production, a seed, a migration or a dev script,
  and never present it as DRC-approved.
- **Access-log redaction.** Payslip downloads send `Content-Disposition: attachment;
  filename="payslip-YYYY-MM-xxxxxxxx.pdf"`. Caddy already deletes `Content-Disposition` from logged
  response headers (SEC-003) and nginx logs only the safe format; the canary must gain a payslip
  download sentinel. Payroll paths contain opaque UUIDs only; no amount is ever in a path or query.
- Dev data contains no real salary or payslip; never paste amounts, employee numbers, digests or
  payslip files into issues, logs or tickets.
