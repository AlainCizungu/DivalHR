# Restore drill evidence (LAUNCH-002)

Complete one copy per drill and keep it with the redacted output of `restore-drill.sh`. Names, times, counts and
statuses only; never a value, secret or personal data. The first production deployment is **not accepted** until one drill
passes; then repeat quarterly.

| Field                                         | Value |
| --------------------------------------------- | ----- |
| Date (UTC) and operator                       |       |
| Release under drill (sha12)                   |       |
| Backup used (source: off-host or local; stamp) |       |
| Backup release (sha12) and Flyway entries     |       |
| Both dumps restored into empty databases      | PASS / FAIL |
| Core started, Flyway validated, entry count equals the manifest | PASS / FAIL |
| Restored migration set equals the running release's | PASS / FAIL |
| Production realm present in the restored Keycloak database | PASS / FAIL |
| HTTP checks and back-channel check on the drill | PASS / FAIL |
| Production healthy throughout (samples / failures) |       |
| Drill mail host neutralised (no outbound e-mail) | confirmed |
| Measured restore seconds / recovery seconds   |       |
| Measured recovery point (backup age at drill) |       |
| Against internal targets (RPO 24 h, RTO 8 h)  | within / outside, with reason |
| Result of `state/last-restore-drill`          | PASS / FAIL |
| Follow-ups                                    |       |

Reviewer (founder or delegate) and date:
