import type {
  PartnerAccountAction,
  PartnerAccountReasonCode,
  PartnerDetail,
  PartnerHistoryEntry,
  PartnerProductSummary,
  PartnerProductType,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { ScrollRegion } from '../people/ScrollRegion';
import { AccountBoundary, AccountStatus, ProductStatus } from './AccountBits';
import {
  ACCOUNT_NOTE_MAX,
  PARTNER_CONFIRMED,
  PARTNER_REASONED,
  PARTNER_REASON_REQUIRED,
  PRODUCT_CODE,
  PRODUCT_TYPES,
  REASON_CODES,
  contentFromForm,
  emptyForm,
  localized,
  productPath,
} from './accounts';
import { PartnerAlert } from './PartnerBits';
import {
  NETWORK_FAILURE,
  countryOptions,
  partnerFailureOf,
  referencePath,
  type PartnerFailure,
} from './partners';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | {
      kind: 'ready';
      partner: PartnerDetail;
      history: PartnerHistoryEntry[];
      products: PartnerProductSummary[];
    };

/** Lifecycle actions: the server lists what is allowed; the browser sends the version it saw. */
function LifecyclePanel({ partner, onChanged }: { partner: PartnerDetail; onChanged: () => void }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const idempotency = useIdempotencyKey();
  const [action, setAction] = useState<PartnerAccountAction | ''>('');
  const [reason, setReason] = useState<PartnerAccountReasonCode | ''>('');
  const [note, setNote] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [added, setAdded] = useState<string[]>([]);
  const [pick, setPick] = useState('');
  const [failure, setFailure] = useState<PartnerFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);
  const options = countryOptions(i18n.language);

  if (partner.allowedActions.length === 0) {
    return (
      <p className="muted" data-testid="lifecycle-closed">
        {t('accounts.detail.noActions')}
      </p>
    );
  }

  const reasoned = action !== '' && PARTNER_REASONED.has(action);
  const needsNote = reasoned && reason === 'OTHER';
  const complete =
    action !== '' &&
    (!PARTNER_REASON_REQUIRED.has(action) || reason !== '') &&
    (!needsNote || note.trim() !== '') &&
    (!PARTNER_CONFIRMED.has(action) || confirmed) &&
    (action !== 'AMEND_SCOPE' || added.length > 0);

  const submit = async () => {
    if (busyRef.current || action === '' || !complete) return;
    busyRef.current = true;
    setBusy(true);
    setFailure(null);
    const body = {
      action,
      expectedVersion: partner.version,
      ...(reasoned && reason !== '' ? { reasonCode: reason } : {}),
      ...(needsNote ? { note: note.trim() } : {}),
      ...(PARTNER_CONFIRMED.has(action) ? { confirmed: true } : {}),
      ...(action === 'AMEND_SCOPE' ? { countries: added } : {}),
    };
    try {
      const { data, error, response } = await core.POST(
        '/platform/partners/{partnerReference}/transitions',
        {
          params: {
            path: { partnerReference: partner.reference },
            header: { 'Idempotency-Key': idempotency.keyFor(body) },
          },
          body,
        },
      );
      if (data) {
        idempotency.reset();
        setAction('');
        setReason('');
        setNote('');
        setConfirmed(false);
        setAdded([]);
        onChanged();
        return;
      }
      setFailure(partnerFailureOf(response, error));
      requestAnimationFrame(() => alertRef.current?.focus());
    } catch {
      setFailure(NETWORK_FAILURE);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <form
      noValidate
      aria-busy={busy}
      data-testid="lifecycle-form"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      {failure && <PartnerAlert failure={failure} testId="lifecycle-failure" alertRef={alertRef} />}
      <div className="field">
        <label htmlFor={`${ids}-action`}>{t('accounts.detail.action')}</label>
        <select
          id={`${ids}-action`}
          value={action}
          data-testid="lifecycle-action"
          onChange={(event) => {
            setAction(event.target.value as PartnerAccountAction | '');
            setReason('');
            setNote('');
            setConfirmed(false);
          }}
        >
          <option value="">{t('accounts.detail.chooseAction')}</option>
          {partner.allowedActions.map((a) => (
            <option key={a} value={a}>
              {t(`accounts.action.${a}`)}
            </option>
          ))}
        </select>
      </div>
      {action !== '' && <p className="muted">{t(`accounts.actionHelp.${action}`)}</p>}
      {reasoned && (
        <div className="field">
          <label htmlFor={`${ids}-reason`}>
            {t('accounts.detail.reason')}
            {!PARTNER_REASON_REQUIRED.has(action) && (
              <span className="muted"> ({t('accounts.optional')})</span>
            )}
          </label>
          <select
            id={`${ids}-reason`}
            value={reason}
            data-testid="lifecycle-reason"
            onChange={(event) => {
              setReason(event.target.value as PartnerAccountReasonCode | '');
            }}
          >
            <option value="">{t('accounts.detail.chooseReason')}</option>
            {REASON_CODES.map((r) => (
              <option key={r} value={r}>
                {t(`accounts.reason.${r}`)}
              </option>
            ))}
          </select>
        </div>
      )}
      {needsNote && (
        <div className="field">
          <label htmlFor={`${ids}-note`}>{t('accounts.detail.note')}</label>
          <p id={`${ids}-note-help`} className="field__help">
            {t('accounts.detail.noteHelp', { max: ACCOUNT_NOTE_MAX })}
          </p>
          <textarea
            id={`${ids}-note`}
            rows={3}
            maxLength={ACCOUNT_NOTE_MAX}
            autoComplete="off"
            aria-describedby={`${ids}-note-help`}
            value={note}
            onChange={(event) => {
              setNote(event.target.value);
            }}
          />
        </div>
      )}
      {action === 'AMEND_SCOPE' && (
        <div className="field">
          <label htmlFor={`${ids}-country`}>{t('accounts.detail.addCountry')}</label>
          <select
            id={`${ids}-country`}
            value={pick}
            onChange={(event) => {
              setPick(event.target.value);
            }}
          >
            <option value="">{t('accounts.detail.chooseCountry')}</option>
            {options
              .filter((o) => !partner.countries.includes(o.value) && !added.includes(o.value))
              .map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
          </select>
          <button
            type="button"
            className="button button--secondary"
            disabled={pick === '' || added.length >= 30}
            onClick={() => {
              setAdded([...added, pick]);
              setPick('');
            }}
          >
            {t('accounts.detail.add')}
          </button>
          <ul aria-label={t('accounts.detail.toAdd')} data-testid="scope-added">
            {added.map((code) => (
              <li key={code}>
                {options.find((o) => o.value === code)?.label ?? code}{' '}
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => {
                    setAdded(added.filter((c) => c !== code));
                  }}
                >
                  {t('accounts.detail.remove')}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
      {action !== '' && PARTNER_CONFIRMED.has(action) && (
        <label className="choice">
          <input
            type="checkbox"
            checked={confirmed}
            data-testid="lifecycle-confirm"
            onChange={(event) => {
              setConfirmed(event.target.checked);
            }}
          />
          {t(`accounts.confirm.${action}`)}
        </label>
      )}
      <div className="button-row">
        <button
          type="submit"
          className="button"
          disabled={!complete || busy}
          data-testid="lifecycle-submit"
        >
          {t('accounts.detail.apply')}
        </button>
      </div>
    </form>
  );
}

/** A new product starts as an empty DRAFT; its content is written in the editor. */
function NewProduct({ partner, onCreated }: { partner: PartnerDetail; onCreated: () => void }) {
  const { t } = useTranslation();
  const { core } = useApi();
  const navigate = useNavigate();
  const ids = useId();
  const idempotency = useIdempotencyKey();
  const [code, setCode] = useState('');
  const [type, setType] = useState<PartnerProductType | ''>('');
  const [failure, setFailure] = useState<PartnerFailure | null>(null);
  const [codeError, setCodeError] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);

  if (partner.status === 'CLOSED') {
    return (
      <p className="muted" data-testid="new-product-closed">
        {t('accounts.products.closed')}
      </p>
    );
  }

  const submit = async () => {
    if (busyRef.current || type === '') return;
    const normalized = code.trim().toUpperCase();
    if (!PRODUCT_CODE.test(normalized)) {
      setCodeError(true);
      return;
    }
    setCodeError(false);
    busyRef.current = true;
    setBusy(true);
    setFailure(null);
    const body = {
      productCode: normalized,
      productType: type,
      content: contentFromForm(emptyForm()),
    };
    try {
      const { data, error, response } = await core.POST(
        '/platform/partners/{partnerReference}/products',
        {
          params: {
            path: { partnerReference: partner.reference },
            header: { 'Idempotency-Key': idempotency.keyFor(body) },
          },
          body,
        },
      );
      if (data) {
        idempotency.reset();
        onCreated();
        void navigate(productPath(partner.reference, data.productCode));
        return;
      }
      setFailure(partnerFailureOf(response, error));
      requestAnimationFrame(() => alertRef.current?.focus());
    } catch {
      setFailure(NETWORK_FAILURE);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <form
      noValidate
      aria-busy={busy}
      data-testid="new-product-form"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      {failure && (
        <PartnerAlert failure={failure} testId="new-product-failure" alertRef={alertRef} />
      )}
      <div className="field">
        <label htmlFor={`${ids}-code`}>{t('accounts.products.code')}</label>
        <p id={`${ids}-code-help`} className="field__help">
          {t('accounts.products.codeHelp')}
        </p>
        <input
          id={`${ids}-code`}
          type="text"
          autoComplete="off"
          maxLength={40}
          value={code}
          aria-invalid={codeError}
          aria-describedby={`${ids}-code-help`}
          onChange={(event) => {
            setCode(event.target.value);
          }}
        />
        {codeError && <p className="field__error">{t('accounts.products.codeInvalid')}</p>}
      </div>
      <div className="field">
        <label htmlFor={`${ids}-type`}>{t('accounts.products.type')}</label>
        <select
          id={`${ids}-type`}
          value={type}
          onChange={(event) => {
            setType(event.target.value as PartnerProductType | '');
          }}
        >
          <option value="">{t('accounts.products.chooseType')}</option>
          {PRODUCT_TYPES.map((p) => (
            <option key={p} value={p}>
              {t(`accounts.productType.${p}`)}
            </option>
          ))}
        </select>
      </div>
      <div className="button-row">
        <button
          type="submit"
          className="button"
          disabled={busy || type === '' || code.trim() === ''}
          data-testid="new-product-submit"
        >
          {t('accounts.products.create')}
        </button>
      </div>
    </form>
  );
}

/**
 * MVP-120E: one partner account for a platform administrator. Identity comes from the approved
 * application; no contact, licence reference, checklist result or note is shown here.
 */
export function PartnerAccountDetailPage() {
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
    const path = { partnerReference: reference };
    Promise.all([
      core.GET('/platform/partners/{partnerReference}', {
        params: { path },
        cache: 'no-store',
      }),
      core.GET('/platform/partners/{partnerReference}/history', {
        params: { path, query: { limit: 50 } },
        cache: 'no-store',
      }),
      core.GET('/platform/partners/{partnerReference}/products', {
        params: { path },
        cache: 'no-store',
      }),
    ]).then(
      ([detail, history, products]) => {
        if (!active) return;
        if (!detail.data) {
          setLoad({ kind: 'failed', failure: partnerFailureOf(detail.response, detail.error) });
        } else if (!history.data) {
          setLoad({ kind: 'failed', failure: partnerFailureOf(history.response, history.error) });
        } else if (!products.data) {
          setLoad({ kind: 'failed', failure: partnerFailureOf(products.response, products.error) });
        } else {
          setLoad({
            kind: 'ready',
            partner: detail.data,
            history: history.data.items,
            products: products.data.items,
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
      <p role="status" data-testid="account-loading">
        {t('accounts.loading')}
      </p>
    );
  }
  if (load.kind === 'failed') {
    return (
      <section data-testid="account-detail-page">
        <PartnerAlert failure={load.failure} testId="account-failure" />
        <p>
          <Link to="/admin/partners">{t('accounts.back')}</Link>
        </p>
      </section>
    );
  }
  const { partner, history, products } = load;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="account-detail-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={partner.tradingName ?? partner.legalName}
        description={t('accounts.detail.description', { reference: partner.reference })}
      />
      <AccountBoundary />
      <dl className="detail-list" data-testid="account-identity">
        <dt>{t('accounts.detail.reference')}</dt>
        <dd data-testid="account-reference">{partner.reference}</dd>
        <dt>{t('accounts.detail.status')}</dt>
        <dd>
          <AccountStatus status={partner.status} />
        </dd>
        <dt>{t('accounts.detail.legalName')}</dt>
        <dd>{partner.legalName}</dd>
        <dt>{t('accounts.detail.country')}</dt>
        <dd>{regionNames.of(partner.countryOfRegistration) ?? partner.countryOfRegistration}</dd>
        <dt>{t('accounts.detail.categories')}</dt>
        <dd>
          {[partner.primaryCategory, ...partner.additionalCategories]
            .map((c) => t(`partners.category.${c}`))
            .join(', ')}
        </dd>
        <dt>{t('accounts.detail.scope')}</dt>
        <dd data-testid="account-scope">
          {partner.countries.map((c) => regionNames.of(c) ?? c).join(', ')}
        </dd>
        <dt>{t('accounts.detail.application')}</dt>
        <dd>
          <Link to={referencePath(partner.applicationReference)}>
            {partner.applicationReference}
          </Link>
        </dd>
        <dt>{t('accounts.detail.updated')}</dt>
        <dd>
          <time dateTime={partner.updatedAt}>{date(partner.updatedAt)}</time>
        </dd>
      </dl>

      <section aria-labelledby={`${ids}-lifecycle`}>
        <h2 id={`${ids}-lifecycle`}>{t('accounts.detail.lifecycle')}</h2>
        <LifecyclePanel key={partner.version} partner={partner} onChanged={reload} />
      </section>

      <section aria-labelledby={`${ids}-products`}>
        <h2 id={`${ids}-products`}>{t('accounts.products.title')}</h2>
        {products.length === 0 ? (
          <p className="muted" data-testid="products-empty">
            {t('accounts.products.empty')}
          </p>
        ) : (
          <ScrollRegion label={t('accounts.products.tableLabel')}>
            <table className="timesheet-table" data-testid="products-table">
              <caption className="visually-hidden">{t('accounts.products.tableLabel')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('accounts.products.product')}</th>
                  <th scope="col">{t('accounts.products.type')}</th>
                  <th scope="col">{t('accounts.products.latest')}</th>
                  <th scope="col">{t('accounts.products.live')}</th>
                </tr>
              </thead>
              <tbody>
                {products.map((p) => (
                  <tr key={p.productCode} data-testid="product-row" data-code={p.productCode}>
                    <th scope="row">
                      <Link to={productPath(partner.reference, p.productCode)}>
                        {localized(p.name, i18n.language) || p.productCode}
                      </Link>
                      <span className="muted"> · {p.productCode}</span>
                    </th>
                    <td>{t(`accounts.productType.${p.productType}`)}</td>
                    <td>
                      {t('accounts.product.versionShort', { version: p.latestVersion })}{' '}
                      <ProductStatus status={p.latestStatus} />
                    </td>
                    <td>
                      {p.liveVersion !== null && p.liveStatus !== null ? (
                        <>
                          {t('accounts.product.versionShort', { version: p.liveVersion })}{' '}
                          <ProductStatus status={p.liveStatus} />
                        </>
                      ) : (
                        <span className="muted">{t('accounts.products.noneLive')}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        )}
        <h3>{t('accounts.products.new')}</h3>
        <NewProduct partner={partner} onCreated={reload} />
      </section>

      <section aria-labelledby={`${ids}-history`}>
        <h2 id={`${ids}-history`}>{t('accounts.detail.history')}</h2>
        <ScrollRegion label={t('accounts.detail.historyLabel')}>
          <table className="timesheet-table" data-testid="account-history">
            <caption className="visually-hidden">{t('accounts.detail.historyLabel')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('accounts.detail.when')}</th>
                <th scope="col">{t('accounts.detail.event')}</th>
                <th scope="col">{t('accounts.detail.reason')}</th>
              </tr>
            </thead>
            <tbody>
              {history.map((entry) => (
                <tr key={entry.sequence} data-testid="account-history-row">
                  <td>
                    <time dateTime={entry.occurredAt}>{date(entry.occurredAt)}</time>
                  </td>
                  <td>
                    {t(`accounts.event.${entry.eventType}`)}
                    {entry.itemCount !== null && (
                      <span className="muted">
                        {' '}
                        ({t('accounts.detail.countries', { count: entry.itemCount })})
                      </span>
                    )}
                  </td>
                  <td>
                    {entry.reasonCode ? t(`accounts.reason.${entry.reasonCode}`) : '—'}
                    {entry.noteRecorded && (
                      <span className="muted"> · {t('accounts.detail.noteRecorded')}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      </section>
      <p>
        <Link to="/admin/partners">{t('accounts.back')}</Link>
      </p>
    </section>
  );
}
