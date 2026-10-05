#!/bin/bash
# OPS-001 (A65-1, A65-4): the AWS side of the test environment, run by the owner on the Mac with
# the AWS CLI already configured. Every output is REDACTED for posting on GitHub: resource IDs keep
# only their last four characters, the account number and ARNs are hidden, and every source
# address except 0.0.0.0/0 and ::/0 is shown as <restricted>.
#
#   bash ops/aws/hr-dev-aws.sh evidence            read-only report with PASS/FAIL lines
#   bash ops/aws/hr-dev-aws.sh encryption-default --yes   enable EBS encryption by default (region)
#   bash ops/aws/hr-dev-aws.sh data-volume --yes          create + attach the encrypted gp3 data volume
#   bash ops/aws/hr-dev-aws.sh snapshots --yes            DLM policy: daily snapshots of that volume
#   bash ops/aws/hr-dev-aws.sh open-web --yes             security group: TCP 80/443 from anywhere
#
# Changing commands run only with --yes and only during the approved deployment (Issue #65);
# nothing here touches the instance's operating system. Compatible with macOS bash 3.2.
set -u -o pipefail
export AWS_PAGER=""
HOST="${HR_DEV_HOST:-hr-dev.dival.ai}"
CMD="${1:-evidence}"
YES="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
TAG_KEY="divalhr-backup" TAG_VALUE="hr-dev-data"
VOLUME_SIZE="${HR_DEV_DATA_VOLUME_GIB:-20}"
FAIL=0

die() { echo "STOP: $*" >&2; exit 1; }
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; FAIL=1; }
redact() {
  perl -pe '
    s{arn:aws[a-z-]*:\S+}{<arn>}g;
    s{\b\d{12}\b}{<account>}g;
    s{\b(i|vol|snap|sg|sgr|policy|eni|subnet|vpc|ami)-[0-9a-f]{4,}([0-9a-f]{4})\b}{$1-…$2}g;
    s{\b(\d{1,3}(?:\.\d{1,3}){3}/\d{1,2})\b}{$1 eq "0.0.0.0/0" ? $1 : "<restricted>"}ge;
    s{(?<![\w:])([0-9a-f]*:[0-9a-f:]*/\d{1,3})\b}{$1 eq "::/0" ? $1 : "<restricted>"}gie;
  '
}
need_yes() { [ "$YES" = "--yes" ] || die "$CMD changes AWS resources; rerun with --yes during the approved deployment"; }

command -v aws >/dev/null 2>&1 || die "the AWS CLI is not installed"
aws sts get-caller-identity >/dev/null 2>&1 || die "the AWS CLI is not configured"
IP=$(dig +short "$HOST" | tail -1)
[ -n "$IP" ] || die "cannot resolve $HOST"
IID=$(aws ec2 describe-instances --filters "Name=ip-address,Values=$IP" \
  --query 'Reservations[].Instances[].InstanceId' --output text)
[ -n "$IID" ] && [ "$IID" != None ] || die "no instance with the address of $HOST"
AZ=$(aws ec2 describe-instances --instance-ids "$IID" --query 'Reservations[0].Instances[0].Placement.AvailabilityZone' --output text)
SGS=$(aws ec2 describe-instances --instance-ids "$IID" --query 'Reservations[0].Instances[0].SecurityGroups[].GroupId' --output text)
data_volume() {
  aws ec2 describe-volumes --filters "Name=tag:$TAG_KEY,Values=$TAG_VALUE" "Name=availability-zone,Values=$AZ" \
    --query 'Volumes[?State!=`deleting`].VolumeId' --output text
}

evidence() {
  echo "== OPS-001 AWS evidence for $HOST ($(date -u +%Y-%m-%dT%H:%M:%SZ)); identifiers redacted"
  echo "instance: $IID, $(aws ec2 describe-instances --instance-ids "$IID" \
    --query 'Reservations[0].Instances[0].[InstanceType,State.Name]' --output text | tr '\t' ' ')" | redact

  echo "== EBS (A65-1)"
  default=$(aws ec2 get-ebs-encryption-by-default --query EbsEncryptionByDefault --output text)
  [ "$default" = True ] && pass "EBS encryption by default is enabled in this region" \
    || fail "EBS encryption by default is $default"
  aws ec2 describe-volumes --filters "Name=attachment.instance-id,Values=$IID" \
    --query 'Volumes[].[VolumeId,Attachments[0].Device,Size,VolumeType,Encrypted,Tags[?Key==`'"$TAG_KEY"'`]|[0].Value]' \
    --output text | while read -r id dev size type enc tag; do
      echo "volume $id $dev ${size}GiB $type encrypted=$enc ${tag:+tag=$tag}"
    done | redact
  vol=$(data_volume)
  if [ -z "$vol" ] || [ "$vol" = None ]; then
    fail "no data volume tagged $TAG_KEY=$TAG_VALUE"
  else
    enc=$(aws ec2 describe-volumes --volume-ids "$vol" --query 'Volumes[0].Encrypted' --output text)
    att=$(aws ec2 describe-volumes --volume-ids "$vol" --query 'Volumes[0].Attachments[0].InstanceId' --output text)
    [ "$enc" = True ] && pass "data volume is encrypted" || fail "data volume encrypted=$enc"
    [ "$att" = "$IID" ] && pass "data volume is attached to the instance" || fail "data volume is not attached"
    snaps=$(aws ec2 describe-snapshots --owner-ids self --filters "Name=volume-id,Values=$vol" \
      --query 'Snapshots[].[StartTime,State,Encrypted]' --output text)
    total=$(printf '%s\n' "$snaps" | grep -c . || true)
    plain=$(printf '%s\n' "$snaps" | awk '$3 != "True"' | grep -c . || true)
    echo "data volume snapshots: $total (newest: $(printf '%s\n' "$snaps" | sort | tail -1 | awk '{print $1, $2}'))"
    [ "$total" -gt 0 ] && [ "$plain" = 0 ] && pass "all $total snapshot(s) of the data volume are encrypted" \
      || { [ "$total" = 0 ] && echo "INFO no snapshot yet (the policy runs daily at 03:00 UTC)" \
           || fail "$plain unencrypted snapshot(s)"; }
  fi
  policies=$(aws dlm get-lifecycle-policies --target-tags "$TAG_KEY=$TAG_VALUE" \
    --query 'Policies[].[PolicyId,State]' --output text 2>/dev/null)
  if printf '%s\n' "$policies" | grep -q ENABLED; then
    pass "snapshot lifecycle policy enabled for $TAG_KEY=$TAG_VALUE"
  else
    fail "no enabled snapshot lifecycle policy for $TAG_KEY=$TAG_VALUE"
  fi
  printf '%s\n' "$policies" | sed 's/^/policy /' | redact

  echo "== security groups (A65-4): final inbound rules, IPv4 and IPv6"
  for sg in $SGS; do
    aws ec2 describe-security-groups --group-ids "$sg" --output json \
      --query 'SecurityGroups[0].IpPermissions' | python3 -c '
import json, sys
bad = False
for p in json.load(sys.stdin):
    proto, lo, hi = p.get("IpProtocol"), p.get("FromPort"), p.get("ToPort")
    ports = "all" if proto == "-1" else (str(lo) if lo == hi else f"{lo}-{hi}")
    sources = [r["CidrIp"] for r in p.get("IpRanges", [])] + [r["CidrIpv6"] for r in p.get("Ipv6Ranges", [])]
    sources += ["group:" + g["GroupId"] for g in p.get("UserIdGroupPairs", [])]
    sources += ["prefix-list:" + g["PrefixListId"] for g in p.get("PrefixListIds", [])]
    for s in sources:
        family = "IPv6" if ":" in s and not s.startswith(("group:", "prefix-list:")) else "IPv4"
        public = s in ("0.0.0.0/0", "::/0")
        print(f"rule {family} {proto} {ports} from {s}")
        if public and not (proto == "tcp" and lo == hi and lo in (80, 443)):
            bad = True; print(f"FAIL port {ports} is open to {s}")
        if not public and not (proto == "tcp" and lo == hi == 22):
            bad = True; print(f"FAIL port {ports} is open to a non-public source")
sys.exit(1 if bad else 0)' | redact || FAIL=1
  done
  [ "$FAIL" = 0 ] && echo "ALL AWS CHECKS PASSED" || echo "AWS CHECKS: see FAIL lines"
  return "$FAIL"
}

case "$CMD" in
  evidence) evidence; exit $? ;;
  encryption-default)
    need_yes
    aws ec2 enable-ebs-encryption-by-default --query EbsEncryptionByDefault --output text | sed 's/^/encryption by default: /'
    ;;
  data-volume)
    need_yes
    [ "$(aws ec2 get-ebs-encryption-by-default --query EbsEncryptionByDefault --output text)" = True ] \
      || die "enable encryption by default first (encryption-default --yes)"
    vol=$(data_volume)
    if [ -z "$vol" ] || [ "$vol" = None ]; then
      vol=$(aws ec2 create-volume --availability-zone "$AZ" --size "$VOLUME_SIZE" --volume-type gp3 --encrypted \
        --tag-specifications "ResourceType=volume,Tags=[{Key=Name,Value=divalhr-test-data},{Key=$TAG_KEY,Value=$TAG_VALUE}]" \
        --query VolumeId --output text) || die "create-volume failed"
      aws ec2 wait volume-available --volume-ids "$vol" || die "the volume did not become available"
      echo "created $vol (${VOLUME_SIZE} GiB gp3, encrypted)" | redact
    fi
    att=$(aws ec2 describe-volumes --volume-ids "$vol" --query 'Volumes[0].Attachments[0].InstanceId' --output text)
    if [ "$att" != "$IID" ]; then
      aws ec2 attach-volume --volume-id "$vol" --instance-id "$IID" --device /dev/sdf >/dev/null || die "attach failed"
      aws ec2 wait volume-in-use --volume-ids "$vol" || die "the volume did not attach"
    fi
    echo "attached; on the instance run: sudo ops/hr-dev/prepare-data-volume.sh (it finds the one blank, unmounted EBS volume)"
    ;;
  snapshots)
    need_yes
    role=$(aws iam get-role --role-name AWSDataLifecycleManagerDefaultRole --query Role.Arn --output text 2>/dev/null) \
      || role=$(aws dlm create-default-role --resource-type snapshot >/dev/null 2>&1 && sleep 10 && \
        aws iam get-role --role-name AWSDataLifecycleManagerDefaultRole --query Role.Arn --output text) \
      || die "cannot find or create the DLM default role"
    if aws dlm get-lifecycle-policies --target-tags "$TAG_KEY=$TAG_VALUE" --query 'Policies[].PolicyId' --output text | grep -q .; then
      echo "a policy for $TAG_KEY=$TAG_VALUE already exists"
    else
      aws dlm create-lifecycle-policy --description "DivalHR hr-dev data volume, daily, keep 7" --state ENABLED \
        --execution-role-arn "$role" --policy-details "file://$HERE/../hr-dev/aws/dlm-policy.json" \
        --query PolicyId --output text | sed 's/^/created policy /' | redact
    fi
    ;;
  open-web)
    need_yes
    [ "$(echo "$SGS" | wc -w | tr -d ' ')" = 1 ] || die "expected exactly one security group"
    for port in 80 443; do
      aws ec2 authorize-security-group-ingress --group-id "$SGS" --ip-permissions \
        "IpProtocol=tcp,FromPort=$port,ToPort=$port,IpRanges=[{CidrIp=0.0.0.0/0,Description=hr-dev-web}],Ipv6Ranges=[{CidrIpv6=::/0,Description=hr-dev-web}]" \
        >/dev/null 2>&1 && echo "opened TCP $port (IPv4 and IPv6)" || echo "TCP $port: already open or refused (see evidence)"
    done
    ;;
  *) die "usage: hr-dev-aws.sh evidence | encryption-default --yes | data-volume --yes | snapshots --yes | open-web --yes" ;;
esac
