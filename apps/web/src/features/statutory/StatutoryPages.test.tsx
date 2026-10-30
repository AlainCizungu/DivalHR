import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes, useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import {
  CALC_ID,
  EMPLOYEE_ID,
  EMPLOYEE_ID_2,
  ENTITY_ID,
  RUN_ID,
  VERIFIED_PACK_ID,
  activatedView,
  activationReceipt,
  activationView,
  blockedLine,
  blockedPreview,
  calcDetail,
  calcPage,
  calcReceipt,
  calcSummary,
  calculationPreview,
  draftPackPreview,
  emptyProfileView,
  packDetail,
  packPage,
  packSummary,
  payrollRun,
  problem,
  profilePreview,
  profileReceipt,
  profileView,
  verifiedDetail,
  verifiedSummary,
} from './fixtures';
import { StatutoryProfilePage } from './StatutoryProfilePage';
import { StatutoryRunPage } from './StatutoryRunPage';

type Recorded = { method: string; path: string; body: string; key: string | null };
type Reply = { status: number; body?: unknown };

function stub(handler: (request: Recorded) => Reply) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body: incoming.method === 'GET' ? '' : await incoming.clone().text(),
        key: incoming.headers.get('Idempotency-Key'),
      };
      requests.push(recorded);
      const reply = handler(recorded);
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

const BASE = `/legal-entities/${ENTITY_ID}`;
const RUN = `${BASE}/payroll-runs/${RUN_ID}`;

interface World {
  activation?: unknown;
  packs?: unknown[];
  detail?: unknown;
  versions?: unknown;
}

/** The shared read routes of the run page; a test overrides what it needs first. */
function routes(
  overrides: (request: Recorded) => Reply | undefined = () => undefined,
  world: World = {},
) {
  return (request: Recorded): Reply => {
    const own = overrides(request);
    if (own) return own;
    const { method, path } = request;
    if (method === 'GET' && path.startsWith(`${RUN}/statutory-calculations/${CALC_ID}`)) {
      return { status: 200, body: calcDetail() };
    }
    if (method === 'GET' && path.startsWith(`${RUN}/statutory-calculations`)) {
      return { status: 200, body: world.versions ?? calcPage([]) };
    }
    if (method === 'GET' && path.startsWith(RUN)) return { status: 200, body: payrollRun() };
    if (method === 'GET' && path.startsWith(`${BASE}/statutory-rule-pack`)) {
      return { status: 200, body: world.activation ?? activationView() };
    }
    if (method === 'GET' && path.startsWith('/statutory-rule-packs?')) {
      return { status: 200, body: packPage(world.packs ?? [packSummary()]) };
    }
    if (method === 'GET' && path.startsWith(`/statutory-rule-packs/${VERIFIED_PACK_ID}`)) {
      return { status: 200, body: verifiedDetail() };
    }
    if (method === 'GET' && path.startsWith('/statutory-rule-packs/')) {
      return { status: 200, body: world.detail ?? packDetail() };
    }
    return { status: 500 };
  };
}

function Where() {
  const location = useLocation();
  return <p data-testid="where">{`${location.pathname}${location.search}`}</p>;
}

async function showRun(locale: 'en' | 'fr') {
  return renderWithSession(
    <>
      <Where />
      <Routes>
        <Route
          path="/admin/payroll-runs/:legalEntityId/:runId/statutory"
          element={<StatutoryRunPage />}
        />
        <Route
          path="/admin/people/:employeeId/statutory-profile"
          element={<p data-testid="profile-reached">profile</p>}
        />
      </Routes>
    </>,
    sessionWithRoles(['tenant-admin']),
    locale,
    `/admin/payroll-runs/${ENTITY_ID}/${RUN_ID}/statutory`,
  );
}

async function showProfile(locale: 'en' | 'fr') {
  return renderWithSession(
    <Routes>
      <Route
        path="/admin/people/:employeeId/statutory-profile"
        element={<StatutoryProfilePage />}
      />
    </Routes>,
    sessionWithRoles(['tenant-admin']),
    locale,
    `/admin/people/${EMPLOYEE_ID}/statutory-profile`,
  );
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

function stored(store: Storage): string {
  const values: string[] = [];
  for (let index = 0; index < store.length; index++) {
    const key = store.key(index) ?? '';
    values.push(key, store.getItem(key) ?? '');
  }
  return values.join('\n');
}

const flat = (text: string | null) => (text ?? '').replace(/\s/gu, ' ');
const FORBIDDEN_CONTROL =
  /payslip|approve|disburse|pay now|bulletin|approuver|verser|payer maintenant|valider la paie/iu;

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

describe.each(['en', 'fr'] as const)('MVP-094B rule-pack panel (%s)', (locale) => {
  const k = resources[locale].common.statutory;

  it('shows the DRAFT pack as professional verification required, with sources and no activation', async () => {
    const requests = stub(routes());
    const { container } = await showRun(locale);
    const state = await screen.findByTestId('statutory-verification-required');
    expect(state).toHaveTextContent(k.verificationRequired);
    expect(screen.getByTestId('statutory-label')).toHaveTextContent(k.label);
    expect(screen.getByTestId('statutory-rule-pack-label')).toHaveTextContent(k.label);
    expect(screen.getByTestId('statutory-calculation-label')).toHaveTextContent(k.label);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      locale === 'en'
        ? 'Statutory preview September 2026, CDF'
        : 'Aperçu statutaire septembre 2026, CDF',
    );
    const pack = await screen.findByTestId('statutory-pack');
    expect(pack).toHaveAttribute('data-status', 'DRAFT');
    expect(within(pack).getByTestId('statutory-pack-status')).toHaveTextContent(k.packStatus.DRAFT);
    // Sources: official links open in a new tab; DRAFT sources were never hashed.
    const links = await within(pack).findAllByTestId('statutory-source-link');
    expect(links).toHaveLength(2);
    for (const link of links) {
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
      expect(link.getAttribute('href')).toMatch(/^https:\/\/(cnss\.cd|dgi\.gouv\.cd)\//u);
    }
    expect(within(pack).getAllByTestId('statutory-source-unhashed')[0]).toHaveTextContent(
      k.source.unhashed,
    );
    expect(within(pack).queryByTestId('statutory-source-hash')).toBeNull();
    expect(within(pack).getByTestId('statutory-pack-interval')).toHaveTextContent(/2026/u);
    // The activation is impossible for a DRAFT pack, and says why.
    expect(within(pack).getByTestId('statutory-activate')).toBeDisabled();
    expect(within(pack).getByTestId('statutory-activation-ack')).toBeDisabled();
    expect(within(pack).getByTestId('statutory-activation-month')).toBeDisabled();
    expect(within(pack).getByTestId('statutory-activation-why')).toHaveTextContent(
      k.activation.whyNotVerified,
    );
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    expect(requests.find((r) => r.path.startsWith(`${BASE}/statutory-rule-pack`))?.path).toContain(
      'month=2026-09',
    );
    await expectAccessible(container);
  });

  it('shows hashes and reviewer evidence of a verified pack, and loads more packs by cursor', async () => {
    const user = userEvent.setup();
    let page = 0;
    const requests = stub(
      routes((request) => {
        if (request.method === 'GET' && request.path.startsWith('/statutory-rule-packs?')) {
          page += 1;
          return request.path.includes('cursor=')
            ? { status: 200, body: { items: [packSummary()], nextCursor: null } }
            : { status: 200, body: { items: [verifiedSummary()], nextCursor: 'opaque-packs' } };
        }
        return undefined;
      }),
    );
    await showRun(locale);
    const pack = await screen.findByTestId('statutory-pack');
    expect(pack).toHaveAttribute('data-status', 'VERIFIED');
    expect(await within(pack).findByTestId('statutory-pack-reviewer')).toHaveTextContent(
      'Synthetic Test Reviewer',
    );
    expect(within(pack).getAllByTestId('statutory-source-hash')[0]).toHaveTextContent(
      'f'.repeat(64),
    );
    await user.click(screen.getByTestId('statutory-packs-more'));
    await waitFor(() => {
      expect(screen.getAllByTestId('statutory-pack')).toHaveLength(2);
    });
    expect(requests.some((r) => r.path.includes('cursor=opaque-packs'))).toBe(true);
    expect(page).toBe(2);
  });

  it('activates a verified pack only after the acknowledgement, with an Idempotency-Key reused on retry', async () => {
    const user = userEvent.setup();
    let activations = 0;
    let view: unknown = activationView();
    const requests = stub(
      routes(
        (request) => {
          if (
            request.method === 'POST' &&
            request.path.endsWith('/statutory-rule-pack/activation')
          ) {
            activations += 1;
            if (activations === 1) return { status: 429, body: problem('RATE_LIMITED', 429) };
            view = activatedView();
            return { status: 201, body: activationReceipt() };
          }
          if (request.method === 'GET' && request.path.startsWith(`${BASE}/statutory-rule-pack`)) {
            return { status: 200, body: view };
          }
          return undefined;
        },
        { packs: [verifiedSummary()] },
      ),
    );
    const { container } = await showRun(locale);
    const pack = await screen.findByTestId('statutory-pack');
    const activate = within(pack).getByTestId('statutory-activate');
    const month = within(pack).getByTestId<HTMLInputElement>('statutory-activation-month');
    expect(month.value).toBe('2026-09');
    // Verified pack, but no acknowledgement yet: disabled, with the reason.
    expect(activate).toBeDisabled();
    expect(within(pack).getByTestId('statutory-activation-why')).toHaveTextContent(
      k.activation.whyAcknowledge,
    );
    await user.click(within(pack).getByRole('checkbox', { name: k.activation.acknowledge }));
    expect(activate).toBeEnabled();
    expect(within(pack).queryByTestId('statutory-activation-why')).toBeNull();
    await expectAccessible(container);
    await user.click(activate);
    const failure = await screen.findByTestId('statutory-activation-failure');
    expect(failure).toHaveTextContent(resources[locale].common.errors.RATE_LIMITED);
    expect(failure).toHaveFocus();
    await user.click(activate);
    await waitFor(() => {
      expect(screen.getByTestId('statutory-announcer')).toHaveTextContent(k.activation.announce);
    });
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[0]?.key).toMatch(/^web-/u);
    expect(posts[1]?.key).toBe(posts[0]?.key);
    expect(JSON.parse(posts[0]?.body ?? '{}')).toEqual({
      packId: VERIFIED_PACK_ID,
      fromMonth: '2026-09',
      expectedVersion: 0,
      acknowledged: true,
    });
    // Afterwards the pack is in effect and the verification state is gone.
    expect(await screen.findByTestId('statutory-in-effect')).toHaveTextContent('SYNTHETIC-TEST-1');
    expect(screen.queryByTestId('statutory-verification-required')).toBeNull();
    expect(screen.getAllByTestId('statutory-activation-row')).toHaveLength(1);
  });

  it('shows the domain error when the pack is not applicable, and keeps the form', async () => {
    const user = userEvent.setup();
    stub(
      routes(
        (request) =>
          request.method === 'POST'
            ? { status: 409, body: problem('STATUTORY_RULE_PACK_NOT_APPLICABLE', 409) }
            : undefined,
        { packs: [verifiedSummary()] },
      ),
    );
    await showRun(locale);
    const pack = await screen.findByTestId('statutory-pack');
    await user.click(within(pack).getByRole('checkbox', { name: k.activation.acknowledge }));
    await user.click(within(pack).getByTestId('statutory-activate'));
    const failure = await screen.findByTestId('statutory-activation-failure');
    expect(failure).toHaveAttribute('data-code', 'STATUTORY_RULE_PACK_NOT_APPLICABLE');
    expect(failure).toHaveTextContent(
      resources[locale].common.errors.STATUTORY_RULE_PACK_NOT_APPLICABLE,
    );
    expect(within(pack).getByTestId('statutory-activate')).toBeEnabled();
  });
});

describe.each(['en', 'fr'] as const)('MVP-094B statutory calculation (%s)', (locale) => {
  const k = resources[locale].common.statutory;
  const world: World = { packs: [verifiedSummary()], activation: activatedView() };
  const money = (value: string) =>
    locale === 'en'
      ? new RegExp(`CDF\\s${value}`, 'u')
      : new RegExp(`${value.replace(/,/gu, '\\s')}\\sCDF`, 'u');

  it('previews a READY calculation: aggregates, per-employee table, explanation and create', async () => {
    const user = userEvent.setup();
    const requests = stub(
      routes((request) => {
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations/preview')) {
          return { status: 200, body: calculationPreview() };
        }
        return undefined;
      }, world),
    );
    const { container } = await showRun(locale);
    await screen.findByTestId('statutory-in-effect');
    await user.click(screen.getByTestId('statutory-preview-button'));
    const panel = await screen.findByTestId('statutory-preview');
    expect(
      screen.getByRole('heading', {
        level: 3,
        name: new RegExp(k.preview.heading.split('{{')[0] ?? '', 'u'),
      }),
    ).toHaveFocus();
    expect(JSON.parse(requests.find((r) => r.path.endsWith('/preview'))?.body ?? 'x')).toEqual({});
    expect(within(panel).getByTestId('statutory-preview-label')).toHaveTextContent(k.label);
    expect(within(panel).getByTestId('statutory-preview-status')).toHaveTextContent(
      k.previewStatus.READY,
    );
    expect(within(panel).getByTestId('statutory-preview-pack')).toHaveTextContent(
      'SYNTHETIC-TEST-1',
    );
    expect(within(panel).getByTestId('statutory-preview-supported')).toHaveTextContent('1');
    expect(within(panel).getByTestId('statutory-preview-excluded')).toHaveTextContent('1');
    expect(within(panel).getByTestId('statutory-preview-blocked')).toHaveTextContent('0');
    // The five aggregates, exactly as the server returned them.
    const totals = within(panel).getByTestId('statutory-preview-totals');
    expect(
      flat(within(totals).getByTestId('statutory-total-contractualGross').textContent),
    ).toMatch(money('1,500,000'));
    expect(
      flat(within(totals).getByTestId('statutory-total-employeeDeductions').textContent),
    ).toMatch(money('187,500'));
    expect(
      flat(within(totals).getByTestId('statutory-total-employerContributions').textContent),
    ).toMatch(money('150,000'));
    expect(flat(within(totals).getByTestId('statutory-total-previewNet').textContent)).toMatch(
      money('1,312,500'),
    );
    expect(flat(within(totals).getByTestId('statutory-total-employerCost').textContent)).toMatch(
      money('1,650,000'),
    );
    for (const label of [
      resources[locale].common.payrollRuns.contractualGross,
      k.totals.employeeDeductions,
      k.totals.employerContributions,
      k.totals.previewNet,
      k.totals.employerCost,
    ]) {
      expect(totals).toHaveTextContent(label);
      expect(within(panel).getByRole('columnheader', { name: label })).toBeInTheDocument();
    }
    // Semantic per-employee table.
    const rows = within(panel).getAllByTestId('statutory-line');
    expect(rows).toHaveLength(2);
    expect(within(rows[0] as HTMLElement).getByRole('rowheader')).toHaveTextContent('E-0001');
    expect(
      flat(within(rows[0] as HTMLElement).getByTestId('statutory-line-net').textContent),
    ).toMatch(money('812,500'));
    expect(
      flat(within(rows[0] as HTMLElement).getByTestId('statutory-line-cost').textContent),
    ).toMatch(money('1,050,000'));
    expect(rows[1]).toHaveAttribute('data-excluded', 'true');
    expect(rows[1]).toHaveTextContent(k.lines.excluded);
    expect(within(panel).getByTestId('statutory-create-confirm')).toBeEnabled();
    expect(within(panel).queryByTestId('statutory-create-why')).toBeNull();

    // The expandable explanation names every component, base, rate, brackets, rounding, source.
    const details = await within(panel).findAllByTestId('statutory-explanation');
    expect(details).toHaveLength(1);
    const explanation = details[0] as HTMLElement;
    expect(explanation.tagName).toBe('DETAILS');
    expect(explanation).not.toHaveAttribute('open');
    await user.click(
      within(explanation).getByText(k.explain.summary.replace('{{number}}', 'E-0001')),
    );
    expect(explanation).toHaveAttribute('open');
    const components = within(explanation).getAllByTestId('statutory-component');
    expect(components.map((c) => c.getAttribute('data-component-id'))).toEqual([
      'CNSS_EMPLOYEE_PENSION',
      'CNSS_EMPLOYER_PENSION',
      'IRPP_SALARY_WITHHOLDING',
    ]);
    const pension = components[0] as HTMLElement;
    expect(pension).toHaveTextContent(
      (k.component as Record<string, string>).CNSS_EMPLOYEE_PENSION ?? '',
    );
    await waitFor(() => {
      expect(within(pension).getByTestId('statutory-component-method')).toHaveTextContent(
        locale === 'en' ? 'Flat rate of 5%' : /Taux fixe de 5\s%/u,
      );
    });
    expect(flat(within(pension).getByTestId('statutory-component-base').textContent)).toMatch(
      money('1,000,000'),
    );
    expect(within(pension).getByTestId('statutory-component-base')).toHaveTextContent(
      k.baseKind.GROSS,
    );
    expect(pension).toHaveTextContent(k.rounding.HALF_UP);
    expect(pension).toHaveTextContent(k.party.EMPLOYEE);
    expect(pension).toHaveTextContent(k.explanation.RATE_APPLIED);
    const source = within(pension).getByTestId('statutory-component-source');
    expect(source).toHaveTextContent('CNSS contribution rates (synthetic title)');
    expect(within(source).getByRole('link')).toHaveAttribute(
      'href',
      'https://cnss.cd/?page_id=1388',
    );
    expect(within(source).getByRole('link')).toHaveAttribute('rel', 'noopener noreferrer');
    expect(within(components[1] as HTMLElement).getByText(k.party.EMPLOYER)).toBeInTheDocument();
    const tax = components[2] as HTMLElement;
    expect(within(tax).getAllByTestId('statutory-source-link')[0]).toHaveAttribute(
      'href',
      'https://dgi.gouv.cd/impot-sur-le-revenu-des-personnes-physiques-irpp/',
    );
    expect(within(tax).getByTestId('statutory-brackets').querySelectorAll('li')).toHaveLength(3);
    expect(within(tax).getByTestId('statutory-cap')).toHaveTextContent(/30\s?%/u);
    expect(flat(within(tax).getByTestId('statutory-trace-annual').textContent)).toMatch(
      money('12,000,000'),
    );
    expect(within(tax).getByTestId('statutory-trace-slices').querySelectorAll('li')).toHaveLength(
      2,
    );
    expect(within(tax).getByTestId('statutory-trace-numerator')).toHaveTextContent('15141600000');
    expect(within(tax).getByTestId('statutory-trace-cap')).toHaveTextContent(k.no);
    expect(tax).toHaveTextContent(k.explanation.PROGRESSIVE_APPLIED);
    await expectAccessible(container);
  });

  it('operates the explanation by keyboard', async () => {
    const user = userEvent.setup();
    stub(
      routes((request) => {
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations/preview')) {
          return { status: 200, body: calculationPreview() };
        }
        return undefined;
      }, world),
    );
    await showRun(locale);
    await screen.findByTestId('statutory-in-effect');
    await user.click(screen.getByTestId('statutory-preview-button'));
    const details = (await screen.findAllByTestId('statutory-explanation'))[0] as HTMLElement;
    const summary = details.querySelector('summary') as HTMLElement;
    // A native summary: reachable by Tab with the name of the employee, and toggled by Enter or
    // Space in every browser (proved in the Playwright journey); here the activation is the click
    // that those keys produce, and the keyboard path reaches the control without any trap.
    expect(summary.tagName).toBe('SUMMARY');
    expect(summary).not.toHaveAttribute('tabindex');
    expect(summary).toHaveAccessibleName(k.explain.summary.replace('{{number}}', 'E-0001'));
    await user.tab();
    let reached = false;
    for (let step = 0; step < 40 && !reached; step++) {
      reached = document.activeElement === summary;
      if (!reached) await user.tab();
    }
    expect(reached).toBe(true);
    expect(details).not.toHaveAttribute('open');
    summary.click();
    expect(details).toHaveAttribute('open');
    summary.click();
    expect(details).not.toHaveAttribute('open');
  });

  it('creates with an Idempotency-Key reused on retry, shows the frozen calculation, and no payslip, approval or payment control', async () => {
    const user = userEvent.setup();
    let creates = 0;
    let versions: unknown = calcPage([]);
    const requests = stub(
      routes((request) => {
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations/preview')) {
          return { status: 200, body: calculationPreview() };
        }
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations')) {
          creates += 1;
          if (creates === 1) return { status: 429, body: problem('RATE_LIMITED', 429) };
          versions = calcPage([calcSummary()]);
          return { status: 201, body: calcReceipt() };
        }
        if (request.method === 'GET' && request.path.startsWith(`${RUN}/statutory-calculations?`)) {
          return { status: 200, body: versions };
        }
        return undefined;
      }, world),
    );
    const { container } = await showRun(locale);
    await screen.findByTestId('statutory-in-effect');
    await user.click(screen.getByTestId('statutory-preview-button'));
    await user.click(await screen.findByTestId('statutory-create-confirm'));
    const failure = await screen.findByTestId('statutory-calculation-failure');
    expect(failure).toHaveTextContent(resources[locale].common.errors.RATE_LIMITED);
    expect(failure).toHaveFocus();
    await user.click(screen.getByTestId('statutory-create-confirm'));
    const frozen = await screen.findByTestId('statutory-frozen');
    await waitFor(() => {
      expect(within(frozen).getByTestId('statutory-frozen-totals')).toBeInTheDocument();
    });
    expect(screen.getByTestId('statutory-calculation-announcer')).toHaveTextContent(
      k.create.announce,
    );
    const posts = requests.filter(
      (r) => r.method === 'POST' && r.path.endsWith('/statutory-calculations'),
    );
    expect(posts).toHaveLength(2);
    expect(posts[0]?.key).toMatch(/^web-/u);
    expect(posts[1]?.key).toBe(posts[0]?.key);
    expect(posts[1]?.body).toBe(posts[0]?.body);
    expect(JSON.parse(posts[0]?.body ?? '{}')).toEqual({
      expectedVersion: 0,
      sourceRunDigest: 'a'.repeat(64),
      profileDigest: 'b'.repeat(64),
      rulePackDigest: 'c'.repeat(64),
      calculationDigest: 'd'.repeat(64),
      expiresAt: '2099-01-01T10:15:00Z',
      confirmed: true,
    });
    // The versions list and the frozen view, with the statutory label.
    expect(await screen.findAllByTestId('statutory-version-row')).toHaveLength(1);
    expect(within(frozen).getByTestId('statutory-frozen-label')).toHaveTextContent(k.label);
    expect(within(frozen).getAllByTestId('statutory-line')).toHaveLength(1);
    const frozenDetails = await within(frozen).findAllByTestId('statutory-explanation');
    expect(frozenDetails).toHaveLength(1);
    // No approve, payslip, pay or disburse control anywhere, and nothing in URL or storage.
    expect(screen.queryByRole('button', { name: FORBIDDEN_CONTROL })).toBeNull();
    expect(screen.queryByRole('link', { name: FORBIDDEN_CONTROL })).toBeNull();
    expect(container.textContent).not.toMatch(/\bpayroll\.[a-z]|statutory\.[a-z]/u);
    const where = screen.getByTestId('where').textContent;
    expect(where).toBe(`/admin/payroll-runs/${ENTITY_ID}/${RUN_ID}/statutory`);
    expect(stored(window.localStorage)).not.toMatch(/1500000|187500|812500|E-000/u);
    expect(stored(window.sessionStorage)).not.toMatch(/1500000|187500|812500|E-000/u);
  });

  it('a stale preview clears the preview and the next attempt uses a new key', async () => {
    const user = userEvent.setup();
    let creates = 0;
    const requests = stub(
      routes((request) => {
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations/preview')) {
          return { status: 200, body: calculationPreview() };
        }
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations')) {
          creates += 1;
          return creates === 1
            ? { status: 409, body: problem('STATUTORY_PREVIEW_STALE', 409) }
            : { status: 201, body: calcReceipt() };
        }
        return undefined;
      }, world),
    );
    await showRun(locale);
    await screen.findByTestId('statutory-in-effect');
    await user.click(screen.getByTestId('statutory-preview-button'));
    await user.click(await screen.findByTestId('statutory-create-confirm'));
    const failure = await screen.findByTestId('statutory-calculation-failure');
    expect(failure).toHaveAttribute('data-code', 'STATUTORY_PREVIEW_STALE');
    expect(failure).toHaveTextContent(resources[locale].common.errors.STATUTORY_PREVIEW_STALE);
    expect(screen.queryByTestId('statutory-create-confirm')).toBeNull();
    await user.click(screen.getByTestId('statutory-preview-button'));
    await user.click(await screen.findByTestId('statutory-create-confirm'));
    await screen.findByTestId('statutory-frozen');
    const posts = requests.filter(
      (r) => r.method === 'POST' && r.path.endsWith('/statutory-calculations'),
    );
    expect(posts[1]?.key).not.toBe(posts[0]?.key);
  });

  it('a no-change creation is its own error, not a stale preview', async () => {
    const user = userEvent.setup();
    stub(
      routes((request) => {
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations/preview')) {
          return { status: 200, body: calculationPreview() };
        }
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations')) {
          return { status: 409, body: problem('STATUTORY_CALCULATION_NO_CHANGE', 409) };
        }
        return undefined;
      }, world),
    );
    await showRun(locale);
    await screen.findByTestId('statutory-in-effect');
    await user.click(screen.getByTestId('statutory-preview-button'));
    await user.click(await screen.findByTestId('statutory-create-confirm'));
    const failure = await screen.findByTestId('statutory-calculation-failure');
    expect(failure).toHaveAttribute('data-code', 'STATUTORY_CALCULATION_NO_CHANGE');
    expect(failure).toHaveAttribute('data-kind', 'domain');
  });

  it('BLOCKED: lists employee blockers with a link to each profile page, and cannot create', async () => {
    const user = userEvent.setup();
    const requests = stub(
      routes((request) => {
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations/preview')) {
          return {
            status: 200,
            body: blockedPreview({
              lines: [
                calculationPreview().lines[0],
                blockedLine(['PROFILE_MISSING', 'UNMODELED_EARNINGS']),
              ],
              supportedCount: 1,
              excludedCount: 0,
            }),
          };
        }
        return undefined;
      }, world),
    );
    const { container } = await showRun(locale);
    await screen.findByTestId('statutory-in-effect');
    await user.click(screen.getByTestId('statutory-preview-button'));
    const panel = await screen.findByTestId('statutory-preview');
    expect(within(panel).getByTestId('statutory-preview-status')).toHaveTextContent(
      k.previewStatus.BLOCKED,
    );
    expect(within(panel).getByTestId('statutory-preview-blocked')).toHaveTextContent('1');
    const summary = within(panel).getByTestId('statutory-employee-blockers');
    const entry = within(summary).getByTestId('statutory-employee-blocker');
    expect(entry).toHaveTextContent('E-0002');
    expect(entry).toHaveTextContent(k.lineBlockers.PROFILE_MISSING);
    expect(entry).toHaveTextContent(k.lineBlockers.UNMODELED_EARNINGS);
    // Only a blocker the profile page can resolve gets a link.
    const links = within(summary).getAllByTestId('statutory-profile-link');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute('href', `/admin/people/${EMPLOYEE_ID_2}/statutory-profile`);
    expect(links[0]).toHaveAccessibleName(
      `${k.lines.openProfile} ${k.lines.openProfileContext.replace('{{number}}', 'E-0002')}`,
    );
    expect(within(panel).getAllByTestId('statutory-line')[1]).toHaveAttribute(
      'data-blocked',
      'true',
    );
    // Nothing can be created, and the reason is described.
    const create = within(panel).getByTestId('statutory-create-confirm');
    expect(create).toBeDisabled();
    expect(create).toHaveAccessibleDescription(k.preview.whyBlocked);
    expect(within(panel).getByTestId('statutory-create-why')).toHaveTextContent(
      k.preview.whyBlocked,
    );
    expect(requests.some((r) => r.method === 'POST' && !r.path.endsWith('/preview'))).toBe(false);
    await expectAccessible(container);
    await user.click(links[0] as HTMLElement);
    expect(await screen.findByTestId('profile-reached')).toBeInTheDocument();
    expect(screen.getByTestId('where')).toHaveTextContent(
      `/admin/people/${EMPLOYEE_ID_2}/statutory-profile`,
    );
  });

  it('BLOCKED by a DRAFT pack: verification required, no amount, no total, nothing to create', async () => {
    const user = userEvent.setup();
    stub(
      routes(
        (request) =>
          request.method === 'POST' && request.path.endsWith('/statutory-calculations/preview')
            ? { status: 200, body: draftPackPreview() }
            : undefined,
        {},
      ),
    );
    const { container } = await showRun(locale);
    await screen.findByTestId('statutory-verification-required');
    await user.click(screen.getByTestId('statutory-preview-button'));
    const panel = await screen.findByTestId('statutory-preview');
    expect(within(panel).getByTestId('statutory-preview-verification')).toHaveTextContent(
      k.verificationRequired,
    );
    expect(within(panel).getByTestId('statutory-run-blocker')).toHaveTextContent(
      k.runBlockers.RULE_PACK_NOT_VERIFIED,
    );
    expect(within(panel).getByTestId('statutory-preview-pack')).toHaveTextContent(
      k.packStatus.DRAFT,
    );
    expect(within(panel).queryByTestId('statutory-preview-totals')).toBeNull();
    expect(within(panel).getByTestId('statutory-preview-no-totals')).toHaveTextContent(
      k.preview.noTotals,
    );
    expect(within(panel).getByTestId('statutory-create-confirm')).toBeDisabled();
    expect(within(panel).queryByTestId('statutory-explanation')).toBeNull();
    await expectAccessible(container);
  });

  it.each([
    'SOURCE_RUN_NOT_CURRENT',
    'CURRENCY_NOT_SUPPORTED',
    'RULE_PACK_MISSING',
    'RULE_PACK_NOT_VERIFIED',
  ] as const)('explains the run blocker %s in plain words', async (blocker) => {
    const user = userEvent.setup();
    stub(
      routes(
        (request) =>
          request.method === 'POST' && request.path.endsWith('/statutory-calculations/preview')
            ? {
                status: 200,
                body: blockedPreview({ runBlockers: [blocker], totals: null, pack: null }),
              }
            : undefined,
        {},
      ),
    );
    await showRun(locale);
    await screen.findByTestId('statutory-verification-required');
    await user.click(screen.getByTestId('statutory-preview-button'));
    const item = await screen.findByTestId('statutory-run-blocker');
    expect(item).toHaveAttribute('data-code', blocker);
    expect(item).toHaveTextContent(k.runBlockers[blocker]);
    expect(item.textContent).not.toContain(blocker);
  });

  it('NO_CHANGE: explains the identical calculation and offers no creation', async () => {
    const user = userEvent.setup();
    stub(
      routes((request) => {
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations/preview')) {
          return {
            status: 200,
            body: calculationPreview({
              status: 'NO_CHANGE',
              existingCalculation: calcSummary(),
              calculationDigest: null,
              expiresAt: null,
            }),
          };
        }
        return undefined;
      }, world),
    );
    await showRun(locale);
    await screen.findByTestId('statutory-in-effect');
    await user.click(screen.getByTestId('statutory-preview-button'));
    expect(await screen.findByTestId('statutory-preview-identical')).toHaveTextContent(
      k.preview.identical,
    );
    expect(screen.getByTestId('statutory-preview-status')).toHaveTextContent(
      k.previewStatus.NO_CHANGE,
    );
    const create = screen.getByTestId('statutory-create-confirm');
    expect(create).toBeDisabled();
    expect(screen.getByTestId('statutory-create-why')).toHaveTextContent(k.preview.whyNoChange);
  });

  it('lists the immutable versions, opens one, and loads more lines by cursor', async () => {
    const user = userEvent.setup();
    const requests = stub(
      routes(
        (request) => {
          if (request.path.startsWith(`${RUN}/statutory-calculations/${CALC_ID}`)) {
            return request.path.includes('cursor=')
              ? {
                  status: 200,
                  body: calcDetail({
                    lines: [
                      {
                        ...calcDetail().lines[0],
                        employeeId: EMPLOYEE_ID_2,
                        employeeNumber: 'E-0002',
                      },
                    ],
                    nextCursor: null,
                  }),
                }
              : { status: 200, body: calcDetail({ nextCursor: 'opaque-lines' }) };
          }
          return undefined;
        },
        {
          ...world,
          versions: calcPage([
            calcSummary(),
            calcSummary({
              calculationId: '88888888-0000-4000-8000-000000000002',
              version: 2,
              status: 'SUPERSEDED',
            }),
          ]),
        },
      ),
    );
    const { container } = await showRun(locale);
    const rows = await screen.findAllByTestId('statutory-version-row');
    expect(rows.map((row) => row.getAttribute('data-status'))).toEqual(['DRAFT', 'SUPERSEDED']);
    expect(requests.find((r) => r.path.includes('/statutory-calculations?'))?.path).toContain(
      'limit=25',
    );
    await user.click(within(rows[0] as HTMLElement).getByTestId('statutory-version-open'));
    const frozen = await screen.findByTestId('statutory-frozen');
    await waitFor(() => {
      expect(within(frozen).getByTestId('statutory-frozen-lines')).toBeInTheDocument();
    });
    expect(within(frozen).getAllByRole('heading', { level: 3 })[0]).toHaveFocus();
    // MVP-094C: only a current (DRAFT) calculation links to the approval review, by opaque ids.
    const approval = within(frozen).getByTestId('statutory-approval-link');
    expect(approval.getAttribute('href')).toMatch(
      new RegExp(
        `^/admin/payroll-runs/${ENTITY_ID}/${RUN_ID}/statutory/[0-9a-f-]{36}/approval$`,
        'u',
      ),
    );
    expect(within(frozen).getAllByTestId('statutory-line')).toHaveLength(1);
    await user.click(within(frozen).getByTestId('statutory-frozen-more'));
    await waitFor(() => {
      expect(within(frozen).getAllByTestId('statutory-line')).toHaveLength(2);
    });
    expect(requests.some((r) => r.path.includes('cursor=opaque-lines'))).toBe(true);
    await expectAccessible(container);
  });

  it('shows a safe not-found state for an unknown or another tenant’s run, with no panel', async () => {
    stub(() => ({ status: 404, body: problem('PAYROLL_RUN_NOT_FOUND', 404) }));
    await showRun(locale);
    const alert = await screen.findByTestId('statutory-run-error');
    expect(alert).toHaveAttribute('data-kind', 'notFound');
    expect(alert).toHaveAttribute('data-code', 'PAYROLL_RUN_NOT_FOUND');
    expect(alert).toHaveTextContent(resources[locale].common.errors.PAYROLL_RUN_NOT_FOUND);
    expect(screen.queryByTestId('statutory-rule-pack')).toBeNull();
    expect(screen.queryByTestId('statutory-calculation')).toBeNull();
    expect(screen.getByTestId('statutory-label')).toHaveTextContent(k.label);
  });

  it('keeps the responsive structure that lets long labels wrap at 320, 390 and 640 px', async () => {
    const user = userEvent.setup();
    stub(
      routes((request) => {
        if (request.method === 'POST' && request.path.endsWith('/statutory-calculations/preview')) {
          return { status: 200, body: calculationPreview() };
        }
        return undefined;
      }, world),
    );
    const { container } = await showRun(locale);
    await screen.findByTestId('statutory-in-effect');
    await user.click(screen.getByTestId('statutory-preview-button'));
    const panel = await screen.findByTestId('statutory-preview');
    // Wide tables scroll inside a labelled, focusable region, never the page.
    const regions = container.querySelectorAll('.table-wrapper[role="region"]');
    expect(regions.length).toBeGreaterThanOrEqual(1);
    for (const region of regions) {
      expect(region).toHaveAttribute('tabindex', '0');
      expect(region.getAttribute('aria-label')).toBeTruthy();
      expect(region.querySelector('table.timesheet-table')).not.toBeNull();
    }
    expect(
      within(panel).getByTestId('statutory-preview-lines').closest('.table-wrapper'),
    ).not.toBeNull();
    // Labels and values wrap: wrapping description lists, a card grid for the aggregates.
    expect(
      container.querySelectorAll('dl.decision-summary.decision-summary--wrapping').length,
    ).toBeGreaterThan(0);
    expect(within(panel).getByTestId('statutory-preview-totals')).toHaveClass('statutory-totals');
    const details = within(panel).getAllByTestId('statutory-explanation')[0] as HTMLElement;
    expect(details).toHaveClass('statutory-explanation');
    await user.click(details.querySelector('summary') as HTMLElement);
    expect(
      details.querySelector('.statutory-component dl.decision-summary--wrapping'),
    ).not.toBeNull();
    // No fixed widths in markup.
    expect(container.querySelector('[style*="width"]')).toBeNull();
    // The longest French labels are real, so the structure above is what keeps them in view.
    const longest = Math.max(
      ...Object.values(resources.fr.common.statutory.totals).map((v) => v.length),
    );
    expect(longest).toBeGreaterThan(15);
  });
});

describe.each(['en', 'fr'] as const)('MVP-094B employee statutory profile (%s)', (locale) => {
  const k = resources[locale].common.statutory;

  const profileRoutes =
    (
      overrides: (request: Recorded) => Reply | undefined = () => undefined,
      view: unknown = emptyProfileView(),
    ) =>
    (request: Recorded): Reply => {
      const own = overrides(request);
      if (own) return own;
      if (
        request.method === 'GET' &&
        request.path.startsWith(`/employees/${EMPLOYEE_ID}/statutory-profile`)
      ) {
        return { status: 200, body: view };
      }
      return { status: 500 };
    };

  it('reads the timeline and shows the label, the form and no other data', async () => {
    stub(profileRoutes(() => undefined, profileView()));
    const { container } = await showProfile(locale);
    const current = await screen.findByTestId('statutory-profile-current');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(k.profile.title);
    expect(screen.getByTestId('statutory-label')).toHaveTextContent(k.label);
    expect(current).toHaveTextContent(k.profile.restricted);
    expect(current).toHaveTextContent(k.classification.STANDARD_RESIDENT);
    expect(current).toHaveTextContent(k.evidence.EMPLOYMENT_CONTRACT);
    expect(screen.getAllByTestId('statutory-profile-row')).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 2, name: k.profile.current })).toHaveFocus();
    // Only the closed fields exist: no identifier, no free text.
    const form = screen.getByTestId('statutory-profile-form');
    expect(form.querySelectorAll('textarea, input[type="text"]')).toHaveLength(0);
    expect(within(form).getByLabelText(k.profile.cnss)).toBeChecked();
    await expectAccessible(container);
  });

  it('previews then commits with an Idempotency-Key reused on retry, and reloads the timeline', async () => {
    const user = userEvent.setup();
    let commits = 0;
    let view: unknown = emptyProfileView();
    const requests = stub(
      profileRoutes((request) => {
        if (request.path.endsWith('/statutory-profile/preview')) {
          return { status: 200, body: profilePreview() };
        }
        if (request.method === 'POST' && request.path.endsWith('/statutory-profile')) {
          commits += 1;
          if (commits === 1) return { status: 429, body: problem('RATE_LIMITED', 429) };
          view = profileView();
          return { status: 201, body: profileReceipt() };
        }
        if (request.method === 'GET') return { status: 200, body: view };
        return undefined;
      }),
    );
    const { container } = await showProfile(locale);
    expect(await screen.findByTestId('statutory-profile-none')).toHaveTextContent(k.profile.none);
    expect(screen.queryByTestId('statutory-profile-confirm')).toBeNull();
    const form = screen.getByTestId('statutory-profile-form');
    await user.clear(within(form).getByTestId('statutory-profile-date'));
    await user.type(within(form).getByTestId('statutory-profile-date'), '2026-09-01');
    await user.selectOptions(
      within(form).getByLabelText(k.profile.evidence),
      'PAYROLL_FILE_REVIEW',
    );
    await user.click(within(form).getByRole('button', { name: k.profile.preview }));
    const preview = await screen.findByTestId('statutory-profile-preview');
    expect(preview.querySelector('h3')).toHaveFocus();
    expect(within(preview).getByTestId('statutory-profile-after')).toBeInTheDocument();
    await expectAccessible(container);
    await user.click(within(preview).getByTestId('statutory-profile-confirm'));
    const failure = await screen.findByTestId('statutory-profile-failure');
    expect(failure).toHaveTextContent(resources[locale].common.errors.RATE_LIMITED);
    expect(failure).toHaveFocus();
    await user.click(screen.getByTestId('statutory-profile-confirm'));
    await waitFor(() => {
      expect(screen.getByTestId('statutory-profile-announcer')).toHaveTextContent(
        k.profile.announce,
      );
    });
    expect(await screen.findAllByTestId('statutory-profile-row')).toHaveLength(1);
    const preview1 = requests.find((r) => r.path.endsWith('/statutory-profile/preview'));
    expect(JSON.parse(preview1?.body ?? '{}')).toEqual({
      effectiveFrom: '2026-09-01',
      classification: 'STANDARD_RESIDENT',
      cnssSubject: true,
      irppSubject: true,
      evidenceCode: 'PAYROLL_FILE_REVIEW',
    });
    const posts = requests.filter(
      (r) => r.method === 'POST' && r.path.endsWith('/statutory-profile'),
    );
    expect(posts).toHaveLength(2);
    expect(posts[0]?.key).toMatch(/^web-/u);
    expect(posts[1]?.key).toBe(posts[0]?.key);
    expect(posts[1]?.body).toBe(posts[0]?.body);
    expect(JSON.parse(posts[0]?.body ?? '{}')).toMatchObject({
      expectedVersion: 0,
      previewDigest: 'e'.repeat(64),
      confirmed: true,
      evidenceCode: 'PAYROLL_FILE_REVIEW',
    });
    expect(stored(window.localStorage)).not.toMatch(/STANDARD_RESIDENT|2026-09/u);
    expect(stored(window.sessionStorage)).not.toMatch(/STANDARD_RESIDENT|2026-09/u);
  });

  it('a stale commit clears the preview, reloads and uses a new key next time', async () => {
    const user = userEvent.setup();
    let commits = 0;
    const requests = stub(
      profileRoutes((request) => {
        if (request.path.endsWith('/statutory-profile/preview')) {
          return { status: 200, body: profilePreview() };
        }
        if (request.method === 'POST' && request.path.endsWith('/statutory-profile')) {
          commits += 1;
          return commits === 1
            ? { status: 409, body: problem('STATUTORY_VERSION_CONFLICT', 409) }
            : { status: 201, body: profileReceipt() };
        }
        return undefined;
      }),
    );
    await showProfile(locale);
    await screen.findByTestId('statutory-profile-none');
    await user.click(screen.getByTestId('statutory-profile-preview-button'));
    await user.click(await screen.findByTestId('statutory-profile-confirm'));
    const failure = await screen.findByTestId('statutory-profile-failure');
    expect(failure).toHaveAttribute('data-kind', 'stale');
    expect(screen.queryByTestId('statutory-profile-confirm')).toBeNull();
    await user.click(screen.getByTestId('statutory-profile-preview-button'));
    await user.click(await screen.findByTestId('statutory-profile-confirm'));
    await waitFor(() => {
      expect(screen.getByTestId('statutory-profile-announcer')).toHaveTextContent(
        k.profile.announce,
      );
    });
    const posts = requests.filter(
      (r) => r.method === 'POST' && r.path.endsWith('/statutory-profile'),
    );
    expect(posts[1]?.key).not.toBe(posts[0]?.key);
  });

  it('shows blockers without a confirm control', async () => {
    const user = userEvent.setup();
    stub(
      profileRoutes((request) =>
        request.path.endsWith('/statutory-profile/preview')
          ? {
              status: 200,
              body: profilePreview({
                blockers: ['NO_CHANGE'],
                previewDigest: null,
                expiresAt: null,
              }),
            }
          : undefined,
      ),
    );
    await showProfile(locale);
    await screen.findByTestId('statutory-profile-none');
    await user.click(screen.getByTestId('statutory-profile-preview-button'));
    expect(await screen.findByTestId('statutory-profile-blockers')).toHaveTextContent(
      k.profile.blockers.NO_CHANGE,
    );
    expect(screen.queryByTestId('statutory-profile-confirm')).toBeNull();
  });

  it('an unsupported classification clears and disables both subject flags', async () => {
    const user = userEvent.setup();
    const requests = stub(
      profileRoutes((request) =>
        request.path.endsWith('/statutory-profile/preview')
          ? {
              status: 200,
              body: profilePreview({
                classification: 'UNSUPPORTED',
                cnssSubject: false,
                irppSubject: false,
              }),
            }
          : undefined,
      ),
    );
    await showProfile(locale);
    await screen.findByTestId('statutory-profile-none');
    await user.selectOptions(screen.getByTestId('statutory-profile-classification'), 'UNSUPPORTED');
    expect(screen.getByTestId('statutory-profile-cnss')).toBeDisabled();
    expect(screen.getByTestId('statutory-profile-cnss')).not.toBeChecked();
    expect(screen.getByTestId('statutory-profile-irpp')).toBeDisabled();
    expect(screen.getByTestId('statutory-profile-irpp')).not.toBeChecked();
    await user.click(screen.getByTestId('statutory-profile-preview-button'));
    await screen.findByTestId('statutory-profile-preview');
    const body = JSON.parse(requests.find((r) => r.path.endsWith('/preview'))?.body ?? '{}');
    expect(body).toMatchObject({
      classification: 'UNSUPPORTED',
      cnssSubject: false,
      irppSubject: false,
    });
  });

  it('requires a valid date before any request', async () => {
    const user = userEvent.setup();
    const requests = stub(profileRoutes());
    await showProfile(locale);
    await screen.findByTestId('statutory-profile-none');
    await user.clear(screen.getByTestId('statutory-profile-date'));
    await user.click(screen.getByTestId('statutory-profile-preview-button'));
    const problems = await screen.findByTestId('statutory-profile-problems');
    expect(problems).toHaveTextContent(k.profile.problems.effectiveFrom);
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
  });

  it('shows a safe not-found state for an unknown or another tenant’s employee', async () => {
    stub(() => ({ status: 404, body: problem('COMPENSATION_NOT_FOUND', 404) }));
    await showProfile(locale);
    const alert = await screen.findByTestId('statutory-profile-error');
    expect(alert).toHaveAttribute('data-kind', 'notFound');
    expect(alert).toHaveTextContent(resources[locale].common.errors.COMPENSATION_NOT_FOUND);
    expect(screen.queryByTestId('statutory-profile-form')).toBeNull();
    expect(screen.getByTestId('statutory-label')).toHaveTextContent(k.label);
  });
});
