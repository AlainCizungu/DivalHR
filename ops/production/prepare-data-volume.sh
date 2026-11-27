#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-8): mounts the encrypted EBS data volume at /srv/divalhr-prod. It runs the
# test environment's proven disk-safety logic (whole-disk, serial = volume ID, no signature, never the
# root disk, idempotent) with the PRODUCTION data root, label and fstab entry.
#
#   sudo ops/production/prepare-data-volume.sh --volume-id <vol-...> [--format]
#   sudo ops/production/prepare-data-volume.sh --confirm-encrypted
#
# --confirm-encrypted is run only after the operator has seen, in AWS, that this very volume is
# encrypted with the environment key (aws ec2 describe-volumes; docs/OPS-PRODUCTION.md section 3). It
# records the serial in the marker every production script checks before touching data.
set -u
. "$(dirname "$0")/lib.sh"
prod_require_root
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
sed -e 's/^LABEL=divalhr-test$/LABEL=divalhr-prod/' \
    -e 's#ops/aws/hr-dev-aws.sh evidence#aws ec2 describe-volumes (docs/OPS-PRODUCTION.md section 3)#g' \
    -e 's#ops/aws/hr-dev-aws.sh data-volume#the Terraform output#g' \
    "$PROD_OPS/../hr-dev/prepare-data-volume.sh" > "$tmp/prepare-data-volume.sh"
grep -qx 'LABEL=divalhr-prod' "$tmp/prepare-data-volume.sh" || prod_die "the volume label could not be set to the production label"
# The copy lives in a mirror of ops/ so its relative sourcing of lib.sh and the host lock still resolves.
mkdir -p "$tmp/ops/hr-dev"
mv "$tmp/prepare-data-volume.sh" "$tmp/ops/hr-dev/prepare-data-volume.sh"
ln -s "$PROD_OPS/../hr-dev/lib.sh" "$tmp/ops/hr-dev/lib.sh"
ln -s "$PROD_OPS/../host" "$tmp/ops/host"
bash "$tmp/ops/hr-dev/prepare-data-volume.sh" "$@"
