import createClient, { type Middleware } from 'openapi-fetch';
import type { paths as AiServicePaths } from './generated/ai-service';
import type { components as CoreComponents, paths as CorePaths } from './generated/core-api';

export type SystemStatus = CoreComponents['schemas']['SystemStatus'];
export type CurrentSession = CoreComponents['schemas']['CurrentSession'];
export type Problem = CoreComponents['schemas']['Problem'];
export type ErrorCode = CoreComponents['schemas']['ErrorCode'];

export interface ClientOptions {
  /** Base URL including /api/v1, e.g. http://localhost:8080/api/v1 */
  baseUrl: string;
  /** Returns the current in-memory access token, if any. Tokens are never persisted. */
  getAccessToken?: () => string | undefined;
  fetch?: typeof globalThis.fetch;
}

const correlationMiddleware: Middleware = {
  onRequest({ request }) {
    if (!request.headers.has('X-Correlation-Id')) {
      request.headers.set('X-Correlation-Id', crypto.randomUUID());
    }
    return request;
  },
};

/** Client for the Core API (authoritative contract: docs/API-SPEC.yaml). */
export function createCoreApiClient(options: ClientOptions) {
  const client = createClient<CorePaths>({ baseUrl: options.baseUrl, fetch: options.fetch });
  client.use(correlationMiddleware);
  if (options.getAccessToken) {
    const getToken = options.getAccessToken;
    client.use({
      onRequest({ request }) {
        const token = getToken();
        if (token) request.headers.set('Authorization', `Bearer ${token}`);
        return request;
      },
    });
  }
  return client;
}

/**
 * Client for the AI Service public status endpoint only. The AI Service is not a browser-facing
 * backend; no token is ever attached.
 */
export function createAiServiceStatusClient(options: Omit<ClientOptions, 'getAccessToken'>) {
  const client = createClient<AiServicePaths>({ baseUrl: options.baseUrl, fetch: options.fetch });
  client.use(correlationMiddleware);
  return client;
}
