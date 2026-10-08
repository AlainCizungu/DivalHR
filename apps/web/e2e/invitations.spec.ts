import { expect, test, type Page, type Request } from '@playwright/test';
import {
  CORE_API,
  USERS,
  expectAccessible,
  mailTo,
  signInFr,
  primaryNav,
  openSecretLink,
  typeSecret,
} from './support';

const LINK = /https?:\/\/[^\s"<>]+\/invitation#token=[A-Za-z0-9_-]{43}/u;

function invitationLink(text: string): string {
  const match = LINK.exec(text);
  expect(match, 'invitation link').not.toBeNull();
  return match?.[0] ?? '';
}

async function openUsers(page: Page) {
  await primaryNav(page).getByRole('link', { name: 'Utilisateurs et invitations' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Utilisateurs et invitations');
}

async function invite(
  page: Page,
  email: string,
  role: 'Employé' | 'Administrateur de l’organisation',
) {
  const form = page.getByTestId('invite-form');
  await form.getByLabel('Adresse e-mail').fill(email);
  await form.getByRole('radio', { name: role }).check();
  await form.getByLabel('Langue de l’invitation').selectOption('fr');
  await form.getByRole('button', { name: 'Envoyer l’invitation' }).click();
  await expect(page.getByTestId('announcer')).toHaveText(
    `Invitation créée pour ${email}. Envoi en attente.`,
  );
}

function rowOf(page: Page, email: string) {
  return page.getByTestId('invitation-row').filter({ hasText: email });
}

test.describe.serial('MVP-010: invite a user and assign a scoped role', () => {
  const stamp = Date.now().toString(36);
  const employee = `e2e.employe.${stamp}@example.test`;
  const revoked = `e2e.revoquee.${stamp}@example.test`;
  const password = `Dev-only-E2E-${stamp}-Mdp!`;

  test('tenant administrator A invites an employee; delivery wording follows the real state', async ({
    page,
  }) => {
    await signInFr(page, USERS.adminA);
    const list = page.waitForResponse(
      (response) =>
        response.url().startsWith(`${CORE_API}/invitations`) &&
        response.request().method() === 'GET',
    );
    await openUsers(page);
    // Admin lists carry email addresses: never stored by any cache.
    expect((await list).headers()['cache-control']).toBe('private, no-store');

    // Only tenant roles are offered.
    await expect(page.getByTestId('invite-form').getByRole('radio')).toHaveCount(2);
    await invite(page, employee, 'Employé');
    const row = rowOf(page, employee);
    // Right after creation the delivery is pending; "sent" appears only once the mail server
    // accepted the message.
    await expect(row.getByTestId('invitation-delivery')).toHaveText(
      /Invitation créée – envoi en attente|Invitation envoyée/u,
    );
    await expect
      .poll(
        async () => {
          await page.getByRole('button', { name: 'Actualiser la liste' }).click();
          return rowOf(page, employee)
            .getByTestId('invitation-delivery')
            .getAttribute('data-delivery');
        },
        { timeout: 30_000 },
      )
      .toBe('SENT');
    await expect(rowOf(page, employee).getByTestId('invitation-delivery')).toHaveText(
      'Invitation envoyée',
    );
    await expect(rowOf(page, employee).getByTestId('invitation-status')).toHaveText('En attente');
    await expectAccessible(page);
  });

  test('the invitee accepts anonymously; the token never reaches a URL or carries credentials', async ({
    browser,
  }) => {
    const text = await mailTo(employee, /Invitation à rejoindre/u);
    expect(text).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-/u); // no tenant or record ids
    const link = invitationLink(text);

    const context = await browser.newContext({ locale: 'fr-FR' });
    const page = await context.newPage();
    const api: Request[] = [];
    page.on('request', (request) => {
      if (request.url().startsWith(CORE_API)) api.push(request);
    });
    await openSecretLink(page, link);
    await expect(page.getByTestId('invitation-preview')).toContainText('Employé');
    // The fragment is removed from the address bar and the current history entry.
    expect(new URL(page.url()).hash).toBe('');
    expect(await page.evaluate(() => window.location.href)).not.toContain('token');
    await expectAccessible(page);

    const accepted = page.waitForResponse((r) => r.url().endsWith('/public/invitations/accept'));
    await page.getByRole('button', { name: 'Accepter l’invitation' }).click();
    const response = await accepted;
    expect(response.status()).toBe(200);
    expect(response.headers()['cache-control']).toBe('no-store');
    await expect(page.getByTestId('invitation-result')).toContainText('Invitation acceptée');

    const token = link.split('#token=')[1] ?? '';
    for (const request of api) {
      expect(request.method()).toBe('POST');
      expect(request.url()).not.toContain(token);
      expect(request.headers().authorization).toBeUndefined();
      expect(request.postDataJSON()).toStrictEqual({ token });
    }

    // The same link is now unusable, with the generic message.
    const again = await context.newPage();
    await openSecretLink(again, link);
    await expect(again.getByTestId('invitation-result')).toContainText(
      'Ce lien d’invitation n’est pas valide.',
    );
    await context.close();
  });

  test('the invitee chooses a password through the identity provider and signs in as an employee only', async ({
    browser,
  }) => {
    // The identity provider's own email (sent after acceptance) carries the password setup link.
    const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
      await mailTo(employee, /mot de passe|password|actions|compte|account/iu),
    );
    expect(setup, 'password setup link').not.toBeNull();

    const context = await browser.newContext({ locale: 'fr-FR' });
    const page = await context.newPage();
    await openSecretLink(page, setup?.[0] ?? '');
    // Keycloak may first show a "perform the following actions" confirmation.
    const proceed = page.locator('a[href*="login-actions"], #kc-info-message a').first();
    if ((await page.locator('#password-new').count()) === 0 && (await proceed.count()) > 0) {
      await proceed.click();
    }
    await typeSecret(page, '#password-new', password);
    await typeSecret(page, '#password-confirm', password);
    await page.locator('[type="submit"]').first().click();
    await context.close();

    const session = await browser.newContext({ locale: 'fr-FR' });
    const invitee = await session.newPage();
    await signInFr(invitee, [employee, password]);
    await expect(invitee.getByRole('link', { name: 'Utilisateurs et invitations' })).toHaveCount(0);
    await expect(invitee.getByRole('link', { name: 'Structure organisationnelle' })).toHaveCount(0);
    await session.close();
  });

  test('the administrator sees the acceptance; tenant B never sees tenant A invitations', async ({
    page,
    browser,
  }) => {
    await signInFr(page, USERS.adminA);
    await openUsers(page);
    await expect(rowOf(page, employee).getByTestId('invitation-status')).toHaveText('Acceptée');
    await expect(rowOf(page, employee).getByTestId('invitation-delivery')).toHaveCount(0);

    const other = await browser.newContext({ locale: 'fr-FR' });
    const pageB = await other.newPage();
    await signInFr(pageB, USERS.adminB);
    await openUsers(pageB);
    await expect(
      pageB.getByTestId('invitation-list').or(pageB.getByTestId('invitation-list-empty')),
    ).toBeVisible();
    await expect(rowOf(pageB, employee)).toHaveCount(0);
    await other.close();
  });

  test('a revoked invitation link stops working at once', async ({ page, browser }) => {
    await signInFr(page, USERS.adminA);
    await openUsers(page);
    await invite(page, revoked, 'Administrateur de l’organisation');
    const link = invitationLink(await mailTo(revoked, /Invitation à rejoindre/u));

    const row = rowOf(page, revoked);
    await row.getByRole('button', { name: `Révoquer l’invitation de ${revoked}` }).click();
    const confirm = row.getByRole('button', { name: 'Oui, révoquer' });
    await expect(confirm).toBeFocused();
    await confirm.click();
    await expect(row.getByTestId('invitation-status')).toHaveText('Révoquée');
    await expect(page.getByTestId('announcer')).toHaveText(`Invitation de ${revoked} révoquée.`);

    const context = await browser.newContext({ locale: 'fr-FR' });
    const invitee = await context.newPage();
    await openSecretLink(invitee, link);
    await expect(invitee.getByTestId('invitation-result')).toContainText(
      'Ce lien d’invitation n’est pas valide.',
    );
    await context.close();
  });

  test('employees cannot reach the page', async ({ page }) => {
    await signInFr(page, USERS.employeeA);
    await expect(page.getByRole('link', { name: 'Utilisateurs et invitations' })).toHaveCount(0);
    // In-app navigation keeps the in-memory session (a reload would sign the user out).
    await page.evaluate(() => {
      window.history.pushState({}, '', '/admin/users');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await expect(page.getByTestId('not-authorized')).toHaveText(
      'Seuls les administrateurs de l’organisation peuvent inviter des utilisateurs.',
    );
  });
});
