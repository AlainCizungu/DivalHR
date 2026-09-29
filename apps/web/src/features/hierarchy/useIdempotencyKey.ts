import { useCallback, useRef } from 'react';

/**
 * One idempotency key per distinct payload: retrying the same payload reuses the key, any edit
 * produces a new key, so the server never sees a reused key with different content.
 */
export function useIdempotencyKey() {
  const attempt = useRef<{ payload: string; key: string } | null>(null);
  const keyFor = useCallback((payload: unknown): string => {
    const serialized = JSON.stringify(payload);
    if (attempt.current?.payload !== serialized) {
      attempt.current = { payload: serialized, key: `web-${crypto.randomUUID()}` };
    }
    return attempt.current.key;
  }, []);
  const reset = useCallback(() => {
    attempt.current = null;
  }, []);
  return { keyFor, reset };
}
