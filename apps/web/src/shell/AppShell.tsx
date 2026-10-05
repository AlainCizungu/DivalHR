import { useId, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, matchRoutes, useLocation } from 'react-router';
import { ROUTES, type AppRoute } from '../app/routes';
import { useSession } from '../app/SessionProvider';
import { useAuth } from '../auth/AuthProvider';
import type { Environment } from '../config/runtime';
import { Icon } from '../ui/Icon';
import { Breadcrumbs } from '../ui/primitives';
import { BrandMark, EnvironmentBadge, LanguageSwitcher, ThemeSelect } from './controls';
import { DESKTOP_QUERY, useMediaQuery, useSidebarCollapsed } from './hooks';
import { MobileNavDrawer } from './MobileNavDrawer';
import { Brand, Sidebar } from './Navigation';
import { activeItemPath, navigationRoles, visibleGroups } from './navigation';
import { UserMenu } from './UserMenu';

const MATCHABLE = ROUTES.map((route) => ({ path: route.path, appRoute: route }));

/** The registry entry for the current URL (static segments rank above parameters). */
function useMatchedRoute(): AppRoute | null {
  const location = useLocation();
  return matchRoutes(MATCHABLE, location)?.[0]?.route.appRoute ?? null;
}

function SkipLink() {
  const { t } = useTranslation();
  return (
    <a className="skip-link" href="#main-content">
      {t('nav.skipToContent')}
    </a>
  );
}

function Main({ children, route }: { children: ReactNode; route: AppRoute | null }) {
  const { t } = useTranslation();
  const crumbs = (route?.crumbs ?? []).map((crumb) => ({ label: t(crumb.key), to: crumb.to }));
  return (
    <main id="main-content" className="main" tabIndex={-1}>
      <Breadcrumbs items={crumbs} />
      {children}
    </main>
  );
}

function AuthButton() {
  const { t } = useTranslation();
  const { status, signIn, signOut } = useAuth();
  return status === 'authenticated' ? (
    <button type="button" className="button button--secondary" onClick={() => void signOut()}>
      {t('auth.signOut')}
    </button>
  ) : (
    <button type="button" className="button" onClick={() => void signIn(window.location.pathname)}>
      {t('auth.signIn')}
    </button>
  );
}

/** Anonymous visitors and the public flows (invitation, sign-in callback): no sidebar, no user menu. */
function PublicFrame({
  environment,
  route,
  children,
}: {
  environment: Environment;
  route: AppRoute | null;
  children: ReactNode;
}) {
  return (
    <div className="public-frame">
      <SkipLink />
      <header className="topbar topbar--public">
        <Brand />
        <span className="topbar__spacer" />
        <EnvironmentBadge environment={environment} />
        <LanguageSwitcher />
        <ThemeSelect />
        <AuthButton />
      </header>
      <Main route={route?.frame === 'public' ? null : route}>{children}</Main>
    </div>
  );
}

function WorkspaceContext({ platformOnly }: { platformOnly: boolean }) {
  const { t } = useTranslation();
  return (
    <div className="workspace" data-testid="workspace-context">
      <span className="workspace__icon">
        <Icon name={platformOnly ? 'globe' : 'organization'} />
      </span>
      <span className="workspace__text">
        <span className="workspace__label">{t('shell.workspace')}</span>
        <span className="workspace__value">
          {platformOnly ? t('shell.workspacePlatform') : t('shell.workspaceOrganization')}
        </span>
      </span>
    </div>
  );
}

/**
 * UI-001 application shell (proposal §3–§6, Issue #53). The shell adds landmarks, navigation,
 * breadcrumbs, workspace context, the account menu and the environment badge; pages keep their own
 * h1 and content. Navigation shows only destinations the verified session's roles may open; the
 * route guards and the Core API remain the authority.
 */
export function AppShell({
  environment,
  children,
}: {
  environment: Environment;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  const session = useSession();
  const route = useMatchedRoute();
  const location = useLocation();
  const isDesktop = useMediaQuery(DESKTOP_QUERY, true);
  const [collapsed, toggleCollapsed] = useSidebarCollapsed(isDesktop);
  // The drawer belongs to the page it was opened on: any navigation closes it.
  const [drawerPath, setDrawerPath] = useState<string | null>(null);
  const drawerOpen = drawerPath === location.pathname;
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const drawerId = useId();

  const roles = navigationRoles(session);
  const groups = visibleGroups(roles);
  const activePath = activeItemPath(route?.path ?? null);
  const loading = session.kind === 'loading';
  const platformOnly = roles.length > 0 && roles.every((role) => role === 'platform-admin');

  if (session.kind === 'anonymous' || route?.frame === 'public') {
    return (
      <PublicFrame environment={environment} route={route}>
        {children}
      </PublicFrame>
    );
  }

  return (
    <div className="app-frame" data-collapsed={collapsed}>
      <SkipLink />
      <Sidebar
        groups={groups}
        activePath={activePath}
        loading={loading}
        collapsed={collapsed}
        onToggle={toggleCollapsed}
      />
      <div className="app-frame__column">
        <header className="topbar">
          <button
            ref={menuButtonRef}
            type="button"
            className="topbar__menu-button"
            aria-expanded={drawerOpen}
            aria-controls={drawerId}
            onClick={() => {
              setDrawerPath(location.pathname);
            }}
          >
            <Icon name="menu" />
            <span className="visually-hidden">{t('shell.openNavigation')}</span>
          </button>
          <Link className="topbar__brand" to="/">
            <BrandMark />
            <span className="topbar__brand-name">{t('app.name')}</span>
          </Link>
          {session.kind === 'ready' && roles.length > 0 && (
            <WorkspaceContext platformOnly={platformOnly} />
          )}
          <span className="topbar__spacer" />
          <EnvironmentBadge environment={environment} />
          <LanguageSwitcher />
          <UserMenu />
        </header>
        <Main route={route}>{children}</Main>
      </div>
      <MobileNavDrawer
        id={drawerId}
        open={drawerOpen}
        onClose={() => {
          setDrawerPath(null);
        }}
        returnFocusTo={menuButtonRef}
        groups={groups}
        activePath={activePath}
        loading={loading}
      />
    </div>
  );
}
