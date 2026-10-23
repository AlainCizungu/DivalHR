import { expect, test, type Page } from '@playwright/test';
import { CORE_API, USERS, expectAccessible, primaryNav, signIn, subjectLock } from './support';

// MVP-110 (Issue #161): secure outbound webhooks with the real identity provider, database,
// delivery worker and the CONTROLLED SYNTHETIC HTTPS receiver of the development stack (compose
// service `webhook-receiver`: private network, exact host and port allowed by Core's
// development-only test-receiver exception, TLS and hostname verification kept, the signature
// verified exactly as docs/WEBHOOKS.md tells customers to). Tenant A's administrator; the receiver
// is reached on its published control port (127.0.0.1) to hand it the secret the page revealed
// once and to read what it received (identifiers and a signature verdict only).
// Exactly two journeys:
//  - English: create a webhook, copy the secret once (gone after "Done"), send a test delivery,
//    see it delivered and signed in the history, rotate the secret (the receiver verifies with the
//    new one), disable the webhook;
//  - French : une destination interne est refusée ; un webhook valide est créé ; le récepteur
//    répond 500 : l’historique montre la nouvelle tentative prévue ; désactivation puis
//    réactivation, accessibilité vérifiée.
// Authorization, tenant isolation, SSRF vectors, retries, suspension, limits, audits and
// constraints are proven at API and database level, not here. A tenant holds at most 20 webhooks
// and none can be deleted: this file uses two per run, so a long-lived local database needs a
// reset (`docker compose down -v`) after about nine runs; CI starts from a clean stack.

const RECEIVER = process.env.E2E_WEBHOOK_RECEIVER_URL ?? 'http://127.0.0.1:8099';
const DESTINATION = 'https://receiver.hooks.test:8443/hook';
const stamp = Date.now().toString(36).toUpperCase().slice(-6);

type Received = {
  eventId: string | null;
  eventType: string | null;
  signatureValid: boolean;
  attempt: number;
  userAgent: string | null;
  status: number;
};

let release: (() => void) | undefined;

async function control(path: string, body?: unknown) {
  const response = await fetch(`${RECEIVER}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  expect(response.ok).toBe(true);
  return response.json() as Promise<unknown>;
}

const received = () => control('/control/received') as Promise<Received[]>;

async function openWebhooks(page: Page, link: string, title: string) {
  await primaryNav(page).getByRole('link', { name: link }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title);
}

async function guardCapacity(page: Page, bearer: () => string) {
  const response = await page.request.get(`${CORE_API}/integrations/webhooks?limit=100`, {
    headers: { Authorization: bearer() },
  });
  expect(response.status()).toBe(200);
  const body = (await response.json()) as { items: unknown[] };
  expect(
    body.items.length,
    'tenant A already holds 19 or more webhooks: reset the local stack (docker compose down -v)',
  ).toBeLessThan(19);
}

test.describe.serial('MVP-110: secure outbound webhooks', () => {
  test.beforeAll(async () => {
    test.setTimeout(300_000);
    // Writes as tenant A's administrator never overlap another file's.
    release = await subjectLock(USERS.adminA[0]);
    await control('/control/reset', {});
  });

  test.afterAll(() => {
    release?.();
  });

  test('an administrator creates, tests, rotates and disables a webhook in English', async ({
    page,
    context,
  }) => {
    test.setTimeout(240_000);
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    const bearer = await signIn(page, USERS.adminA, 'en');
    await guardCapacity(page, bearer);

    await openWebhooks(page, 'Webhooks', 'Webhooks');
    await page.getByRole('button', { name: 'New webhook' }).click();
    await page.getByLabel('Name', { exact: true }).fill(`E2E receiver ${stamp}`);
    await page.getByLabel('HTTPS endpoint').fill(`${DESTINATION}/${stamp}`);
    await page.getByLabel(/Leave request approved/u).check();
    await page.getByLabel('I confirm that this endpoint may receive these events.').check();
    await page.getByRole('button', { name: 'Create webhook' }).click();

    // The secret is shown once: copied, handed to the receiver, then gone.
    const secretField = page.getByTestId('secret-value');
    await expect(secretField).toBeVisible();
    const first = await secretField.inputValue();
    expect(first.length).toBeGreaterThanOrEqual(43);
    await expectAccessible(page);
    await page.getByRole('button', { name: 'Copy secret' }).click();
    await expect(page.getByTestId('copy-status')).toHaveText('Secret copied.');
    await control('/control/secrets', { secrets: [first] });
    await page.getByLabel('I have stored the secret safely.').check();
    await page.getByRole('button', { name: 'Done' }).click();
    await expect(secretField).toHaveCount(0);
    await expect(page.locator('body')).not.toContainText(first);
    expect(page.url()).not.toContain(first);

    // A test delivery is queued, sent by the worker and verified by the receiver.
    await page.getByRole('button', { name: 'Send a test delivery' }).click();
    await expect(page.getByTestId('webhook-detail').getByTestId('announcer')).toHaveText(
      'Test delivery queued. Follow it in the history.',
    );
    await expect(async () => {
      await page.getByRole('button', { name: 'Refresh history' }).click();
      await expect(page.getByTestId('history-row').first()).toContainText('Delivered', {
        timeout: 3_000,
      });
    }).toPass({ timeout: 60_000 });
    const afterFirst = await received();
    expect(afterFirst.at(-1)).toMatchObject({
      eventType: 'webhook.test.v1',
      signatureValid: true,
      attempt: 1,
      userAgent: 'DivalHR-Webhooks/1',
    });
    await expectAccessible(page);

    // Rotation: explicit confirmation, a new secret shown once, verified by the receiver.
    await page.getByRole('button', { name: 'Rotate secret' }).click();
    const rotate = page.getByRole('button', { name: 'Yes, rotate the secret' });
    await expect(rotate).toBeDisabled();
    await page.getByLabel('I understand and want to continue.').check();
    await rotate.click();
    const second = await secretField.inputValue();
    expect(second).not.toBe(first);
    await expect(page.getByText(/keeps signing until/u)).toBeVisible();
    await control('/control/secrets', { secrets: [second] });
    await page.getByLabel('I have stored the secret safely.').check();
    await page.getByRole('button', { name: 'Done' }).click();
    await expect(page.getByTestId('detail-status')).toHaveAttribute('data-status', 'ACTIVE');
    await page.getByRole('button', { name: 'Send a test delivery' }).click();
    await expect(async () => {
      expect((await received()).filter((r) => r.signatureValid).length).toBeGreaterThanOrEqual(2);
    }).toPass({ timeout: 60_000 });

    // Disabling stops everything; the history stays.
    await page.getByRole('button', { name: 'Disable', exact: true }).click();
    await page.getByLabel('I understand and want to continue.').check();
    await page.getByRole('button', { name: 'Yes, disable this webhook' }).click();
    await expect(page.getByTestId('detail-status')).toHaveAttribute('data-status', 'DISABLED');
    await expect(page.getByRole('button', { name: 'Send a test delivery' })).toBeDisabled();
    await expect(page.getByTestId('history-table')).toBeVisible();
    await expectAccessible(page);
  });

  test('un administrateur voit une destination refusée, un échec de livraison et réactive le webhook en français', async ({
    page,
  }) => {
    test.setTimeout(240_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    await guardCapacity(page, bearer);
    // A full window after the English journey's writes.
    await page.waitForTimeout(61_000);

    await openWebhooks(page, 'Webhooks', 'Webhooks');
    await page.getByRole('button', { name: 'Nouveau webhook' }).click();

    // A destination on the internal network is refused with a stable, translated message.
    await page.getByLabel('Nom', { exact: true }).fill(`Interne ${stamp}`);
    await page.getByLabel('Point de terminaison HTTPS').fill('https://internal.hooks.test/hook');
    await page.getByLabel(/Feuille de temps approuvée/u).check();
    await page
      .getByLabel('Je confirme que ce point de terminaison peut recevoir ces événements.')
      .check();
    await page.getByRole('button', { name: 'Créer le webhook' }).click();
    await expect(page.getByTestId('webhook-failure')).toContainText(
      'Cette destination ne peut pas être utilisée. Choisissez un point de terminaison HTTPS public.',
    );
    await expect(page.getByTestId('secret-value')).toHaveCount(0);
    await expectAccessible(page);

    // The valid receiver, which now answers 500.
    await control('/control/reset', {});
    await control('/control/status', { status: 500 });
    await page.getByLabel('Nom', { exact: true }).fill(`Récepteur ${stamp}`);
    await page.getByLabel('Point de terminaison HTTPS').fill(`${DESTINATION}/fr-${stamp}`);
    await page.getByRole('button', { name: 'Créer le webhook' }).click();
    const secretField = page.getByTestId('secret-value');
    await expect(secretField).toBeVisible();
    await control('/control/secrets', { secrets: [await secretField.inputValue()] });
    await page.getByLabel('J’ai conservé le secret en lieu sûr.').check();
    await page.getByRole('button', { name: 'Terminé' }).click();
    await expect(secretField).toHaveCount(0);

    await page.getByRole('button', { name: 'Envoyer une livraison de test' }).click();
    await expect(async () => {
      await page.getByRole('button', { name: 'Actualiser l’historique' }).click();
      const row = page.getByTestId('history-row').first();
      await expect(row).toContainText('Nouvelle tentative prévue', { timeout: 3_000 });
      await expect(row).toContainText('HTTP 500');
    }).toPass({ timeout: 60_000 });
    expect((await received()).at(-1)).toMatchObject({ signatureValid: true, status: 500 });
    await expectAccessible(page);

    // Disable (the waiting retry is cancelled), then enable again.
    await control('/control/status', { status: 204 });
    await page.getByRole('button', { name: 'Désactiver', exact: true }).click();
    await page.getByLabel('Je comprends et je souhaite continuer.').check();
    await page.getByRole('button', { name: 'Oui, désactiver ce webhook' }).click();
    await expect(page.getByTestId('detail-status')).toHaveAttribute('data-status', 'DISABLED');
    await page.getByRole('button', { name: 'Activer', exact: true }).click();
    await page.getByLabel('Je comprends et je souhaite continuer.').check();
    await page.getByRole('button', { name: 'Oui, activer ce webhook' }).click();
    await expect(page.getByTestId('detail-status')).toHaveAttribute('data-status', 'ACTIVE');
    await expectAccessible(page);
  });
});
