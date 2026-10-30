#!/usr/bin/env bash
# Mutation checks for the DRC statutory payroll preview (Issue #179, MVP-094B, ADR 0034).
#
# Each mutation changes one place in production code, runs the tests that must notice it and expects
# a FAILURE. A mutation whose tests still pass SURVIVED, which means the evidence is too weak; the
# script then exits non-zero. Sources are always restored, even on interruption.
#
# Usage: scripts/dev/statutory-mutations.sh [mutation-number ...]   (default: all)
# 1 employee CNSS no longer deducted        2 employer contribution deducted from the employee
# 3 wrong IRPP bracket rate                 4 IRPP cap removed
# 5 early rounding of the IRPP              6 a DRAFT pack is accepted
# 7 source digest drift ignored             8 chainVersion() ignores the tenant
# 9 NO_CHANGE reported as stale             10 profile-change-in-month check removed
# Mutations 1 to 5 and 10 are killed by the pure domain tests (StatutoryEngineTest,
# StatutoryCalculatorTest, StatutoryProfileTimelineTest) and by StatutoryApiIntegrationTest (Docker).
# Mutations 6, 7 and 9 only by StatutoryApiIntegrationTest (Docker), mutation 8 by
# JdbcStatutoryRepositoryTenantTest.
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

DOMAIN_TESTS="*StatutoryEngineTest *StatutoryCalculatorTest *StatutoryApiIntegrationTest"

run 1 "the employee CNSS contribution is no longer deducted" \
  "$DOMAIN_TESTS" \
  mutate "$SRC/domain/StatutoryEngine.java" \
  'if (component.party() == Party.EMPLOYEE) {
        deductions = Math.addExact(deductions, component.amountMinor());' \
  'if (component.party() == Party.EMPLOYEE && component.category() != Category.SOCIAL_CONTRIBUTION) {
        deductions = Math.addExact(deductions, component.amountMinor());'

run 2 "the employer contribution is deducted from the employee" \
  "$DOMAIN_TESTS" \
  mutate "$SRC/domain/StatutoryEngine.java" \
  'if (component.party() == Party.EMPLOYEE) {
        deductions = Math.addExact(deductions, component.amountMinor());' \
  'if (true) {
        deductions = Math.addExact(deductions, component.amountMinor());'

run 3 "a wrong IRPP bracket rate" \
  "$DOMAIN_TESTS" \
  mutate "$SRC/domain/StatutoryEngine.java" \
  'numerator = numerator.add(width.multiply(BigInteger.valueOf(bracket.rateBp())));' \
  'numerator = numerator.add(width.multiply(BigInteger.valueOf(bracket.rateBp() + 100)));'

run 4 "the IRPP cap is removed" \
  "$DOMAIN_TESTS" \
  mutate "$SRC/domain/StatutoryEngine.java" \
  'capApplied = numerator.compareTo(base.multiply(capBp).multiply(periods)) > 0;' \
  'capApplied = false;'

run 5 "early rounding: the annual IRPP is rounded before it is divided by the periods" \
  "$DOMAIN_TESTS" \
  mutate "$SRC/domain/StatutoryEngine.java" \
  'amount = round(numerator, UNIT.multiply(periods), rule.rounding());' \
  'amount = round(round(numerator, UNIT, rule.rounding()), periods, rule.rounding());'

mutate_draft_pack() {
  mutate "$SRC/domain/StatutoryCalculator.java" \
    'if (pack.status() != PackStatus.VERIFIED || pack.rules() == null) {' \
    'if (pack.status() == PackStatus.RETIRED || pack.rules() == null) {' &&
  mutate "$SRC/application/StatutoryEvaluator.java" \
    'return "VERIFIED".equals(resolved.pack().status())' \
    'return !"RETIRED".equals(resolved.pack().status())'
}

run 6 "a DRAFT pack is accepted" \
  "*StatutoryCalculatorTest *StatutoryApiIntegrationTest" \
  mutate_draft_pack

run 7 "the source digest drift is ignored: a stale preview is accepted" \
  "*StatutoryApiIntegrationTest" \
  mutate "$SRC/application/StatutoryCalculationService.java" \
  '|| !evaluation.sourceDigest().equals(command.sourceRunDigest())' \
  '|| false'

run 8 "chainVersion() ignores the tenant" "*JdbcStatutoryRepositoryTenantTest" \
  mutate "$SRC/internal/JdbcStatutoryRepository.java" \
  '" WHERE tenant_id = :tenant AND run_id = :run AND pack_id = :pack")' \
  '" WHERE true AND run_id = :run AND pack_id = :pack")'

run 9 "an identical draft is reported as a stale preview instead of no change" \
  "*StatutoryApiIntegrationTest" \
  mutate "$SRC/application/StatutoryCalculationService.java" \
  'throw new ApiException(ErrorCode.STATUTORY_CALCULATION_NO_CHANGE, Map.of());' \
  'throw new ApiException(ErrorCode.STATUTORY_PREVIEW_STALE, Map.of());'

run 10 "a profile change inside the month no longer blocks the line" \
  "*StatutoryProfileTimelineTest *StatutoryCalculatorTest *StatutoryApiIntegrationTest" \
  mutate "$SRC/domain/StatutoryProfileTimeline.java" \
  'if (changes) {
      blockers.add(StatutoryLineBlocker.PROFILE_CHANGES_IN_MONTH);' \
  'if (false) {
      blockers.add(StatutoryLineBlocker.PROFILE_CHANGES_IN_MONTH);'

echo
if [ "$survivors" -ne 0 ]; then
  echo "FAIL: $survivors mutation(s) survived or could not be applied"
  exit 1
fi
echo "PASS: every mutation was killed"
