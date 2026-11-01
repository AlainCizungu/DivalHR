import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import {
  DIGEST,
  ENTITY_ID,
  EXPIRES,
  PACKAGE_ID,
  PACKAGE_ID_2,
  PAYSLIP_ID,
  PAYSLIP_ID_2,
  PREVIEW_DIGEST,
  packageDetail,
  packageSummary,
  payslipPage,
  payslipSummary,
  problem,
  voidPreview,
  voidReceipt,
} from './fixtures';
import { PackageDetailPage } from './PackageDetailPage';
import { PackageListPage } from './PackageListPage';
import { stub, type Recorded, type Reply } from './stub';

const BASE = `/legal-entities/${ENTITY_ID}`;
const PKG = `${BASE}/payroll-packages/${PACKAGE_ID}`;

const entities = {
  data: [{ id: ENTITY_ID, code: 'LE1', name: 'Entity One' }],
};

async function showList(locale: 'en' | 'fr') {
  return renderWithSession(
    <Routes>
      <Route path="/admin/payroll-packages" element={<PackageListPage />} />
    </Routes>,
    sessionWithRoles(['tenant-admin']),
    locale,
    '/admin/payroll-packages',
  );
}

async function showDetail(locale: 'en' | 'fr') {
  return renderWithSession(
    <Routes>
      <Route
        path="/admin/payroll-packages/:legalEntityId/:packageId"
        element={<PackageDetailPage />}
      />
    </Routes>,
    sessionWithRoles(['tenant-admin']),
    locale,
    `/admin/payroll-packages/${ENTITY_ID}/${PACKAGE_ID}`,
  );
}

function detailRoutes(
  own: (request: Recorded) => Reply | undefined = () => undefined,
  detail: unknown = packageDetail(),
) {
  return (request: Recorded): Reply => {
    const mine = own(request);
    if (mine) return mine;
    if (request.method === 'GET' && request.path === PKG) return { status: 200, body: detail };
    if (request.method === 'GET' && request.path.startsWith(`${PKG}/payslips`)) {
      return {
        status: 200,
        body: payslipPage([
          payslipSummary(),
          payslipSummary({ payslipId: PAYSLIP_ID_2, employeeNumber: 'E-0002' }),
        ]),
      };
    }
    return { status: 500 };
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['en', 'fr'] as const)('payroll package list (%s)', (locale) => {
  const c = resources[locale].common;

  it('lists packages with status, synthetic badge and keyset load more', async () => {
    const requests = stub((r) => {
      if (r.path.startsWith('/legal-entities?')) return { status: 200, body: entities };
      if (r.path.startsWith(`${BASE}/payroll-packages`)) {
        return r.path.includes('cursor=c2')
          ? {
              status: 200,
              body: {
                items: [packageSummary({ packageId: PACKAGE_ID_2, status: 'VOIDED', version: 2 })],
                nextCursor: null,
              },
            }
          : { status: 200, body: { items: [packageSummary()], nextCursor: 'c2' } };
      }
      return { status: 500 };
    });
    const user = userEvent.setup();
    await showList(locale);
    await screen.findByTestId('package-list-table');
    expect(screen.getByTestId('package-notice')).toHaveTextContent(c.payrollPackages.notice);
    expect(screen.getAllByTestId('package-row')).toHaveLength(1);
    expect(screen.getAllByTestId('synthetic-badge')).toHaveLength(1);
    expect(screen.getByTestId('package-open')).toHaveAttribute(
      'href',
      `/admin/payroll-packages/${ENTITY_ID}/${PACKAGE_ID}`,
    );
    await user.click(screen.getByTestId('package-list-more'));
    await waitFor(() => {
      expect(screen.getAllByTestId('package-row')).toHaveLength(2);
    });
    expect(screen.getAllByTestId('package-row')[1]).toHaveAttribute('data-status', 'VOIDED');
    expect(screen.queryByTestId('package-list-more')).toBeNull();
    // No amount in any URL or cursor of the list requests.
    expect(requests.map((r) => r.path).join(' ')).not.toMatch(/1350000|amount/u);
  });

  it('filters by status through the query only', async () => {
    const requests = stub((r) => {
      if (r.path.startsWith('/legal-entities?')) return { status: 200, body: entities };
      return { status: 200, body: { items: [], nextCursor: null } };
    });
    const user = userEvent.setup();
    await showList(locale);
    await screen.findByTestId('package-list-empty');
    await user.selectOptions(screen.getByTestId('package-status-filter'), 'VOIDED');
    await waitFor(() => {
      expect(requests.some((r) => r.path.includes('status=VOIDED'))).toBe(true);
    });
  });

  it('shows the not-found state safely', async () => {
    stub((r) =>
      r.path.startsWith('/legal-entities?')
        ? { status: 200, body: entities }
        : { status: 404, body: problem('LEGAL_ENTITY_NOT_FOUND', 404) },
    );
    await showList(locale);
    const error = await screen.findByTestId('package-list-error');
    expect(error).toHaveTextContent(c.errors.LEGAL_ENTITY_NOT_FOUND);
  });
});

describe.each(['en', 'fr'] as const)('payroll package detail and void (%s)', (locale) => {
  const c = resources[locale].common;

  it('shows the approved package with sources, totals and payslips, and is accessible', async () => {
    stub(detailRoutes());
    const { container } = await showDetail(locale);
    const summary = await screen.findByTestId('package-summary');
    expect(within(summary).getByTestId('package-status')).toHaveTextContent(
      c.payrollPackages.status.APPROVED,
    );
    expect(within(summary).getByTestId('synthetic-badge')).toBeInTheDocument();
    expect(within(summary).getByTestId('package-pack-statement')).toHaveTextContent(
      'SYNTHETIC-TEST-1',
    );
    expect(within(summary).getByTestId('package-run')).toHaveTextContent('2');
    expect(within(summary).getByTestId('package-calculation')).toHaveTextContent('3');
    expect(within(summary).getByTestId('not-proof')).toHaveTextContent(
      c.payrollPackages.notProofOfPayment,
    );
    expect(screen.queryByTestId('package-voided-banner')).toBeNull();
    expect(await screen.findAllByTestId('package-payslip-row')).toHaveLength(2);
    expect(screen.getByTestId('void-panel')).toBeInTheDocument();
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('downloads a payslip PDF through the authenticated client under a safe name', async () => {
    const requests = stub(
      detailRoutes((r) => (r.path.includes('.pdf') ? { status: 200, pdf: true } : undefined)),
    );
    const created: Blob[] = [];
    Object.assign(URL, {
      createObjectURL: (blob: Blob) => {
        created.push(blob);
        return 'blob:synthetic';
      },
      revokeObjectURL: vi.fn(),
    });
    const names: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      names.push(this.download);
    });
    const user = userEvent.setup();
    await showDetail(locale);
    const buttons = await screen.findAllByTestId('package-payslip-download');
    await user.click(buttons[0] as HTMLElement);
    await waitFor(() => {
      expect(names).toEqual(['payslip-2026-09-aaaaaaaa.pdf']);
    });
    const pdf = requests.find((r) => r.path.includes('.pdf'));
    expect(pdf?.path).toBe(`${PKG}/payslips/${PAYSLIP_ID}.pdf?locale=${locale}`);
    expect(created[0]?.type).toBe('application/pdf');
    expect(await created[0]?.text()).toMatch(/^%PDF/u);
    expect(screen.getByTestId('package-payslips-announcer')).toHaveTextContent(
      c.payslips.downloaded,
    );
    click.mockRestore();
  });

  it('maps a render failure to a finished message', async () => {
    stub(
      detailRoutes((r) =>
        r.path.includes('.pdf')
          ? { status: 500, body: problem('PAYSLIP_RENDER_FAILED', 500) }
          : undefined,
      ),
    );
    const user = userEvent.setup();
    await showDetail(locale);
    await user.click((await screen.findAllByTestId('package-payslip-download'))[0] as HTMLElement);
    expect(await screen.findByTestId('package-payslip-download-error')).toHaveTextContent(
      c.errors.PAYSLIP_RENDER_FAILED,
    );
  });

  it('shows a voided package with a prominent banner, the reason and the administrator explanation', async () => {
    stub(
      detailRoutes(
        () => undefined,
        packageDetail(
          {
            void: {
              reasonCode: 'OTHER_CONTROLLED',
              explanation: 'Synthetic administrator explanation',
              voidedAt: '2026-10-03T09:00:00Z',
            },
          },
          {
            status: 'VOIDED',
            voidedAt: '2026-10-03T09:00:00Z',
            voidReasonCode: 'OTHER_CONTROLLED',
          },
        ),
      ),
    );
    await showDetail(locale);
    const banner = await screen.findByTestId('package-voided-banner');
    expect(banner).toHaveTextContent(c.payrollPackages.detail.voidedBanner);
    expect(screen.getByTestId('package-void-reason')).toHaveAttribute(
      'data-code',
      'OTHER_CONTROLLED',
    );
    expect(screen.getByTestId('package-void-explanation')).toHaveTextContent(
      'Synthetic administrator explanation',
    );
    expect(screen.queryByTestId('void-panel')).toBeNull();
  });

  it('requires the explanation only for the other-controlled reason and sends it only then', async () => {
    const requests = stub(
      detailRoutes((r) => {
        if (r.path === `${PKG}/void/preview`) {
          const body = JSON.parse(r.body) as { reasonCode: string };
          return { status: 200, body: voidPreview({ reasonCode: body.reasonCode }) };
        }
        return undefined;
      }),
    );
    const user = userEvent.setup();
    await showDetail(locale);
    await screen.findByTestId('void-panel');
    const preview = screen.getByTestId('void-preview-button');
    expect(preview).toBeDisabled();
    expect(screen.queryByTestId('void-explanation')).toBeNull();
    // The closed reason list.
    const options = within(screen.getByTestId('void-reason'))
      .getAllByRole('option')
      .map((option) => option.getAttribute('value'));
    expect(options).toEqual([
      '',
      'SOURCE_CORRECTION',
      'PROFILE_CORRECTION',
      'RULE_PACK_CORRECTION',
      'ADMINISTRATIVE_ERROR',
      'OTHER_CONTROLLED',
    ]);
    await user.selectOptions(screen.getByTestId('void-reason'), 'ADMINISTRATIVE_ERROR');
    expect(preview).toBeEnabled();
    expect(screen.queryByTestId('void-explanation')).toBeNull();
    await user.selectOptions(screen.getByTestId('void-reason'), 'OTHER_CONTROLLED');
    expect(screen.getByTestId('void-explanation')).toBeRequired();
    expect(preview).toBeDisabled();
    await user.type(screen.getByTestId('void-explanation'), 'short');
    expect(preview).toBeDisabled();
    await user.type(screen.getByTestId('void-explanation'), ' but long enough now');
    expect(preview).toBeEnabled();
    await user.click(preview);
    await screen.findByTestId('void-preview');
    const sent = requests.find((r) => r.path === `${PKG}/void/preview`);
    expect(JSON.parse(sent?.body ?? '{}')).toEqual({
      reasonCode: 'OTHER_CONTROLLED',
      explanation: 'short but long enough now',
    });
    // Switching to a reason without explanation sends null and discards the preview.
    await user.selectOptions(screen.getByTestId('void-reason'), 'SOURCE_CORRECTION');
    expect(screen.queryByTestId('void-preview')).toBeNull();
    await user.click(screen.getByTestId('void-preview-button'));
    await screen.findByTestId('void-preview');
    const last = requests.filter((r) => r.path === `${PKG}/void/preview`).at(-1);
    expect(JSON.parse(last?.body ?? '{}')).toEqual({
      reasonCode: 'SOURCE_CORRECTION',
      explanation: null,
    });
  });

  it('needs the no-payment acknowledgement, then voids and reloads the package', async () => {
    let voided = false;
    const requests = stub(
      detailRoutes((r) => {
        if (r.path === `${PKG}/void/preview`) return { status: 200, body: voidPreview() };
        if (r.path === `${PKG}/void`) {
          voided = true;
          return { status: 201, body: voidReceipt() };
        }
        if (voided && r.method === 'GET' && r.path === PKG) {
          return {
            status: 200,
            body: packageDetail(
              {
                void: {
                  reasonCode: 'SOURCE_CORRECTION',
                  explanation: null,
                  voidedAt: '2026-10-03T09:00:00Z',
                },
              },
              {
                status: 'VOIDED',
                voidedAt: '2026-10-03T09:00:00Z',
                voidReasonCode: 'SOURCE_CORRECTION',
              },
            ),
          };
        }
        return undefined;
      }),
    );
    const user = userEvent.setup();
    await showDetail(locale);
    await screen.findByTestId('void-panel');
    await user.selectOptions(screen.getByTestId('void-reason'), 'SOURCE_CORRECTION');
    await user.click(screen.getByTestId('void-preview-button'));
    await screen.findByTestId('void-preview');
    const commit = screen.getByTestId('void-commit');
    expect(commit).toBeDisabled();
    expect(screen.getByTestId('void-commit-why')).toHaveTextContent(
      c.payrollPackages.void.whyAcknowledge,
    );
    await user.click(screen.getByTestId('void-ack'));
    expect(commit).toBeEnabled();
    await user.click(commit);
    expect(await screen.findByTestId('package-voided-banner')).toBeInTheDocument();
    const post = requests.find((r) => r.path === `${PKG}/void`);
    expect(post?.key).toMatch(/^web-/u);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({
      reasonCode: 'SOURCE_CORRECTION',
      explanation: null,
      packageVersion: 1,
      approvalDigest: DIGEST,
      previewDigest: PREVIEW_DIGEST,
      expiresAt: EXPIRES,
      noPaymentAcknowledged: true,
    });
    expect(screen.getByTestId('package-announcer')).toHaveTextContent(
      c.payrollPackages.void.announce,
    );
  });

  it('blocks the void when a payment exists and handles a stale void review', async () => {
    let blocked = true;
    stub(
      detailRoutes((r) => {
        if (r.path === `${PKG}/void/preview`) {
          return blocked
            ? {
                status: 200,
                body: voidPreview({
                  status: 'BLOCKED',
                  blockers: ['PAYMENT_EXISTS'],
                  previewDigest: null,
                  expiresAt: null,
                }),
              }
            : { status: 200, body: voidPreview() };
        }
        if (r.path === `${PKG}/void`) {
          return { status: 409, body: problem('PAYROLL_VOID_PREVIEW_STALE', 409) };
        }
        return undefined;
      }),
    );
    const user = userEvent.setup();
    await showDetail(locale);
    await screen.findByTestId('void-panel');
    await user.selectOptions(screen.getByTestId('void-reason'), 'PROFILE_CORRECTION');
    await user.click(screen.getByTestId('void-preview-button'));
    await screen.findByTestId('void-blockers');
    expect(screen.getByTestId('void-blockers')).toHaveTextContent(
      c.payrollPackages.voidBlockers.PAYMENT_EXISTS,
    );
    expect(screen.getByTestId('void-commit')).toBeDisabled();
    expect(screen.getByTestId('void-ack')).toBeDisabled();
    blocked = false;
    await user.click(screen.getByTestId('void-preview-button'));
    await waitFor(() => {
      expect(screen.getByTestId('void-ack')).toBeEnabled();
    });
    await user.click(screen.getByTestId('void-ack'));
    await user.click(screen.getByTestId('void-commit'));
    const failure = await screen.findByTestId('void-failure');
    expect(failure).toHaveAttribute('data-kind', 'stale');
    expect(failure).toHaveTextContent(c.errors.PAYROLL_VOID_PREVIEW_STALE);
    expect(screen.queryByTestId('void-preview')).toBeNull();
    expect(screen.getByTestId('void-stale-refresh')).toBeInTheDocument();
  });

  it('shows the not-found state for a foreign package', async () => {
    stub(() => ({ status: 404, body: problem('PAYROLL_PACKAGE_NOT_FOUND', 404) }));
    await showDetail(locale);
    const error = await screen.findByTestId('package-detail-error');
    expect(error).toHaveAttribute('data-kind', 'notFound');
    expect(error).toHaveTextContent(c.errors.PAYROLL_PACKAGE_NOT_FOUND);
  });
});
