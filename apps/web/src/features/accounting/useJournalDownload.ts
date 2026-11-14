import { useCallback, useRef, useState } from 'react';
import { useApi } from '../../app/ApiProvider';
import { NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { accountingFailureOf, safeDispositionName } from './accounting';

export type JournalFile = 'csv' | 'manifest';

/**
 * Downloads a journal file through the authenticated client: the token travels in the request
 * header, the response becomes a Blob saved under the generated name (the Content-Disposition name
 * when it has the generated shape, otherwise the identical local name), and the object URL is
 * revoked after the click. Nothing is placed in a URL, in browser storage or in a log.
 */
export function useJournalDownload(
  legalEntityId: string,
  journalId: string,
  fileNameOf: (file: JournalFile) => string,
) {
  const { core } = useApi();
  const [busy, setBusy] = useState<JournalFile | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [done, setDone] = useState<JournalFile | null>(null);
  const running = useRef(false);

  const download = useCallback(
    async (file: JournalFile) => {
      if (running.current) return;
      running.current = true;
      setBusy(file);
      setFailure(null);
      setDone(null);
      try {
        const result =
          file === 'csv'
            ? await core.GET(
                '/legal-entities/{legalEntityId}/accounting-journals/{journalId}/csv',
                {
                  params: { path: { legalEntityId, journalId } },
                  parseAs: 'blob',
                  cache: 'no-store',
                },
              )
            : await core.GET(
                '/legal-entities/{legalEntityId}/accounting-journals/{journalId}/manifest',
                {
                  params: { path: { legalEntityId, journalId } },
                  parseAs: 'blob',
                  cache: 'no-store',
                },
              );
        if (!result.data) {
          setFailure(accountingFailureOf(result.response, result.error));
          return;
        }
        const url = URL.createObjectURL(result.data);
        const link = document.createElement('a');
        link.href = url;
        link.download =
          safeDispositionName(result.response.headers.get('Content-Disposition')) ??
          fileNameOf(file);
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
    [core, legalEntityId, journalId, fileNameOf],
  );
  return { download, busy, failure, done };
}
