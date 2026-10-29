import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '../../ui/primitives';
import {
  CompensationAlert,
  HistoryBlock,
  IntervalItem,
  useCompensationRecord,
} from './CompensationBits';

/**
 * MVP-093A: « Ma rémunération » / "My compensation". The linked employee reads their own current
 * monthly base compensation (the currency code always next to the amount), the scheduled changes
 * and the dated history with the reason code of a correction. Read-only: no control changes
 * anything, and the administrator's explanation is never shown. Nothing is kept in browser storage.
 */
export function MyCompensationPage() {
  const { t } = useTranslation();
  const ids = useId();
  const { load, loadMore, moreBusy, moreFailure } = useCompensationRecord(null);
  const record = load.kind === 'ready' ? load.record : null;
  const empty = record !== null && record.current === null && record.scheduled.length === 0;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-compensation">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('compensation.me.title')}
        description={t('compensation.me.description')}
      />
      <p className="card muted" data-testid="my-compensation-notice">
        {t('compensation.me.notice')}
      </p>
      <p className="card muted" data-testid="compensation-privacy">
        {t('compensation.me.privacy')}
      </p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="compensation-loading">
          {t('compensation.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <CompensationAlert failure={load.failure} testId="compensation-error" />
      )}
      {record && (
        <>
          <section
            className="card"
            aria-labelledby={`${ids}-current`}
            data-testid="compensation-current"
          >
            <h2 id={`${ids}-current`}>{t('compensation.current.title')}</h2>
            {record.current ? (
              <ul className="plain-list">
                <IntervalItem interval={record.current} testId="current-interval" />
              </ul>
            ) : (
              <p className="muted" data-testid="compensation-empty">
                {empty ? t('compensation.me.empty') : t('compensation.current.none')}
              </p>
            )}
          </section>
          {record.scheduled.length > 0 && (
            <section
              className="card"
              aria-labelledby={`${ids}-scheduled`}
              data-testid="compensation-scheduled"
            >
              <h2 id={`${ids}-scheduled`}>{t('compensation.scheduled.title')}</h2>
              <ul className="plain-list">
                {record.scheduled.map((interval) => (
                  <IntervalItem
                    key={interval.originChangeId}
                    interval={interval}
                    testId="scheduled-interval"
                  />
                ))}
              </ul>
            </section>
          )}
          <div className="card">
            <HistoryBlock
              record={record}
              showExplanation={false}
              moreBusy={moreBusy}
              moreFailure={moreFailure}
              onMore={() => void loadMore()}
              headingId={`${ids}-history`}
              level={2}
            />
          </div>
        </>
      )}
    </section>
  );
}
