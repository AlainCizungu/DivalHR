#!/usr/bin/env bash
# DEVX-005 (Issue #153): the secret values log-hygiene.sh looks for, one per line, read from the
# root-only secrets directory. Every file is read on its own, in a fixed order, so a record never
# runs into the next file's record whether or not a file ends with a newline.
#
#   secret-needles.sh <secrets-directory> <needles-file>
#
# Writes the needles (at least 8 characters, sorted, without duplicates) to <needles-file> and
# prints only the number of files read and of distinct needles. It never prints a secret value, and
# no value is expanded by the shell: values travel through files and pipes only.
#   - *.txt files hold identifiers, not secrets, and are skipped (organization.txt);
#   - a file with an `=` is a KEY=VALUE file: the values of its secret-named keys (password, secret
#     or key in the name), without surrounding quotes;
#   - any other file is one bare secret: its first line.
# Exit 1 when a file cannot be read. The caller decides what an empty result means.
set -u
[ $# -eq 2 ] || { echo "STOP: usage: secret-needles.sh <secrets-directory> <needles-file>" >&2; exit 2; }
S="$1" OUT="$2"
[ -d "$S" ] || { echo "STOP: no secrets directory" >&2; exit 1; }

W=$(mktemp -d) || { echo "STOP: mktemp failed" >&2; exit 1; }
chmod 700 "$W"
trap 'rm -rf "$W"' EXIT
: > "$W/all"

files=0
# A fixed byte order, whatever the file system returns: the same files give the same needles.
while IFS= read -r -d '' f; do
  [ -r "$f" ] || { echo "STOP: a secret file cannot be read" >&2; exit 1; }
  files=$((files + 1))
  if grep -q '=' -- "$f"; then
    # KEY=VALUE: secret-named keys only. grep ends every match with a newline, terminated or not.
    { grep -iE '^[A-Za-z0-9_.-]*(password|secret|key)[A-Za-z0-9_.-]*=' -- "$f" || true; } \
      | cut -d= -f2- | sed -e "s/^['\"]//" -e "s/['\"]$//" >> "$W/all"
  else
    # One bare secret: awk emits its first record newline-terminated even without a final newline.
    awk 'NR == 1 { print; exit }' "$f" >> "$W/all"
  fi
done < <(find "$S" -type f ! -name '*.txt' -print0 | LC_ALL=C sort -z)

awk 'length($0) >= 8' "$W/all" | LC_ALL=C sort -u > "$OUT"
printf 'secret files %s, distinct needles %s\n' "$files" "$(wc -l < "$OUT" | tr -d ' ')"
