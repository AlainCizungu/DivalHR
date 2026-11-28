# LAUNCH-002 production data flow and threat model (Issue #230, ADR 0048)

Scope: the controlled-pilot production environment at `https://app.divalhr.com` (and the commercial site at `https://divalhr.com`). This is an engineering framework, not legal advice, certification or a service-level commitment. It extends `SPRINT-0-THREAT-MODEL.md` and the OPS-001 boundary; it does not change application authorization.

## Data flow

1. A browser reaches the **load balancer** on 443 (WAF attached). TLS ends there (ACM) and re-encrypts to Caddy on the host.
2. **Caddy** (host, private subnet) routes `app.divalhr.com`: `/` to the web container, `/api` to Core, `/ai` status to the AI service, `/identity` (one realm) to Keycloak; `divalhr.com` serves only the commercial pages and redirects everything else to the application origin; `www` redirects to `divalhr.com`.
3. **Core** and **Keycloak** use separate databases in one PostgreSQL container on the encrypted data volume. Core reaches Keycloak only through Caddy's internal route.
4. **Secrets** come from Secrets Manager (KMS) to root-owned files on the data volume; containers read them as mounted files.
5. **Backups**: nightly logical dumps on the data volume, copied to the versioned, encrypted S3 backup bucket; EBS snapshots by DLM. The secret store is never in a backup.
6. **E-mail** (invitations, identity set-up) leaves only through the Amazon SES SMTP interface endpoint (the one routable mail path), if the operator configured the sending identity.
7. **Logs**: container logs on the host (rotated), edge access log with the SEC-001/002/003 field deletions, WAF log with redacted fields, CloudWatch metrics without personal dimensions. No request body, authorization, cookie, invitation token, idempotency key, webhook signature or personal value is logged.
8. **Administration**: Session Manager and Run Command only; every session and command is logged by AWS. No inbound SSH.

No data flows to hr-dev, a synthetic provider, a real payment, lending, insurance, telecom or accounting partner, an analytics service or a CRM.

## Assets

Real workforce data once the launch lock is lifted (none before); identity credentials and keys; encryption and signing keys; database dumps; the release artefacts and the evidence that gates them; the launch lock itself.

## Actors

Anonymous internet visitor; abusive client; stolen end-user token without MFA; compromised application container; a malicious or mistaken operator; an AWS-credential thief; a supply-chain attacker (base image, dependency); the founder authorizing a release.

## Threats and controls

1. **Production reached from or shared with hr-dev.** Separate realm, issuer, clients, secrets paths, buckets, KMS key, ECR repository, volumes and project name. Policy tests fail on any hr-dev origin, `localhost`, development realm, seed account, Mailpit or synthetic provider in production files, and on any shared prefix.
2. **Direct access to the host or database.** No public address on the host, no inbound SSH, security group accepts only the load balancer on one port, PostgreSQL/Core/AI/Keycloak back-channel never published. An external scan after deployment proves only 80 (redirect) and 443.
3. **Data exfiltration or provider contact from a compromised container.** Egress is deny by default: only VPC endpoints (including SES SMTP). No default route, NAT or internet path.
4. **Abuse of anonymous endpoints** (invitation inspect/accept, partner applications). WAF managed baseline, per-address rate rules on `/api/v1/public/`, a stricter abuse rule, body-size limit; the application limiter stays on; the client address is the load balancer's view, never a client-supplied header; Caddy trusts only the load balancer's subnet for `X-Forwarded-For`.
5. **Secret exposure.** Secrets Manager with KMS; root-owned 0400 files; none in environment metadata, Git, CI output or evidence; secret-needle scans over logs and evidence; development/test prefixes refused; rotation runbook names what cannot be rotated in place (email lookup, encryption and signing keys).
6. **Weak identity.** Production realm with exact MFA for privileged roles, no users, exact redirect and web origins, secure cookies, no public admin console, bootstrap administrator removed after a named operator exists; final-mode realm verification gates the release.
7. **Unreviewed or unverified code in production.** Full commit reachable from `origin/main`, all checks green, a fresh full verification of the same tree, checksummed read-only evidence; images are built once, tagged with the full SHA and pulled from an immutable-tag repository; deploys come from a bundle, never a working tree.
8. **A bad release.** Pre-deploy backup, health/OIDC/backchannel/smoke before "current", automatic restore of both databases and the previous release on failure; one host lock serialises operations.
9. **Data loss.** Nightly dumps with checksums copied to versioned S3, snapshots, a restore drill proving release and migration state, alarms on backup age and drill age.
10. **Operator error or abuse.** No SSH; Session Manager and Run Command are audited; deployment needs recorded founder approval; the launch lock refuses onboarding until lifted with a recorded authorization.
11. **Premature use of real data.** The launch lock is closed by default and checked by the deployment, the acceptance script and the readiness checklist; acceptance uses synthetic identities that are disabled afterwards.
12. **Monitoring blind spot.** External HTTPS and certificate checks and alarms that do not depend on the host; the alarm destination must be confirmed before acceptance.
13. **Log leakage.** The edge access-log policy of SEC-001/002/003 is reused; WAF log redaction; `log-hygiene.sh` and secret-needle scans across every service in acceptance.

## Residual risks

Single host and zone (a recovery, not a failover); the load balancer does not validate the host's internal-CA certificate; a human with Session Manager and root on the host can read decrypted data and secret files (mitigated by audit, approval and few operators, not removed); the recovery targets are demonstrated, not guaranteed; the pilot has no private object storage for receipts (SECURITY.md pilot limitation) so receipt uploads stay in PostgreSQL.
