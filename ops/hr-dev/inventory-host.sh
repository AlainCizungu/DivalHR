#!/usr/bin/env bash
# OPS-001 (A65-7): read-only, REDACTED inventory of the instance, for the baseline and the
# post-deployment comparison posted on Issue #65. Run through ops/hr-dev/deploy.sh (or with
# `sudo bash -s < ops/hr-dev/inventory-host.sh` over SSH). Changes nothing; prints no secret,
# no environment variable, no AWS identifier and no host address other than wildcard/loopback.
set -u
redact() {
  sed -E 's/\b(10|172|192)\.[0-9]+\.[0-9]+\.[0-9]+\b/<host-address>/g; s/\b(i|vol|snap|sg|eni)-[0-9a-f]{8,}\b/<aws-id>/g'
}
section() { echo ""; echo "-- $1"; }
{
  section "host"
  . /etc/os-release; echo "os: $PRETTY_NAME"; echo "kernel: $(uname -r)"; echo "cpus: $(nproc)"
  free -g | awk '/^Mem:/ {print "memory: " $2 " GiB"}'
  section "filesystems"
  df -h -x tmpfs -x devtmpfs -x overlay -x squashfs --output=target,fstype,size | sed 1d | sort
  findmnt -no TARGET,FSTYPE /srv/divalhr-test 2>/dev/null || echo "/srv/divalhr-test: not mounted"
  [ -f /srv/divalhr-test/.volume-encryption-verified ] && echo "encryption confirmation: present" \
    || echo "encryption confirmation: absent"
  section "listening sockets (address:port process)"
  ss -Hlntup | awk '{print $1, $5, $7}' | sed -E 's/users:\(\("([^"]+)".*/\1/' | sort -u
  section "docker"
  docker version --format 'docker {{.Server.Version}}' 2>/dev/null
  docker compose version --short 2>/dev/null | sed 's/^/compose /'
  echo "projects:"; docker compose ls -a --format json 2>/dev/null \
    | python3 -c 'import json,sys; [print(" ", p["Name"], p["Status"]) for p in json.load(sys.stdin)]' 2>/dev/null
  echo "containers (name | image | status | ports):"
  docker ps -a --format '  {{.Names}} | {{.Image}} | {{.Status}} | {{.Ports}}' | sed -E 's/\| Up [^|(]*/| Up /; s/\| Exited (\([0-9]+\)) [^|]*/| Exited \1 /' | sort
  echo "networks:"; docker network ls --format '  {{.Name}} {{.Driver}}' | sort
  echo "named volumes:"; docker volume ls -q | sed 's/^/  /' | sort
  echo "images (repository:tag):"; docker images --format '  {{.Repository}}:{{.Tag}}' | sort
  section "test environment"
  echo "current release: $(cat /srv/divalhr-test/state/current-release 2>/dev/null || echo none)"
  echo "previous release: $(cat /srv/divalhr-test/state/previous-release 2>/dev/null || echo none)"
  ls -1 /srv/divalhr-test/releases 2>/dev/null | sed 's/^/  release /'
  for r in nightly pre-deploy; do echo "backups $r: $(find /srv/divalhr-test/backups/$r -mindepth 1 -maxdepth 1 -type d ! -name "*.partial" 2>/dev/null | wc -l)"; done
  section "systemd timers"
  systemctl list-timers --all --no-legend 2>/dev/null | awk '{print "  " $(NF-1), $NF}' | sort
  section "host lock"
  ls -l /run/lock/divalhr-host.lock 2>/dev/null | awk '{print $1, $3, $4, $NF}' || echo "absent"
  getent group divalhr-ops | cut -d: -f1,4
} 2>&1 | redact
