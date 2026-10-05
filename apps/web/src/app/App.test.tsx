import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { describe, expect, it, vi } from 'vitest';
import { renderApp } from '../test/renderApp';

function stubStatusFetch(core: 'UP' | 'DOWN' | 'offline', ai: 'UP' | 'DOWN' | 'offline') {
  const body = (service: string, status: string) =>
    JSON.stringify({ service, status, version: '0.1.0', checkedAt: '2026-09-28T12:00:00Z' });
  vi.stubGlobal(
    'fetch',
    vi.fn((input: Request) => {
      const isCore = input.url.startsWith('http://core.test');
      const state = isCore ? core : ai;
      if (state === 'offline') return Promise.reject(new TypeError('Failed to fetch'));
      return Promise.resolve(
        new Response(body(isCore ? 'core-api' : 'ai-service', state), {
          status: state === 'UP' ? 200 : 503,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }),
  );
}

async function expectNoAxeViolations(container: HTMLElement) {
  const result = await axe.run(container, {
    rules: { 'color-contrast': { enabled: false } }, // jsdom cannot compute colours
  });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

describe.each(['en', 'fr'] as const)('application shell (%s)', (locale) => {
  const strings = resources[locale].common;

  it('renders the public shell with no raw translation keys', async () => {
    stubStatusFetch('UP', 'UP');
    const { container } = await renderApp('/', locale);
    expect(document.documentElement.lang).toBe(locale);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(strings.home.title);
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content');
    // UI-001: anonymous visitors get the public frame, without the signed-in navigation.
    expect(screen.queryByRole('navigation', { name: strings.nav.primary })).toBeNull();
    expect(screen.getByTestId('environment-badge')).toHaveTextContent(
      strings.shell.environment.development,
    );
    expect(container.textContent).not.toMatch(
      /\b(nav|auth|home|status|theme|locale|shell|roadmap)\.[a-z]/,
    );
  });

  it('shows both services on the status page', async () => {
    stubStatusFetch('UP', 'offline');
    await renderApp('/status', locale);
    await waitFor(() => {
      expect(screen.getByTestId('status-core-api')).toHaveAttribute('data-state', 'up');
    });
    expect(screen.getByTestId('status-ai-service')).toHaveAttribute('data-state', 'unreachable');
    expect(
      within(screen.getByTestId('status-core-api')).getByText(strings.status.state.up),
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId('status-ai-service')).getByText(strings.status.state.unreachable),
    ).toBeInTheDocument();
  });

  it('has no detectable accessibility violations', async () => {
    stubStatusFetch('UP', 'DOWN');
    const { container } = await renderApp('/status', locale);
    await waitFor(() => {
      expect(screen.getByTestId('status-ai-service')).toHaveAttribute('data-state', 'down');
    });
    await expectNoAxeViolations(container);
  });
});

describe('locale switching', () => {
  it('switches the whole shell between French and English and remembers the choice', async () => {
    stubStatusFetch('UP', 'UP');
    const user = userEvent.setup();
    await renderApp('/', 'en');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Welcome to DivalHR');

    await user.click(screen.getByRole('button', { name: 'Français' }));
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Bienvenue sur DivalHR');
    expect(screen.getByRole('link', { name: 'État du système' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Français' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(document.documentElement.lang).toBe('fr');
    expect(window.localStorage.getItem('divalhr.locale')).toBe('fr');

    await user.click(screen.getByRole('button', { name: 'English' }));
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Welcome to DivalHR');
    expect(window.localStorage.getItem('divalhr.locale')).toBe('en');
  });
});

describe('theme', () => {
  it('applies and remembers the selected theme', async () => {
    stubStatusFetch('UP', 'UP');
    const user = userEvent.setup();
    await renderApp('/', 'en');
    await user.selectOptions(screen.getByLabelText('Theme'), 'dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(window.localStorage.getItem('divalhr.theme')).toBe('dark');
  });
});
