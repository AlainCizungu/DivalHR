import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { stub, type Recorded, type Reply } from '../payslips/stub';
import { CataloguePage } from './CataloguePage';
import { PartnerAccountDetailPage } from './PartnerAccountDetailPage';
import { PartnerAccountsPage } from './PartnerAccountsPage';

const PARTNER = 'PT-ABCDEFGHJKMN';
const APPLICATION = 'PA-ABCDEFGHJKMN';
const en = resources.en.common;

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

const product = {
  publicReference: 'PP-ABCDEFGHJKMN',
  partnerDisplayName: 'Synthetic Test Bank',
  productType: 'SALARY_ADVANCE',
  name: { en: 'Salary advance', fr: 'Avance sur salaire' },
  summary: { en: 'A short synthetic summary.', fr: 'Un court résumé synthétique.' },
  eligibility: { en: 'Open to permanent staff.', fr: 'Ouvert au personnel permanent.' },
  disclosure: { en: 'Synthetic disclosure text here.', fr: 'Texte de mention synthétique.' },
  infoUrl: 'https://example.test/info',
  effectiveDate: '2026-11-01',
  endDate: null,
  minMonths: 1,
  maxMonths: 6,
  currencies: [{ currency: 'CDF', minAmount: '10000', maxAmount: '500000' }],
  informationOnly: true,
};

const catalogue =
  (reply: Partial<{ items: unknown[]; availability: string }> = {}) =>
  (r: Recorded): Reply => {
    if (r.path.startsWith('/catalogue/partners')) {
      return {
        status: 200,
        body: {
          items: [{ displayName: 'Synthetic Test Bank', categories: ['BANK'], productCount: 1 }],
          availability: 'AVAILABLE',
          informationOnly: true,
        },
      };
    }
    return {
      status: 200,
      body: {
        items: reply.items ?? [product],
        nextCursor: null,
        availability: reply.availability ?? 'AVAILABLE',
        informationOnly: true,
      },
    };
  };

describe.each(['en', 'fr'] as const)('catalogue (%s)', (locale) => {
  const c = resources[locale].common.catalogue;

  it.each(['tenant', 'employee'] as const)(
    'is information only for the %s audience: three sentences, no apply control, no internal data',
    async (audience) => {
      const requests = stub(catalogue());
      await renderWithSession(
        <CataloguePage audience={audience} />,
        sessionWithRoles([audience === 'tenant' ? 'tenant-admin' : 'employee']),
        locale,
        '/x',
      );
      const card = await screen.findByTestId('catalogue-product');
      const notice = screen.getByTestId('catalogue-notice');
      expect(notice).toHaveTextContent(c.informationOnly);
      expect(notice).toHaveTextContent(c.noApplication);
      expect(notice).toHaveTextContent(c.providerDetermines);
      expect(within(card).getByTestId('catalogue-product-notice')).toHaveTextContent(
        c.providerDetermines,
      );
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(c[audience].title);
      expect(
        screen.queryByRole('button', {
          name: /apply for|enrol|enroll|request|contact|postuler|demander|inscrire|contacter/iu,
        }),
      ).toBeNull();
      expect(screen.queryByRole('link', { name: /apply|postuler/iu })).toBeNull();
      expect(document.body.textContent).not.toMatch(/PT-[A-Z0-9]{12}|platform-admin/u);
      for (const request of requests) {
        expect(request.path).toMatch(/^\/catalogue\//u);
        expect(request.method).toBe('GET');
      }
      expect(window.location.search).toBe('');
    },
  );

  it('shows the provider text in the interface language', async () => {
    stub(catalogue());
    await renderWithSession(
      <CataloguePage audience="employee" />,
      sessionWithRoles(['employee']),
      locale,
      '/x',
    );
    const card = await screen.findByTestId('catalogue-product');
    expect(within(card).getByRole('heading', { level: 2 })).toHaveTextContent(
      locale === 'en' ? 'Salary advance' : 'Avance sur salaire',
    );
  });

  it('explains an unknown organization country instead of an empty list', async () => {
    stub(catalogue({ items: [], availability: 'ORGANIZATION_COUNTRY_UNKNOWN' }));
    await renderWithSession(
      <CataloguePage audience="tenant" />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/x',
    );
    expect(await screen.findByTestId('catalogue-country-unknown')).toHaveTextContent(
      c.countryUnknown,
    );
    expect(screen.queryByTestId('catalogue-empty')).toBeNull();
  });

  it('says plainly when nothing matches', async () => {
    stub(catalogue({ items: [] }));
    await renderWithSession(
      <CataloguePage audience="employee" />,
      sessionWithRoles(['employee']),
      locale,
      '/x',
    );
    expect(await screen.findByTestId('catalogue-empty')).toHaveTextContent(c.empty);
  });
});

describe('catalogue behaviour', () => {
  it('filters through the API and has no accessibility violations', async () => {
    const requests = stub(catalogue());
    const { container } = await renderWithSession(
      <CataloguePage audience="employee" />,
      sessionWithRoles(['employee']),
      'en',
      '/x',
    );
    await screen.findByTestId('catalogue-product');
    const results = await axe.run(container);
    expect(results.violations).toEqual([]);
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText(en.catalogue.type), 'HEALTH_INSURANCE');
    await user.click(screen.getByRole('button', { name: en.catalogue.apply }));
    await waitFor(() => {
      expect(requests.at(-1)?.path).toContain('productType=HEALTH_INSURANCE');
    });
  });

  it('shows a finished sentence, not a code, when the catalogue cannot be read', async () => {
    stub(() => ({ status: 403, body: { code: 'ACCESS_DENIED', correlationId: 'c-1' } }));
    await renderWithSession(
      <CataloguePage audience="employee" />,
      sessionWithRoles(['employee']),
      'en',
      '/x',
    );
    const alert = await screen.findByTestId('catalogue-failure');
    expect(alert).toHaveTextContent(en.errors.ACCESS_DENIED);
    expect(alert).not.toHaveTextContent(/ACCESS_DENIED/u);
  });
});

describe('platform partner accounts', () => {
  it('creates a partner only after an explicit confirmation, with one idempotency key', async () => {
    const requests = stub((r) => {
      if (r.path.startsWith('/platform/partners/ready-applications')) {
        return {
          status: 200,
          body: {
            items: [
              {
                applicationReference: APPLICATION,
                legalName: 'Synthetic Test Bank SA',
                tradingName: null,
                countryOfRegistration: 'CD',
                primaryCategory: 'BANK',
                approvedAt: '2026-11-20T09:00:00Z',
                partnerReference: null,
              },
            ],
            nextCursor: null,
          },
        };
      }
      if (r.method === 'POST') return { status: 500, body: { code: 'INTERNAL_ERROR' } };
      return {
        status: 200,
        body: {
          items: [],
          nextCursor: null,
          statusCounts: { DRAFT: 0, ACTIVE: 0, SUSPENDED: 0, CLOSED: 0 },
        },
      };
    });
    await renderWithSession(
      <PartnerAccountsPage />,
      sessionWithRoles(['platform-admin']),
      'en',
      '/admin/partners',
    );
    const user = userEvent.setup();
    await user.click(await screen.findByTestId('ready-start'));
    expect(screen.getByText(en.accounts.ready.copyRule)).toBeInTheDocument();
    const create = screen.getByTestId('ready-create');
    expect(create).toBeDisabled();
    await user.click(screen.getByLabelText(en.accounts.ready.confirm));
    await user.click(create);
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    await user.click(screen.getByTestId('ready-create'));
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts[0]?.path).toBe(`/platform/partners/from-application/${APPLICATION}`);
    expect(JSON.parse(posts[0]?.body ?? '{}')).toEqual({ confirmed: true });
    expect(posts[0]?.key).toMatch(/^web-/u);
    // A retry of the same payload reuses the key.
    if (posts.length > 1) expect(posts[1]?.key).toBe(posts[0]?.key);
    expect(posts.every((p) => !p.path.includes('?'))).toBe(true);
  });

  it('links an application that already has a partner instead of offering to create another', async () => {
    stub((r) =>
      r.path.startsWith('/platform/partners/ready-applications')
        ? {
            status: 200,
            body: {
              items: [
                {
                  applicationReference: APPLICATION,
                  legalName: 'Synthetic Test Bank SA',
                  tradingName: null,
                  countryOfRegistration: 'CD',
                  primaryCategory: 'BANK',
                  approvedAt: '2026-11-20T09:00:00Z',
                  partnerReference: PARTNER,
                },
              ],
              nextCursor: null,
            },
          }
        : {
            status: 200,
            body: {
              items: [],
              nextCursor: null,
              statusCounts: { DRAFT: 0, ACTIVE: 0, SUSPENDED: 0, CLOSED: 0 },
            },
          },
    );
    await renderWithSession(
      <PartnerAccountsPage />,
      sessionWithRoles(['platform-admin']),
      'en',
      '/admin/partners',
    );
    expect(await screen.findByTestId('ready-open')).toHaveAttribute(
      'href',
      `/admin/partners/${PARTNER}`,
    );
    expect(screen.queryByTestId('ready-start')).toBeNull();
  });
});

const detail = (status: string, allowed: string[]) => ({
  reference: PARTNER,
  applicationReference: APPLICATION,
  status,
  version: 3,
  legalName: 'Synthetic Test Bank SA',
  tradingName: null,
  countryOfRegistration: 'CD',
  primaryCategory: 'BANK',
  additionalCategories: [],
  capabilities: ['SALARY_ADVANCE'],
  website: 'https://example.test',
  supportedLanguages: ['fr'],
  countries: ['CD'],
  allowedActions: allowed,
  productCount: 0,
  activeProductCount: 0,
  createdAt: '2026-11-20T09:00:00Z',
  updatedAt: '2026-11-20T09:00:00Z',
});

async function openPartner(status: string, allowed: string[], locale: 'en' | 'fr' = 'en') {
  const requests = stub((r) => {
    if (r.method === 'POST') return { status: 200, body: { partner: detail(status, allowed) } };
    if (r.path.includes('/history'))
      return {
        status: 200,
        body: {
          items: [
            {
              sequence: 1,
              eventType: 'CREATED',
              fromStatus: null,
              toStatus: 'DRAFT',
              reasonCode: null,
              noteRecorded: false,
              itemCount: null,
              actorType: 'PLATFORM_ADMIN',
              occurredAt: '2026-11-20T09:00:00Z',
            },
          ],
          nextCursor: null,
        },
      };
    if (r.path.endsWith('/products')) return { status: 200, body: { items: [] } };
    return { status: 200, body: detail(status, allowed) };
  });
  await renderWithSession(
    <Routes>
      <Route path="/admin/partners/:reference" element={<PartnerAccountDetailPage />} />
    </Routes>,
    sessionWithRoles(['platform-admin']),
    locale,
    `/admin/partners/${PARTNER}`,
  );
  await screen.findByTestId('account-detail-page');
  await screen.findByTestId('account-identity');
  return { requests, user: userEvent.setup() };
}

describe('partner account detail', () => {
  it('shows identity and the boundary note, and never a contact or a licence reference', async () => {
    await openPartner('DRAFT', ['ACTIVATE', 'CLOSE']);
    expect(screen.getByTestId('account-boundary')).toHaveTextContent(en.accounts.boundary);
    expect(screen.getByTestId('account-reference')).toHaveTextContent(PARTNER);
    expect(document.body.textContent).not.toMatch(/@|LIC-|SYN-|privacy/iu);
  });

  it('offers only the actions the server allows', async () => {
    await openPartner('DRAFT', ['ACTIVATE', 'CLOSE']);
    const select = screen.getByTestId('lifecycle-action');
    const options = within(select)
      .getAllByRole('option')
      .map((o) => o.getAttribute('value'));
    expect(options).toEqual(['', 'ACTIVATE', 'CLOSE']);
  });

  it('requires confirmation to activate and sends the version it saw', async () => {
    const { requests, user } = await openPartner('DRAFT', ['ACTIVATE']);
    await user.selectOptions(screen.getByTestId('lifecycle-action'), 'ACTIVATE');
    const submit = screen.getByTestId('lifecycle-submit');
    expect(submit).toBeDisabled();
    await user.click(screen.getByTestId('lifecycle-confirm'));
    await user.click(submit);
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.path).toBe(`/platform/partners/${PARTNER}/transitions`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({
      action: 'ACTIVATE',
      expectedVersion: 3,
      confirmed: true,
    });
    expect(post?.key).toMatch(/^web-/u);
  });

  it('requires a reason to suspend and a note for “Other”, keeping the note out of the address', async () => {
    const { requests, user } = await openPartner('ACTIVE', ['SUSPEND', 'AMEND_SCOPE']);
    await user.selectOptions(screen.getByTestId('lifecycle-action'), 'SUSPEND');
    const submit = screen.getByTestId('lifecycle-submit');
    expect(submit).toBeDisabled();
    await user.selectOptions(screen.getByTestId('lifecycle-reason'), 'OTHER');
    expect(submit).toBeDisabled();
    await user.type(screen.getByLabelText(en.accounts.detail.note), 'internal-note-zx81');
    expect(submit).toBeEnabled();
    await user.click(submit);
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(JSON.parse(post?.body ?? '{}')).toEqual({
      action: 'SUSPEND',
      expectedVersion: 3,
      reasonCode: 'OTHER',
      note: 'internal-note-zx81',
    });
    for (const request of requests) expect(request.path).not.toContain('internal-note');
    expect(window.location.search).toBe('');
  });

  it('shows a closed partner without actions', async () => {
    await openPartner('CLOSED', []);
    expect(screen.getByTestId('lifecycle-closed')).toBeInTheDocument();
    expect(screen.getByTestId('new-product-closed')).toBeInTheDocument();
  });

  it('has no accessibility violations in French', async () => {
    await openPartner('DRAFT', ['ACTIVATE'], 'fr');
    const results = await axe.run(document.body);
    expect(results.violations).toEqual([]);
  });
});
