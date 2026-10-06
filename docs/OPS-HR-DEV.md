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

Keycloak trusts `X-Forwarded-*` only from Caddy's address on the `idp` network (`KC_PROXY_TRUSTED_ADDRESSES`), and only Caddy can reach Keycloak (A65-2). Caddy overwrites `X-Forwarded-Proto`, `Host` and `X-Forwarded-Port` on the public and private routes and strips `Forwarded`. Access logs drop query strings and the `Authorization`, `Cookie` and `Set-Cookie` headers.

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

It fails closed unless the SHA is reachable from `origin/main`; every GitHub check run on the SHA is green and every check in `ops/hr-dev/required-checks.txt` succeeded (`provenance.py`); and the instance holds a successful complete `aws-verify` run (stage `all`, including `PASS e2e` and `PASS hrdev-rehearsal`) whose bundle head is exactly that SHA. It ships a bundle whose only head is the SHA; the instance unpacks it with `git archive` into `releases/<sha>`, builds `divalhr-test/*:<sha>`, takes a **pre-deploy dump** of both databases, starts the release, runs the admin setup, `http-checks.sh` and `backchannel-check.sh`, and installs the timers. The release is recorded as current only after all of these succeed, timer installation included; `install-units.sh` restores the previous unit files and enablement when it fails (R66-4). A failure triggers the automatic rollback (section 9). The Mac keeps redacted evidence in `.git/divalhr-deploy/<run>/`: provenance decisions, inventory before and after with their diff, the remote log and the host evidence.

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
* Logs are Docker `json-file`, 10 MB × 5 per container. Applications log no tokens, credentials or personal data; Caddy drops query strings and credential headers.

## 9. Backup, restore and rollback (A65-6)

* `backup.sh --reason nightly|pre-deploy`: `pg_dump -Fc --no-owner --no-privileges` of `divalhr` and `keycloak`, SHA-256 sums, a manifest with the release and the Flyway history count. Keeps 7 nightly and 3 pre-deploy.
* `restore-drill.sh`: restores the newest dump into a **separate** project (`divalhr-test-drill`), networks (10.72), loopback ports (29080/29443/29180), Caddy's internal CA and fresh storage under `drill/<stamp>`, never the live `postgres/`. Both dumps go into empty databases (checksums, `DROP … WITH (FORCE)`, owners, `btree_gist`, `pg_restore --role=<owner>` without the extension entries). Core must start (Flyway validates) with the same history count; the realm must exist; HTTP and back-channel checks run against the drill; the live stack is probed every 5 s throughout and any failure fails the drill. Times are measured and written to `state/last-restore-drill`. The drill is removed afterwards.
* `rollback.sh --to <previous sha> [--from <sha>]`: always stops the application containers, restores **both** pre-deploy dumps of the failed release (Core and Keycloak) into empty databases, and only then starts the target's images (R66-2: a newer Keycloak image migrates its own database, which Core's Flyway history does not show). Data written by the failed release is discarded. Without pre-deploy dumps for that release it refuses and changes nothing. `state/last-rollback` records the measured times. A rollback that fails its checks stops for manual recovery.

Manual recovery: stop the application (`docker stop divalhr-test-core-api-1 divalhr-test-keycloak-1 divalhr-test-ai-service-1 divalhr-test-web-1`), restore a chosen backup with the functions in `lib.sh` (`hr_restore_backup <running sha> <backup dir>`) under the host lock, start the release whose migrations match, and run `http-checks.sh`. If the volume itself is lost, create a volume from the newest DLM snapshot, attach it, mount it and restore the newest dump.

## 10. Verification and rehearsal

Every complete `aws-verify` (`bash ops/aws/aws-verify.sh <ref> all`) ends with the stage `hrdev`: `pnpm ops:test` and `ops/hr-dev/rehearse.sh`, which deploys the commit into the throw-away project `divalhr-rehearsal` (10.73, `127.0.0.1:28080/28443/28180`, internal CA, `/var/tmp`), then runs the browser acceptance suite (`apps/web/e2e/hr-dev`: landing EN/FR, discovery and keys, authorization code with PKCE, callback, refresh, logout, the web app round trip, a privileged first sign-in with password and authenticator from the setup e-mail, the admin console through the admin URL, identity assets), `verify-realm.mjs` in final mode, a backup, the host-lock concurrency test, a watchdog failure injection (Core's process stopped with SIGSTOP), the isolated restore drill with the browser suite on the restored stack, a first deployment and an upgrade whose timer installation fails (nothing recorded as current; the upgrade returns to the previous release), and rollbacks of failing releases: one that changes only the identity database (the pre-deploy Keycloak dump must be restored) and one that adds a Core migration. It removes everything afterwards. The same browser suite runs against the live environment with `HR_DEV_BASE_URL=https://hr-dev.dival.ai`, the tunnel's admin URL and, for the employee smoke test, `HR_DEV_EMPLOYEE_USER` / `HR_DEV_EMPLOYEE_PASSWORD` of a synthetic employee.

## 11. Known limitations

* One instance, one availability zone; no high availability. Recovery point: the last nightly dump (and the last daily snapshot).
* The root volume is unencrypted (operating system, images, logs).
* Keycloak and the identity origin share `hr-dev.dival.ai`; a dedicated identity origin remains a production requirement (D1).
* The operator account has no second factor of its own; it is reachable only through SSH from the owner's address.
* Certificates come from Let's Encrypt for `hr-dev.dival.ai` only.
