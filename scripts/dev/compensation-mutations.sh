#!/usr/bin/env bash
# Mutation checks for the monthly compensation backend (Issue #175, MVP-093A).
#
# Each mutation changes one line of production code, runs the tests that must notice it and expects
# a FAILURE. A mutation whose tests still pass SURVIVED, which means the evidence is too weak; the
# script then exits non-zero. Sources are always restored, even on interruption.
#
# Usage: scripts/dev/compensation-mutations.sh [mutation-number ...]   (default: all)
# Needs a working Gradle build of apps/core-api (the integration tests need Docker/Testcontainers).
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CORE="$ROOT/apps/core-api"
SRC="$CORE/src/main/java/com/divalhr/core/payroll"
BACKUP="$(mktemp -d)"
trap 'restore_all' EXIT INT TERM

declare -a TOUCHED=()

restore_all() {
  for f in "${TOUCHED[@]:-}"; do
    [ -n "$f" ] && [ -f "$BACKUP/$(basename "$f")" ] && cp "$BACKUP/$(basename "$f")" "$f"
  done
}

# mutate FILE OLD NEW : replaces exactly one occurrence, or fails.
mutate() {
  local file="$1" old="$2" new="$3"
  cp "$file" "$BACKUP/$(basename "$file")"
  TOUCHED+=("$file")
  python3 - "$file" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1:4]
text = open(path, encoding="utf-8").read()
if text.count(old) != 1:
    sys.exit("mutation anchor not found exactly once in " + path + ": " + old)
open(path, "w", encoding="utf-8").write(text.replace(old, new))
PY
}

survivors=0
run() {
  local number="$1" name="$2" tests="$3"
  shift 3
  if [ "${ONLY:-}" ] && [[ " $ONLY " != *" $number "* ]]; then return; fi
  echo "== mutation $number: $name"
  if ! "$@"; then echo "   cannot apply the mutation"; survivors=$((survivors + 1)); return; fi
  local args=()
  for t in $tests; do args+=(--tests "$t"); done
  local log; log="$(mktemp)"
  if (cd "$CORE" && ./gradlew --no-daemon test "${args[@]}" >"$log" 2>&1); then
    echo "   SURVIVED (tests still pass): $tests"
    survivors=$((survivors + 1))
  elif grep -qE '^[A-Za-z0-9_.$]+ > .* FAILED' "$log"; then
    echo "   KILLED by: $(grep -E '^[A-Za-z0-9_.$]+ > .* FAILED' "$log" | head -3 | tr '\n' ';')"
  else
    echo "   NOT A VALID KILL (no test failed; probably a compile error). See $log"
    survivors=$((survivors + 1))
    cp "$log" "${MUTATION_LOG_DIR:-/tmp}/mutation-$number-invalid.log" 2>/dev/null
  fi
  restore_all
  TOUCHED=()
}

ONLY="$*"

run 1 "version() ignores the tenant" "*JdbcCompensationRepositoryTenantTest" \
  mutate "$SRC/internal/JdbcCompensationRepository.java" \
  '" WHERE tenant_id = :tenant AND employee_id = :employee")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query(Integer.class)' \
  '" WHERE employee_id = :employee")
        .param("employee", employeeId)
        .query(Integer.class)'

run 2 "USD exponent 2 becomes 0" "*CompensationMoneyTest" \
  mutate "$SRC/domain/CompensationCurrency.java" 'USD(2, 10_000_000L)' 'USD(0, 10_000_000L)'

run 3 "a scheduled change leaves the previous interval overlapping" "*CompensationTimelineTest" \
  mutate "$SRC/domain/CompensationTimeline.java" \
  'latest.from(), from.minusDays(1)));
    created.add(new Interval(changeId, money, from, null));' \
  'latest.from(), from));
    created.add(new Interval(changeId, money, from, null));'

run 4 "an amount reaches the audit and event metadata" "*CompensationRecorderTest" \
  mutate "$SRC/application/CompensationRecorder.java" \
  'metadata.put("frequency", "MONTHLY");' \
  'metadata.put("frequency", "MONTHLY"); metadata.put("amount", recorded.sequence());'

run 5 "the preview quota is ten times too large" "*CompensationQuotasTest" \
  mutate "$SRC/application/CompensationQuotas.java" 'PREVIEWS_PER_HOUR = 60' 'PREVIEWS_PER_HOUR = 600'

echo
if [ "$survivors" -ne 0 ]; then
  echo "FAIL: $survivors mutation(s) survived or could not be applied"
  exit 1
fi
echo "PASS: every mutation was killed"
