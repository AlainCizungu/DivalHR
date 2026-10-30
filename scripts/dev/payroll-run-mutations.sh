#!/usr/bin/env bash
# Mutation checks for the monthly payroll run backend (Issue #177, MVP-094A).
#
# Each mutation changes one line of production code, runs the tests that must notice it and expects
# a FAILURE. A mutation whose tests still pass SURVIVED, which means the evidence is too weak; the
# script then exits non-zero. Sources are always restored, even on interruption.
#
# Usage: scripts/dev/payroll-run-mutations.sh [mutation-number ...]   (default: all)
# Mutations 1 to 3 are killed by PayrollCalculatorTest (and by the API integration test, which needs
# Docker), mutation 4 only by PayrollRunApiIntegrationTest, mutation 5 by JdbcPayrollRunRepositoryTenantTest.
# Mutations 6 and 7 (R178-1, currency rules) are killed by PayrollCalculatorTest and the API test;
# mutations 8 and 9 (R178-1, NO_CHANGE) by PayrollRunApiIntegrationTest.
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

run 1 "hours-style scaling: a full month is scaled instead of exact" \
  "*PayrollCalculatorTest *PayrollRunApiIntegrationTest" \
  mutate "$SRC/domain/PayrollCalculator.java" \
  'if (eligibleDays == daysInMonth) {
      return monthlyMinor;' \
  'if (eligibleDays == daysInMonth) {
      return monthlyMinor * 8 / 9;'

run 2 "wrong denominator: calendar days divide by 30" \
  "*PayrollCalculatorTest *PayrollRunApiIntegrationTest" \
  mutate "$SRC/domain/PayrollCalculator.java" \
  'monthly.multiply(BigInteger.valueOf(eligibleDays)),
                  BigInteger.valueOf(daysInMonth))' \
  'monthly.multiply(BigInteger.valueOf(eligibleDays)),
                  BigInteger.valueOf(30))'

run 3 "early rounding: the daily rate is rounded before it is multiplied" \
  "*PayrollCalculatorTest *PayrollRunApiIntegrationTest" \
  mutate "$SRC/domain/PayrollCalculator.java" \
  'roundHalfUp(
                  monthly.multiply(BigInteger.valueOf(eligibleDays)),
                  BigInteger.valueOf(daysInMonth))
              .longValueExact();' \
  'roundHalfUp(monthly, BigInteger.valueOf(daysInMonth))
              .multiply(BigInteger.valueOf(eligibleDays))
              .longValueExact();'

run 4 "the source-drift check is removed: a stale preview is accepted" \
  "*PayrollRunApiIntegrationTest" \
  mutate "$SRC/application/PayrollRunService.java" \
  '|| !computation.inputDigest().equals(command.inputDigest())
        || !previewDigest.equals(command.previewDigest())) {' \
  '|| false) {'

run 5 "chainVersion() ignores the tenant" "*JdbcPayrollRunRepositoryTenantTest" \
  mutate "$SRC/internal/JdbcPayrollRunRepository.java" \
  'FROM payroll.pay_run WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity AND period_start = :period"' \
  'FROM payroll.pay_run WHERE true"
                + " AND legal_entity_id = :entity AND period_start = :period"'

run 6 "a currency mismatch blocks an employee paid only in the other currency again" \
  "*PayrollCalculatorTest *PayrollRunApiIntegrationTest" \
  mutate "$SRC/domain/PayrollCalculator.java" \
  'if ((here & coveredRun) != 0 && (here & coveredAny & ~coveredRun) != 0) {' \
  'if ((here & coveredAny & ~coveredRun) != 0) {'

run 7 "an employee who switches currency inside the month no longer blocks" \
  "*PayrollCalculatorTest *PayrollRunApiIntegrationTest" \
  mutate "$SRC/domain/PayrollCalculator.java" \
  'if ((here & coveredRun) != 0 && (here & coveredAny & ~coveredRun) != 0) {' \
  'if ((here & coveredRun) == 0 && (here & coveredAny & ~coveredRun) != 0) {'

run 8 "an identical recalculation is reported as a stale preview again" \
  "*PayrollRunApiIntegrationTest" \
  mutate "$SRC/application/PayrollRunService.java" \
  'throw new ApiException(ErrorCode.PAYROLL_RUN_NO_CHANGE, Map.of());' \
  'throw new ApiException(ErrorCode.PAYROLL_RUN_PREVIEW_STALE, Map.of());'

run 9 "the preview of an identical draft says READY" \
  "*PayrollRunApiIntegrationTest" \
  mutate "$SRC/application/PayrollRunService.java" \
  'String status = blocked ? "BLOCKED" : identical ? "NO_CHANGE" : "READY";' \
  'String status = blocked ? "BLOCKED" : "READY";'

echo
if [ "$survivors" -ne 0 ]; then
  echo "FAIL: $survivors mutation(s) survived or could not be applied"
  exit 1
fi
echo "PASS: every mutation was killed"
