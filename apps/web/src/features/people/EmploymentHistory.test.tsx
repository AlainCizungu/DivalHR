import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Route, Routes } from 'react-router';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequireRole } from '../hierarchy/RequireRole';
import { EmployeeDirectoryPage } from './EmployeeDirectoryPage';
import { EmployeeProfilePage } from './EmployeeProfilePage';

const EMPLOYEE = '11111111-1111-4111-8111-111111111111';
const MANAGER = '22222222-2222-4222-8222-222222222222';
const HIRE = '33333333-3333-4333-8333-333333333333';
const SCHEDULED = '44444444-4444-4444-8444-444444444444';
const ROW_PLACEMENT = '55555555-5555-4555-8555-555555555555';
const ROW_CONTRACT = '66666666-6666-4666-8666-666666666666';
const LE = '77777777-7777-4777-8777-777777777777';
const SITE = '88888888-8888-4888-8888-888888888888';
const DEPT = '99999999-9999-4999-8999-999999999999';
const DIGEST = 'd'.repeat(64);
const TODAY = '2026-10-03';

const unit = (id: string, code: string, name: string) => ({ id, code, name });
const placementValue = {
  legalEntity: unit(LE, 'KIN', 'Société Kinshasa'),
  site: unit(SITE, 'LSH', 'Site Lubumbashi'),
  department: unit(DEPT, 'FIN', 'Finances'),
  costCenter: null,
  team: null,
};
const row = (overrides: Record<string, unknown>) => ({
  id: ROW_PLACEMENT,
  kind: 'PLACEMENT',
  effectiveFrom: '2026-03-01',
  effectiveTo: null,
  status: 'CURRENT',
  changeId: HIRE,
  supersededAt: null,
  placement: placementValue,
  manager: null,
  contractClassification: null,
  compensationBasis: null,
  ...overrides,
});
const contractRow = row({
  id: ROW_CONTRACT,
  kind: 'CONTRACT',
  effectiveFrom: '2026-09-01',
  placement: null,
  contractClassification: 'PERMANENT',
});
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
  current: { placement: row({}), manager: null, contract: contractRow, compensation: null },
};
const change = (overrides: Record<string, unknown>) => ({
  id: HIRE,
  type: 'HIRE',
  effectiveFrom: '2026-03-01',
  kinds: ['PLACEMENT'],
  reasonCode: null,
  timing: null,
  state: 'ACTIVE',
  cancelsChangeId: null,
  recordedAt: '2026-03-01T08:00:00Z',
  ...overrides,
});
const scheduled = change({
  id: SCHEDULED,
  type: 'CHANGE',
  effectiveFrom: '2026-11-01',
  kinds: ['CONTRACT'],
  timing: 'SCHEDULED',
});
const period = (from: string, to: string | null, contract: string) => ({
  assignmentId: null,
  effectiveFrom: from,
  effectiveTo: to,
  placement: null,
  manager: null,
  contractClassification: contract,
  compensationBasis: null,
});
const changePreview = (overrides: Record<string, unknown> = {}) => ({
  expectedVersion: 2,
  timing: 'SCHEDULED',
  requiresReason: false,
  requiresAcknowledgement: false,
  kinds: [
    {
      kind: 'CONTRACT',
      before: [{ ...period('2026-09-01', null, 'PERMANENT'), assignmentId: ROW_CONTRACT }],
      after: [
        period('2026-09-01', '2026-10-31', 'PERMANENT'),
        period('2026-11-01', null, 'FIXED_TERM'),
      ],
    },
  ],
  warnings: [],
  previewDigest: DIGEST,
  ...overrides,
});
const summaries = [
  {
    id: EMPLOYEE,
    employeeNumber: 'E-001',
    givenNames: 'Élodie',
    familyName: 'N’Kanza',
    employmentStatus: 'CURRENT',
  },
  {
    id: MANAGER,
    employeeNumber: 'E-002',
    givenNames: 'Jean-Pierre',
    familyName: 'Mukendi',
    employmentStatus: 'NOT_STARTED',
  },
];

type Reply = { status: number; body?: unknown };
type Route = (request: Request, url: URL) => Reply | undefined;

function stubApi(route: Route = () => undefined) {
  const requests: { method: string; url: URL; body: string; headers: Headers }[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const url = new URL(request.url);
      const body = request.method === 'GET' ? '' : await request.clone().text();
      requests.push({ method: request.method, url, body, headers: request.headers });
      const path = url.pathname.replace(/^.*\/api\/v1/u, '');
      const reply: Reply =
        route(request, url) ??
        (path === '/employees'
          ? { status: 200, body: { items: summaries, nextCursor: null } }
          : path === '/employees/search'
            ? { status: 200, body: { items: summaries.slice(1), nextCursor: null } }
            : path === `/employees/${EMPLOYEE}`
              ? { status: 200, body: profile }
              : path === `/employees/${EMPLOYEE}/timeline`
                ? { status: 200, body: { items: [row({}), contractRow], nextCursor: null } }
                : path === `/employees/${EMPLOYEE}/employment-changes` && request.method === 'GET'
                  ? { status: 200, body: { items: [scheduled, change({})], nextCursor: null } }
                  : path.endsWith('/employment-changes/preview')
                    ? { status: 200, body: changePreview() }
                    : path === `/employees/${EMPLOYEE}/employment-changes`
                      ? {
                          status: 201,
                          body: {
                            change: change({
                              id: 'new',
                              type: 'CHANGE',
                              effectiveFrom: '2026-11-15',
                              kinds: ['CONTRACT'],
                              timing: 'SCHEDULED',
                            }),
                            employmentVersion: 3,
                          },
                        }
                      : path.endsWith('/cancel/preview')
                        ? {
                            status: 200,
                            body: {
                              expectedVersion: 2,
                              kinds: changePreview().kinds,
                              cancellationDigest: DIGEST,
                            },
                          }
                        : path.endsWith('/cancel')
                          ? {
                              status: 200,
                              body: {
                                change: change({
                                  id: 'cancel',
                                  type: 'CANCELLATION',
                                  effectiveFrom: '2026-11-01',
                                  kinds: ['CONTRACT'],
                                  timing: 'SCHEDULED',
                                  cancelsChangeId: SCHEDULED,
                                }),
                                employmentVersion: 3,
                              },
                            }
                          : path === '/legal-entities'
                            ? {
                                status: 200,
                                body: { data: [{ ...unit(LE, 'KIN', 'Société Kinshasa') }] },
                              }
                            : path === '/sites'
                              ? {
                                  status: 200,
                                  body: { data: [{ ...unit(SITE, 'LSH', 'Site Lubumbashi') }] },
                                }
                              : path === '/departments'
                                ? {
                                    status: 200,
                                    body: { data: [{ ...unit(DEPT, 'FIN', 'Finances') }] },
                                  }
                                : path === '/cost-centers' || path === '/teams'
                                  ? { status: 200, body: { data: [] } }
                                  : { status: 404, body: problem('NOT_FOUND', 404) });
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

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-history-1234',
});

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

function guarded(page: React.ReactNode) {
  return (
    <RequireRole
      requiredRole="tenant-admin"
      deniedKey="employees.unauthorized"
      signInKey="employees.signInRequired"
    >
      {page}
    </RequireRole>
  );
}

async function renderProfile(locale: 'fr' | 'en') {
  return renderWithSession(
    <Routes>
      <Route path="/admin/people/:employeeId" element={guarded(<EmployeeProfilePage />)} />
    </Routes>,
    sessionWithRoles(['tenant-admin']),
    locale,
    `/admin/people/${EMPLOYEE}`,
  );
}

let setItem: { mock: { calls: unknown[][] } };
beforeEach(() => {
  setItem = vi.spyOn(Storage.prototype, 'setItem');
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['fr', 'en'] as const)('employment history (%s)', (locale) => {
  const c = resources[locale].common;
  const e = c.employees;

  it('lists and searches employees without putting the query in the URL', async () => {
    const requests = stubApi();
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      guarded(<EmployeeDirectoryPage />),
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    const table = await screen.findByTestId('directory-table');
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    expect(within(table).getByText(e.employmentStatus.NOT_STARTED)).toBeInTheDocument();
    expect(within(table).getByRole('link', { name: 'Élodie N’Kanza' })).toHaveAttribute(
      'href',
      `/admin/people/${EMPLOYEE}`,
    );
    await expectAccessible(container);

    await user.type(screen.getByLabelText(e.directory.query), 'é');
    await user.click(screen.getByRole('button', { name: e.directory.search }));
    expect(await screen.findByText(e.directory.queryLength)).toBeInTheDocument();

    await user.clear(screen.getByLabelText(e.directory.query));
    await user.type(screen.getByLabelText(e.directory.query), '  muk ');
    await user.click(screen.getByRole('button', { name: e.directory.search }));
    await waitFor(() => {
      expect(within(screen.getByTestId('directory-table')).getAllByRole('row')).toHaveLength(2);
    });
    const search = requests.find((r) => r.url.pathname.endsWith('/employees/search'));
    expect(search?.method).toBe('POST');
    expect(JSON.parse(search?.body ?? '{}')).toEqual({ query: 'muk', cursor: null, limit: 25 });
    for (const request of requests) expect(request.url.search).not.toContain('muk');
    expect(screen.getByRole('heading', { name: e.directory.resultsTitle })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: e.directory.showAll }));
    expect(await screen.findByRole('heading', { name: e.directory.allTitle })).toBeInTheDocument();
    // Only the language preference is ever stored; no query, cursor or employee data.
    expect(setItem.mock.calls.filter(([key]) => key !== 'divalhr.locale')).toEqual([]);
  });

  it('refuses employees who are not administrators', async () => {
    stubApi();
    await renderWithSession(
      guarded(<EmployeeDirectoryPage />),
      sessionWithRoles(['employee']),
      locale,
    );
    expect(await screen.findByText(e.unauthorized)).toBeInTheDocument();
  });

  it('shows the profile, the history and the recorded changes', async () => {
    stubApi();
    const { container } = await renderProfile(locale);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Élodie N’Kanza' }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('current-PLACEMENT')).toHaveTextContent('Finances (FIN)');
    expect(screen.getByTestId('current-MANAGER')).toHaveTextContent(e.profile.unset.MANAGER);
    expect(screen.getByTestId('current-CONTRACT')).toHaveTextContent(e.contract.PERMANENT);
    const timeline = await screen.findByTestId('timeline-table');
    expect(within(timeline).getAllByRole('row')).toHaveLength(3);
    const changes = await screen.findByTestId('changes-table');
    // Only the active scheduled business change can be cancelled; the hire cannot.
    expect(within(changes).getAllByRole('button', { name: /./u })).toHaveLength(1);
    await expectAccessible(container);
  });

  it('previews a scheduled change and records it as previewed', async () => {
    const requests = stubApi();
    const user = userEvent.setup();
    await renderProfile(locale);
    const form = await screen.findByTestId('change-form');
    await user.type(within(form).getByLabelText(e.form.effectiveFrom), '2026-11-01');
    await user.click(within(form).getByLabelText(e.kinds.CONTRACT));
    await user.selectOptions(
      within(form).getByRole('combobox', { name: e.kinds.CONTRACT }),
      'FIXED_TERM',
    );
    await user.click(within(form).getByRole('button', { name: e.form.preview }));
    const preview = await screen.findByTestId('change-preview');
    expect(within(preview).getByTestId('preview-timing')).toHaveTextContent(e.timing.SCHEDULED);
    expect(within(preview).getByTestId('preview-table')).toHaveTextContent(e.contract.FIXED_TERM);
    await user.click(within(preview).getByRole('button', { name: e.form.confirm }));
    expect(await screen.findByTestId('announcer')).toHaveTextContent(/\S/u);
    const created = requests.find(
      (r) =>
        r.method === 'POST' && r.url.pathname.endsWith(`/employees/${EMPLOYEE}/employment-changes`),
    );
    expect(created?.headers.get('Idempotency-Key')).toMatch(/^web-/u);
    expect(JSON.parse(created?.body ?? '{}')).toMatchObject({
      type: 'CHANGE',
      effectiveFrom: '2026-11-01',
      contractClassification: 'FIXED_TERM',
      placement: null,
      manager: null,
      expectedVersion: 2,
      previewDigest: DIGEST,
      acknowledgeRetroactive: false,
    });
    for (const request of requests) expect(request.url.search).not.toContain('FIXED_TERM');
  });

  it('requires a reason and an acknowledgement for a retroactive change', async () => {
    const requests = stubApi((_request, url) =>
      url.pathname.endsWith('/employment-changes/preview')
        ? {
            status: 200,
            body: changePreview({
              timing: 'RETROACTIVE',
              requiresReason: true,
              requiresAcknowledgement: true,
            }),
          }
        : undefined,
    );
    const user = userEvent.setup();
    await renderProfile(locale);
    const form = await screen.findByTestId('change-form');
    await user.type(within(form).getByLabelText(e.form.effectiveFrom), '2026-09-20');
    await user.click(within(form).getByLabelText(e.kinds.CONTRACT));
    await user.selectOptions(
      within(form).getByRole('combobox', { name: e.kinds.CONTRACT }),
      'DAILY',
    );
    await user.click(within(form).getByRole('button', { name: e.form.preview }));
    expect(await screen.findByTestId('form-problems')).toHaveTextContent(e.form.problems.reason);
    await user.selectOptions(
      within(form).getByRole('combobox', { name: e.form.reasonRequired }),
      'LATE_NOTIFICATION',
    );
    await user.click(within(form).getByRole('button', { name: e.form.preview }));
    const preview = await screen.findByTestId('change-preview');
    expect(preview).toHaveTextContent(e.timing.RETROACTIVE);
    await user.click(within(preview).getByRole('button', { name: e.form.confirm }));
    expect(await screen.findByTestId('form-problems')).toHaveTextContent(
      e.form.problems.acknowledge,
    );
    await user.click(within(preview).getByLabelText(e.preview.acknowledge));
    await user.click(within(preview).getByRole('button', { name: e.form.confirm }));
    await waitFor(() => {
      expect(
        requests.some((r) => r.method === 'POST' && r.url.pathname.endsWith('/employment-changes')),
      ).toBe(true);
    });
    const created = requests.find(
      (r) => r.method === 'POST' && r.url.pathname.endsWith('/employment-changes'),
    );
    expect(JSON.parse(created?.body ?? '{}')).toMatchObject({
      reasonCode: 'LATE_NOTIFICATION',
      acknowledgeRetroactive: true,
    });
  });

  it('shows a stale preview and an invalid manager without echoing values', async () => {
    let previews = 0;
    stubApi((request, url) => {
      if (url.pathname.endsWith('/employment-changes/preview')) {
        previews += 1;
        return previews === 1
          ? { status: 422, body: problem('MANAGER_INVALID', 422, { reason: 'CYCLE' }) }
          : { status: 200, body: changePreview() };
      }
      if (
        url.pathname.endsWith(`/employees/${EMPLOYEE}/employment-changes`) &&
        request.method === 'POST'
      ) {
        return { status: 409, body: problem('EMPLOYMENT_PREVIEW_CHANGED', 409) };
      }
      return undefined;
    });
    const user = userEvent.setup();
    await renderProfile(locale);
    const form = await screen.findByTestId('change-form');
    await user.type(within(form).getByLabelText(e.form.effectiveFrom), '2026-11-01');
    await user.click(within(form).getByLabelText(e.kinds.MANAGER));
    await user.type(within(form).getByLabelText(e.manager.find), 'muk');
    await user.click(within(form).getByRole('button', { name: e.manager.search }));
    await user.click(await within(form).findByRole('radio'));
    expect(within(form).getByTestId('manager-selected')).toHaveTextContent('Jean-Pierre Mukendi');
    await user.click(within(form).getByRole('button', { name: e.form.preview }));
    const refused = await screen.findByTestId('change-error');
    expect(refused).toHaveTextContent(c.errors.MANAGER_INVALID);
    expect(refused).toHaveTextContent(e.problems.manager.CYCLE);
    expect(refused).toHaveTextContent('corr-history-1234');

    await user.click(within(form).getByRole('button', { name: e.form.preview }));
    await user.click(
      within(await screen.findByTestId('change-preview')).getByRole('button', {
        name: e.form.confirm,
      }),
    );
    expect(await screen.findByTestId('change-error')).toHaveTextContent(
      c.errors.EMPLOYMENT_PREVIEW_CHANGED,
    );
    expect(screen.queryByTestId('change-preview')).not.toBeInTheDocument();
  });

  it('records a placement chosen from the hierarchy', async () => {
    const requests = stubApi();
    const user = userEvent.setup();
    await renderProfile(locale);
    const form = await screen.findByTestId('change-form');
    await user.type(within(form).getByLabelText(e.form.effectiveFrom), '2026-11-01');
    await user.click(within(form).getByLabelText(e.kinds.PLACEMENT));
    const le = within(form).getByLabelText(e.placement.fields.legalEntityId);
    await waitFor(() => {
      expect(within(le).getAllByRole('option')).toHaveLength(2);
    });
    await user.selectOptions(le, LE);
    const site = within(form).getByLabelText(e.placement.fields.siteId);
    await waitFor(() => {
      expect(within(site).getAllByRole('option')).toHaveLength(2);
    });
    await user.selectOptions(site, SITE);
    const unitSelect = within(form).getByLabelText(e.placement.unit);
    await waitFor(() => {
      expect(within(unitSelect).getAllByRole('option')).toHaveLength(2);
    });
    await user.selectOptions(unitSelect, `department:${DEPT}`);
    await user.click(within(form).getByRole('button', { name: e.form.preview }));
    await screen.findByTestId('change-preview');
    const previewed = requests.find((r) => r.url.pathname.endsWith('/employment-changes/preview'));
    expect(JSON.parse(previewed?.body ?? '{}')).toMatchObject({
      placement: {
        legalEntityId: LE,
        siteId: SITE,
        departmentId: DEPT,
        costCenterId: null,
        teamId: null,
      },
    });
  });

  it('corrects one row for its own dates', async () => {
    const requests = stubApi();
    const user = userEvent.setup();
    await renderProfile(locale);
    const timeline = await screen.findByTestId('timeline-table');
    const buttons = within(timeline).getAllByRole('button', {
      name: new RegExp(e.kinds.CONTRACT, 'u'),
    });
    await user.click(buttons[0] as HTMLElement);
    const form = await screen.findByTestId('change-form');
    expect(within(form).getByRole('heading', { level: 2 })).toHaveTextContent(e.form.correctTitle);
    expect(within(form).getByRole('heading', { level: 2 })).toHaveFocus();
    expect(within(form).getByLabelText(e.form.effectiveFrom)).toHaveValue('2026-09-01');
    await user.selectOptions(
      within(form).getByRole('combobox', { name: e.kinds.CONTRACT }),
      'INTERNSHIP',
    );
    await user.selectOptions(
      within(form).getByRole('combobox', { name: e.form.reasonRequired }),
      'DATA_ENTRY_ERROR',
    );
    await user.click(within(form).getByRole('button', { name: e.form.preview }));
    await screen.findByTestId('change-preview');
    const previewed = requests.find((r) => r.url.pathname.endsWith('/employment-changes/preview'));
    expect(JSON.parse(previewed?.body ?? '{}')).toMatchObject({
      type: 'CORRECTION',
      effectiveFrom: '2026-09-01',
      correctsAssignmentId: ROW_CONTRACT,
      contractClassification: 'INTERNSHIP',
      reasonCode: 'DATA_ENTRY_ERROR',
    });
  });

  it('previews and confirms the cancellation of a scheduled change', async () => {
    const requests = stubApi();
    const user = userEvent.setup();
    const { container } = await renderProfile(locale);
    const changes = await screen.findByTestId('changes-table');
    await user.click(within(changes).getByRole('button', { name: /./u }));
    const panel = await screen.findByTestId('cancel-panel');
    expect(await within(panel).findByTestId('cancel-preview')).toHaveTextContent(
      e.preview.restored,
    );
    await expectAccessible(container);
    await user.click(within(panel).getByRole('button', { name: e.changes.confirmCancel }));
    await waitFor(() => {
      expect(screen.queryByTestId('cancel-panel')).not.toBeInTheDocument();
    });
    const cancelled = requests.find((r) => r.url.pathname.endsWith(`/${SCHEDULED}/cancel`));
    expect(JSON.parse(cancelled?.body ?? '{}')).toEqual({
      expectedVersion: 2,
      cancellationDigest: DIGEST,
    });
    expect(cancelled?.headers.get('Idempotency-Key')).toMatch(/^web-/u);
  });
});
