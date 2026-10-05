#!/usr/bin/env bash
# OPS-001 (A65-1): mounts the encrypted EBS data volume at /srv/divalhr-test.
#
#   sudo ops/hr-dev/prepare-data-volume.sh [--device /dev/nvmeXn1] [--format]
#   sudo ops/hr-dev/prepare-data-volume.sh --confirm-encrypted
#
# Step 1 (after `ops/aws/hr-dev-aws.sh data-volume --yes`): finds the one attached EBS volume that
# is not the root disk, holds no partition and is not mounted (or takes --device). A blank volume
# is formatted ext4 (label divalhr-test) only with --format. The mount goes into /etc/fstab by
# UUID with nofail, so a missing volume never blocks boot, and is mounted now.
# Step 2, --confirm-encrypted: only after `ops/aws/hr-dev-aws.sh evidence` showed "PASS data
# volume is encrypted". Records the volume's serial in $HR_DEV_DATA/.volume-encryption-verified;
# every test-environment script refuses to run unless the mounted volume is that volume.
# Never touches the root volume and never formats a device that already holds a filesystem.
set -u
. "$(dirname "$0")/lib.sh"
hr_require_root
DEVICE="" FORMAT=0 CONFIRM=0
while [ $# -gt 0 ]; do
  case "$1" in
    --device) DEVICE="$2"; shift 2 ;;
    --format) FORMAT=1; shift ;;
    --confirm-encrypted) CONFIRM=1; shift ;;
    *) hr_die "unknown argument $1" ;;
  esac
done
divalhr_lock "prepare data volume" || exit $?
MOUNT="$HR_DEV_DATA"

if [ "$CONFIRM" = 1 ]; then
  mountpoint -q "$MOUNT" || hr_die "$MOUNT is not mounted"
  serial=$(hr_data_volume_serial)
  [ -n "$serial" ] || hr_die "cannot read the serial of the volume mounted at $MOUNT"
  printf 'volume=%s\nconfirmed=%s\nevidence=ops/aws/hr-dev-aws.sh evidence\n' "$serial" \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$MOUNT/.volume-encryption-verified"
  chmod 0600 "$MOUNT/.volume-encryption-verified"
  hr_log "encryption confirmation recorded for the mounted data volume"
  exit 0
fi

root_disk=$(lsblk -no PKNAME "$(findmnt -no SOURCE /)" | head -1)
if [ -z "$DEVICE" ]; then
  candidates=$(lsblk -dnpo NAME,TYPE,MODEL | awk '$2 == "disk" && /Elastic Block Store/ {print $1}' | while read -r d; do
    [ "$(basename "$d")" = "$root_disk" ] && continue
    [ "$(lsblk -no NAME "$d" | grep -c .)" = 1 ] || continue   # no partitions
    [ -z "$(lsblk -no MOUNTPOINTS "$d" | tr -d '[:space:]')" ] || continue
    echo "$d"
  done)
  [ "$(printf '%s\n' "$candidates" | grep -c .)" = 1 ] || hr_die "expected exactly one unused EBS volume, found: ${candidates:-none}; pass --device"
  DEVICE="$candidates"
fi
[ -b "$DEVICE" ] || hr_die "$DEVICE is not a block device"
[ "$(basename "$DEVICE")" != "$root_disk" ] || hr_die "$DEVICE is the root disk"
fstype=$(blkid -o value -s TYPE "$DEVICE" 2>/dev/null || true)
label=$(blkid -o value -s LABEL "$DEVICE" 2>/dev/null || true)
if [ -z "$fstype" ]; then
  [ "$FORMAT" = 1 ] || hr_die "$DEVICE is blank; rerun with --format to create the ext4 filesystem"
  mkfs.ext4 -q -L divalhr-test -m 0 "$DEVICE" || hr_die "mkfs failed"
  hr_log "formatted $DEVICE (ext4, label divalhr-test)"
elif [ "$fstype" != ext4 ] || [ "$label" != divalhr-test ]; then
  hr_die "$DEVICE already holds a $fstype filesystem labelled '$label'; refusing to touch it"
fi
uuid=$(blkid -o value -s UUID "$DEVICE")
mkdir -p "$MOUNT"
if ! grep -q "UUID=$uuid " /etc/fstab; then
  cp /etc/fstab "/etc/fstab.before-divalhr-$(date -u +%Y%m%dT%H%M%SZ)"
  printf 'UUID=%s %s ext4 defaults,nofail,x-systemd.device-timeout=30s 0 2\n' "$uuid" "$MOUNT" >> /etc/fstab
  systemctl daemon-reload
  hr_log "added $MOUNT to /etc/fstab (by UUID, nofail)"
fi
mountpoint -q "$MOUNT" || mount "$MOUNT" || hr_die "mount failed"
chmod 0711 "$MOUNT"
findmnt -no SOURCE,TARGET,FSTYPE,OPTIONS "$MOUNT"
hr_log "next: run ops/aws/hr-dev-aws.sh evidence on the Mac, then this script with --confirm-encrypted"
