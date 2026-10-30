import { vi } from 'vitest';

export type Recorded = {
  method: string;
  path: string;
  body: string;
  key: string | null;
};
export type Reply = { status: number; body?: unknown; pdf?: boolean };

/** Stubs fetch with a handler and records every request (path without the API prefix). */
export function stub(handler: (request: Recorded) => Reply) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body: incoming.method === 'GET' ? '' : await incoming.clone().text(),
        key: incoming.headers.get('Idempotency-Key'),
      };
      requests.push(recorded);
      const reply = handler(recorded);
      if (reply.pdf) {
        return new Response('%PDF-1.7 synthetic', {
          status: reply.status,
          headers: { 'Content-Type': 'application/pdf' },
        });
      }
      return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status,
        headers: {
          'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
        },
      });
    }),
  );
  return requests;
}
