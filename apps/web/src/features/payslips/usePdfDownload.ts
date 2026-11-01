import { useCallback, useRef, useState } from 'react';
import { useApi } from '../../app/ApiProvider';
import type { PayrollFailure } from '../payrollRuns/payrollRuns';
import { NETWORK_FAILURE } from '../payrollRuns/payrollRuns';
import { packageFailureOf, payslipFileName } from './payslips';

export type PdfScope = { kind: 'me' } | { kind: 'admin'; legalEntityId: string; packageId: string };

/**
 * Downloads one payslip PDF through the authenticated client: the bearer token travels in the
 * request header, the response becomes a Blob and is saved under a safe local name
 * (`payslip-YYYY-MM-<8 hex>.pdf`). No token, amount or filename from the server is put in a URL,
 * kept in storage or logged; the object URL is revoked after the click.
 */
export function usePdfDownload(scope: PdfScope) {
  const { core } = useApi();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const running = useRef(false);
  const kind = scope.kind;
  const legalEntityId = scope.kind === 'admin' ? scope.legalEntityId : '';
  const packageId = scope.kind === 'admin' ? scope.packageId : '';

  const download = useCallback(
    async (payslipId: string, period: string, locale: 'en' | 'fr', doneMessage: string) => {
      if (running.current) return;
      running.current = true;
      setBusyId(payslipId);
      setFailure(null);
      setAnnouncement('');
      try {
        const result =
          kind === 'me'
            ? await core.GET('/me/payslips/{payslipId}.pdf', {
                params: { path: { payslipId }, query: { locale } },
                parseAs: 'blob',
                cache: 'no-store',
              })
            : await core.GET(
                '/legal-entities/{legalEntityId}/payroll-packages/{packageId}/payslips/{payslipId}.pdf',
                {
                  params: { path: { legalEntityId, packageId, payslipId }, query: { locale } },
                  parseAs: 'blob',
                  cache: 'no-store',
                },
              );
        if (!result.data) {
          setFailure(packageFailureOf(result.response, result.error));
          return;
        }
        const url = URL.createObjectURL(result.data);
        const link = document.createElement('a');
        link.href = url;
        link.download = payslipFileName(period, payslipId);
        link.rel = 'noopener';
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => {
          URL.revokeObjectURL(url);
        }, 0);
        setAnnouncement(doneMessage);
      } catch {
        setFailure(NETWORK_FAILURE);
      } finally {
        running.current = false;
        setBusyId(null);
      }
    },
    [core, kind, legalEntityId, packageId],
  );
  return { download, busyId, failure, announcement };
}
