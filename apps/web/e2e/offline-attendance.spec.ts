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

// MVP-061B (Issue #124): the foreground offline attendance queue with the real identity
// provider, database, server clock and browser IndexedDB. Setup (API, the administrator's
// existing SSO session) as MVP-060A: two employees at a new site with shifts starting half an
// hour from now, so the check-in window is open. Exactly two journeys:
//  - English: Ana's network is cut on an already-loaded "My schedule"; she checks in, the event
//    is saved on this device only; on "My attendance" she sees it pending; the network returns,
//    the browser's online event synchronizes it through MVP-061A, the queue is empty and the
//    record is open with the server-accepted offline event;
//  - French: Cléo saves « Pointer l’arrivée » offline; meanwhile the same shift is checked in on
//    the server (a controlled permanent conflict); back online, « Présence hors connexion » shows
//    « Action requise » with the neutral reason, and she discards it after a confirmation.
// The network is cut and restored with Playwright's offline emulation (deterministic, no sleep).
// The saved row, browser storage, Cache Storage, the URL and the console are checked for the
// absence of credentials, identity, labels and queued identifiers; axe and reflow at 390 px,
// 320 px and 200 % zoom are checked on the new section.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ZONE = 'Africa/Lubumbashi';
const ENTITY = `OE-${stamp}`;
const SITE = `OS-${stamp}`;
const DEPARTMENT = `OD-${stamp}`;
const ANA = `OFA-${stamp}-1`;
const CLEO = `OFC-${stamp}-2`;
const ANA_FAMILY = `Mutombo${letters}`;
const CLEO_FAMILY = `Ngalula${letters}`;
const ANA_EMAIL = `e2e.horsligne.ana.${lower}@example.test`;
const CLEO_EMAIL = `e2e.horsligne.cleo.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Hors!`;
const SITE_NAME = `Site Hors Ligne ${stamp}`;
const WORK_EN = `Stocker ${letters}`;
const WORK_FR = `Magasinière ${letters}`;
const QUEUE_FIELDS = [
  'attempts',
  'clientEventId',
  'createdAt',
  'deviceOccurredAt',
  'deviceOffsetMinutes',
  'ownerScope',
  'reason',
  'schemaVersion',
  'slotId',
  'state',
  'type',
];

/**
 * PR evidence (EVIDENCE=1 only): a screenshot written outside the working tree. The pages hold
 * only this run's generated test data.
 */
async function evidence(page: Page, name: string, region?: Locator) {
  const out = process.env.EVIDENCE_DIR;
  if (!process.env.EVIDENCE || !out) return;
  mkdirSync(out, { recursive: true });
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

function slotOf(number: string): string {
  return sql(
    `SELECT l.id FROM people.work_schedule_slot l WHERE l.tenant_id = '${TENANT_A}'
     AND l.assigned_employee_id = '${employeeId(number)}'`,
  );
}

/** The employee's attendance events as `type method` lines (the server's evidence). */
function events(number: string): string {
  return sql(
    `SELECT coalesce(string_agg(event_type || ' ' || recording_method, ',' ORDER BY recorded_at), '')
     FROM people.attendance_event WHERE tenant_id = '${TENANT_A}'
     AND employee_id = '${employeeId(number)}'`,
  );
}

/** The site-local date-time `minutes` from now, `YYYY-MM-DDTHH:MM` (the server's own clock). */
function localFromNow(minutes: number): string {
  return sql(
    `SELECT to_char((now() + interval '${minutes} minutes') AT TIME ZONE '${ZONE}',
     'YYYY-MM-DD"T"HH24:MI')`,
  );
}

/** Every row of the attendance queue in this browser's IndexedDB (read only). */
async function queued(page: Page): Promise<Record<string, unknown>[]> {
  return page.evaluate(async () => {
    const names = (await indexedDB.databases()).map((database) => database.name);
    if (!names.includes('divalhr-attendance-queue')) return [];
    return new Promise<Record<string, unknown>[]>((resolve, reject) => {
      const opening = indexedDB.open('divalhr-attendance-queue');
      opening.onerror = () => {
        reject(new Error('cannot open the queue'));
      };
      opening.onsuccess = () => {
        const db = opening.result;
        const read = db.transaction('events', 'readonly').objectStore('events').getAll();
        read.onsuccess = () => {
          resolve(read.result as Record<string, unknown>[]);
          db.close();
        };
        read.onerror = () => {
          reject(new Error('cannot read the queue'));
        };
      };
    });
  });
}

/** localStorage, sessionStorage and every Cache Storage request URL, as one text. */
async function browserStores(page: Page): Promise<string> {
  return page.evaluate(async () => {
    const values: string[] = [];
    for (const store of [window.localStorage, window.sessionStorage]) {
      for (let index = 0; index < store.length; index++) {
        const key = store.key(index) ?? '';
        values.push(key, store.getItem(key) ?? '');
      }
    }
    for (const name of await caches.keys()) {
      for (const request of await (await caches.open(name)).keys()) values.push(request.url);
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
 * The saved row holds only the MVP-061A fields and bounded metadata; nothing of the run's
 * identity, labels or credentials; and none of it reaches browser storage, Cache Storage, the URL
 * or the console.
 */
async function minimalAndPrivate(
  page: Page,
  row: Record<string, unknown>,
  bearer: string,
  consoleLines: string[],
) {
  expect(Object.keys(row).sort()).toEqual(QUEUE_FIELDS);
  expect(String(row.ownerScope)).toMatch(/^[0-9a-f]{64}$/u);
  expect(String(row.deviceOccurredAt)).toMatch(
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}$/u,
  );
  const serialized = JSON.stringify(row);
  for (const forbidden of [
    stamp,
    ANA_FAMILY,
    CLEO_FAMILY,
    ANA_EMAIL,
    CLEO_EMAIL,
    WORK_EN,
    WORK_FR,
    SITE_NAME,
    TENANT_A,
    bearer.replace(/^Bearer /u, '').slice(0, 40),
  ]) {
    expect(serialized).not.toContain(forbidden);
  }
  const stores = await browserStores(page);
  const markers = new RegExp(
    `${stamp}|${ANA_FAMILY}|${CLEO_FAMILY}|${String(row.slotId)}|${String(row.clientEventId)}|${String(row.ownerScope)}|offline-events`,
    'iu',
  );
  expect(stores).not.toMatch(markers);
  expect(page.url()).not.toMatch(markers);
  expect(page.url()).not.toMatch(/[?&#](code|state|session_state)=/u);
  expect(consoleLines.join('\n')).not.toMatch(markers);
  expect(consoleLines.join('\n')).not.toContain(String(row.deviceOccurredAt));
}

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-of-${key}-${stamp}-0001` },
    data,
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
  const context = await browser.newContext({ locale: 'fr-FR' });
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

/** Confirms the open attendance dialog (offline) and waits for it to close. */
async function confirmOffline(page: Page, title: string, button: string, notice: string) {
  const dialog = page.getByTestId('attendance-dialog');
  await expect(page.getByRole('dialog', { name: title })).toBeVisible();
  await expect(dialog.getByTestId('attendance-dialog-offline')).toBeVisible();
  const action = dialog.getByRole('button', { name: button, exact: true });
  await expect(action).toBeFocused();
  await expectAccessible(page);
  await action.click();
  await expect(dialog).toBeHidden();
  await expect(page.getByTestId('my-schedule-offline-notice')).toHaveText(notice);
}

test.describe.serial('MVP-061B: queue attendance offline and synchronize it', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(360_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Hors Ligne Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: ((await entity.json()) as { id: string }).id,
      code: SITE,
      name: SITE_NAME,
      timezone: ZONE,
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    const siteId = ((await site.json()) as { id: string }).id;
    expect(
      (
        await post(page, bearer, '/departments', 'dp', {
          siteId,
          code: DEPARTMENT,
          name: `Entrepôt ${stamp}`,
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
        'Idempotency-Key': `e2e-of-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-of-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);

    // Shifts starting 30 minutes from now at the site: the check-in window (2 h before) is open.
    const start = localFromNow(30);
    const end = localFromNow(270);
    const day = start.slice(0, 10);
    const proposal = {
      siteId,
      firstDate: day,
      lastDate: day,
      slots: [
        {
          clientRef: 'ana',
          start,
          end,
          labels: { en: WORK_EN, fr: WORK_FR },
          employeeId: employeeId(ANA),
        },
        {
          clientRef: 'cleo',
          start,
          end,
          labels: { en: WORK_EN, fr: WORK_FR },
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
    await inviteAndLink(page, browser, bearer, ANA_EMAIL, employeeId(ANA), 'ana');
    await inviteAndLink(page, browser, bearer, CLEO_EMAIL, employeeId(CLEO), 'cleo');
    await context.close();
  });

  test('Ana checks in while offline and the event synchronizes when she is back online', async ({
    page,
  }) => {
    test.setTimeout(180_000);
    const consoleLines: string[] = [];
    page.on('console', (message) => consoleLines.push(message.text()));
    const bearer = await signIn(page, [ANA_EMAIL, PASSWORD], 'en', undefined);
    await primaryNav(page).getByRole('link', { name: 'My schedule', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My schedule');
    const shift = page.getByTestId('my-shift').filter({ hasText: WORK_EN });
    await expect(shift.getByTestId('my-shift-attendance-status')).toHaveText('Not checked in');

    // The already-loaded screen keeps working when the network drops.
    await page.context().setOffline(true);
    await shift.getByRole('button', { name: `Check in for ${WORK_EN} on`, exact: false }).click();
    await evidence(page, 'mvp061b-1-check-in-offline-en');
    await confirmOffline(
      page,
      'Check in',
      'Confirm check-in',
      'Saved on this device — waiting to synchronize.',
    );
    await expect(shift.getByTestId('my-shift-offline-queued')).toHaveText(
      'Check-in saved on this device — waiting to synchronize',
    );
    await expect(shift.getByTestId('check-in')).toHaveCount(0);
    await expect(shift.getByTestId('my-shift-attendance-status')).toHaveText('Not checked in');
    expect(events(ANA)).toBe('');
    const rows = await queued(page);
    expect(rows).toHaveLength(1);
    const row = rows[0] ?? {};
    expect(row).toMatchObject({
      schemaVersion: 1,
      slotId: slotOf(ANA),
      type: 'CHECK_IN',
      state: 'PENDING',
      attempts: 0,
      reason: null,
    });
    await minimalAndPrivate(page, row, bearer(), consoleLines);

    // My attendance, still offline: the section shows the saved check-in, not yet accepted.
    await primaryNav(page).getByRole('link', { name: 'My attendance', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My attendance');
    const panel = page.getByTestId('offline-attendance');
    await expect(panel.getByRole('heading', { level: 2 })).toHaveText('Offline attendance');
    await expect(panel.getByTestId('offline-connection')).toHaveText('Connection: offline');
    await expect(panel.getByTestId('offline-count')).toHaveText(
      '1 attendance event saved on this device',
    );
    const entry = panel.getByTestId('offline-entry');
    await expect(entry).toContainText('Check-in');
    await expect(entry.getByTestId('offline-entry-state')).toHaveText('Pending sync');
    await expect(entry.getByTestId('offline-entry-time')).toContainText(
      'Time recorded on this device:',
    );
    await expect(panel.getByTestId('offline-sync-now')).toHaveCount(0);
    await expect(panel).not.toContainText(slotOf(ANA));
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp061b-2-pending-offline-en', panel);

    // Back online: the browser's online event runs one pass through MVP-061A.
    await page.context().setOffline(false);
    await expect(panel.getByTestId('offline-connection')).toHaveText('Connection: online');
    await expect(panel.getByTestId('offline-status')).toHaveText(
      '1 saved attendance event was synchronized.',
    );
    await expect(panel.getByTestId('offline-count')).toHaveText(
      'Nothing is waiting on this device.',
    );
    expect(await queued(page)).toEqual([]);
    expect(events(ANA)).toBe('CHECKED_IN EMPLOYEE_OFFLINE_SYNC');
    const record = page.getByTestId('my-attendance-record').filter({ hasText: WORK_EN });
    await expect(record.getByTestId('my-attendance-status')).toHaveText('Open');
    await expect(record.getByTestId('my-attendance-checked-in')).not.toBeEmpty();
    await expectAccessible(page);
    await evidence(page, 'mvp061b-3-synchronized-en');
    await minimalAndPrivate(page, row, bearer(), consoleLines);
  });

  test('Cléo enregistre son arrivée hors connexion, voit « Action requise » et la supprime', async ({
    page,
    request,
  }) => {
    test.setTimeout(180_000);
    const consoleLines: string[] = [];
    page.on('console', (message) => consoleLines.push(message.text()));
    const bearer = await signIn(page, [CLEO_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mon horaire', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Mon horaire');
    const shift = page.getByTestId('my-shift').filter({ hasText: WORK_FR });
    await expect(shift.getByTestId('my-shift-attendance-status')).toHaveText('Arrivée non pointée');

    await page.context().setOffline(true);
    await shift
      .getByRole('button', { name: `Pointer l’arrivée pour ${WORK_FR} le`, exact: false })
      .click();
    await confirmOffline(
      page,
      'Pointer l’arrivée',
      'Confirmer l’arrivée',
      'Enregistré sur cet appareil — en attente de synchronisation.',
    );
    const rows = await queued(page);
    expect(rows).toHaveLength(1);
    const row = rows[0] ?? {};
    await minimalAndPrivate(page, row, bearer(), consoleLines);

    // A controlled permanent conflict: the same shift is checked in on the server meanwhile
    // (the online MVP-060A command, outside this offline browser).
    const online = await request.post(`${CORE_API}/me/attendance/check-in`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-of-conflict-${stamp}-0001` },
      data: { slotId: slotOf(CLEO) },
    });
    expect(online.status()).toBe(201);

    await primaryNav(page).getByRole('link', { name: 'Ma présence', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Ma présence');
    const panel = page.getByTestId('offline-attendance');
    await expect(panel.getByTestId('offline-connection')).toHaveText('Connexion : hors ligne');
    await expect(panel.getByTestId('offline-entry-state')).toHaveText(
      'En attente de synchronisation',
    );

    await page.context().setOffline(false);
    await expect(panel.getByTestId('offline-status')).toHaveText(
      '1 pointage enregistré demande une action.',
    );
    const entry = panel.getByTestId('offline-entry');
    await expect(entry.getByTestId('offline-entry-state')).toHaveText('Action requise');
    await expect(entry.getByTestId('offline-entry-reason')).toHaveText(
      'Ce pointage a été reçu plus tard et n’a pas pu être appliqué, car il entre en conflit avec un pointage déjà enregistré pour vous.',
    );
    expect((await queued(page))[0]).toMatchObject({
      clientEventId: row.clientEventId,
      deviceOccurredAt: row.deviceOccurredAt,
      deviceOffsetMinutes: row.deviceOffsetMinutes,
      state: 'NEEDS_ATTENTION',
      reason: 'OFFLINE_ATTENDANCE_CONFLICT',
    });
    expect(events(CLEO)).toBe('CHECKED_IN SELF');
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp061b-4-needs-attention-fr', panel);

    await entry.getByRole('button', { name: 'Supprimer', exact: false }).click();
    const dialog = page.getByRole('dialog', { name: 'Supprimer ce pointage enregistré ?' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByTestId('offline-discard-confirm')).toBeFocused();
    await expectAccessible(page);
    await evidence(page, 'mvp061b-5-discard-confirm-fr');
    await dialog.getByTestId('offline-discard-confirm').click();
    await expect(dialog).toBeHidden();
    await expect(panel.getByTestId('offline-status')).toHaveText(
      'Le pointage enregistré a été supprimé.',
    );
    await expect(panel.getByTestId('offline-count')).toHaveText(
      'Rien n’est en attente sur cet appareil.',
    );
    expect(await queued(page)).toEqual([]);
    expect(events(CLEO)).toBe('CHECKED_IN SELF');
    await minimalAndPrivate(page, row, bearer(), consoleLines);
  });
});
