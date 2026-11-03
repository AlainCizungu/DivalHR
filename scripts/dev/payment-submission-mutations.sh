#!/usr/bin/env bash
# Mutation checks for provider submission of a payment batch (Issue #187, MVP-095B, ADR 0037).
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
# 1 the audit/event allow-list is removed (a destination, amount or signature key could be persisted)
# 2 the tenant predicate is removed from the submission-by-batch read
# 3 the signature check of the connector is bypassed (an unsigned answer is believed)
# 4 the total is recalculated instead of copied from the immutable batch lines
# 5 a partial submission is allowed (an unreadable destination no longer blocks the whole batch)
# 6 the request ID is regenerated for the connector call (duplicate prevention)
# 7 a timeout after the write is classified as an accepted submission
# 8 a rejection is retried (the rejection is no longer final)
# 9 the cancel guard is bypassed (a submitted batch can be cancelled)
# 10 the void guard is bypassed (the cancel preview no longer reports ALREADY_SUBMITTED)
# 11 payment submission events are published to webhooks (the fan-out allow-list names them)
#
# Needs a working Gradle build of apps/core-api; the integration classes need Docker/Testcontainers.
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

UNIT="PaymentSubmissionDomainTest HttpsPaymentProviderGatewayTest JdbcPaymentSubmissionRepositoryTenantTest \
PaymentErrorCodesTest PaymentEventsNotDeliveredTest PayrollPackageArchitectureTest"
INTEGRATION="PaymentSubmissionApiIntegrationTest"

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


run 1 "the audit and event allow-list is removed" \
  "PaymentSubmissionRecorderTest" \
  "" \
  mutate "$SRC/application/PaymentSubmissionRecorder.java" \
  '      if (!ALLOWED.contains(key)) {' \
  '      if (false) {'

run 2 "the tenant predicate is removed from the submission-by-batch read" \
  "JdbcPaymentSubmissionRepositoryTenantTest" \
  "PaymentSubmissionApiIntegrationTest" \
  mutate "$SRC/internal/JdbcPaymentSubmissionRepository.java" \
  '                + " FROM payroll.payment_submission s WHERE s.tenant_id = :tenant"
                + " AND s.legal_entity_id = :entity AND s.batch_id = :batch")' \
  '                + " FROM payroll.payment_submission s WHERE true"
                + " AND s.legal_entity_id = :entity AND s.batch_id = :batch")'

run 3 "the response signature check is bypassed" \
  "HttpsPaymentProviderGatewayTest" \
  "" \
  mutate "$SRC/internal/HttpsPaymentProviderGateway.java" \
  '    if (!keys.signer().verify(requestId, response.body(), response.signature())) {' \
  '    if (false) {'

run 4 "the total is recalculated instead of copied from the batch" \
  "PaymentSubmissionDomainTest" \
  "PaymentSubmissionApiIntegrationTest" \
  mutate "$SRC/domain/PaymentSubmissionPayload.java" \
  '    if (sum != totalMinor || lines.isEmpty()) {' \
  '    totalMinor = sum;
    if (sum != totalMinor || lines.isEmpty()) {'

run 5 "a partial submission is allowed: an unreadable destination no longer blocks the whole batch" \
  "" \
  "PaymentSubmissionApiIntegrationTest" \
  mutate "$SRC/application/PaymentSubmissionService.java" \
  '        blockers.add(PaymentSubmissionBlocker.DESTINATION_UNREADABLE);' \
  '        // mutated: no blocker'

run 6 "the request ID is regenerated for the connector call" \
  "" \
  "PaymentSubmissionApiIntegrationTest" \
  mutate "$SRC/application/PaymentSubmissionWorker.java" \
  '            result = gateway.send(profile, claim.requestId(), claim.keyVersion(), signature, body);' \
  '            result = gateway.send(profile, java.util.UUID.randomUUID(), claim.keyVersion(), signature, body);'

run 7 "a timeout after the write is classified as accepted" \
  "HttpsPaymentProviderGatewayTest" \
  "" \
  mutate "$SRC/internal/HttpsPaymentProviderGateway.java" \
  '      throw new Stop(
          written
              ? PaymentSubmissionOutcome.UNKNOWN_TIMEOUT
              : PaymentSubmissionOutcome.TRANSIENT_TLS);' \
  '      throw new Stop(
          written
              ? PaymentSubmissionOutcome.ACCEPTED
              : PaymentSubmissionOutcome.TRANSIENT_TLS);'

run 8 "a rejection is retried: the rejection kind is treated as transient" \
  "PaymentSubmissionDomainTest" \
  "PaymentSubmissionApiIntegrationTest" \
  mutate "$SRC/domain/PaymentSubmissionOutcome.java" \
  '  REJECTED_SIGNATURE(Kind.REJECTED),' \
  '  REJECTED_SIGNATURE(Kind.TRANSIENT),'

run 9 "the cancel guard is bypassed: a submitted batch can be cancelled" \
  "" \
  "PaymentSubmissionApiIntegrationTest" \
  mutate "$SRC/application/PaymentBatchService.java" \
  '    if (submissions.existsForBatch(tenant, batchId)) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_ALREADY_SUBMITTED, Map.of());' \
  '    if (false) {
      throw new ApiException(ErrorCode.PAYMENT_BATCH_ALREADY_SUBMITTED, Map.of());'

run 10 "the cancel preview no longer reports ALREADY_SUBMITTED" \
  "" \
  "PaymentSubmissionApiIntegrationTest" \
  mutate "$SRC/application/PaymentBatchService.java" \
  '                if (submissions.existsForBatch(tenant, row.id())) {
                  blockers.add(PaymentBatchCancelBlocker.ALREADY_SUBMITTED);' \
  '                if (false) {
                  blockers.add(PaymentBatchCancelBlocker.ALREADY_SUBMITTED);'

run 11 "submission events are published to webhooks: the recorder type is renamed to a public type" \
  "PaymentEventsNotDeliveredTest" \
  "PaymentSubmissionEventsNotDeliveredIntegrationTest" \
  mutate "$SRC/application/PaymentSubmissionRecorder.java" \
  '"payroll.payment-submission.created.v1"' \
  '"payroll.employee.created.v1"'

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
