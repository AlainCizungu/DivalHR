import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequirePlatformAdmin } from '../admin/RequirePlatformAdmin';
import { stub, type Recorded, type Reply } from '../payslips/stub';
import { PartnerApplicationDetailPage } from './PartnerApplicationDetailPage';
import { PartnerApplicationsPage } from './PartnerApplicationsPage';

const REFERENCE = 'PA-ABCDEFGHJKMN';
const NOTE = 'secret internal note 7f3a';
const en = resources.en.common;

type Status =
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'MORE_INFORMATION_REQUIRED'
  | 'APPROVED_FOR_ONBOARDING'
  | 'DECLINED'
  | 'WITHDRAWN';

const CODES = [
  'BUSINESS_IDENTITY',
  'REGISTRATION_REFERENCE',
  'REGULATORY_INFORMATION',
  'SERVICE_SCOPE',
  'PRIVACY_SECURITY_CONTACT',
  'TECHNICAL_API_READINESS',
  'COMMERCIAL_DISCUSSION',
] as const;

function detail(status: Status, allowed: Status[], blocked: string[] = []) {
  return {
    reference: REFERENCE,
    status,
    version: 4,
    primaryCategory: 'INSURANCE',
    additionalCategories: ['TELECOM'],
    requestedCapabilities: ['HEALTH_INSURANCE'],
    legalName: 'Synthetic Test Insurance SA',
    tradingName: null,
    countryOfRegistration: 'CD',
    countriesOfOperation: ['CD', 'RW'],
    website: 'https://example.test',
    contactRole: 'Head of partnerships',
    supportedLanguages: ['fr'],
    apiAvailability: 'PLANNED',
    regulatorName: 'Synthetic Regulator',
    serviceSummary: 'A synthetic summary that is long enough.',
    submissionLocale: 'fr',
    submittedAt: '2026-10-20T09:30:00Z',
    updatedAt: '2026-10-20T09:30:00Z',
    contact: {
      contactName: 'Test Contact',
      businessEmail: 'contact@example.test',
      businessPhone: null,
      registrationReference: 'SYN-0001',
      licenceReference: 'LIC-0001',
      privacyContactEmail: 'privacy@example.test',
    },
    consent: {
      privacyNoticeVersion: 'PARTNER-PRIVACY-1',
      consentVersion: 'PARTNER-APPLICATION-CONSENT-1',
      locale: 'fr',
      acceptedAt: '2026-10-20T09:30:00Z',
    },
    checklist: CODES.map((checkCode) => ({
      checkCode,
      mandatory: checkCode !== 'COMMERCIAL_DISCUSSION',
      result: 'PENDING',
      version: 2,
      performedByCaller: null,
      updatedAt: '2026-10-20T09:30:00Z',
      note: null,
    })),
    allowedTransitions: allowed,
    approvalBlockedBy: blocked,
    possibleDuplicates: 0,
  };
}

const HISTORY = {
  items: [
    {
      sequence: 1,
      eventType: 'SUBMITTED',
      fromStatus: null,
      toStatus: 'SUBMITTED',
      checkCode: null,
      checkResult: null,
      reasonCode: null,
      actorType: 'APPLICANT',
      performedByCaller: null,
      occurredAt: '2026-10-20T09:30:00Z',
    },
  ],
  nextCursor: null,
};

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

async function openDetail(
  d: ReturnType<typeof detail>,
  extra: (r: Recorded) => Reply | undefined = () => undefined,
  locale: 'en' | 'fr' = 'en',
) {
  const requests = stub((r) => {
    const custom = extra(r);
    if (custom) return custom;
    if (r.method === 'GET' && r.path.includes('/history')) return { status: 200, body: HISTORY };
    if (r.method === 'GET') return { status: 200, body: d };
    return { status: 200, body: {} };
  });
  await renderWithSession(
    <Routes>
      <Route
        path="/admin/partner-applications/:reference"
        element={<PartnerApplicationDetailPage />}
      />
    </Routes>,
    sessionWithRoles(['platform-admin']),
    locale,
    `/admin/partner-applications/${REFERENCE}`,
  );
  await screen.findByTestId('partner-application-detail');
  return { requests, user: userEvent.setup(), c: resources[locale].common.partners };
}

describe('partner review queue', () => {
  it('lists applications with counts, links by reference only, and filters through the API', async () => {
    const requests = stub(() => ({
      status: 200,
      body: {
        items: [
          {
            reference: REFERENCE,
            status: 'SUBMITTED',
            version: 1,
            primaryCategory: 'BANK',
            additionalCategories: [],
            requestedCapabilities: ['SALARY_ADVANCE'],
            legalName: 'Synthetic Test Bank SA',
            countryOfRegistration: 'CD',
            submittedAt: '2026-10-20T09:30:00Z',
            updatedAt: '2026-10-20T09:30:00Z',
          },
        ],
        nextCursor: null,
        statusCounts: {
          SUBMITTED: 1,
          UNDER_REVIEW: 0,
          MORE_INFORMATION_REQUIRED: 0,
          APPROVED_FOR_ONBOARDING: 0,
          DECLINED: 0,
          WITHDRAWN: 0,
        },
      },
    }));
    await renderWithSession(
      <PartnerApplicationsPage />,
      sessionWithRoles(['platform-admin']),
      'en',
      '/admin/partner-applications',
    );
    const row = await screen.findByTestId('partner-application-row');
    expect(within(row).getByRole('link', { name: REFERENCE })).toHaveAttribute(
      'href',
      `/admin/partner-applications/${REFERENCE}`,
    );
    expect(screen.getByTestId('partner-count-SUBMITTED')).toHaveTextContent('1');
    const user = userEvent.setup();
    await user.selectOptions(
      screen.getByLabelText(en.partners.review.filters.status),
      'UNDER_REVIEW',
    );
    await user.click(screen.getByRole('button', { name: en.partners.review.filters.apply }));
    await waitFor(() => {
      expect(requests.at(-1)?.path).toContain('status=UNDER_REVIEW');
    });
    for (const request of requests) {
      expect(request.path).toContain('/platform/partner-applications');
      expect(request.path).not.toMatch(/legalName|email|note/iu);
    }
    expect(window.location.search).toBe('');
  });

  it('shows no queue and makes no request to anyone but a platform administrator', async () => {
    for (const roles of [['tenant-admin'], ['employee'], ['employee', 'tenant-admin']] as const) {
      const requests = stub(() => ({ status: 200, body: {} }));
      await renderWithSession(
        <RequirePlatformAdmin
          deniedKey="partners.review.unauthorized"
          signInKey="partners.review.signInRequired"
        >
          <PartnerApplicationsPage />
        </RequirePlatformAdmin>,
        sessionWithRoles([...roles]),
        'en',
        '/admin/partner-applications',
      );
      expect(screen.getByTestId('not-authorized')).toHaveTextContent(
        en.partners.review.unauthorized,
      );
      expect(screen.queryByTestId('partner-applications-page')).toBeNull();
      expect(requests).toHaveLength(0);
      document.body.innerHTML = '';
    }
  });
});

describe('partner application review', () => {
  it('shows the application, the consent, the checklist and the history', async () => {
    await openDetail(
      detail('UNDER_REVIEW', ['MORE_INFORMATION_REQUIRED', 'DECLINED', 'WITHDRAWN']),
    );
    expect(screen.getByTestId('partner-contact')).toHaveTextContent('contact@example.test');
    expect(screen.getByTestId('partner-consent')).toHaveTextContent(
      'PARTNER-APPLICATION-CONSENT-1',
    );
    expect(screen.getAllByTestId('partner-check')).toHaveLength(7);
    expect(screen.getAllByTestId('partner-history-row')).toHaveLength(1);
    expect(screen.getByTestId('partner-status')).toHaveTextContent(en.partners.status.UNDER_REVIEW);
  });

  it('saves a check with the version seen and keeps the note out of URLs and storage', async () => {
    const { requests, user } = await openDetail(
      detail('UNDER_REVIEW', ['MORE_INFORMATION_REQUIRED', 'DECLINED', 'WITHDRAWN']),
    );
    const check = screen
      .getAllByTestId('partner-check')
      .find((el) => el.getAttribute('data-check') === 'SERVICE_SCOPE') as HTMLElement;
    await user.selectOptions(within(check).getByLabelText(en.partners.review.result), 'SATISFIED');
    await user.type(within(check).getByLabelText(en.partners.review.note), NOTE);
    await user.click(within(check).getByRole('button', { name: en.partners.review.saveCheck }));
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'PUT')).toBe(true);
    });
    const put = requests.find((r) => r.method === 'PUT') as Recorded;
    expect(put.path).toBe(`/platform/partner-applications/${REFERENCE}/checklist/SERVICE_SCOPE`);
    expect(put.key).toMatch(/^web-/u);
    expect(JSON.parse(put.body)).toEqual({ result: 'SATISFIED', expectedVersion: 2, note: NOTE });
    for (const request of requests) expect(request.path).not.toContain('secret');
    expect(window.location.href).not.toContain('secret');
    expect(
      [...Object.values(window.localStorage), ...Object.values(window.sessionStorage)].join(),
    ).not.toContain('secret');
    await waitFor(() => {
      expect(within(check).getByLabelText(en.partners.review.note)).toHaveValue('');
    });
  });

  it('blocks approval while mandatory checks are open and names them', async () => {
    const { user } = await openDetail(
      detail('UNDER_REVIEW', ['APPROVED_FOR_ONBOARDING', 'DECLINED'], ['SERVICE_SCOPE']),
    );
    expect(screen.getByTestId('partner-approval-blocked')).toHaveTextContent(
      en.partners.check.SERVICE_SCOPE.title,
    );
    expect(screen.getByTestId('partner-transition-APPROVED_FOR_ONBOARDING')).toBeDisabled();
    await user.click(screen.getByTestId('partner-transition-DECLINED'));
    expect(screen.getByTestId('partner-transition-form')).toBeInTheDocument();
  });

  it('declines only with a reason and a confirmation, and sends exactly that', async () => {
    const { requests, user } = await openDetail(detail('UNDER_REVIEW', ['DECLINED', 'WITHDRAWN']));
    await user.click(screen.getByTestId('partner-transition-DECLINED'));
    await user.click(screen.getByRole('button', { name: en.partners.review.apply }));
    expect(await screen.findByTestId('partner-transition-failure')).toHaveTextContent(
      en.partners.review.reasonRequired,
    );
    await user.selectOptions(screen.getByLabelText(en.partners.review.reason), 'OUT_OF_SCOPE');
    await user.click(screen.getByRole('button', { name: en.partners.review.apply }));
    expect(await screen.findByTestId('partner-transition-failure')).toHaveTextContent(
      en.partners.review.confirmRequired,
    );
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
    await user.click(screen.getByRole('checkbox', { name: en.partners.review.confirm.DECLINED }));
    await user.click(screen.getByRole('button', { name: en.partners.review.apply }));
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    const post = requests.find((r) => r.method === 'POST') as Recorded;
    expect(post.path).toBe(`/platform/partner-applications/${REFERENCE}:transition`);
    expect(JSON.parse(post.body)).toEqual({
      toStatus: 'DECLINED',
      expectedVersion: 4,
      reasonCode: 'OUT_OF_SCOPE',
      confirmed: true,
    });
  });

  it('states what approval does not create, before and after approving', async () => {
    const { requests, user } = await openDetail(
      detail('UNDER_REVIEW', ['APPROVED_FOR_ONBOARDING', 'DECLINED', 'WITHDRAWN']),
    );
    await user.click(screen.getByTestId('partner-transition-APPROVED_FOR_ONBOARDING'));
    expect(screen.getByTestId('partner-approval-warning')).toHaveTextContent(
      en.partners.review.approvalBoundary,
    );
    await user.click(
      screen.getByRole('checkbox', { name: en.partners.review.confirm.APPROVED_FOR_ONBOARDING }),
    );
    await user.click(screen.getByRole('button', { name: en.partners.review.apply }));
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    expect(JSON.parse((requests.find((r) => r.method === 'POST') as Recorded).body)).toEqual({
      toStatus: 'APPROVED_FOR_ONBOARDING',
      expectedVersion: 4,
      reasonCode: null,
      confirmed: true,
    });
  });

  it('shows an approved application as closed, with the boundary and no actions', async () => {
    await openDetail(detail('APPROVED_FOR_ONBOARDING', []));
    expect(screen.getByTestId('partner-approval-boundary')).toHaveTextContent(
      en.partners.review.approvalBoundary,
    );
    expect(screen.getByTestId('partner-terminal')).toBeInTheDocument();
    expect(screen.queryByTestId('partner-transition-form')).toBeNull();
    expect(screen.queryAllByRole('button', { name: en.partners.review.saveCheck })).toHaveLength(0);
  });

  it.each(['en', 'fr'] as const)(
    'renders a stale change in %s as a finished sentence',
    async (locale) => {
      const { user, c } = await openDetail(
        detail('UNDER_REVIEW', ['DECLINED']),
        (r) =>
          r.method === 'POST'
            ? {
                status: 409,
                body: {
                  type: 'about:blank',
                  title: 'x',
                  status: 409,
                  code: 'PARTNER_APPLICATION_TRANSITION_INVALID',
                  params: {},
                  correlationId: 'corr-12345678',
                },
              }
            : undefined,
        locale,
      );
      await user.click(screen.getByTestId('partner-transition-DECLINED'));
      await user.selectOptions(screen.getByLabelText(c.review.reason), 'OUT_OF_SCOPE');
      await user.click(screen.getByRole('checkbox', { name: c.review.confirm.DECLINED }));
      await user.click(screen.getByRole('button', { name: c.review.apply }));
      expect(await screen.findByTestId('partner-transition-failure')).toHaveTextContent(
        resources[locale].common.errors.PARTNER_APPLICATION_TRANSITION_INVALID,
      );
    },
  );

  it('has no detectable accessibility violations', async () => {
    await openDetail(detail('UNDER_REVIEW', ['MORE_INFORMATION_REQUIRED', 'DECLINED']));
    const result = await axe.run(screen.getByTestId('partner-application-detail'), {
      rules: { 'color-contrast': { enabled: false } },
    });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });
});
