#!/usr/bin/env python3
"""Fake lsblk/findmnt/blkid/wipefs/swapon/mkfs.ext4/mount/mountpoint/systemctl/id for the
prepare-data-volume.sh tests (R66-1). Nothing touches a real device: every answer comes from the
JSON world in $FAKE_WORLD, and every changing command is appended to $FAKE_LOG."""
import json, os, sys

WORLD = os.environ["FAKE_WORLD"]
LOG = os.environ["FAKE_LOG"]
tool = os.path.basename(sys.argv[0])
args = sys.argv[1:]
with open(WORLD) as handle:
    world = json.load(handle)
disks = world["disks"]


def log(line):
    with open(LOG, "a") as handle:
        handle.write(line + "\n")


def find(dev):
    name = os.path.basename(dev)
    for disk_name, disk in disks.items():
        if disk_name == name:
            return disk_name, disk, None
        for child in disk.get("children", []):
            if child["name"] == name:
                return disk_name, disk, child
    return None, None, None


if tool == "id":
    print(0 if args[:1] == ["-u"] else "root")
elif tool == "lsblk":
    flags = [a for a in args if a.startswith("-")]
    targets = [a for a in args if a.startswith("/dev/")]
    target = targets[-1] if targets else None
    cols = next((a for a in args if not a.startswith(("-", "/")) and a.replace(",", "").isupper()), "")
    if target is None:  # -dnpro NAME,TYPE,SERIAL: every disk
        for name, disk in disks.items():
            print(f"/dev/{name} disk {disk.get('serial', '')}")
        sys.exit(0)
    disk_name, disk, child = find(target)
    if disk is None:
        sys.exit(32)
    if "-nsro" in flags:  # ancestors, innermost first
        if child:
            print(f"{child['name']} part")
        print(f"{disk_name} disk")
    elif cols == "TYPE,SERIAL":
        print("part " if child else f"disk {disk.get('serial', '')}")
    elif cols == "MODEL":
        print(disk.get("model", ""))
    elif cols == "NAME":
        print(disk_name)
        for c in disk.get("children", []):
            print(c["name"])
    elif cols == "MOUNTPOINTS":
        print(disk.get("mount", ""))
        for c in disk.get("children", []):
            print(c.get("mount", ""))
elif tool == "findmnt":
    if "-rno" in args:
        for name, disk in disks.items():
            if disk.get("mount"):
                print(f"/dev/{name}")
            for c in disk.get("children", []):
                if c.get("mount"):
                    print(f"/dev/{c['name']}")
    else:
        print("fake findmnt")
elif tool == "swapon":
    for s in world.get("swap", []):
        print(s)
elif tool == "blkid":
    disk_name, disk, child = find(args[-1])
    if "-s" in args and "UUID" in args:
        print(disk.get("uuid", ""))
    else:
        print(disk.get("probe", ""), end="")
elif tool == "wipefs":
    disk_name, disk, child = find(args[-1])
    print("# offset,uuid,label,type")
    for line in disk.get("wipefs", []):
        print(line)
elif tool == "mkfs.ext4":
    disk_name, disk, child = find(args[-1])
    log("mkfs " + args[-1])
    disk["probe"] = "TYPE=ext4\nLABEL=divalhr-test\n"
    disk["uuid"] = "11111111-2222-3333-4444-555555555555"
    with open(WORLD, "w") as handle:
        json.dump(world, handle)
elif tool == "mountpoint":
    sys.exit(0 if world.get("data_root_mounted") else 1)
elif tool in ("mount", "systemctl"):
    log(tool + " " + " ".join(args))
