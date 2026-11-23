// DEVX-005 (Issue #222): no two tracked paths may differ only by letter case. macOS and Windows
// checkouts are case-insensitive, so such a pair makes one file shadow the other and breaks the
// module resolution of tools that run there, while Linux CI stays green.
//   node --test scripts/ops/tracked-paths.test.mjs
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { describe, it } from 'node:test';

/** Groups paths by their case-folded form and returns the groups with more than one path. */
export function caseCollisions(paths) {
  const byFold = new Map();
  for (const path of paths) {
    const folded = path.normalize('NFC').toLowerCase();
    byFold.set(folded, [...(byFold.get(folded) ?? []), path]);
  }
  return [...byFold.values()].filter((group) => new Set(group).size > 1);
}

/** A path and every directory above it: `a/B/c.ts` collides with `A/b/d.ts` at directory level. */
function withDirectories(paths) {
  const all = new Set();
  for (const path of paths) {
    const parts = path.split('/');
    for (let i = 1; i <= parts.length; i += 1) all.add(parts.slice(0, i).join('/'));
  }
  return [...all];
}

const MODULE_EXTENSION = /\.(?:[cm]?[jt]sx?)$/;

/**
 * JavaScript and TypeScript modules are imported without their extension, so `Navigation.tsx` and
 * `navigation.ts` differ as paths but resolve to one specifier on a case-insensitive checkout.
 * Returns the module paths whose case-folded, extension-less form is shared by two modules.
 */
export function moduleStemCollisions(paths) {
  const stems = paths
    .filter((path) => MODULE_EXTENSION.test(path))
    .map((path) => ({ path, stem: path.replace(MODULE_EXTENSION, '') }));
  const byFold = new Map();
  for (const { path, stem } of stems) {
    const folded = stem.normalize('NFC').toLowerCase();
    byFold.set(folded, [...(byFold.get(folded) ?? []), { path, stem }]);
  }
  return [...byFold.values()]
    .filter((group) => new Set(group.map((entry) => entry.stem)).size > 1)
    .map((group) => group.map((entry) => entry.path));
}

const tracked = () =>
  execFileSync('git', ['ls-files', '-z'], { maxBuffer: 1 << 28 })
    .toString('utf8')
    .split('\0')
    .filter(Boolean);

describe('tracked repository paths are case-portable', () => {
  it('the detector finds files and directories that differ only by case', () => {
    assert.deepEqual(caseCollisions(['a/b.ts', 'a/c.ts']), []);
    assert.deepEqual(caseCollisions(['a/Navigation.tsx', 'a/navigation.tsx']), [
      ['a/Navigation.tsx', 'a/navigation.tsx'],
    ]);
    assert.deepEqual(caseCollisions(withDirectories(['Docs/x.md', 'docs/y.md'])), [
      ['Docs', 'docs'],
    ]);
    assert.deepEqual(caseCollisions(['a/b.ts', 'a/b.ts']), []);
  });

  it('the module detector finds the pair that differs by case and extension only', () => {
    assert.deepEqual(moduleStemCollisions(['s/Navigation.tsx', 's/navigation.ts']), [
      ['s/Navigation.tsx', 's/navigation.ts'],
    ]);
    // The same stem with two extensions, and a test file beside its module, are not collisions.
    assert.deepEqual(moduleStemCollisions(['s/a.ts', 's/a.tsx', 's/a.test.ts', 's/A.md']), []);
  });

  it('no two tracked paths collide when case is folded', () => {
    const collisions = caseCollisions(withDirectories(tracked()));
    assert.deepEqual(
      collisions,
      [],
      `case-insensitive path collisions: ${JSON.stringify(collisions)}`,
    );
  });

  it('no two tracked modules share a case-folded module specifier', () => {
    const collisions = moduleStemCollisions(tracked());
    assert.deepEqual(
      collisions,
      [],
      `module specifiers that differ only by case: ${JSON.stringify(collisions)}`,
    );
  });
});
