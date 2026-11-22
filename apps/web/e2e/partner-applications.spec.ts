import { expect, test, type Locator, type Page } from '@playwright/test';
import { expectAccessible, primaryNav, signIn, sql, USERS } from './support';

// MVP-120D (Issue #215, ADR 0043): exactly two journeys, run without retries and with the seeded
// platform administrator. Each submits a synthetic partner application as an anonymous visitor,
// reviews it as the platform administrator, and proves that approval created nothing: no
// invitation, no notification, no listing, credential or integration. The applications use
// example.test addresses and invented names only. The internal note is checked in the URL and in
// browser storage; the layout (320, 390 and 640 px), keyboard focus, target size and text spacing
// are checked on the public form and on the review page.
//
// Journey 1: English, public bank and microfinance application, then review.
// Journey 2: French, insurance and telecom application, through "more information required" and
// "approved for onboarding".

test.describe.configure({ retries: 0 });

const MAILPIT = process.env.E2E_MAILPIT_URL ?? 'http://127.0.0.1:8025';
const REFERENCE = /^PA-[0-9A-HJKMNP-TV-Z]{12}$/u;
const NOTE = 'internal review note 9c41';

const COPY = {
  en: {
    language: 'English',
    landingCta: 'Apply to partner with DivalHR',
    legalName: 'Legal name',
    registrationCountry: 'Country of registration',
    operation: 'Countries of operation',
    registration: 'Registration reference',
    primary: 'Primary category',
    additional: 'Additional categories',
    regulator: 'Regulator',
    licence: 'Licence reference',
    services: 'Services you would like to discuss',
    summary: 'Summary of your service',
    website: 'Website',
    api: 'Planned',
    contactName: 'Contact name',
    contactRole: 'Contact role',
    email: 'Business email',
    privacyEmail: 'Privacy contact email',
    acknowledgment:
      'I confirm that I am authorized to submit this information for partnership review.',
    submit: 'Submit application',
    summaryTitle: 'Please correct the following:',
    confirmation: 'Application received',
    nav: 'Partner applications',
    boundary: 'No credentials, integration or access to employee data was created',
    confirmApprove: 'I confirm approval for onboarding discussions only.',
    apply: 'Apply',
    approved: 'Approved for onboarding',
    underReview: 'Under review',
  },
  fr: {
    language: 'Français',
    landingCta: 'Candidater pour devenir partenaire de DivalHR',
    legalName: 'Dénomination légale',
    registrationCountry: 'Pays d’immatriculation',
    operation: 'Pays d’activité',
    registration: 'Référence d’immatriculation',
    primary: 'Catégorie principale',
    additional: 'Catégories supplémentaires',
    regulator: 'Autorité de régulation',
    licence: 'Référence d’agrément',
    services: 'Services dont vous souhaitez discuter',
    summary: 'Résumé de votre service',
    website: 'Site web',
    api: 'Prévue',
    contactName: 'Nom du contact',
    contactRole: 'Fonction du contact',
    email: 'Courriel professionnel',
    privacyEmail: 'Courriel du contact vie privée',
    acknowledgment:
      'Je confirme être autorisé(e) à soumettre ces informations pour examen de partenariat.',
    submit: 'Envoyer la candidature',
    summaryTitle: 'Veuillez corriger les points suivants :',
    confirmation: 'Candidature reçue',
    nav: 'Candidatures de partenaires',
    boundary: 'Aucun identifiant, aucune intégration et aucun accès',
    confirmApprove: 'Je confirme l’approbation pour les seules discussions d’intégration.',
    apply: 'Appliquer',
    approved: 'Approuvée pour l’intégration',
    underReview: 'En cours d’examen',
  },
} as const;
type Locale = keyof typeof COPY;

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

async function sessionKeys(page: Page): Promise<string[]> {
  return page.evaluate(() => Object.keys(window.sessionStorage));
}

/**
 * R216-4: the only browser-storage entry is the four-field receipt (reference, time, status and
 * next-step code). No form field, contact data, regulatory value, consent datum, honeypot or
 * internal note is ever stored, in sessionStorage or localStorage.
 */
async function expectOnlyTheSafeReceiptStored(page: Page, reference: string, a: Applicant) {
  expect(await sessionKeys(page)).toEqual(['divalhr.partner.receipt']);
  const raw = await page.evaluate(() => window.sessionStorage.getItem('divalhr.partner.receipt'));
  const receipt = JSON.parse(raw ?? '{}') as Record<string, string>;
  expect(Object.keys(receipt).sort()).toEqual(['nextStep', 'reference', 'status', 'submittedAt']);
  expect(receipt.reference).toBe(reference);
  expect(receipt.status).toBe('SUBMITTED');
  expect(receipt.nextStep).toBe('DIVALHR_WILL_CONTACT_BUSINESS_EMAIL');
  const everything = await storage(page);
  for (const forbidden of [
    a.name,
    a.email,
    a.privacyEmail,
    'SYN-E2E-0001',
    'Synthetic Regulator',
    'LIC-E2E-0001',
    'PARTNER-PRIVACY',
    'PARTNER-APPLICATION-CONSENT',
    'faxNumber',
    NOTE,
  ]) {
    expect(everything).not.toContain(forbidden);
  }
  const local = await page.evaluate(() => JSON.stringify(Object.entries(window.localStorage)));
  expect(local).not.toContain(reference);
}

/** Reflow at 390, 320 and 640 px (200 % zoom of a 1280 px window), then back to desktop. */
async function noOverflow(page: Page) {
  for (const viewport of [
    { width: 390, height: 800 },
    { width: 320, height: 720 },
    { width: 640, height: 400 },
  ]) {
    await page.setViewportSize(viewport);
    const wide = await page.evaluate(() =>
      document.documentElement.scrollWidth <= window.innerWidth
        ? []
        : Array.from(document.body.querySelectorAll('*'))
            .filter((element) => element.getBoundingClientRect().right > window.innerWidth + 1)
            .slice(0, 6)
            .map((element) => `${element.tagName.toLowerCase()}[${element.className}]`),
    );
    expect(wide, `no horizontal overflow at ${viewport.width} px`).toEqual([]);
    await expectAccessible(page);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

/** WCAG 1.4.12: with the author's spacing overridden, nothing is clipped or pushed off screen. */
async function textSpacing(page: Page) {
  // A constructed stylesheet: the page's CSP (style-src 'self') refuses an injected <style> element.
  await page.evaluate((css) => {
    const sheet = new CSSStyleSheet();
    sheet.replaceSync(css);
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
  }, '* { line-height: 1.5 !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; } p { margin-bottom: 2em !important; }');
  for (const width of [320, 390, 640]) {
    await page.setViewportSize({ width, height: 800 });
    const problems = await page.evaluate(() => {
      const found: string[] = [];
      if (document.documentElement.scrollWidth > window.innerWidth) found.push('page overflow');
      for (const element of Array.from(
        document.querySelectorAll('button, label, legend, h1, h2'),
      )) {
        const box = element.getBoundingClientRect();
        if (box.width < 2 || box.height < 2) continue;
        const style = getComputedStyle(element);
        const clips = style.overflow !== 'visible' || style.overflowX !== 'visible';
        if (clips && element.scrollWidth > element.clientWidth + 1) {
          found.push(`${element.tagName.toLowerCase()} clipped`);
        }
      }
      return found;
    });
    expect(problems, `text spacing at ${width} px`).toEqual([]);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

/** The first field takes focus from the keyboard and shows a visible focus indicator. */
async function keyboardFocusVisible(page: Page, first: Locator) {
  for (let step = 0; step < 40; step++) {
    if (await first.evaluate((element) => element === document.activeElement)) break;
    await page.keyboard.press('Tab');
  }
  expect(await first.evaluate((element) => element === document.activeElement)).toBe(true);
  const indicator = await first.evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      focusVisible: element.matches(':focus-visible'),
      outline: style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0,
      shadow: style.boxShadow !== 'none',
    };
  });
  expect(indicator.focusVisible).toBe(true);
  expect(indicator.outline || indicator.shadow).toBe(true);
}

/** Controls a finger must hit are at least 24 by 24 CSS px (WCAG 2.5.8). */
async function targetSize(page: Page) {
  const small = await page.evaluate(() =>
    Array.from(
      document.querySelectorAll(
        'main input:not([tabindex="-1"]):not([type="checkbox"]):not([type="radio"]), main select, main textarea, main button',
      ),
    )
      .map((element) => ({ element, box: element.getBoundingClientRect() }))
      .filter(({ box }) => box.width > 0 && (box.width < 24 || box.height < 24))
      .map(
        ({ element }) => `${element.tagName.toLowerCase()}[${element.getAttribute('name') ?? ''}]`,
      ),
  );
  expect(small).toEqual([]);
}

function field(page: Page, label: string) {
  return page.getByLabel(label, { exact: true });
}

interface Applicant {
  locale: Locale;
  name: string;
  primary: 'BANK' | 'INSURANCE';
  additional: string;
  capability: string;
  email: string;
  privacyEmail: string;
}

/** Fills the public form up to, but not including, the submit button. */
async function fillApplication(page: Page, a: Applicant) {
  const t = COPY[a.locale];
  await page.getByRole('group', { name: t.operation }).locator('input[value="CD"]').check();
  await field(page, t.legalName).fill(a.name);
  await field(page, t.registrationCountry).selectOption('CD');
  await field(page, t.registration).fill('SYN-E2E-0001');
  await field(page, t.primary).selectOption(a.primary);
  await page
    .getByRole('group', { name: t.additional })
    .getByRole('checkbox', { name: a.additional })
    .check();
  await expect(page.getByTestId('partner-regulatory-fields')).toBeVisible();
  await field(page, t.regulator).fill('Synthetic Regulator');
  await field(page, t.licence).fill('LIC-E2E-0001');
  await page
    .getByRole('group', { name: t.services })
    .getByRole('checkbox', { name: a.capability })
    .check();
  await field(page, t.summary).fill('A synthetic end-to-end application for review.');
  await field(page, t.website).fill('https://example.test');
  await page.getByRole('radio', { name: t.api }).check();
  await field(page, t.contactName).fill('Test Contact');
  await field(page, t.contactRole).fill('Head of partnerships');
  await field(page, t.email).fill(a.email);
  await field(page, t.privacyEmail).fill(a.privacyEmail);
}

/** Anonymous visitor: validation keeps values, the honeypot stays hidden, then submit. */
async function apply(page: Page, a: Applicant, fromLanding: boolean): Promise<string> {
  const t = COPY[a.locale];
  await page.goto('/');
  await page.getByRole('button', { name: t.language, exact: true }).click();
  if (fromLanding) {
    await page.getByTestId('landing-partner-cta').click();
    await expect(page).toHaveURL(/\/partners\/apply$/u);
  } else {
    await page.goto('/partners/apply');
  }
  await expect(page.getByTestId('partner-apply-page')).toBeVisible();

  // The honeypot is off screen, inert, out of the tab order and out of the accessibility tree.
  const trap = page.getByTestId('partner-trap');
  await expect(trap).toHaveAttribute('aria-hidden', 'true');
  await expect(trap).toHaveAttribute('inert', '');
  await expect(trap.locator('input')).toHaveAttribute('tabindex', '-1');
  await expect(page.getByRole('textbox', { name: /fax/iu })).toHaveCount(0);

  await keyboardFocusVisible(page, field(page, t.legalName));
  await expectAccessible(page);
  await noOverflow(page);
  await textSpacing(page);
  await targetSize(page);
  await page.reload();
  await page.getByRole('button', { name: t.language, exact: true }).click();

  // An ordinary validation error keeps what was typed.
  await field(page, t.legalName).fill(a.name);
  await page.getByRole('button', { name: t.submit }).click();
  await expect(page.getByTestId('partner-error-summary')).toContainText(t.summaryTitle);
  await expect(field(page, t.legalName)).toHaveValue(a.name);
  await expect(page.getByTestId('partner-confirmation')).toHaveCount(0);

  await fillApplication(page, a);
  await page.getByRole('checkbox', { name: t.acknowledgment }).check();
  await page.getByRole('button', { name: t.submit }).click();

  const confirmation = page.getByTestId('partner-confirmation');
  await expect(confirmation).toBeVisible();
  await expect(confirmation.getByRole('heading', { level: 1 })).toHaveText(t.confirmation);
  const reference = (await confirmation.getByTestId('partner-reference').innerText()).trim();
  expect(reference).toMatch(REFERENCE);
  await expect(confirmation.getByTestId('partner-submitted-at')).toBeVisible();
  await expect(confirmation.getByTestId('partner-next-step')).toBeVisible();
  // Only the reference, the time and the next step: no status link, nothing typed, no storage.
  await expect(confirmation.getByRole('link')).toHaveCount(0);
  await expect(confirmation).not.toContainText(a.name);
  expect(page.url()).not.toContain(reference);
  await expectOnlyTheSafeReceiptStored(page, reference, a);
  await expectAccessible(page);

  // R216-4: a reload in the same tab session restores the confirmation from the safe receipt.
  await page.reload();
  const restored = page.getByTestId('partner-confirmation');
  await expect(restored).toBeVisible();
  await expect(restored.getByTestId('partner-reference')).toHaveText(reference);
  await expect(restored.getByTestId('partner-submitted-at')).toBeVisible();
  await expect(restored.getByTestId('partner-next-step')).toBeVisible();
  await expect(page.getByTestId('partner-apply-page')).toHaveCount(0);
  await expectOnlyTheSafeReceiptStored(page, reference, a);

  // "Copy reference" answers with a visible status; the clipboard itself may be denied headless.
  await restored.getByTestId('partner-copy-reference').click();
  await expect(restored.getByTestId('partner-copy-status')).not.toHaveText('');

  // "Start another application" clears the stored receipt and shows an empty form.
  await restored.getByTestId('partner-start-another').click();
  await expect(page.getByTestId('partner-apply-page')).toBeVisible();
  await expect(field(page, t.legalName)).toHaveValue('');
  expect(await sessionKeys(page)).toEqual([]);
  await page.reload();
  await expect(page.getByTestId('partner-apply-page')).toBeVisible();
  await expect(page.getByTestId('partner-confirmation')).toHaveCount(0);
  return reference;
}

async function openApplication(page: Page, locale: Locale, reference: string) {
  const t = COPY[locale];
  await primaryNav(page).getByRole('link', { name: t.nav }).click();
  const row = page.locator(
    `[data-testid="partner-application-row"][data-reference="${reference}"]`,
  );
  await expect(row).toBeVisible();
  await row.getByRole('link', { name: reference }).click();
  await expect(page.getByTestId('partner-application-detail')).toBeVisible();
  await expect(page.getByTestId('partner-detail-reference')).toHaveText(reference);
  expect(page.url()).toMatch(new RegExp(`/admin/partner-applications/${reference}$`, 'u'));
}

async function transition(
  page: Page,
  to: string,
  opts: { reason?: string; confirm?: string } = {},
) {
  await page.getByTestId(`partner-transition-${to}`).click();
  const form = page.getByTestId('partner-transition-form');
  if (opts.reason) await form.getByRole('combobox').selectOption(opts.reason);
  if (opts.confirm) await form.getByRole('checkbox', { name: opts.confirm }).check();
  await form.getByRole('button', { name: /^(Apply|Appliquer)$/u }).click();
  await expect(page.getByTestId('partner-transition-form')).toHaveCount(0);
}

async function satisfyMandatoryChecks(page: Page, withNote: boolean) {
  const mandatory = [
    'BUSINESS_IDENTITY',
    'REGISTRATION_REFERENCE',
    'REGULATORY_INFORMATION',
    'SERVICE_SCOPE',
    'PRIVACY_SECURITY_CONTACT',
    'TECHNICAL_API_READINESS',
  ];
  for (const code of mandatory) {
    const check = page.locator(`[data-testid="partner-check"][data-check="${code}"]`);
    await check.getByRole('combobox').selectOption('SATISFIED');
    if (withNote && code === 'SERVICE_SCOPE') await check.getByRole('textbox').fill(NOTE);
    await check.getByRole('button').click();
    await expect(check.getByTestId('partner-check-result')).not.toHaveText(
      /^(Pending|En attente)$/u,
    );
    if (withNote && code === 'SERVICE_SCOPE') {
      // The note is shown to the administrator only; it never reaches the URL or browser storage.
      await expect(check.getByTestId('partner-check-note')).toContainText(NOTE);
      expect(page.url()).not.toContain('note');
      expect(await storage(page)).not.toContain(NOTE);
    }
  }
}

/** Nothing was created for the applicant: no invitation and no message to either address. */
async function expectNothingCreated(a: Applicant) {
  const invitations = sql(
    `SELECT count(*) FROM identity.invitation WHERE lower(email) IN ('${a.email}', '${a.privacyEmail}')`,
  );
  expect(invitations).toBe('0');
  for (const address of [a.email, a.privacyEmail]) {
    const response = await fetch(
      `${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${address}"`)}`,
    );
    const { messages } = (await response.json()) as { messages: unknown[] };
    expect(messages, `no message to ${address}`).toHaveLength(0);
  }
}

test('MVP-120D journey 1: an English bank and microfinance application is reviewed and approved for onboarding', async ({
  browser,
  page,
}) => {
  const stamp = Date.now().toString(36);
  const a: Applicant = {
    locale: 'en',
    name: `Synthetic Bank E2E ${stamp}`,
    primary: 'BANK',
    additional: 'Microfinance institution',
    capability: 'Salary advance',
    email: `partner.bank.${stamp}@example.test`,
    privacyEmail: `partner.bank.privacy.${stamp}@example.test`,
  };
  const t = COPY.en;

  const visitor = await (await browser.newContext()).newPage();
  const reference = await apply(visitor, a, true);
  await visitor.context().close();

  await signIn(page, USERS.platformAdmin, 'en');
  await openApplication(page, 'en', reference);
  await expect(page.getByTestId('partner-contact')).toContainText(a.email);
  await expectAccessible(page);
  await noOverflow(page);
  await textSpacing(page);
  await targetSize(page);

  await transition(page, 'UNDER_REVIEW');
  await expect(page.getByTestId('partner-status')).toHaveText(t.underReview);
  // Approval is blocked while mandatory checks are open.
  await expect(page.getByTestId('partner-transition-APPROVED_FOR_ONBOARDING')).toBeDisabled();
  await satisfyMandatoryChecks(page, true);

  await page.getByTestId('partner-transition-APPROVED_FOR_ONBOARDING').click();
  await expect(page.getByTestId('partner-approval-warning')).toContainText(t.boundary);
  await page
    .getByTestId('partner-transition-form')
    .getByRole('checkbox', { name: t.confirmApprove })
    .check();
  await page.getByTestId('partner-transition-form').getByRole('button', { name: t.apply }).click();
  await expect(page.getByTestId('partner-status')).toHaveText(t.approved);
  await expect(page.getByTestId('partner-approval-boundary')).toContainText(t.boundary);
  await expect(page.getByTestId('partner-terminal')).toBeVisible();
  await expect(page.getByTestId('partner-history')).toContainText(t.approved);
  expect(page.url()).not.toContain(NOTE);
  expect(await storage(page)).not.toContain(NOTE);
  await expectAccessible(page);

  await expectNothingCreated(a);
});

test('MVP-120D journey 2: a French insurance and telecom application goes through more information to approval', async ({
  browser,
  page,
}) => {
  const stamp = Date.now().toString(36);
  const a: Applicant = {
    locale: 'fr',
    name: `Assurance Synthétique E2E ${stamp}`,
    primary: 'INSURANCE',
    additional: 'Opérateur télécom',
    capability: 'Assurance santé',
    email: `partenaire.assurance.${stamp}@example.test`,
    privacyEmail: `partenaire.assurance.vie-privee.${stamp}@example.test`,
  };
  const t = COPY.fr;

  const visitor = await (await browser.newContext()).newPage();
  const reference = await apply(visitor, a, false);
  await visitor.context().close();

  await signIn(page, USERS.platformAdmin, 'fr');
  await openApplication(page, 'fr', reference);
  await expectAccessible(page);

  await transition(page, 'UNDER_REVIEW');
  await expect(page.getByTestId('partner-status')).toHaveText(t.underReview);
  await transition(page, 'MORE_INFORMATION_REQUIRED', { reason: 'CLARIFY_SERVICE_SCOPE' });
  await expect(page.getByTestId('partner-status')).toHaveText(
    'Informations complémentaires requises',
  );
  await transition(page, 'UNDER_REVIEW');
  await expect(page.getByTestId('partner-status')).toHaveText(t.underReview);
  await satisfyMandatoryChecks(page, false);

  await page.getByTestId('partner-transition-APPROVED_FOR_ONBOARDING').click();
  await expect(page.getByTestId('partner-approval-warning')).toContainText(t.boundary);
  await page
    .getByTestId('partner-transition-form')
    .getByRole('checkbox', { name: t.confirmApprove })
    .check();
  await page.getByTestId('partner-transition-form').getByRole('button', { name: t.apply }).click();
  await expect(page.getByTestId('partner-status')).toHaveText(t.approved);
  await expect(page.getByTestId('partner-approval-boundary')).toContainText(t.boundary);
  await expect(page.getByTestId('partner-history')).toContainText('Informations demandées');
  await expectAccessible(page);

  await expectNothingCreated(a);
});
