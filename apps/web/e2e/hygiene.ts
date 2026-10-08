// DEVX-001A (A75-3): credential hygiene after a browser-suite run. Runs under plain Node:
//
//   node apps/web/e2e/hygiene.ts --since <epoch seconds> --sso-dir <dir> <path>...
//
// Fails (exit 1) when
//   * the run's SSO state directory still exists;
//   * a generated file (modified since the run started) under the given paths contains a JWT, a
//     Keycloak session cookie assignment, a seed password or TOTP seed, or (in Playwright output:
//     test-results and playwright-report) a TOTP code of a seed user for the run's time window.
// The Playwright HTML report keeps its data in a base64 ZIP inside index.html; those entries are
// decoded and scanned too, so step titles and source snippets in the report are covered.
// It reports file names and the kind of finding only, never the matched value. It never scans the
// repository itself: committed development fixtures legitimately contain the published dev values.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, sep } from 'node:path';
import { inflateRawSync } from 'node:zlib';
import { TOTP_SEEDS, USERS, totp } from './credentials.ts';

/** Larger generated files are not read; each one is a finding (R76-2: fail closed). */
export const MAX_BYTES = 32 * 1024 * 1024;
const JWT = /eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\./u;
const COOKIE =
  /\b(?:KEYCLOAK_IDENTITY|KEYCLOAK_SESSION|AUTH_SESSION_ID)(?:_LEGACY)?\s*["']?\s*[=:]\s*["']?[A-Za-z0-9]/u;

export interface Finding {
  file: string;
  kind: string;
}

/**
 * Every regular file under the paths modified at or after `since` (epoch seconds), split into the
 * files to scan and those above `maxBytes`, which are never silently skipped.
 */
export function filesSince(
  paths: string[],
  since: number,
  maxBytes: number = MAX_BYTES,
): { files: string[]; oversized: string[] } {
  const files: string[] = [];
  const oversized: string[] = [];
  const walk = (path: string) => {
    if (!existsSync(path)) return;
    const stat = statSync(path);
    if (stat.isDirectory()) {
      for (const entry of readdirSync(path)) walk(join(path, entry));
    } else if (stat.isFile() && stat.mtimeMs >= since * 1000) {
      (stat.size <= maxBytes ? files : oversized).push(path);
    }
  };
  for (const path of paths) walk(path);
  return { files, oversized };
}

/** The TOTP codes of every seed user for the periods around [since, until]. */
export function seedCodes(since: number, until: number): Set<string> {
  const codes = new Set<string>();
  for (const secret of Object.values(TOTP_SEEDS)) {
    if (!secret) continue;
    for (
      let counter = Math.max(0, Math.floor(since / 30) - 1);
      counter <= Math.floor(until / 30) + 1;
      counter++
    ) {
      codes.add(totp(secret, counter));
    }
  }
  return codes;
}

function isPlaywrightOutput(file: string): boolean {
  return (
    file.includes(`${sep}test-results${sep}`) || file.includes(`${sep}playwright-report${sep}`)
  );
}

/** The entries of a ZIP archive (stored or deflated), enough for Playwright's embedded report. */
export function unzip(data: Buffer): { name: string; text: string }[] {
  let end = data.length - 22;
  while (end >= 0 && data.readUInt32LE(end) !== 0x06054b50) end--;
  if (end < 0) throw new Error('not a ZIP archive');
  const entries: { name: string; text: string }[] = [];
  let at = data.readUInt32LE(end + 16);
  for (let i = 0; i < data.readUInt16LE(end + 10); i++) {
    if (data.readUInt32LE(at) !== 0x02014b50) throw new Error('corrupt ZIP directory');
    const method = data.readUInt16LE(at + 10);
    const size = data.readUInt32LE(at + 20);
    const nameLength = data.readUInt16LE(at + 28);
    const name = data.toString('utf8', at + 46, at + 46 + nameLength);
    const local = data.readUInt32LE(at + 42);
    const start = local + 30 + data.readUInt16LE(local + 26) + data.readUInt16LE(local + 28);
    const raw = data.subarray(start, start + size);
    if (method !== 0 && method !== 8) throw new Error('unsupported ZIP compression');
    entries.push({ name, text: (method === 8 ? inflateRawSync(raw) : raw).toString('utf8') });
    at += 46 + nameLength + data.readUInt16LE(at + 30) + data.readUInt16LE(at + 32);
  }
  return entries;
}

const EMBEDDED_ZIP = /data:application\/zip;base64,([A-Za-z0-9+/=]+)/gu;

/** A file's text plus, for an HTML report, the text of every entry of its embedded archives. */
export function textsOf(file: string, text: string): { file: string; text: string }[] {
  if (!file.endsWith('.html')) return [{ file, text }];
  // The base64 archive itself is not scanned as text (its digits would look like codes).
  const texts = [{ file, text: text.replace(EMBEDDED_ZIP, 'data:application/zip;base64,') }];
  for (const match of text.matchAll(EMBEDDED_ZIP)) {
    try {
      for (const entry of unzip(Buffer.from(match[1], 'base64'))) {
        texts.push({ file: `${file}#${entry.name}`, text: entry.text });
      }
    } catch {
      texts.push({ file, text: 'unreadable embedded report' });
    }
  }
  return texts;
}

/** The findings in one file's text. */
export function scanText(file: string, text: string, codes: Set<string>): Finding[] {
  const findings: Finding[] = [];
  if (JWT.test(text)) findings.push({ file, kind: 'token-shaped value' });
  if (COOKIE.test(text)) findings.push({ file, kind: 'session cookie value' });
  for (const [, password] of Object.values(USERS)) {
    if (text.includes(password)) findings.push({ file, kind: 'seed password' });
  }
  for (const secret of Object.values(TOTP_SEEDS)) {
    if (secret && text.includes(secret)) findings.push({ file, kind: 'TOTP seed' });
  }
  if (isPlaywrightOutput(file)) {
    for (const match of text.matchAll(/(?<![0-9A-Za-z])[0-9]{6}(?![0-9A-Za-z])/gu)) {
      if (codes.has(match[0])) {
        findings.push({ file, kind: 'TOTP code' });
        break;
      }
    }
  }
  return findings;
}

export function check(
  paths: string[],
  since: number,
  ssoDir: string | undefined,
  maxBytes: number = MAX_BYTES,
): Finding[] {
  const findings: Finding[] = [];
  if (ssoDir && existsSync(ssoDir))
    findings.push({ file: ssoDir, kind: 'SSO state directory left behind' });
  const codes = seedCodes(since, Math.floor(Date.now() / 1000));
  const { files, oversized } = filesSince(paths, since, maxBytes);
  for (const file of oversized) {
    findings.push({ file, kind: 'oversized generated file was not scanned' });
  }
  for (const file of files) {
    for (const part of textsOf(file, readFileSync(file, 'utf8'))) {
      if (part.text === 'unreadable embedded report') {
        findings.push({ file: part.file, kind: 'unreadable embedded report' });
      } else {
        findings.push(...scanText(part.file, part.text, codes));
      }
    }
  }
  return findings;
}

function main(argv: string[]): number {
  let since = 0;
  let ssoDir: string | undefined;
  const paths: string[] = [];
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--since') since = Number(argv[++i]);
    else if (arg === '--sso-dir') ssoDir = argv[++i];
    else paths.push(arg);
  }
  if (!Number.isFinite(since) || since <= 0 || paths.length === 0) {
    console.error('usage: hygiene.ts --since <epoch seconds> [--sso-dir <dir>] <path>...');
    return 2;
  }
  const findings = check(paths, since, ssoDir);
  for (const finding of findings) console.log(`FAIL hygiene: ${finding.kind} in ${finding.file}`);
  if (findings.length === 0) {
    console.log(
      `PASS hygiene: ${filesSince(paths, since).files.length} generated files scanned, SSO state removed`,
    );
  }
  return findings.length === 0 ? 0 : 1;
}

if (import.meta.url === `file://${process.argv[1]}`) process.exitCode = main(process.argv.slice(2));
