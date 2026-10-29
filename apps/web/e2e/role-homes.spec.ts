import { expect, test, type Page } from '@playwright/test';
import { expectAccessible, signIn, USERS } from './support';

/**
 * UI-003 (Issue #103), UI-004 (Issue #136) and MVP-093A (Issue #175, D93-14 to D93-16): the
 * refreshed landing page and role homes in a real browser. Three truthful states (Available now,
 * Capabilities available, Coming next), the payroll preparation and compensation message while
 * calculation, payslips and payment stay planned, per-entry integration states, Tasks and Expenses
 * in the illustrative preview; the organization
 * administrator's quick actions, three groups and the progress panel, and the employee's work
 * cards and approvals card, open their pages; each role sees only its own destinations; Axe and
 * reflow at 320 px, 390 px and 200 % zoom in English and French. The homes load no dashboard data
 * beyond the approved contract summary.
 */

const LANDING = {
  en: {
    language: 'English',
    available: 'Available now',
    capabilities: 'Capabilities available',
    later: 'Coming next',
    compensation: 'Monthly compensation records',
    next: 'Gross and net payroll calculation',
    active:
      'Payroll preparation and monthly compensation records are active. Salary calculation, statutory rules and payment are not.',
  },
  fr: {
    language: 'Français',
    available: 'Disponible maintenant',
    capabilities: 'Fonctionnalités disponibles',
    later: 'Prochainement',
    compensation: 'Rémunérations mensuelles enregistrées',
    next: 'Calcul de la paie brute et nette',
    active:
      'La préparation de la paie et les rémunérations mensuelles sont actives. Le calcul des salaires, les règles légales et le paiement ne le sont pas encore.',
  },
} as const;

async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
}

/** R150-4: every home-card heading link is at least 24 px tall (WCAG 2.2 target size), at any scroll. */
async function expectCardLinksBigEnough(page: Page) {
  const heights = await page
    .locator('.link-card__title a')
    .evaluateAll((links) => links.map((link) => link.getBoundingClientRect().height));
  for (const height of heights) expect(height).toBeGreaterThanOrEqual(24);
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
    await expectCardLinksBigEnough(page);
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
  test(`the landing page shows what is available now, what comes next and the approved-time message, in ${locale}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/');
    await page.getByRole('button', { name: LANDING[locale].language, exact: true }).click();
    const modules = page.getByTestId('landing-modules');
    await expect(modules.locator('li[data-status]')).toHaveCount(12);
    // D93-14: the exact module truth table, one state label per module.
    const truth = {
      available: ['employees', 'leave', 'ai'],
      capabilities: ['payroll', 'documents', 'analytics', 'workflow', 'integrations'],
      later: ['loans', 'benefits', 'performance', 'mobile'],
    } as const;
    for (const [state, ids] of Object.entries(truth)) {
      for (const id of ids) {
        const card = modules.locator(`li[data-status][data-module="${id}"]`);
        await expect(card).toHaveAttribute('data-status', state);
        await expect(card).toContainText(LANDING[locale][state as keyof typeof truth]);
      }
    }
    await expect(modules.locator('[data-testid="landing-remaining"]')).toHaveCount(5);
    // The two plain lists (no link, button or figure): Available now grouped, Coming next.
    const today = page.getByTestId('landing-today');
    await expect(today.getByRole('listitem')).toHaveCount(12);
    await expect(today.locator('li[data-module="compensation"]')).toHaveText(
      LANDING[locale].compensation,
    );
    const next = page.getByTestId('landing-next');
    await expect(next.getByRole('listitem')).toHaveCount(6);
    await expect(next.locator('li[data-module="payrollCalculation"]')).toHaveText(
      LANDING[locale].next,
    );
    await expect(today.locator('a, button')).toHaveCount(0);
    await expect(next.locator('a, button')).toHaveCount(0);
    // Payroll: preparation and compensation active, calculation and payment planned.
    await expect(page.getByTestId('landing-payroll-active')).toContainText(LANDING[locale].active);
    const payroll = page.locator('#payroll');
    for (const step of ['preparation', 'compensation']) {
      await expect(payroll.locator(`li[data-step="${step}"]`)).toContainText(
        LANDING[locale].available,
      );
    }
    for (const step of ['calculation', 'payment']) {
      await expect(payroll.locator(`li[data-step="${step}"]`)).toContainText(LANDING[locale].later);
    }
    // Integrations: each entry states its own state; only three are active.
    const integrations = page.getByTestId('landing-integrations');
    await expect(integrations.locator('li[data-entry]')).toHaveCount(20);
    await expect(integrations.locator('li[data-entry][data-state="capabilities"]')).toHaveCount(3);
    await expect(integrations.locator('li[data-entry="Slack"]')).toContainText(
      LANDING[locale].later,
    );
    await expect(integrations.locator('li[data-entry="WhatsApp Business"]')).toContainText(
      LANDING[locale].capabilities,
    );
    // The illustrative preview shows Tasks and Expenses, without customer data.
    const preview = page.getByTestId('landing-preview');
    await expect(preview.locator('.landing-preview__card')).toHaveCount(6);
    await expect(preview.locator('.landing-preview__link')).toHaveCount(9);
    await expectAccessible(page);
    await reflows(page);
  });
}

test.describe('organization administrator home', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('quick actions, the three groups and the progress panel open their pages, in English and French', async ({
    page,
  }) => {
    const api = trackApi(page);
    await signIn(page, USERS.adminA, 'en');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Organization administration');
    await expect(page.getByTestId('home-contract-attention')).toBeVisible();
    await expect(page.getByTestId('home-my-work')).toHaveCount(0);
    await expect(page.locator('main a[href^="/me/"]')).toHaveCount(0);
    // U4-3: the progress panel is static text with no control; the roadmap names what comes next.
    const progress = page.getByTestId('home-progress');
    await expect(progress.getByRole('listitem')).toHaveCount(5);
    await expect(progress).toContainText('Monthly compensation records');
    await expect(progress).toContainText('Payroll preparation, locking and export');
    await expect(progress.locator('a, button')).toHaveCount(0);
    const roadmap = page.getByTestId('roadmap');
    await expect(roadmap.getByRole('listitem')).toHaveCount(4);
    await expect(roadmap).toContainText('Coming next');
    await expect(roadmap).not.toContainText('locking');
    await expect(roadmap).not.toContainText('Leave');
    await expect(roadmap).not.toContainText('Scheduling');
    await expect(roadmap).not.toContainText('Attendance');
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
      ['Open timesheets', '/admin/timesheets'],
      ['Export approved time', '/admin/time-exports'],
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
          ['Organization structure', '/admin/hierarchy'],
          ['Leave policies', '/admin/leave-policies'],
          ['Leave approvals', '/admin/leave-approvals'],
        ],
      ],
      [
        'operations',
        [
          ['Schedules', '/admin/work-schedules'],
          ['Attendance review', '/admin/attendance-review'],
          ['Timesheets', '/admin/timesheets'],
          ['Time exports', '/admin/time-exports'],
          ['Payroll preparation', '/admin/payroll-preparation'],
          ['Tasks', '/admin/tasks'],
          ['Expenses', '/admin/expenses'],
        ],
      ],
      [
        'governance',
        [
          ['Contract templates', '/admin/contract-templates'],
          ['Users and invitations', '/admin/users'],
          ['Access review', '/admin/access'],
        ],
      ],
      [
        'integrations',
        [
          ['Webhooks', '/admin/webhooks'],
          ['WhatsApp Business', '/admin/integrations/whatsapp'],
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
    ).toHaveText('Personnel et congés');
    await expect(
      page.getByTestId('home-group-operations').getByRole('heading', { level: 2 }),
    ).toHaveText('Opérations et temps');
    await expect(
      page.getByTestId('home-group-governance').getByRole('heading', { level: 2 }),
    ).toHaveText('Contrats et accès');
    await expect(page.getByTestId('home-progress')).toContainText(
      'Progrès récents de la plateforme',
    );
    await expect(page.getByTestId('home-card-time-exports')).toContainText(
      'aucune paie n’est calculée',
    );
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
    // U4-4: no administrator capability, progress panel or organization-wide evidence.
    await expect(page.getByTestId('quick-actions')).toHaveCount(0);
    await expect(page.getByTestId('home-contract-attention')).toHaveCount(0);
    await expect(page.getByTestId('home-progress')).toHaveCount(0);
    await expect(page.locator('main a[href^="/admin/"]')).toHaveCount(0);
    await expect(page.getByRole('main')).not.toContainText('Exports de temps');
    const roadmap = page.getByTestId('roadmap');
    await expect(roadmap.getByRole('listitem')).toHaveCount(3);
    await expect(roadmap).toContainText('Prochainement');
    await expect(roadmap).not.toContainText('Congés');
    await expect(roadmap).not.toContainText('Mes tâches');
    // D93-15: no administrator destination, and the conditional expense-approval card is present.
    await expect(page.getByTestId('home-expense-approvals')).toBeVisible();
    // UI3-7: the employee home loads only the session and, as the one approved exception
    // (MVP-060B, B60B-8), the first item of the caller's own team-attendance queue for the
    // conditional task card; an employee who manages nobody sees no card.
    await page.waitForLoadState('networkidle');
    expect(
      [...new Set(api)].filter(
        (path) => !['/session', '/me/team-attendance/actions'].includes(path),
      ),
    ).toEqual([]);
    await expect(page.getByTestId('home-team-attendance')).toHaveCount(0);
    await expectAccessible(page);

    const home = () =>
      page
        .getByRole('navigation', { name: 'Navigation principale' })
        .getByRole('link', { name: 'Accueil', exact: true })
        .click();
    for (const [name, path] of [
      ['Mon horaire', '/me/schedule'],
      ['Mes congés', '/me/leave'],
      ['Ma présence', '/me/attendance'],
      ['Mes feuilles de temps', '/me/timesheets'],
      ['Mes tâches', '/me/tasks'],
      ['Mes notes de frais', '/me/expenses'],
      ['Ma rémunération', '/me/compensation'],
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
    await page
      .getByTestId('home-expense-approvals')
      .getByRole('link', { name: 'Ouvrir les approbations de notes de frais' })
      .click();
    await expect(page).toHaveURL(/\/me\/expense-approvals$/u);
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
