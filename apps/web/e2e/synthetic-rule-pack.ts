import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { sqlScript } from './support';

// MVP-094B (Issue #179): the test-only SYNTHETIC VERIFIED rule pack. No endpoint can verify a
// pack, so the browser journeys insert one with synthetic-verified-rule-pack.sql through
// `sqlScript` (support.ts), the same psql path as `sql()`. Nothing else references that file: not
// a migration, not seed data, not a dev or deployment script.
const FIXTURE = fileURLToPath(new URL('./synthetic-verified-rule-pack.sql', import.meta.url));

/** Creates a VERIFIED pack `code` (it must start with SYNTHETIC-TEST-) and returns its id. */
export function createSyntheticVerifiedPack(code: string): string {
  if (!/^SYNTHETIC-TEST-[A-Z0-9-]+$/u.test(code)) {
    throw new Error('a synthetic rule pack code must start with SYNTHETIC-TEST-');
  }
  const packId = randomUUID();
  sqlScript(readFileSync(FIXTURE, 'utf8'), { pack_id: packId, pack_code: code });
  return packId;
}

// MVP-097A (Issue #200): a SYNTHETIC DRAFT pack with reference cases for the governance journeys. No
// endpoint creates a pack or its cases, so the journeys insert one with
// synthetic-governed-draft-pack.sql, as the database owner. It states no law (the effective interval
// lies in 2090) and never appears in a migration, a seed or a deployment script.
const DRAFT_FIXTURE = fileURLToPath(
  new URL('./synthetic-governed-draft-pack.sql', import.meta.url),
);

/**
 * Creates a DRAFT pack `code` (it must start with SYNTHETIC-TEST-) and returns its id. With
 * `withEvidence` false the source evidence is left empty, so a submission must be refused.
 */
export function createSyntheticDraftPack(code: string, withEvidence = true): string {
  if (!/^SYNTHETIC-TEST-[A-Z0-9-]+$/u.test(code)) {
    throw new Error('a synthetic rule pack code must start with SYNTHETIC-TEST-');
  }
  const packId = randomUUID();
  sqlScript(readFileSync(DRAFT_FIXTURE, 'utf8'), {
    pack_id: packId,
    pack_code: code,
    with_evidence: withEvidence ? 'true' : 'false',
  });
  return packId;
}
