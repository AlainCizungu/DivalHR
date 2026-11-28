#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-11): publishes the host metrics the alarms watch, every 5 minutes.
#
#   sudo ops/production/metrics.sh          (divalhr-prod-metrics.timer)
#
# Namespace Divalhr/Production, NO dimensions: no tenant, employee, e-mail, partner, token or free
# text can ever become a label. Values are counts and gauges computed from container state, file
# ages and log LINE COUNTS only (no log content is read into a metric, printed or sent).
#   HostHeartbeat 1 | DiskUsedPercent | MemoryUsedPercent | UnhealthyContainers
#   BackupAgeHours (newest verified OFF-HOST backup) | RestoreDrillAgeDays (newest PASSING drill)
#   DeployFailed (1 while the last deployment failed or rolled back) | WatchdogRestarts (last 5 min)
#   IdentityFailures | WorkerFailures | Application5xx (counts, last 5 min)
# A missing/never-succeeded backup or drill reports a very large age, so its alarm fires.
set -u
. "$(dirname "$0")/lib.sh"
prod_require_root
DOCKER="${DOCKER:-docker}" AWS="${PROD_AWS:-aws}"
NOW="${METRICS_NOW:-$(date +%s)}"
REGION="$(prod_config region)"
NS="Divalhr/Production"
S="$PROD_DATA/state"
age_hours() { local t; t="$(cat "$1" 2>/dev/null)" || t=""; case "$t" in ''|*[!0-9]*) echo 9999 ;; *) echo $(( (NOW - t) / 3600 )) ;; esac; }

[ -n "${METRICS_DRY_RUN:-}" ] || "$PROD_OPS/launch-lock.sh" sync >/dev/null 2>&1 || true   # the host copy of the lock follows SSM (read-only, fail closed)
disk=$(df -P "$PROD_DATA" | awk 'NR==2 {gsub("%","",$5); print $5}')
rootdisk=$(df -P / | awk 'NR==2 {gsub("%","",$5); print $5}')
[ "${rootdisk:-0}" -le "${disk:-0}" ] || disk="$rootdisk"
mem=$(awk '/MemTotal/ {t=$2} /MemAvailable/ {a=$2} END {printf "%d", (t-a)*100/t}' /proc/meminfo)
unhealthy=$("$DOCKER" ps --filter "label=com.docker.compose.project=$PROD_PROJECT" --format '{{.Status}}' \
  | grep -cE 'unhealthy|Restarting|Exited' || true)

drill_age_days=9999
if [ "$(sed -n 's/^result=//p' "$S/last-restore-drill" 2>/dev/null)" = PASS ]; then
  f="$(sed -n 's/^finished=//p' "$S/last-restore-drill")"
  t=$(date -u -d "$f" +%s 2>/dev/null || echo 0)
  [ "$t" -gt 0 ] && drill_age_days=$(( (NOW - t) / 86400 ))
fi
deploy_failed=0
[ -s "$S/failed-release" ] && [ "$(cat "$S/failed-release")" != "$(hr_current_release)" ] && deploy_failed=1
[ -s "$S/deploy-failed-flag" ] && deploy_failed=1
wd=$(awk -v now="$NOW" '$1 > now - 300' "$S/watchdog/"*.restarts 2>/dev/null | grep -c . || true)

# Counts of log LINES in the last 5 minutes. Nothing from a line is kept.
count_lines() { "$DOCKER" logs --since 5m "$1" 2>&1 | grep -cE "$2" || true; }
edge="$PROD_PROJECT-caddy-1" core="$PROD_PROJECT-core-api-1" idp="$PROD_PROJECT-keycloak-1"
app5xx=$(count_lines "$edge" '"status": ?5[0-9][0-9]')
idfail=$(count_lines "$edge" '"uri": ?"/identity/realms/[^"]*/protocol/openid-connect/token".*"status": ?(400|401|5[0-9][0-9])')
idfail=$(( idfail + $(count_lines "$idp" 'type=(LOGIN_ERROR|CODE_TO_TOKEN_ERROR|REFRESH_TOKEN_ERROR)') ))
workerfail=$(count_lines "$core" ' ERROR .*(Scheduled|scheduler|worker|queue|outbox|retention)')

data="[
 {\"MetricName\":\"HostHeartbeat\",\"Value\":1},
 {\"MetricName\":\"DiskUsedPercent\",\"Value\":${disk:-100},\"Unit\":\"Percent\"},
 {\"MetricName\":\"MemoryUsedPercent\",\"Value\":${mem:-100},\"Unit\":\"Percent\"},
 {\"MetricName\":\"UnhealthyContainers\",\"Value\":${unhealthy:-0}},
 {\"MetricName\":\"BackupAgeHours\",\"Value\":$(age_hours "$S/last-backup-success")},
 {\"MetricName\":\"RestoreDrillAgeDays\",\"Value\":$drill_age_days},
 {\"MetricName\":\"DeployFailed\",\"Value\":$deploy_failed},
 {\"MetricName\":\"WatchdogRestarts\",\"Value\":${wd:-0}},
 {\"MetricName\":\"IdentityFailures\",\"Value\":${idfail:-0}},
 {\"MetricName\":\"WorkerFailures\",\"Value\":${workerfail:-0}},
 {\"MetricName\":\"Application5xx\",\"Value\":${app5xx:-0}}
]"
if [ -n "${METRICS_DRY_RUN:-}" ]; then printf '%s\n' "$data"; exit 0; fi
[ -n "$REGION" ] || prod_die "missing $PROD_DATA/config/region"
"$AWS" cloudwatch put-metric-data --region "$REGION" --namespace "$NS" --metric-data "$data" \
  || prod_die "publishing the metrics failed"
