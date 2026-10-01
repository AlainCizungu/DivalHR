import { describe, expect, it, vi } from 'vitest';
import { createAiServiceStatusClient, createCoreApiClient } from './index';

function recordingFetch() {
  const requests: Request[] = [];
  const fetch = vi.fn(async (input: Request) => {
    requests.push(input);
    return new Response(
      JSON.stringify({
        service: 'x',
        status: 'UP',
        version: '0',
        checkedAt: '2026-09-28T00:00:00Z',
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    );
  });
  return { fetch: fetch as unknown as typeof globalThis.fetch, requests };
}

describe('api client', () => {
  it('attaches the in-memory bearer token and a correlation ID to Core API calls', async () => {
    const { fetch, requests } = recordingFetch();
    const client = createCoreApiClient({
      baseUrl: 'http://core.test/api/v1',
      getAccessToken: () => 'token-123',
      fetch,
    });
    await client.GET('/session');
    expect(requests[0]!.url).toBe('http://core.test/api/v1/session');
    expect(requests[0]!.headers.get('Authorization')).toBe('Bearer token-123');
    expect(requests[0]!.headers.get('X-Correlation-Id')).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('never sends a token to the AI Service', async () => {
    const { fetch, requests } = recordingFetch();
    const client = createAiServiceStatusClient({ baseUrl: 'http://ai.test/api/v1', fetch });
    const { data } = await client.GET('/system/status');
    expect(data?.status).toBe('UP');
    expect(requests[0]!.headers.get('Authorization')).toBeNull();
  });

  it('reports MFA_REQUIRED without consuming the response', async () => {
    const onMfaRequired = vi.fn();
    const reply = (status: number, code: string) =>
      vi.fn(
        async () =>
          new Response(JSON.stringify({ status, code, params: {} }), {
            status,
            headers: { 'Content-Type': 'application/problem+json' },
          }),
      ) as unknown as typeof globalThis.fetch;
    const mfa = createCoreApiClient({
      baseUrl: 'http://core.test/api/v1',
      getAccessToken: () => 'token-123',
      fetch: reply(403, 'MFA_REQUIRED'),
      onMfaRequired,
    });
    const { error, response } = await mfa.GET('/legal-entities');
    expect(onMfaRequired).toHaveBeenCalledTimes(1);
    expect(response.status).toBe(403);
    expect(error?.code).toBe('MFA_REQUIRED');

    for (const [status, code] of [
      [403, 'ACCESS_DENIED'],
      [401, 'MFA_REQUIRED'],
      [400, 'VALIDATION_FAILED'],
    ] as const) {
      const other = createCoreApiClient({
        baseUrl: 'http://core.test/api/v1',
        fetch: reply(status, code),
        onMfaRequired,
      });
      await other.GET('/legal-entities');
    }
    expect(onMfaRequired).toHaveBeenCalledTimes(1);
  });
});
