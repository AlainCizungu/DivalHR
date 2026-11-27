import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { expect, test, type Browser, type Page } from '@playwright/test';
import {
  acceptInvitationInUi,
  expectAccessible,
  openInvitationInUi,
  openSecretLink,
  primaryNav,
} from '../support';
import {
  addressFor,
  completeAccountSetup,
  get,
  mailTo,
  post,
  randomPassword,
  requireOrigins,
  requireSyntheticEnv,
  signIn,
  type SyntheticEnv,
} from './support';

/**
 * LAUNCH-002 acceptance journey 2 of exactly two (bilingual synthetic tenant). The launch lock stays
 * CLOSED and only operator-controlled synthetic data exists. Through the authorized platform path a
 * platform administrator creates ONE synthetic pilot organization and invites its first
 * administrator; the invitations travel through the production e-mail path to plus-addresses of the
 * operator's own mailbox (read only through PROD_ACCEPTANCE_MAIL_CMD). A small bilingual workflow
 * then runs: employee import (French), leave (French request, English review, manager decision),
 * schedule and attendance, timesheet (submit, approve, lock) and payroll preparation (English).
 * Cleanup uses only product paths: the employees are separated with immediate access removal and the
 * identity provider's sign-in removal is awaited; no statutory or retention pack is activated, no
 * partner is contacted, nothing is deleted from the database. The first administrator cannot be
 * separated by design: the operator disables that identity in the realm console and records it
 * (docs/security/LAUNCH-002-ACCEPTANCE-TENANT.md). The organization stays, with no active access.
 * Credentials and links stay in memory; only counts and statuses are written to the evidence file.
 */
const ZONE_PRIMARY = 'Africa/Lubumbashi';
const ZONE_FALLBACK = 'Africa/Kinshasa';

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const ORG_NAME = `ACCEPTANCE-SYNTHETIC ${stamp}`;
const ENTITY = `AC-${stamp}`;
const SITE = `AS-${stamp}`;
const ANA = `ACC-${stamp}-1`;
const MIA = `ACC-${stamp}-2`;
const POLICY = `ACL-${stamp}`;
const POLICY_EN = `Acceptance leave ${stamp}`;
const POLICY_FR = `Congé de recette ${stamp}`;
const SHIFT_EN = `Acceptance shift ${lower}`;
const SHIFT_FR = `Quart de recette ${lower}`;

function localParts(zone: string, at: Date) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
      .formatToParts(at)
      .map((part) => [part.type, part.value]),
  ) as Record<string, string>;
  return {
    date: `${parts.year}-${parts.month}-${parts.day}`,
    time: `${parts.hour}:${parts.minute}`,
  };
}

function plusDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function weekStart(date: string): string {
  const weekday = new Date(`${date}T00:00:00Z`).getUTCDay();
  return plusDays(date, -((weekday + 6) % 7));
}

const evidence: { step: string; result: string }[] = [];
const record = (step: string, result = 'PASS') => {
  evidence.push({ step, result });
};

async function inviteAndSetUp(
  browser: Browser,
  env: SyntheticEnv,
  address: string,
  admin: { page: Page; bearer: () => string },
  password: string,
  key: string,
  employeeId: string,
) {
  expect(
    (
      await post(admin.page, admin.bearer, '/invitations', `acc-inv-${key}-${stamp}`, {
        email: address,
        role: 'employee',
        locale: 'fr',
      })
    ).status(),
  ).toBe(201);
  const link = /https?:\/\/[^\s"<>]+\/invitation#token=[A-Za-z0-9_-]{43}/u.exec(
    await mailTo(env, address, /Invitation à rejoindre/u),
  );
  expect(link, 'invitation link delivered by e-mail').not.toBeNull();
  const context = await browser.newContext({ locale: 'fr-FR' });
  const page = await context.newPage();
  expect((await openInvitationInUi(page, link?.[0] ?? '')).status()).toBe(200);
  expect((await acceptInvitationInUi(page)).status()).toBe(200);
  const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
    await mailTo(env, address, /mot de passe|password|actions|compte|account/iu),
  );
  expect(setup, 'account setup link delivered by e-mail').not.toBeNull();
  await openSecretLink(page, setup?.[0] ?? '');
  await completeAccountSetup(page, address, password, false);
  await context.close();
  const lookup = await post(
    admin.page,
    admin.bearer,
    `/employees/${employeeId}/access-link/lookup`,
    null,
    { email: address },
  );
  expect(lookup.status()).toBe(200);
  const membershipId = ((await lookup.json()) as { membershipId: string }).membershipId;
  expect(
    (
      await post(
        admin.page,
        admin.bearer,
        `/employees/${employeeId}/access-link`,
        `acc-link-${key}-${stamp}`,
        {
          membershipId,
        },
      )
    ).status(),
  ).toBe(201);
}

async function employeeId(page: Page, bearer: () => string, number: string): Promise<string> {
  const found = await post(page, bearer, '/employees/search', null, { query: number });
  expect(found.status()).toBe(200);
  const items = ((await found.json()) as { items: { id: string; employeeNumber: string }[] }).items;
  const match = items.find((item) => item.employeeNumber === number);
  expect(match, `employee ${number}`).toBeDefined();
  return match?.id ?? '';
}

async function separate(page: Page, bearer: () => string, id: string, day: string, key: string) {
  const command = { lastDay: day, reasonCode: 'OTHER_SEPARATION', accessTiming: 'IMMEDIATELY' };
  const preview = await post(page, bearer, `/employees/${id}/separations/preview`, null, command);
  expect(preview.status()).toBe(200);
  const previewed = (await preview.json()) as {
    expectedVersion: number;
    previewDigest: string;
    reportPlanRequired: boolean;
    requiredAcknowledgements: string[];
    blockers: unknown[];
  };
  expect(previewed.blockers, 'no blocking future effects').toHaveLength(0);
  const created = await post(
    page,
    bearer,
    `/employees/${id}/separations`,
    `acc-sep-${key}-${stamp}`,
    {
      ...command,
      ...(previewed.reportPlanRequired ? { reportPlan: { action: 'CLEAR' } } : {}),
      expectedVersion: previewed.expectedVersion,
      previewDigest: previewed.previewDigest,
      acknowledgements: previewed.requiredAcknowledgements,
    },
  );
  expect(created.status()).toBe(201);
  // The product's own proof that the sign-in was removed: the access state reaches COMPLETED.
  await expect
    .poll(
      async () => {
        const listed = await get(page, bearer, `/employees/${id}/separations`);
        const body = (await listed.json()) as unknown;
        const items = Array.isArray(body) ? body : ((body as { items?: unknown[] }).items ?? []);
        return (items[0] as { access?: string } | undefined)?.access;
      },
      { timeout: 180_000, intervals: [3000] },
    )
    .toBe('COMPLETED');
}

test('bilingual synthetic tenant: create, invite, work a small payroll-preparation week, clean up', async ({
  browser,
}) => {
  test.setTimeout(1_800_000);
  requireOrigins();
  const env = requireSyntheticEnv();
  const adminAddress = addressFor(env.mailbox, `acc-admin-${lower}`);
  const anaAddress = addressFor(env.mailbox, `acc-ana-${lower}`);
  const miaAddress = addressFor(env.mailbox, `acc-mia-${lower}`);
  const adminPassword = randomPassword();
  const employeePassword = randomPassword();
  let adminSecret: string | undefined;
  let organizationId = '';

  const zone = localParts(ZONE_PRIMARY, new Date()).time < '01:00' ? ZONE_FALLBACK : ZONE_PRIMARY;
  const minute = Math.floor(Date.now() / 60_000) * 60_000;
  const start = localParts(zone, new Date(minute - 60 * 60_000));
  const end = localParts(zone, new Date(minute - 45 * 60_000));
  const day = start.date;
  expect(end.date, 'the shift stays on one local date').toBe(day);
  const monday = weekStart(day);

  const platformContext = await browser.newContext({ locale: 'en-US' });
  const platform = await platformContext.newPage();

  await test.step('platform administrator creates the synthetic organization and invites its first administrator', async () => {
    await signIn(platform, [env.adminUser, env.adminPassword], 'en', env.adminTotpSecret);
    await primaryNav(platform).getByRole('link', { name: 'Create organization' }).click();
    await platform.getByLabel('Organization name').fill(ORG_NAME);
    await platform.getByRole('button', { name: 'Create organization' }).click();
    const success = platform.getByTestId('organization-created');
    await expect(success).toBeVisible();
    organizationId = (await success.getByTestId('created-id').innerText()).trim();
    expect(organizationId).toMatch(/^[0-9a-f-]{36}$/u);
    const form = platform.getByTestId('first-admin-panel').getByTestId('first-admin-form');
    await expect(form).toBeVisible();
    await form.getByLabel('Email address').fill(adminAddress);
    await form.getByLabel('Language of the invitation').selectOption('fr');
    await form.getByRole('button', { name: 'Send invitation' }).click();
    await expect(platform.getByTestId('first-admin-invitation')).toBeVisible();
    await expectAccessible(platform);
    record('organization created and first administrator invited');
  });

  await test.step('the first administrator accepts through the e-mail link and sets password and authenticator', async () => {
    const link = /https?:\/\/[^\s"<>]+\/invitation#token=[A-Za-z0-9_-]{43}/u.exec(
      await mailTo(env, adminAddress, /Invitation à rejoindre/u),
    );
    expect(link, 'invitation link delivered by production e-mail').not.toBeNull();
    const context = await browser.newContext({ locale: 'fr-FR' });
    const page = await context.newPage();
    expect((await openInvitationInUi(page, link?.[0] ?? '')).status()).toBe(200);
    await expect(page.getByTestId('invitation-preview')).toContainText(
      'Administrateur de l’organisation',
    );
    expect((await acceptInvitationInUi(page)).status()).toBe(200);
    const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
      await mailTo(env, adminAddress, /mot de passe|password|actions|compte|account/iu),
    );
    expect(setup, 'setup link delivered by production e-mail').not.toBeNull();
    await openSecretLink(page, setup?.[0] ?? '');
    adminSecret = await completeAccountSetup(page, adminAddress, adminPassword, true);
    expect(adminSecret, 'an authenticator was set up').toBeDefined();
    await context.close();
    record('first administrator onboarded through production e-mail');
  });

  const adminContext = await browser.newContext({ locale: 'fr-FR' });
  const adminPage = await adminContext.newPage();
  const adminBearer = await signIn(adminPage, [adminAddress, adminPassword], 'fr', adminSecret);
  await expect(adminPage.getByTestId('session-tenant')).toHaveText(organizationId);
  const admin = { page: adminPage, bearer: adminBearer };

  let siteId = '';
  let entityId = '';
  await test.step('structure and French employee import', async () => {
    const entity = await post(adminPage, adminBearer, '/legal-entities', `acc-le-${stamp}`, {
      code: ENTITY,
      name: `Entité de recette ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    entityId = ((await entity.json()) as { id: string }).id;
    const site = await post(adminPage, adminBearer, '/sites', `acc-st-${stamp}`, {
      legalEntityId: entityId,
      code: SITE,
      name: `Site de recette ${stamp}`,
      timezone: zone,
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    siteId = ((await site.json()) as { id: string }).id;

    await primaryNav(adminPage).getByRole('link', { name: 'Importer des employés' }).click();
    await expect(adminPage.getByRole('heading', { level: 1 })).toHaveText('Importer des employés');
    const hired = plusDays(day, -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site',
      `${ANA};Ana;Recette${lower.replace(/[^a-z]/gu, '')};${hired};${ENTITY};${SITE}`,
      `${MIA};Mia;Contrôle${lower.replace(/[^a-z]/gu, '')};${hired};${ENTITY};${SITE}`,
    ].join('\r\n');
    await adminPage.getByLabel('Fichier CSV').setInputFiles({
      name: 'recette.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from(file, 'utf8'),
    });
    await adminPage.getByRole('button', { name: 'Vérifier le fichier' }).click();
    await expect(adminPage.getByTestId('rows-table')).toContainText('Ana');
    await adminPage.getByRole('button', { name: 'Importer 2 employés' }).click();
    await adminPage.getByRole('button', { name: 'Oui, importer' }).click();
    await expect(adminPage.getByTestId('result-created')).toHaveText('2 employés créés.');
    await expectAccessible(adminPage);
    record('employee import (French): 2 employees created');
  });

  const anaId = await employeeId(adminPage, adminBearer, ANA);
  const miaId = await employeeId(adminPage, adminBearer, MIA);

  await test.step('Mia manages Ana; both are invited through production e-mail and linked', async () => {
    const change = {
      type: 'CHANGE',
      effectiveFrom: day,
      reasonCode: 'LATE_NOTIFICATION',
      manager: { employeeId: miaId },
    };
    const preview = await post(
      adminPage,
      adminBearer,
      `/employees/${anaId}/employment-changes/preview`,
      null,
      change,
    );
    expect(preview.status()).toBe(200);
    const previewed = (await preview.json()) as {
      expectedVersion: number;
      previewDigest: string;
      requiresAcknowledgement: boolean;
    };
    expect(
      (
        await post(
          adminPage,
          adminBearer,
          `/employees/${anaId}/employment-changes`,
          `acc-mgr-${stamp}`,
          {
            ...change,
            expectedVersion: previewed.expectedVersion,
            previewDigest: previewed.previewDigest,
            acknowledgeRetroactive: previewed.requiresAcknowledgement,
          },
        )
      ).status(),
    ).toBe(201);
    await inviteAndSetUp(browser, env, anaAddress, admin, employeePassword, 'ana', anaId);
    await inviteAndSetUp(browser, env, miaAddress, admin, employeePassword, 'mia', miaId);
    record('manager link and two employee invitations through production e-mail');
  });

  await test.step('leave: French request by the employee, English review, manager decision', async () => {
    expect(
      (
        await post(adminPage, adminBearer, '/leave-policies', `acc-pol-${stamp}`, {
          code: POLICY,
          names: { en: POLICY_EN, fr: POLICY_FR },
          unit: 'DAYS',
          balanceMode: 'UNTRACKED',
          minimumServiceDays: 0,
          approvalRoute: 'MANAGER',
          payrollEffect: 'PAID',
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const context = await browser.newContext({ locale: 'fr-FR' });
    const page = await context.newPage();
    await signIn(page, [anaAddress, employeePassword], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mes congés', exact: true }).click();
    const form = page.getByTestId('request-leave');
    await form.getByLabel('Politique de congé').selectOption({ label: `${POLICY_FR} (${POLICY})` });
    await form.getByLabel('Premier jour').fill(day);
    await form.getByLabel('Dernier jour').fill(day);
    await form.getByLabel('Jours demandés').fill('1');
    await form.getByRole('button', { name: 'Soumettre la demande' }).click();
    await expect(page.getByTestId('my-request')).toHaveCount(1);
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(page.getByTestId('my-request').getByTestId('request-state')).toHaveText(
      /Pending/u,
    );
    await expectAccessible(page);
    await context.close();

    const mia = await browser.newContext({ locale: 'en-US' });
    const miaPage = await mia.newPage();
    const miaBearer = await signIn(miaPage, [miaAddress, employeePassword], 'en', undefined);
    const approvals = await get(miaPage, miaBearer, '/me/leave-approvals');
    expect(approvals.status()).toBe(200);
    const pending = ((await approvals.json()) as { items: { id: string }[] }).items;
    expect(pending).toHaveLength(1);
    expect(
      (
        await post(
          miaPage,
          miaBearer,
          `/me/leave-approvals/${pending[0]?.id ?? ''}/decision`,
          `acc-lv-${stamp}`,
          {
            decision: 'APPROVED',
            reasonLocale: 'fr',
            reason: 'Approuvé pour la recette synthétique',
          },
        )
      ).status(),
    ).toBe(200);
    await mia.close();
    record('leave requested (fr), reviewed (en) and approved by the manager');
  });

  await test.step('schedule and attendance: a published shift and an administrator-created record', async () => {
    const proposal = {
      siteId,
      firstDate: day,
      lastDate: day,
      slots: [
        {
          clientRef: 'acc',
          start: `${start.date}T${start.time}`,
          end: `${end.date}T${end.time}`,
          labels: { en: SHIFT_EN, fr: SHIFT_FR },
          employeeId: anaId,
        },
      ],
    };
    const preview = await post(adminPage, adminBearer, '/work-schedules/preview', null, proposal);
    expect(preview.status()).toBe(200);
    const digest = ((await preview.json()) as { previewDigest: string }).previewDigest;
    const published = await post(adminPage, adminBearer, '/work-schedules', `acc-pub-${stamp}`, {
      ...proposal,
      previewDigest: digest,
      confirm: true,
    });
    expect(published.status()).toBe(201);
    const slot = (
      (await published.json()) as { slots: { id: string; startAt: string; endAt: string }[] }
    ).slots[0];
    expect(slot).toBeDefined();
    const missing = {
      slotId: slot.id,
      employeeId: anaId,
      checkedInAt: slot.startAt,
      checkedOutAt: slot.endAt,
      reasonCode: 'DEVICE_OR_CONNECTIVITY',
      explanation: 'Synthetic acceptance record created by the administrator.',
    };
    const reviewed = await post(
      adminPage,
      adminBearer,
      '/attendance/corrections/missing-record-preview',
      null,
      missing,
    );
    expect(reviewed.status()).toBe(200);
    const body = (await reviewed.json()) as { targetVersion: number; previewDigest: string };
    expect(
      (
        await post(
          adminPage,
          adminBearer,
          '/attendance/corrections/missing-record',
          `acc-att-${stamp}`,
          {
            ...missing,
            targetVersion: body.targetVersion,
            previewDigest: body.previewDigest,
            confirm: true,
          },
        )
      ).status(),
    ).toBe(201);
    record('schedule published and attendance record created');
  });

  let generationId = '';
  await test.step('timesheet: generate, employee submits, manager approves, administrator locks', async () => {
    const generated = await post(
      adminPage,
      adminBearer,
      '/timesheets/generations',
      `acc-gen-${stamp}`,
      {
        siteId,
        weekStart: monday,
        confirm: true,
      },
    );
    expect(generated.status()).toBe(200);
    generationId = ((await generated.json()) as { generation: { generationId: string } }).generation
      .generationId;

    const ana = await browser.newContext({ locale: 'en-US' });
    const anaPage = await ana.newPage();
    const anaBearer = await signIn(anaPage, [anaAddress, employeePassword], 'en', undefined);
    const mine = await get(anaPage, anaBearer, '/me/timesheets');
    expect(mine.status()).toBe(200);
    const sheet =
      ((await mine.json()) as { items: { timesheetId: string }[] }).items[0]?.timesheetId ?? '';
    expect(sheet).toMatch(/^[0-9a-f-]{36}$/u);
    const detail = await get(anaPage, anaBearer, `/me/timesheets/${sheet}`);
    const own = (await detail.json()) as { revision: number; generationVersion: number };
    expect(
      (
        await post(anaPage, anaBearer, `/me/timesheets/${sheet}/submissions`, `acc-sub-${stamp}`, {
          revision: own.revision,
          generationVersion: own.generationVersion,
          confirm: true,
        })
      ).status(),
    ).toBe(200);
    await ana.close();

    const mia = await browser.newContext({ locale: 'en-US' });
    const miaPage = await mia.newPage();
    const miaBearer = await signIn(miaPage, [miaAddress, employeePassword], 'en', undefined);
    const review = await get(miaPage, miaBearer, `/me/time-approvals/${sheet}`);
    expect(review.status()).toBe(200);
    const submission = (await review.json()) as {
      revision: number;
      generationVersion: number;
      cycle: number;
      submissionActionId: string;
    };
    expect(
      (
        await post(miaPage, miaBearer, `/me/time-approvals/${sheet}/approval`, `acc-apv-${stamp}`, {
          revision: submission.revision,
          generationVersion: submission.generationVersion,
          cycle: submission.cycle,
          submissionActionId: submission.submissionActionId,
          confirm: true,
        })
      ).status(),
    ).toBe(200);
    await mia.close();

    const scope = { legalEntityId: entityId, siteId, weekStart: monday };
    const lock = await post(adminPage, adminBearer, '/timesheet-periods/lock-preview', null, scope);
    expect(lock.status()).toBe(200);
    const locked = (await lock.json()) as {
      ready: boolean;
      generationVersion: number;
      lock: { version: number };
      previewDigest: string;
    };
    expect(locked.ready, 'the week is ready to lock').toBe(true);
    expect(
      (
        await post(adminPage, adminBearer, '/timesheet-periods/locks', `acc-lock-${stamp}`, {
          ...scope,
          generationVersion: locked.generationVersion,
          lockVersion: locked.lock.version,
          previewDigest: locked.previewDigest,
          confirm: true,
        })
      ).status(),
    ).toBe(200);
    expect(generationId).toMatch(/^[0-9a-f-]{36}$/u);
    record('timesheet submitted, approved and locked');
  });

  await test.step('payroll preparation (English): preview, confirm, generate', async () => {
    const context = await browser.newContext({ locale: 'en-US' });
    const page = await context.newPage();
    await signIn(page, [adminAddress, adminPassword], 'en', adminSecret);
    await primaryNav(page).getByRole('link', { name: 'Payroll preparation', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Payroll preparation');
    const form = page.getByTestId('payroll-prep-form');
    await form.getByRole('combobox', { name: 'Legal entity' }).selectOption(entityId);
    await form.getByLabel('First day').fill(monday);
    await form.getByLabel('Last day').fill(day);
    await form.getByRole('button', { name: 'Preview', exact: true }).click();
    const preview = page.getByTestId('payroll-prep-preview');
    await expect(preview).toHaveAttribute('data-ready', 'true');
    await preview
      .getByLabel('I reviewed this preview and want to generate the payroll inputs.')
      .check();
    await preview.getByRole('button', { name: 'Generate payroll inputs', exact: true }).click();
    await expect(page.getByTestId('payroll-prep-announcer')).toHaveText(
      'Payroll inputs generated.',
    );
    await expect(page.getByTestId('payroll-prep-detail-digest')).toHaveText(/^[0-9a-f]{64}$/u);
    await expectAccessible(page);
    await context.close();
    record('payroll inputs generated for the synthetic week');
  });

  await test.step('cleanup through product paths: employees separated, sign-in removal awaited', async () => {
    await separate(adminPage, adminBearer, anaId, day, 'ana');
    await separate(adminPage, adminBearer, miaId, day, 'mia');
    record('employees separated, access COMPLETED (sign-in removed)');
    record(
      'first administrator identity and organization remain: operator disables the identity in the realm console',
      'OPERATOR',
    );
  });

  await adminContext.close();
  await platformContext.close();
  const out =
    process.env.PROD_ACCEPTANCE_EVIDENCE ?? 'test-results/production-tenant-evidence.json';
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(
    out,
    `${JSON.stringify({ organization: ORG_NAME, organizationId, stamp, steps: evidence }, null, 2)}\n`,
  );
});
