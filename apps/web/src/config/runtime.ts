/** UI-001 (UI1-2): the only environment values the app accepts; the shell labels each one. */
export const ENVIRONMENTS = ['development', 'test', 'staging', 'production'] as const;
export type Environment = (typeof ENVIRONMENTS)[number];

export interface RuntimeConfig {
  environment: Environment;
  coreApiUrl: string;
  aiServiceUrl: string;
  oidcAuthority: string;
  oidcClientId: string;
}

declare global {
  interface Window {
    __DIVALHR_CONFIG__?: Partial<RuntimeConfig>;
  }
}

const REQUIRED: (keyof RuntimeConfig)[] = [
  'environment',
  'coreApiUrl',
  'aiServiceUrl',
  'oidcAuthority',
  'oidcClientId',
];

/** Reads public runtime configuration injected by /config.js. Contains no secrets. */
export function readRuntimeConfig(source: Partial<RuntimeConfig> | undefined): RuntimeConfig {
  const missing = REQUIRED.filter((key) => !source?.[key]);
  if (!source || missing.length > 0) {
    throw new Error(`Missing runtime configuration: ${missing.join(', ')}`);
  }
  if (!(ENVIRONMENTS as readonly unknown[]).includes(source.environment)) {
    // The value is never echoed: only allow-listed environments are ever displayed.
    throw new Error('Invalid runtime configuration: environment');
  }
  return source as RuntimeConfig;
}

let cached: RuntimeConfig | undefined;

export function getRuntimeConfig(): RuntimeConfig {
  cached ??= readRuntimeConfig(window.__DIVALHR_CONFIG__);
  return cached;
}
