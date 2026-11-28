#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-12): the operational launch lock of the production environment.
#
#   ops/production/launch-lock.sh status           prints the lock, the e-mail prerequisite and the onboarding rules
#   ops/production/launch-lock.sh sync             read-only mirror of the SSM value into the host copy
#   ops/production/launch-lock.sh require-closed   exit 0 only while the lock is CLOSED
#
# The lock is CLOSED by default and fail closed: anything but the exact word OPEN reads as CLOSED.
# Its host copy is $PROD_DATA/state/launch-lock; the authoritative value is the SSM parameter
# /divalhr/production/launch-lock, which only the founder-approved lift policy can change. THERE IS NO
# "lift" COMMAND HERE: lifting is a recorded founder authorization after LAUNCH-003, not part of
# LAUNCH-002. While the lock is closed: no real organization, no real employee invitation, no real
# data import, no statutory pack, no retention policy, no real provider or partner egress, no real
# payment, credit, insurance or telecom workflow. Prints no secret and no personal value.
set -u
. "$(dirname "$0")/lib.sh"

status() {
  local lock email
  lock="$(prod_launch_lock)"
  if [ -s "$PROD_DATA/config/mail.env" ] && grep -q '^PROD_MAIL_HOST=.' "$PROD_DATA/config/mail.env" \
    && grep -q '^PROD_MAIL_FROM=.' "$PROD_DATA/config/mail.env"; then email=CONFIGURED; else email=NOT_CONFIGURED; fi
  printf 'launch-lock: %s\n' "$lock"
  printf 'email: %s\n' "$email"
  if [ "$lock" = CLOSED ]; then
    printf 'customer-onboarding: REFUSED\n'
    printf 'real-organizations: NOT_ALLOWED\nreal-employees: NOT_ALLOWED\nreal-data-import: NOT_ALLOWED\n'
    printf 'statutory-pack: NOT_ACTIVATED\nretention-policy: NOT_ACTIVATED\npartner-egress: DISABLED\nreal-payments: DISABLED\n'
  else
    printf 'customer-onboarding: OPEN_BY_FOUNDER_AUTHORIZATION\n'
  fi
  [ "$email" = CONFIGURED ] || [ "$lock" = CLOSED ]
}

case "${1:-status}" in
  status) status ;;
  sync)
    # Read-only mirror of the authoritative SSM value into the host copy. Anything unreadable or
    # unexpected becomes CLOSED, so a failure here can only keep the lock closed. Never writes SSM.
    prod_require_root
    region="$(prod_config region)"
    v="$("${PROD_AWS:-aws}" ssm get-parameter --region "$region" --name /divalhr/production/launch-lock \
      --query Parameter.Value --output text 2>/dev/null)" || v=CLOSED
    [ "$v" = OPEN ] || v=CLOSED
    mkdir -p "$PROD_DATA/state"; printf '%s\n' "$v" > "$PROD_DATA/state/launch-lock"
    printf 'launch-lock (host copy): %s\n' "$v" ;;
  require-closed) [ "$(prod_launch_lock)" = CLOSED ] || prod_die "the launch lock is OPEN" ; printf 'launch-lock: CLOSED\n' ;;
  *) prod_die "usage: launch-lock.sh status | sync | require-closed (lifting the lock is not part of LAUNCH-002)" ;;
esac
