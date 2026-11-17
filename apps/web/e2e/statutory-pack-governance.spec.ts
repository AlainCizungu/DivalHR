import { expect, test, type Locator, type Page } from '@playwright/test';
import { CORE_API, USERS, expectAccessible, primaryNav, signIn, subjectLock } from './support';
import { acquireStatutoryPackLock } from './statutory-pack-lock';
import { createSyntheticDraftPack } from './synthetic-rule-pack';

// MVP-097A (Issue #200, ADR 0040): exactly two journeys, with synthetic data only. The synthetic
// packs (SYNTHETIC-TEST-*, effective in 2090) prove the governance mechanism only: they state no law
// and carry no legal meaning. The real DRC candidate CD-SALARY-PREVIEW is only looked at, never
// validated, and stays DRAFT.
// (1) English: a platform administrator opens the platform page, runs the technical validation of a
//     synthetic pack and submits it for review; a different seeded platform administrator
//     (dev-platform-reviewer, own TOTP) approves it with a confirmation and schedules it; the
//     tenant administrator has no route and, through the API, only the read-only applicability
//     summary (no formula, note or evidence digest).
// (2) French: the real DRC candidate is visibly DRAFT, a self-approval is refused, and a pack with
//     incomplete evidence cannot be submitted for review.
// Calculation vectors, the full state machine, races, rollbacks and the database invariants are
// covered by the Core tests, not here. axe, a keyboard-only step, reflow at 320, 390 and 640 px
// (200 % zoom of a 1280 px window), browser storage and URLs are checked. No screenshot gate, no
// retry and no arbitrary sleep: every wait is a web-first assertion. The command limit of one
// subject (5 per minute) holds: the first administrator sends 5 commands, the second 4.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const PACK_A = `SYNTHETIC-TEST-GA-${stamp}`;
const PACK_B = `SYNTHETIC-TEST-GB-${stamp}`;
const PACK_C = `SYNTHETIC-TEST-GC-${stamp}`;
const REAL_PACK = 'CD-SALARY-PREVIEW';
const SCHEDULED_FROM = '2090-02-01';
const EVIDENCE_MARKERS = ['example.test/synthetic-source', 'd'.repeat(64)];

/** Client-side navigation keeps the in-memory session (a reload would sign the user out). */
async function navigateInApp(page: Page, path: string) {
  await page.evaluate((target) => {
    window.history.pushState({}, '', target);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

/** axe at the top of the page (the sticky top bar would otherwise cover the last control). */
async function accessible(page: Page) {
  await page.evaluate(() => {
    window.scrollTo(0, 0);
  });
  await expectAccessible(page);
}

async function storage(page: Page): Promise<string> {
  return page.evaluate(() => {
    const values: string[] = [];
    for (const store of [window.localStorage, window.sessionStorage]) {
      for (let index = 0; index < store.length; index++) {
        const key = store.key(index) ?? '';
        values.push(key, store.getItem(key) ?? '');
      }
    }
    return values.join('\n');
  });
}

async function noOverflow(page: Page) {
  for (const viewport of [
    { width: 390, height: 800 },
    { width: 320, height: 720 },
    // 200 % zoom of a 1280 px window.
    { width: 640, height: 400 },
  ]) {
    await page.setViewportSize(viewport);
    const wide = await page.evaluate(() =>
      document.documentElement.scrollWidth <= window.innerWidth
        ? []
        : Array.from(document.body.querySelectorAll('*'))
            .filter((element) => element.getBoundingClientRect().right > window.innerWidth + 1)
            .slice(0, 6)
            .map(
              (element) =>
                `${element.tagName.toLowerCase()}[${element.getAttribute('data-testid') ?? element.className}]=${String(Math.round(element.getBoundingClientRect().right))}`,
            ),
    );
    expect(wide, `no horizontal overflow at ${viewport.width} px`).toEqual([]);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

/** Keyboard-only: tab forward until the control has focus (bounded). */
async function keyboardTabReaches(page: Page, target: Locator) {
  for (let step = 0; step < 160; step++) {
    if (await target.evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  expect(await target.evaluate((el) => el === document.activeElement)).toBe(true);
}

/** The row of a pack, following "Show more" while it is not on the first page (packs are never deleted). */
async function findRow(page: Page, code: string) {
  const row = page.locator(`[data-testid="statutory-pack-row"][data-pack-code="${code}"]`);
  const more = page.getByTestId('statutory-packs-more');
  await expect(page.getByTestId('statutory-packs-table')).toBeVisible();
  for (let step = 0; step < 40 && (await row.count()) === 0 && (await more.count()) > 0; step++) {
    const before = await page.getByTestId('statutory-pack-row').count();
    await more.click();
    await expect.poll(() => page.getByTestId('statutory-pack-row').count()).toBeGreaterThan(before);
  }
  await expect(row).toBeVisible();
  return row;
}

async function openRow(page: Page, code: string) {
  await (await findRow(page, code)).getByRole('link').click();
  await expect(page.getByTestId('statutory-pack-detail')).toBeVisible();
}

/** Opens the platform page from the navigation and one pack version from its list. */
async function openPack(page: Page, linkName: string, code: string) {
  await primaryNav(page).getByRole('link', { name: linkName, exact: true }).click();
  await expect(page.getByTestId('statutory-packs-page')).toBeVisible();
  await openRow(page, code);
}

async function act(page: Page, command: string, confirm = false) {
  await page.getByTestId(`statutory-pack-action-${command}`).click();
  if (confirm) await page.getByTestId('statutory-pack-confirm').check();
  await page.getByTestId('statutory-pack-submit').click();
}

const state = (page: Page) => page.getByTestId('statutory-pack-state');

let releaseAdmin: () => void = () => undefined;
let releaseReviewer: () => void = () => undefined;
let releaseStatutory: () => void = () => undefined;
let scheduledPack = '';

test.describe.serial('MVP-097A: governed statutory pack lifecycle', () => {
  test.beforeAll(async () => {
    test.setTimeout(600_000);
    releaseAdmin = await subjectLock(USERS.platformAdmin[0]);
    releaseReviewer = await subjectLock(USERS.platformReviewer[0]);
    // R207-3: the packs below stay SCHEDULED for both journeys, and creating a verified pack in
    // another spec retires every scheduled one. Nobody creates one while this describe runs.
    releaseStatutory = await acquireStatutoryPackLock();
    createSyntheticDraftPack(PACK_A);
    createSyntheticDraftPack(PACK_B);
    createSyntheticDraftPack(PACK_C, false);
  });

  test.afterAll(() => {
    releaseStatutory();
    releaseAdmin();
    releaseReviewer();
  });

  test('English: a platform administrator validates and submits a synthetic pack, a different platform administrator approves and schedules it, and the tenant administrator only reads', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    const urls: string[] = [];
    admin.on('request', (request) => urls.push(request.url()));
    await signIn(admin, USERS.platformAdmin, 'en');

    // 1. The platform page, reached from the navigation: the permanent notice, a state badge with text.
    await openPack(admin, 'Statutory packs', PACK_A);
    await expect(admin.getByRole('heading', { level: 1 })).toContainText(PACK_A);
    await expect(admin.getByTestId('statutory-pack-legal-notice')).toContainText(
      'Technical validation is not legal approval.',
    );
    await expect(state(admin)).toHaveText('Draft');
    await expect(admin.getByTestId('statutory-pack-draft-notice')).toHaveText(
      'DRAFT — not approved for operational payroll',
    );
    await accessible(admin);
    await noOverflow(admin);

    // 2. Technical validation (keyboard first): the run is recorded and the state moves.
    const validate = admin.getByTestId('statutory-pack-action-validate');
    await keyboardTabReaches(admin, validate);
    await admin.keyboard.press('Enter');
    await keyboardTabReaches(admin, admin.getByTestId('statutory-pack-submit'));
    await admin.keyboard.press('Enter');
    await expect(admin.getByTestId('statutory-pack-announcement')).toHaveText(
      'Technical validation passed.',
    );
    await expect(state(admin)).toHaveText('Technically validated');
    await expect(admin.getByTestId('statutory-pack-run').first()).toHaveAttribute(
      'data-outcome',
      'PASSED',
    );
    await expect(admin.getByTestId('statutory-pack-evidence-status')).toContainText(
      'Evidence complete',
    );

    // 3. Submission for review: no confirmation, the approval actions appear.
    await act(admin, 'submit-review');
    await expect(admin.getByTestId('statutory-pack-action-approve')).toBeVisible();
    await expect(admin.getByTestId('statutory-pack-self-hint')).toBeVisible();
    await expect(state(admin)).toHaveText('Technically validated');
    await accessible(admin);

    // 4. A different seeded platform administrator approves (confirmation) and schedules.
    const reviewerContext = await browser.newContext();
    const reviewer = await reviewerContext.newPage();
    await signIn(reviewer, USERS.platformReviewer, 'en');
    await openPack(reviewer, 'Statutory packs', PACK_A);
    await reviewer.getByTestId('statutory-pack-action-approve').click();
    await expect(reviewer.getByTestId('statutory-pack-submit')).toBeDisabled();
    await reviewer.getByTestId('statutory-pack-confirm').check();
    await reviewer.getByTestId('statutory-pack-submit').click();
    await expect(state(reviewer)).toHaveText('Reviewed');
    await expect(reviewer.getByTestId('statutory-pack-review')).toContainText('approved');
    await reviewer.getByTestId('statutory-pack-action-schedule').click();
    await reviewer.getByTestId('statutory-pack-effective-from').fill(SCHEDULED_FROM);
    await reviewer.getByTestId('statutory-pack-confirm').check();
    await reviewer.getByTestId('statutory-pack-submit').click();
    await expect(state(reviewer)).toHaveText('Scheduled');
    await expect(reviewer.getByTestId('statutory-pack-history')).toContainText('Scheduled');
    await accessible(reviewer);
    scheduledPack = PACK_A;

    // 5. The tenant administrator has no route, and the API gives the read-only summary only.
    const tenantContext = await browser.newContext();
    const tenant = await tenantContext.newPage();
    const bearer = await signIn(tenant, USERS.adminA, 'en');
    await expect(
      primaryNav(tenant).getByRole('link', { name: 'Statutory packs', exact: true }),
    ).toHaveCount(0);
    await navigateInApp(tenant, '/admin/statutory-packs');
    await expect(tenant.getByTestId('not-authorized')).toBeVisible();
    await expect(tenant.getByTestId('statutory-packs-page')).toHaveCount(0);
    const refused = await tenant.request.get(`${CORE_API}/platform/statutory-packs`, {
      headers: { Authorization: bearer() },
    });
    expect(refused.status()).toBe(403);
    const created = await tenant.request.post(`${CORE_API}/legal-entities`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-gov-le-${stamp}-0001` },
      data: {
        code: `GOV-${stamp}`,
        name: `Governance entity ${stamp}`,
        countryCode: 'CD',
        effectiveFrom: '2024-01-01',
      },
    });
    expect(created.status()).toBe(201);
    const entityId = ((await created.json()) as { id: string }).id;
    const read = await tenant.request.get(
      `${CORE_API}/legal-entities/${entityId}/statutory-pack-applicability?payrollPeriod=2090-06`,
      { headers: { Authorization: bearer() } },
    );
    expect(read.status()).toBe(200);
    expect(read.headers()['cache-control']).toContain('no-store');
    const summary = (await read.json()) as Record<string, unknown>;
    // A SCHEDULED version is not an ACTIVE one: nothing applies yet, and nothing else is disclosed.
    expect(summary.applicable).toBe(false);
    expect(Object.keys(summary).sort()).toEqual(
      [
        'applicable',
        'country',
        'effectiveFrom',
        'effectiveTo',
        'legalEntityId',
        'packCode',
        'payrollPeriod',
        'semanticVersion',
        'sourceAuthority',
        'sourcePublishedOn',
        'sourceReference',
        'sourceTitle',
        'state',
        'version',
      ].sort(),
    );
    const body = JSON.stringify(summary);
    for (const marker of ['rateBp', 'rules', 'note', 'sha256', 'fixture']) {
      expect(body).not.toContain(marker);
    }

    // 6. Hygiene: no evidence in a URL or in the browser storage.
    for (const page of [admin, reviewer, tenant]) {
      const stored = await storage(page);
      for (const marker of EVIDENCE_MARKERS) expect(stored).not.toContain(marker);
    }
    for (const url of urls) {
      for (const marker of EVIDENCE_MARKERS) expect(url).not.toContain(marker);
    }
    await adminContext.close();
    await reviewerContext.close();
    await tenantContext.close();
  });

  test('French: the real DRC candidate is visibly DRAFT, a self-approval is refused and incomplete evidence cannot be submitted', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    expect(scheduledPack, 'journey 1 ran').not.toBe('');
    const adminContext = await browser.newContext({ locale: 'fr-FR' });
    const admin = await adminContext.newPage();
    await signIn(admin, USERS.platformAdmin, 'fr');

    // 1. The real DRC candidate: DRAFT, with the French wording, and it is only looked at.
    await primaryNav(admin)
      .getByRole('link', { name: 'Référentiels légaux de paie', exact: true })
      .click();
    await expect(admin.getByRole('heading', { level: 1 })).toHaveText(
      'Référentiels légaux de paie',
    );
    await expect(admin.getByTestId('statutory-pack-legal-notice')).toContainText(
      'La validation technique n’est pas une approbation légale.',
    );
    const real = await findRow(admin, REAL_PACK);
    await expect(real).toHaveAttribute('data-state', 'DRAFT');
    await expect(real.getByTestId('statutory-pack-draft-notice')).toHaveText(
      'BROUILLON — non approuvé pour la paie opérationnelle',
    );
    await accessible(admin);
    await noOverflow(admin);
    await real.getByRole('link').click();
    await expect(admin.getByTestId('statutory-pack-detail')).toBeVisible();
    await expect(state(admin)).toHaveText('Brouillon');
    await expect(admin.getByTestId('statutory-pack-evidence-status')).toContainText(
      'Preuves incomplètes',
    );
    await expect(admin.getByTestId('statutory-pack-draft-notice')).toBeVisible();

    // 2. A self-approval is refused: the same administrator validated, submitted and tries to approve.
    await navigateInApp(admin, '/admin/statutory-packs');
    await openRow(admin, PACK_B);
    await act(admin, 'validate');
    await expect(state(admin)).toHaveText('Validée techniquement');
    await act(admin, 'submit-review');
    await expect(admin.getByTestId('statutory-pack-self-hint')).toBeVisible();
    await act(admin, 'approve', true);
    await expect(admin.getByTestId('statutory-pack-action-failure')).toHaveText(
      /La personne qui a soumis cette version du référentiel ne peut pas aussi la relire/,
    );
    await expect(admin.getByTestId('statutory-pack-action-failure')).toHaveAttribute(
      'data-code',
      'STATUTORY_PACK_SELF_APPROVAL_FORBIDDEN',
    );
    await expect(state(admin)).toHaveText('Validée techniquement');
    await accessible(admin);
    await noOverflow(admin);

    // 3. Incomplete evidence: the second administrator validates, the submission is refused.
    const reviewerContext = await browser.newContext({ locale: 'fr-FR' });
    const reviewer = await reviewerContext.newPage();
    await signIn(reviewer, USERS.platformReviewer, 'fr');
    await openPack(reviewer, 'Référentiels légaux de paie', PACK_C);
    await expect(reviewer.getByTestId('statutory-pack-evidence-status')).toContainText(
      'Preuves incomplètes',
    );
    await act(reviewer, 'validate');
    await expect(state(reviewer)).toHaveText('Validée techniquement');
    await act(reviewer, 'submit-review');
    await expect(reviewer.getByTestId('statutory-pack-action-failure')).toHaveText(
      /Les preuves de source de cette version du référentiel sont incomplètes/,
    );
    await expect(reviewer.getByTestId('statutory-pack-action-failure')).toHaveAttribute(
      'data-code',
      'STATUTORY_PACK_EVIDENCE_INCOMPLETE',
    );
    await expect(state(reviewer)).toHaveText('Validée techniquement');
    await accessible(reviewer);
    await noOverflow(reviewer);

    // 4. The scheduled pack of journey 1 shows its history in French.
    await navigateInApp(reviewer, '/admin/statutory-packs');
    await openRow(reviewer, scheduledPack);
    await expect(state(reviewer)).toHaveText('Planifiée');
    await expect(reviewer.getByTestId('statutory-pack-history')).toContainText('Approuvée');
    await adminContext.close();
    await reviewerContext.close();
  });
});
