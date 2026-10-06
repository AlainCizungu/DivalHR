# shellcheck shell=bash
# OPS-001 (Issue #68): functions of deploy.sh that run on the owner's Mac, kept apart so that
# `pnpm ops:test` can exercise them. Sourced by deploy.sh; the caller defines rsh (ssh to the
# instance). Compatible with macOS bash 3.2.

# The deployment log's complete lines on the instance, then one "@@EXIT <code>" line (empty code
# while the deployment runs). A last line still being written (no newline yet) is left for the
# next poll, so no line is ever printed in two halves.
hr_deploy_log_command() {
  local dir="\$HOME/divalhr-deploy/$1"
  printf '%s' "L=\"$dir/deploy.log\"; [ -f \"\$L\" ] && head -n \"\$(wc -l < \"\$L\")\" \"\$L\"; echo \"@@EXIT \$(cat \"$dir/exit\" 2>/dev/null)\""
}

# Number of lines of a followed log, counted exactly as hr_log_new_lines prints them: every line,
# blank lines included (Issue #68: counting only non-empty lines re-printed earlier output).
hr_log_line_count() {
  if [ -z "$1" ]; then echo 0; return; fi
  printf '%s\n' "$1" | wc -l | tr -d ' '
}

# Prints, indented, the lines of <log> after the first <printed> lines.
hr_log_new_lines() {
  [ -n "$1" ] || return 0
  printf '%s\n' "$1" | sed -n "$(($2 + 1)),\$p" | sed 's/^/  /'
}

# The release recorded as current on the instance. state/ is root 0700, so it is read with sudo -n
# (Issue #68: an unprivileged read returned nothing after a successful deployment).
hr_running_release() {
  rsh 'sudo -n cat /srv/divalhr-test/state/current-release 2>/dev/null'
}

# hr_deploy_finish <remote exit code> <sha> <evidence dir>: reports the outcome and collects the
# host evidence of a successful deployment. Success requires the remote exit code 0 and exactly
# <sha> recorded as the running release. Returns 0 only for a success with clean host evidence.
hr_deploy_finish() {
  local rc="$1" sha="$2" out="$3" running ev
  running=$(hr_running_release)
  if [ "$rc" = 0 ] && [ -n "$sha" ] && [ "$running" = "$sha" ]; then
    echo "== host evidence (redacted)"
    rsh "sudo -n /srv/divalhr-test/current/ops/hr-dev/evidence-host.sh" > "$out/evidence-host.txt" 2>&1
    ev=$?
    sed -n '/== checks/,$p' "$out/evidence-host.txt"
    echo ""
    echo "DEPLOYED $sha to https://hr-dev.dival.ai (evidence: $out)"
    [ "$ev" = 0 ] || { echo "WARNING: host evidence reported FAIL lines; send $out to Claude"; return 1; }
    return 0
  fi
  echo "DEPLOYMENT FAILED (exit ${rc:-none}); running release is ${running:-none}. Send $out to Claude."
  return 1
}
