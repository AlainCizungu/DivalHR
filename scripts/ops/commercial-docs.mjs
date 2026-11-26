#!/usr/bin/env node
// LAUNCH-004 (Issue #228): renders docs/COMMERCIAL-LAUNCH-TRUTH.md and docs/COMMERCIAL-LAUNCH-GAPS.md
// from the single structured source the landing page also imports:
//   apps/web/src/features/commercial/launch-truth.json
// Usage: node scripts/ops/commercial-docs.mjs [--write | --check]   (default: --check)
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
export const SOURCE = join(ROOT, 'apps/web/src/features/commercial/launch-truth.json');
export const TRUTH_DOC = join(ROOT, 'docs/COMMERCIAL-LAUNCH-TRUTH.md');
export const GAPS_DOC = join(ROOT, 'docs/COMMERCIAL-LAUNCH-GAPS.md');

export const STATUSES = ['PILOT_AVAILABLE', 'CONFIGURATION_REQUIRED', 'PARTNER_REQUIRED', 'PLANNED'];
export const PARTIES = ['DIVALHR', 'CUSTOMER', 'COUNSEL', 'PARTNER', 'REGULATOR'];

export function loadSource() {
  return JSON.parse(readFileSync(SOURCE, 'utf8'));
}

const cell = (text) => String(text).replaceAll('|', '\\|').replaceAll('\n', ' ');

export function renderTruth(data) {
  const lines = [
    '# Commercial launch truth',
    '',
    'Canonical reconciliation file for every change to the public commercial page (LAUNCH-004, Issue #228). Generated from `apps/web/src/features/commercial/launch-truth.json` by `node scripts/ops/commercial-docs.mjs --write`; edit the JSON, never this table. `scripts/ops/commercial-truth.test.mjs` fails when the two drift. This is a commercial framework, not legal advice or certification.',
    '',
    `Release reconciled: \`${data.release}\`. Controlled pilot; DivalHR is not generally available and is not production-certified.`,
    '',
    '## Status catalogue (closed)',
    '',
    '| Status | English label | French label | Meaning |',
    '| --- | --- | --- | --- |',
    `| PILOT_AVAILABLE | ${data.statuses.PILOT_AVAILABLE.en} | ${data.statuses.PILOT_AVAILABLE.fr} | Implemented, merged, deployed and accepted on hr-dev; may be demonstrated with synthetic data; subject to the controlled-pilot terms. |`,
    `| CONFIGURATION_REQUIRED | ${data.statuses.CONFIGURATION_REQUIRED.en} | ${data.statuses.CONFIGURATION_REQUIRED.fr} | Implemented, but needs customer-specific configuration, reviewed rules or approved operational setup before use. |`,
    `| PARTNER_REQUIRED | ${data.statuses.PARTNER_REQUIRED.en} | ${data.statuses.PARTNER_REQUIRED.fr} | The workflow exists, but a real bank, operator, lender, insurer or telecom provider must be contracted, approved and technically activated. No real connection or funds movement may be implied. |`,
    `| PLANNED | ${data.statuses.PLANNED.en} | ${data.statuses.PLANNED.fr} | Not presently delivered for pilot use; roadmap direction only. |`,
    '',
    '## Capabilities',
    '',
    '| Capability ID | Commercial capability | Status | Evidence | English public wording | French public wording | Remaining dependency | Activation condition | Next issue |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const c of data.capabilities) {
    lines.push(
      `| \`${c.id}\` | ${cell(c.name.en)} | ${c.status} | ${cell(c.evidence)} | ${cell(c.en)} | ${cell(c.fr)} | ${cell(c.dependency)} | ${cell(c.activation)} | ${cell(c.nextIssue)} |`,
    );
  }
  lines.push(
    '',
    '## Required partner disclosure',
    '',
    `English: ${data.partnerDisclosure.en}`,
    '',
    `French: ${data.partnerDisclosure.fr}`,
    '',
  );
  return lines.join('\n');
}

export function renderGaps(data) {
  const gaps = data.capabilities.filter((c) => c.status !== 'PILOT_AVAILABLE');
  const yes = (b) => (b ? 'blocks' : 'no');
  const lines = [
    '# Commercial launch gaps',
    '',
    'Every customer-visible capability that is not `PILOT_AVAILABLE` (LAUNCH-004, Issue #228). Generated from `apps/web/src/features/commercial/launch-truth.json` by `node scripts/ops/commercial-docs.mjs --write`. A gap is never marked complete here: when the activation condition is met the capability is reclassified in the source and its entry disappears. No date estimate is recorded. This is a commercial framework, not legal advice or certification.',
    '',
    `${gaps.length} open gaps.`,
    '',
    '| Capability ID | Status | Dependency | Responsible | Related issue | Controlled pilot | Production launch | Partner activation | Public marketing | Next review condition | Wording currently allowed (EN / FR) |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const c of gaps) {
    const g = c.gap;
    lines.push(
      `| \`${c.id}\` | ${c.status} | ${cell(c.dependency)} | ${g.party} | ${cell(c.nextIssue)} | ${yes(g.blocks.pilot)} | ${yes(g.blocks.production)} | ${yes(g.blocks.partner)} | ${yes(g.blocks.marketing)} | ${cell(g.review)} | EN: ${cell(c.en)} FR: ${cell(c.fr)} |`,
    );
  }
  lines.push('');
  return lines.join('\n');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const data = loadSource();
  const outputs = [
    [TRUTH_DOC, renderTruth(data)],
    [GAPS_DOC, renderGaps(data)],
  ];
  if (process.argv.includes('--write')) {
    for (const [path, text] of outputs) writeFileSync(path, text);
    console.log('wrote docs/COMMERCIAL-LAUNCH-TRUTH.md and docs/COMMERCIAL-LAUNCH-GAPS.md');
  } else {
    let bad = false;
    for (const [path, text] of outputs) {
      if (readFileSync(path, 'utf8') !== text) {
        console.error(`stale: ${path}; run node scripts/ops/commercial-docs.mjs --write`);
        bad = true;
      }
    }
    process.exit(bad ? 1 : 0);
  }
}
