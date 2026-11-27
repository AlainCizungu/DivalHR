# LAUNCH-002 readiness and onboarding-lock checklists

Two checklists. **A. Production readiness** is completed with evidence before the controlled-pilot environment is accepted
(`accept.sh` writes most of it). **B. Onboarding lock** is a gate for a founder decision that is **not part of LAUNCH-002**:
nothing here lifts the lock. Mark each line with the evidence path or the reason it is not met; an unmet line stops the gate.

## A. Production readiness (all must be met while the launch lock is CLOSED)

| #  | Item                                                                                                               | Evidence |
| -- | ------------------------------------------------------------------------------------------------------------------ | -------- |
| 1  | Provisioned from `infrastructure/production/terraform` with a reviewed plan; no manual resource                     | plan output reviewed by the founder |
| 2  | Data volume mounted and encryption confirmed (`prepare-data-volume.sh --confirm-encrypted`)                         | host evidence |
| 3  | `exposure-check.sh --waf-probe` passes: only 443 and the 80 redirect; WAF blocks the probe; certificate valid      | `02-exposure.txt` |
| 4  | `http-checks.sh` passes through the public names; commercial redirect and never-public paths                       | `03-http-through-public-names.txt` |
| 5  | `evidence-host.sh` passes: only 8443 non-loopback, no SSH, no test service, secret files 0400, timers active        | `04-host-evidence.txt` |
| 6  | Final-mode realm verification passed in the deployment; bootstrap administrator removed                            | deploy log |
| 7  | Secrets exist only in Secrets Manager and as 0400 files; `log-hygiene.sh` passes after real sign-ins               | `06-log-hygiene.txt` |
| 8  | E-mail provider configured (`launch-lock.sh status` prints `email: CONFIGURED`), SPF/DKIM/DMARC verified, one synthetic message delivered | operator record (no addresses in Git) |
| 9  | One complete restore drill passed; measured recovery recorded                                                       | drill template |
| 10 | Backup younger than 26 h and verified off-host; backup, drill-age and heartbeat alarms exist and the SNS subscription is confirmed | `05-backup-and-drill.txt`, SNS confirmation |
| 11 | Both browser journeys passed (public/sign-in; bilingual synthetic tenant)                                           | `07-browser-journeys.txt` |
| 12 | Launch lock `CLOSED` in SSM and on the host before and after acceptance                                             | `01-`, `08-launch-lock-still-closed.txt` |
| 13 | Founder approval recorded for the release; deployment evidence package kept                                         | `approval.txt`, evidence bucket |
| 14 | Costs reviewed against the AWS Pricing Calculator estimate                                                          | estimate on file |
| 15 | Incident runbook read by the on-call operator; alarm drill (one alarm provoked and received)                        | log entry |

## B. Onboarding-lock checklist (precondition of a founder decision, after LAUNCH-003)

- [ ] LAUNCH-003 outcome accepted by the founder, with its evidence.
- [ ] All of section A met within the last 90 days; a drill younger than 100 days.
- [ ] Privacy governance of LAUNCH-001 operational: organization privacy contacts, retention policy decisions made (or
      the mechanism confirmed inactive by decision), lawful-basis worksheet reviewed by counsel.
- [ ] Customer-facing terms and privacy notice reviewed by counsel for the markets being onboarded; the commercial claims
      match `COMMERCIAL-LAUNCH-TRUTH.md`.
- [ ] Named support and incident contacts, escalation path and communication template agreed.
- [ ] Capacity baseline recorded (OPS-PRODUCTION section 11) and the single-host limitation accepted for the first cohort.
- [ ] Written founder authorization naming who may attach the `launch-lock-lift` policy and for how long; the change is made
      through a recorded `ssm put-parameter` by that identity, then the policy is detached.
- [ ] After a lift: evidence of the new state (`launch-lock.sh status`), a copy of the authorization, and the first
      customer organization created through the normal flow, not seeded.
