import { useCallback, useRef, useState } from 'react';
import { useApi } from '../../app/ApiProvider';
import { NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { paymentFailureOf } from './payments';

export type RegisterFile = 'csv' | 'manifest';

/** `payment-register-YYYY-MM-<8 hex of the batch id>.csv|.json`: never a name or an amount. */
export function registerFileName(period: string, batchId: string, file: RegisterFile): string {
  return `payment-register-${period}-${batchId.replaceAll('-', '').slice(0, 8)}.${
    file === 'csv' ? 'csv' : 'json'
  }`;
}

/**
 * Downloads a batch register through the authenticated client: the token travels in the request
 * header, the response becomes a Blob saved under a safe local name, and the object URL is revoked
 * after the click. Nothing is placed in a URL, in browser storage or in a log.
 */
export function useRegisterDownload(legalEntityId: string, batchId: string, period: string) {
  const { core } = useApi();
  const [busy, setBusy] = useState<RegisterFile | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [done, setDone] = useState<RegisterFile | null>(null);
  const running = useRef(false);

  const download = useCallback(
    async (file: RegisterFile) => {
      if (running.current) return;
      running.current = true;
      setBusy(file);
      setFailure(null);
      setDone(null);
      try {
        const result =
          file === 'csv'
            ? await core.GET(
                '/legal-entities/{legalEntityId}/payment-batches/{batchId}/register.csv',
                {
                  params: { path: { legalEntityId, batchId } },
                  parseAs: 'blob',
                  cache: 'no-store',
                },
              )
            : await core.GET(
                '/legal-entities/{legalEntityId}/payment-batches/{batchId}/manifest.json',
                {
                  params: { path: { legalEntityId, batchId } },
                  parseAs: 'blob',
                  cache: 'no-store',
                },
              );
        if (!result.data) {
          setFailure(paymentFailureOf(result.response, result.error));
          return;
        }
        const url = URL.createObjectURL(result.data);
        const link = document.createElement('a');
        link.href = url;
        link.download = registerFileName(period, batchId, file);
        link.rel = 'noopener';
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => {
          URL.revokeObjectURL(url);
        }, 0);
        setDone(file);
      } catch {
        setFailure(NETWORK_FAILURE);
      } finally {
        running.current = false;
        setBusy(null);
      }
    },
    [core, legalEntityId, batchId, period],
  );
  return { download, busy, failure, done };
}
