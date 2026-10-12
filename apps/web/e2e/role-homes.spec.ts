import { expect, test, type Page } from '@playwright/test';
import { expectAccessible, signIn, USERS } from './support';

/**
 * UI-003 (Issue #103): the refreshed landing page and role homes in a real browser. Leave is
 * available and Work scheduling is in "In DivalHR today" while the rest stays planned; the
 * organization administrator's quick actions and both groups, and the employee's work cards and
 * approvals card, open their pages; each role sees only its own destinations; Axe and reflow at
 * 320 px, 390 px and 200 % zoom in English and French. The homes load no dashboard data beyond the
 * approved contract summary.
 */

const LANDING = {
  en: {
    language: 'English',
    available: 'Available now',
    later: 'Coming later',
    scheduling: 'Work scheduling',
  },
  fr: {
    language: 'Français',
    available: 'Disponible',
    later: 'À venir',
    scheduling: 'Planification du travail',
  },
} as const;

async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
}

async function reflows(page: Page) {
  for (const viewport of [
    { width: 320, height: 720 },
    { width: 390, height: 844 },
    // 200 % zoom of a 1280 px window.
    { width: 640, height: 400 },
  ]) {
    await page.setViewportSize(viewport);
    await expectNoHorizontalScroll(page);
    await expectAccessible(page);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

/** Records the Core API paths the page requests (UI3-7). */
function trackApi(page: Page) {
  const paths: string[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname.includes('/api/v1/')) paths.push(url.pathname.replace(/^.*\/api\/v1/u, ''));
  });
  return paths;
}

for (const locale of ['en', 'fr'] as const) {
  test(`the landing page shows Leave available and Work scheduling today, in ${locale}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/');
    await page.getByRole('button', { name: LANDING[locale].language, exact: true }).click();
    const modules = page.getByTestId('landing-modules');
    await expect(modules.locator('li[data-status][data-module="leave"]')).toContainText(
      LANDING[locale].available,
    );
    for (const id of ['payroll', 'loans', 'benefits', 'integrations', 'ai', 'mobile']) {
      await expect(modules.locator(`li[data-status][data-module="${id}"]`)).toContainText(
        LANDING[locale].later,
      );
    }
    await expect(modules.locator('li[data-status]')).toHaveCount(12);
    await expect(
      page.getByTestId('landing-today').locator('li[data-module="scheduling"]'),
    ).toContainText(LANDING[locale].scheduling);
    await expect(page.getByTestId('landing-preview').locator('.landing-preview__card')).toHaveCount(
      4,
    );
    await expectAccessible(page);
    await reflows(page);
  });
}

test.describe('organization administrator home', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('quick actions and both groups open their pages, in English and French', async ({
    page,
  }) => {
    const api = trackApi(page);
    await signIn(page, USERS.adminA, 'en');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Organization administration');
    await expect(page.getByTestId('home-contract-attention')).toBeVisible();
    await expect(page.getByTestId('home-my-work')).toHaveCount(0);
    await expect(page.locator('main a[href^="/me/"]')).toHaveCount(0);
    const roadmap = page.getByTestId('roadmap');
    await expect(roadmap.getByRole('listitem')).toHaveCount(3);
    await expect(roadmap).not.toContainText('Leave');
    await expect(roadmap).not.toContainText('Scheduling');
    // UI3-7: the home loads only the session and the approved contract summary.
    await page.waitForLoadState('networkidle');
    expect(
      [...new Set(api)].filter(
        (path) => !['/session', '/contract-expirations/summary'].includes(path),
      ),
    ).toEqual([]);
    await expectAccessible(page);

    const home = () =>
      page
        .getByRole('navigation', { name: 'Main navigation' })
        .getByRole('link', { name: 'Home', exact: true })
        .click();
    for (const [name, path] of [
      ['Import employees', '/admin/people/import'],
      ['Review leave requests', '/admin/leave-approvals'],
      ['Open work schedules', '/admin/work-schedules'],
      ['Invite a user', '/admin/users'],
    ] as const) {
      await page.getByTestId('quick-actions').getByRole('link', { name, exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`${path}$`, 'u'));
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await home();
    }
    for (const [group, cards] of [
      [
        'people',
        [
          ['Employees', '/admin/people'],
          ['Leave policies', '/admin/leave-policies'],
          ['Leave approvals', '/admin/leave-approvals'],
          ['Work schedules', '/admin/work-schedules'],
        ],
      ],
      [
        'governance',
        [
          ['Organization structure', '/admin/hierarchy'],
          ['Contract templates', '/admin/contract-templates'],
          ['Users and invitations', '/admin/users'],
          ['Access review', '/admin/access'],
        ],
      ],
    ] as const) {
      for (const [name, path] of cards) {
        await page
          .getByTestId(`home-group-${group}`)
          .getByRole('link', { name, exact: true })
          .click();
        await expect(page).toHaveURL(new RegExp(`${path}$`, 'u'));
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
        await home();
      }
    }

    await page.getByRole('button', { name: 'Français', exact: true }).click();
    await expect(
      page.getByTestId('home-group-people').getByRole('heading', { level: 2 }),
    ).toHaveText('Personnel et opérations');
    await expect(
      page.getByTestId('home-group-governance').getByRole('heading', { level: 2 }),
    ).toHaveText('Organisation et gouvernance');
    await expect(page.getByTestId('home-contract-attention')).toContainText('Contrats à traiter');
    await page.waitForLoadState('networkidle');
    await expectAccessible(page);
    await reflows(page);
  });
});

test.describe('employee home', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('My work and the approvals card open their pages, in French and English', async ({
    page,
  }) => {
    const api = trackApi(page);
    await signIn(page, USERS.employeeA, 'fr');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Mon espace');
    await expect(page.getByTestId('home-my-work').getByRole('heading', { level: 2 })).toHaveText(
      'Mon travail',
    );
    await expect(page.getByTestId('quick-actions')).toHaveCount(0);
    await expect(page.getByTestId('home-contract-attention')).toHaveCount(0);
    await expect(page.locator('main a[href^="/admin/"]')).toHaveCount(0);
    const roadmap = page.getByTestId('roadmap');
    await expect(roadmap.getByRole('listitem')).toHaveCount(3);
    await expect(roadmap).not.toContainText('Congés');
    // UI3-7: the employee home requests nothing beyond the session.
    await page.waitForLoadState('networkidle');
    expect([...new Set(api)].filter((path) => path !== '/session')).toEqual([]);
    await expectAccessible(page);

    const home = () =>
      page
        .getByRole('navigation', { name: 'Navigation principale' })
        .getByRole('link', { name: 'Accueil', exact: true })
        .click();
    for (const [name, path] of [
      ['Mes congés', '/me/leave'],
      ['Mon horaire', '/me/schedule'],
      ['Ma présence', '/me/attendance'],
      ['Mes contrats', '/me/contracts'],
    ] as const) {
      await page.getByTestId('home-my-work').getByRole('link', { name, exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`${path}$`, 'u'));
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await home();
    }
    await page
      .getByTestId('home-leave-approvals')
      .getByRole('link', { name: 'Ouvrir les approbations de congé' })
      .click();
    await expect(page).toHaveURL(/\/me\/leave\/approvals$/u);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await home();
    // MVP-051: the shift-substitution card opens the manager's page.
    await page
      .getByTestId('home-shift-substitutions')
      .getByRole('link', { name: 'Ouvrir les remplacements d’horaire' })
      .click();
    await expect(page).toHaveURL(/\/me\/schedule\/substitutions$/u);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Remplacements d’horaire');
    await home();

    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(
      page.getByTestId('home-leave-approvals').getByRole('heading', { level: 2 }),
    ).toHaveText('Leave approvals assigned to you');
    await expectAccessible(page);
    await reflows(page);
  });
});
