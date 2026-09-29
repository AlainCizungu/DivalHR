import { describe, expect, it } from 'vitest';
import { readRuntimeConfig } from './runtime';

describe('runtime configuration', () => {
  it('rejects incomplete configuration', () => {
    expect(() => readRuntimeConfig({ coreApiUrl: 'x' })).toThrow(/Missing runtime configuration/);
    expect(() => readRuntimeConfig(undefined)).toThrow();
  });
});
