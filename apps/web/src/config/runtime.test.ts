import { describe, expect, it } from 'vitest';
import { ENVIRONMENTS, readRuntimeConfig, type RuntimeConfig } from './runtime';

const complete: RuntimeConfig = {
  environment: 'development',
  coreApiUrl: 'http://core.test/api/v1',
  aiServiceUrl: 'http://ai.test/api/v1',
  oidcAuthority: 'http://idp.test/realms/divalhr-dev',
  oidcClientId: 'divalhr-web',
};

describe('runtime configuration', () => {
  it('rejects incomplete configuration', () => {
    expect(() => readRuntimeConfig({ coreApiUrl: 'x' })).toThrow(/Missing runtime configuration/);
    expect(() => readRuntimeConfig(undefined)).toThrow();
  });

  it.each(ENVIRONMENTS)('accepts the allow-listed environment %s', (environment) => {
    expect(readRuntimeConfig({ ...complete, environment }).environment).toBe(environment);
  });

  it.each(['prod', 'Development', 'qa', '<b>staging</b>'])(
    'rejects the environment %j without echoing it (UI-001, UI1-2)',
    (environment) => {
      const config = { ...complete, environment } as unknown as RuntimeConfig;
      expect(() => readRuntimeConfig(config)).toThrow('Invalid runtime configuration: environment');
    },
  );
});
