import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MySchedulePage } from './MySchedulePage';
import { WorkSchedulesPage } from './WorkSchedulesPage';
import {
  changeReasonOf,
  changeReasonValid,
  emptyScheduleForm,
  formFromDetail,
  replacementBodyOf,
  emptySlot,
  labelValid,
  normalizeLabel,
  overnight,
  scheduleBodyOf,
  scheduleFailureOf,
  scheduleProblemsOf,
  slotTimes,
  type ScheduleForm,
} from './workSchedules';

const SITE = '33333333-0000-4000-8000-000000000003';
const ENTITY = '44444444-0000-4000-8000-000000000004';
const SCHEDULE = '55555555-0000-4000-8000-000000000005';
const ANA = {
  id: 'aaaaaaaa-0000-4000-8000-0000000000a1',
  employeeNumber: 'E-0001',
  givenNames: 'Ana',
  familyName: '<i>Kabeya</i>',
};

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-schedule-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string; body: string; key: string | null };

function stubApi(route: (request: Recorded) => Reply | Promise<Reply>) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const body = incoming.method === 'GET' ? '' : await incoming.clone().text();
      const recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body,
        key: incoming.headers.get('Idempotency-Key'),
      };
      requests.push(recorded);
      const reply = await route(recorded);
      return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status,
        headers: {
          'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
          ...reply.headers,
        },
      });
    }),
  );
  return requests;
}

const summary = (overrides: Record<string, unknown> = {}) => ({
  id: SCHEDULE,
  site: { id: SITE, code: 'KIN', name: 'Kinshasa <b>Gombe</b>' },
  firstDate: '2026-11-02',
  lastDate: '2026-11-04',
  siteTimezone: 'Africa/Kinshasa',
  totals: { slots: 3, assigned: 1, uncovered: 2 },
  publishedAt: '2026-10-11T10:00:00Z',
  status: 'PUBLISHED',
  changedAt: null,
  changeReason: null,
  replacesScheduleId: null,
  replacedByScheduleId: null,
  ...overrides,
});

const detail = (overrides: Record<string, unknown> = {}) => ({
  ...summary(overrides),
  slots: [
    {
      id: 'c1',
      position: 1,
      start: '2026-11-02T22:00',
      end: '2026-11-03T06:00',
      startAt: '2026-11-02T21:00:00Z',
      endAt: '2026-11-03T05:00:00Z',
      overnight: true,
      labels: { en: 'Night watch', fr: 'Veille de nuit' },
      employee: ANA,
    },
    {
      id: 'c2',
      position: 2,
      start: '2026-11-03T08:00',
      end: '2026-11-03T16:00',
      startAt: '2026-11-03T07:00:00Z',
      endAt: '2026-11-03T15:00:00Z',
      overnight: false,
      labels: { en: 'Cashier', fr: 'Caissière' },
      employee: null,
    },
    {
      id: 'c3',
      position: 3,
      start: '2026-11-04T08:00',
      end: '2026-11-04T16:00',
      startAt: '2026-11-04T07:00:00Z',
      endAt: '2026-11-04T15:00:00Z',
      overnight: false,
      labels: { en: 'Cashier', fr: 'Caissière' },
      employee: null,
    },
  ],
});

const previewOf = (body: { slots: { clientRef: string; start: string; end: string }[] }) => ({
  asOf: '2026-10-11',
  // The selected site's clock (R100-1), one hour from the organization's Kinshasa.
  siteTimezone: 'Africa/Lubumbashi',
  site: { id: SITE, code: 'KIN', name: 'Kinshasa Gombe' },
  firstDate: '2026-11-02',
  lastDate: '2026-11-02',
  slots: body.slots.map((slot, index) => ({
    clientRef: slot.clientRef,
    position: index + 1,
    start: slot.start,
    end: slot.end,
    startAt: null,
    endAt: null,
    overnight: false,
    labels: { en: 'Cashier', fr: 'Caissière' },
    employee: null,
    issue: null,
  })),
  totals: { slots: body.slots.length, assigned: 0, uncovered: body.slots.length },
  warnings: ['UNCOVERED_SLOTS'],
  publishable: true,
  previewDigest: 'a'.repeat(64),
});

/** Lists, the unit options and one schedule; every write answered by {@code write}. */
function adminApi(
  items: () => unknown[],
  write: (request: Recorded) => Reply | Promise<Reply> = () => ({ status: 500 }),
  details: (id: string) => unknown = () => detail(),
) {
  return stubApi((request) => {
    if (request.method === 'GET' && request.path.startsWith('/work-schedules/')) {
      return { status: 200, body: details(request.path.slice('/work-schedules/'.length)) };
    }
    if (request.method === 'GET' && request.path.startsWith('/work-schedules')) {
      return { status: 200, body: { items: items(), nextCursor: null, asOf: '2026-10-11' } };
    }
    if (request.method === 'GET' && request.path.startsWith('/legal-entities')) {
      return { status: 200, body: { data: [{ id: ENTITY, code: 'LE', name: 'Entité' }] } };
    }
    if (request.method === 'GET' && request.path.startsWith('/sites')) {
      return { status: 200, body: { data: [{ id: SITE, code: 'KIN', name: 'Kinshasa Gombe' }] } };
    }
    return write(request);
  });
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

describe('MVP-050A schedule rules in the browser', () => {
  it('follows the server label grammar after normalization', () => {
    expect(normalizeLabel('  Agent d’accueil   (nuit) ')).toBe('Agent d’accueil (nuit)');
    for (const good of ['Caissière n° 2', 'Équipe A.', 'Night desk (lead)', 'B2']) {
      expect(labelValid(good), good).toBe(true);
    }
    for (const bad of ['x', '<b>Lead</b>', 'a@b.cd', 'www.evil.com', 'Go: now', 'x'.repeat(61)]) {
      expect(labelValid(bad), bad).toBe(false);
    }
  });

  it('ends a slot the next day when its end time is not after its start time', () => {
    const slot = { ...emptySlot('2026-11-02'), start: '22:00', end: '06:00' };
    expect(overnight(slot)).toBe(true);
    expect(slotTimes(slot)).toEqual({ start: '2026-11-02T22:00', end: '2026-11-03T06:00' });
    expect(overnight({ start: '08:00', end: '16:00' })).toBe(false);
    expect(slotTimes({ ...slot, date: '2026-12-31' }).end).toBe('2027-01-01T06:00');
  });

  it('names every problem of the form and of each slot, never a value', () => {
    const empty = emptyScheduleForm();
    const problems = scheduleProblemsOf(empty);
    expect([...problems.form]).toEqual(['site', 'firstDate', 'lastDate']);
    const slotKey = empty.slots[0]?.key ?? '';
    expect([...(problems.slots.get(slotKey) ?? [])]).toEqual(['date', 'labelEn', 'labelFr']);
    const long: ScheduleForm = {
      ...empty,
      siteId: SITE,
      firstDate: '2026-11-01',
      lastDate: '2026-12-02',
      slots: [
        {
          ...emptySlot('2026-12-03'),
          start: '08:00',
          end: '08:00',
          labelEn: 'Cashier',
          labelFr: 'Caissière',
        },
      ],
    };
    const found = scheduleProblemsOf(long);
    expect([...found.form]).toEqual(['lastDate']);
    expect([...(found.slots.values().next().value ?? [])]).toEqual(['date', 'end']);
  });

  it('sends IDs, dates, local times and normalized labels only', () => {
    const slot = {
      ...emptySlot('2026-11-02'),
      key: 'k1',
      start: '22:00',
      end: '06:00',
      labelEn: '  Night   watch ',
      labelFr: 'Veille de nuit',
      employee: { ...ANA, employmentStatus: 'CURRENT' as const },
    };
    const uncovered = { ...slot, key: 'k2', employee: null };
    expect(
      scheduleBodyOf({
        legalEntityId: ENTITY,
        siteId: SITE,
        firstDate: '2026-11-02',
        lastDate: '2026-11-02',
        slots: [slot, uncovered],
      }),
    ).toEqual({
      siteId: SITE,
      firstDate: '2026-11-02',
      lastDate: '2026-11-02',
      slots: [
        {
          clientRef: 'k1',
          start: '2026-11-02T22:00',
          end: '2026-11-03T06:00',
          labels: { en: 'Night watch', fr: 'Veille de nuit' },
          employeeId: ANA.id,
        },
        {
          clientRef: 'k2',
          start: '2026-11-02T22:00',
          end: '2026-11-03T06:00',
          labels: { en: 'Night watch', fr: 'Veille de nuit' },
        },
      ],
    });
  });

  it('maps every refusal to an allow-listed message, reason and position', () => {
    const response = (status: number) => new Response(null, { status });
    const conflict = scheduleFailureOf(
      response(409),
      problem('WORK_SCHEDULE_EMPLOYEE_CONFLICT', 409, {
        position: '3',
        reason: 'PUBLISHED',
        value: 'secret',
      }),
    );
    expect(conflict).toMatchObject({
      kind: 'refused',
      messageKey: 'errors.WORK_SCHEDULE_EMPLOYEE_CONFLICT',
      reasonKey: 'workSchedules.issues.PUBLISHED',
      position: 3,
    });
    expect(JSON.stringify(conflict)).not.toContain('secret');
    expect(
      scheduleFailureOf(
        response(422),
        problem('WORK_SCHEDULE_PERIOD_INVALID', 422, { reason: 'PAST' }),
      ).reasonKey,
    ).toBe('workSchedules.periodReasons.PAST');
    expect(
      scheduleFailureOf(
        response(422),
        problem('WORK_SCHEDULE_SLOT_INVALID', 422, { reason: '<script>', position: 'x' }),
      ),
    ).toMatchObject({ reasonKey: undefined, position: undefined });
    expect(scheduleFailureOf(response(409), problem('WORK_SCHEDULE_PREVIEW_STALE', 409)).kind).toBe(
      'stale',
    );
    expect(scheduleFailureOf(response(403), problem('MFA_REQUIRED', 403)).kind).toBe('mfaRequired');
    expect(scheduleFailureOf(response(403), problem('EMPLOYEE_LINK_REQUIRED', 403)).kind).toBe(
      'linkRequired',
    );
    expect(scheduleFailureOf(response(404), problem('WORK_SCHEDULE_NOT_FOUND', 404)).kind).toBe(
      'gone',
    );
  });
});

describe.each(['fr', 'en'] as const)('MVP-050A schedules (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.workSchedules;
  const show = () =>
    renderWithSession(
      <WorkSchedulesPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/work-schedules',
    );

  it('lists published schedules with their totals and filters uncovered slots', async () => {
    adminApi(() => [
      summary(),
      summary({ id: 'other', totals: { slots: 2, assigned: 2, uncovered: 0 } }),
    ]);
    const user = userEvent.setup();
    const { container } = await show();
    expect(await screen.findByRole('heading', { level: 1, name: k.title })).toBeInTheDocument();
    const rows = await screen.findAllByTestId('schedule-row');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByTestId('schedule-uncovered')).toHaveTextContent('2');
    expect(within(rows[1]!).getByTestId('schedule-uncovered')).toHaveTextContent(k.fullyCovered);
    // Site names are plain text.
    expect(screen.getByTestId('schedules')).toHaveTextContent('Kinshasa <b>Gombe</b>');
    expect(screen.getByTestId('schedules').querySelector('b')).toBeNull();
    await expectAccessible(container);

    await user.click(
      within(rows[0]!).getByRole('button', { name: new RegExp(k.actions.view, 'u') }),
    );
    const section = await screen.findByTestId('schedule-detail');
    await waitFor(() => {
      expect(within(section).getAllByTestId('detail-slot')).toHaveLength(3);
    });
    expect(within(section).getByTestId('detail-totals')).toHaveTextContent('3');
    expect(within(section).getByTestId('detail-timezone')).toHaveTextContent(
      k.timezone.replace('{{zone}}', 'Africa/Kinshasa'),
    );
    const slots = within(section).getAllByTestId('detail-slot');
    expect(slots[0]).toHaveTextContent('<i>Kabeya</i>');
    expect(slots[0]).toHaveTextContent(locale === 'fr' ? 'Veille de nuit' : 'Night watch');
    expect(within(slots[1]!).getByTestId('slot-uncovered')).toHaveTextContent(k.uncovered);
    await user.click(within(section).getByLabelText(k.detail.uncoveredOnly));
    expect(within(section).getAllByTestId('detail-slot')).toHaveLength(2);
    for (const slot of within(section).getAllByTestId('detail-slot')) {
      expect(slot).toHaveAttribute('data-covered', 'false');
    }
    await expectAccessible(container);
  });

  it('names the problems of an incomplete schedule and focuses them', async () => {
    const requests = adminApi(() => []);
    const user = userEvent.setup();
    await show();
    await screen.findByTestId('schedules-empty');
    await user.click(screen.getByRole('button', { name: k.form.preview }));
    const box = await screen.findByTestId('create-problems');
    await waitFor(() => {
      expect(box).toHaveFocus();
    });
    expect(box).toHaveTextContent(k.problems.site);
    expect(box).toHaveTextContent(k.problems.firstDate);
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
  });

  it('builds, duplicates, removes, previews and publishes with one stable key', async () => {
    let calls = 0;
    let listed: unknown[] = [];
    const requests = adminApi(
      () => listed,
      (request) => {
        const body = JSON.parse(request.body) as {
          slots: { clientRef: string; start: string; end: string }[];
        };
        if (request.path === '/work-schedules/preview') {
          return { status: 200, body: previewOf(body) };
        }
        if (request.path === '/work-schedules') {
          calls += 1;
          if (calls === 1) return { status: 503, body: problem('INTERNAL_ERROR', 503) };
          listed = [summary()];
          return {
            status: 201,
            body: {
              scheduleId: SCHEDULE,
              publishedAt: '2026-10-11T10:00:00Z',
              totals: { slots: body.slots.length, assigned: 0, uncovered: body.slots.length },
            },
          };
        }
        return { status: 500 };
      },
    );
    const user = userEvent.setup();
    const { container } = await show();
    await screen.findByTestId('schedules-empty');
    await user.selectOptions(screen.getByLabelText(k.form.legalEntity), ENTITY);
    await screen.findByRole('option', { name: /Kinshasa Gombe/u });
    await user.selectOptions(screen.getByLabelText(k.form.siteLabel), SITE);
    await user.type(screen.getByLabelText(k.form.firstDate), '2026-11-02');
    await user.type(screen.getByLabelText(k.form.lastDate), '2026-11-02');
    const first = screen.getAllByTestId('slot')[0]!;
    await user.clear(within(first).getByLabelText(k.slot.start));
    await user.type(within(first).getByLabelText(k.slot.start), '22:00');
    await user.clear(within(first).getByLabelText(k.slot.end));
    await user.type(within(first).getByLabelText(k.slot.end), '06:00');
    expect(within(first).getByTestId('slot-overnight')).toHaveTextContent(k.slot.overnight);
    await user.type(within(first).getByLabelText(k.slot.labelEn), 'Cashier');
    await user.type(within(first).getByLabelText(k.slot.labelFr), 'Caissière');
    // Duplicate, then remove the copy: focus stays inside the slots.
    await user.click(
      within(first).getByRole('button', { name: k.slot.duplicateFor.replace('{{position}}', '1') }),
    );
    expect(screen.getAllByTestId('slot')).toHaveLength(2);
    const copy = screen.getAllByTestId('slot')[1]!;
    expect(within(copy).getByLabelText(k.slot.labelFr)).toHaveValue('Caissière');
    await user.click(
      within(copy).getByRole('button', { name: k.slot.removeFor.replace('{{position}}', '2') }),
    );
    expect(screen.getAllByTestId('slot')).toHaveLength(1);
    await expectAccessible(container);

    await user.click(screen.getByRole('button', { name: k.form.preview }));
    const review = await screen.findByTestId('schedule-review');
    expect(within(review).getByTestId('review-warning')).toBeInTheDocument();
    expect(within(review).getAllByTestId('review-slot')).toHaveLength(1);
    expect(within(review).getByTestId('review-timezone')).toHaveTextContent(
      k.timezone.replace('{{zone}}', 'Africa/Lubumbashi'),
    );
    await expectAccessible(container);
    const publish = within(review).getByRole('button', { name: k.review.publish });
    await user.click(publish);
    expect(await screen.findByTestId('create-error')).toBeInTheDocument();
    await user.click(within(review).getByRole('button', { name: k.review.publish }));
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).not.toHaveTextContent(/^$/u);
    });
    const writes = requests.filter((r) => r.method === 'POST' && r.path === '/work-schedules');
    expect(writes).toHaveLength(2);
    expect(writes[0]!.key).toMatch(/^web-/u);
    expect(writes[1]!.key).toBe(writes[0]!.key);
    const sent = JSON.parse(writes[0]!.body) as Record<string, unknown>;
    expect(sent.confirm).toBe(true);
    expect(sent.previewDigest).toBe('a'.repeat(64));
    expect(JSON.stringify(sent)).toContain('"end":"2026-11-03T06:00"');
    expect(window.localStorage.length + window.sessionStorage.length).toBe(0);
  });

  it('blocks publication of a preview with issues and explains a stale one', async () => {
    const requests = adminApi(
      () => [],
      (request) => {
        const body = JSON.parse(request.body) as {
          slots: { clientRef: string; start: string; end: string }[];
        };
        if (request.path === '/work-schedules/preview') {
          const preview = previewOf(body);
          preview.publishable = false;
          (preview.slots[0] as { issue: unknown }).issue = {
            code: 'WORK_SCHEDULE_EMPLOYEE_CONFLICT',
            reason: 'IN_SCHEDULE',
          };
          return { status: 200, body: preview };
        }
        return { status: 409, body: problem('WORK_SCHEDULE_PREVIEW_STALE', 409) };
      },
    );
    const user = userEvent.setup();
    await show();
    await screen.findByTestId('schedules-empty');
    await user.selectOptions(screen.getByLabelText(k.form.legalEntity), ENTITY);
    await screen.findByRole('option', { name: /Kinshasa Gombe/u });
    await user.selectOptions(screen.getByLabelText(k.form.siteLabel), SITE);
    await user.type(screen.getByLabelText(k.form.firstDate), '2026-11-02');
    await user.type(screen.getByLabelText(k.form.lastDate), '2026-11-02');
    await user.type(screen.getByLabelText(k.slot.labelEn), 'Cashier');
    await user.type(screen.getByLabelText(k.slot.labelFr), 'Caissière');
    await user.click(screen.getByRole('button', { name: k.form.preview }));
    const review = await screen.findByTestId('schedule-review');
    expect(within(review).getByTestId('review-blocked')).toHaveTextContent(k.review.blocked);
    expect(within(review).getByTestId('review-check')).toHaveTextContent(k.issues.IN_SCHEDULE);
    expect(within(review).getByRole('button', { name: k.review.publish })).toBeDisabled();
    expect(
      requests.filter((r) => r.path === '/work-schedules' && r.method === 'POST'),
    ).toHaveLength(0);
  });
});

const REPLACEMENT = '66666666-0000-4000-8000-000000000006';

describe('MVP-050B change rules in the browser', () => {
  it('prefills a replacement with every slot, label and employee of the published schedule', () => {
    const form = formFromDetail(detail() as never);
    expect(form.siteId).toBe(SITE);
    expect([form.firstDate, form.lastDate]).toEqual(['2026-11-02', '2026-11-04']);
    expect(form.slots).toHaveLength(3);
    expect(form.slots[0]).toMatchObject({
      date: '2026-11-02',
      start: '22:00',
      end: '06:00',
      labelEn: 'Night watch',
      labelFr: 'Veille de nuit',
      employee: { id: ANA.id },
    });
    expect(form.slots[1]!.employee).toBeNull();
    // Each slot gets a new client reference.
    expect(new Set(form.slots.map((slot) => slot.key)).size).toBe(3);
    const body = replacementBodyOf(form, '  Store  closed early ');
    expect(Object.keys(body).sort()).toEqual(['reason', 'slots']);
    expect(body.reason).toBe(changeReasonOf('  Store  closed early '));
    expect(body.slots[0]).toMatchObject({
      start: '2026-11-02T22:00',
      end: '2026-11-03T06:00',
      employeeId: ANA.id,
    });
    expect(body.slots[1]).not.toHaveProperty('employeeId');
    expect(JSON.stringify(body)).not.toContain('siteId');
  });

  it('accepts a change reason only within the decision-reason grammar', () => {
    expect(changeReasonValid('Store closed')).toBe(true);
    expect(changeReasonValid('   ')).toBe(false);
    expect(changeReasonValid('x')).toBe(false);
    expect(changeReasonValid('a'.repeat(501))).toBe(false);
    expect(changeReasonValid('bad\u0007bell')).toBe(false);
  });
});

describe.each(['fr', 'en'] as const)('MVP-050B schedule lifecycle (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.workSchedules;
  const show = () =>
    renderWithSession(
      <WorkSchedulesPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/work-schedules',
    );
  const open = async (user: ReturnType<typeof userEvent.setup>) => {
    const rows = await screen.findAllByTestId('schedule-row');
    await user.click(
      within(rows[0]!).getByRole('button', { name: new RegExp(k.actions.view, 'u') }),
    );
    const section = await screen.findByTestId('schedule-detail');
    await within(section).findAllByTestId('detail-slot');
    return section;
  };

  it('shows each status, filters by it and links a replaced schedule to its successor', async () => {
    const requests = adminApi(
      () => [
        summary({
          status: 'REPLACED',
          changedAt: '2026-10-12T09:30:00Z',
          changeReason: 'Store <b>closed</b>',
          replacedByScheduleId: REPLACEMENT,
        }),
        summary({ id: 'cancelled', status: 'CANCELLED', changedAt: '2026-10-12T09:30:00Z' }),
        summary({ id: REPLACEMENT, replacesScheduleId: SCHEDULE }),
      ],
      undefined,
      (id) =>
        id === REPLACEMENT
          ? detail({ id: REPLACEMENT, replacesScheduleId: SCHEDULE })
          : detail({
              status: 'REPLACED',
              changedAt: '2026-10-12T09:30:00Z',
              changeReason: 'Store <b>closed</b>',
              replacedByScheduleId: REPLACEMENT,
            }),
    );
    const user = userEvent.setup();
    const { container } = await show();
    const badges = await screen.findAllByTestId('schedule-status');
    expect(badges.map((badge) => badge.textContent)).toEqual([
      k.status.REPLACED,
      k.status.CANCELLED,
      k.status.PUBLISHED,
    ]);
    await expectAccessible(container);

    await user.selectOptions(screen.getByTestId('status-filter'), 'CANCELLED');
    await waitFor(() => {
      expect(requests.some((r) => r.path.includes('status=CANCELLED'))).toBe(true);
    });

    const section = await open(user);
    expect(within(section).getByTestId('detail-status-badge')).toHaveTextContent(k.status.REPLACED);
    expect(within(section).getByTestId('detail-changed')).toHaveTextContent(
      k.detail.replacedAt.split('{{')[0]!.trim(),
    );
    // The reason is plain text, and a changed schedule offers no further change.
    expect(within(section).getByTestId('detail-reason')).toHaveTextContent('Store <b>closed</b>');
    expect(section.querySelector('b')).toBeNull();
    expect(within(section).queryByTestId('detail-actions')).toBeNull();
    await expectAccessible(container);

    await user.click(within(section).getByTestId('view-successor'));
    await waitFor(() => {
      expect(
        within(screen.getByTestId('schedule-detail')).getByTestId('view-predecessor'),
      ).toBeInTheDocument();
    });
    expect(
      within(screen.getByTestId('schedule-detail')).getByTestId('detail-status-badge'),
    ).toHaveTextContent(k.status.PUBLISHED);
    expect(requests.some((r) => r.path === `/work-schedules/${REPLACEMENT}`)).toBe(true);
  });

  it('reviews and confirms a cancellation with a reason and one stable key', async () => {
    let calls = 0;
    let cancelled = false;
    const requests = adminApi(
      () => [summary(cancelled ? { status: 'CANCELLED' } : {})],
      (request) => {
        if (request.path === `/work-schedules/${SCHEDULE}/cancellation`) {
          calls += 1;
          if (calls === 1) return { status: 503, body: problem('INTERNAL_ERROR', 503) };
          cancelled = true;
          return {
            status: 200,
            body: {
              scheduleId: SCHEDULE,
              status: 'CANCELLED',
              changedAt: '2026-10-12T09:30:00Z',
              replacementScheduleId: null,
              totals: { slots: 3, assigned: 1, uncovered: 2 },
            },
          };
        }
        return { status: 500 };
      },
      () =>
        cancelled
          ? detail({
              status: 'CANCELLED',
              changedAt: '2026-10-12T09:30:00Z',
              changeReason: 'Store closed',
            })
          : detail(),
    );
    const user = userEvent.setup();
    const { container } = await show();
    const section = await open(user);
    expect(within(section).getByTestId('detail-actions')).toHaveTextContent(k.actions.futureOnly);
    await user.click(within(section).getByRole('button', { name: k.actions.cancel }));
    const panel = await screen.findByTestId('cancel-schedule');
    await expectAccessible(container);
    // An empty reason is named and focused; nothing is sent.
    await user.click(within(panel).getByRole('button', { name: k.cancel.review }));
    const field = within(panel).getByLabelText(k.cancel.reason);
    await waitFor(() => {
      expect(field).toHaveFocus();
    });
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(panel).toHaveTextContent(k.problems.reason);
    await user.type(field, '  Store closed ');
    await user.click(within(panel).getByRole('button', { name: k.cancel.review }));
    expect(await within(panel).findByTestId('cancel-summary')).toHaveTextContent('3');
    expect(within(panel).getByTestId('cancel-reason')).toHaveTextContent('Store closed');
    await expectAccessible(container);

    await user.click(within(panel).getByRole('button', { name: k.cancel.confirm }));
    expect(await within(panel).findByTestId('cancel-error')).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: k.cancel.confirm }));
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent(k.cancelled);
    });
    await waitFor(() => {
      expect(screen.getByTestId('detail-status-badge')).toHaveTextContent(k.status.CANCELLED);
    });
    expect(screen.queryByTestId('detail-actions')).toBeNull();
    const writes = requests.filter((r) => r.method === 'POST');
    expect(writes).toHaveLength(2);
    expect(writes[0]!.key).toMatch(/^web-/u);
    expect(writes[1]!.key).toBe(writes[0]!.key);
    expect(JSON.parse(writes[0]!.body)).toEqual({ reason: 'Store closed', confirm: true });
    expect(window.localStorage.length + window.sessionStorage.length).toBe(0);
  });

  it('explains a closed change window and an already cancelled schedule', async () => {
    let answer = 'WORK_SCHEDULE_CHANGE_WINDOW_CLOSED';
    adminApi(
      () => [summary()],
      () => ({ status: 409, body: problem(answer, 409) }),
    );
    const user = userEvent.setup();
    await show();
    const section = await open(user);
    await user.click(within(section).getByRole('button', { name: k.actions.cancel }));
    const panel = await screen.findByTestId('cancel-schedule');
    await user.type(within(panel).getByLabelText(k.cancel.reason), 'Store closed');
    await user.click(within(panel).getByRole('button', { name: k.cancel.review }));
    await user.click(within(panel).getByRole('button', { name: k.cancel.confirm }));
    expect(await within(panel).findByTestId('cancel-error')).toHaveTextContent(
      c.errors.WORK_SCHEDULE_CHANGE_WINDOW_CLOSED,
    );
    answer = 'WORK_SCHEDULE_ALREADY_CANCELLED';
    await user.click(within(panel).getByRole('button', { name: k.cancel.confirm }));
    await waitFor(() => {
      expect(within(panel).getByTestId('cancel-error')).toHaveTextContent(
        c.errors.WORK_SCHEDULE_ALREADY_CANCELLED,
      );
    });
  });

  it('replaces with the site and dates fixed, the current and proposed slots side by side', async () => {
    let replaced = false;
    const requests = adminApi(
      () => [summary(replaced ? { status: 'REPLACED', replacedByScheduleId: REPLACEMENT } : {})],
      (request) => {
        const body = JSON.parse(request.body) as {
          slots: { clientRef: string; start: string; end: string }[];
        };
        if (request.path === `/work-schedules/${SCHEDULE}/replacement-preview`) {
          return { status: 200, body: { ...previewOf(body), previewDigest: 'b'.repeat(64) } };
        }
        if (request.path === `/work-schedules/${SCHEDULE}/replacement`) {
          replaced = true;
          return {
            status: 201,
            body: {
              scheduleId: SCHEDULE,
              status: 'REPLACED',
              changedAt: '2026-10-12T09:30:00Z',
              replacementScheduleId: REPLACEMENT,
              totals: { slots: body.slots.length, assigned: 1, uncovered: 1 },
            },
          };
        }
        return { status: 500 };
      },
      (id) =>
        id === REPLACEMENT ? detail({ id: REPLACEMENT, replacesScheduleId: SCHEDULE }) : detail(),
    );
    const user = userEvent.setup();
    const { container } = await show();
    const section = await open(user);
    await user.click(within(section).getByRole('button', { name: k.actions.replace }));
    const builder = screen.getByTestId('create-schedule');
    expect(within(builder).getByRole('heading', { name: k.replace.title })).toBeInTheDocument();
    expect(within(builder).getByTestId('replace-site')).toHaveTextContent('Kinshasa <b>Gombe</b>');
    expect(within(builder).queryByTestId('site-choice')).toBeNull();
    expect(within(builder).getAllByTestId('slot')).toHaveLength(3);
    await expectAccessible(container);

    // The reason is required before any preview.
    await user.click(within(builder).getByRole('button', { name: k.form.preview }));
    expect(await screen.findByTestId('create-problems')).toHaveTextContent(k.problems.reason);
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);

    // Drop the last slot, give a reason and preview.
    const slots = within(builder).getAllByTestId('slot');
    await user.click(
      within(slots[2]!).getByRole('button', {
        name: k.slot.removeFor.replace('{{position}}', '3'),
      }),
    );
    await user.type(within(builder).getByLabelText(k.replace.reason), 'Inventory day');
    await user.click(within(builder).getByRole('button', { name: k.form.preview }));
    const review = await screen.findByTestId('schedule-review');
    expect(within(review).getAllByTestId('current-slot')).toHaveLength(3);
    expect(within(review).getAllByTestId('review-slot')).toHaveLength(2);
    expect(within(review).getByTestId('review-reason')).toHaveTextContent('Inventory day');
    expect(review).toHaveTextContent(k.replace.confirmText);
    await expectAccessible(container);

    await user.click(within(review).getByRole('button', { name: k.replace.confirm }));
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).not.toHaveTextContent(/^$/u);
    });
    const preview = requests.find((r) => r.path.endsWith('/replacement-preview'))!;
    expect(preview.key).toBeNull();
    const write = requests.find((r) => r.path.endsWith('/replacement'))!;
    expect(write.key).toMatch(/^web-/u);
    const sent = JSON.parse(write.body) as Record<string, unknown>;
    expect(Object.keys(sent).sort()).toEqual(['confirm', 'previewDigest', 'reason', 'slots']);
    expect(sent.previewDigest).toBe('b'.repeat(64));
    expect(sent.reason).toBe('Inventory day');
    expect((sent.slots as unknown[]).length).toBe(2);
    // The new schedule opens, linked back to the one it replaced.
    await waitFor(() => {
      expect(screen.getByTestId('view-predecessor')).toBeInTheDocument();
    });
    expect(within(screen.getByTestId('create-schedule')).queryByTestId('replace-fixed')).toBeNull();
  });

  it('abandons a replacement for a new empty schedule', async () => {
    adminApi(() => [summary()]);
    const user = userEvent.setup();
    await show();
    const section = await open(user);
    await user.click(within(section).getByRole('button', { name: k.actions.replace }));
    const builder = screen.getByTestId('create-schedule');
    await user.click(within(builder).getByRole('button', { name: k.replace.abandon }));
    expect(within(builder).queryByTestId('replace-fixed')).toBeNull();
    expect(within(builder).getByTestId('site-choice')).toBeInTheDocument();
    expect(within(builder).getAllByTestId('slot')).toHaveLength(1);
  });
});

describe.each(['fr', 'en'] as const)('MVP-050A my schedule (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.mySchedule;
  const show = () =>
    renderWithSession(<MySchedulePage />, sessionWithRoles(['employee']), locale, '/me/schedule');

  const shift = (
    id: string,
    start: string,
    end: string,
    overnightShift: boolean,
    siteTimezone = 'Africa/Kinshasa',
  ) => ({
    id,
    scheduleId: SCHEDULE,
    site: { id: SITE, code: 'KIN', name: 'Kinshasa <b>Gombe</b>' },
    start,
    end,
    siteTimezone,
    overnight: overnightShift,
    labels: { en: 'Night watch', fr: 'Veille de nuit' },
    assignmentSource: 'PUBLISHED',
    attendance: {
      status: 'NOT_RECORDED',
      attendanceRecordId: null,
      checkedInAt: null,
      checkedOutAt: null,
      checkInAvailable: false,
      checkOutAvailable: false,
    },
  });

  it('lists my shifts in my language with an overnight indicator and pages on', async () => {
    let page = 0;
    const requests = stubApi((request) => {
      // MVP-051: the page also loads the employee's substitution history.
      if (request.path.startsWith('/me/work-schedule-substitutions')) {
        return {
          status: 200,
          body: { items: [], nextCursor: null, asOfInstant: '2026-10-11T08:00:00Z' },
        };
      }
      page += 1;
      return page === 1
        ? {
            status: 200,
            body: {
              items: [shift('s1', '2026-11-02T22:00', '2026-11-03T06:00', true)],
              nextCursor: 'next',
              asOfInstant: '2026-10-11T08:00:00Z',
              from: null,
              to: null,
            },
          }
        : {
            status: 200,
            body: {
              items: [
                shift('s2', '2026-11-04T08:00', '2026-11-04T12:00', false, 'Africa/Lubumbashi'),
              ],
              nextCursor: null,
              asOfInstant: '2026-10-11T08:00:00Z',
              from: null,
              to: null,
            },
          };
    });
    const user = userEvent.setup();
    const { container } = await show();
    expect(await screen.findByRole('heading', { level: 1, name: k.title })).toBeInTheDocument();
    const shifts = await screen.findAllByTestId('my-shift');
    // The server's instant-based default view: no date is sent, none is presented as "today".
    expect(screen.getByTestId('my-schedule-scope')).toHaveTextContent(k.current);
    expect(
      requests.find(
        (r) => r.path.startsWith('/me/work-schedule?') || r.path === '/me/work-schedule',
      )!.path,
    ).not.toMatch(/[?&](from|to)=/u);
    expect(shifts).toHaveLength(1);
    expect(within(shifts[0]!).getByTestId('my-shift-label')).toHaveTextContent(
      locale === 'fr' ? 'Veille de nuit' : 'Night watch',
    );
    expect(within(shifts[0]!).getByTestId('my-shift-label')).toHaveAttribute('lang', locale);
    expect(within(shifts[0]!).getByTestId('my-shift-overnight')).toBeInTheDocument();
    expect(within(shifts[0]!).getByTestId('my-shift-site')).toHaveTextContent('<b>Gombe</b>');
    expect(within(shifts[0]!).getByTestId('my-shift-timezone')).toHaveTextContent(
      k.timezone.replace('{{zone}}', 'Africa/Kinshasa'),
    );
    await expectAccessible(container);
    await user.click(screen.getByRole('button', { name: k.more }));
    await waitFor(() => {
      expect(screen.getAllByTestId('my-shift')).toHaveLength(2);
    });
    await waitFor(() => {
      expect(screen.getAllByTestId('my-shift')[1]).toHaveFocus();
    });
    expect(
      screen.getAllByTestId('my-shift')[1]!.querySelector('[data-testid="my-shift-overnight"]'),
    ).toBeNull();
    // Each shift in its own schedule's site time zone, shown as published (never converted).
    const later = screen.getAllByTestId('my-shift')[1]!;
    expect(within(later).getByTestId('my-shift-timezone')).toHaveTextContent(
      k.timezone.replace('{{zone}}', 'Africa/Lubumbashi'),
    );
    expect(within(later).getByTestId('my-shift-time')).toHaveTextContent(/\b8:00\b/u);
    expect(requests.filter((r) => r.path.startsWith('/me/work-schedule?')).at(-1)!.path).toContain(
      'cursor=next',
    );
    expect(screen.queryByRole('button', { name: k.more })).toBeNull();
  });

  it('shows the empty state and the link-required refusal without a retry', async () => {
    stubApi(() => ({
      status: 200,
      body: {
        items: [],
        nextCursor: null,
        asOfInstant: '2026-10-11T08:00:00Z',
        from: null,
        to: null,
      },
    }));
    const { container } = await show();
    expect(await screen.findByTestId('my-schedule-empty')).toHaveTextContent(k.empty);
    await expectAccessible(container);
    vi.unstubAllGlobals();
    stubApi(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    await show();
    const alerts = await screen.findAllByTestId('my-schedule-error');
    expect(alerts.at(-1)).toHaveTextContent(k.linkRequired);
    expect(screen.queryByRole('button', { name: k.retry })).toBeNull();
  });
});
