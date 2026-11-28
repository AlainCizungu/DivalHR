import type { AnchorHTMLAttributes, ReactNode } from 'react';
import { Link } from 'react-router';

/**
 * LAUNCH-002 (ADR 0048, L2-1): the commercial website may be served from the canonical public
 * origin (https://divalhr.com) as well as from the application origin (https://app.divalhr.com).
 * Both are optional public runtime settings, set only in production; when they are unset (every other
 * environment) nothing here changes behaviour: links stay relative, the canonical origin is the
 * page's own origin and "Sign in" starts the flow in place.
 *
 * Authentication, the API and the identity provider exist only under the application origin, so on
 * the public origin every call to action that needs them leaves for the application origin.
 */
export interface CommercialOrigins {
  publicOrigin?: string;
  appOrigin?: string;
}

const ORIGIN = /^https:\/\/[a-z0-9.-]+$/u;

function clean(value: unknown): string | undefined {
  return typeof value === 'string' && ORIGIN.test(value) ? value : undefined;
}

export function commercialOrigins(): CommercialOrigins {
  const config = typeof window === 'undefined' ? undefined : window.__DIVALHR_CONFIG__;
  return { publicOrigin: clean(config?.publicOrigin), appOrigin: clean(config?.appOrigin) };
}

/** True when this page is served from the public commercial origin and an application origin exists. */
export function onPublicOrigin(current: string = window.location.origin): boolean {
  const { publicOrigin, appOrigin } = commercialOrigins();
  return Boolean(publicOrigin && appOrigin && current === publicOrigin);
}

/** The origin canonical metadata names: the public origin when configured, else the page's own. */
export function canonicalOrigin(): string {
  return commercialOrigins().publicOrigin ?? window.location.origin;
}

/** A path on the application origin when the page is on the public origin, else the path itself. */
export function appUrl(path: string, current: string = window.location.origin): string {
  return onPublicOrigin(current) ? `${commercialOrigins().appOrigin}${path}` : path;
}

/** The only paths served on the public commercial origin (everything else belongs to the application). */
export const PUBLIC_ORIGIN_PATHS = ['/', '/legal/privacy', '/legal/terms'] as const;

/**
 * Where a visit to the public origin must go instead: any path that is not a commercial page leaves
 * for the same path on the application origin (the edge redirects too; this covers a service-worker
 * navigation that never reaches the edge). Undefined when the visit stays where it is.
 */
export function publicOriginRedirect(location: {
  origin: string;
  pathname: string;
  search: string;
  hash: string;
}): string | undefined {
  if (!onPublicOrigin(location.origin)) return undefined;
  const path = location.pathname.length > 1 ? location.pathname.replace(/\/$/u, '') : '/';
  if ((PUBLIC_ORIGIN_PATHS as readonly string[]).includes(path)) return undefined;
  return `${commercialOrigins().appOrigin}${location.pathname}${location.search}${location.hash}`;
}

interface AppLinkProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> {
  to: string;
  children: ReactNode;
}

/** A client-side link in the application; a plain link to the application origin on the public origin. */
export function AppLink({ to, children, ...rest }: AppLinkProps) {
  if (onPublicOrigin()) {
    return (
      <a href={appUrl(to)} {...rest}>
        {children}
      </a>
    );
  }
  return (
    <Link to={to} {...rest}>
      {children}
    </Link>
  );
}
