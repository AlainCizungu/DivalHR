import type { ContractAcknowledgementEvidence } from '@divalhr/api-client';
import { useId, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { formatInstant } from './contracts';

/**
 * MVP-030: the acknowledgement evidence as shown to the employee and the administrator: the time,
 * the statement as displayed (in its language) and the evidence reference. It states only that
 * the employee made the confirmation (A30-3).
 */
export function AcknowledgementEvidence({
  evidence,
  headingRef,
}: {
  evidence: ContractAcknowledgementEvidence;
  headingRef?: Ref<HTMLHeadingElement>;
}) {
  const { t, i18n } = useTranslation();
  const id = useId();
  return (
    <section aria-labelledby={id} className="card" data-testid="evidence">
      <h3 id={id} tabIndex={-1} ref={headingRef}>
        {t('contracts.evidence.title')}
      </h3>
      <p data-testid="evidence-at">
        {t('contracts.evidence.at', { at: formatInstant(i18n.language, evidence.acknowledgedAt) })}
      </p>
      <p>
        {t('contracts.evidence.statement', {
          language: t(`contracts.languages.${evidence.statementLocale}`),
        })}
      </p>
      <blockquote lang={evidence.statementLocale} data-testid="evidence-statement">
        {evidence.statementText}
      </blockquote>
      <p className="muted contract-document__digest">
        {t('contracts.evidence.integrity', { digest: evidence.evidenceSha256 })}
      </p>
      <p className="muted">{t('contracts.evidence.only')}</p>
    </section>
  );
}
