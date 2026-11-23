// DEVX-005 (Issue #153): ops/hr-dev/secret-needles.sh reads every secret file on its own, in a fixed
// order, so the records of two files never run together whether or not a file ends with a newline.
//   node --test scripts/ops/secret-needles.test.mjs
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

const SCRIPT = new URL('../../ops/hr-dev/secret-needles.sh', import.meta.url).pathname;

function run(files, { order } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'needles-'));
  const secrets = join(dir, 'secrets');
  mkdirSync(secrets);
  for (const name of order ?? Object.keys(files)) {
    const path = join(secrets, name);
    mkdirSync(join(path, '..'), { recursive: true });
    writeFileSync(path, files[name]);
  }
  const out = join(dir, 'needles');
  const result = spawnSync('bash', [SCRIPT, secrets, out], { encoding: 'utf8' });
  const needles = existsSync(out) ? readFileSync(out, 'utf8') : null;
  return {
    result,
    needles,
    secrets,
    dir,
    lines: needles === null ? [] : needles.split('\n').filter(Boolean),
  };
}

const A = 'alphaSecretValue0001';
const B = 'bravoSecretValue0002';

test('a terminated and an unterminated file are two separate records', () => {
  const { result, lines } = run({ 'one/A': `${A}\n`, 'two/B': B });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(lines, [A, B].sort());
  assert.match(result.stdout, /secret files 2, distinct needles 2/);
});

test('an unterminated file never runs into the file that follows it', () => {
  // Names sort so that the unterminated file is read first: a naive concatenation would give
  // `alphaSecretValue0001bravoSecretValue0002`.
  const { lines } = run({ 'a/first': A, 'b/second': B });
  assert.deepEqual(lines, [A, B].sort());
  assert.ok(!lines.some((line) => line.includes(A + B)));
});

test('KEY=VALUE files give the values of their secret-named keys, terminated or not', () => {
  const terminated = run({ 'kc.env': `USER='operator'\nKC_ADMIN_PASSWORD='${A}'\n` });
  const unterminated = run({ 'kc.env': `USER='operator'\nKC_ADMIN_PASSWORD='${A}'` });
  assert.deepEqual(terminated.lines, [A]);
  assert.deepEqual(unterminated.lines, [A]);
  assert.equal(terminated.needles, unterminated.needles);
});

test('the enumeration order of the files does not change the needles', () => {
  const files = { 'z/last': `${A}`, 'a/first': B, 'm/env.env': 'API_SECRET=cccSecretValue0003\n' };
  const forward = run(files, { order: ['z/last', 'a/first', 'm/env.env'] });
  const backward = run(files, { order: ['m/env.env', 'a/first', 'z/last'] });
  assert.equal(forward.needles, backward.needles);
  assert.equal(forward.lines.length, 3);
});

test('a value held by two files is one needle', () => {
  const { result, lines } = run({
    'x/one': `${A}\n`,
    'y/two': A,
    'z/three.env': `DB_PASSWORD=${A}\n`,
  });
  assert.deepEqual(lines, [A]);
  assert.match(result.stdout, /secret files 3, distinct needles 1/);
});

test('empty files and short values give no needle, and identifier files are skipped', () => {
  const { result, lines } = run({ empty: '', short: 'short', 'organization.txt': `${A}\n` });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(lines, []);
  assert.match(result.stdout, /secret files 2, distinct needles 0/);
});

test(
  'an unreadable file fails the scan without a value',
  { skip: process.getuid?.() === 0 },
  () => {
    const dir = mkdtempSync(join(tmpdir(), 'needles-'));
    const secrets = join(dir, 'secrets');
    mkdirSync(secrets);
    writeFileSync(join(secrets, 'good'), `${A}\n`);
    writeFileSync(join(secrets, 'locked'), `${B}\n`);
    chmodSync(join(secrets, 'locked'), 0o000);
    const result = spawnSync('bash', [SCRIPT, secrets, join(dir, 'needles')], { encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.ok(!result.stdout.includes(A) && !result.stderr.includes(B));
  },
);

test('a missing secrets directory fails', () => {
  const dir = mkdtempSync(join(tmpdir(), 'needles-'));
  const result = spawnSync('bash', [SCRIPT, join(dir, 'nope'), join(dir, 'needles')], {
    encoding: 'utf8',
  });
  assert.equal(result.status, 1);
});

test('spaces and shell-special characters are values and file names, never expanded', () => {
  const marker = mkdtempSync(join(tmpdir(), 'marker-'));
  const tricky = `sp ace $(touch ${marker}/ran) \`touch ${marker}/ran2\` *?[a] 'q' "d" \\ ;&|<>`;
  const { result, lines } = run({ 'dir with space/we ird$name': `${tricky}\n`, plain: A });
  assert.equal(result.status, 0, result.stderr);
  assert.ok(lines.includes(tricky), 'the exact value is a needle');
  assert.ok(lines.includes(A));
  assert.equal(existsSync(join(marker, 'ran')), false, 'no command substitution');
  assert.equal(existsSync(join(marker, 'ran2')), false, 'no backtick substitution');
});

test('only counts are printed, never a value', () => {
  const { result } = run({ one: `${A}\n`, 'two.env': `X_SECRET=${B}\n` });
  assert.ok(!result.stdout.includes(A) && !result.stdout.includes(B));
  assert.ok(!result.stderr.includes(A) && !result.stderr.includes(B));
  assert.match(result.stdout, /^secret files 2, distinct needles 2$/m);
});
