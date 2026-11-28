# LAUNCH-002 incident runbook (production controlled pilot)

Applies to `https://app.divalhr.com` and `https://divalhr.com`. Alarms go to `contact@dival.ai`. The environment is a
single-host pilot behind a launch lock; response times below are **internal targets for a staffed pilot, not an SLA**.
Never paste a secret, token, OIDC code, personal value or log line into a ticket, chat or this log: record names, times,
counts and statuses only. Tools: [OPS-PRODUCTION.md](../OPS-PRODUCTION.md).

## 1. Severity

| Severity | Meaning                                                                                              | Acknowledge | Mitigate or escalate to the founder |
| -------- | ---------------------------------------------------------------------------------------------------- | ----------- | ----------------------------------- |
| SEV-1    | Suspected secret or data exposure; production reachable in a way it must not be; data loss or corruption; complete outage while customers use it | 30 minutes (staffed hours: immediately) | 2 hours |
| SEV-2    | Outage or sustained error rate of sign-in or core workflows; failed backup beyond 26 h; failed deployment that rolled back; certificate under 14 days | 2 hours | 8 hours |
| SEV-3    | Degraded or intermittent behaviour, a single failing alarm that clears, restore drill overdue        | 1 business day | Next business day |
| SEV-4    | Question or improvement; no user impact                                                              | 3 business days | Backlog |

While the launch lock is closed there is no customer data: an outage is SEV-3 unless it blocks the planned acceptance or
the exposure is a security one (always SEV-1).

## 2. First ten minutes

1. Acknowledge the alarm; open a log entry (template below) with the UTC time and severity.
2. Look at the state, not the data: `launch-lock.sh status`, `docker compose ps` for `divalhr-prod`, the alarm names that
   fired, `state/last-deploy`, `state/failed-release`, `state/last-restore-drill`.
3. If a deployment is running, do nothing that needs the host lock until it ends (a busy lock exits 75).
4. SEV-1 security: contain first (below), then preserve evidence, then investigate.

## 3. Playbooks

- **Secret suspected exposed.** Treat as SEV-1. Rotate rotatable secrets per OPS-PRODUCTION section 6. For a non-rotatable key
  (e-mail lookup, encryption and signing keys) record the exposure, restrict access, and ask the founder for the
  migration decision. Never "fix" by pasting a value anywhere.
- **Unexpected public exposure** (a port, a path, the admin console). Tighten the security group or Caddy rule, run
  `exposure-check.sh --waf-probe` and `http-checks.sh`, preserve `evidence-host.sh` output.
- **Outage.** Check the load balancer target health, the Route 53 health check, `http-checks.sh` on the host, the watchdog
  journal. A single unhealthy container is restarted by the watchdog within its bounds; when it reports "operator
  attention required" the cause is not transient: read `docker compose logs --tail 40 <service>` (service names and
  states only in tickets).
- **Failed deployment.** The rollback is automatic. Confirm `state/last-rollback`, run the acceptance HTTP checks, keep the
  deploy evidence directory, and do not retry blindly: fix the cause and deploy a new SHA through the gate.
- **Backup alarm.** Run `backup.sh --reason nightly`; if the off-host copy fails check the instance role, the bucket and the
  endpoint, never write a backup by hand into the bucket.
- **Certificate expiry.** The ACM certificate renews automatically when its DNS validation record still exists; verify the
  record and the certificate status in ACM.
- **Abuse or traffic spike.** WAF rate rules already limit per address. Use the time-boxed `emergency_allow_cidrs` only to
  keep an operator working through an incident, then clear it. Do not disable a rate rule.
- **Restore needed.** OPS-PRODUCTION sections 8 and 9. Decide the data-loss window with the founder before restoring over a
  live database.

## 4. Incident log template

```
Incident:            INC-YYYYMMDD-N
Opened (UTC):        
Severity:            SEV-
Detected by:         alarm name / person / acceptance check
Launch lock state:   CLOSED / OPEN
Release (sha12):     
Summary (no data):   
Timeline (UTC):      hh:mm event
Customer impact:     none (lock closed) / description without personal data
Actions taken:       
Evidence kept:       paths of redacted outputs only
Decision owner:      
Resolved (UTC):      
Follow-ups:          
Customer communication sent: yes/no (template below)
```

## 5. Customer communication template (only once real customers exist)

Send from the monitored Dival address; never include personal data, tenant data or technical secrets.

```
Subject: [DivalHR] Service notice - <short description> - <date>

Hello,

At <time, with timezone> we detected <what customers experienced, in plain words>. <Current status: investigating /
mitigated / resolved at time>. <What customers should do, if anything.>

<If personal data may be affected: say so plainly, say what is known and not yet known, and that we will notify the
affected organizations and the competent authority as the law requires.>

Next update: <time>. Questions: contact@dival.ai.

DivalHR
```

Personal-data breaches follow the LAUNCH-001 privacy runbook (`LAUNCH-001-OPERATOR-RUNBOOK.md`) for assessment and
notification; this runbook only gets the environment safe.
