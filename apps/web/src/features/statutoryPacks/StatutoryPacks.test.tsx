import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import problemSchema from '../../../../../packages/shared-contracts/schemas/problem.schema.json';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { stub, type Recorded, type Reply } from '../payslips/stub';
import { ApplicabilityPanel } from './ApplicabilityPanel';
import { StatutoryPackDetailPage } from './StatutoryPackDetailPage';
import { StatutoryPacksPage } from './StatutoryPacksPage';
import {
  commandsFor,
  needsConfirmation,
  PACK_CODES,
  packFailureOf,
  PACK_STATES,
  versionPath,
} from './statutoryPacks';

const SHA = 'a'.repeat(64);
const ENTITY = '11111111-1111-4111-8111-111111111111';

type State = (typeof PACK_STATES)[number];

function summary(state: State = 'DRAFT', over: Record<string, unknown> = {}) {
  return {
    packCode: 'SYNTHETIC-TEST-UNIT',
    version: 1,
    semanticVersion: '1.0.0',
    country: 'CD',
    applicability: 'GENERAL',
    state,
    effectiveFrom: '2090-01-01',
    effectiveTo: '2090-12-31',
    activeFrom: null,
    evidenceComplete: state !== 'DRAFT',
    lastValidation: state === 'DRAFT' ? null : 'PASSED',
    rowVersion: 3,
    approvedForOperationalPayroll: false,
    ...over,
  };
}

function detail(state: State = 'DRAFT', over: Record<string, unknown> = {}) {
  return {
    summary: summary(state),
    evidence: {
      sourceAuthority: state === 'DRAFT' ? null : 'Synthetic test authority',
      sourceTitle: state === 'DRAFT' ? null : 'Synthetic test source bundle',
      sourcePublishedOn: state === 'DRAFT' ? null : '2026-10-01',
      sourceReference: state === 'DRAFT' ? null : 'https://example.test/synthetic-source',
      sourceBundleSha256: state === 'DRAFT' ? null : SHA,
      evidenceSchemaVersion: state === 'DRAFT' ? null : 1,
    },
    digests: {
      rulesSha256: SHA,
      packSha256: state === 'DRAFT' ? null : SHA,
      fixtureSha256: state === 'DRAFT' ? null : SHA,
    },
    sourceCount: 2,
    caseCount: 3,
    submittedAt: null,
    submittedByCaller: false,
    retireReason: null,
    supersedesVersion: null,
    validationRuns: [],
    review: null,
    history: [],
    ...over,
  };
}

const problem = (code: string, status = 409) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-pack-4242',
});

const PACKS = '/platform/statutory-packs';
const VERSION = `${PACKS}/SYNTHETIC-TEST-UNIT/versions/1`;
const isDetail = (r: Recorded) => r.method === 'GET' && r.path === VERSION;
const isCommand = (r: Recorded, verb: string) =>
  r.method === 'POST' && r.path === `${VERSION}:${verb}`;

async function showDetail(locale: 'en' | 'fr') {
  return renderWithSession(
    <Routes>
      <Route
        path="/admin/statutory-packs/:packCode/:version"
        element={<StatutoryPackDetailPage />}
      />
    </Routes>,
    sessionWithRoles(['platform-admin']),
    locale,
    '/admin/statutory-packs/SYNTHETIC-TEST-UNIT/1',
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('statutory pack lifecycle rules', () => {
  it('offers only the commands the current state allows', () => {
    expect(commandsFor('DRAFT', false)).toEqual(['validate']);
    expect(commandsFor('VALIDATED', false)).toEqual(['submit-review']);
    expect(commandsFor('VALIDATED', true)).toEqual(['approve', 'reject']);
    expect(commandsFor('REVIEWED', true)).toEqual(['schedule', 'activate', 'retire']);
    expect(commandsFor('SCHEDULED', true)).toEqual(['activate', 'retire']);
    expect(commandsFor('ACTIVE', true)).toEqual(['retire']);
    expect(commandsFor('RETIRED', true)).toEqual([]);
  });

  it('asks for a confirmation exactly where the API requires one', () => {
    expect(needsConfirmation('validate')).toBe(false);
    expect(needsConfirmation('submit-review')).toBe(false);
    for (const command of ['approve', 'reject', 'schedule', 'activate', 'retire'] as const) {
      expect(needsConfirmation(command)).toBe(true);
    }
  });

  it('keeps the version path to the code and the integer version', () => {
    expect(versionPath('CD SALARY', 2)).toBe('/admin/statutory-packs/CD%20SALARY/2');
  });

  it('maps every pack code to a kind and a finished message', () => {
    const response = new Response(null, { status: 409 });
    for (const code of PACK_CODES) {
      const failure = packFailureOf(response, { code });
      expect(failure.messageKey).toBe(`errors.${code}`);
    }
    expect(packFailureOf(response, { code: 'STATUTORY_PACK_VERSION_CONFLICT' }).kind).toBe('stale');
    expect(packFailureOf(response, { code: 'STATUTORY_PACK_NOT_FOUND' }).kind).toBe('notFound');
    expect(packFailureOf(response, { code: 'STATUTORY_PACK_SELF_APPROVAL_FORBIDDEN' }).kind).toBe(
      'domain',
    );
  });

  it('covers the eleven pack codes of the shared problem schema, in both languages', () => {
    const schema = problemSchema as { properties: { code: { enum: string[] } } };
    const contract = schema.properties.code.enum.filter((code) =>
      code.startsWith('STATUTORY_PACK_'),
    );
    expect([...contract].sort()).toEqual([...PACK_CODES].sort());
    for (const locale of ['en', 'fr'] as const) {
      const errors = resources[locale].common.errors as Record<string, string | undefined>;
      for (const code of PACK_CODES) {
        expect(errors[code], `${locale} ${code}`).toBeTruthy();
        expect(errors[code]).not.toContain('STATUTORY_PACK');
      }
    }
  });

  it('keeps English and French strings in step', () => {
    const keys = (value: unknown, prefix = ''): string[] =>
      Object.entries(value as Record<string, unknown>).flatMap(([key, inner]) =>
        typeof inner === 'object' && inner !== null
          ? keys(inner, `${prefix}${key}.`)
          : [`${prefix}${key}`],
      );
    expect(keys(resources.fr.common.statutoryPacks).sort()).toEqual(
      keys(resources.en.common.statutoryPacks).sort(),
    );
  });
});

describe.each(['en', 'fr'] as const)('statutory packs list (%s)', (locale) => {
  const p = resources[locale].common.statutoryPacks;

  it('shows the permanent notice, the DRAFT wording and a state badge with text', async () => {
    stub((r): Reply => {
      if (r.method === 'GET' && r.path.startsWith(PACKS)) {
        return {
          status: 200,
          body: {
            items: [
              summary('DRAFT', { packCode: 'CD-SALARY-PREVIEW', version: 1 }),
              summary('ACTIVE', { lastValidation: 'PASSED' }),
            ],
            nextCursor: null,
          },
        };
      }
      return { status: 500 };
    });
    const { container } = await renderWithSession(
      <Routes>
        <Route path="/admin/statutory-packs" element={<StatutoryPacksPage />} />
      </Routes>,
      sessionWithRoles(['platform-admin']),
      locale,
      '/admin/statutory-packs',
    );
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(p.title);
    expect(screen.getByTestId('statutory-pack-legal-notice')).toHaveTextContent(p.notice.heading);
    const rows = await screen.findAllByTestId('statutory-pack-row');
    expect(rows.map((row) => row.getAttribute('data-state'))).toEqual(['DRAFT', 'ACTIVE']);
    expect(rows[0]).toHaveTextContent(p.state.DRAFT);
    expect(
      within(rows[0] as HTMLElement).getByTestId('statutory-pack-draft-notice'),
    ).toHaveTextContent(p.draftNotice);
    expect(rows[1]).toHaveTextContent(p.state.ACTIVE);
    expect(rows[1]).toHaveTextContent(p.validation.PASSED);
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('shows a refusal for a caller without authority', async () => {
    stub(() => ({ status: 403, body: problem('ACCESS_DENIED', 403) }));
    await renderWithSession(
      <Routes>
        <Route path="/admin/statutory-packs" element={<StatutoryPacksPage />} />
      </Routes>,
      sessionWithRoles(['platform-admin']),
      locale,
      '/admin/statutory-packs',
    );
    expect(await screen.findByTestId('statutory-packs-failure')).toHaveTextContent(
      resources[locale].common.errors.ACCESS_DENIED,
    );
  });
});

describe.each(['en', 'fr'] as const)('statutory pack detail (%s)', (locale) => {
  const p = resources[locale].common.statutoryPacks;
  const errors = resources[locale].common.errors as Record<string, string>;

  it('runs technical validation with an idempotency key and the expected version', async () => {
    const requests = stub((r): Reply => {
      if (isDetail(r)) return { status: 200, body: detail('DRAFT') };
      if (isCommand(r, 'validate')) {
        return {
          status: 200,
          body: {
            packCode: 'SYNTHETIC-TEST-UNIT',
            version: 1,
            state: 'VALIDATED',
            rowVersion: 4,
            validationOutcome: 'PASSED',
            revisionVersion: null,
          },
        };
      }
      return { status: 500 };
    });
    const user = userEvent.setup();
    const { container } = await showDetail(locale);
    expect(await screen.findByTestId('statutory-pack-draft-notice')).toHaveTextContent(
      p.draftNotice,
    );
    expect(screen.getByTestId('statutory-pack-evidence-status')).toHaveTextContent(
      p.evidence.incomplete,
    );
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
    await user.click(screen.getByTestId('statutory-pack-action-validate'));
    await user.click(screen.getByTestId('statutory-pack-submit'));
    await waitFor(() => {
      expect(requests.some((r) => isCommand(r, 'validate'))).toBe(true);
    });
    const sent = requests.find((r) => isCommand(r, 'validate'));
    expect(sent?.key).toMatch(/^web-/u);
    expect(JSON.parse(sent?.body ?? '{}')).toEqual({ expectedVersion: 3 });
    expect(await screen.findByTestId('statutory-pack-announcement')).toHaveTextContent(
      p.actions.announce.validate,
    );
  });

  it('requires a confirmation to approve and shows the self-approval refusal', async () => {
    const requests = stub((r): Reply => {
      if (isDetail(r)) {
        return {
          status: 200,
          body: detail('VALIDATED', {
            submittedAt: '2090-01-01T10:00:00Z',
            submittedByCaller: true,
          }),
        };
      }
      if (isCommand(r, 'approve')) {
        return { status: 409, body: problem('STATUTORY_PACK_SELF_APPROVAL_FORBIDDEN') };
      }
      return { status: 500 };
    });
    const user = userEvent.setup();
    await showDetail(locale);
    expect(await screen.findByTestId('statutory-pack-self-hint')).toHaveTextContent(
      p.actions.selfHint,
    );
    await user.click(screen.getByTestId('statutory-pack-action-approve'));
    expect(screen.getByTestId('statutory-pack-submit')).toBeDisabled();
    await user.click(screen.getByTestId('statutory-pack-confirm'));
    await user.click(screen.getByTestId('statutory-pack-submit'));
    expect(await screen.findByTestId('statutory-pack-action-failure')).toHaveTextContent(
      errors.STATUTORY_PACK_SELF_APPROVAL_FORBIDDEN as string,
    );
    const sent = requests.find((r) => isCommand(r, 'approve'));
    expect(JSON.parse(sent?.body ?? '{}')).toEqual({ expectedVersion: 3, confirmed: true });
  });

  it('refuses to reject without a reason and sends the bounded reason code', async () => {
    const requests = stub((r): Reply => {
      if (isDetail(r)) {
        return { status: 200, body: detail('VALIDATED', { submittedAt: '2090-01-01T10:00:00Z' }) };
      }
      if (isCommand(r, 'reject')) {
        return {
          status: 200,
          body: {
            packCode: 'SYNTHETIC-TEST-UNIT',
            version: 1,
            state: 'RETIRED',
            rowVersion: 4,
            validationOutcome: null,
            revisionVersion: 2,
          },
        };
      }
      return { status: 500 };
    });
    const user = userEvent.setup();
    await showDetail(locale);
    await user.click(await screen.findByTestId('statutory-pack-action-reject'));
    await user.click(screen.getByTestId('statutory-pack-confirm'));
    expect(screen.getByTestId('statutory-pack-submit')).toBeDisabled();
    await user.selectOptions(screen.getByTestId('statutory-pack-reason'), 'VALUE_ERROR');
    await user.type(screen.getByTestId('statutory-pack-note'), 'Synthetic note');
    await user.click(screen.getByTestId('statutory-pack-submit'));
    await waitFor(() => {
      expect(requests.some((r) => isCommand(r, 'reject'))).toBe(true);
    });
    const sent = requests.find((r) => isCommand(r, 'reject'));
    expect(JSON.parse(sent?.body ?? '{}')).toEqual({
      expectedVersion: 3,
      confirmed: true,
      reasonCode: 'VALUE_ERROR',
      note: 'Synthetic note',
    });
  });

  it('shows the stale-version refusal as a finished sentence', async () => {
    stub((r): Reply => {
      if (isDetail(r)) return { status: 200, body: detail('DRAFT') };
      if (isCommand(r, 'validate')) {
        return { status: 409, body: problem('STATUTORY_PACK_VERSION_CONFLICT') };
      }
      return { status: 500 };
    });
    const user = userEvent.setup();
    await showDetail(locale);
    await user.click(await screen.findByTestId('statutory-pack-action-validate'));
    await user.click(screen.getByTestId('statutory-pack-submit'));
    const alert = await screen.findByTestId('statutory-pack-action-failure');
    expect(alert).toHaveTextContent(errors.STATUTORY_PACK_VERSION_CONFLICT as string);
    expect(alert).toHaveAttribute('data-kind', 'stale');
    expect(alert).not.toHaveTextContent('STATUTORY_PACK_');
  });

  it('offers no action for a retired version', async () => {
    stub((r): Reply => (isDetail(r) ? { status: 200, body: detail('RETIRED') } : { status: 500 }));
    await showDetail(locale);
    expect(await screen.findByTestId('statutory-pack-actions')).toHaveTextContent(p.actions.none);
  });
});

describe.each(['en', 'fr'] as const)('applicable pack panel (%s)', (locale) => {
  const p = resources[locale].common.statutoryPacks;

  const show = () =>
    renderWithSession(
      <Routes>
        <Route path="/x" element={<ApplicabilityPanel legalEntityId={ENTITY} month="2090-06" />} />
      </Routes>,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/x',
    );

  it('states the refusal plainly when no active pack applies', async () => {
    stub(() => ({
      status: 200,
      body: {
        legalEntityId: ENTITY,
        payrollPeriod: '2090-06',
        country: 'CD',
        applicable: false,
        packCode: null,
        version: null,
        semanticVersion: null,
        state: null,
        effectiveFrom: null,
        effectiveTo: null,
        sourceAuthority: null,
        sourceTitle: null,
        sourcePublishedOn: null,
        sourceReference: null,
      },
    }));
    await show();
    expect(await screen.findByTestId('statutory-pack-applicability-none')).toHaveTextContent(
      p.applicability.none.replace('{{country}}', 'CD').replace('{{period}}', '2090-06'),
    );
  });

  it('shows read-only provenance and nothing else', async () => {
    const requests = stub(() => ({
      status: 200,
      body: {
        legalEntityId: ENTITY,
        payrollPeriod: '2090-06',
        country: 'CD',
        applicable: true,
        packCode: 'SYNTHETIC-TEST-UNIT',
        version: 1,
        semanticVersion: '1.0.0',
        state: 'ACTIVE',
        effectiveFrom: '2090-01-01',
        effectiveTo: null,
        sourceAuthority: 'Synthetic test authority',
        sourceTitle: 'Synthetic test source bundle',
        sourcePublishedOn: '2026-10-01',
        sourceReference: 'https://example.test/synthetic-source',
      },
    }));
    await show();
    const summaryView = await screen.findByTestId('statutory-pack-applicability-summary');
    expect(summaryView).toHaveTextContent('SYNTHETIC-TEST-UNIT');
    expect(summaryView).toHaveTextContent('Synthetic test authority');
    expect(screen.getByTestId('statutory-pack-applicability')).not.toHaveTextContent(
      /rateBp|formula/iu,
    );
    expect(screen.queryByRole('button')).toBeNull();
    expect(requests.every((r) => r.method === 'GET')).toBe(true);
    expect(requests[0]?.path).toContain('payrollPeriod=2090-06');
  });
});
