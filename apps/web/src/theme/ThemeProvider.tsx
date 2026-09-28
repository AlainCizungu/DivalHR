import {
  applyTheme,
  isThemeMode,
  resolveTheme,
  type ResolvedTheme,
  type ThemeMode,
} from '@divalhr/design-system';
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { readPreference, writePreference } from '../app/preferences';

interface ThemeContextValue {
  mode: ThemeMode;
  resolved: ResolvedTheme;
  setMode: (mode: ThemeMode) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);
const DARK_QUERY = '(prefers-color-scheme: dark)';

function prefersDark(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches;
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(() => {
    const stored = readPreference('theme');
    return isThemeMode(stored) ? stored : 'system';
  });
  const [systemDark, setSystemDark] = useState(prefersDark);

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia(DARK_QUERY);
    const onChange = (event: MediaQueryListEvent) => {
      setSystemDark(event.matches);
    };
    query.addEventListener('change', onChange);
    return () => {
      query.removeEventListener('change', onChange);
    };
  }, []);

  const resolved = resolveTheme(mode, systemDark);
  useEffect(() => {
    applyTheme(document.documentElement, resolved);
  }, [resolved]);

  const value = useMemo<ThemeContextValue>(
    () => ({
      mode,
      resolved,
      setMode: (next) => {
        writePreference('theme', next);
        setModeState(next);
      },
    }),
    [mode, resolved],
  );
  return <ThemeContext value={value}>{children}</ThemeContext>;
}

export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (!context) throw new Error('useTheme must be used inside ThemeProvider');
  return context;
}
