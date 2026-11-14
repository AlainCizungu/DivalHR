import { useTranslation } from 'react-i18next';
import { ScrollRegion } from '../people/ScrollRegion';
import { MinorAmount } from '../payments/PaymentBits';
import type { AccountingJournalLine } from './accounting';

/** The fixed notice of every accounting page and section (D96-8). */
export function AccountingNotice({ testId = 'accounting-notice' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="card" data-testid={testId}>
      <strong>{t('accounting.notice')}</strong>
    </p>
  );
}

/** The exact aggregated lines: account code, direction and amount in the journal currency. */
export function JournalLinesTable({
  lines,
  currency,
  testId,
}: {
  lines: AccountingJournalLine[];
  currency: string;
  testId: string;
}) {
  const { t } = useTranslation();
  const extras = lines.some((line) => line.costCenterCode !== null || line.siteCode !== null);
  return (
    <ScrollRegion label={t('accounting.journal.lines.caption')}>
      <table data-testid={testId}>
        <caption className="visually-hidden">{t('accounting.journal.lines.caption')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('accounting.journal.lines.number')}</th>
            <th scope="col">{t('accounting.journal.lines.account')}</th>
            <th scope="col">{t('accounting.journal.lines.direction')}</th>
            {extras && <th scope="col">{t('accounting.journal.lines.costCenter')}</th>}
            {extras && <th scope="col">{t('accounting.journal.lines.site')}</th>}
            <th scope="col" className="numeric">
              {t('accounting.journal.lines.amount')}
            </th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => (
            <tr key={line.lineNumber} data-testid="accounting-line" data-direction={line.direction}>
              <th scope="row">{line.lineNumber}</th>
              <td>
                <code>{line.accountCode}</code>
              </td>
              <td>{t(`accounting.direction.${line.direction}`)}</td>
              {extras && <td>{line.costCenterCode ?? '—'}</td>}
              {extras && <td>{line.siteCode ?? '—'}</td>}
              <td className="numeric">
                <MinorAmount minor={line.amountMinor} currency={currency} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}
