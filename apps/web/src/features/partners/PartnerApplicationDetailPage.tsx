import type { PartnerApplicationDetail, PartnerApplicationHistoryEntry } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { ApprovalBoundary, PartnerAlert, PartnerStatus } from './PartnerBits';
import { ChecklistPanel, ScrollTable, TransitionPanel } from './PartnerReviewPanels';
import { NETWORK_FAILURE, partnerFailureOf, type PartnerFailure } from './partners';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | {
      kind: 'ready';
      application: PartnerApplicationDetail;
      history: PartnerApplicationHistoryEntry[];
    };

/**
 * MVP-120D: one application for a platform administrator. The reference is the only thing in the
 * address; review notes are typed into forms and never leave component state.
 */
export function PartnerApplicationDetailPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const { reference = '' } = useParams();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [reloads, setReloads] = useState(0);
  const reload = useCallback(() => {
    setReloads((n) => n + 1);
  }, []);
  const regionNames = new Intl.DisplayNames([i18n.language], { type: 'region' });
  const date = (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(iso),
    );

  useEffect(() => {
    let active = true;
    const path = { reference };
    Promise.all([
      core.GET('/platform/partner-applications/{reference}', {
        params: { path },
        cache: 'no-store',
      }),
      core.GET('/platform/partner-applications/{reference}/history', {
        params: { path, query: { limit: 50 } },
        cache: 'no-store',
      }),
    ]).then(
      ([detail, history]) => {
        if (!active) return;
        if (detail.data && history.data) {
          setLoad({ kind: 'ready', application: detail.data, history: history.data.items });
        } else {
          setLoad({
            kind: 'failed',
            failure: detail.data
              ? partnerFailureOf(history.response, history.error)
              : partnerFailureOf(detail.response, detail.error),
          });
        }
      },
      () => {
        if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
      },
    );
    return () => {
      active = false;
    };
  }, [core, reference, reloads]);

  if (load.kind === 'loading') {
    return (
      <p role="status" data-testid="partner-application-loading">
        {t('partners.review.loading')}
      </p>
    );
  }
  if (load.kind === 'failed') {
    return (
      <section>
        <PartnerAlert failure={load.failure} testId="partner-application-failure" />
        <p>
          <Link to="/admin/partner-applications">{t('partners.review.back')}</Link>
        </p>
      </section>
    );
  }
  const app = load.application;
  return (
    <section aria-labelledby={`${ids}-title`} data-testid="partner-application-detail">
      <PageHeader
        titleId={`${ids}-title`}
        title={app.legalName}
        description={t('partners.review.detailDescription', { reference: app.reference })}
        actions={<PartnerStatus status={app.status} />}
      />
      <p>
        <Link to="/admin/partner-applications">{t('partners.review.back')}</Link>
      </p>
      {app.status === 'APPROVED_FOR_ONBOARDING' && <ApprovalBoundary />}
      {app.possibleDuplicates > 0 && (
        <p className="card" data-testid="partner-duplicates">
          {t('partners.review.possibleDuplicates', { count: app.possibleDuplicates })}
        </p>
      )}

      <h2>{t('partners.review.application')}</h2>
      <dl className="card summary" data-testid="partner-application-facts">
        <dt>{t('partners.review.columns.reference')}</dt>
        <dd data-testid="partner-detail-reference">{app.reference}</dd>
        <dt>{t('partners.apply.fields.tradingName.label')}</dt>
        <dd>{app.tradingName ?? '—'}</dd>
        <dt>{t('partners.apply.fields.countryOfRegistration.label')}</dt>
        <dd>{regionNames.of(app.countryOfRegistration) ?? app.countryOfRegistration}</dd>
        <dt>{t('partners.apply.fields.countriesOfOperation.label')}</dt>
        <dd>{app.countriesOfOperation.map((c) => regionNames.of(c) ?? c).join(', ')}</dd>
        <dt>{t('partners.apply.fields.primaryCategory.label')}</dt>
        <dd>{t(`partners.category.${app.primaryCategory}`)}</dd>
        <dt>{t('partners.apply.fields.additionalCategories.label')}</dt>
        <dd>
          {app.additionalCategories.length > 0
            ? app.additionalCategories.map((c) => t(`partners.category.${c}`)).join(', ')
            : '—'}
        </dd>
        <dt>{t('partners.apply.fields.requestedCapabilities.label')}</dt>
        <dd>{app.requestedCapabilities.map((c) => t(`partners.capability.${c}`)).join(', ')}</dd>
        <dt>{t('partners.apply.fields.regulatorName.label')}</dt>
        <dd>{app.regulatorName ?? '—'}</dd>
        <dt>{t('partners.apply.fields.licenceReference.label')}</dt>
        <dd>{app.contact.licenceReference ?? '—'}</dd>
        <dt>{t('partners.apply.fields.registrationReference.label')}</dt>
        <dd>{app.contact.registrationReference}</dd>
        <dt>{t('partners.apply.fields.website.label')}</dt>
        <dd>{app.website}</dd>
        <dt>{t('partners.apply.fields.apiAvailability.label')}</dt>
        <dd>{t(`partners.api.${app.apiAvailability}`)}</dd>
        <dt>{t('partners.apply.fields.supportedLanguages.label')}</dt>
        <dd>{app.supportedLanguages.map((l) => t(`language.${l}`)).join(', ')}</dd>
        <dt>{t('partners.apply.fields.serviceSummary.label')}</dt>
        <dd className="preformatted">{app.serviceSummary}</dd>
        <dt>{t('partners.review.columns.submitted')}</dt>
        <dd>{date(app.submittedAt)}</dd>
      </dl>

      <h2>{t('partners.review.contact')}</h2>
      <p className="muted">{t('partners.review.contactNotice')}</p>
      <dl className="card summary" data-testid="partner-contact">
        <dt>{t('partners.apply.fields.contactName.label')}</dt>
        <dd>{app.contact.contactName}</dd>
        <dt>{t('partners.apply.fields.contactRole.label')}</dt>
        <dd>{app.contactRole}</dd>
        <dt>{t('partners.apply.fields.businessEmail.label')}</dt>
        <dd>{app.contact.businessEmail}</dd>
        <dt>{t('partners.apply.fields.businessPhone.label')}</dt>
        <dd>{app.contact.businessPhone ?? '—'}</dd>
        <dt>{t('partners.apply.fields.privacyContactEmail.label')}</dt>
        <dd>{app.contact.privacyContactEmail}</dd>
      </dl>

      <h2>{t('partners.review.consent')}</h2>
      <dl className="card summary" data-testid="partner-consent">
        <dt>{t('partners.review.privacyNoticeVersion')}</dt>
        <dd>{app.consent.privacyNoticeVersion}</dd>
        <dt>{t('partners.review.consentVersion')}</dt>
        <dd>{app.consent.consentVersion}</dd>
        <dt>{t('partners.review.consentLocale')}</dt>
        <dd>{t(`language.${app.consent.locale}`)}</dd>
        <dt>{t('partners.review.acceptedAt')}</dt>
        <dd>{date(app.consent.acceptedAt)}</dd>
      </dl>

      <ChecklistPanel application={app} onChanged={reload} />
      <TransitionPanel application={app} onChanged={reload} />

      <h2>{t('partners.review.history')}</h2>
      <ScrollTable label={t('partners.review.history')}>
        <table className="timesheet-table" data-testid="partner-history">
          <caption className="visually-hidden">{t('partners.review.history')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('partners.review.historyWhen')}</th>
              <th scope="col">{t('partners.review.historyEvent')}</th>
              <th scope="col">{t('partners.review.historyDetail')}</th>
            </tr>
          </thead>
          <tbody>
            {load.history.map((entry) => (
              <tr key={entry.sequence} data-testid="partner-history-row">
                <td>{date(entry.occurredAt)}</td>
                <td>{t(`partners.event.${entry.eventType}`)}</td>
                <td>
                  {entry.fromStatus
                    ? `${t(`partners.status.${entry.fromStatus}`)} → ${t(`partners.status.${entry.toStatus}`)}`
                    : t(`partners.status.${entry.toStatus}`)}
                  {entry.checkCode &&
                    ` · ${t(`partners.check.${entry.checkCode}.title`)}${
                      entry.checkResult ? `: ${t(`partners.result.${entry.checkResult}`)}` : ''
                    }`}
                  {entry.reasonCode && ` · ${t(`partners.reason.${entry.reasonCode}`)}`}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </ScrollTable>
    </section>
  );
}
