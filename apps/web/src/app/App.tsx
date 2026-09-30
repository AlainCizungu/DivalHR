import type { UserManager } from 'oidc-client-ts';
import type { ReactNode } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router';
import { AuthProvider, useAuth } from '../auth/AuthProvider';
import { CallbackPage } from '../auth/CallbackPage';
import { MfaRequiredPage } from '../auth/MfaRequiredPage';
import type { RuntimeConfig } from '../config/runtime';
import { CreateOrganizationPage } from '../features/admin/CreateOrganizationPage';
import { RequirePlatformAdmin } from '../features/admin/RequirePlatformAdmin';
import { HierarchyPage } from '../features/hierarchy/HierarchyPage';
import { AcceptInvitationPage } from '../features/invitation/AcceptInvitationPage';
import { RequireRole } from '../features/hierarchy/RequireRole';
import { HomePage } from '../features/home/HomePage';
import { StatusPage } from '../features/status/StatusPage';
import { UsersPage } from '../features/users/UsersPage';
import { AppShell } from '../layout/AppShell';
import { NotFoundPage } from '../layout/NotFoundPage';
import { ThemeProvider } from '../theme/ThemeProvider';
import { ApiProvider } from './ApiProvider';
import { SessionProvider } from './SessionProvider';

/** After a refused step-up, the MFA-required page replaces the current page (MVP-011). */
function MfaGate({ children }: { children: ReactNode }) {
  const { mfaBlocked } = useAuth();
  return mfaBlocked ? <MfaRequiredPage returnTo={mfaBlocked.returnTo} /> : children;
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
                    <Route path="/" element={<HomePage />} />
                    <Route path="/status" element={<StatusPage />} />
                    <Route
                      path="/admin/organizations/new"
                      element={
                        <RequirePlatformAdmin>
                          <CreateOrganizationPage />
                        </RequirePlatformAdmin>
                      }
                    />
                    <Route
                      path="/admin/hierarchy"
                      element={
                        <RequireRole
                          requiredRole="tenant-admin"
                          deniedKey="hierarchy.unauthorized"
                          signInKey="hierarchy.signInRequired"
                        >
                          <HierarchyPage />
                        </RequireRole>
                      }
                    />
                    <Route
                      path="/admin/users"
                      element={
                        <RequireRole
                          requiredRole="tenant-admin"
                          deniedKey="users.unauthorized"
                          signInKey="users.signInRequired"
                        >
                          <UsersPage />
                        </RequireRole>
                      }
                    />
                    <Route path="/invitation" element={<AcceptInvitationPage />} />
                    <Route path="/auth/callback" element={<CallbackPage />} />
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
