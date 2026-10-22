import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { OperationsStatusPage, TeamOperationsPage } from './OperationsStatusPage';
import { statusFailureOf } from './operationalStatus';
import { summaryBodyOf, summaryRouteOf } from './operationalSummary';

const LEGAL = '44444444-0000-4000-8000-000000000004';

const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-sum-1234',
});

type Reply = { status: number; body?: unknown };
type Recorded = { method: string; path: string; body: string };

function stubApi(route: (request: Recorded) => Reply) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body: incoming.method === 'POST' ? await incoming.clone().text() : '',
      };
      requests.push(recorded);
      const reply = route(recorded);
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

function stored(): string {
  const values: string[] = [];
  for (const storage of [window.localStorage, window.sessionStorage]) {
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i);
      if (key) values.push(`${key}=${storage.getItem(key) ?? ''}`);
    }
  }
  return values.join('\n');
}

/** Typographic non-breaking spaces compare equal to plain ones. */
const plain = (value: string | null | undefined): string => (value ?? '').replace(/\u00a0/gu, ' ');
function expectText(element: HTMLElement, expected: string) {
  expect(plain(element.textContent)).toContain(plain(expected));
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

const THRESHOLDS = {
  lateMinutes: 5,
  notCheckedInGraceMinutes: 30,
  leaveOverdueHours: 48,
  timesheetOverdueHours: 72,
  detailLimit: 20,
};

const emptyStatus = {
  asOfInstant: '2099-11-02T09:30:00Z',
  thresholds: THRESHOLDS,
  shifts: { scheduled: 0, expected: 0, notCheckedIn: 0, present: 0, completed: 0, late: 0 },
  coverage: { uncovered: 0 },
  approvals: { leave: { pending: 0, overdue: 0 }, timesheets: { pending: 0, overdue: 0 } },
  details: { notCheckedIn: { items: [], truncated: false }, uncovered: null },
};

/** Server-rendered text in the requested language, as the contract returns it. */
const TEXT = {
  en: {
    headline: 'Attendance needs attention.',
    first: '3 shifts have no check-in after the 30-minute grace period.',
    second: '2 leave requests have waited more than 48 hours.',
    limits: [
      'This briefing describes recorded operational facts only.',
      'It is not a leave, pay, disciplinary or legal conclusion.',
      'Review the linked source records before acting.',
    ],
  },
  fr: {
    headline: 'La présence demande de l’attention.',
    first: '3 quarts n’ont aucun pointage après le délai de grâce de 30 minutes.',
    second: '2 demandes de congé attendent depuis plus de 48 heures.',
    limits: [
      'Ce résumé décrit uniquement des faits opérationnels enregistrés.',
      'Il ne constitue pas une conclusion en matière de congé, de paie, de discipline ou de droit.',
      'Consultez les dossiers sources liés avant d’agir.',
    ],
  },
};

const summary = (language: 'en' | 'fr', overrides: Record<string, unknown> = {}) => ({
  asOfInstant: '2099-11-02T09:30:00Z',
  generatedAt: '2099-11-02T09:30:02Z',
  language,
  generationMode: 'MODEL',
  templateVersion: 'ops-summary-1',
  headline: { code: 'ATTENDANCE_NEEDS_ATTENTION', text: TEXT[language].headline },
  highlights: [
    {
      factType: 'NOT_CHECKED_IN',
      severity: 'MEDIUM',
      routeId: 'ATTENDANCE',
      text: TEXT[language].first,
    },
    {
      factType: 'OVERDUE_LEAVE',
      severity: 'LOW',
      routeId: 'LEAVE_APPROVALS',
      text: TEXT[language].second,
    },
  ],
  sourceSnapshot: {
    thresholds: THRESHOLDS,
    shifts: { scheduled: 12, expected: 2, notCheckedIn: 3, present: 4, completed: 3, late: 2 },
    coverage: null,
    approvals: { leave: { pending: 5, overdue: 2 }, timesheets: { pending: 4, overdue: 1 } },
  },
  limitations: [
    { code: 'RECORDED_FACTS_ONLY', text: TEXT[language].limits[0] },
    { code: 'NO_CONCLUSION', text: TEXT[language].limits[1] },
    { code: 'REVIEW_SOURCES', text: TEXT[language].limits[2] },
  ],
  ...overrides,
});

describe('MVP-101 request rules in the browser', () => {
  it('sends the language and nothing else, and maps only known route identifiers', () => {
    expect(summaryBodyOf('fr')).toEqual({ language: 'fr' });
    expect(summaryRouteOf('admin', 'ATTENDANCE')).toBe('/admin/attendance-review');
    expect(summaryRouteOf('team', 'TIMESHEETS')).toBe('/me/time-approvals');
    expect(summaryRouteOf('admin', 'constructor')).toBeNull();
    expect(summaryRouteOf('team', 'https://evil.example')).toBeNull();
  });

  it('maps the briefing problems to a stable unavailable kind without a parameter', () => {
    const response = (code: number) => new Response(null, { status: code });
    expect(
      statusFailureOf('admin', response(503), problem('OPERATIONAL_SUMMARY_UNAVAILABLE', 503)),
    ).toMatchObject({
      kind: 'unavailable',
      messageKey: 'errors.OPERATIONAL_SUMMARY_UNAVAILABLE',
    });
    expect(
      statusFailureOf('team', response(500), problem('OPERATIONAL_SUMMARY_SOURCE_INVALID', 500)),
    ).toMatchObject({ kind: 'unavailable' });
  });
});

describe.each(['en', 'fr'] as const)('MVP-101 daily briefing (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.operationalSummary;

  it('is idle until asked, then shows the server text, sources and limitations', async () => {
    const requests = stubApi((request) => {
      if (request.method === 'POST') return { status: 200, body: summary(locale) };
      return { status: 200, body: emptyStatus };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TeamOperationsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/team-operations',
    );
    await screen.findByTestId('status-shifts');
    expect(requests.filter((r) => r.method === 'POST')).toEqual([]);
    expect(screen.queryByTestId('operational-summary-result')).toBeNull();

    await user.click(screen.getByRole('button', { name: k.generate }));
    const result = await screen.findByTestId('operational-summary-result');
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]?.path).toBe('/me/team-operations/status-summary');
    expect(JSON.parse(posts[0]?.body ?? '')).toEqual({ language: locale });

    expect(result).toHaveAttribute('lang', locale);
    const headline = within(result).getByTestId('operational-summary-headline');
    expect(headline).toHaveTextContent(TEXT[locale].headline);
    await waitFor(() => {
      expect(headline).toHaveFocus();
    });
    const items = within(screen.getByTestId('operational-summary-highlights')).getAllByRole(
      'listitem',
    );
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent(TEXT[locale].first);
    expect(within(items[0] as HTMLElement).getByRole('link')).toHaveAttribute(
      'href',
      '/me/team-attendance',
    );
    expect(within(items[1] as HTMLElement).getByRole('link')).toHaveAttribute(
      'href',
      '/me/leave/approvals',
    );
    for (const limit of TEXT[locale].limits) {
      expect(screen.getByTestId('operational-summary-limitations')).toHaveTextContent(limit);
    }
    expectText(screen.getByTestId('operational-summary-mode'), k.mode.MODEL);
    expectText(screen.getByTestId('operational-summary-announcer'), k.ready);
    expect(stored()).not.toMatch(
      /briefing|attendance|check-in|pointage|ATTENDANCE|2099|ops-summary/iu,
    );
    expect(window.location.search).toBe('');

    const result2 = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(result2.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });

  it('labels a fallback briefing and never names a provider or a model', async () => {
    stubApi((request) =>
      request.method === 'POST'
        ? { status: 200, body: summary(locale, { generationMode: 'FALLBACK' }) }
        : { status: 200, body: emptyStatus },
    );
    const user = userEvent.setup();
    await renderWithSession(
      <TeamOperationsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/team-operations',
    );
    await user.click(await screen.findByRole('button', { name: k.generate }));
    const mode = await screen.findByTestId('operational-summary-mode');
    expectText(mode, k.mode.FALLBACK);
    expect(screen.getByTestId('operational-summary-result').textContent).not.toMatch(
      /openai|gpt|claude|anthropic|prompt|model/iu,
    );
    expect(screen.getByRole('button', { name: k.regenerate })).toBeEnabled();
  });

  it('sends the applied filters of the organization view and discards the briefing on change', async () => {
    const requests = stubApi((request) => {
      if (request.path.startsWith('/legal-entities')) {
        return { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } };
      }
      if (request.path.startsWith('/sites')) return { status: 200, body: { data: [] } };
      if (request.method === 'POST') {
        return {
          status: 200,
          body: summary(locale, { sourceSnapshot: summary(locale).sourceSnapshot }),
        };
      }
      return { status: 200, body: emptyStatus };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <OperationsStatusPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/operations',
    );
    await screen.findByTestId('status-shifts');
    await user.click(screen.getByRole('button', { name: k.generate }));
    await screen.findByTestId('operational-summary-result');
    expect(requests.find((r) => r.method === 'POST')?.path).toBe('/operations/status-summary');

    await user.selectOptions(
      await screen.findByLabelText(c.operationsStatus.filters.legalEntity),
      LEGAL,
    );
    await user.click(screen.getByTestId('operations-status-apply'));
    await waitFor(() => {
      expect(screen.queryByTestId('operational-summary-result')).toBeNull();
    });
    await user.click(screen.getByRole('button', { name: k.generate }));
    await screen.findByTestId('operational-summary-result');
    expect(requests.filter((r) => r.method === 'POST').at(-1)?.path).toBe(
      `/operations/status-summary?legalEntityId=${LEGAL}`,
    );
  });

  it.each([
    [
      'OPERATIONAL_SUMMARY_UNAVAILABLE',
      503,
      'unavailable',
      c.errors.OPERATIONAL_SUMMARY_UNAVAILABLE,
    ],
    ['RATE_LIMITED', 429, 'rateLimited', c.errors.RATE_LIMITED],
    ['EMPLOYEE_LINK_REQUIRED', 403, 'linkRequired', c.errors.EMPLOYEE_LINK_REQUIRED],
  ])(
    'shows the stable %s failure without discarding the status',
    async (code, http, kind, text) => {
      stubApi((request) =>
        request.method === 'POST'
          ? { status: http, body: problem(code, http) }
          : { status: 200, body: emptyStatus },
      );
      const user = userEvent.setup();
      await renderWithSession(
        <TeamOperationsPage />,
        sessionWithRoles(['employee']),
        locale,
        '/me/team-operations',
      );
      await user.click(await screen.findByRole('button', { name: k.generate }));
      const error = await screen.findByTestId('operational-summary-error');
      expect(error).toHaveAttribute('data-kind', kind);
      expectText(error, text);
      expect(error).toHaveTextContent('corr-sum-1234');
      expect(screen.getByTestId('status-shifts')).toBeInTheDocument();
    },
  );
});
