import { afterEach, describe, expect, it } from 'vitest';
import {
  appUrl,
  canonicalOrigin,
  commercialOrigins,
  onPublicOrigin,
  publicOriginRedirect,
} from './origins';

/** LAUNCH-002 (ADR 0048, L2-1): the two production origins are optional and change nothing when unset. */
const PUBLIC = 'https://divalhr.com';
const APP = 'https://app.divalhr.com';

function configure(value: Record<string, unknown> | undefined) {
  window.__DIVALHR_CONFIG__ = value;
}

afterEach(() => {
  delete window.__DIVALHR_CONFIG__;
});

describe('commercial origins', () => {
  it('changes nothing when the origins are unset (every other environment)', () => {
    configure({ environment: 'test' });
    expect(commercialOrigins()).toEqual({ publicOrigin: undefined, appOrigin: undefined });
    expect(onPublicOrigin(PUBLIC)).toBe(false);
    expect(appUrl('/partners/apply', PUBLIC)).toBe('/partners/apply');
    expect(canonicalOrigin()).toBe(window.location.origin);
  });

  it('points canonical metadata at the public origin and sends calls to action to the application', () => {
    configure({ environment: 'production', publicOrigin: PUBLIC, appOrigin: APP });
    expect(canonicalOrigin()).toBe(PUBLIC);
    expect(onPublicOrigin(PUBLIC)).toBe(true);
    expect(appUrl('/partners/apply', PUBLIC)).toBe(`${APP}/partners/apply`);
  });

  it('keeps the application origin itself in place (sign-in and links stay same-origin)', () => {
    configure({ environment: 'production', publicOrigin: PUBLIC, appOrigin: APP });
    expect(onPublicOrigin(APP)).toBe(false);
    expect(appUrl('/partners/apply', APP)).toBe('/partners/apply');
    // The canonical origin is still the public one, so the page at app.divalhr.com/ creates no
    // conflicting public truth.
    expect(canonicalOrigin()).toBe(PUBLIC);
  });

  it('ignores anything that is not a bare https origin', () => {
    for (const bad of [
      'http://divalhr.com',
      'https://divalhr.com/',
      'https://a.com/path',
      'javascript:alert(1)',
      42,
    ]) {
      configure({ environment: 'production', publicOrigin: bad, appOrigin: bad });
      expect(commercialOrigins()).toEqual({ publicOrigin: undefined, appOrigin: undefined });
    }
  });

  it('needs both origins before it redirects anything', () => {
    configure({ environment: 'production', publicOrigin: PUBLIC });
    expect(onPublicOrigin(PUBLIC)).toBe(false);
  });

  it('sends every non-commercial path on the public origin to the application origin', () => {
    configure({ environment: 'production', publicOrigin: PUBLIC, appOrigin: APP });
    const at = (origin: string, pathname: string, search = '', hash = '') =>
      publicOriginRedirect({ origin, pathname, search, hash });
    for (const stay of ['/', '/legal/privacy', '/legal/terms', '/legal/terms/']) {
      expect(at(PUBLIC, stay)).toBeUndefined();
    }
    expect(at(PUBLIC, '/partners/apply')).toBe(`${APP}/partners/apply`);
    expect(at(PUBLIC, '/leave', '?x=1', '#y')).toBe(`${APP}/leave?x=1#y`);
    expect(at(PUBLIC, '/legal')).toBe(`${APP}/legal`);
    // Never on the application origin, and never when the origins are not configured.
    expect(at(APP, '/partners/apply')).toBeUndefined();
    configure({ environment: 'test' });
    expect(at(PUBLIC, '/partners/apply')).toBeUndefined();
  });
});
