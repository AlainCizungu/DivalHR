import { useCallback, useEffect, useState } from 'react';
import { readPreference, writePreference } from '../app/preferences';

/**
 * Tracks a media query. Breakpoints are in em so browser zoom moves the page between layouts.
 * Without matchMedia (jsdom) the fallback applies.
 */
export function useMediaQuery(query: string, fallback: boolean): boolean {
  const supported = typeof window.matchMedia === 'function';
  const [matches, setMatches] = useState(() =>
    supported ? window.matchMedia(query).matches : fallback,
  );
  useEffect(() => {
    if (!supported) return;
    const list = window.matchMedia(query);
    const onChange = () => {
      setMatches(list.matches);
    };
    onChange();
    list.addEventListener('change', onChange);
    return () => {
      list.removeEventListener('change', onChange);
    };
  }, [query, supported]);
  return matches;
}

export const DESKTOP_QUERY = '(min-width: 64em)';
export const MOBILE_QUERY = '(max-width: 47.99em)';

/**
 * UI-001: whether the sidebar shows as an icon rail. On desktop the choice is remembered (the only
 * new preference, D8); on tablet the rail is the default and a choice lasts for this page view.
 */
export function useSidebarCollapsed(isDesktop: boolean): [boolean, () => void] {
  const [desktopCollapsed, setDesktopCollapsed] = useState(
    () => readPreference('sidebar') === 'collapsed',
  );
  const [tabletCollapsed, setTabletCollapsed] = useState(true);
  const collapsed = isDesktop ? desktopCollapsed : tabletCollapsed;
  const toggle = useCallback(() => {
    if (isDesktop) {
      const next = !desktopCollapsed;
      writePreference('sidebar', next ? 'collapsed' : 'expanded');
      setDesktopCollapsed(next);
    } else {
      setTabletCollapsed((value) => !value);
    }
  }, [isDesktop, desktopCollapsed]);
  return [collapsed, toggle];
}
