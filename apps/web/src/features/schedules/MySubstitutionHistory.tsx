import type { MyWorkScheduleSubstitution } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { SubstitutionAlert, ShiftFacts, SubstitutionStateBadge } from './SubstitutionParts';
import { personName, type SubstitutionFailure } from './substitutions';

export type History =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: SubstitutionFailure }
  | { kind: 'ready'; items: MyWorkScheduleSubstitution[]; nextCursor: string | null };

/**
 * MVP-051: « Mes demandes de remplacement » / "My substitution requests": each request's shift,
 * bilingual state, the reason the employee gave and, once decided, the decision's date and reason;
 * for an approval, the substitute's name and number. Never the deciding manager. Reasons are plain
 * text in their own language.
 */
export function MySubstitutionHistory({
  headingId,
  history,
  loadingMore,
  onMore,
  onRetry,
}: {
  headingId: string;
  history: History;
  loadingMore: boolean;
  onMore: (cursor: string) => void;
  onRetry: () => void;
}) {
  const { t, i18n } = useTranslation();
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const date = (instant: string) =>
    new Intl.DateTimeFormat(language, { dateStyle: 'medium' }).format(new Date(instant));
  return (
    <section aria-labelledby={headingId} className="card" data-testid="my-substitutions">
      <h2 id={headingId} tabIndex={-1}>
        {t('scheduleSubstitutions.mine.title')}
      </h2>
      <p className="muted">{t('scheduleSubstitutions.mine.description')}</p>
      {history.kind === 'loading' && <p role="status">{t('scheduleSubstitutions.mine.loading')}</p>}
      {history.kind === 'failed' && (
        <>
          <SubstitutionAlert failure={history.failure} testId="my-substitutions-error" />
          {history.failure.kind !== 'linkRequired' && history.failure.kind !== 'forbidden' && (
            <button type="button" className="button button--secondary" onClick={onRetry}>
              {t('mySchedule.retry')}
            </button>
          )}
        </>
      )}
      {history.kind === 'ready' && history.items.length === 0 && (
        <p data-testid="my-substitutions-empty">{t('scheduleSubstitutions.mine.empty')}</p>
      )}
      {history.kind === 'ready' && history.items.length > 0 && (
        <ul className="plain-list approval-list" aria-label={t('scheduleSubstitutions.mine.title')}>
          {history.items.map((item) => (
            <li
              key={item.id}
              className="hierarchy-item approval-item"
              data-testid="my-substitution"
              data-state={item.state}
            >
              <h3>{t('scheduleSubstitutions.mine.requested', { date: date(item.requestedAt) })}</h3>
              <p>
                <SubstitutionStateBadge state={item.state} />
              </p>
              <ShiftFacts
                start={item.shift.start}
                end={item.shift.end}
                labels={item.shift.labels}
                siteName={item.shift.site.name}
                siteTimezone={item.shift.siteTimezone}
              />
              <figure className="decision-reason">
                <figcaption>{t('scheduleSubstitutions.mine.yourReason')}</figcaption>
                <blockquote lang={item.reasonLocale} data-testid="my-substitution-reason">
                  {item.reason}
                </blockquote>
              </figure>
              {item.decision && (
                <div data-testid="my-substitution-decision">
                  <p>
                    {t(`scheduleSubstitutions.mine.decided.${item.decision.outcome}`, {
                      date: date(item.decision.decidedAt),
                    })}
                  </p>
                  {item.decision.substitute && (
                    <p data-testid="my-substitution-substitute">
                      {t('scheduleSubstitutions.mine.substitute', {
                        name: personName(item.decision.substitute),
                      })}
                    </p>
                  )}
                  <figure className="decision-reason">
                    <figcaption>{t('scheduleSubstitutions.mine.decisionReason')}</figcaption>
                    <blockquote
                      lang={item.decision.reasonLocale}
                      data-testid="my-substitution-decision-reason"
                    >
                      {item.decision.reason}
                    </blockquote>
                  </figure>
                </div>
              )}
              {item.state === 'VOIDED' && (
                <p className="muted">{t('scheduleSubstitutions.mine.voided')}</p>
              )}
              {item.state === 'EXPIRED' && (
                <p className="muted">{t('scheduleSubstitutions.mine.expired')}</p>
              )}
            </li>
          ))}
        </ul>
      )}
      {history.kind === 'ready' && history.nextCursor && (
        <p>
          <button
            type="button"
            className="button button--secondary"
            disabled={loadingMore}
            aria-busy={loadingMore ? true : undefined}
            onClick={() => {
              if (history.nextCursor) onMore(history.nextCursor);
            }}
          >
            {loadingMore
              ? t('scheduleSubstitutions.mine.loadingMore')
              : t('scheduleSubstitutions.mine.more')}
          </button>
        </p>
      )}
    </section>
  );
}
