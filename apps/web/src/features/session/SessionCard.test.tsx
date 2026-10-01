import { resources } from '@divalhr/localization';
import { screen } from '@testing-library/react';
import axe from 'axe-core';
import { describe, expect, it } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { SessionCard } from './SessionCard';

// MVP-012A (A12A-1): a platform administrator without a tenant claim has a session whose tenant is
// null; the card says so instead of showing an empty value. Effective roles come from the server.
describe.each(['en', 'fr'] as const)('session card (%s)', (locale) => {
  const s = resources[locale].common.session;

  it('shows the explicit no-organization text for a tenantless platform session', async () => {
    const { container } = await renderWithSession(
      <SessionCard />,
      { kind: 'ready', session: { tenantId: null, roles: ['platform-admin'] } },
      locale,
    );
    expect(screen.getByTestId('session-tenant')).toHaveTextContent(s.noTenant);
    expect(screen.getByTestId('session-roles')).toHaveTextContent(s.role['platform-admin']);
    const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });

  it('shows the tenant and "no roles" when no tenant role is effective', async () => {
    await renderWithSession(<SessionCard />, sessionWithRoles([]), locale);
    expect(screen.getByTestId('session-tenant')).toHaveTextContent(
      '00000000-0000-4000-8000-00000000000a',
    );
    expect(screen.getByTestId('session-roles')).toHaveTextContent(s.noRoles);
  });
});
