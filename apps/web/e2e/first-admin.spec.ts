import { expect, test, type Page } from '@playwright/test';
import { CORE_API, USERS, expectAccessible, freshCode, mailTo, signIn, typeCode } from './support';

// MVP-014 (Issue #38). A platform administrator creates an organization and invites its first
// administrator; the invitee accepts, sets a password and an authenticator in the real Keycloak,
// signs in with both and administers the new organization. One-time codes, the authenticator
// secret and invitation links stay in memory and are never written to a step, trace or message.

const INVITATION_LINK = /https?:\/\/[^\s"<>]+\/invitation#token=[A-Za-z0-9_-]{43}/u;
const SETUP_LINK = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u;

async function inviteFirstAdministrator(page: Page, email: string) {
  const panel = page.getByTestId('first-admin-panel');
  const form = panel.getByTestId('first-admin-form');
  await expect(form).toBeVisible();
  await form.getByLabel('Email address').fill(email);
  await form.getByLabel('Language of the invitation').selectOption('fr');
  await expect(form).toContainText('This person becomes an organization administrator.');
  await form.getByRole('button', { name: 'Send invitation' }).click();
  await expect(panel.getByTestId('first-admin-invitation')).toBeVisible();
  // The address is never echoed back by the bootstrap API.
  await expect(panel).not.toContainText(email);
}

test.describe.serial('MVP-014: first tenant administrator', () => {
  const stamp = Date.now().toString(36);
  const name = `Clinique Première E2E ${stamp}`;
  const revokedAddress = `e2e.first.revoked.${stamp}@example.test`;
  const invited = `e2e.first.admin.${stamp}@example.test`;
  const employee = `e2e.first.employee.${stamp}@example.test`;
  const password = `Dev-only-E2E-${stamp}-First!`;
  let organizationId = '';
  let invitedSecret: string | undefined;
  let revokedLink: string | undefined;

  test('a platform administrator creates an organization and invites, revokes and re-invites its first administrator', async ({
    page,
  }) => {
    await signIn(page, USERS.platformAdmin, 'en');
    await page.getByRole('link', { name: 'Create organization' }).click();
    await page.getByLabel('Organization name').fill(name);
    await page.getByRole('button', { name: 'Create organization' }).click();
    const success = page.getByTestId('organization-created');
    await expect(success).toBeVisible();
    organizationId = (await success.getByTestId('created-id').innerText()).trim();
    expect(organizationId).toMatch(/^[0-9a-f-]{36}$/u);

    await inviteFirstAdministrator(page, revokedAddress);
    const panel = page.getByTestId('first-admin-panel');
    await expect(panel.getByTestId('first-admin-delivery')).toHaveText(
      /Invitation created – delivery pending|Invitation sent/u,
    );
    await expectAccessible(page);
    revokedLink = INVITATION_LINK.exec(
      await mailTo(revokedAddress, /Invitation à rejoindre/u),
    )?.[0];
    expect(revokedLink, 'first invitation link').toBeDefined();

    await panel.getByRole('button', { name: 'Revoke the invitation' }).click();
    const confirm = panel.getByRole('button', { name: 'Yes, revoke' });
    await expect(confirm).toBeFocused();
    await confirm.click();
    await inviteFirstAdministrator(page, invited);
    // The organization reference never reaches the address bar.
    expect(page.url()).not.toContain(organizationId);
  });

  test('the revoked link is refused', async ({ browser }) => {
    const context = await browser.newContext({ locale: 'fr-FR' });
    const page = await context.newPage();
    await page.goto(revokedLink ?? '');
    await expect(page.getByTestId('invitation-result')).toContainText(
      'Ce lien d’invitation n’est pas valide.',
    );
    await context.close();
  });

  test('the first administrator accepts, then sets a password and an authenticator', async ({
    browser,
  }) => {
    const link = INVITATION_LINK.exec(await mailTo(invited, /Invitation à rejoindre/u));
    expect(link, 'invitation link').not.toBeNull();
    const context = await browser.newContext({ locale: 'fr-FR' });
    const page = await context.newPage();
    await page.goto(link?.[0] ?? '');
    await expect(page.getByTestId('invitation-preview')).toContainText(
      'Administrateur de l’organisation',
    );
    await expectAccessible(page);
    await page.getByRole('button', { name: 'Accepter l’invitation' }).click();
    await expect(page.getByTestId('invitation-result')).toContainText('Invitation acceptée');

    const setup = SETUP_LINK.exec(
      await mailTo(invited, /mot de passe|password|actions|compte|account/iu),
    );
    expect(setup, 'setup link').not.toBeNull();
    await page.goto(setup?.[0] ?? '');
    const proceed = page.locator('a[href*="login-actions"]').first();
    if ((await page.locator('#password-new, #totp').count()) === 0 && (await proceed.count()) > 0) {
      await proceed.click();
    }
    for (let step = 0; step < 2; step++) {
      await expect(page.locator('#password-new').or(page.locator('#totp'))).toBeVisible();
      if ((await page.locator('#password-new').count()) > 0) {
        await page.locator('#password-new').fill(password);
        await page.locator('#password-confirm').fill(password);
        await page.locator('[type="submit"]').first().click();
      } else {
        invitedSecret = await page.locator('#totpSecret').inputValue();
        await typeCode(page, '#totp', await freshCode(page, invited, invitedSecret));
        await page.locator('#userLabel').fill('E2E authenticator');
        await page.locator('#saveTOTPBtn').click();
      }
    }
    await expect(page.locator('#password-new').or(page.locator('#totp'))).toHaveCount(0);
    expect(invitedSecret, 'an authenticator was set up').toBeDefined();
    await context.close();
  });

  test('the first administrator signs in with password and code and invites an employee', async ({
    page,
  }) => {
    const list = page.waitForResponse(
      (response) =>
        response.url().startsWith(`${CORE_API}/invitations`) &&
        response.request().method() === 'GET',
    );
    await signIn(page, [invited, password], 'fr', invitedSecret ?? '');
    // The session belongs to the new organization, never to a fixture tenant.
    await expect(page.getByTestId('session-tenant')).toHaveText(organizationId);
    await page.getByRole('link', { name: 'Utilisateurs et invitations' }).click();
    expect((await list).status()).toBe(200);
    const own = page.getByTestId('invitation-row').filter({ hasText: invited });
    await expect(own).toContainText('Invité par DivalHR lors de la création de l’organisation');

    const form = page.getByTestId('invite-form');
    await form.getByLabel('Adresse e-mail').fill(employee);
    await form.getByRole('radio', { name: 'Employé' }).check();
    await form.getByLabel('Langue de l’invitation').selectOption('fr');
    await form.getByRole('button', { name: 'Envoyer l’invitation' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      `Invitation créée pour ${employee}. Envoi en attente.`,
    );
    await expectAccessible(page);
  });

  test('the platform administrator now sees the organization as unavailable for bootstrap', async ({
    page,
  }) => {
    await signIn(page, USERS.platformAdmin, 'fr');
    await page.getByRole('link', { name: 'Premier administrateur' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(
      'Inviter le premier administrateur d’une organisation',
    );
    await page.getByLabel('Référence de l’organisation').fill(organizationId);
    await page.getByRole('button', { name: 'Continuer' }).click();
    await expect(page.getByTestId('first-admin-unavailable')).toBeVisible();
    await expect(page.getByTestId('first-admin-form')).toHaveCount(0);
    expect(page.url()).not.toContain(organizationId);
    await expectAccessible(page);
  });
});
