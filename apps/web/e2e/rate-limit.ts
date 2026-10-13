// R120-2 (PR #120): the browser suite's one retry rule for the anonymous invitation endpoints.
// The public endpoints allow 10 requests per client address per minute (application.yaml), and the
// parallel feature workers share one client address, so an acceptance can meet a correct 429 from
// another worker's traffic. The limit is respected, never raised: a 429 waits its bounded
// Retry-After and the same user action is repeated, at most three attempts in all. Any other
// status is returned at once for the caller's own assertion. No Playwright import: the rule is unit
// tested with the plain Node test runner (e2e/tooling.test.ts).

/** Total attempts, the first included. */
export const MAX_ATTEMPTS = 3;
/** Longest wait honoured: one fixed one-minute window plus a second. */
export const MAX_WAIT_SECONDS = 61;
/** The wait when Retry-After is missing or not a positive number of seconds. */
export const DEFAULT_WAIT_SECONDS = 60;

/** The part of a response the rule reads (a Playwright APIResponse or Response has both). */
export interface StatusResponse {
  status(): number;
  headers(): Record<string, string>;
}

/** Retry-After as whole seconds, between 1 and {@link MAX_WAIT_SECONDS}. */
export function retryAfterSeconds(header: string | undefined): number {
  const seconds = Number(header);
  if (!Number.isFinite(seconds) || seconds <= 0) return DEFAULT_WAIT_SECONDS;
  return Math.min(Math.ceil(seconds), MAX_WAIT_SECONDS);
}

/**
 * Performs `attempt` (1, 2, 3) until its response is not 429 or {@link MAX_ATTEMPTS} were made,
 * waiting the bounded Retry-After between attempts. Returns the last response; the caller asserts
 * its status, so an unexpected status or an exhausted bound fails the test normally.
 */
export async function retryOn429<R extends StatusResponse>(
  attempt: (number: number) => Promise<R>,
  sleep: (milliseconds: number) => Promise<unknown>,
): Promise<R> {
  let response = await attempt(1);
  for (let number = 2; number <= MAX_ATTEMPTS && response.status() === 429; number++) {
    await sleep(retryAfterSeconds(response.headers()['retry-after']) * 1000);
    response = await attempt(number);
  }
  return response;
}
