import { resources } from '@divalhr/localization';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { AttendanceReviewPage } from './AttendanceReviewPage';
import { MyAttendancePage } from './MyAttendancePage';
import {
  correctionFailureOf,
  correctionProblemsOf,
  instantOf,
  timeField,
  zonedInstant,
  zonedLocal,
  type CorrectionForm,
} from './attendanceCorrections';

const SITE = '33333333-0000-4000-8000-000000000003';
const SCHEDULE = '55555555-0000-4000-8000-000000000005';
const SLOT = '66666666-0000-4000-8000-000000000006';
const SLOT_2 = '77777777-0000-4000-8000-000000000007';
const RECORD = '88888888-0000-4000-8000-000000000008';
const ANA = '11111111-0000-4000-8000-000000000001';
const DIGEST = 'a'.repeat(64);
const EXPLANATION = 'Le lecteur de badge était hors service';

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-fix-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = {
  method: string;
  path: string;
  body: Record<string, unknown> | null;
  key: string | null;
};

function stubApi(route: (request: Recorded) => Reply | Promise<Reply>) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const text = incoming.method === 'GET' ? '' : await incoming.text();
      const recorded: Recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body: text ? (JSON.parse(text) as Record<string, unknown>) : null,
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

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

const ANA_PERSON = { id: ANA, employeeNumber: 'E-0042', givenNames: 'Ana', familyName: 'Kalala' };

const shift = (overrides: Record<string, unknown> = {}) => ({
  scheduleId: SCHEDULE,
  slotId: SLOT,
  site: { id: SITE, code: 'KIN', name: 'Kinshasa Gombe' },
  siteTimezone: 'Africa/Kinshasa',
  start: '2099-11-02T08:00',
  end: '2099-11-02T16:00',
  startAt: '2099-11-02T07:00:00Z',
  endAt: '2099-11-02T15:00:00Z',
  overnight: false,
  labels: { en: 'Cashier', fr: 'Caissière' },
  ...overrides,
});

const missingRow = {
  type: 'MISSING_CHECK_IN',
  employee: ANA_PERSON,
  ...shift({ slotId: SLOT_2 }),
  attendanceRecordId: null,
  checkedInAt: null,
  checkedOutAt: null,
  varianceMinutes: null,
};

const lateRow = {
  type: 'LATE_CHECK_IN',
  employee: ANA_PERSON,
  ...shift(),
  attendanceRecordId: RECORD,
  checkedInAt: '2099-11-02T07:12:00.123456Z',
  checkedOutAt: '2099-11-02T14:40:00Z',
  varianceMinutes: 13,
};

const page = (items: unknown[]) => ({
  status: 200,
  body: { items, nextCursor: null, asOfInstant: '2099-11-03T06:00:00Z' },
});

const preview = (overrides: Record<string, unknown> = {}) => ({
  operation: 'CREATE_MISSING_RECORD',
  attendanceRecordId: null,
  targetVersion: 0,
  employee: ANA_PERSON,
  shift: shift({ slotId: SLOT_2 }),
  boundaries: {
    checkInOpensAt: '2099-11-02T05:00:00Z',
    checkInClosesAt: '2099-11-02T15:00:00Z',
    checkOutUntil: '2099-11-03T15:00:00Z',
  },
  before: null,
  after: { checkedInAt: '2099-11-02T07:00:00Z', checkedOutAt: '2099-11-02T15:00:00Z' },
  reasonCode: 'SUPERVISOR_OR_EMPLOYEE_REPORT',
  explanation: EXPLANATION,
  exceptionsBefore: ['MISSING_CHECK_IN'],
  exceptionsAfter: [],
  warnings: [],
  previewedAt: '2099-11-03T06:00:00Z',
  previewDigest: DIGEST,
  ...overrides,
});

const receipt = (overrides: Record<string, unknown> = {}) => ({
  correctionId: '99999999-0000-4000-8000-000000000009',
  operation: 'CREATE_MISSING_RECORD',
  correctionVersion: 1,
  correctedAt: '2099-11-03T06:00:01Z',
  reasonCode: 'SUPERVISOR_OR_EMPLOYEE_REPORT',
  before: null,
  after: { checkedInAt: '2099-11-02T07:00:00Z', checkedOutAt: '2099-11-02T15:00:00Z' },
  record: {
    attendanceRecordId: RECORD,
    slotId: SLOT_2,
    status: 'COMPLETED',
    checkedInAt: '2099-11-02T07:00:00Z',
    checkedOutAt: '2099-11-02T15:00:00Z',
    correctionVersion: 1,
    correctedAt: '2099-11-03T06:00:01Z',
  },
  ...overrides,
});

const history = (overrides: Record<string, unknown> = {}) => ({
  record: {
    attendanceRecordId: RECORD,
    slotId: SLOT,
    status: 'COMPLETED',
    checkedInAt: '2099-11-02T07:12:00.123456Z',
    checkedOutAt: '2099-11-02T14:40:00Z',
    correctionVersion: 0,
    correctedAt: null,
  },
  employee: ANA_PERSON,
  shift: shift(),
  originalEvents: [
    { type: 'CHECKED_IN', recordedAt: '2099-11-02T07:12:00.123456Z', recordingMethod: 'SELF' },
    { type: 'CHECKED_OUT', recordedAt: '2099-11-02T14:40:00Z', recordingMethod: 'SELF' },
  ],
  corrections: [],
  ...overrides,
});

const form = (overrides: Partial<CorrectionForm> = {}): CorrectionForm => ({
  checkIn: { local: '2099-11-02T08:00', original: null },
  hasCheckOut: true,
  checkOut: { local: '2099-11-02T16:00', original: null },
  reasonCode: 'DATA_ENTRY_ERROR',
  explanation: EXPLANATION,
  ...overrides,
});

describe('MVP-062 correction rules in the browser', () => {
  it('sends site-local times with the site’s own offset, never the browser’s', () => {
    expect(zonedInstant('2099-11-02T08:00', 'Africa/Kinshasa')).toBe('2099-11-02T08:00:00+01:00');
    expect(zonedInstant('2099-11-02T08:00:30', 'Africa/Lubumbashi')).toBe(
      '2099-11-02T08:00:30+02:00',
    );
    expect(zonedInstant('2026-07-01T08:00', 'America/Toronto')).toBe('2026-07-01T08:00:00-04:00');
    // A local time that does not exist (the spring-forward gap) is never guessed.
    expect(zonedInstant('2026-03-08T02:30', 'America/Toronto')).toBeNull();
    expect(zonedInstant('2099-11-02 08:00', 'Africa/Kinshasa')).toBeNull();
    expect(zonedLocal('2099-11-02T07:12:05.123456Z', 'Africa/Kinshasa')).toBe(
      '2099-11-02T08:12:05',
    );
    // An untouched prefilled value is sent exactly, microseconds included.
    const prefilled = timeField('2099-11-02T07:12:05.123456Z', 'Africa/Kinshasa');
    expect(instantOf(prefilled, 'Africa/Kinshasa')).toBe('2099-11-02T07:12:05.123456Z');
    expect(instantOf({ ...prefilled, local: '2099-11-02T08:00' }, 'Africa/Kinshasa')).toBe(
      '2099-11-02T08:00:00+01:00',
    );
  });

  it('checks the form like the server and maps every refusal without a param', () => {
    const zone = 'Africa/Kinshasa';
    expect(correctionProblemsOf(form(), zone).size).toBe(0);
    expect(correctionProblemsOf(form({ hasCheckOut: false }), zone).size).toBe(0);
    expect(
      correctionProblemsOf(form({ checkOut: { local: '2099-11-02T08:00', original: null } }), zone),
    ).toEqual(new Map([['checkedOutAt', 'order']]));
    expect(
      correctionProblemsOf(form({ checkIn: { local: '', original: null }, reasonCode: '' }), zone),
    ).toEqual(
      new Map([
        ['checkedInAt', 'required'],
        ['reasonCode', 'required'],
      ]),
    );
    for (const [text, kind] of [
      ['court', 'length'],
      ['x'.repeat(501), 'length'],
      ['Voir https://exemple.test ici', 'format'],
      ['Voir <b>ici</b> svp', 'format'],
      ['Ligne une\nligne deux', 'format'],
      ['   ', 'required'],
    ] as const) {
      expect(correctionProblemsOf(form({ explanation: text }), zone).get('explanation')).toBe(kind);
    }
    expect(correctionProblemsOf(form({ explanation: `  ${'é'.repeat(500)}  ` }), zone).size).toBe(
      0,
    );
    const at = (status: number, code: string, params: Record<string, unknown> = {}) =>
      correctionFailureOf(new Response(null, { status }), problem(code, status, params));
    expect(at(404, 'ATTENDANCE_CORRECTION_TARGET_NOT_FOUND').kind).toBe('notFound');
    expect(at(409, 'ATTENDANCE_CORRECTION_NOT_ALLOWED').kind).toBe('notAllowed');
    expect(at(409, 'ATTENDANCE_CORRECTION_PREVIEW_STALE').kind).toBe('stale');
    expect(at(409, 'IDEMPOTENCY_KEY_REUSED').kind).toBe('idempotency');
    expect(at(403, 'MFA_REQUIRED').kind).toBe('forbidden');
    const invalid = at(400, 'VALIDATION_FAILED', {
      fields: [
        { field: 'checkedOutAt', constraint: 'RANGE' },
        { field: 'tenantId', constraint: 'UNKNOWN_PROPERTY' },
      ],
    });
    expect(invalid).toMatchObject({ kind: 'validation', fields: ['checkedOutAt'] });
    expect(JSON.stringify(invalid)).not.toContain('tenantId');
    for (const locale of ['en', 'fr'] as const) {
      const errors = resources[locale].common.errors as Record<string, string>;
      for (const code of [
        'ATTENDANCE_CORRECTION_TARGET_NOT_FOUND',
        'ATTENDANCE_CORRECTION_NOT_ALLOWED',
        'ATTENDANCE_CORRECTION_PREVIEW_STALE',
      ]) {
        expect(errors[code]).toBeTruthy();
      }
    }
  });
});

describe.each(['en', 'fr'] as const)('MVP-062 attendance corrections (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.attendanceCorrection;

  it('creates a missing record from its exception: form, preview, confirmation, history', async () => {
    let listed = 0;
    const requests = stubApi((request) => {
      if (request.path.startsWith('/attendance/exceptions')) {
        listed += 1;
        return page(listed === 1 ? [missingRow] : []);
      }
      if (request.path === '/attendance/corrections/missing-record-preview') {
        return { status: 200, body: preview() };
      }
      if (request.path === '/attendance/corrections/missing-record') {
        return { status: 201, body: receipt() };
      }
      if (request.path === `/attendance/records/${RECORD}/corrections`) {
        return {
          status: 200,
          body: history({
            record: receipt().record,
            originalEvents: [],
            corrections: [
              {
                id: receipt().correctionId,
                version: 1,
                operation: 'CREATE_MISSING_RECORD',
                before: null,
                after: receipt().after,
                reasonCode: 'SUPERVISOR_OR_EMPLOYEE_REPORT',
                explanation: EXPLANATION,
                correctedAt: receipt().correctedAt,
                correctedByYou: true,
                administratorReference: 'ABCD1234',
              },
            ],
          }),
        };
      }
      return { status: 200, body: { data: [] } };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <AttendanceReviewPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/attendance-review',
    );
    const card = (await screen.findAllByTestId('attendance-exception'))[0]!;
    await user.click(within(card).getByRole('button', { name: /Ana Kalala/u }));
    const dialog = await screen.findByTestId('attendance-correction-dialog');
    expect(dialog).toHaveAttribute('data-operation', 'CREATE_MISSING_RECORD');
    expect(
      within(dialog).getByRole('heading', { name: k.title.CREATE_MISSING_RECORD }),
    ).toBeVisible();
    expect(within(dialog).getByTestId('attendance-correction-zone')).toHaveTextContent(
      'Africa/Kinshasa',
    );
    await expectAccessible(dialog);

    // Nothing is sent while the browser can already tell a problem.
    await user.click(within(dialog).getByTestId('attendance-correction-preview'));
    expect(requests.some((r) => r.path.includes('correction'))).toBe(false);
    expect(within(dialog).getByText(k.problems.checkedInAt.required)).toBeVisible();

    fireEvent.change(within(dialog).getByLabelText(k.checkIn), {
      target: { value: '2099-11-02T08:00' },
    });
    fireEvent.change(within(dialog).getByLabelText(k.checkOut), {
      target: { value: '2099-11-02T16:00' },
    });
    await user.selectOptions(
      within(dialog).getByLabelText(k.reason),
      k.reasons.SUPERVISOR_OR_EMPLOYEE_REPORT,
    );
    await user.type(within(dialog).getByLabelText(k.explanation), `  ${EXPLANATION}  `);
    await user.click(within(dialog).getByTestId('attendance-correction-preview'));

    const review = await within(dialog).findByTestId('attendance-correction-review');
    const sent = requests.find((r) => r.path === '/attendance/corrections/missing-record-preview');
    expect(sent?.body).toEqual({
      checkedInAt: '2099-11-02T08:00:00+01:00',
      checkedOutAt: '2099-11-02T16:00:00+01:00',
      reasonCode: 'SUPERVISOR_OR_EMPLOYEE_REPORT',
      explanation: EXPLANATION,
      slotId: SLOT_2,
      employeeId: ANA,
    });
    expect(within(review).getByTestId('attendance-correction-exceptions-before')).toHaveTextContent(
      c.attendanceExceptions.type.MISSING_CHECK_IN,
    );
    expect(within(review).getByTestId('attendance-correction-exceptions-after')).toHaveTextContent(
      k.noException,
    );
    expect(
      within(review).getByTestId('attendance-correction-before-after-before-in'),
    ).toHaveTextContent(k.noRecord);
    // The commit needs the explicit confirmation.
    const commit = within(review).getByTestId('attendance-correction-commit');
    expect(commit).toBeDisabled();
    await expectAccessible(dialog);
    await user.click(within(review).getByTestId('attendance-correction-confirm-check'));
    await user.click(commit);

    expect(await within(dialog).findByTestId('attendance-correction-status')).toHaveTextContent(
      k.done.CREATE_MISSING_RECORD.replace('{{version}}', '1'),
    );
    const committed = requests.find((r) => r.path === '/attendance/corrections/missing-record');
    expect(committed?.body).toEqual({
      ...sent?.body,
      targetVersion: 0,
      previewDigest: DIGEST,
      confirm: true,
    });
    // The server's Idempotency-Key grammar (IdempotencyKeys.ALLOWED).
    expect(committed?.key).toMatch(/^[A-Za-z0-9._:-]{16,128}$/u);
    const entries = await within(dialog).findAllByTestId('attendance-correction-entry');
    expect(entries[0]).toHaveTextContent(k.byYou);
    await expectAccessible(dialog);

    // Closing reads the exceptions again: the missing check-in has disappeared.
    await user.click(within(dialog).getByTestId('attendance-correction-close'));
    await waitFor(() => {
      expect(screen.getByTestId('attendance-exceptions-empty')).toBeVisible();
    });
    expect(stored()).not.toMatch(/Kalala|badge|E-0042|2099-11|88888888/u);
    await expectAccessible(container);
  });

  it('corrects a record from its current values and previews again when stale', async () => {
    let previews = 0;
    const requests = stubApi((request) => {
      if (request.path.startsWith('/attendance/exceptions')) return page([lateRow]);
      if (
        request.path === `/attendance/records/${RECORD}/corrections` &&
        request.method === 'GET'
      ) {
        return { status: 200, body: history() };
      }
      if (request.path === `/attendance/records/${RECORD}/correction-preview`) {
        previews += 1;
        return {
          status: 200,
          body: preview({
            operation: 'REPLACE_TIMES',
            attendanceRecordId: RECORD,
            targetVersion: previews === 1 ? 0 : 1,
            before: {
              checkedInAt: '2099-11-02T07:12:00.123456Z',
              checkedOutAt: '2099-11-02T14:40:00Z',
            },
            after: { checkedInAt: '2099-11-02T07:12:00.123456Z', checkedOutAt: null },
            warnings: ['CHECK_OUT_REMOVED', 'NO_CHECK_OUT', 'ORIGINAL_EVENTS_KEPT'],
          }),
        };
      }
      if (request.path === `/attendance/records/${RECORD}/corrections`) {
        return { status: 409, body: problem('ATTENDANCE_CORRECTION_PREVIEW_STALE', 409) };
      }
      return { status: 200, body: { data: [] } };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <AttendanceReviewPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/attendance-review',
    );
    const card = (await screen.findAllByTestId('attendance-exception'))[0]!;
    await user.click(within(card).getByTestId('attendance-exception-correct'));
    const dialog = await screen.findByTestId('attendance-correction-dialog');
    // Prefilled with the record's current effective values; the original events are shown.
    expect(
      await within(dialog).findByTestId('attendance-correction-current-originals'),
    ).toBeVisible();
    expect(within(dialog).getByLabelText(k.checkIn)).toHaveValue('2099-11-02T08:12');
    await user.click(within(dialog).getByTestId('attendance-correction-no-check-out'));
    await user.selectOptions(within(dialog).getByLabelText(k.reason), k.reasons.DATA_ENTRY_ERROR);
    await user.type(within(dialog).getByLabelText(k.explanation), EXPLANATION);
    await user.click(within(dialog).getByTestId('attendance-correction-preview'));
    const review = await within(dialog).findByTestId('attendance-correction-review');
    // The untouched check-in is sent exactly; the check-out is cleared.
    expect(requests.find((r) => r.path.endsWith('/correction-preview'))?.body).toMatchObject({
      checkedInAt: '2099-11-02T07:12:00.123456Z',
      checkedOutAt: null,
    });
    expect(within(review).getByTestId('attendance-correction-warnings')).toHaveTextContent(
      k.warnings.CHECK_OUT_REMOVED,
    );
    await user.click(within(review).getByTestId('attendance-correction-confirm-check'));
    await user.click(within(review).getByTestId('attendance-correction-commit'));
    // Stale: back to the form with a neutral message; preview again before any commit.
    const error = await within(dialog).findByTestId('attendance-correction-error');
    expect(error).toHaveAttribute('data-kind', 'stale');
    expect(error).toHaveTextContent(c.errors.ATTENDANCE_CORRECTION_PREVIEW_STALE);
    expect(error).not.toHaveTextContent(RECORD);
    expect(within(dialog).getByTestId('attendance-correction-form')).toBeVisible();
    await expectAccessible(dialog);
  });
});

describe.each(['en', 'fr'] as const)('MVP-062 the employee sees a correction (%s)', (locale) => {
  const c = resources[locale].common;

  it('shows the corrected times and “Corrected by the organization”, never who or why', async () => {
    stubApi((request) => {
      if (request.path.startsWith('/me/attendance/exceptions')) return page([]);
      if (request.path.startsWith('/me/attendance')) {
        return page([
          {
            id: RECORD,
            status: 'COMPLETED',
            checkedInAt: '2099-11-02T07:00:00Z',
            checkedOutAt: '2099-11-02T15:00:00Z',
            checkOutAvailable: false,
            checkInRecordingMethod: null,
            checkInSupervisorReasonLocale: null,
            checkInSupervisorReason: null,
            checkOutRecordingMethod: null,
            checkOutSupervisorReasonLocale: null,
            checkOutSupervisorReason: null,
            correctedAt: '2099-11-03T06:00:01Z',
            shift: shift(),
          },
        ]);
      }
      return { status: 200, body: { items: [], nextCursor: null } };
    });
    const { container } = await renderWithSession(
      <MyAttendancePage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/attendance',
    );
    const note = await screen.findByTestId('my-attendance-corrected');
    expect(within(note).getByTestId('my-attendance-corrected-badge')).toHaveTextContent(
      c.attendance.corrected.label,
    );
    expect(within(note).getByTestId('my-attendance-corrected-date')).toBeVisible();
    expect(container).not.toHaveTextContent(EXPLANATION);
    expect(container).not.toHaveTextContent(/administrat/iu);
    await expectAccessible(container);
  });
});
