#!/usr/bin/env node
// DEVX-001A (D3): chooses what the `changed` verification profile runs for a set of changed
// paths. It only ever widens: a path it cannot place with confidence selects `pr`, and
// operational, identity, build and workflow paths select `full`. (DEVX-001B replaces the
// rehearsal side of this with the reviewed risk classifier; nothing here changes deployment.)
//
//   node scripts/dev/classify-changed.mjs <base> [<head>]   git diff --name-only base...head
//   node scripts/dev/classify-changed.mjs --paths a b c     explicit paths (tests)
//
// Prints `profile=changed|pr|full`, then for `changed` the checks (`check=docs|core|web|e2e`) and
// the browser specs to run (`spec=<path>`, or `spec=*` for the whole optimized suite).
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

/** Ordered rules: the first whose pattern matches a path decides it. */
const RULES = [
  // Escalate to the complete profile: operations, identity, builds, workflows, dependencies.
  [/^(ops|infrastructure)\//u, 'full'],
  [/^\.github\//u, 'full'],
  [/^scripts\/(dev|ops)\//u, 'full'],
  [/(^|\/)Dockerfile$|(^|\/)compose[^/]*\.ya?ml$/u, 'full'],
  [/^apps\/keycloak-provisioning\//u, 'full'],
  [/^apps\/web\/(playwright\.hr-dev\.config\.ts|e2e\/hr-dev\/)/u, 'full'],
  [/^(package\.json|pnpm-lock\.yaml|pnpm-workspace\.yaml|\.npmrc|\.nvmrc)$/u, 'full'],
  [/^apps\/core-api\/(build\.gradle|settings\.gradle|gradle\/|gradlew)/u, 'full'],
  [/^apps\/[^/]+\/package\.json$/u, 'full'],
  // Contracts touch both sides.
  [/^docs\/API-SPEC\.yaml$|^packages\/shared-contracts\//u, 'pr'],
  // Narrow checks.
  [/^apps\/core-api\/(src|config)\//u, 'core'],
  [/^apps\/web\/e2e\/[^/]+\.spec\.ts$/u, 'spec'],
  [/^apps\/web\/(e2e\/|playwright\.config\.ts$)/u, 'e2e'],
  [/^apps\/web\/(src|public)\/|^packages\/(api-client|design-system|localization)\//u, 'web'],
  [
    /^apps\/web\/(index\.html|vite\.config\.ts|tsconfig[^/]*\.json|eslint\.config\.[a-z]+)$/u,
    'web',
  ],
  [/^(docs\/.*|[^/]+\.md|.*\/README\.md|CLAUDE\.md)$/u, 'docs'],
];

/** The decision for a list of changed paths. */
export function classify(paths) {
  const checks = new Set();
  const specs = new Set();
  let profile = 'changed';
  const reasons = [];
  for (const path of paths) {
    const rule = RULES.find(([pattern]) => pattern.test(path));
    const kind = rule ? rule[1] : 'pr';
    if (!rule) reasons.push(`unclassified: ${path}`);
    if (kind === 'full') {
      profile = 'full';
      reasons.push(`complete profile required by ${path}`);
    } else if (kind === 'pr') {
      if (profile !== 'full') profile = 'pr';
      if (rule) reasons.push(`pr profile required by ${path}`);
    } else if (kind === 'spec') {
      checks.add('e2e');
      checks.add('web');
      specs.add(path.replace(/^apps\/web\//u, ''));
    } else if (kind === 'e2e') {
      checks.add('e2e');
      checks.add('web');
      specs.add('*');
    } else if (kind === 'web') {
      checks.add('web');
      checks.add('e2e');
      specs.add('*');
    } else {
      checks.add(kind);
    }
  }
  if (profile !== 'changed') return { profile, checks: [], specs: [], reasons };
  const all = specs.has('*');
  return { profile, checks: [...checks].sort(), specs: all ? ['*'] : [...specs].sort(), reasons };
}

function changedPaths(base, head) {
  const out = execFileSync('git', ['diff', '--name-only', '--no-renames', `${base}...${head}`], {
    encoding: 'utf8',
  });
  return out.split('\n').filter(Boolean);
}

function main(argv) {
  let paths;
  if (argv[0] === '--paths') {
    paths = argv.slice(1);
  } else {
    if (!argv[0]) {
      console.log('profile=pr');
      console.log('reason=no base commit');
      return 0;
    }
    try {
      paths = changedPaths(argv[0], argv[1] ?? 'HEAD');
    } catch {
      console.log('profile=pr');
      console.log('reason=unreadable diff');
      return 0;
    }
  }
  const decision = classify(paths);
  console.log(`profile=${decision.profile}`);
  for (const check of decision.checks) console.log(`check=${check}`);
  for (const spec of decision.specs) console.log(`spec=${spec}`);
  for (const reason of decision.reasons) console.log(`reason=${reason}`);
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  process.exitCode = main(process.argv.slice(2));
}
