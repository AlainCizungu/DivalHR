import type { PaymentBatchLine, PaymentBatchMethodCounts } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { formatMoney } from '../compensation/compensation';
import { ScrollRegion } from '../people/ScrollRegion';
import { minorToAmount } from './payments';

/** An amount of integer minor units as the server returned it, formatted for the language. */
export function MinorAmount({ minor, currency }: { minor: number; currency: string }) {
  const { i18n } = useTranslation();
  return (
    <>
      {formatMoney(i18n.language, {
        currency: currency === 'USD' ? 'USD' : 'CDF',
        amount: minorToAmount(minor, currency),
      })}
    </>
  );
}

/** The permanent statement that a payment batch is a register, not a payment. */
export function NoMoneySent({ testId = 'payment-no-money' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="muted" data-testid={testId}>
      {t('payments.noMoney')}
    </p>
  );
}

export function MethodCounts({ counts }: { counts: PaymentBatchMethodCounts }) {
  const { t } = useTranslation();
  return (
    <dl className="decision-summary decision-summary--wrapping" data-testid="payment-method-counts">
      <dt>{t('payments.method.BANK_ACCOUNT')}</dt>
      <dd data-testid="payment-count-bank">{counts.bankAccount}</dd>
      <dt>{t('payments.method.MOBILE_MONEY')}</dt>
      <dd data-testid="payment-count-mobile">{counts.mobileMoney}</dd>
      <dt>{t('payments.method.MANUAL')}</dt>
      <dd data-testid="payment-count-manual">{counts.manual}</dd>
    </dl>
  );
}

/** Masked lines of a batch: employee number, method, provider, masked destination, net amount. */
export function BatchLinesTable({
  lines,
  currency,
  testId,
}: {
  lines: PaymentBatchLine[];
  currency: string;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <ScrollRegion label={t('payments.lines.caption')}>
      <table data-testid={testId}>
        <caption className="visually-hidden">{t('payments.lines.caption')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('payments.lines.employee')}</th>
            <th scope="col">{t('payments.lines.method')}</th>
            <th scope="col">{t('payments.lines.destination')}</th>
            <th scope="col" className="numeric">
              {t('payments.lines.net')}
            </th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => (
            <tr key={line.payslipId} data-testid="payment-line" data-issue={line.issue ?? ''}>
              <th scope="row">{line.employeeNumber}</th>
              <td>
                {line.method ? t(`payments.method.${line.method}`) : '—'}
                {line.provider ? ` · ${line.provider.name}` : ''}
              </td>
              <td>
                {line.issue ? t(`payments.lineIssue.${line.issue}`) : (line.maskedValue ?? '—')}
              </td>
              <td className="numeric">
                <MinorAmount minor={line.netMinor} currency={currency} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}
