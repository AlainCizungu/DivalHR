#!/usr/bin/env bash
# Mutation checks for payment destinations and payment batches (Issue #183, MVP-095A, ADR 0036).
#
# Each mutation changes one place in production code, runs the tests that must notice it and expects
# a FAILURE of at least one of those tests. A mutation whose tests still pass SURVIVED, which means
# the evidence is too weak; the script then exits non-zero. Sources are always restored, even on
# interruption.
#
# BASELINE: before anything is mutated, every focused test class runs once on the unmodified
# sources and each must report PASSED tests. A red or empty baseline aborts the script (exit 2): in
# MVP-094B a migration failure made every test red and mutations were reported KILLED falsely.
#
# Usage: scripts/dev/payroll-package-mutations.sh [mutation-number ...]   (default: all)
#   UNIT_ONLY=1    run only the pure unit classes (no Docker); a mutation that no unit class can
#                  see is reported NOT EVALUATED and the final exit code is 3, never a pass
#   CHECK_ONLY=1   only prove that every anchor exists exactly once and restores cleanly (no Gradle)
#
# 1 the destination is stored in plaintext (the sealed blob carries the account number)
# 2 the tenant predicate is removed from the destination read
# 3 the full account number is returned instead of the masked value
# 4 a stale destination is accepted (the preview digest is no longer compared)
# 5 the batch total is recalculated instead of copied from the approved net amounts
# 6 a partial batch is allowed (a missing destination is no longer a blocker)
# 7 the void guard is bypassed (a prepared batch no longer blocks a payroll void)
# 8 a cancelled batch can be cancelled again (never reactivated or re-cancelled)
# 9 CSV formula protection is removed
#
# Needs a working Gradle build of apps/core-api; the integration classes need Docker/Testcontainers
# and are written by the integration test worker (PayrollPackage*IntegrationTest, PayslipPdf*).
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

# mutate FILE OLD NEW : replaces exactly one occurrence, or fails. The first backup of a file is
# kept, so several mutations of one file restore to the original.
mutate() {
  local file="$1" old="$2" new="$3"
  [ -f "$BACKUP/$(basename "$file")" ] || cp "$file" "$BACKUP/$(basename "$file")"
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

UNIT="PaymentCipherTest PaymentRegisterTest PaymentBatchEvaluatorTest PaymentErrorCodesTest \
PaymentRecorderTest PayrollPackageArchitectureTest"
INTEGRATION="PaymentApiIntegrationTest"

# Gradle prints one "<class> > <method> FAILED|PASSED" line per test (build.gradle.kts testLogging).
FAILED_RE='^[A-Za-z0-9_.$]+ > .* FAILED'

gradle_test() {
  local log="$1"
  shift
  local args=()
  for t in "$@"; do args+=(--tests "*$t"); done
  (cd "$CORE" && ./gradlew --no-daemon cleanTest test "${args[@]}" >"$log" 2>&1)
}

survivors=0
skipped=0

baseline() {
  local classes=($UNIT)
  [ "${UNIT_ONLY:-}" ] || classes+=($INTEGRATION)
  echo "== baseline: ${#classes[@]} focused test classes on the unmodified sources"
  local log; log="$(mktemp)"
  local retry_log=""
  if ! gradle_test "$log" "${classes[@]}"; then
    # Under the load of one long run the database can answer a 503 *_TIMEOUT (observed in
    # MVP-094C: IMPORT_TIMEOUT, EMPLOYMENT_CHANGE_TIMEOUT). Failed classes get ONE rerun alone; a
    # real defect fails again and aborts. Only timeouts may be retried; any other failure aborts.
    local failed
    failed=$(grep -aE "$FAILED_RE" "$log" | sed -E 's/ >.*//' | sort -u)
    local nfailed ntimeouts
    nfailed=$(grep -cE "$FAILED_RE" "$log")
    ntimeouts=$(grep -E -A2 "$FAILED_RE" "$log" | grep -c "_TIMEOUT")
    if [ "$ntimeouts" -lt "$nfailed" ]; then
      echo "   BASELINE IS RED (not a timeout): no mutation can be judged. See $log"
      grep -E -A25 "$FAILED_RE" "$log" | head -120
      exit 2
    fi
    echo "   timeouts under load in: $(echo $failed); rerunning those classes alone once"
    retry_log="$(mktemp)"
    if ! gradle_test "$retry_log" $failed; then
      echo "   BASELINE IS RED after the rerun: no mutation can be judged. See $retry_log"
      grep -E -A25 "$FAILED_RE" "$retry_log" | head -120
      exit 2
    fi
  fi
  for c in "${classes[@]}"; do
    if ! grep -qE "^$c > .* PASSED" "$log" && { [ -z "$retry_log" ] || ! grep -qE "^$c > .* PASSED" "$retry_log"; }; then
      echo "   BASELINE RAN NO PASSING TEST OF $c (missing, skipped or not compiled). See $log"
      exit 2
    fi
  done
  echo "   baseline green"
}

run() {
  local number="$1" name="$2" unit_tests="$3" integration_tests="$4"
  shift 4
  if [ "${ONLY:-}" ] && [[ " $ONLY " != *" $number "* ]]; then return; fi
  echo "== mutation $number: $name"
  if ! "$@"; then
    echo "   cannot apply the mutation"
    survivors=$((survivors + 1))
    restore_all
    TOUCHED=()
    return
  fi
  if [ "${CHECK_ONLY:-}" ]; then
    echo "   anchors OK"
    restore_all
    TOUCHED=()
    return
  fi
  local tests="$unit_tests"
  [ "${UNIT_ONLY:-}" ] || tests="$unit_tests $integration_tests"
  if [ -z "${tests// /}" ]; then
    echo "   NOT EVALUATED: no unit class sees this mutation (run without UNIT_ONLY)"
    skipped=$((skipped + 1))
    restore_all
    TOUCHED=()
    return
  fi
  local log; log="$(mktemp)"
  # shellcheck disable=SC2086
  if gradle_test "$log" $tests; then
    echo "   SURVIVED (tests still pass): $tests"
    survivors=$((survivors + 1))
  elif grep -aqE "$FAILED_RE" "$log"; then
    echo "   KILLED by:"
    grep -aE "$FAILED_RE" "$log" | sed 's/^/     /' | head -12
    echo "   killer classes: $(grep -aE "$FAILED_RE" "$log" | sed -E 's/ > .*//; s/.*\.//' | sort -u | tr '\n' ' ')"
  else
    echo "   NOT A VALID KILL (no test failed; probably a compile error). See $log"
    survivors=$((survivors + 1))
    cp "$log" "${MUTATION_LOG_DIR:-/tmp}/mutation-$number-invalid.log" 2>/dev/null
  fi
  restore_all
  TOUCHED=()
}

ONLY="$*"

if [ -z "${CHECK_ONLY:-}" ]; then
  baseline
fi

ONLY="$*"

if [ -z "${CHECK_ONLY:-}" ]; then
  baseline
fi

mutate_plaintext() {
  mutate "$SRC/domain/PaymentCipher.java" \
    '          .put(sealed)' \
    '          .put(plaintext.getBytes(StandardCharsets.UTF_8))'
}

run 1 "the destination is stored in plaintext" \
  "PaymentCipherTest" \
  "PaymentApiIntegrationTest" \
  mutate_plaintext

run 2 "the tenant predicate is removed from the destination read" \
  "" \
  "PaymentApiIntegrationTest" \
  mutate "$SRC/internal/JdbcPaymentRepository.java" \
  '                + " FROM payroll.payment_destination d WHERE d.tenant_id = :tenant"
                + " AND d.employee_id = :employee ORDER BY d.sequence DESC LIMIT 1")' \
  '                + " FROM payroll.payment_destination d WHERE true"
                + " AND d.employee_id = :employee ORDER BY d.sequence DESC LIMIT 1")'

run 3 "the full account number is returned instead of the masked value" \
  "PaymentCipherTest PaymentBatchEvaluatorTest" \
  "PaymentApiIntegrationTest" \
  mutate "$SRC/domain/PaymentMasking.java" \
  '    return "****" + account.substring(account.length() - keep);' \
  '    return account;'

run 4 "a stale destination is accepted: the preview digest is not compared" \
  "" \
  "PaymentApiIntegrationTest" \
  mutate "$SRC/application/PaymentBatchService.java" \
  '        || !CompensationCalendar.onGrid(expiresAt)
        || !previewDigest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_PREVIEW_STALE, Map.of());
    }
    // 5.' \
  '        || !CompensationCalendar.onGrid(expiresAt)
        || false) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_PREVIEW_STALE, Map.of());
    }
    // 5.'

run 5 "the batch total is recalculated instead of copied from the approved net amounts" \
  "PaymentBatchEvaluatorTest" \
  "PaymentApiIntegrationTest" \
  mutate "$SRC/domain/PaymentBatchEvaluator.java" \
  '      total += slip.netMinor();' \
  '      total += slip.netMinor() - slip.netMinor() / 100;'

run 6 "a partial batch is allowed: a missing destination is not a blocker" \
  "PaymentBatchEvaluatorTest" \
  "PaymentApiIntegrationTest" \
  mutate "$SRC/domain/PaymentBatchEvaluator.java" \
  '        blockers.add(PaymentBatchBlocker.MISSING_DESTINATION);' \
  '        // mutated: no blocker'

run 7 "the void guard is bypassed: a prepared batch no longer blocks a void" \
  "" \
  "PaymentApiIntegrationTest" \
  mutate "$SRC/application/PreparedPaymentBatchGuard.java" \
  '    return payments.preparedExists(tenant, legalEntityId, packageId);' \
  '    return false;'

run 8 "a cancelled batch is treated as cancellable again" \
  "" \
  "PaymentApiIntegrationTest" \
  mutate "$SRC/application/PaymentBatchService.java" \
  '    // 1. Already cancelled: final, never reactivated.
    if (!"PREPARED".equals(row.status())) {' \
  '    // 1. Already cancelled: final, never reactivated.
    if (false) {'

run 9 "CSV formula protection is removed" \
  "PaymentRegisterTest" \
  "" \
  mutate "$SRC/domain/PaymentRegister.java" \
  '    if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {' \
  '    if (false) {'

echo
if [ "${CHECK_ONLY:-}" ]; then
  [ "$survivors" -eq 0 ] && { echo "PASS: every anchor exists exactly once"; exit 0; }
  echo "FAIL: $survivors anchor(s) not applicable"
  exit 1
fi
if [ "$survivors" -ne 0 ]; then
  echo "FAIL: $survivors mutation(s) survived or could not be applied"
  exit 1
fi
if [ "$skipped" -ne 0 ]; then
  echo "PARTIAL: $skipped mutation(s) not evaluated (UNIT_ONLY); the unit-visible ones were killed"
  exit 3
fi
echo "PASS: every mutation was killed"
