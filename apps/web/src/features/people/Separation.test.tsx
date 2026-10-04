import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Route, Routes } from 'react-router';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequireRole } from '../hierarchy/RequireRole';
import { EmployeeProfilePage } from './EmployeeProfilePage';

const EMPLOYEE = '11111111-1111-4111-8111-111111111111';
const REPORT = '22222222-2222-4222-8222-222222222222';
const MEMBERSHIP = '33333333-3333-4333-8333-333333333333';
const LINK = '44444444-4444-4444-8444-444444444444';
const SEPARATION = '55555555-5555-4555-8555-555555555555';
const TASK_ASSETS = '66666666-6666-4666-8666-666666666666';
const TASK_DOCUMENTS = '77777777-7777-4777-8777-777777777777';
const DIGEST = 'd'.repeat(64);
const TODAY = '2026-10-03';
const ADDRESS = 'élodie.nkanza@exemple.cd';

const profile = {
  id: EMPLOYEE,
  employeeNumber: 'E-001',
  givenNames: 'Élodie',
  familyName: 'N’Kanza',
  businessDate: TODAY,
  employment: {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    startDate: '2026-03-01',
    endDate: null,
    status: 'CURRENT',
    version: 2,
  },
  current: { placement: null, manager: null, contract: null, compensation: null },
};

const preview = (overrides: Record<string, unknown> = {}) => ({
  employmentId: profile.employment.id,
  expectedVersion: 2,
  businessDate: TODAY,
  lastDay: '2026-10-31',
  timing: 'SCHEDULED',
  accessTiming: 'END_OF_LAST_DAY',
  kinds: [],
  blockers: [],
  reports: [
    {
      employee: {
        id: REPORT,
        employeeNumber: 'E-002',
        givenNames: 'Jean-Pierre',
        familyName: 'Mukendi',
      },
      intervals: [
        { effectiveFrom: '2026-11-01', effectiveTo: '2026-12-31' },
        { effectiveFrom: '2027-02-01', effectiveTo: null },
      ],
    },
  ],
  reportPlanRequired: true,
  access: { status: 'LINKED', accessEndsAt: '2026-10-31T23:00:00Z' },
  checklist: [
    { code: 'RETURN_ASSIGNED_ASSETS', dueDate: '2026-10-31' },
    { code: 'COLLECT_OR_ARCHIVE_DOCUMENTS', dueDate: '2026-11-14' },
  ],
  requiredAcknowledgements: [],
  previewDigest: DIGEST,
  ...overrides,
});

const task = (id: string, code: string, overrides: Record<string, unknown> = {}) => ({
  id,
  code,
  status: 'OPEN',
  dueDate: '2026-10-31',
  updatedAt: '2026-10-03T08:00:00Z',
  version: 0,
  ...overrides,
});

const separation = (overrides: Record<string, unknown> = {}) => ({
  id: SEPARATION,
  employmentId: profile.employment.id,
  lastDay: '2026-10-31',
  reasonCode: 'RESIGNATION',
  accessTiming: 'END_OF_LAST_DAY',
  reportAction: 'CLEAR',
  reportCount: 1,
  intervalCount: 2,
  state: 'SCHEDULED',
  effectiveAt: '2026-10-31T23:00:00Z',
  recordedAt: '2026-10-03T08:00:00Z',
  cancelledAt: null,
  cancellable: true,
  access: 'SCHEDULED',
  accessEndsAt: '2026-10-31T23:00:00Z',
  tasks: [
    task(TASK_ASSETS, 'RETURN_ASSIGNED_ASSETS'),
    task(TASK_DOCUMENTS, 'COLLECT_OR_ARCHIVE_DOCUMENTS', { dueDate: '2026-11-14' }),
  ],
  version: 0,
  ...overrides,
});

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-separation-1234',
});

type Reply = { status: number; body?: unknown };
type Handler = (path: string, request: Request) => Reply | undefined;
type Recorded = { method: string; url: URL; body: string; headers: Headers };

function stubApi(route: Handler = () => undefined) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const url = new URL(request.url);
      const body = request.method === 'GET' ? '' : await request.clone().text();
      requests.push({ method: request.method, url, body, headers: request.headers });
      const path = url.pathname.replace(/^.*\/api\/v1/u, '');
      const fallback = (): Reply => {
        if (path === `/employees/${EMPLOYEE}`) return { status: 200, body: profile };
        if (path.endsWith('/timeline') || path.endsWith('/employment-changes')) {
          return { status: 200, body: { items: [], nextCursor: null } };
        }
        if (path === `/employees/${EMPLOYEE}/separations` && request.method === 'GET') {
          return { status: 200, body: { items: [] } };
        }
        if (path === `/employees/${EMPLOYEE}/access-link` && request.method === 'GET') {
          return { status: 200, body: { state: 'NOT_LINKED', link: null } };
        }
        if (path === '/employees/search') {
          return {
            status: 200,
            body: {
              items: [
                {
                  id: REPORT,
                  employeeNumber: 'E-002',
                  givenNames: 'Jean-Pierre',
                  familyName: 'Mukendi',
                  employmentStatus: 'CURRENT',
                },
              ],
              nextCursor: null,
            },
          };
        }
        return { status: 404, body: problem('NOT_FOUND', 404) };
      };
      const reply = route(path, request) ?? fallback();
      return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status,
        headers: {
          'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
        },
      });
    }),
  );
  return requests;
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

async function renderProfile(locale: 'fr' | 'en') {
  const rendered = renderWithSession(
    <Routes>
      <Route
        path="/admin/people/:employeeId"
        element={
          <RequireRole
            requiredRole="tenant-admin"
            deniedKey="employees.unauthorized"
            signInKey="employees.signInRequired"
          >
            <EmployeeProfilePage />
          </RequireRole>
        }
      />
    </Routes>,
    sessionWithRoles(['tenant-admin']),
    locale,
    `/admin/people/${EMPLOYEE}`,
  );
  await screen.findByTestId('separations');
  return rendered;
}

const posts = (requests: Recorded[], suffix: string) =>
  requests.filter((r) => r.method === 'POST' && r.url.pathname.endsWith(suffix));

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['fr', 'en'] as const)('MVP-022 separation and access (%s)', (locale) => {
  const c = resources[locale].common;
  const s = c.separation;
  const a = c.accessLink;

  it('links the employee to the membership of an exact address, sent only in a POST body', async () => {
    const requests = stubApi((path, request) => {
      if (path.endsWith('/access-link/lookup')) {
        return {
          status: 200,
          body: {
            membershipId: MEMBERSHIP,
            role: 'employee',
            linkable: true,
            notLinkableReason: null,
          },
        };
      }
      if (path === `/employees/${EMPLOYEE}/access-link` && request.method === 'POST') {
        return {
          status: 201,
          body: {
            state: 'ACTIVE',
            link: {
              id: LINK,
              membershipId: MEMBERSHIP,
              role: 'employee',
              linkedAt: '2026-10-03T08:00:00Z',
              version: 0,
            },
          },
        };
      }
      return undefined;
    });
    const user = userEvent.setup();
    const { container } = await renderProfile(locale);
    const section = await screen.findByTestId('access-link');
    expect(await within(section).findByTestId('access-link-state')).toHaveTextContent(
      a.state.NOT_LINKED,
    );
    await user.type(within(section).getByLabelText(a.email), ADDRESS);
    await user.click(within(section).getByRole('button', { name: a.lookup }));
    const candidate = await within(section).findByTestId('access-candidate');
    expect(candidate).toHaveTextContent(c.users.form.roles.employee);
    await user.click(within(candidate).getByRole('button', { name: a.link }));
    await waitFor(() => {
      expect(within(section).getByTestId('access-link-state')).toHaveAttribute(
        'data-state',
        'ACTIVE',
      );
    });
    expect(screen.getByTestId('announcer')).toHaveTextContent(a.announce.linked);

    const [lookup] = posts(requests, '/access-link/lookup');
    expect(JSON.parse(lookup?.body ?? '{}')).toStrictEqual({ email: ADDRESS });
    const [created] = posts(requests, '/access-link');
    expect(JSON.parse(created?.body ?? '{}')).toStrictEqual({ membershipId: MEMBERSHIP });
    expect(created?.headers.get('Idempotency-Key')).toMatch(/^web-[0-9a-f-]{36}$/u);
    // The address never reaches a URL, history or storage.
    for (const request of requests) {
      expect(decodeURIComponent(request.url.href)).not.toContain(ADDRESS);
    }
    expect(window.location.href).not.toContain('exemple');
    for (const storage of [window.localStorage, window.sessionStorage]) {
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index) ?? '';
        expect(`${key}=${storage.getItem(key) ?? ''}`).not.toContain(ADDRESS);
      }
    }
    await expectAccessible(container);
  });

  it('explains why a found access cannot be linked', async () => {
    stubApi((path) =>
      path.endsWith('/access-link/lookup')
        ? {
            status: 200,
            body: {
              membershipId: MEMBERSHIP,
              role: 'employee',
              linkable: false,
              notLinkableReason: 'ACCESS_REVOKED',
            },
          }
        : undefined,
    );
    const user = userEvent.setup();
    await renderProfile(locale);
    const section = await screen.findByTestId('access-link');
    await user.type(await within(section).findByLabelText(a.email), ADDRESS);
    await user.click(within(section).getByRole('button', { name: a.lookup }));
    const candidate = await within(section).findByTestId('access-candidate');
    expect(candidate).toHaveTextContent(a.notLinkable.ACCESS_REVOKED);
    expect(within(candidate).queryByRole('button')).toBeNull();
  });

  it('offers only the access timings the last day allows (A22-1)', async () => {
    stubApi();
    const user = userEvent.setup();
    await renderProfile(locale);
    const form = await screen.findByTestId('separation-form');
    const date = within(form).getByLabelText(s.form.lastDay);
    const timings = () =>
      within(form)
        .getAllByRole('radio')
        .map((radio) => radio.getAttribute('id') ?? '')
        .filter((id) => id.includes('-timing-'))
        .map((id) => id.replace(/^.*-timing-/u, ''));

    await user.type(date, '2026-10-31');
    expect(timings()).toEqual(['END_OF_LAST_DAY']);
    expect(within(form).getByText(s.form.timingFuture)).toBeInTheDocument();
    await user.clear(date);
    await user.type(date, TODAY);
    expect(timings()).toEqual(['END_OF_LAST_DAY', 'IMMEDIATELY']);
    await user.clear(date);
    await user.type(date, '2026-09-30');
    expect(timings()).toEqual(['IMMEDIATELY']);
    expect(within(form).getByText(s.form.timingPast)).toBeInTheDocument();
  });

  it('lists what is missing before previewing', async () => {
    const requests = stubApi();
    const user = userEvent.setup();
    await renderProfile(locale);
    const form = await screen.findByTestId('separation-form');
    await user.click(within(form).getByRole('button', { name: s.form.preview }));
    const problems = await within(form).findByTestId('separation-problems');
    expect(problems).toHaveTextContent(s.form.problems.lastDay);
    expect(problems).toHaveTextContent(s.form.problems.reason);
    expect(problems).toHaveTextContent(s.form.problems.timing);
    expect(problems).toHaveFocus();
    expect(posts(requests, '/separations/preview')).toHaveLength(0);
  });

  it('previews every affected direct-report interval and requires a plan, then records', async () => {
    const requests: Recorded[] = [];
    // Reply per request body: without a plan the plan is required; with CLEAR an acknowledgement.
    vi.stubGlobal(
      'fetch',
      vi.fn(async (request: Request) => {
        const url = new URL(request.url);
        const text = request.method === 'GET' ? '' : await request.clone().text();
        requests.push({ method: request.method, url, body: text, headers: request.headers });
        const path = url.pathname.replace(/^.*\/api\/v1/u, '');
        const json = (status: number, body: unknown) =>
          new Response(JSON.stringify(body), {
            status,
            headers: { 'Content-Type': 'application/json' },
          });
        if (path === `/employees/${EMPLOYEE}`) return json(200, profile);
        if (path.endsWith('/separations') && request.method === 'GET') {
          return json(200, { items: [] });
        }
        if (path.endsWith('/access-link')) return json(200, { state: 'ACTIVE', link: null });
        if (path.endsWith('/separations/preview')) {
          const command = JSON.parse(text) as { reportPlan: unknown };
          return json(
            200,
            command.reportPlan ? preview({ requiredAcknowledgements: ['RETROACTIVE'] }) : preview(),
          );
        }
        if (path.endsWith('/separations') && request.method === 'POST') {
          return json(201, { separation: separation(), employmentVersion: 3 });
        }
        return json(200, { items: [], nextCursor: null });
      }),
    );
    const user = userEvent.setup();
    const { container } = await renderProfile(locale);
    const form = await screen.findByTestId('separation-form');
    await user.type(within(form).getByLabelText(s.form.lastDay), '2026-10-31');
    await user.selectOptions(within(form).getByLabelText(s.form.reason), 'RESIGNATION');
    await user.click(within(form).getByRole('button', { name: s.form.preview }));

    const shown = await within(form).findByTestId('separation-preview');
    expect(within(shown).getByRole('heading', { level: 4 })).toHaveFocus();
    const reports = within(shown).getByTestId('separation-reports');
    expect(reports).toHaveTextContent('Jean-Pierre');
    expect(within(shown).getByTestId('separation-report-count')).toHaveTextContent(
      locale === 'fr' ? '2 périodes' : '2 periods',
    );
    expect(within(shown).getByTestId('separation-plan-required')).toBeInTheDocument();
    expect(within(shown).getByTestId('separation-access')).toHaveAttribute('data-status', 'LINKED');
    expect(within(shown).getByTestId('separation-checklist')).toHaveTextContent(
      s.tasks.RETURN_ASSIGNED_ASSETS,
    );
    expect(within(shown).getByRole('button', { name: s.form.confirm })).toBeDisabled();
    await expectAccessible(container);

    // Choosing a plan invalidates the preview; previewing again requires the acknowledgement.
    await user.click(within(form).getByLabelText(s.plan.CLEAR));
    expect(within(form).queryByTestId('separation-preview')).toBeNull();
    await user.click(within(form).getByRole('button', { name: s.form.preview }));
    const again = await within(form).findByTestId('separation-preview');
    await user.click(within(again).getByRole('button', { name: s.form.confirm }));
    expect(await within(form).findByTestId('separation-problems')).toHaveTextContent(
      s.form.problems.acknowledge,
    );
    expect(posts(requests, '/separations')).toHaveLength(0);
    await user.click(within(again).getByLabelText(s.acknowledgements.RETROACTIVE));
    await user.click(within(again).getByRole('button', { name: s.form.confirm }));
    await waitFor(() => {
      expect(posts(requests, '/separations')).toHaveLength(1);
    });
    const [commit] = posts(requests, '/separations');
    expect(JSON.parse(commit?.body ?? '{}')).toStrictEqual({
      lastDay: '2026-10-31',
      reasonCode: 'RESIGNATION',
      accessTiming: 'END_OF_LAST_DAY',
      reportPlan: { action: 'CLEAR', managerEmployeeId: null },
      expectedVersion: 2,
      previewDigest: DIGEST,
      acknowledgements: ['RETROACTIVE'],
    });
    expect(commit?.headers.get('Idempotency-Key')).toMatch(/^web-[0-9a-f-]{36}$/u);
    expect(await screen.findByTestId('announcer')).toHaveTextContent(
      locale === 'fr' ? 'Sortie enregistrée' : 'Separation recorded',
    );
  });

  it('shows future changes to cancel first and refuses to confirm', async () => {
    stubApi((path) =>
      path.endsWith('/separations/preview')
        ? {
            status: 200,
            body: preview({
              reports: [],
              reportPlanRequired: false,
              blockers: [
                {
                  kind: 'CONTRACT',
                  effectiveFrom: '2026-11-15',
                  resolution: 'CANCEL_CHANGE',
                  changeId: SEPARATION,
                },
              ],
            }),
          }
        : undefined,
    );
    const user = userEvent.setup();
    await renderProfile(locale);
    const form = await screen.findByTestId('separation-form');
    await user.type(within(form).getByLabelText(s.form.lastDay), '2026-10-31');
    await user.selectOptions(within(form).getByLabelText(s.form.reason), 'RETIREMENT');
    await user.click(within(form).getByRole('button', { name: s.form.preview }));
    const blockers = await within(form).findByTestId('separation-blockers');
    expect(blockers).toHaveTextContent(c.employees.kinds.CONTRACT);
    expect(within(form).getByRole('button', { name: s.form.confirm })).toBeDisabled();
  });

  it('maps refusals to stable messages with allow-listed details only', async () => {
    stubApi((path) =>
      path.endsWith('/separations/preview')
        ? {
            status: 409,
            body: problem('SEPARATION_PROTECTED', 409, { reason: 'SELF', email: ADDRESS }),
          }
        : undefined,
    );
    const user = userEvent.setup();
    await renderProfile(locale);
    const form = await screen.findByTestId('separation-form');
    await user.type(within(form).getByLabelText(s.form.lastDay), TODAY);
    await user.selectOptions(within(form).getByLabelText(s.form.reason), 'DISMISSAL');
    await user.click(within(form).getByLabelText(s.timing.IMMEDIATELY));
    await user.click(within(form).getByRole('button', { name: s.form.preview }));
    const alert = await within(form).findByTestId('separation-error');
    expect(alert).toHaveTextContent(c.errors.SEPARATION_PROTECTED);
    expect(alert).toHaveTextContent(s.problems.protected.SELF);
    expect(alert).not.toHaveTextContent(ADDRESS);
    expect(alert).toHaveFocus();
  });

  it('updates a follow-up task, cancels a scheduled separation and hides the form meanwhile', async () => {
    let cancelled = false;
    const requests = stubApi((path, request) => {
      if (path === `/employees/${EMPLOYEE}/separations` && request.method === 'GET') {
        return {
          status: 200,
          body: {
            items: [
              cancelled
                ? separation({ state: 'CANCELLED', cancellable: false, access: 'CANCELLED' })
                : separation(),
            ],
          },
        };
      }
      if (path.endsWith(`/tasks/${TASK_ASSETS}/status`)) {
        return {
          status: 200,
          body: task(TASK_ASSETS, 'RETURN_ASSIGNED_ASSETS', { status: 'DONE', version: 1 }),
        };
      }
      if (path.endsWith('/cancel/preview')) {
        return {
          status: 200,
          body: {
            expectedVersion: 3,
            kinds: [],
            reportCount: 1,
            intervalCount: 2,
            cancellationDigest: DIGEST,
          },
        };
      }
      if (path.endsWith(`/separations/${SEPARATION}/cancel`)) {
        cancelled = true;
        return {
          status: 200,
          body: {
            separation: separation({ state: 'CANCELLED', cancellable: false }),
            employmentVersion: 4,
          },
        };
      }
      return undefined;
    });
    const user = userEvent.setup();
    const { container } = await renderProfile(locale);
    const item = await screen.findByTestId('separation');
    expect(item).toHaveAttribute('data-state', 'SCHEDULED');
    expect(screen.queryByTestId('separation-form')).toBeNull();
    expect(within(item).getByTestId('separation-access-state')).toHaveAttribute(
      'data-access',
      'SCHEDULED',
    );
    await expectAccessible(container);

    await user.selectOptions(
      within(item).getByLabelText(
        s.checklist.statusOf.replace('{{task}}', s.tasks.RETURN_ASSIGNED_ASSETS),
      ),
      'DONE',
    );
    await waitFor(() => {
      expect(posts(requests, `/tasks/${TASK_ASSETS}/status`)).toHaveLength(1);
    });
    const [update] = posts(requests, `/tasks/${TASK_ASSETS}/status`);
    expect(JSON.parse(update?.body ?? '{}')).toStrictEqual({ status: 'DONE', expectedVersion: 0 });
    expect(update?.headers.get('Idempotency-Key')).toMatch(/^web-[0-9a-f-]{36}$/u);

    await user.click(within(item).getByRole('button', { name: s.cancel }));
    const panel = await screen.findByTestId('separation-cancel-panel');
    expect(panel).toHaveTextContent(locale === 'fr' ? '2 périodes' : '2 periods');
    await user.click(within(panel).getByRole('button', { name: s.confirmCancel }));
    await waitFor(() => {
      expect(screen.getByTestId('separation')).toHaveAttribute('data-state', 'CANCELLED');
    });
    const [cancel] = posts(requests, `/separations/${SEPARATION}/cancel`);
    expect(JSON.parse(cancel?.body ?? '{}')).toStrictEqual({
      expectedVersion: 3,
      cancellationDigest: DIGEST,
    });
    expect(await screen.findByTestId('separation-form')).toBeInTheDocument();
  });

  it('retries a sign-in removal that needs an administrator', async () => {
    const requests = stubApi((path, request) => {
      if (path === `/employees/${EMPLOYEE}/separations` && request.method === 'GET') {
        return {
          status: 200,
          body: {
            items: [
              separation({
                state: 'EFFECTIVE',
                cancellable: false,
                access: 'MANUAL_INTERVENTION',
                accessEndsAt: '2026-10-03T08:00:00Z',
              }),
            ],
          },
        };
      }
      if (path.endsWith('/access-revocation/retry')) {
        return {
          status: 200,
          body: separation({
            state: 'EFFECTIVE',
            cancellable: false,
            access: 'SIGN_OUT_PENDING',
            accessEndsAt: '2026-10-03T08:00:00Z',
          }),
        };
      }
      return undefined;
    });
    const user = userEvent.setup();
    await renderProfile(locale);
    const item = await screen.findByTestId('separation');
    expect(within(item).queryByRole('button', { name: s.cancel })).toBeNull();
    await user.click(within(item).getByRole('button', { name: s.retry }));
    await waitFor(() => {
      expect(within(item).getByTestId('separation-access-state')).toHaveAttribute(
        'data-access',
        'SIGN_OUT_PENDING',
      );
    });
    const [retry] = posts(requests, '/access-revocation/retry');
    expect(retry?.headers.get('Idempotency-Key')).toMatch(/^web-[0-9a-f-]{36}$/u);
    expect(screen.getByTestId('announcer')).toHaveTextContent(s.announce.retried);
  });
});
