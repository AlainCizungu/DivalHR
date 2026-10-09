import { expect, test, type Page } from '@playwright/test';
import { CORE_API, USERS, expectAccessible, primaryNav, signIn } from './support';

// MVP-040A: leave policy configuration with the real identity provider and database. A French
// administrator opens the page from the People navigation, creates a tracked policy, sees it in
// the catalogue with the server's status, is told a reused code exists, switches to English and
// creates an untracked policy with the keyboard; the page has no horizontal overflow at 320 px,
// 390 px and 200 % zoom. An employee is refused in the UI and the API. No policy name or code
// reaches browser storage.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const ANNUAL = `AN-${stamp}`;
const SICK = `SK-${stamp}`;
const ANNUAL_FR = `Congé annuel ${stamp} — été`;
const ANNUAL_EN = `Annual leave ${stamp}`;
const SICK_FR = `Congé maladie ${stamp}`;
const SICK_EN = `Sick leave ${stamp}`;

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

test.describe.serial('MVP-040A: leave policies', () => {
  test('an administrator creates policies in French and English', async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, USERS.adminA, 'fr');
    await primaryNav(page).getByRole('link', { name: 'Politiques de congé', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Politiques de congé');
    await expect(page.getByTestId('leave-scope')).toContainText(
      'Les employés soumettent leurs demandes dans Mes congés ; le calcul des soldes et les approbations ne sont pas encore activés.',
    );
    await expect(page.getByTestId('as-of')).toContainText('(Africa/Kinshasa)');

    const form = page.getByTestId('create-policy');
    await form.getByLabel('Code', { exact: true }).fill(ANNUAL.toLowerCase());
    await form.getByLabel('Nom en anglais').fill(ANNUAL_EN);
    await form.getByLabel('Nom en français').fill(ANNUAL_FR);
    await form.getByLabel('Unité').selectOption({ label: 'Jours' });
    await form.getByLabel('Solde', { exact: true }).selectOption({ label: 'Droit annuel suivi' });
    await form.getByLabel('Droit annuel').fill('18,5');
    await form.getByLabel('Ancienneté minimale (jours)').fill('90');
    await form.getByLabel('Approbation').selectOption({ label: 'Approuvée par le responsable' });
    await form.getByLabel('Description pour la paie').selectOption({ label: 'Congé payé' });
    await form.getByLabel('En vigueur à partir du').fill('2026-01-01');
    await form.getByRole('button', { name: 'Créer la politique' }).click();

    const row = page.getByTestId('policy-row').filter({ hasText: ANNUAL });
    await expect(row).toHaveCount(1);
    await expect(row.getByRole('rowheader')).toContainText(ANNUAL_FR);
    await expect(row.getByRole('rowheader')).toContainText(ANNUAL_EN);
    await expect(row).toContainText('18,5 jours par an');
    await expect(row).toContainText('Après 90 jours d’ancienneté');
    await expect(row.getByTestId('policy-status')).toHaveText('En vigueur');
    await expect(page.getByTestId('announcer')).toContainText(ANNUAL_FR);
    await expect(form.getByLabel('Code', { exact: true })).toHaveValue('');
    await expectAccessible(page);

    // The same code again: a stable conflict, the form kept.
    await form.getByLabel('Code', { exact: true }).fill(ANNUAL);
    await form.getByLabel('Nom en anglais').fill('Another name');
    await form.getByLabel('Nom en français').fill('Un autre nom');
    await form.getByLabel('Unité').selectOption({ label: 'Jours' });
    await form.getByLabel('Solde', { exact: true }).selectOption({ label: 'Aucun solde suivi' });
    await form.getByLabel('Ancienneté minimale (jours)').fill('0');
    await form.getByLabel('Approbation').selectOption({ label: 'Approuvée par le responsable' });
    await form.getByLabel('Description pour la paie').selectOption({ label: 'Congé payé' });
    await form.getByLabel('En vigueur à partir du').fill('2026-01-01');
    await form.getByRole('button', { name: 'Créer la politique' }).click();
    await expect(page.getByTestId('create-error')).toContainText(
      'Une politique de congé avec ce code existe déjà dans votre organisation.',
    );
    await expect(page.getByTestId('create-error')).toBeFocused();
    await expect(form.getByLabel('Code', { exact: true })).toHaveValue(ANNUAL);
    await noOverflow(page);

    // English, without reloading: the English name leads; then a keyboard-only creation.
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Leave policies');
    await expect(row.getByRole('rowheader')).toContainText(ANNUAL_EN);
    await expect(row.getByTestId('policy-status')).toHaveText('Active');
    await expect(row).toContainText('18.5 days per year');

    await form.getByLabel('Code', { exact: true }).fill('');
    await form.getByLabel('Code', { exact: true }).focus();
    await page.keyboard.type(SICK);
    await page.keyboard.press('Tab');
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.type(SICK_EN);
    await page.keyboard.press('Tab');
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.type(SICK_FR);
    await form.getByLabel('Unit').selectOption({ label: 'Hours' });
    await form.getByLabel('Balance', { exact: true }).selectOption({ label: 'No balance tracked' });
    await form.getByLabel('Minimum service (days)').fill('0');
    await form.getByLabel('Approval').selectOption({
      label: 'Approved by an organization administrator',
    });
    await form.getByLabel('Payroll description').selectOption({ label: 'Unpaid leave' });
    await form.getByLabel('Effective from').fill('2999-01-01');
    await form.getByLabel('Effective until (optional)').fill('2999-12-31');
    await form.getByLabel('Effective until (optional)').press('Enter');

    const sick = page.getByTestId('policy-row').filter({ hasText: SICK });
    await expect(sick).toHaveCount(1);
    await expect(sick.getByRole('rowheader')).toContainText(SICK_EN);
    await expect(sick).toContainText('No balance tracked');
    await expect(sick).toContainText('No minimum service');
    await expect(sick.getByTestId('policy-status')).toHaveText('Planned');
    await expectAccessible(page);
    await noOverflow(page);
    expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|Congé|Annual leave`, 'iu'));
  });

  test('an employee is refused in the UI and the API', async ({ page }) => {
    const bearer = await signIn(page, USERS.employeeA, 'fr', undefined);
    await expect(primaryNav(page).getByRole('link', { name: 'Politiques de congé' })).toHaveCount(
      0,
    );
    // In-app navigation keeps the in-memory session (a reload would sign the user out).
    await page.evaluate(() => {
      window.history.pushState({}, '', '/admin/leave-policies');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await expect(page.getByTestId('not-authorized')).toHaveText(
      'Seuls les administrateurs de l’organisation peuvent gérer les politiques de congé.',
    );
    for (const response of [
      await page.request.get(`${CORE_API}/leave-policies`, {
        headers: { Authorization: bearer() },
      }),
      await page.request.post(`${CORE_API}/leave-policies`, {
        headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-leave-deny-${stamp}-0001` },
        data: { code: `DENY-${stamp}` },
      }),
    ]) {
      expect(response.status()).toBe(403);
      expect(((await response.json()) as { code: string }).code).toBe('ACCESS_DENIED');
      expect(response.headers()['cache-control']).toContain('no-store');
    }
  });
});
