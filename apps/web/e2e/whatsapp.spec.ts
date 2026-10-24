import { expect, test, type Page } from '@playwright/test';
import {
  CORE_API,
  USERS,
  expectAccessible,
  primaryNav,
  signIn,
  subjectLock,
  typeReactSecret,
} from './support';

// MVP-111 (Issue #163): the WhatsApp Business connector with the real identity provider, database,
// delivery worker and the CONTROLLED SYNTHETIC Meta provider of the development stack (compose
// service `meta-provider`: TLS and hostname verification kept, one fixed callback target, never
// Meta, throwaway data only). Tenant A's administrator; the provider is reached on its published
// control port (127.0.0.1) to hand it the account, the approved templates and a send scenario, to
// read what it received (template, language, last two digits, parameter count) and to emit signed
// status callbacks. The access token is typed with typeReactSecret() and is a throwaway value.
// Exactly two journeys:
//  - English: connect with all twelve templates, verify with the provider, register a recipient
//    with a consent attestation (masked number only), send a test message, see it accepted and
//    then delivered after a signed callback, rotate the access token;
//  - French : a recipient who prefers French receives a test message that the provider rejects
//    (closed failure class in the history), then the connector is disabled and enabled again
//    (re-verified), accessibility checked.
// Authorization, tenant isolation, callback signatures, retries, suspension, rate limits, audits
// and constraints are proven at API and database level, not here. A tenant holds one connector
// and it cannot be deleted: a long-lived local database needs a reset (`docker compose down -v`)
// before this file runs again; CI starts from a clean stack.

const PROVIDER = process.env.E2E_META_PROVIDER_URL ?? 'http://127.0.0.1:8098';
const WEB_BASE = process.env.E2E_WEB_BASE_URL ?? 'http://localhost:5173';
const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const digits = (n: number) =>
  Array.from({ length: n }, () => Math.floor(Math.random() * 10)).join('');
const WABA_ID = `9${digits(11)}`;
const PHONE_NUMBER_ID = `8${digits(11)}`;
const TYPES = [
  ['LEAVE_DECISION', 'Leave decision', 'Décision sur un congé'],
  ['TIMESHEET_APPROVED', 'Timesheet approved', 'Feuille de temps approuvée'],
  ['ATTENDANCE_CORRECTED', 'Attendance corrected', 'Présence corrigée'],
  ['PAYROLL_INPUT_LOCKED', 'Payroll inputs locked', 'Éléments de paie verrouillés'],
  ['PAYROLL_EXPORT_READY', 'Payroll export ready', 'Export de paie prêt'],
  ['CONNECTOR_TEST', 'Connection test', 'Test de connexion'],
] as const;
const templateName = (type: string, language: string) =>
  `divalhr_${type.toLowerCase()}_${language}_${stamp.toLowerCase()}`;

type Send = { template: string; language: string; last2: string; parameters: number };

let release: (() => void) | undefined;

async function control(path: string, body?: unknown) {
  const response = await fetch(`${PROVIDER}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  expect(response.ok).toBe(true);
  return response.json() as Promise<unknown>;
}

async function seedProvider() {
  await control('/control/reset', {});
  await control('/control/account', { phoneNumberId: PHONE_NUMBER_ID, wabaId: WABA_ID });
  await control('/control/templates', {
    names: TYPES.flatMap(([type]) => ['en', 'fr'].map((l) => templateName(type, l))),
    languages: ['en', 'fr'],
    buttonUrl: `${WEB_BASE}/{{1}}`,
    bodyParameters: 1,
  });
}

async function guardNoConnector(page: Page, bearer: () => string) {
  const response = await page.request.get(`${CORE_API}/integrations/messaging/whatsapp`, {
    headers: { Authorization: bearer() },
  });
  expect(
    response.status(),
    'tenant A already holds a WhatsApp connector: reset the local stack (docker compose down -v)',
  ).toBe(404);
}

async function openWhatsApp(page: Page, link: string, title: string) {
  await primaryNav(page).getByRole('link', { name: link }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title);
}

function yesterday() {
  const day = new Date(Date.now() - 24 * 3_600_000);
  return day.toISOString().slice(0, 10);
}

test.describe.serial('MVP-111: WhatsApp Business connector', () => {
  test.beforeAll(async () => {
    test.setTimeout(300_000);
    // Writes as tenant A's administrator never overlap another file's.
    release = await subjectLock(USERS.adminA[0]);
    await seedProvider();
  });

  test.afterAll(() => {
    release?.();
  });

  test('an administrator connects, verifies and tests WhatsApp notifications in English', async ({
    page,
  }) => {
    test.setTimeout(240_000);
    const bearer = await signIn(page, USERS.adminA, 'en');
    await guardNoConnector(page, bearer);

    await openWhatsApp(page, 'WhatsApp Business', 'WhatsApp Business');
    await expect(
      page.getByRole('heading', { level: 2, name: 'Connect WhatsApp Business' }),
    ).toBeVisible();
    await page.getByLabel('WhatsApp Business account ID').fill(WABA_ID);
    await page.getByLabel('Sending phone number ID').fill(PHONE_NUMBER_ID);
    await page.getByLabel('Sending phone number', { exact: true }).fill('+243812345678');
    await typeReactSecret(page, 'input[type="password"]', `synthetic-token-${stamp}-0123456789`);
    for (const [type, english] of TYPES) {
      await page.getByLabel(`${english}, English: template name`).fill(templateName(type, 'en'));
      await page.getByLabel(`${english}, French: template name`).fill(templateName(type, 'fr'));
    }
    await expectAccessible(page);
    await page
      .getByLabel('I confirm that these identifiers and templates belong to my organization.')
      .check();
    await page.getByRole('button', { name: 'Connect', exact: true }).click();

    // The connector exists as a draft; the token is never shown again.
    const state = page.getByTestId('connector-state');
    await expect(state).toHaveAttribute('data-status', 'DRAFT');
    await expect(page.locator('body')).not.toContainText(`synthetic-token-${stamp}`);

    // Verification with the provider reports only; enabling is a separate, explicit step.
    await page.getByRole('button', { name: 'Verify with WhatsApp' }).click();
    await page.getByLabel('I understand and want to continue.').check();
    await page.getByRole('button', { name: 'Yes, verify' }).click();
    await expect(page.getByTestId('verification')).toContainText('Everything checks out', {
      timeout: 30_000,
    });
    await expect(state).toHaveAttribute('data-status', 'DRAFT');
    await page.getByRole('button', { name: 'Enable', exact: true }).click();
    await page.getByRole('button', { name: 'Yes, enable', exact: true }).click();
    await expect(state).toHaveAttribute('data-status', 'ACTIVE', { timeout: 30_000 });
    await expectAccessible(page);

    // A recipient with a recorded consent: only the masked number is displayed afterwards.
    const number = `+24399${digits(7)}`;
    await page.getByRole('button', { name: 'Add a recipient' }).click();
    await page.getByLabel('Name', { exact: true }).fill(`Recipient ${stamp}`);
    await page.getByLabel('WhatsApp number').fill(number);
    await page.getByLabel('Message language').selectOption('en');
    await page.getByLabel('Leave decision').check();
    await page.getByLabel('How the person agreed').selectOption('WRITTEN');
    await page.getByLabel('Date of consent').fill(yesterday());
    await page
      .getByLabel('I attest that this person agreed to receive these WhatsApp messages.')
      .check();
    await page.getByLabel('I confirm that this recipient may receive these notifications.').check();
    await page.getByRole('button', { name: 'Add recipient' }).click();
    const row = page.getByTestId('recipient-row').filter({ hasText: `Recipient ${stamp}` });
    await expect(row).toBeVisible();
    await expect(row).toContainText('***');
    await expect(page.locator('body')).not.toContainText(number);
    expect(page.url()).not.toContain(number.slice(1));

    // A test message: queued, sent by the worker, accepted, then delivered by a signed callback.
    await row.getByRole('button', { name: `Send a test message Recipient ${stamp}` }).click();
    await page.getByRole('button', { name: 'Yes, send the test' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      'Test message queued. Follow it in the history.',
    );
    await expect(async () => {
      await page.getByRole('button', { name: 'Refresh history' }).click();
      await expect(page.getByTestId('history-row').first()).toContainText('Accepted by WhatsApp', {
        timeout: 3_000,
      });
    }).toPass({ timeout: 60_000 });
    const sends = (await control('/control/sends')) as Send[];
    expect(sends.at(-1)).toMatchObject({
      template: templateName('CONNECTOR_TEST', 'en'),
      language: 'en',
      last2: number.slice(-2),
    });
    await control('/control/emit-callback', {
      statuses: [{ id: 'last', status: 'delivered' }],
      signature: 'valid',
    });
    await expect(async () => {
      await page.getByRole('button', { name: 'Refresh history' }).click();
      await expect(page.getByTestId('history-row').first()).toContainText('Delivered', {
        timeout: 3_000,
      });
    }).toPass({ timeout: 60_000 });
    await expect(page.getByTestId('history-table')).not.toContainText(number);
    await expectAccessible(page);

    // Rotating the access token: confirmed, checked with the provider, never echoed.
    await page.getByRole('button', { name: 'Rotate access token' }).click();
    await typeReactSecret(
      page,
      '[data-testid="confirm"] input[type="password"]',
      `rotated-${stamp}-0123456789abcdef`,
    );
    await page.getByLabel('I understand and want to continue.').check();
    await page.getByRole('button', { name: 'Yes, rotate the token' }).click();
    await expect(page.getByTestId('announcer')).toHaveText('Access token rotated.');
    await expect(state).toHaveAttribute('data-status', 'ACTIVE');
    await expect(page.locator('body')).not.toContainText(`rotated-${stamp}`);
  });

  test('un administrateur voit un envoi refusé, puis désactive et réactive le connecteur en français', async ({
    page,
  }) => {
    test.setTimeout(240_000);
    await signIn(page, USERS.adminA, 'fr');
    // A full window after the English journey's writes.
    await page.waitForTimeout(61_000);

    await openWhatsApp(page, 'WhatsApp Business', 'WhatsApp Business');
    const state = page.getByTestId('connector-state');
    await expect(state).toHaveAttribute('data-status', 'ACTIVE');
    await expect(page.getByTestId('recipients-table')).toBeVisible();
    await expectAccessible(page);

    // A French-speaking recipient, whose test message the provider refuses.
    const number = `+24398${digits(7)}`;
    await page.getByRole('button', { name: 'Ajouter un destinataire' }).click();
    await page.getByLabel('Nom', { exact: true }).fill(`Destinataire ${stamp}`);
    await page.getByLabel('Numéro WhatsApp').fill(number);
    await page.getByLabel('Langue des messages').selectOption('fr');
    await page.getByLabel('Décision sur un congé').check();
    await page.getByLabel('Comment la personne a donné son accord').selectOption('WEB_FORM');
    await page.getByLabel('Date du consentement').fill(yesterday());
    await page
      .getByLabel('J’atteste que cette personne a accepté de recevoir ces messages WhatsApp.')
      .check();
    await page
      .getByLabel('Je confirme que ce destinataire peut recevoir ces notifications.')
      .check();
    await page.getByRole('button', { name: 'Ajouter le destinataire' }).click();
    const row = page.getByTestId('recipient-row').filter({ hasText: `Destinataire ${stamp}` });
    await expect(row).toBeVisible();
    await expect(row).toContainText('***');
    await expect(page.locator('body')).not.toContainText(number);

    await control('/control/scenario', { mode: 'recipient-rejected' });
    await row
      .getByRole('button', { name: `Envoyer un message de test Destinataire ${stamp}` })
      .click();
    await page.getByRole('button', { name: 'Oui, envoyer le test' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      'Message de test mis en file d’attente. Suivez-le dans l’historique.',
    );
    await expect(async () => {
      await page.getByRole('button', { name: 'Actualiser l’historique' }).click();
      const history = page.getByTestId('history-row').first();
      await expect(history).toContainText('Échec', { timeout: 3_000 });
      await expect(history).toContainText('Le destinataire ne peut pas recevoir de messages');
    }).toPass({ timeout: 60_000 });
    await expectAccessible(page);
    await control('/control/scenario', { mode: 'accept' });

    // Disable (waiting messages are cancelled, history stays), then enable: re-verified.
    await page.getByRole('button', { name: 'Désactiver', exact: true }).first().click();
    await page.getByLabel('Je comprends et je souhaite continuer.').check();
    await page.getByRole('button', { name: 'Oui, désactiver', exact: true }).click();
    await expect(state).toHaveAttribute('data-status', 'DISABLED');
    await expect(page.getByTestId('history-table')).toBeVisible();
    await page.getByRole('button', { name: 'Activer', exact: true }).first().click();
    await page.getByLabel('Je comprends et je souhaite continuer.').check();
    await page.getByRole('button', { name: 'Oui, activer', exact: true }).click();
    await expect(state).toHaveAttribute('data-status', 'ACTIVE', { timeout: 30_000 });
    await expectAccessible(page);
  });
});
