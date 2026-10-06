#!/usr/bin/env bash
# OPS-001 (A65-1, R66-1): mounts the encrypted EBS data volume at /srv/divalhr-test.
#
#   sudo ops/hr-dev/prepare-data-volume.sh --volume-id <vol-…> [--device /dev/nvmeXn1] [--format]
#   sudo ops/hr-dev/prepare-data-volume.sh --confirm-encrypted
#
# Step 1 (after `ops/aws/hr-dev-aws.sh data-volume --yes`, which prints the volume ID of the one
# volume tagged for the test environment). The disk is identified by that volume ID, never by its
# position: on EBS NVMe the disk's serial is the volume ID without its hyphen. Whether the disk is
# found automatically or named with --device, the same checks run before anything is written:
#   * it is a whole disk (not a partition) of model "Amazon Elastic Block Store" whose serial is
#     the given volume ID;
#   * it is not the root disk and backs no mounted filesystem and no swap, directly or through a
#     partition, LVM or device-mapper holder;
#   * it has no partitions, no holders and no signature of any kind (filesystem, RAID, LVM or a
#     partition table: `blkid -p` and `wipefs -n` probe the raw device);
#   * the only exception is idempotent reuse: an ext4 filesystem labelled divalhr-test, with no
#     partition table, that is either not mounted or already mounted exactly at the data root.
# A blank disk is formatted (ext4, label divalhr-test) only with --format. The mount goes into
# /etc/fstab by UUID with nofail and is mounted now.
#
# Step 2, --confirm-encrypted: only after `ops/aws/hr-dev-aws.sh evidence` showed "PASS data
# volume is encrypted". Records the volume's serial in $HR_DEV_DATA/.volume-encryption-verified;
# every test-environment script refuses to run unless the mounted volume is that volume.
set -u
. "$(dirname "$0")/lib.sh"
hr_require_root
DEVICE="" VOLUME_ID="" FORMAT=0 CONFIRM=0
while [ $# -gt 0 ]; do
  case "$1" in
    --device) DEVICE="$2"; shift 2 ;;
    --volume-id) VOLUME_ID="$2"; shift 2 ;;
    --format) FORMAT=1; shift ;;
    --confirm-encrypted) CONFIRM=1; shift ;;
    *) hr_die "unknown argument $1" ;;
  esac
done
divalhr_lock "prepare data volume" || exit $?
MOUNT="$HR_DEV_DATA"
FSTAB="${HR_DEV_FSTAB:-/etc/fstab}"
SYSFS="${HR_DEV_SYSFS:-/sys}"
LABEL=divalhr-test

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

case "$VOLUME_ID" in
  vol-[0-9a-f]*) ;;
  *) hr_die "--volume-id vol-… is required (printed by ops/aws/hr-dev-aws.sh data-volume)" ;;
esac
SERIAL="${VOLUME_ID//-/}"

# Outermost disk(s) behind a block device (itself, its partition's disk, LVM/dm members…).
disks_behind() { lsblk -nsro NAME,TYPE "$1" 2>/dev/null | awk '$2 == "disk" {print $1}'; }

# Every disk that backs a mounted filesystem or an active swap area.
busy_disks() {
  { findmnt -rno SOURCE; swapon --show=NAME --noheadings 2>/dev/null; } | grep '^/dev/' | sort -u \
    | while read -r src; do disks_behind "$src"; done | sort -u
}

# The one EBS disk whose serial is the volume ID.
if [ -z "$DEVICE" ]; then
  matches=$(lsblk -dnpro NAME,TYPE,SERIAL | awk -v s="$SERIAL" '$2 == "disk" && $3 == s {print $1}')
  [ "$(printf '%s\n' "$matches" | grep -c .)" = 1 ] \
    || hr_die "expected exactly one disk with the serial of $VOLUME_ID, found: ${matches:-none}"
  DEVICE="$matches"
fi
DEVICE="$(readlink -f "$DEVICE")"
NAME="$(basename "$DEVICE")"

info=$(lsblk -dnro TYPE,SERIAL "$DEVICE" 2>/dev/null) || hr_die "$DEVICE is not a block device"
model=$(lsblk -dno MODEL "$DEVICE" 2>/dev/null)
[ "${info%% *}" = disk ] || hr_die "$DEVICE is a ${info%% *}, not a whole disk"
[ "${info#* }" = "$SERIAL" ] || hr_die "$DEVICE is not volume $VOLUME_ID (serial ${info#* })"
case "$model" in *"Elastic Block Store"*) ;; *) hr_die "$DEVICE is not an EBS volume ($model)" ;; esac
[ "$(lsblk -nro NAME "$DEVICE" | grep -c .)" = 1 ] || hr_die "$DEVICE has partitions; refusing to touch it"
if [ -d "$SYSFS/class/block/$NAME/holders" ] && [ -n "$(ls -A "$SYSFS/class/block/$NAME/holders")" ]; then
  hr_die "$DEVICE is held by another device (LVM, RAID or device-mapper); refusing to touch it"
fi

# Signatures of the raw device: blkid -p probes filesystems, RAID/LVM members and partition tables.
probe=$(blkid -p -o export "$DEVICE" 2>/dev/null || true)
fstype=$(printf '%s\n' "$probe" | sed -n 's/^TYPE=//p')
pttype=$(printf '%s\n' "$probe" | sed -n 's/^PTTYPE=//p')
label=$(printf '%s\n' "$probe" | sed -n 's/^LABEL=//p')
mounted_at=$(lsblk -nro MOUNTPOINTS "$DEVICE" | grep -v '^$' | sort -u)

# wipefs (no-act) is a second, independent probe for any known signature.
signatures=$(wipefs -n -p "$DEVICE" 2>/dev/null | grep -v '^#' | grep -c . || true)
if [ -z "$(printf '%s' "$probe" | tr -d '[:space:]')" ] && [ "$signatures" = 0 ]; then
  state=blank
elif [ "$fstype" = ext4 ] && [ "$label" = "$LABEL" ] && [ -z "$pttype" ]; then
  state=ours
else
  hr_die "$DEVICE already carries a signature (filesystem '${fstype:-none}', label '${label:-none}', partition table '${pttype:-none}'); refusing to touch it"
fi

if busy_disks | grep -qx "$NAME"; then
  # Only our own filesystem, mounted exactly at the data root, may be busy (idempotent rerun).
  [ "$state" = ours ] && [ "$mounted_at" = "$MOUNT" ] \
    || hr_die "$DEVICE backs a mounted filesystem or swap (${mounted_at:-through another device}); refusing to touch it"
fi
[ -z "$mounted_at" ] || [ "$mounted_at" = "$MOUNT" ] || hr_die "$DEVICE is mounted at $mounted_at"

if [ "$state" = blank ]; then
  [ "$FORMAT" = 1 ] || hr_die "$DEVICE is blank; rerun with --format to create the ext4 filesystem"
  mkfs.ext4 -q -L "$LABEL" -m 0 "$DEVICE" || hr_die "mkfs failed"
  hr_log "formatted $DEVICE (ext4, label $LABEL)"
else
  hr_log "$DEVICE already holds the $LABEL filesystem; reusing it"
fi

uuid=$(blkid -o value -s UUID "$DEVICE")
[ -n "$uuid" ] || hr_die "cannot read the filesystem UUID of $DEVICE"
mkdir -p "$MOUNT"
if ! grep -q "^UUID=$uuid " "$FSTAB"; then
  cp "$FSTAB" "$FSTAB.before-divalhr-$(date -u +%Y%m%dT%H%M%SZ)"
  printf 'UUID=%s %s ext4 defaults,nofail,x-systemd.device-timeout=30s 0 2\n' "$uuid" "$MOUNT" >> "$FSTAB"
  systemctl daemon-reload
  hr_log "added $MOUNT to $FSTAB (by UUID, nofail)"
fi
mountpoint -q "$MOUNT" || mount "$MOUNT" || hr_die "mount failed"
chmod 0711 "$MOUNT"
findmnt -no SOURCE,TARGET,FSTYPE,OPTIONS "$MOUNT"
hr_log "next: run ops/aws/hr-dev-aws.sh evidence on the Mac, then this script with --confirm-encrypted"
