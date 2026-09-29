// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { applyTheme, isThemeMode, resolveTheme } from './theme';

describe('theme', () => {
  it('resolves system mode from the OS preference', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
    expect(resolveTheme('light', true)).toBe('light');
  });

  it('validates stored modes', () => {
    expect(isThemeMode('dark')).toBe(true);
    expect(isThemeMode('purple')).toBe(false);
  });

  it('applies the theme to the root element', () => {
    applyTheme(document.documentElement, 'dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
  });
});
