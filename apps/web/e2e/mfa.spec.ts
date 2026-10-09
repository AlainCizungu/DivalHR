import { expect, test, type Page } from '@playwright/test';
import {
  CORE_API,
  USERS,
  expectAccessible,
  freshCode,
  mailTo,
  signIn,
  signInFr,
  openSecretLink,
  typeCode,
  typeSecret,
  TOTP_SEEDS,
  totp,
  primaryNav,
} from './support';

// MVP-011. One-time codes and the authenticator secret created during the invited journey stay in
// memory: they are typed through the DOM and never appear in a step, trace, screenshot or message.

const INVITATION_LINK = /https?:\/\/[^\s"<>]+\/invitation#token=[A-Za-z0-9_-]{43}/u;
const SETUP_LINK = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u;

async function openUsers(page: Page) {
  await primaryNav(page).getByRole('link', { name: 'Utilisateurs et invitations' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Utilisateurs et invitations');
}

test.describe.serial('MVP-011: privileged roles complete MFA', { tag: '@identity' }, () => {
  const stamp = Date.now().toString(36);
  const invited = `e2e.admin.mfa.${stamp}@example.test`;
  const password = `Dev-only-E2E-${stamp}-Mfa!`;
  // Created by Keycloak during the invited journey; memory only.
  let invitedSecret: string | undefined;

  test('an employee signs in with a password only', async ({ page }) => {
    // No code is supplied: an authenticator page would stop the sign-in here.
    await signIn(page, USERS.employeeA, 'fr', undefined);
    await expect(page.getByRole('link', { name: 'Structure organisationnelle' })).toHaveCount(0);
  });

  test('Issue #77: an employee who reloads the app signs straight back in at password level', async ({
    page,
  }) => {
    await signIn(page, USERS.employeeA, 'fr', undefined);
    // A reload drops the in-memory tokens: the app is anonymous again, the Keycloak session lives.
    await page.reload();
    const keycloakPages: string[] = [];
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame() && new URL(frame.url()).pathname.includes('/realms/')) {
        keycloakPages.push(new URL(frame.url()).pathname);
      }
    });
    const tokenResponse = page.waitForResponse(
      (r) => r.url().endsWith('/protocol/openid-connect/token') && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Français', exact: true }).click();
    await page.getByRole('main').getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByTestId('session-tenant')).toHaveText(/^[0-9a-f-]{36}$/);
    // Keycloak reused the SSO session: no login, code or error page was displayed.
    expect(keycloakPages).toEqual([]);
    // Still password level (decoded in memory; only the level is compared).
    const idToken = String(
      ((await (await tokenResponse).json()) as { id_token?: string }).id_token,
    );
    const acr = (
      JSON.parse(Buffer.from(idToken.split('.')[1] ?? '', 'base64url').toString()) as {
        acr?: string;
      }
    ).acr;
    expect(acr === 'urn:divalhr:loa:pwd', 'password-level assurance').toBe(true);
    await expect(
      primaryNav(page).getByRole('link', { name: 'Structure organisationnelle' }),
    ).toHaveCount(0);
  });

  test('a platform administrator is asked for an authenticator code; a wrong code is refused', async ({
    page,
  }) => {
    const [username, secret] = [USERS.platformAdmin[0], TOTP_SEEDS[USERS.platformAdmin[0]] ?? ''];
    await page.goto('/');
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await page.getByRole('main').getByRole('button', { name: 'Sign in' }).click();
    await page.locator('#username').fill(username);
    await typeSecret(page, '#password', USERS.platformAdmin[1]);
    await page.locator('#kc-login').click();

    // A labelled code field, in the user's language.
    const code = page.getByLabel('One-time code');
    await expect(code).toBeVisible();
    await expect(code).toHaveAttribute('autocomplete', 'one-time-code');
    await typeCode(page, '#otp', '000000');
    await page.locator('#kc-login').click();
    await expect(page.locator('#input-error-otp')).toHaveText('Invalid authenticator code.');
    expect(new URL(page.url()).pathname).not.toBe('/');

    await typeCode(page, '#otp', await freshCode(page, username, secret));
    await page.locator('#kc-login').click();
    await page.waitForURL((url) => url.pathname === '/');
    await expect(page.getByTestId('session-tenant')).toHaveText(/^[0-9a-f-]{36}$/);

    // A privileged page works without any further prompt.
    await primaryNav(page).getByRole('link', { name: 'Create organization' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Authenticator code required' })).toHaveCount(0);
    await expectAccessible(page);
  });

  test('tenant administrator A invites another administrator', async ({ page }) => {
    await signInFr(page, USERS.adminA);
    await openUsers(page);
    const form = page.getByTestId('invite-form');
    await form.getByLabel('Adresse e-mail').fill(invited);
    await form.getByRole('radio', { name: 'Administrateur de l’organisation' }).check();
    await form.getByLabel('Langue de l’invitation').selectOption('fr');
    await form.getByRole('button', { name: 'Envoyer l’invitation' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      `Invitation créée pour ${invited}. Envoi en attente.`,
    );
  });

  test('the invited administrator accepts, then sets a password and an authenticator from the setup link', async ({
    browser,
  }) => {
    const link = INVITATION_LINK.exec(await mailTo(invited, /Invitation à rejoindre/u));
    expect(link, 'invitation link').not.toBeNull();
    const context = await browser.newContext({ locale: 'fr-FR' });
    const page = await context.newPage();
    await openSecretLink(page, link?.[0] ?? '');
    await expect(page.getByTestId('invitation-preview')).toContainText(
      'Administrateur de l’organisation',
    );
    await page.getByRole('button', { name: 'Accepter l’invitation' }).click();
    await expect(page.getByTestId('invitation-result')).toContainText('Invitation acceptée');

    const setup = SETUP_LINK.exec(
      await mailTo(invited, /mot de passe|password|actions|compte|account/iu),
    );
    expect(setup, 'setup link').not.toBeNull();
    await openSecretLink(page, setup?.[0] ?? '');
    const proceed = page.locator('a[href*="login-actions"]').first();
    if ((await page.locator('#password-new, #totp').count()) === 0 && (await proceed.count()) > 0) {
      await proceed.click();
    }
    // Keycloak asks for both, in its own order.
    for (let step = 0; step < 2; step++) {
      await expect(page.locator('#password-new').or(page.locator('#totp'))).toBeVisible();
      if ((await page.locator('#password-new').count()) > 0) {
        await typeSecret(page, '#password-new', password);
        await typeSecret(page, '#password-confirm', password);
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

  test('the new administrator signs in with password and code and administers the organization', async ({
    page,
  }) => {
    const secret = invitedSecret ?? '';
    const privileged = page.waitForResponse(
      (response) =>
        response.url().startsWith(`${CORE_API}/invitations`) &&
        response.request().method() === 'GET',
    );
    await signIn(page, [invited, password], 'fr', secret);
    await openUsers(page);
    // The Core accepted the MFA level: the privileged list loads.
    expect((await privileged).status()).toBe(200);
    await expect(
      page.getByTestId('invitation-list').or(page.getByTestId('invitation-list-empty')),
    ).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Code d’authentification requis' })).toHaveCount(
      0,
    );
    await expectAccessible(page);
  });

  test('the RFC 6238 generator used by these tests is correct', () => {
    // Appendix B, SHA-1, last six digits.
    expect(totp('12345678901234567890', Math.floor(59 / 30))).toBe('287082');
    expect(totp('12345678901234567890', Math.floor(1111111109 / 30))).toBe('081804');
  });
});
