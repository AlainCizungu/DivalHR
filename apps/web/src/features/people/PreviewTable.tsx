import type { AssignmentPeriod, PreviewKind } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { formatPeriod, valueText } from './history';
import { ScrollRegion } from './ScrollRegion';

/**
 * The exact rows a change or cancellation replaces and writes, per kind. Nothing is updated in
 * place: replaced rows are kept as history.
 */
export function PreviewTable({
  kinds,
  beforeLabel,
  afterLabel,
  'data-testid': testId,
}: {
  kinds: readonly PreviewKind[];
  beforeLabel: string;
  afterLabel: string;
  'data-testid': string;
}) {
  const { t, i18n } = useTranslation();
  const rows = (periods: readonly AssignmentPeriod[]) =>
    periods.length === 0
      ? t('employees.none')
      : periods
          .map(
            (period) =>
              `${formatPeriod(t, i18n.language, period.effectiveFrom, period.effectiveTo)}: ${valueText(t, period)}`,
          )
          .join('\n');
  return (
    <ScrollRegion label={t('employees.preview.caption')}>
      <table data-testid={testId}>
        <caption>{t('employees.preview.caption')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('employees.preview.kind')}</th>
            <th scope="col">{beforeLabel}</th>
            <th scope="col">{afterLabel}</th>
          </tr>
        </thead>
        <tbody>
          {kinds.map((kind) => (
            <tr key={kind.kind}>
              <th scope="row">{t(`employees.kinds.${kind.kind}`)}</th>
              <td className="preformatted">{rows(kind.before)}</td>
              <td className="preformatted">{rows(kind.after)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}
