import type { MyPaymentDestination } from '@divalhr/api-client';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { formatDate } from '../compensation/compensation';
import { PayrollAlert } from '../payrollRuns/PayrollBits';
import { NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { paymentFailureOf } from './payments';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; destination: MyPaymentDestination };

/**
 * MVP-095A: « Ma destination de paiement » / "My payment destination". A linked employee sees only
 * their own current destination, masked and read-only: the method, the provider, the masked value
 * and the effective date. There is no history, holder name, full number, edit control or other
 * employee. Updates are requested from the organization.
 */
export function MyPaymentDestinationPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });

  useEffect(() => {
    let active = true;
    core.GET('/me/payment-destination', { cache: 'no-store' }).then(
      ({ data, error, response }) => {
        if (!active) return;
        setLoad(
          data
            ? { kind: 'ready', destination: data }
            : { kind: 'failed', failure: paymentFailureOf(response, error) },
        );
      },
      () => {
        if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
      },
    );
    return () => {
      active = false;
    };
  }, [core]);

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-payment-destination">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('payments.my.title')}
        description={t('payments.my.description')}
      />
      {load.kind === 'loading' && <p role="status">{t('payments.loading')}</p>}
      {load.kind === 'failed' && (
        <PayrollAlert failure={load.failure} testId="my-payment-failure" />
      )}
      {load.kind === 'ready' && (
        <section className="card" aria-labelledby={`${ids}-current`}>
          <h2 id={`${ids}-current`}>{t('payments.my.current')}</h2>
          {load.destination.status === 'NOT_RECORDED' || !load.destination.method ? (
            <p data-testid="my-payment-none">{t('payments.my.none')}</p>
          ) : (
            <dl className="decision-summary decision-summary--wrapping" data-testid="my-payment">
              <dt>{t('payments.destination.methodLabel')}</dt>
              <dd>{t(`payments.method.${load.destination.method}`)}</dd>
              {load.destination.provider && (
                <>
                  <dt>{t('payments.destination.providerLabel')}</dt>
                  <dd>{load.destination.provider.name}</dd>
                </>
              )}
              {load.destination.maskedValue && (
                <>
                  <dt>{t('payments.destination.maskedLabel')}</dt>
                  <dd data-testid="my-payment-masked">{load.destination.maskedValue}</dd>
                </>
              )}
              {load.destination.effectiveFrom && (
                <>
                  <dt>{t('payments.destination.effectiveLabel')}</dt>
                  <dd>{formatDate(i18n.language, load.destination.effectiveFrom)}</dd>
                </>
              )}
            </dl>
          )}
          <p className="muted" data-testid="my-payment-request">
            {t('payments.my.requestUpdate')}
          </p>
          <p className="muted">{t('payments.noMoney')}</p>
        </section>
      )}
    </section>
  );
}
