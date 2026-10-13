import { expect, test, type Browser, type Locator, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
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

// MVP-062 (Issue #126): a tenant administrator corrects factual attendance evidence with the real
// identity provider, database and server clock. Setup (API, the administrator's existing SSO
// session): Ana and Cléo are imported at a new site, and one schedule of today holds Ana's shift
// that started ten minutes ago and ends in two hours, and Cléo's shift that ended a quarter of an
// hour ago. Ana checked in late (synchronized from an offline device) and never checks out; Cléo
// never checks in. Exactly two journeys:
//  - English: the administrator previews and saves Ana's missing check-out from her "Checked in
//    after scheduled start" card; Ana then sees her record completed and "Corrected by the
//    organization" in "My attendance" and "My schedule", never who corrected it or why;
//  - French : l'administratrice crée le pointage manquant de Cléo depuis l'anomalie « Aucune
//    arrivée enregistrée », et l'anomalie disparaît de la liste.
// axe, reflow at 320 px, 390 px and 200 % zoom, browser storage and URLs are checked. Guards,
// stale previews, concurrency, tenant scope, audits, history immutability and query plans are
// proven at API and database level, not here. The site's zone is whichever of the two supported
// zones is at least an hour into its local day, so the past shift can be published for today.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `CE-${stamp}`;
const SITE = `CS-${stamp}`;
const DEPARTMENT = `CD-${stamp}`;
const ANA = `CAA-${stamp}-1`;
const CLEO = `CAC-${stamp}-2`;
const ANA_FAMILY = `Kalala${letters}`;
const CLEO_FAMILY = `Ilunga${letters}`;
const ANA_EMAIL = `e2e.correction.ana.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Cor!`;
const SITE_NAME = `Site Corrections ${stamp}`;
const ANA_EN = `Cashier ${letters}`;
const ANA_FR = `Caissière ${letters}`;
const CLEO_EN = `Stock count ${letters}`;
const CLEO_FR = `Inventaire ${letters}`;
const EXPLANATION_EN = 'The badge reader at the exit was offline when Ana left.';
const EXPLANATION_FR = 'La tablette du site était hors service ; Cléo a travaillé tout le créneau.';

let ZONE = 'Africa/Lubumbashi';
let entityId = '';
let siteId = '';
let cleoStart = '';
let cleoEnd = '';

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

function today(): string {
  return sql(
    `SELECT (now() AT TIME ZONE timezone)::date::text FROM tenant.organization
     WHERE id = '${TENANT_A}'`,
  );
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

async function clean(page: Page) {
  // The run's own markers only: the stamp, the synthetic family names, the slot labels and the
  // explanations. Never the stamp's bare letter fragment.
  const markers = new RegExp(
    `${stamp}|${ANA_FAMILY}|${CLEO_FAMILY}|Caissi|Cashier|Inventaire|Stock count|badge reader|tablette`,
    'iu',
  );
  expect(await storage(page)).not.toMatch(markers);
  expect(page.url()).not.toMatch(markers);
  expect(page.url()).not.toMatch(/[?&#](code|state|session_state|siteId|type|from|to)=/u);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data?: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ac-${key}-${stamp}-0001` },
    ...(data === undefined ? {} : { data }),
  });
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

/** The administrator's review of the run's site, filtered through the page's own controls. */
async function openReview(page: Page, language: 'en' | 'fr'): Promise<Locator> {
  const en = language === 'en';
  await primaryNav(page)
    .getByRole('link', { name: en ? 'Attendance review' : 'Suivi de la présence', exact: true })
    .click();
  const review = page.getByTestId('attendance-exceptions-admin');
  await review
    .getByRole('combobox', { name: en ? 'Legal entity' : 'Entité juridique' })
    .selectOption(entityId);
  await review.getByRole('combobox', { name: 'Site', exact: true }).selectOption(siteId);
  await review
    .getByRole('button', { name: en ? 'Apply filters' : 'Appliquer les filtres' })
    .click();
  return review;
}

function cardsOf(scope: Locator, family: string): Locator {
  return scope.getByTestId('attendance-exception').filter({ hasText: family });
}

test.describe.serial('MVP-062: attendance corrections', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(420_000);
    ZONE = chooseZone();
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Corrections Entité ${stamp}`,
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
          name: `Caisse ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const hired = plusDays(today(), -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${ANA};Ana;${ANA_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${CLEO};Cléo;${CLEO_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-ac-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ac-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);

    // Today at the site: Cléo's shift ended 15 minutes ago (never checked in); Ana's started 10
    // minutes ago and ends in two hours.
    cleoStart = localFromNow(-50);
    cleoEnd = localFromNow(-15);
    const day = cleoStart.slice(0, 10);
    const proposal = {
      siteId,
      firstDate: day,
      lastDate: day,
      slots: [
        {
          clientRef: 'cleo',
          start: cleoStart,
          end: cleoEnd,
          labels: { en: CLEO_EN, fr: CLEO_FR },
          employeeId: employeeId(CLEO),
        },
        {
          clientRef: 'ana',
          start: localFromNow(-10),
          end: localFromNow(120),
          labels: { en: ANA_EN, fr: ANA_FR },
          employeeId: employeeId(ANA),
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
    await inviteAndLink(page, browser, bearer, ANA_EMAIL, employeeId(ANA), 'ana');
    await context.close();

    // Ana checked in late, three minutes ago, on a device that was offline (MVP-061A; so the
    // corrected check-out can be a whole minute already in the past), and does not check out.
    const anaContext = await browser.newContext({ locale: 'en-US' });
    const anaPage = await anaContext.newPage();
    const anaBearer = await signIn(anaPage, [ANA_EMAIL, PASSWORD], 'en', undefined);
    const checkIn = await post(anaPage, anaBearer, '/me/attendance/offline-events:sync', 'in', {
      clientEventId: randomUUID(),
      slotId: slotId(ANA_EN),
      type: 'CHECK_IN',
      deviceOccurredAt: sql(
        `SELECT to_char((now() - interval '3 minutes') AT TIME ZONE 'UTC',
         'YYYY-MM-DD"T"HH24:MI:SS"+00:00"')`,
      ),
      deviceOffsetMinutes: 0,
    });
    expect(checkIn.status()).toBe(201);
    await anaContext.close();
  });

  test('the administrator adds Ana’s missing check-out; Ana sees the corrected status, in English', async ({
    page,
    browser,
  }) => {
    test.setTimeout(240_000);
    await signIn(page, USERS.adminA, 'en');
    const review = await openReview(page, 'en');
    const ana = cardsOf(review, ANA_FAMILY);
    await expect(ana).toHaveCount(1);
    await expect(ana.getByTestId('attendance-exception-type')).toHaveText(
      'Checked in after scheduled start',
    );
    const correct = ana.getByTestId('attendance-exception-correct');
    await expect(correct).toHaveText('Correct this record');
    await correct.click();

    const dialog = page.getByTestId('attendance-correction-dialog');
    const panel = dialog.getByTestId('attendance-correction-panel');
    await expect(panel).toHaveAttribute('data-step', 'form');
    await expect(panel.getByRole('heading', { level: 2 })).toHaveText(
      'Correct an attendance record',
    );
    await expect(panel.getByTestId('attendance-correction-employee')).toContainText(
      `Ana ${ANA_FAMILY}`,
    );
    await expect(panel.getByTestId('attendance-correction-zone')).toContainText(ZONE);
    await expect(panel.getByTestId('attendance-correction-current-status')).toHaveText('Open');
    await expect(panel.getByTestId('attendance-correction-current-empty')).toHaveText(
      'No correction yet.',
    );
    // The current record has no check-out: the form starts with "No check-out" ticked.
    const noCheckOut = panel.getByTestId('attendance-correction-no-check-out');
    await expect(noCheckOut).toBeChecked();
    await noCheckOut.uncheck();
    // A minute ago (whole minutes): after Ana's check-in and already recorded at the preview.
    await panel.getByLabel('Check-out', { exact: true }).fill(localFromNow(-1));
    await panel
      .getByLabel('Reason', { exact: true })
      .selectOption({ label: 'Device or connectivity problem' });
    await panel.getByLabel('Explanation', { exact: true }).fill(EXPLANATION_EN);
    await expectAccessible(page);
    await evidence(page, 'mvp062-1-admin-correction-form-en', dialog);
    await panel.getByTestId('attendance-correction-preview').click();

    await expect(panel).toHaveAttribute('data-step', 'preview');
    const table = panel.getByTestId('attendance-correction-before-after');
    await expect(table.getByTestId('attendance-correction-before-after-before-out')).toHaveText(
      'None',
    );
    await expect(table.getByTestId('attendance-correction-before-after-after-out')).not.toHaveText(
      'None',
    );
    await expect(panel.getByTestId('attendance-correction-exceptions-before')).toContainText(
      'Checked in after scheduled start',
    );
    await expect(panel.getByTestId('attendance-correction-exceptions-after')).toContainText(
      'Checked out before scheduled end',
    );
    await expect(panel.getByTestId('attendance-correction-warnings')).toContainText(
      'The original check-in and check-out stay unchanged in the history',
    );
    await expect(panel.getByTestId('attendance-correction-explanation')).toHaveText(EXPLANATION_EN);
    const save = panel.getByTestId('attendance-correction-commit');
    await expect(save).toBeDisabled();
    await panel.getByTestId('attendance-correction-confirm-check').check();
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp062-2-admin-correction-preview-en', dialog);
    await save.click();

    await expect(panel).toHaveAttribute('data-step', 'done');
    await expect(panel.getByTestId('attendance-correction-status')).toHaveText(
      'The record was corrected (correction 1).',
    );
    const history = panel.getByTestId('attendance-correction-history');
    await expect(history.getByTestId('attendance-correction-history-status')).toHaveText(
      'Completed',
    );
    const entries = history.getByTestId('attendance-correction-entry');
    await expect(entries).toHaveCount(1);
    await expect(entries.first()).toContainText('Correction 1');
    await expect(entries.first()).toContainText('Times replaced');
    await expect(entries.first()).toContainText('by you');
    await expect(history.getByTestId('attendance-correction-history-originals')).toContainText(
      'check-in',
    );
    await expectAccessible(page);
    await evidence(page, 'mvp062-3-admin-correction-saved-en', dialog);
    await panel.getByTestId('attendance-correction-close').click();
    await expect(dialog).toBeHidden();
    await expect(ana.getByTestId('attendance-exception-type')).toHaveText([
      'Checked in after scheduled start',
      'Checked out before scheduled end',
    ]);
    await clean(page);

    // The original check-in event stays; the record carries the correction; no check-out event.
    const record = sql(
      `SELECT r.id FROM people.attendance_record r JOIN people.work_schedule_slot s
       ON s.tenant_id = r.tenant_id AND s.id = r.slot_id
       WHERE r.tenant_id = '${TENANT_A}' AND s.label_en = '${ANA_EN}'`,
    );
    expect(
      sql(
        `SELECT string_agg(event_type, ',' ORDER BY event_type) FROM people.attendance_event
         WHERE tenant_id = '${TENANT_A}' AND record_id = '${record}'`,
      ),
    ).toBe('CHECKED_IN');
    expect(
      sql(
        `SELECT r.correction_version || ':' || c.operation || ':' || (c.before_checked_out_at IS NULL)
         || ':' || (r.checked_out_at = c.after_checked_out_at)
         FROM people.attendance_record r JOIN people.attendance_correction c
         ON c.tenant_id = r.tenant_id AND c.record_id = r.id
         WHERE r.tenant_id = '${TENANT_A}' AND r.id = '${record}'`,
      ),
    ).toBe('1:REPLACE_TIMES:true:true');

    // Ana: completed, "Corrected by the organization", never who or why.
    const context = await browser.newContext({ locale: 'en-US' });
    const own = await context.newPage();
    await signIn(own, [ANA_EMAIL, PASSWORD], 'en', undefined);
    await primaryNav(own).getByRole('link', { name: 'My attendance', exact: true }).click();
    await expect(own.getByRole('heading', { level: 1 })).toHaveText('My attendance');
    const mine = own.getByTestId('my-attendance-record').filter({ hasText: ANA_EN });
    await expect(mine.getByTestId('my-attendance-status')).toHaveText('Completed');
    await expect(mine.getByTestId('my-attendance-corrected-badge')).toHaveText(
      'Corrected by the organization',
    );
    await expect(mine.getByTestId('my-attendance-corrected-date')).toContainText(
      'The times shown are the corrected times.',
    );
    const attendance = own.getByTestId('my-attendance');
    await expect(attendance).not.toContainText(EXPLANATION_EN);
    await expect(attendance).not.toContainText(/administrator|badge reader/iu);
    await expectAccessible(own);
    await noOverflow(own);
    await evidence(own, 'mvp062-4-employee-corrected-en', mine);

    await primaryNav(own).getByRole('link', { name: 'My schedule', exact: true }).click();
    await expect(own.getByRole('heading', { level: 1 })).toHaveText('My schedule');
    const shift = own.getByTestId('my-shift').filter({ hasText: ANA_EN });
    await expect(shift.getByTestId('my-shift-corrected-badge')).toHaveText(
      'Corrected by the organization',
    );
    await expect(shift).not.toContainText(EXPLANATION_EN);
    await expectAccessible(own);
    await clean(own);
    await context.close();
  });

  test('l’administratrice crée le pointage manquant de Cléo et l’anomalie disparaît, en français', async ({
    page,
  }) => {
    test.setTimeout(180_000);
    await signIn(page, USERS.adminA, 'fr');
    const review = await openReview(page, 'fr');
    const cleo = cardsOf(review, CLEO_FAMILY);
    await expect(cleo).toHaveCount(1);
    await expect(cleo.getByTestId('attendance-exception-type')).toHaveText(
      'Aucune arrivée enregistrée',
    );
    await expect(cleo).toContainText(CLEO_FR);
    const create = cleo.getByTestId('attendance-exception-correct');
    await expect(create).toHaveText('Créer le pointage manquant');
    await create.click();

    const dialog = page.getByTestId('attendance-correction-dialog');
    const panel = dialog.getByTestId('attendance-correction-panel');
    await expect(panel).toHaveAttribute('data-step', 'form');
    await expect(panel.getByRole('heading', { level: 2 })).toHaveText('Créer un pointage manquant');
    await expect(panel.getByTestId('attendance-correction-employee')).toContainText(
      `Cléo ${CLEO_FAMILY}`,
    );
    await panel.getByLabel('Arrivée', { exact: true }).fill(cleoStart);
    const noCheckOut = panel.getByTestId('attendance-correction-no-check-out');
    if (await noCheckOut.isChecked()) await noCheckOut.uncheck();
    await panel.getByLabel('Départ', { exact: true }).fill(cleoEnd);
    await panel
      .getByLabel('Motif', { exact: true })
      .selectOption({ label: 'Problème d’appareil ou de connexion' });
    await panel.getByLabel('Explication', { exact: true }).fill(EXPLANATION_FR);
    await expect(panel).toContainText(`${String(EXPLANATION_FR.length)} caractères sur 500`);
    await expectAccessible(page);
    await evidence(page, 'mvp062-5-admin-missing-form-fr', dialog);
    await panel.getByTestId('attendance-correction-preview').click();

    await expect(panel).toHaveAttribute('data-step', 'preview');
    const table = panel.getByTestId('attendance-correction-before-after');
    await expect(table.getByTestId('attendance-correction-before-after-before-in')).toHaveText(
      'Aucun pointage',
    );
    await expect(panel.getByTestId('attendance-correction-exceptions-before')).toHaveText(
      'Aucune arrivée enregistrée',
    );
    await expect(panel.getByTestId('attendance-correction-exceptions-after')).toHaveText('Aucune');
    await expect(panel.getByTestId('attendance-correction-reason')).toHaveText(
      'Problème d’appareil ou de connexion',
    );
    await panel.getByTestId('attendance-correction-confirm-check').check();
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp062-6-admin-missing-preview-fr', dialog);
    await panel.getByTestId('attendance-correction-commit').click();

    await expect(panel).toHaveAttribute('data-step', 'done');
    await expect(panel.getByTestId('attendance-correction-status')).toHaveText(
      'Le pointage manquant a été créé (correction 1).',
    );
    const entries = panel.getByTestId('attendance-correction-entry');
    await expect(entries).toHaveCount(1);
    await expect(entries.first()).toContainText('Pointage manquant créé');
    await expect(entries.first()).toContainText('par vous');
    await expectAccessible(page);
    await evidence(page, 'mvp062-7-admin-missing-saved-fr', dialog);
    await panel.getByTestId('attendance-correction-close').click();
    await expect(dialog).toBeHidden();

    // The list reloads: Cléo's exception is gone; Ana's two remain.
    await expect(cleo).toHaveCount(0);
    await expect(cardsOf(review, ANA_FAMILY)).toHaveCount(2);
    await expect(page.getByTestId('attendance-exceptions-announcer')).toContainText(/\d/u);
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp062-8-admin-exception-gone-fr');
    await clean(page);

    // A correction-created record: no check-in event, correction 1 is its only source.
    expect(
      sql(
        `SELECT (r.check_in_event_id IS NULL) || ':' || r.correction_version || ':' || c.operation
         || ':' || (SELECT count(*) FROM people.attendance_event e
                    WHERE e.tenant_id = r.tenant_id AND e.record_id = r.id)
         FROM people.attendance_record r JOIN people.attendance_correction c
         ON c.tenant_id = r.tenant_id AND c.record_id = r.id
         JOIN people.work_schedule_slot s ON s.tenant_id = r.tenant_id AND s.id = r.slot_id
         WHERE r.tenant_id = '${TENANT_A}' AND s.label_en = '${CLEO_EN}'`,
      ),
    ).toBe('true:1:CREATE_MISSING_RECORD:0');
  });
});
