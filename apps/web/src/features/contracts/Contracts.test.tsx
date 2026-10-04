import { resources } from '@divalhr/localization';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequireRole } from '../hierarchy/RequireRole';
import { EmployeeContractsSection } from './EmployeeContractsSection';
import { ContractTemplatePage } from './ContractTemplatePage';
import { ContractTemplatesPage } from './ContractTemplatesPage';
import { MyContractPage } from './MyContractPage';
import { MyContractsPage } from './MyContractsPage';

const TEMPLATE = '11111111-1111-4111-8111-111111111111';
const DRAFT = '22222222-2222-4222-8222-222222222222';
const APPROVED = '33333333-3333-4333-8333-333333333333';
const EMPLOYEE = '44444444-4444-4444-8444-444444444444';
const CONTRACT = '55555555-5555-4555-8555-555555555555';
const EMPLOYMENT = '66666666-6666-4666-8666-666666666666';
const SNAPSHOT_SHA = 'a'.repeat(64);
const PREVIEW_SHA = 'b'.repeat(64);
const STATEMENT_FR = 'c'.repeat(64);
const STATEMENT_EN = 'd'.repeat(64);
const EVIDENCE_SHA = 'e'.repeat(64);
const STATEMENT_TEXT = {
  fr: 'Je confirme avoir reçu ce contrat et en avoir pris connaissance tel qu’il est affiché. Cette confirmation n’est pas une signature électronique.',
  en: 'I confirm that I have received and reviewed this contract as displayed. This confirmation is not an electronic signature.',
};

const integrity = {
  snapshotSha256: SNAPSHOT_SHA,
  digestAlgorithm: 'SHA-256',
  digestVersion: 1,
  grammarVersion: 1,
  rendererVersion: 1,
};

/** Text that would be markup if it were ever interpreted (A30-2 defense in depth). */
const snapshot = {
  title: 'Contrat de travail',
  blocks: [
    { type: 'h1', text: 'Article 1 : Durée' },
    { type: 'p', text: 'Entre Hôpital Général et Bénédicte Mbuyi <b>gras</b>.' },
    { type: 'li', text: 'Type : Durée indéterminée' },
    { type: 'li', text: 'Début : 1er mars 2026' },
  ],
};

const versionSummary = (id: string, state: string, overrides: Record<string, unknown> = {}) => ({
  id,
  locale: 'fr',
  versionNumber: state === 'DRAFT' ? 2 : 1,
  state,
  placeholders: ['employee.fullName'],
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
  approvedAt: state === 'DRAFT' ? null : '2026-10-01T09:00:00Z',
  retiredAt: null,
  version: 0,
  ...overrides,
});

const template = {
  id: TEMPLATE,
  code: 'CDI-STANDARD',
  name: 'Contrat à durée indéterminée – modèle standard de l’organisation',
  contractType: 'PERMANENT',
  createdAt: '2026-10-01T08:00:00Z',
  versions: [versionSummary(DRAFT, 'DRAFT'), versionSummary(APPROVED, 'APPROVED')],
};

const fullVersion = (id: string, state: string) => ({
  ...versionSummary(id, state),
  templateId: TEMPLATE,
  title: 'Contrat de travail',
  body: '# Article 1\nEntre {{organization.name}} et {{employee.fullName}}.\n- Début : {{contract.startDate}}',
  bodySha256: 'f'.repeat(64),
  grammarVersion: 1,
  digestVersion: 1,
});

const summary = (overrides: Record<string, unknown> = {}) => ({
  id: CONTRACT,
  employmentId: EMPLOYMENT,
  templateId: TEMPLATE,
  templateVersionId: APPROVED,
  contractType: 'PERMANENT',
  locale: 'fr',
  startDate: '2026-10-03',
  endDate: null,
  state: 'ISSUED',
  issuedAt: '2026-10-03T08:00:00Z',
  acknowledgedAt: null,
  voidedAt: null,
  voidReason: null,
  version: 0,
  ...overrides,
});

const contract = (overrides: Record<string, unknown> = {}) => ({
  ...summary(overrides),
  snapshot,
  integrity,
  acknowledgement: null,
  ...overrides,
});

const evidence = {
  acknowledgedAt: '2026-10-04T08:00:00Z',
  statementCode: 'RECEIVED_AND_REVIEWED',
  statementVersion: 1,
  statementLocale: 'fr',
  statementText: STATEMENT_TEXT.fr,
  statementSha256: STATEMENT_FR,
  evidenceSha256: EVIDENCE_SHA,
};

const mine = (overrides: Record<string, unknown> = {}) => ({
  id: CONTRACT,
  contractType: 'PERMANENT',
  locale: 'fr',
  startDate: '2026-10-03',
  endDate: null,
  state: 'ISSUED',
  issuedAt: '2026-10-03T08:00:00Z',
  acknowledgedAt: null,
  snapshot,
  integrity,
  statements: [
    {
      code: 'RECEIVED_AND_REVIEWED',
      version: 1,
      locale: 'fr',
      text: STATEMENT_TEXT.fr,
      sha256: STATEMENT_FR,
    },
    {
      code: 'RECEIVED_AND_REVIEWED',
      version: 1,
      locale: 'en',
      text: STATEMENT_TEXT.en,
      sha256: STATEMENT_EN,
    },
  ],
  acknowledgement: null,
  ...overrides,
});

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-contract-1234',
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
      const reply = route(path, request) ?? { status: 404, body: problem('NOT_FOUND', 404) };
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

const sent = (requests: Recorded[], method: string, suffix: string) =>
  requests.filter((r) => r.method === method && r.url.pathname.endsWith(suffix));

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['fr', 'en'] as const)('MVP-030 contracts (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.contracts;

  it('lists templates with their language lines and creates one with an idempotency key', async () => {
    const requests = stubApi((path, request) => {
      if (path === '/contract-templates' && request.method === 'GET') {
        return {
          status: 200,
          body: {
            items: [
              {
                id: TEMPLATE,
                code: template.code,
                name: template.name,
                contractType: 'PERMANENT',
                createdAt: template.createdAt,
                lines: [
                  {
                    locale: 'fr',
                    approvedVersionId: APPROVED,
                    approvedVersionNumber: 1,
                    draftVersionId: DRAFT,
                  },
                  {
                    locale: 'en',
                    approvedVersionId: null,
                    approvedVersionNumber: null,
                    draftVersionId: null,
                  },
                ],
              },
            ],
            nextCursor: null,
          },
        };
      }
      if (path === '/contract-templates' && request.method === 'POST') {
        return { status: 201, body: { ...template, versions: [] } };
      }
      if (path === `/contract-templates/${TEMPLATE}`) return { status: 200, body: template };
      return undefined;
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <Routes>
        <Route path="/admin/contract-templates" element={<ContractTemplatesPage />} />
        <Route path="/admin/contract-templates/:templateId" element={<ContractTemplatePage />} />
      </Routes>,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/contract-templates',
    );
    const table = await screen.findByTestId('templates');
    expect(table).toHaveTextContent(template.name);
    expect(table).toHaveTextContent(c.employees.contract.PERMANENT);
    await expectAccessible(container);

    const form = screen.getByTestId('create-template');
    await user.click(within(form).getByRole('button', { name: k.templates.create.submit }));
    expect(await within(form).findByTestId('create-problems')).toHaveTextContent(
      k.templates.create.problems.code,
    );
    await user.type(within(form).getByLabelText(k.templates.create.code), 'cdi-2');
    await user.type(within(form).getByLabelText(k.templates.create.name), 'Modèle CDI');
    await user.selectOptions(
      within(form).getByLabelText(k.templates.create.type),
      c.employees.contract.PERMANENT,
    );
    await user.click(within(form).getByRole('button', { name: k.templates.create.submit }));
    expect(await screen.findByTestId('announcer')).toHaveTextContent(k.templates.announce.created);
    const [create] = sent(requests, 'POST', '/contract-templates');
    expect(JSON.parse(create?.body ?? '{}')).toEqual({
      code: 'CDI-2',
      name: 'Modèle CDI',
      contractType: 'PERMANENT',
    });
    expect(create?.headers.get('Idempotency-Key')).toMatch(/^web-/u);
  });

  it('checks a draft with closed reasons and line numbers, then approves with TEXT_VERIFIED', async () => {
    const requests = stubApi((path, request) => {
      if (path === `/contract-templates/${TEMPLATE}`) return { status: 200, body: template };
      if (
        path === `/contract-templates/${TEMPLATE}/versions/${DRAFT}` &&
        request.method === 'GET'
      ) {
        return { status: 200, body: fullVersion(DRAFT, 'DRAFT') };
      }
      if (path === '/contract-templates/validate') {
        return {
          status: 200,
          body: {
            valid: false,
            problems: [
              { reason: 'WEB_ADDRESS', line: 3 },
              { reason: 'BRACES', line: 0 },
            ],
            placeholders: [],
          },
        };
      }
      if (path.endsWith('/approve')) {
        return { status: 200, body: fullVersion(DRAFT, 'APPROVED') };
      }
      return undefined;
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <Routes>
        <Route path="/admin/contract-templates/:templateId" element={<ContractTemplatePage />} />
      </Routes>,
      sessionWithRoles(['tenant-admin']),
      locale,
      `/admin/contract-templates/${TEMPLATE}`,
    );
    const versions = await screen.findByTestId('versions');
    expect(within(versions).getAllByTestId('version')).toHaveLength(2);

    // A new draft for the language without a draft, with a field inserted at the cursor.
    await user.click(screen.getByRole('button', { name: k.versions.newDraft }));
    const editor = await screen.findByTestId('editor');
    await user.type(within(editor).getByLabelText(k.versions.titleLabel), 'Contract');
    await user.type(within(editor).getByLabelText(k.versions.body), 'Hello ');
    await user.selectOptions(
      within(editor).getByLabelText(k.versions.placeholders),
      k.fields['employee.fullName'],
    );
    await user.click(within(editor).getByRole('button', { name: k.versions.insert }));
    expect(within(editor).getByLabelText(k.versions.body)).toHaveValue(
      'Hello {{employee.fullName}}',
    );
    await user.click(within(editor).getByRole('button', { name: k.versions.check }));
    const report = await within(editor).findByTestId('validation');
    expect(report).toHaveTextContent(
      k.versions.problemLine.replace('{{line}}', '3').replace('{{reason}}', k.problems.WEB_ADDRESS),
    );
    expect(report).toHaveTextContent(
      k.versions.problemTitle.replace('{{reason}}', k.problems.BRACES),
    );
    await expectAccessible(container);
    await user.click(within(editor).getByRole('button', { name: k.versions.cancel }));

    // The draft's text is shown as a sample (labels, never employee data); approval needs the tick.
    const [draft] = within(versions).getAllByTestId('version');
    if (!draft) throw new Error('no draft');
    await user.click(within(draft).getByRole('button', { name: k.versions.view }));
    const text = await within(draft).findByTestId('version-text');
    expect(text).toHaveTextContent(`[${k.fields['employee.fullName']}]`);
    await user.click(within(text).getByRole('button', { name: k.versions.approve }));
    expect(within(text).getByRole('alert')).toHaveTextContent(k.versions.tickConfirm);
    expect(sent(requests, 'POST', '/approve')).toHaveLength(0);
    await user.click(within(text).getByLabelText(k.versions.approveConfirm));
    await user.click(within(text).getByRole('button', { name: k.versions.approve }));
    expect(await screen.findByTestId('announcer')).toHaveTextContent(k.templates.announce.approved);
    const [approve] = sent(requests, 'POST', '/approve');
    expect(JSON.parse(approve?.body ?? '{}')).toEqual({
      expectedVersion: 0,
      acknowledgements: ['TEXT_VERIFIED'],
    });
  });

  it('previews the exact text, then issues with the preview version and digest', async () => {
    let issued = false;
    const requests = stubApi((path, request) => {
      if (path === `/employees/${EMPLOYEE}/contracts` && request.method === 'GET') {
        return {
          status: 200,
          body: { items: issued ? [summary()] : [], nextCursor: null },
        };
      }
      if (path === '/contract-templates') {
        return {
          status: 200,
          body: {
            items: [
              {
                id: TEMPLATE,
                code: template.code,
                name: template.name,
                contractType: 'FIXED_TERM',
                createdAt: template.createdAt,
                lines: [
                  {
                    locale: 'fr',
                    approvedVersionId: APPROVED,
                    approvedVersionNumber: 1,
                    draftVersionId: null,
                  },
                ],
              },
            ],
            nextCursor: null,
          },
        };
      }
      if (path.endsWith('/contracts/preview')) {
        return {
          status: 200,
          body: {
            employmentId: EMPLOYMENT,
            employmentVersion: 3,
            templateVersionId: APPROVED,
            contractType: 'FIXED_TERM',
            locale: 'fr',
            startDate: '2026-10-03',
            endDate: '2027-04-02',
            snapshot,
            integrity,
            warnings: ['TYPE_DIFFERS_FROM_CLASSIFICATION'],
            previewDigest: PREVIEW_SHA,
          },
        };
      }
      if (path === `/employees/${EMPLOYEE}/contracts` && request.method === 'POST') {
        issued = true;
        return { status: 201, body: contract({ contractType: 'FIXED_TERM' }) };
      }
      return undefined;
    });
    const changed = vi.fn();
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <EmployeeContractsSection
        employeeId={EMPLOYEE}
        businessDate="2026-10-03"
        revision={0}
        onChanged={changed}
      />,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    const section = await screen.findByTestId('contracts');
    expect(await within(section).findByTestId('contracts-none')).toHaveTextContent(k.issue.none);
    const form = within(section).getByTestId('issue-form');
    await user.selectOptions(within(form).getByLabelText(k.issue.template), [APPROVED]);
    await user.type(within(form).getByLabelText(k.issue.end), '2027-04-02');
    await user.click(within(form).getByRole('button', { name: k.issue.preview }));
    const preview = await within(section).findByTestId('contract-preview');
    await waitFor(() => {
      expect(within(preview).getByRole('heading', { name: k.issue.previewTitle })).toHaveFocus();
    });
    expect(within(preview).getByTestId('contract-warnings')).toHaveTextContent(
      k.issue.warnings.TYPE_DIFFERS_FROM_CLASSIFICATION,
    );
    // Rendered as text: the angle brackets are characters, never an element.
    const document = within(preview).getByTestId('contract-document');
    expect(document).toHaveAttribute('lang', 'fr');
    expect(document).toHaveTextContent('<b>gras</b>');
    expect(document.querySelector('b')).toBeNull();
    expect(within(document).getAllByRole('listitem')).toHaveLength(2);
    await expectAccessible(container);

    await user.click(within(preview).getByRole('button', { name: k.issue.confirm }));
    await waitFor(() => {
      expect(changed).toHaveBeenCalledWith('contracts.issue.announce.issued', {}, false);
    });
    const [issue] = sent(requests, 'POST', `/employees/${EMPLOYEE}/contracts`);
    expect(JSON.parse(issue?.body ?? '{}')).toEqual({
      templateVersionId: APPROVED,
      startDate: '2026-10-03',
      endDate: '2027-04-02',
      expectedEmploymentVersion: 3,
      previewDigest: PREVIEW_SHA,
    });
    expect(await within(section).findByTestId('contract-list')).toHaveTextContent(
      k.issue.state.ISSUED,
    );
  });

  it('shows a closed date problem and voids an issued contract with a reason', async () => {
    const requests = stubApi((path, request) => {
      if (path === `/employees/${EMPLOYEE}/contracts` && request.method === 'GET') {
        return { status: 200, body: { items: [summary()], nextCursor: null } };
      }
      if (path === '/contract-templates')
        return { status: 200, body: { items: [], nextCursor: null } };
      if (path === `/employees/${EMPLOYEE}/contracts/${CONTRACT}`) {
        return { status: 200, body: contract() };
      }
      if (path.endsWith('/void')) {
        return {
          status: 200,
          body: contract({
            state: 'VOID',
            voidedAt: '2026-10-04T08:00:00Z',
            voidReason: 'WRONG_DATA',
            version: 1,
          }),
        };
      }
      return undefined;
    });
    const user = userEvent.setup();
    await renderWithSession(
      <EmployeeContractsSection
        employeeId={EMPLOYEE}
        businessDate="2026-10-03"
        revision={0}
        onChanged={vi.fn()}
      />,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    const section = await screen.findByTestId('contracts');
    expect(await within(section).findByTestId('no-template')).toHaveTextContent(k.issue.noTemplate);
    await user.click(
      await within(section).findByRole('button', { name: c.employees.contract.PERMANENT }),
    );
    const detail = await within(section).findByTestId('contract-detail');
    const voiding = within(detail).getByTestId('void');
    await user.selectOptions(
      within(voiding).getByLabelText(k.issue.voidReason),
      k.issue.voidReasons.WRONG_DATA,
    );
    await user.click(within(voiding).getByRole('button', { name: k.issue.voidConfirm }));
    await waitFor(() => {
      expect(within(section).getByTestId('contract-state')).toHaveTextContent(k.issue.state.VOID);
    });
    const [voided] = sent(requests, 'POST', '/void');
    expect(JSON.parse(voided?.body ?? '{}')).toEqual({
      expectedVersion: 0,
      reasonCode: 'WRONG_DATA',
    });
  });

  it('lets the employee acknowledge receipt with the statement in the interface language', async () => {
    let acknowledged = false;
    const requests = stubApi((path, request) => {
      if (path === `/me/contracts/${CONTRACT}` && request.method === 'GET') {
        return {
          status: 200,
          body: acknowledged ? mine({ state: 'ACKNOWLEDGED', acknowledgement: evidence }) : mine(),
        };
      }
      if (path.endsWith('/acknowledgement')) {
        acknowledged = true;
        return {
          status: 200,
          body: {
            contract: mine({
              state: 'ACKNOWLEDGED',
              acknowledgedAt: evidence.acknowledgedAt,
              acknowledgement: {
                ...evidence,
                statementLocale: locale,
                statementText: STATEMENT_TEXT[locale],
              },
            }),
            alreadyAcknowledged: false,
          },
        };
      }
      return undefined;
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <Routes>
        <Route
          path="/me/contracts/:contractId"
          element={
            <RequireRole
              requiredRole="employee"
              deniedKey="contracts.my.unauthorized"
              signInKey="contracts.my.signInRequired"
            >
              <MyContractPage />
            </RequireRole>
          }
        />
      </Routes>,
      sessionWithRoles(['employee']),
      locale,
      `/me/contracts/${CONTRACT}`,
    );
    const panel = await screen.findByTestId('acknowledge');
    expect(screen.getByTestId('contract-document')).toHaveTextContent(snapshot.title);
    expect(within(panel).getByLabelText(STATEMENT_TEXT[locale])).not.toBeChecked();
    expect(panel).toHaveTextContent(k.my.disclaimer);
    await expectAccessible(container);

    await user.click(within(panel).getByRole('button', { name: k.my.acknowledge }));
    expect(within(panel).getByRole('alert')).toHaveTextContent(k.my.tick);
    expect(sent(requests, 'POST', '/acknowledgement')).toHaveLength(0);
    await user.click(within(panel).getByLabelText(STATEMENT_TEXT[locale]));
    await user.click(within(panel).getByRole('button', { name: k.my.acknowledge }));
    const evidenceBox = await screen.findByTestId('evidence');
    await waitFor(() => {
      expect(within(evidenceBox).getByRole('heading', { name: k.evidence.title })).toHaveFocus();
    });
    expect(screen.getByTestId('announcer')).toHaveTextContent(k.my.acknowledged);
    expect(screen.getByTestId('my-contract-state')).toHaveTextContent(k.issue.state.ACKNOWLEDGED);
    expect(within(evidenceBox).getByTestId('evidence-statement')).toHaveAttribute('lang', locale);
    expect(screen.queryByTestId('acknowledge')).toBeNull();

    const [ack] = sent(requests, 'POST', '/acknowledgement');
    expect(JSON.parse(ack?.body ?? '{}')).toEqual({
      snapshotSha256: SNAPSHOT_SHA,
      snapshotDigestVersion: 1,
      grammarVersion: 1,
      rendererVersion: 1,
      statementCode: 'RECEIVED_AND_REVIEWED',
      statementVersion: 1,
      statementLocale: locale,
      statementSha256: locale === 'fr' ? STATEMENT_FR : STATEMENT_EN,
    });
    expect(ack?.headers.get('Idempotency-Key')).toMatch(/^web-/u);
    await expectAccessible(container);
  });

  it('reloads after a changed contract and explains a missing employee link', async () => {
    stubApi((path, request) => {
      if (path === `/me/contracts/${CONTRACT}` && request.method === 'GET') {
        return { status: 200, body: mine() };
      }
      if (path.endsWith('/acknowledgement')) {
        return { status: 409, body: problem('CONTRACT_ACKNOWLEDGEMENT_CHANGED', 409) };
      }
      if (path === '/me/contracts') {
        return { status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) };
      }
      return undefined;
    });
    const user = userEvent.setup();
    await renderWithSession(
      <Routes>
        <Route path="/me/contracts" element={<MyContractsPage />} />
        <Route path="/me/contracts/:contractId" element={<MyContractPage />} />
      </Routes>,
      sessionWithRoles(['employee']),
      locale,
      `/me/contracts/${CONTRACT}`,
    );
    const panel = await screen.findByTestId('acknowledge');
    await user.click(within(panel).getByLabelText(STATEMENT_TEXT[locale]));
    await user.click(within(panel).getByRole('button', { name: k.my.acknowledge }));
    const alert = await screen.findByTestId('ack-error');
    expect(alert).toHaveTextContent(k.my.changed);
    await waitFor(() => {
      expect(alert).toHaveFocus();
    });
    expect(
      within(await screen.findByTestId('acknowledge')).getByLabelText(STATEMENT_TEXT[locale]),
    ).not.toBeChecked();

    await user.click(screen.getByRole('link', { name: k.my.back }));
    expect(await screen.findByTestId('my-contracts-error')).toHaveTextContent(
      c.errors.EMPLOYEE_LINK_REQUIRED,
    );
    await act(async () => {
      await Promise.resolve();
    });
  });
});

/**
 * A30-3: no contract label, state, button, message or error claims a signature. The word
 * "signature" appears only in the negative disclaimer (the statement itself is server-owned).
 */
describe('MVP-030 terminology (A30-3)', () => {
  const FORBIDDEN = [
    /\bsign(?:s|ed|ing)?\b(?!\s*(?:in|out)\b)/iu,
    /sign contract/iu,
    /contract signed/iu,
    /\bsigner\b/iu,
    /contrat signé/iu,
    /\bsigné(?:e|s|es)?\b/iu,
  ];
  const ALLOWED_SIGNATURE = new Set(['contracts.my.disclaimer']);

  function* strings(node: unknown, path: string): Generator<[string, string]> {
    if (typeof node === 'string') {
      yield [path, node];
    } else if (node && typeof node === 'object') {
      for (const [key, value] of Object.entries(node)) {
        yield* strings(value, path ? `${path}.${key}` : key);
      }
    }
  }

  it.each(['fr', 'en'] as const)('holds for every contract key (%s)', (locale) => {
    const common = resources[locale].common;
    const scanned: [string, string][] = [
      ...strings(common.contracts, 'contracts'),
      ['nav.contractTemplates', common.nav.contractTemplates],
      ['nav.myContracts', common.nav.myContracts],
      ...Object.entries(common.errors)
        .filter(([code]) => code.startsWith('CONTRACT') || code === 'EMPLOYEE_LINK_REQUIRED')
        .map(([code, text]): [string, string] => [`errors.${code}`, text]),
    ];
    expect(scanned.length).toBeGreaterThan(100);
    for (const [key, text] of scanned) {
      for (const pattern of FORBIDDEN) {
        expect(pattern.test(text), `${key}: ${text}`).toBe(false);
      }
      if (/signature/iu.test(text)) {
        expect(ALLOWED_SIGNATURE.has(key), key).toBe(true);
        expect(text).toMatch(/not an electronic signature|n’est pas une signature électronique/u);
      }
    }
    expect(common.contracts.my.acknowledge).toBe(
      locale === 'fr' ? 'Accuser réception' : 'Acknowledge',
    );
    expect(common.contracts.my.acknowledged).toBe(
      locale === 'fr' ? 'Réception confirmée' : 'Acknowledged',
    );
    expect(common.contracts.issue.state.ACKNOWLEDGED).toBe(
      locale === 'fr' ? 'Réception confirmée' : 'Acknowledged',
    );
  });
});
