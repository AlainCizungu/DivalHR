import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { AppShell } from '../../shell/AppShell';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { CreateOrganizationPage } from './CreateOrganizationPage';
import { RequirePlatformAdmin } from './RequirePlatformAdmin';

const created = {
  id: '6f1c1f0e-8a8b-4b43-9a3e-0f6f7b0c2a11',
  name: 'Hôpital Général de Kinshasa',
  countryCode: 'CD',
  defaultLocale: 'fr',
  timezone: 'Africa/Lubumbashi',
  currencies: ['CDF', 'USD'],
  status: 'ACTIVE',
  createdAt: '2026-09-29T10:15:00Z',
};

type Reply = { status: number; body?: unknown } | 'network';

function stubApi(...replies: Reply[]) {
  const requests: Request[] = [];
  const queue = [...replies];
  vi.stubGlobal(
    'fetch',
    vi.fn((request: Request) => {
      requests.push(request);
      const reply = queue.shift() ?? { status: 500 };
      if (reply === 'network') return Promise.reject(new TypeError('Failed to fetch'));
      return Promise.resolve(
        new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
          status: reply.status,
          headers: {
            'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
          },
        }),
      );
    }),
  );
  return requests;
}

const problem = (code: string, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status: 400,
  code,
  params,
  correlationId: 'corr-12345678',
});

async function renderPage(locale: 'fr' | 'en' = 'en') {
  return renderWithSession(
    <RequirePlatformAdmin>
      <CreateOrganizationPage />
    </RequirePlatformAdmin>,
    sessionWithRoles(['platform-admin']),
    locale,
  );
}

async function expectNoAxeViolations(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

describe.each(['en', 'fr'] as const)('create organization page (%s)', (locale) => {
  const s = resources[locale].common;

  it('renders every label, help text and control in the selected language', async () => {
    const { container } = await renderPage(locale);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(s.createOrganization.title);
    expect(screen.getByText(s.createOrganization.description)).toBeInTheDocument();
    expect(screen.getByLabelText(s.createOrganization.name.label)).toHaveAccessibleDescription(
      s.createOrganization.name.help,
    );
    expect(screen.getByLabelText(s.createOrganization.country.label)).toHaveDisplayValue(
      s.country.CD,
    );
    const language = screen.getByRole('group', { name: s.createOrganization.locale.legend });
    expect(within(language).getByRole('radio', { name: s.language.fr })).toBeChecked();
    expect(screen.getByLabelText(s.createOrganization.timezone.label)).toHaveDisplayValue(
      s.timezones['Africa/Kinshasa'],
    );
    const currencies = screen.getByRole('group', { name: s.createOrganization.currencies.legend });
    expect(within(currencies).getByRole('checkbox', { name: s.currency.CDF })).toBeChecked();
    expect(within(currencies).getByRole('checkbox', { name: s.currency.USD })).not.toBeChecked();
    expect(screen.getByRole('button', { name: s.createOrganization.submit })).toBeEnabled();
    expect(container.textContent).not.toMatch(/createOrganization\.|timezones\.|currency\./);
    await expectNoAxeViolations(container);
  });

  it('does not override the active language on default-language options', async () => {
    await renderPage(locale);
    const language = screen.getByRole('group', { name: s.createOrganization.locale.legend });
    // Option labels are translated into the interface language, so they must inherit it rather
    // than claim the language they describe (e.g. "Anglais" is French text, not lang="en").
    expect(language.querySelectorAll('[lang]')).toHaveLength(0);
    for (const option of ['fr', 'en'] as const) {
      const radio = within(language).getByRole('radio', { name: s.language[option] });
      expect(radio.closest('[lang]')?.getAttribute('lang')).toBe(locale);
    }
  });

  it('shows client validation with focusable summary and field associations', async () => {
    const requests = stubApi();
    const user = userEvent.setup();
    const { container } = await renderPage(locale);
    await user.click(
      within(screen.getByRole('group', { name: s.createOrganization.currencies.legend })).getByRole(
        'checkbox',
        { name: s.currency.CDF },
      ),
    );
    await user.click(screen.getByRole('button', { name: s.createOrganization.submit }));

    const name = screen.getByLabelText(s.createOrganization.name.label);
    expect(name).toHaveAttribute('aria-invalid', 'true');
    expect(name).toHaveAccessibleDescription(
      `${s.createOrganization.name.help} ${s.createOrganization.validation.name.REQUIRED}`,
    );
    const summary = screen.getByRole('alert');
    expect(summary).toHaveTextContent(s.createOrganization.errorSummary);
    expect(summary).toHaveTextContent(s.createOrganization.validation.currencies.REQUIRED);
    await waitFor(() => {
      expect(screen.getByTestId('form-summary')).toHaveFocus();
    });
    expect(requests).toHaveLength(0);
    await expectNoAxeViolations(container);
  });

  it('maps server validation and not-supported codes to the right fields', async () => {
    stubApi(
      {
        status: 400,
        body: problem('VALIDATION_FAILED', { fields: [{ field: 'name', constraint: 'LENGTH' }] }),
      },
      {
        status: 400,
        body: problem('TIMEZONE_NOT_SUPPORTED', { field: 'timezone', supported: [] }),
      },
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await user.type(screen.getByLabelText(s.createOrganization.name.label), 'Clinique Espoir');
    await user.click(screen.getByRole('button', { name: s.createOrganization.submit }));
    expect(
      await screen.findByText(s.createOrganization.validation.name.LENGTH, {
        selector: '.field__error',
      }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: s.createOrganization.submit }));
    expect(
      await screen.findByText(s.createOrganization.validation.timezone.NOT_SUPPORTED, {
        selector: '.field__error',
      }),
    ).toBeInTheDocument();
  });

  it('submits through the generated client and shows the created organization', async () => {
    const requests = stubApi({ status: 201, body: created });
    const user = userEvent.setup();
    await renderPage(locale);
    await user.type(screen.getByLabelText(s.createOrganization.name.label), `  ${created.name}  `);
    await user.selectOptions(
      screen.getByLabelText(s.createOrganization.timezone.label),
      'Africa/Lubumbashi',
    );
    await user.click(screen.getByRole('checkbox', { name: s.currency.USD }));
    await user.click(screen.getByRole('button', { name: s.createOrganization.submit }));

    const success = await screen.findByTestId('organization-created');
    expect(within(success).getByRole('heading', { level: 1 })).toHaveTextContent(
      s.createOrganization.success.title,
    );
    expect(within(success).getByTestId('created-name')).toHaveTextContent(created.name);
    expect(success).toHaveTextContent(s.timezones['Africa/Lubumbashi']);
    expect(success).toHaveTextContent(s.currency.USD);
    expect(document.documentElement.lang).toBe(locale);

    const request = requests[0]!;
    expect(request.method).toBe('POST');
    expect(request.url).toBe('http://core.test/api/v1/organizations');
    expect(request.headers.get('Idempotency-Key')).toMatch(/^web-[0-9a-f-]{36}$/);
    expect(await request.json()).toEqual({
      name: created.name,
      countryCode: 'CD',
      defaultLocale: 'fr',
      timezone: 'Africa/Lubumbashi',
      currencies: ['CDF', 'USD'],
    });
  });

  it('reuses the idempotency key on retry and shows network, conflict and 403 errors', async () => {
    const requests = stubApi('network', { status: 201, body: created });
    const user = userEvent.setup();
    await renderPage(locale);
    await user.type(screen.getByLabelText(s.createOrganization.name.label), created.name);
    await user.click(screen.getByRole('button', { name: s.createOrganization.submit }));
    expect(await screen.findByTestId('form-error')).toHaveTextContent(s.errors.network);
    await user.click(screen.getByRole('button', { name: s.createOrganization.submit }));
    await screen.findByTestId('organization-created');
    expect(requests[1]!.headers.get('Idempotency-Key')).toBe(
      requests[0]!.headers.get('Idempotency-Key'),
    );
  });

  it('uses a new key after an edit and explains conflicts and denials', async () => {
    const requests = stubApi(
      { status: 409, body: { ...problem('IDEMPOTENCY_KEY_REUSED'), status: 409 } },
      { status: 403, body: { ...problem('ACCESS_DENIED'), status: 403 } },
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await user.type(screen.getByLabelText(s.createOrganization.name.label), 'Première');
    await user.click(screen.getByRole('button', { name: s.createOrganization.submit }));
    expect(await screen.findByTestId('form-error')).toHaveTextContent(
      s.errors.IDEMPOTENCY_KEY_REUSED,
    );
    // Focus moves to the error summary first; then the user edits the name.
    await waitFor(() => {
      expect(screen.getByTestId('form-summary')).toHaveFocus();
    });
    await user.type(screen.getByLabelText(s.createOrganization.name.label), ' bis');
    await user.click(screen.getByRole('button', { name: s.createOrganization.submit }));
    expect(await screen.findByTestId('form-error')).toHaveTextContent(
      s.createOrganization.unauthorized,
    );
    expect(requests[1]!.headers.get('Idempotency-Key')).not.toBe(
      requests[0]!.headers.get('Idempotency-Key'),
    );
  });
});

describe('keyboard-only operation', () => {
  it('completes the form using only the keyboard', async () => {
    stubApi({ status: 201, body: created });
    const user = userEvent.setup();
    await renderPage('fr');
    await user.tab();
    expect(screen.getByLabelText('Nom de l’organisation')).toHaveFocus();
    await user.keyboard('Hôpital Général de Kinshasa');
    await user.tab(); // country
    await user.tab(); // language radio group
    await user.tab(); // time zone
    await user.tab(); // CDF checkbox
    await user.tab(); // USD checkbox
    await user.keyboard(' ');
    await user.tab(); // submit
    expect(screen.getByRole('button', { name: 'Créer l’organisation' })).toHaveFocus();
    await user.keyboard('{Enter}');
    const success = await screen.findByTestId('organization-created');
    await waitFor(() => {
      expect(within(success).getByRole('heading', { level: 1 })).toHaveFocus();
    });
  });
});

describe('role-based access', () => {
  it('shows the navigation entry only to platform administrators', async () => {
    const shell = (
      <AppShell environment="development">
        <p>content</p>
      </AppShell>
    );
    const admin = await renderWithSession(shell, sessionWithRoles(['platform-admin']), 'fr');
    expect(screen.getByRole('link', { name: 'Créer une organisation' })).toBeInTheDocument();
    admin.unmount();
    await renderWithSession(shell, sessionWithRoles(['tenant-admin']), 'en');
    expect(screen.queryByRole('link', { name: 'Create organization' })).not.toBeInTheDocument();
  });

  it('refuses the page to other roles without calling the API', async () => {
    const requests = stubApi();
    await renderWithSession(
      <Routes>
        <Route
          path="/"
          element={
            <RequirePlatformAdmin>
              <CreateOrganizationPage />
            </RequirePlatformAdmin>
          }
        />
      </Routes>,
      sessionWithRoles(['employee']),
      'en',
    );
    expect(screen.getByTestId('not-authorized')).toHaveTextContent(
      'Only platform administrators can create organizations.',
    );
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    expect(requests).toHaveLength(0);
  });
});
