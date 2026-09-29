import type { CurrentSession } from '@divalhr/api-client';
import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { ApiProvider } from '../app/ApiProvider';
import { SessionContext, type SessionState } from '../app/SessionProvider';
import { AuthProvider } from '../auth/AuthProvider';
import { initI18n } from '../i18n';
import { ThemeProvider } from '../theme/ThemeProvider';
import { fakeUserManager, testConfig } from './renderApp';

export function sessionWithRoles(roles: CurrentSession['roles']): SessionState {
  return {
    kind: 'ready',
    session: { tenantId: '00000000-0000-4000-8000-00000000000a', roles },
  };
}

export async function renderWithSession(
  ui: ReactNode,
  session: SessionState,
  locale: 'fr' | 'en' = 'en',
  path = '/',
) {
  await initI18n(locale);
  return render(
    <ThemeProvider>
      <AuthProvider userManager={fakeUserManager()}>
        <ApiProvider config={testConfig}>
          <SessionContext value={session}>
            <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
          </SessionContext>
        </ApiProvider>
      </AuthProvider>
    </ThemeProvider>,
  );
}
