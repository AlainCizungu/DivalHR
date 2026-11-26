// LAUNCH-004 (Issue #228): the commercial truth test. Runs under `node --test` (pnpm ops:test).
import assert from 'node:assert/strict';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  GAPS_DOC,
  PARTIES,
  STATUSES,
  TRUTH_DOC,
  loadSource,
  renderGaps,
  renderTruth,
} from './commercial-docs.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const data = loadSource();
const byId = new Map(data.capabilities.map((c) => [c.id, c]));

/** C3 forbidden claims, English and French (one source shared with the web component tests). */
const FORBIDDEN_SOURCE = JSON.parse(
  readFileSync(join(ROOT, 'apps/web/src/features/commercial/forbidden-claims.json'), 'utf8'),
);
export const FORBIDDEN = FORBIDDEN_SOURCE.claims.map(
  (claim) => new RegExp(claim, FORBIDDEN_SOURCE.flags),
);
const SYNTHETIC_INTERNALS = new RegExp(FORBIDDEN_SOURCE.syntheticInternals, FORBIDDEN_SOURCE.flags);

function localeCommercial(lang) {
  const path = join(ROOT, `packages/localization/locales/${lang}/common.json`);
  const json = JSON.parse(readFileSync(path, 'utf8'));
  return json.commercial;
}

function strings(value, out = []) {
  if (typeof value === 'string') out.push(value);
  else if (value && typeof value === 'object') Object.values(value).forEach((v) => strings(v, out));
  return out;
}

test('the closed status catalogue is exactly four statuses with distinct English and French labels', () => {
  assert.deepEqual(Object.keys(data.statuses), STATUSES);
  for (const lang of ['en', 'fr']) {
    const labels = STATUSES.map((s) => data.statuses[s][lang]);
    assert.equal(new Set(labels).size, 4, `${lang} labels are distinct`);
  }
  assert.equal(data.statuses.PILOT_AVAILABLE.en, 'Available in the pilot');
  assert.equal(data.statuses.PILOT_AVAILABLE.fr, 'Disponible dans le pilote');
  assert.equal(data.statuses.CONFIGURATION_REQUIRED.fr, 'Disponible après configuration');
  assert.equal(data.statuses.PARTNER_REQUIRED.fr, 'Partenaire approuvé requis');
  assert.equal(data.statuses.PLANNED.fr, 'Prévu');
});

test('commercial locale status labels equal the source labels in both languages', () => {
  for (const lang of ['en', 'fr']) {
    const commercial = localeCommercial(lang);
    if (!commercial) continue; // before the page copy exists
    for (const status of STATUSES) {
      assert.equal(commercial.status?.[status], data.statuses[status][lang], `${lang} ${status}`);
    }
  }
});

test('every capability uses a status of the closed catalogue and has complete bilingual wording', () => {
  const ids = new Set();
  for (const c of data.capabilities) {
    assert.ok(!ids.has(c.id), `duplicate id ${c.id}`);
    ids.add(c.id);
    assert.match(c.id, /^[a-z0-9]+(-[a-z0-9]+)*$/u);
    assert.ok(STATUSES.includes(c.status), `${c.id}: status ${c.status} is outside the catalogue`);
    for (const f of ['en', 'fr']) {
      assert.ok(c[f]?.trim(), `${c.id}: missing ${f} wording`);
      assert.ok(c.name[f]?.trim(), `${c.id}: missing ${f} name`);
    }
    assert.notEqual(c.en, c.fr, `${c.id}: French wording is a copy of the English`);
    for (const f of ['dependency', 'activation', 'nextIssue'])
      assert.ok(c[f]?.trim(), `${c.id}: ${f}`);
    assert.match(c.nextIssue, /^(UNSCHEDULED|#\d+)$/u, `${c.id}: next issue`);
  }
});

test('every landing-page capability id is in the truth file and every truth capability is on the page', () => {
  const used = new Set();
  for (const p of data.pillars) p.capabilities.forEach((id) => used.add(id));
  for (const list of Object.values(data.sections)) list.forEach((id) => used.add(id));
  for (const id of used) assert.ok(byId.has(id), `landing references unknown capability ${id}`);
  for (const id of byId.keys())
    assert.ok(used.has(id), `capability ${id} is not placed on the page`);
});

test('PILOT_AVAILABLE items carry evidence; other items carry a gap entry; gaps are never complete', () => {
  for (const c of data.capabilities) {
    if (c.status === 'PILOT_AVAILABLE') {
      assert.match(
        c.evidence,
        /(PR|Issue) #\d+|release [0-9a-f]{7}|docs\/[A-Za-z-]+\.md/u,
        `${c.id}: evidence`,
      );
      assert.match(c.evidence, /release 225da6c|deployed/u, `${c.id}: deployment evidence`);
      assert.equal(
        c.gap,
        undefined,
        `${c.id}: an available item must not keep a gap (reclassify, then remove it)`,
      );
    } else {
      assert.ok(c.gap, `${c.id}: ${c.status} needs a gap entry`);
      assert.ok(PARTIES.includes(c.gap.party), `${c.id}: responsible party`);
      assert.deepEqual(Object.keys(c.gap.blocks), ['pilot', 'production', 'partner', 'marketing']);
      for (const v of Object.values(c.gap.blocks)) assert.equal(typeof v, 'boolean');
      assert.ok(c.gap.review?.trim(), `${c.id}: review condition`);
      for (const bad of ['complete', 'completed', 'done', 'closed', 'resolved']) {
        assert.equal(
          c.gap[bad],
          undefined,
          `${c.id}: a gap is removed or reclassified, never marked ${bad}`,
        );
      }
      assert.doesNotMatch(
        JSON.stringify(c.gap),
        /\b20\d\d-\d\d-\d\d\b|Q[1-4] 20\d\d/u,
        `${c.id}: no date estimate`,
      );
    }
    assert.doesNotMatch(c.en + c.fr, /coming soon|bientôt|prochainement/iu, `${c.id}: stale label`);
  }
});

test('the two Markdown files are the rendering of the structured source', () => {
  assert.equal(readFileSync(TRUTH_DOC, 'utf8'), renderTruth(data));
  assert.equal(readFileSync(GAPS_DOC, 'utf8'), renderGaps(data));
});

test('the gap file lists exactly the non-available capabilities', () => {
  const text = readFileSync(GAPS_DOC, 'utf8');
  const listed = [...text.matchAll(/^\| `([a-z0-9-]+)` \|/gmu)].map((m) => m[1]);
  const expected = data.capabilities.filter((c) => c.status !== 'PILOT_AVAILABLE').map((c) => c.id);
  assert.deepEqual(listed, expected);
});

test('partner-dependent capabilities carry the exact partner disclosure or a stricter limit', () => {
  assert.equal(
    data.partnerDisclosure.en,
    'Available only through approved partners and customer-specific activation. DivalHR does not itself make credit decisions, lend money, issue insurance or guarantee approval.',
  );
  assert.equal(
    data.partnerDisclosure.fr,
    'Disponible uniquement par l’intermédiaire de partenaires approuvés et après activation pour le client. DivalHR ne prend pas lui-même de décision de crédit, n’accorde pas de prêt, n’émet pas d’assurance et ne garantit aucune approbation.',
  );
  const salary = byId.get('salary-advances');
  assert.equal(salary.en, data.partnerDisclosure.en);
  assert.equal(salary.fr, data.partnerDisclosure.fr);
  for (const id of [
    'partner-referral',
    'real-payment-delivery',
    'partner-health-insurance-telecom',
  ]) {
    assert.equal(byId.get(id).status, 'PARTNER_REQUIRED');
  }
});

test('forbidden claims are absent from the capability wording, the status labels and the page copy', () => {
  const texts = [];
  for (const c of data.capabilities)
    texts.push(
      [`${c.id} en`, c.en],
      [`${c.id} fr`, c.fr],
      [`${c.id} name en`, c.name.en],
      [`${c.id} name fr`, c.name.fr],
    );
  for (const [s, l] of Object.entries(data.statuses))
    texts.push([`${s} en`, l.en], [`${s} fr`, l.fr]);
  for (const lang of ['en', 'fr']) {
    const commercial = localeCommercial(lang);
    if (commercial) for (const s of strings(commercial)) texts.push([`locale ${lang}`, s]);
  }
  for (const [where, text] of texts) {
    for (const pattern of FORBIDDEN)
      assert.doesNotMatch(text, pattern, `${where}: forbidden claim ${pattern}`);
    assert.doesNotMatch(
      text,
      SYNTHETIC_INTERNALS,
      `${where}: synthetic-provider internals on the landing page`,
    );
  }
});

test('no integration or partner brand is described as connected without evidence', () => {
  for (const id of [
    'real-payment-delivery',
    'accounting-system-connectors',
    'sso-federation',
    'partner-network-activation',
  ]) {
    assert.notEqual(
      byId.get(id).status,
      'PILOT_AVAILABLE',
      `${id} cannot be available without a real connection`,
    );
  }
});

test('the commercial locale has the same keys in English and French and no copied French', () => {
  const en = localeCommercial('en');
  const fr = localeCommercial('fr');
  if (!en && !fr) return;
  assert.ok(en && fr, 'the commercial copy must exist in both languages');
  const flat = (o, p = '') =>
    Object.entries(o).flatMap(([k, v]) =>
      v && typeof v === 'object' ? flat(v, `${p}${k}.`) : [[`${p}${k}`, v]],
    );
  const e = new Map(flat(en));
  const f = new Map(flat(fr));
  assert.deepEqual([...f.keys()].sort(), [...e.keys()].sort(), 'key sets differ');
  // Words and names that are legitimately identical in both languages.
  const SAME_IN_BOTH = new Set([
    'nav.menu',
    'nav.solutions',
    'outcomes.kicker',
    'mobile.kicker',
    'footer.contact',
    'footer.tagline',
    'footer.copyright',
    'integrations.entries.whatsapp',
  ]);
  for (const [key, text] of e) {
    if (SAME_IN_BOTH.has(key) || text.length < 4) continue;
    assert.notEqual(f.get(key), text, `commercial.${key}: French equals English`);
  }
});

test('the launch-truth files exist where the evidence map and backlog name them', () => {
  assert.ok(existsSync(TRUTH_DOC) && existsSync(GAPS_DOC));
});

test('screenshot manifest: every file exists within the 300 KB budget, with localized alternative text', () => {
  const media = JSON.parse(
    readFileSync(join(ROOT, 'apps/web/src/features/commercial/media.json'), 'utf8'),
  );
  assert.match(media.release, /^[0-9a-f]{40}$/u);
  assert.ok(media.shots.length >= 8, 'at least eight real screenshots');
  for (const locale of ['en', 'fr']) {
    const mine = media.shots.filter((shot) => shot.locale === locale);
    assert.ok(mine.length >= 4, `${locale}: at least four screenshots`);
    assert.ok(
      mine.some((shot) => shot.viewport === 'desktop'),
      `${locale}: a desktop capture`,
    );
    assert.ok(
      mine.some((shot) => shot.viewport === 'mobile'),
      `${locale}: a mobile capture`,
    );
    const texts = localeCommercial(locale).media.shots;
    for (const shot of mine) {
      assert.ok((texts[shot.id]?.alt ?? '').length > 60, `${locale}/${shot.id}: descriptive alt`);
      assert.ok((texts[shot.id]?.title ?? '').length > 0, `${locale}/${shot.id}: title`);
    }
  }
  for (const shot of media.shots) {
    assert.equal(shot.width, Math.max(...shot.widths));
    for (const width of shot.widths) {
      for (const extension of ['avif', 'webp', 'jpg']) {
        const file = join(ROOT, 'apps/web/public', `${shot.base}-${width}.${extension}`);
        assert.ok(existsSync(file), `missing ${shot.base}-${width}.${extension}`);
        assert.ok(statSync(file).size <= 300 * 1024, `${file} is over 300 KB`);
      }
    }
  }
  const preview = join(ROOT, 'apps/web/public/social-preview.png');
  assert.ok(existsSync(preview) && statSync(preview).size <= 300 * 1024, 'social preview exists');
});

test('static index.html carries the French metadata defaults, equal to the locale strings', () => {
  const html = readFileSync(join(ROOT, 'apps/web/index.html'), 'utf8');
  const meta = localeCommercial('fr').meta;
  const unescape = (value) =>
    value.replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&#x27;', "'");
  const content = (key) => {
    const match = new RegExp(`<meta\\s+(?:name|property)="${key}"\\s+content="([^"]*)"`, 'u').exec(
      html,
    );
    assert.ok(match, `index.html lacks ${key}`);
    return unescape(match[1]);
  };
  assert.match(html, /<html lang="fr">/u);
  assert.equal(content('description'), meta.description);
  assert.equal(content('og:title'), meta.ogTitle);
  assert.equal(content('og:description'), meta.ogDescription);
  assert.doesNotMatch(html, /og:image/u, 'no environment-specific image URL in the static file');
  for (const claim of FORBIDDEN) assert.doesNotMatch(html, claim);
});
