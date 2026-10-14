import { expect, test, type Browser, type Locator, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import {
  CORE_API,
  acceptInvitation,
  TENANT_A,
  USERS,
  expectAccessible,
  mailTo,
  openSecretLink,
  primaryNav,
  signIn,
  sql,
  typeSecret,
} from './support';

// MVP-070A (Issue #128): weekly draft timesheets with the real identity provider, database and
// server clock. Setup (API, the administrator's existing SSO session): Ana and Cléo are imported
// at a new site; one schedule of today holds Ana's two short shifts and Cléo's one, all already
// ended. Ana's first shift gets its missing record from an administrator's correction (check-in
// and check-out); her second and Cléo's have no attendance. Exactly two journeys:
//  - English: the administrator previews and generates the site's current week (Ana: one READY
//    corrected row and one blocked row; Cléo: one blocked row); Ana then sees only her own draft
//    under "My timesheets";
//  - French : après une nouvelle correction de présence (le second créneau d'Ana), l'administratrice
//    prévisualise puis applique l'actualisation ; la révision 2 et les nouveaux totaux s'affichent.
// axe, reflow at 320 px, 390 px and 200 % zoom, browser storage and URLs are checked. Guards,
// stale previews, bounds, concurrency, tenant scope, audits and query plans are proven at API and
// database level, not here. The site's zone is whichever of the two supported zones is at least
// an hour into its local day, so the past shifts are published for today.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `TE-${stamp}`;
const SITE = `TS-${stamp}`;
const DEPARTMENT = `TD-${stamp}`;
const ANA = `TSA-${stamp}-1`;
const CLEO = `TSC-${stamp}-2`;
const ANA_FAMILY = `Mbala${letters}`;
const CLEO_FAMILY = `Tshilombo${letters}`;
const ANA_EMAIL = `e2e.timesheet.ana.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Tsh!`;
const SITE_NAME = `Site Feuilles ${stamp}`;
const FIRST_EN = `Till shift ${letters}`;
const FIRST_FR = `Poste de caisse ${letters}`;
const SECOND_EN = `Shelving ${letters}`;
const SECOND_FR = `Mise en rayon ${letters}`;
const CLEO_EN = `Stocktake ${letters}`;
const CLEO_FR = `Comptage du stock ${letters}`;
const EXPLANATION = 'The badge reader at the gate was offline during this shift.';

let ZONE = 'Africa/Lubumbashi';
let entityId = '';
let siteId = '';
let monday = '';

/**
 * PR evidence (EVIDENCE=1 only): a screenshot written outside the working tree. The pages hold
 * only this run's generated test data.
 */
async function evidence(page: Page, name: string, region?: Locator) {
  const out = process.env.EVIDENCE_DIR;
  if (!process.env.EVIDENCE || !out) return;
  mkdirSync(out, { recursive: true });
  await page.waitForLoadState('networkidle');
  const path = join(out, `${name}.png`);
  if (region) await region.screenshot({ path, animations: 'disabled' });
  else await page.screenshot({ path, fullPage: true, animations: 'disabled' });
}

function plusDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function employeeId(number: string): string {
  return sql(
    `SELECT id FROM people.employee WHERE tenant_id = '${TENANT_A}' AND employee_number = '${number}'`,
  );
}

/** Lubumbashi, unless it is in the first hour of its day; then Kinshasa (an hour behind). */
function chooseZone(): string {
  return sql(
    `SELECT CASE WHEN (now() AT TIME ZONE 'Africa/Lubumbashi')::time >= time '01:00'
     THEN 'Africa/Lubumbashi' ELSE 'Africa/Kinshasa' END`,
  );
}

/** The site-local date-time `minutes` from now, `YYYY-MM-DDTHH:MM` (the server's own clock). */
function localFromNow(minutes: number): string {
  return sql(
    `SELECT to_char((now() + interval '${minutes} minutes') AT TIME ZONE '${ZONE}',
     'YYYY-MM-DD"T"HH24:MI')`,
  );
}

/** A slot's stored start or end as an RFC 3339 instant in UTC. */
function slotInstant(labelEn: string, column: 'start_at' | 'end_at'): string {
  return sql(
    `SELECT to_char(${column} AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"+00:00"')
     FROM people.work_schedule_slot WHERE tenant_id = '${TENANT_A}' AND label_en = '${labelEn}'`,
  );
}

function slotId(labelEn: string): string {
  return sql(
    `SELECT id FROM people.work_schedule_slot WHERE tenant_id = '${TENANT_A}'
     AND label_en = '${labelEn}'`,
  );
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
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      `no horizontal overflow at ${viewport.width} px`,
    ).toBe(true);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

/**
 * axe at the top of the page: its target-size rule counts a control that the last interaction
 * scrolled under the shell's sticky top bar as obscured. The whole page is still analysed.
 */
async function accessible(page: Page) {
  await page.evaluate(() => {
    window.scrollTo(0, 0);
  });
  await expectAccessible(page);
}

async function clean(page: Page) {
  const markers = new RegExp(
    `${stamp}|${ANA_FAMILY}|${CLEO_FAMILY}|Till shift|caisse|Shelving|rayon|Stocktake|Comptage`,
    'iu',
  );
  expect(await storage(page)).not.toMatch(markers);
  expect(page.url()).not.toMatch(markers);
  expect(page.url()).not.toMatch(/[?&#](code|state|session_state|siteId|weekStart)=/u);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data?: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ts-${key}-${stamp}-0001` },
    ...(data === undefined ? {} : { data }),
  });
}

/** An administrator's correction creating a shift's missing record (MVP-062), over the API. */
async function createMissing(
  page: Page,
  bearer: () => string,
  labelEn: string,
  number: string,
  key: string,
) {
  const proposal = {
    slotId: slotId(labelEn),
    employeeId: employeeId(number),
    checkedInAt: slotInstant(labelEn, 'start_at'),
    checkedOutAt: slotInstant(labelEn, 'end_at'),
    reasonCode: 'DEVICE_OR_CONNECTIVITY',
    explanation: EXPLANATION,
  };
  const preview = await page.request.post(
    `${CORE_API}/attendance/corrections/missing-record-preview`,
    { headers: { Authorization: bearer() }, data: proposal },
  );
  expect(preview.status()).toBe(200);
  const reviewed = (await preview.json()) as { targetVersion: number; previewDigest: string };
  const commit = await post(page, bearer, '/attendance/corrections/missing-record', key, {
    ...proposal,
    targetVersion: reviewed.targetVersion,
    previewDigest: reviewed.previewDigest,
    confirm: true,
  });
  expect(commit.status()).toBe(201);
}

/** Invites an employee, accepts, sets the password and links the access to the employee record. */
async function inviteAndLink(
  page: Page,
  browser: Browser,
  bearer: () => string,
  email: string,
  employee: string,
  key: string,
) {
  expect(
    (
      await post(page, bearer, '/invitations', `inv-${key}`, {
        email,
        role: 'employee',
        locale: 'fr',
      })
    ).status(),
  ).toBe(201);
  const link = /https?:\/\/[^\s"<>]+\/invitation#token=([A-Za-z0-9_-]{43})/u.exec(
    await mailTo(email, /Invitation à rejoindre/u),
  );
  expect(link, 'invitation link').not.toBeNull();
  expect(await acceptInvitation(page, link?.[1] ?? '')).toBe(200);
  const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
    await mailTo(email, /mot de passe|password|actions|compte|account/iu),
  );
  expect(setup, 'password setup link').not.toBeNull();
  const context = await browser.newContext({ locale: 'en-US' });
  const setupPage = await context.newPage();
  await openSecretLink(setupPage, setup?.[0] ?? '');
  const proceed = setupPage.locator('a[href*="login-actions"], #kc-info-message a').first();
  if ((await setupPage.locator('#password-new').count()) === 0 && (await proceed.count()) > 0) {
    await proceed.click();
  }
  await typeSecret(setupPage, '#password-new', PASSWORD);
  await typeSecret(setupPage, '#password-confirm', PASSWORD);
  await setupPage.locator('[type="submit"]').first().click();
  await context.close();

  const lookup = await page.request.post(`${CORE_API}/employees/${employee}/access-link/lookup`, {
    headers: { Authorization: bearer() },
    data: { email },
  });
  expect(lookup.status()).toBe(200);
  const membershipId = ((await lookup.json()) as { membershipId: string }).membershipId;
  expect(
    (
      await post(page, bearer, `/employees/${employee}/access-link`, `link-${key}`, {
        membershipId,
      })
    ).status(),
  ).toBe(201);
}

/** The Timesheets page with the run's site and week chosen through the page's own controls. */
async function chooseWeek(page: Page, language: 'en' | 'fr'): Promise<Locator> {
  const en = language === 'en';
  await primaryNav(page)
    .getByRole('link', { name: en ? 'Timesheets' : 'Feuilles de temps', exact: true })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    en ? 'Timesheets' : 'Feuilles de temps',
  );
  const form = page.getByTestId('timesheets-form');
  await form
    .getByRole('combobox', { name: en ? 'Legal entity' : 'Entité juridique' })
    .selectOption(entityId);
  await form.getByRole('combobox', { name: 'Site', exact: true }).selectOption(siteId);
  await form
    .getByLabel(en ? 'Week starting (Monday)' : 'Semaine commençant le (lundi)')
    .fill(monday);
  return form;
}

function draftOf(scope: Locator, family: string): Locator {
  return scope.getByTestId('timesheets-draft').filter({ hasText: family });
}

test.describe.serial('MVP-070A: weekly draft timesheets', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(420_000);
    ZONE = chooseZone();
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Feuilles Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    entityId = ((await entity.json()) as { id: string }).id;
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: entityId,
      code: SITE,
      name: SITE_NAME,
      timezone: ZONE,
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    siteId = ((await site.json()) as { id: string }).id;
    expect(
      (
        await post(page, bearer, '/departments', 'dp', {
          siteId,
          code: DEPARTMENT,
          name: `Magasin ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const first = localFromNow(-60);
    const day = first.slice(0, 10);
    const hired = plusDays(day, -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${ANA};Ana;${ANA_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${CLEO};Cléo;${CLEO_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-ts-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ts-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);

    // Today at the site, all ended: Ana 15 minutes from an hour ago and 10 minutes from 40
    // minutes ago; Cléo 10 minutes from 25 minutes ago.
    const proposal = {
      siteId,
      firstDate: day,
      lastDate: day,
      slots: [
        {
          clientRef: 'first',
          start: first,
          end: localFromNow(-45),
          labels: { en: FIRST_EN, fr: FIRST_FR },
          employeeId: employeeId(ANA),
        },
        {
          clientRef: 'second',
          start: localFromNow(-40),
          end: localFromNow(-30),
          labels: { en: SECOND_EN, fr: SECOND_FR },
          employeeId: employeeId(ANA),
        },
        {
          clientRef: 'cleo',
          start: localFromNow(-25),
          end: localFromNow(-15),
          labels: { en: CLEO_EN, fr: CLEO_FR },
          employeeId: employeeId(CLEO),
        },
      ],
    };
    const preview = await page.request.post(`${CORE_API}/work-schedules/preview`, {
      headers: { Authorization: bearer() },
      data: proposal,
    });
    expect(preview.status()).toBe(200);
    const digest = ((await preview.json()) as { previewDigest: string }).previewDigest;
    expect(
      (
        await post(page, bearer, '/work-schedules', 'pub', {
          ...proposal,
          previewDigest: digest,
          confirm: true,
        })
      ).status(),
    ).toBe(201);
    // The site-local Monday of the shifts' week.
    monday = sql(`SELECT (date_trunc('week', DATE '${day}'))::date::text`);
    // Ana's first shift: its missing record created by an administrator's correction.
    await createMissing(page, bearer, FIRST_EN, ANA, 'first');
    await inviteAndLink(page, browser, bearer, ANA_EMAIL, employeeId(ANA), 'ana');
    await context.close();
  });

  test('the administrator generates the week; Ana sees only her own draft, in English', async ({
    page,
    browser,
  }) => {
    test.setTimeout(240_000);
    await signIn(page, USERS.adminA, 'en');
    const form = await chooseWeek(page, 'en');
    await expect(page.getByTestId('timesheets-scope')).toHaveText(
      'Drafts only: nothing here submits, approves, prices or exports time.',
    );
    await form.getByRole('button', { name: 'Preview', exact: true }).click();

    const preview = page.getByTestId('timesheets-generation-preview');
    await expect(preview.getByRole('heading', { level: 2 })).toHaveText('Generation preview');
    await expect(preview.getByTestId('timesheet-period')).toContainText(SITE_NAME);
    await expect(preview.getByTestId('timesheets-preview-drafts')).toHaveText('2');
    await expect(preview.getByTestId('timesheets-preview-totals-rows')).toHaveText('3');
    await expect(preview.getByTestId('timesheets-preview-totals-ready')).toHaveText('1');
    await expect(preview.getByTestId('timesheets-preview-totals-blocked')).toContainText(
      '2 (Missing attendance: 2)',
    );
    await expect(preview.getByTestId('timesheets-preview-totals-worked')).toHaveText('0 h 15 min');
    const generate = preview.getByRole('button', { name: 'Generate drafts' });
    await expect(generate).toBeDisabled();
    await preview.getByLabel('I reviewed this preview and want to generate the drafts.').check();
    await accessible(page);
    await evidence(page, 'mvp070a-1-admin-generation-preview-en', preview);
    await generate.click();

    const generation = page.getByTestId('timesheets-generation');
    await expect(generation.getByRole('heading', { level: 2 })).toHaveText('Drafts for the week');
    await expect(page.getByTestId('timesheets-announcer')).toHaveText('2 drafts generated.');
    await expect(generation.getByTestId('timesheets-generation-version')).toHaveText('Version 1');
    await expect(generation.getByTestId('timesheets-draft')).toHaveCount(2);
    const ana = draftOf(generation, ANA_FAMILY);
    await expect(ana.getByTestId('timesheets-draft-revision')).toHaveText('1');
    await expect(ana.getByTestId('timesheets-draft-blocked')).toHaveText('1');
    await expect(ana.getByTestId('timesheets-draft-worked')).toHaveText('0 h 15 min');
    await expect(
      draftOf(generation, CLEO_FAMILY).getByTestId('timesheets-draft-blocked'),
    ).toHaveText('1');
    await accessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp070a-2-admin-drafts-en', generation);

    await ana
      .getByRole('button', { name: `View the timesheet of Ana ${ANA_FAMILY} (${ANA})` })
      .click();
    const detail = page.getByTestId('timesheets-detail');
    await expect(detail.getByRole('heading', { level: 2 })).toHaveText(
      `Timesheet of Ana ${ANA_FAMILY} (${ANA})`,
    );
    const rows = detail.getByTestId('timesheet-row');
    await expect(rows).toHaveCount(2);
    const ready = rows.filter({ hasText: FIRST_EN });
    await expect(ready.getByTestId('timesheet-row-state')).toHaveText('Ready');
    await expect(ready.getByTestId('timesheet-row-corrected')).toHaveText('Corrected (version 1)');
    await expect(ready.getByTestId('timesheet-row-worked')).toHaveText('0 h 15 min');
    const blocked = rows.filter({ hasText: SECOND_EN });
    await expect(blocked.getByTestId('timesheet-row-state')).toHaveText('Missing attendance');
    await expect(blocked.getByTestId('timesheet-row-worked')).toHaveText('—');
    await expect(detail.getByTestId('timesheets-revision')).toHaveCount(1);
    await accessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp070a-3-admin-draft-detail-en');
    await clean(page);

    // The stored draft rows: the corrected READY row's minutes are its exact whole minutes.
    expect(
      sql(
        `SELECT string_agg(r.state || ':' || coalesce(r.worked_minutes::text, '-'), ','
         ORDER BY r.scheduled_start_at) FROM people.timesheet_row r
         JOIN people.timesheet s ON s.tenant_id = r.tenant_id AND s.id = r.timesheet_id
         WHERE r.tenant_id = '${TENANT_A}' AND s.site_id = '${siteId}'`,
      ),
    ).toBe('READY:15,MISSING_ATTENDANCE:-,MISSING_ATTENDANCE:-');

    // Ana: only her own draft, with a neutral notice; no other employee and no cost center.
    const context = await browser.newContext({ locale: 'en-US' });
    const own = await context.newPage();
    await signIn(own, [ANA_EMAIL, PASSWORD], 'en', undefined);
    await primaryNav(own).getByRole('link', { name: 'My timesheets', exact: true }).click();
    await expect(own.getByRole('heading', { level: 1 })).toHaveText('My timesheets');
    await expect(own.getByTestId('my-timesheets-notice')).toHaveText(
      'These are drafts. They are not submitted, approved or a pay statement. If something looks wrong, contact your administrator.',
    );
    const cards = own.getByTestId('my-timesheet');
    await expect(cards).toHaveCount(1);
    await expect(cards.getByTestId('my-timesheet-status')).toHaveText('Draft');
    await expect(cards.getByTestId('my-timesheet-site')).toHaveText(SITE_NAME);
    await expect(cards.getByTestId('my-timesheet-worked')).toHaveText('0 h 15 min');
    await cards.getByTestId('my-timesheet-view').click();
    const mine = own.getByTestId('my-timesheet-detail');
    await expect(mine.getByTestId('timesheet-row')).toHaveCount(2);
    await expect(
      mine
        .getByTestId('timesheet-row')
        .filter({ hasText: FIRST_EN })
        .getByTestId('timesheet-row-corrected'),
    ).toHaveText('Corrected by the organization');
    const page2 = own.getByTestId('my-timesheets');
    await expect(page2).not.toContainText(CLEO_FAMILY);
    await expect(page2).not.toContainText(CLEO_EN);
    await expect(page2).not.toContainText(EXPLANATION);
    await expect(mine.getByTestId('timesheet-row-cost-center')).toHaveCount(0);
    await accessible(own);
    await noOverflow(own);
    await evidence(own, 'mvp070a-4-employee-own-draft-en');
    await clean(own);
    await context.close();
  });

  test('l’administratrice prévisualise et applique l’actualisation après une correction, en français', async ({
    page,
  }) => {
    test.setTimeout(180_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    // The attendance changes: Ana's second shift gets its missing record.
    await createMissing(page, bearer, SECOND_EN, ANA, 'second');
    const form = await chooseWeek(page, 'fr');
    await form.getByRole('button', { name: 'Prévisualiser', exact: true }).click();
    const preview = page.getByTestId('timesheets-generation-preview');
    await expect(preview.getByTestId('timesheets-existing')).toHaveText(
      'Cette semaine a déjà été générée (version 1). Ouvrez ses brouillons pour les consulter ou les actualiser.',
    );
    await preview.getByRole('button', { name: 'Ouvrir les brouillons' }).click();

    const generation = page.getByTestId('timesheets-generation');
    await expect(generation.getByTestId('timesheets-generation-version')).toHaveText('Version 1');
    await generation.getByRole('button', { name: 'Prévisualiser l’actualisation' }).click();
    const refresh = generation.getByTestId('timesheets-refresh');
    await expect(refresh.getByRole('heading', { level: 3 })).toHaveText(
      'Aperçu de l’actualisation',
    );
    await expect(refresh.getByTestId('timesheets-refresh-new')).toHaveText('0');
    await expect(refresh.getByTestId('timesheets-refresh-changed')).toHaveText('1');
    await expect(refresh.getByTestId('timesheets-refresh-unchanged')).toHaveText('1');
    await expect(refresh.getByTestId('timesheets-refresh-rows')).toHaveText('1');
    await expect(refresh.getByTestId('timesheets-refresh-totals-ready')).toHaveText('2');
    await expect(refresh.getByTestId('timesheets-refresh-totals-worked')).toHaveText('0 h 25 min');
    const apply = refresh.getByRole('button', { name: 'Appliquer l’actualisation' });
    await expect(apply).toBeDisabled();
    await refresh
      .getByLabel('J’ai vérifié cet aperçu et je veux appliquer l’actualisation.')
      .check();
    await accessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp070a-5-admin-refresh-preview-fr', refresh);
    await apply.click();

    await expect(page.getByTestId('timesheets-announcer')).toHaveText(
      'Actualisation appliquée : version 2.',
    );
    await expect(generation.getByTestId('timesheets-generation-version')).toHaveText('Version 2');
    const ana = draftOf(generation, ANA_FAMILY);
    await expect(ana.getByTestId('timesheets-draft-revision')).toHaveText('2');
    await expect(ana.getByTestId('timesheets-draft-worked')).toHaveText('0 h 25 min');
    await expect(
      draftOf(generation, CLEO_FAMILY).getByTestId('timesheets-draft-revision'),
    ).toHaveText('1');
    await ana
      .getByRole('button', { name: `Consulter la feuille de temps de Ana ${ANA_FAMILY} (${ANA})` })
      .click();
    const detail = page.getByTestId('timesheets-detail');
    await expect(detail.getByTestId('timesheets-detail-revision')).toHaveText('Révision 2');
    const rows = detail.getByTestId('timesheet-row');
    await expect(rows.filter({ hasText: SECOND_FR }).getByTestId('timesheet-row-state')).toHaveText(
      'Prêt',
    );
    await expect(
      rows.filter({ hasText: SECOND_FR }).getByTestId('timesheet-row-worked'),
    ).toHaveText('0 h 10 min');
    await expect(detail.getByTestId('timesheets-detail-totals-worked')).toHaveText('0 h 25 min');
    await expect(detail.getByTestId('timesheets-revision')).toHaveCount(2);
    await accessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp070a-6-admin-revision-2-fr');
    await clean(page);

    // Revision 1 is kept as it was; revision 2 is the current one.
    expect(
      sql(
        `SELECT string_agg(r.revision || ':' || r.state, ',' ORDER BY r.revision, r.scheduled_start_at)
         FROM people.timesheet_row r JOIN people.timesheet s
         ON s.tenant_id = r.tenant_id AND s.id = r.timesheet_id
         WHERE r.tenant_id = '${TENANT_A}' AND s.site_id = '${siteId}'
         AND s.employee_id = '${employeeId(ANA)}'`,
      ),
    ).toBe('1:READY,1:MISSING_ATTENDANCE,2:READY,2:READY');
  });
});
