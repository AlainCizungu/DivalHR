import type {
  CompensationBlocker,
  CompensationInterval,
  CompensationWarning,
} from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { IntervalItem } from './CompensationBits';

/** The intervals a command touches, as they are now and as they will be. */
export function PreviewIntervals({
  before,
  after,
  testId,
  level = 5,
}: {
  before: CompensationInterval[];
  after: CompensationInterval[];
  testId: string;
  level?: 4 | 5;
}) {
  const { t } = useTranslation();
  const Heading = level === 4 ? 'h4' : 'h5';
  return (
    <div data-testid={testId}>
      <Heading>{t('compensation.preview.before')}</Heading>
      {before.length === 0 ? (
        <p className="muted">{t('compensation.preview.nothing')}</p>
      ) : (
        <ul className="plain-list" data-testid="preview-before">
          {before.map((interval) => (
            <IntervalItem
              key={interval.originChangeId}
              interval={interval}
              testId="before-interval"
            />
          ))}
        </ul>
      )}
      <Heading>{t('compensation.preview.after')}</Heading>
      {after.length === 0 ? (
        <p className="muted">{t('compensation.preview.nothing')}</p>
      ) : (
        <ul className="plain-list" data-testid="preview-after">
          {after.map((interval) => (
            <IntervalItem
              key={interval.originChangeId}
              interval={interval}
              testId="after-interval"
            />
          ))}
        </ul>
      )}
    </div>
  );
}

/** Warnings and blockers by their stable codes, translated; a blocker prevents the commit. */
export function PreviewNotes({
  warnings,
  blockers,
}: {
  warnings: CompensationWarning[];
  blockers: CompensationBlocker[];
}) {
  const { t } = useTranslation();
  return (
    <>
      {warnings.length > 0 && (
        <div className="card" data-testid="preview-warnings">
          <p>{t('compensation.preview.warningsTitle')}</p>
          <ul>
            {warnings.map((warning) => (
              <li key={warning} data-code={warning}>
                {t(`compensation.warnings.${warning}`)}
              </li>
            ))}
          </ul>
        </div>
      )}
      {blockers.length > 0 && (
        <div className="error-summary" role="alert" data-testid="preview-blockers">
          <p>{t('compensation.preview.blockersTitle')}</p>
          <ul>
            {blockers.map((blocker) => (
              <li key={blocker} data-code={blocker}>
                {t(`compensation.blockers.${blocker}`)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </>
  );
}
