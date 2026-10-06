#!/usr/bin/env bash
# Fake AWS CLI (and dig) for the hr-dev-aws.sh tests (R66-3). Answers from environment variables;
# every changing call is appended to $FAKE_LOG. FAKE_WEB_SG: an existing dedicated web group;
# FAKE_SHARED=1: that group is also attached to another instance's interface.
IID=i-0123456789abc0001
case "$(basename "$0")" in
  dig) echo 203.0.113.10; exit 0 ;;
esac
args="$*"
case "$args" in
  "sts get-caller-identity"*) echo '{}' ;;
  *"describe-instances --filters Name=ip-address"*) echo "$IID" ;;
  *"Placement.AvailabilityZone"*) echo us-east-1a ;;
  *"SecurityGroups[].GroupId"*"describe-instances"*|*"describe-instances"*"SecurityGroups[].GroupId"*) echo sg-0123456789abc0ssh ;;
  *"describe-instances"*"VpcId"*) echo vpc-0123456789abc0001 ;;
  *"describe-instances"*"NetworkInterfaces[].NetworkInterfaceId"*) echo eni-0123456789abc0001 ;;
  *"describe-security-groups --filters"*) echo "${FAKE_WEB_SG:-}" ;;
  *"create-security-group"*) echo "create-security-group" >> "$FAKE_LOG"; echo sg-0123456789abc0web ;;
  *"describe-network-interfaces --filters Name=group-id"*)
    printf 'eni-0123456789abc0001\t%s\n' "$IID"
    [ "${FAKE_SHARED:-}" = 1 ] && printf 'eni-0123456789abc0999\ti-0123456789abc0999\n'
    ;;
  *"describe-network-interfaces --network-interface-ids"*) echo sg-0123456789abc0ssh ;;
  *"authorize-security-group-ingress"*) echo "authorize $args" >> "$FAKE_LOG" ;;
  *"modify-network-interface-attribute"*) echo "modify $args" >> "$FAKE_LOG" ;;
  *) echo "fake aws: unexpected call: $args" >> "$FAKE_LOG"; exit 1 ;;
esac
