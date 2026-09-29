export interface RuntimeConfig {
  environment: 'development' | 'test' | 'staging' | 'production';
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
  return source as RuntimeConfig;
}

let cached: RuntimeConfig | undefined;

export function getRuntimeConfig(): RuntimeConfig {
  cached ??= readRuntimeConfig(window.__DIVALHR_CONFIG__);
  return cached;
}
