import { resources } from '@divalhr/localization';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import type { UserManager } from 'oidc-client-ts';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useEffect } from 'react';
import { ApiProvider, useApi } from '../app/ApiProvider';
import { initI18n } from '../i18n';
import { fakeUserManager, testConfig } from '../test/renderApp';
import { AuthProvider, MFA_ACR, useAuth } from './AuthProvider';
import { MfaRequiredPage } from './MfaRequiredPage';

type Auth = ReturnType<typeof useAuth>;

function managerWith(stepUpCallback: boolean) {
  const manager = fakeUserManager() as unknown as Record<string, unknown>;
  const signinRedirect = vi.fn(() => Promise.resolve());
  manager.signinRedirect = signinRedirect;
  manager.signinRedirectCallback = () =>
    Promise.resolve({
      access_token: 'test-only-access-token',
      state: { returnTo: '/admin/users', stepUp: stepUpCallback },
    });
  return { manager: manager as unknown as UserManager, signinRedirect };
}

function renderAuth(manager: UserManager) {
  const auth: { current?: Auth } = {};
  function Probe() {
    auth.current = useAuth();
    return auth.current.mfaBlocked ? <p>blocked {auth.current.mfaBlocked.returnTo}</p> : null;
  }
  render(
    <AuthProvider userManager={manager}>
      <Probe />
    </AuthProvider>,
  );
  return auth as { current: Auth };
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('MFA step-up (MVP-011)', () => {
  it('redirects once to the MFA level and returns to the page', async () => {
    await initI18n('en');
    const { manager, signinRedirect } = managerWith(false);
    const auth = renderAuth(manager);
    act(() => {
      auth.current.requireStepUp('/admin/users?status=PENDING');
      // Concurrent refusals while the redirect is starting do not start a second one.
      auth.current.requireStepUp('/admin/users?status=PENDING');
    });
    expect(signinRedirect).toHaveBeenCalledTimes(1);
    expect(signinRedirect).toHaveBeenCalledWith({
      state: { returnTo: '/admin/users?status=PENDING', stepUp: true },
      extraQueryParams: { ui_locales: 'en', acr_values: MFA_ACR },
    });
  });

  it('never loops: a refusal after a completed step-up shows the MFA-required page', async () => {
    await initI18n('en');
    const { manager, signinRedirect } = managerWith(true);
    const auth = renderAuth(manager);
    await act(async () => {
      await auth.current.completeSignIn();
    });
    act(() => {
      auth.current.requireStepUp('/admin/users');
    });
    expect(signinRedirect).not.toHaveBeenCalled();
    expect(screen.getByText('blocked /admin/users')).toBeInTheDocument();
  });

  it('a normal sign-in still allows one automatic step-up', async () => {
    await initI18n('en');
    const { manager, signinRedirect } = managerWith(false);
    const auth = renderAuth(manager);
    await act(async () => {
      await auth.current.completeSignIn();
    });
    act(() => {
      auth.current.requireStepUp('/admin/hierarchy');
    });
    expect(signinRedirect).toHaveBeenCalledTimes(1);
  });

  it('a privileged API refusal starts the step-up from the current page', async () => {
    await initI18n('fr');
    const { manager, signinRedirect } = managerWith(false);
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ status: 403, code: 'MFA_REQUIRED', params: {} }), {
            status: 403,
            headers: { 'Content-Type': 'application/problem+json' },
          }),
        ),
      ),
    );
    window.history.pushState({}, '', '/admin/users?status=PENDING');
    function CallsTheCore() {
      const { core } = useApi();
      useEffect(() => {
        void core.GET('/invitations');
      }, [core]);
      return null;
    }
    render(
      <AuthProvider userManager={manager}>
        <ApiProvider config={testConfig}>
          <CallsTheCore />
        </ApiProvider>
      </AuthProvider>,
    );
    await vi.waitFor(() => {
      expect(signinRedirect).toHaveBeenCalledWith({
        state: { returnTo: '/admin/users?status=PENDING', stepUp: true },
        extraQueryParams: { ui_locales: 'fr', acr_values: MFA_ACR },
      });
    });
  });
});

describe.each(['en', 'fr'] as const)('MFA-required page (%s)', (locale) => {
  const strings = resources[locale].common;

  it('explains the requirement, offers a user-initiated retry and lost-device help', async () => {
    await initI18n(locale);
    const { manager, signinRedirect } = managerWith(false);
    const { container } = render(
      <AuthProvider userManager={manager}>
        <MfaRequiredPage returnTo="/admin/users" />
      </AuthProvider>,
    );
    const heading = screen.getByRole('heading', { level: 1, name: strings.mfa.title });
    expect(heading).toHaveFocus();
    expect(screen.getByText(strings.mfa.help.lostDevice)).toBeInTheDocument();
    expect(screen.getByText(strings.mfa.help.noAuthenticator)).toBeInTheDocument();
    expect(signinRedirect).not.toHaveBeenCalled();
    await expectAccessible(container);

    await userEvent.click(screen.getByRole('button', { name: strings.mfa.retry }));
    expect(signinRedirect).toHaveBeenCalledWith({
      state: { returnTo: '/admin/users', stepUp: true },
      extraQueryParams: { ui_locales: locale, acr_values: MFA_ACR },
    });
  });
});
