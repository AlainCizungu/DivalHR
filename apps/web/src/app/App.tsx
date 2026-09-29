import type { UserManager } from 'oidc-client-ts';
import { BrowserRouter, Route, Routes } from 'react-router';
import { AuthProvider } from '../auth/AuthProvider';
import { CallbackPage } from '../auth/CallbackPage';
import type { RuntimeConfig } from '../config/runtime';
import { CreateOrganizationPage } from '../features/admin/CreateOrganizationPage';
import { RequirePlatformAdmin } from '../features/admin/RequirePlatformAdmin';
import { HierarchyPage } from '../features/hierarchy/HierarchyPage';
import { RequireRole } from '../features/hierarchy/RequireRole';
import { HomePage } from '../features/home/HomePage';
import { StatusPage } from '../features/status/StatusPage';
import { AppShell } from '../layout/AppShell';
import { NotFoundPage } from '../layout/NotFoundPage';
import { ThemeProvider } from '../theme/ThemeProvider';
import { ApiProvider } from './ApiProvider';
import { SessionProvider } from './SessionProvider';

export function App({ config, userManager }: { config: RuntimeConfig; userManager: UserManager }) {
  return (
    <ThemeProvider>
      <AuthProvider userManager={userManager}>
        <ApiProvider config={config}>
          <SessionProvider>
            <BrowserRouter>
              <AppShell environment={config.environment}>
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
                  <Route path="/auth/callback" element={<CallbackPage />} />
                  <Route path="*" element={<NotFoundPage />} />
                </Routes>
              </AppShell>
            </BrowserRouter>
          </SessionProvider>
        </ApiProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}
