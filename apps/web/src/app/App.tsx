import type { UserManager } from 'oidc-client-ts';
import type { ReactNode } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router';
import { AuthProvider, useAuth } from '../auth/AuthProvider';
import { MfaRequiredPage } from '../auth/MfaRequiredPage';
import type { RuntimeConfig } from '../config/runtime';
import { RequirePlatformAdmin } from '../features/admin/RequirePlatformAdmin';
import { RequireRole } from '../features/hierarchy/RequireRole';
import { NotFoundPage } from '../layout/NotFoundPage';
import { AppShell } from '../shell/AppShell';
import { ThemeProvider } from '../theme/ThemeProvider';
import { ApiProvider } from './ApiProvider';
import { ROUTES, type AppRoute } from './routes';
import { SessionProvider } from './SessionProvider';

/** After a refused step-up, the MFA-required page replaces the current page (MVP-011). */
function MfaGate({ children }: { children: ReactNode }) {
  const { mfaBlocked } = useAuth();
  return mfaBlocked ? <MfaRequiredPage returnTo={mfaBlocked.returnTo} /> : children;
}

/** Each registry entry keeps the guard it had before UI-001 (usability only; the API decides). */
function guarded({ element, access }: AppRoute): ReactNode {
  if (!access) return element;
  if (access.role === 'platform-admin') {
    return (
      <RequirePlatformAdmin deniedKey={access.deniedKey} signInKey={access.signInKey}>
        {element}
      </RequirePlatformAdmin>
    );
  }
  return (
    <RequireRole
      requiredRole={access.role}
      deniedKey={access.deniedKey}
      signInKey={access.signInKey}
    >
      {element}
    </RequireRole>
  );
}

export function App({ config, userManager }: { config: RuntimeConfig; userManager: UserManager }) {
  return (
    <ThemeProvider>
      <AuthProvider userManager={userManager}>
        <ApiProvider config={config}>
          <SessionProvider>
            <BrowserRouter>
              <AppShell environment={config.environment}>
                <MfaGate>
                  <Routes>
                    {ROUTES.map((route) => (
                      <Route key={route.path} path={route.path} element={guarded(route)} />
                    ))}
                    <Route path="*" element={<NotFoundPage />} />
                  </Routes>
                </MfaGate>
              </AppShell>
            </BrowserRouter>
          </SessionProvider>
        </ApiProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}
