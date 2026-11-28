# LAUNCH-002 evidence map

Maps each requirement of Issue #230 to the file that implements it, the test that proves it in CI, and the evidence a
live acceptance produces later. "Live" items are **not available until the separately approved provisioning, deployment
and acceptance steps run**; this PR provisions nothing.

| Requirement (ADR 0048)                                  | Implementation                                                                 | Test in CI (no AWS needed)                                  | Live evidence (after approval) |
| ------------------------------------------------------- | ------------------------------------------------------------------------------ | ----------------------------------------------------------- | ------------------------------ |
| L2-1 domains, two origins                               | `apps/web/src/features/commercial/origins.tsx`, `main.tsx`, Caddyfile           | `origins.test.tsx`, policy: Caddy commercial/app rules      | `public.spec.ts`, `http-checks.sh` |
| L2-2/L2-3 single host, only the ALB public, no SSH      | `terraform/network.tf`, `security.tf`, `compute.tf`                              | policy: network exposure                                    | `exposure-check.sh`, `evidence-host.sh` |
| L2-3 deny-by-default egress, no Mailpit or test services | endpoints in `network.tf`, `compose.yaml` (`mail-out` for Core and Keycloak only) | policy: egress, no development material, mail network       | host evidence |
| L2-3a images from a private registry                    | `build-push.sh`, `mirror-images.sh`, immutable ECR in `storage.tf`               | policy: registries, ECR/tooling agreement                   | registry listing |
| L2-4 WAF baseline, body limit, rate rules, no sensitive logging, bounded emergency access | `terraform/waf.tf`                                     | policy: WAF                                                 | `exposure-check.sh --waf-probe` |
| L2-5 secrets: Secrets Manager + KMS, root files, never in env or Git, production-only | `secrets.manifest`, `secrets-bootstrap.sh`, `init-secrets.sh`, `iam.tf` | policy: secrets, IAM, compose environment                   | file modes in host evidence, `log-hygiene.sh` |
| L2-6 production realm, exact MFA, no accounts, bootstrap admin removed, final verification | `render-realm.py`, `realm-verify.sh`, `keycloak-admin-setup.sh`          | policy: realm render; `verify-realm.test.mjs` (existing)    | final-mode verification in the deploy log |
| L2-7 e-mail fails closed until the provider exists      | `lib.sh` (`prod_load_mail`), Core `MailProperties.validate`, `launch-lock.sh`    | policy: lock reports NOT_CONFIGURED; SES SMTP endpoint routable, mail host pinned, no CIDR egress   | `launch-lock.sh status` |
| L2-8 data isolation                                     | production-only names, roles, buckets, KMS key                                   | policy: no development material, name prefix validation     | resource listing |
| L2-9 backup and restore                                 | `backup.sh`, `restore-drill.sh`, DLM, versioned buckets                          | policy: off-host gating, drill isolation, bucket controls   | drill template, `last-restore-drill` |
| L2-10 gated release, rollback, one lock, redacted evidence | `deploy.sh`, `remote-deploy.sh`, `rollback.sh`, `lib.sh`                       | policy: gate guards, rollback order, no SSH                 | deploy evidence package |
| L2-11 monitoring and incident routing                   | `monitoring.tf`, `metrics.sh`, `watchdog.sh`, units, incident runbook            | policy: metric/alarm agreement, no dimensions, timers       | provoked alarm, SNS confirmation |
| L2-12 launch lock closed by default                     | `launch-lock.tf`, `launch-lock.sh`, `lib.sh`, `accept.sh`                        | policy: lock defaults, no lift command, no `OPEN` in Terraform | `01-` and `08-` acceptance files |

Existing suites that still guard the pieces this story reuses: `pnpm ops:test` (hr-dev, evidence, secret needles,
access-log), `pnpm realm:test`, the web unit tests, and the commercial truth test.
