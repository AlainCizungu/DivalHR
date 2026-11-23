import type {
  PartnerAccountReasonCode,
  PartnerDetail,
  PartnerProductAction,
  PartnerProductDetail,
  PartnerProductHistoryEntry,
  PartnerProductVersion,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { ScrollRegion } from '../people/ScrollRegion';
import { AccountBoundary, AccountStatus, ProductStatus } from './AccountBits';
import {
  ACCOUNT_NOTE_MAX,
  CURRENCIES,
  NEEDS_LIMITS,
  PRODUCT_CONFIRMED,
  PRODUCT_REASONED,
  REASON_CODES,
  contentErrorsFromProblem,
  contentFromForm,
  formFromContent,
  localized,
  partnerPath,
  validateContent,
  type ContentErrors,
  type ContentForm,
} from './accounts';
import { PartnerAlert } from './PartnerBits';
import { NETWORK_FAILURE, countryOptions, partnerFailureOf, type PartnerFailure } from './partners';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | {
      kind: 'ready';
      product: PartnerProductDetail;
      partner: PartnerDetail;
      history: PartnerProductHistoryEntry[];
    };

const BILINGUAL = [
  { key: 'name', min: 2, max: 120, rows: 1 },
  { key: 'summary', min: 10, max: 300, rows: 2 },
  { key: 'eligibility', min: 10, max: 500, rows: 3 },
  { key: 'disclosure', min: 20, max: 2000, rows: 5 },
] as const;

function ErrorText({ id, constraint }: { id: string; constraint: string | undefined }) {
  const { t } = useTranslation();
  if (!constraint) return null;
  return (
    <p id={id} className="field__error" data-testid={id}>
      {t(`accounts.editor.constraint.${constraint}`)}
    </p>
  );
}

/**
 * The bilingual editor for a version. Both languages are checked for format here; completeness is
 * decided by the server when the version is submitted, so a draft may be saved incomplete.
 */
function ContentEditor({
  product,
  partner,
  version,
  saved,
  onSavedChange,
  onSaved,
}: {
  product: PartnerProductDetail;
  partner: PartnerDetail;
  version: PartnerProductVersion;
  /** Held by the page: a save changes the row version, which remounts this editor. */
  saved: boolean;
  onSavedChange: (saved: boolean) => void;
  onSaved: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const idempotency = useIdempotencyKey();
  const [form, setForm] = useState<ContentForm>(() => formFromContent(version.content));
  const [errors, setErrors] = useState<ContentErrors>({});
  const [failure, setFailure] = useState<PartnerFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);
  const regionNames = new Intl.DisplayNames([i18n.language], { type: 'region' });
  const isNew = version.status !== 'DRAFT';
  const scope = countryOptions(i18n.language).filter((o) => partner.countries.includes(o.value));

  const set = <K extends keyof ContentForm>(key: K, value: ContentForm[K]) => {
    onSavedChange(false);
    setForm((current) => ({ ...current, [key]: value }));
  };

  const save = async () => {
    if (busyRef.current) return;
    const found = validateContent(form);
    setErrors(found);
    if (Object.keys(found).length > 0) {
      setFailure({ kind: 'invalid', messageKey: 'errors.PARTNER_PRODUCT_INVALID' });
      requestAnimationFrame(() => alertRef.current?.focus());
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setFailure(null);
    const body = {
      expectedVersion: version.version,
      expectedRowVersion: version.rowVersion,
      content: contentFromForm(form),
    };
    try {
      const { data, error, response } = await core.PUT(
        '/platform/partners/{partnerReference}/products/{productCode}/draft',
        {
          params: {
            path: { partnerReference: product.partnerReference, productCode: product.productCode },
            header: { 'Idempotency-Key': idempotency.keyFor(body) },
          },
          body,
        },
      );
      if (data) {
        idempotency.reset();
        onSavedChange(true);
        onSaved();
        return;
      }
      setErrors(contentErrorsFromProblem(error) ?? {});
      setFailure(partnerFailureOf(response, error));
      requestAnimationFrame(() => alertRef.current?.focus());
    } catch {
      setFailure(NETWORK_FAILURE);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const text = (key: keyof ContentForm, label: string, rows: number, max: number) => {
    const id = `${ids}-${key}`;
    const error = errors[key];
    const common = {
      id,
      value: form[key] as string,
      maxLength: max,
      'aria-invalid': error !== undefined,
      'aria-describedby': error ? `${id}-error` : undefined,
      onChange: (event: { target: { value: string } }) => {
        set(key, event.target.value as never);
      },
    };
    return (
      <div className="field" key={key}>
        <label htmlFor={id}>{label}</label>
        {rows === 1 ? (
          <input type="text" autoComplete="off" {...common} />
        ) : (
          <textarea rows={rows} {...common} />
        )}
        <ErrorText id={`${id}-error`} constraint={error} />
      </div>
    );
  };

  return (
    <form
      noValidate
      aria-busy={busy}
      data-testid="content-editor"
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
    >
      <p className="muted">
        {isNew ? t('accounts.editor.newVersion') : t('accounts.editor.draft')}
      </p>
      {failure && <PartnerAlert failure={failure} testId="editor-failure" alertRef={alertRef} />}
      {BILINGUAL.map(({ key, max, rows }) => (
        <fieldset key={key} className="partner-bilingual">
          <legend>{t(`accounts.editor.${key}`)}</legend>
          {text(`${key}En` as keyof ContentForm, t('accounts.editor.english'), rows, max)}
          {text(`${key}Fr` as keyof ContentForm, t('accounts.editor.french'), rows, max)}
        </fieldset>
      ))}
      <div className="field">
        <label htmlFor={`${ids}-infoUrl`}>{t('accounts.editor.infoUrl')}</label>
        <p id={`${ids}-infoUrl-help`} className="field__help">
          {t('accounts.editor.infoUrlHelp')}
        </p>
        <input
          id={`${ids}-infoUrl`}
          type="url"
          autoComplete="off"
          maxLength={300}
          value={form.infoUrl}
          aria-invalid={errors.infoUrl !== undefined}
          aria-describedby={`${ids}-infoUrl-help${errors.infoUrl ? ` ${ids}-infoUrl-error` : ''}`}
          onChange={(event) => {
            set('infoUrl', event.target.value);
          }}
        />
        <ErrorText id={`${ids}-infoUrl-error`} constraint={errors.infoUrl} />
      </div>
      <div className="partner-dates">
        <div className="field">
          <label htmlFor={`${ids}-effective`}>{t('accounts.editor.effectiveDate')}</label>
          <input
            id={`${ids}-effective`}
            type="date"
            value={form.effectiveDate}
            aria-invalid={errors.effectiveDate !== undefined}
            onChange={(event) => {
              set('effectiveDate', event.target.value);
            }}
          />
          <ErrorText id={`${ids}-effective-error`} constraint={errors.effectiveDate} />
        </div>
        <div className="field">
          <label htmlFor={`${ids}-end`}>{t('accounts.editor.endDate')}</label>
          <input
            id={`${ids}-end`}
            type="date"
            value={form.endDate}
            aria-invalid={errors.endDate !== undefined}
            onChange={(event) => {
              set('endDate', event.target.value);
            }}
          />
          <ErrorText id={`${ids}-end-error`} constraint={errors.endDate} />
        </div>
      </div>
      <div className="partner-dates">
        <div className="field">
          <label htmlFor={`${ids}-min`}>{t('accounts.editor.minMonths')}</label>
          <input
            id={`${ids}-min`}
            type="text"
            inputMode="numeric"
            autoComplete="off"
            value={form.minMonths}
            aria-invalid={errors.minMonths !== undefined}
            onChange={(event) => {
              set('minMonths', event.target.value);
            }}
          />
        </div>
        <div className="field">
          <label htmlFor={`${ids}-max`}>{t('accounts.editor.maxMonths')}</label>
          <input
            id={`${ids}-max`}
            type="text"
            inputMode="numeric"
            autoComplete="off"
            value={form.maxMonths}
            aria-invalid={errors.minMonths !== undefined}
            onChange={(event) => {
              set('maxMonths', event.target.value);
            }}
          />
        </div>
      </div>
      <ErrorText id={`${ids}-months-error`} constraint={errors.minMonths} />
      {NEEDS_LIMITS.has(product.productType) && (
        <p className="muted">{t('accounts.editor.limitsRequired')}</p>
      )}

      <fieldset>
        <legend>{t('accounts.editor.countries')}</legend>
        <p className="field__help">{t('accounts.editor.countriesHelp')}</p>
        {scope.map((o) => (
          <label className="choice" key={o.value}>
            <input
              type="checkbox"
              checked={form.countries.includes(o.value)}
              onChange={(event) => {
                set(
                  'countries',
                  event.target.checked
                    ? [...form.countries, o.value]
                    : form.countries.filter((c) => c !== o.value),
                );
              }}
            />
            {regionNames.of(o.value) ?? o.value}
          </label>
        ))}
        <ErrorText id={`${ids}-countries-error`} constraint={errors.countries} />
      </fieldset>

      <fieldset>
        <legend>{t('accounts.editor.currencies')}</legend>
        {CURRENCIES.map((currency) => {
          const row = form.currencies[currency] ?? { on: false, min: '', max: '' };
          const update = (next: Partial<typeof row>) => {
            set('currencies', { ...form.currencies, [currency]: { ...row, ...next } });
          };
          return (
            <div key={currency} className="partner-currency">
              <label className="choice">
                <input
                  type="checkbox"
                  checked={row.on}
                  onChange={(event) => {
                    update({ on: event.target.checked });
                  }}
                />
                {t(`accounts.currency.${currency}`)}
              </label>
              {row.on && (
                <div className="partner-dates">
                  <div className="field">
                    <label htmlFor={`${ids}-${currency}-min`}>
                      {t('accounts.editor.minAmount', { currency })}
                    </label>
                    <input
                      id={`${ids}-${currency}-min`}
                      type="text"
                      inputMode="decimal"
                      autoComplete="off"
                      value={row.min}
                      aria-invalid={errors[`currency-${currency}`] !== undefined}
                      onChange={(event) => {
                        update({ min: event.target.value });
                      }}
                    />
                  </div>
                  <div className="field">
                    <label htmlFor={`${ids}-${currency}-max`}>
                      {t('accounts.editor.maxAmount', { currency })}
                    </label>
                    <input
                      id={`${ids}-${currency}-max`}
                      type="text"
                      inputMode="decimal"
                      autoComplete="off"
                      value={row.max}
                      aria-invalid={errors[`currency-${currency}`] !== undefined}
                      onChange={(event) => {
                        update({ max: event.target.value });
                      }}
                    />
                  </div>
                  <ErrorText
                    id={`${ids}-${currency}-error`}
                    constraint={errors[`currency-${currency}`]}
                  />
                </div>
              )}
            </div>
          );
        })}
        <ErrorText id={`${ids}-currencies-error`} constraint={errors.currencies} />
      </fieldset>

      <div className="button-row">
        <button type="submit" className="button" disabled={busy} data-testid="editor-save">
          {isNew ? t('accounts.editor.saveNew') : t('accounts.editor.save')}
        </button>
      </div>
      {saved && (
        <p role="status" data-testid="editor-saved">
          {t('accounts.editor.saved')}
        </p>
      )}
    </form>
  );
}

/** One lifecycle action on a version; the browser sends the row version it saw. */
function VersionTransition({
  product,
  version,
  onChanged,
}: {
  product: PartnerProductDetail;
  version: PartnerProductVersion;
  onChanged: () => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const idempotency = useIdempotencyKey();
  const [action, setAction] = useState<PartnerProductAction | ''>('');
  const [reason, setReason] = useState<PartnerAccountReasonCode | ''>('');
  const [note, setNote] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [failure, setFailure] = useState<PartnerFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);

  if (version.allowedActions.length === 0) return null;
  const reasoned = action !== '' && PRODUCT_REASONED.has(action);
  const needsNote = reasoned && reason === 'OTHER';
  const complete =
    action !== '' &&
    (!reasoned || reason !== '') &&
    (!needsNote || note.trim() !== '') &&
    (!PRODUCT_CONFIRMED.has(action) || confirmed);

  const submit = async () => {
    if (busyRef.current || action === '' || !complete) return;
    busyRef.current = true;
    setBusy(true);
    setFailure(null);
    const body = {
      action,
      version: version.version,
      expectedRowVersion: version.rowVersion,
      ...(reasoned && reason !== '' ? { reasonCode: reason } : {}),
      ...(needsNote ? { note: note.trim() } : {}),
      ...(PRODUCT_CONFIRMED.has(action) ? { confirmed: true } : {}),
    };
    try {
      const { data, error, response } = await core.POST(
        '/platform/partners/{partnerReference}/products/{productCode}/transitions',
        {
          params: {
            path: { partnerReference: product.partnerReference, productCode: product.productCode },
            header: { 'Idempotency-Key': idempotency.keyFor(body) },
          },
          body,
        },
      );
      if (data) {
        idempotency.reset();
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
      data-testid="version-transition"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      {failure && (
        <PartnerAlert failure={failure} testId="transition-failure" alertRef={alertRef} />
      )}
      <div className="field">
        <label htmlFor={`${ids}-action`}>{t('accounts.detail.action')}</label>
        <select
          id={`${ids}-action`}
          value={action}
          data-testid="version-action"
          onChange={(event) => {
            setAction(event.target.value as PartnerProductAction | '');
            setReason('');
            setNote('');
            setConfirmed(false);
          }}
        >
          <option value="">{t('accounts.detail.chooseAction')}</option>
          {version.allowedActions.map((a) => (
            <option key={a} value={a}>
              {t(`accounts.productAction.${a}`)}
            </option>
          ))}
        </select>
      </div>
      {action !== '' && <p className="muted">{t(`accounts.productActionHelp.${action}`)}</p>}
      {reasoned && (
        <div className="field">
          <label htmlFor={`${ids}-reason`}>{t('accounts.detail.reason')}</label>
          <select
            id={`${ids}-reason`}
            value={reason}
            data-testid="version-reason"
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
          <textarea
            id={`${ids}-note`}
            rows={3}
            maxLength={ACCOUNT_NOTE_MAX}
            autoComplete="off"
            value={note}
            onChange={(event) => {
              setNote(event.target.value);
            }}
          />
        </div>
      )}
      {action !== '' && PRODUCT_CONFIRMED.has(action) && (
        <label className="choice">
          <input
            type="checkbox"
            checked={confirmed}
            data-testid="version-confirm"
            onChange={(event) => {
              setConfirmed(event.target.checked);
            }}
          />
          {t(`accounts.productConfirm.${action}`)}
        </label>
      )}
      <div className="button-row">
        <button
          type="submit"
          className="button"
          disabled={!complete || busy}
          data-testid="version-submit"
        >
          {t('accounts.detail.apply')}
        </button>
      </div>
    </form>
  );
}

/** A read-only rendering of a version's content, in both languages. */
function ContentSummary({ version }: { version: PartnerProductVersion }) {
  const { t, i18n } = useTranslation();
  const regionNames = new Intl.DisplayNames([i18n.language], { type: 'region' });
  const c = version.content;
  const row = (label: string, value: ReactNode) => (
    <>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </>
  );
  return (
    <dl className="detail-list" data-testid="version-content">
      {BILINGUAL.map(({ key }) =>
        row(
          t(`accounts.editor.${key}`),
          <>
            <span lang="en">{c[key].en ?? '—'}</span> / <span lang="fr">{c[key].fr ?? '—'}</span>
          </>,
        ),
      )}
      {row(t('accounts.editor.effectiveDate'), c.effectiveDate ?? '—')}
      {row(t('accounts.editor.endDate'), c.endDate ?? '—')}
      {row(
        t('accounts.editor.countries'),
        c.countries.map((x) => regionNames.of(x) ?? x).join(', ') || '—',
      )}
      {row(
        t('accounts.editor.currencies'),
        c.currencies
          .map((l) => `${l.currency} ${l.minAmount ?? '—'}–${l.maxAmount ?? '—'}`)
          .join('; ') || '—',
      )}
    </dl>
  );
}

/**
 * MVP-120E: one product with its versions. A version is edited only while it is a draft; saving an
 * edit to a live version starts a new draft version and never changes what employees see until
 * that draft is submitted and published.
 */
export function PartnerProductPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const { reference = '', code = '' } = useParams();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [reloads, setReloads] = useState(0);
  const [saved, setSaved] = useState(false);
  const reload = useCallback(() => {
    setReloads((n) => n + 1);
  }, []);
  const date = (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(iso),
    );

  useEffect(() => {
    let active = true;
    const path = { partnerReference: reference, productCode: code };
    Promise.all([
      core.GET('/platform/partners/{partnerReference}/products/{productCode}', {
        params: { path },
        cache: 'no-store',
      }),
      core.GET('/platform/partners/{partnerReference}', {
        params: { path: { partnerReference: reference } },
        cache: 'no-store',
      }),
      core.GET('/platform/partners/{partnerReference}/products/{productCode}/history', {
        params: { path, query: { limit: 50 } },
        cache: 'no-store',
      }),
    ]).then(
      ([product, partner, history]) => {
        if (!active) return;
        const failed = [product, partner, history].find((r) => !r.data);
        if (failed || !product.data || !partner.data || !history.data) {
          setLoad({
            kind: 'failed',
            failure: failed ? partnerFailureOf(failed.response, failed.error) : NETWORK_FAILURE,
          });
          return;
        }
        setLoad({
          kind: 'ready',
          product: product.data,
          partner: partner.data,
          history: history.data.items,
        });
      },
      () => {
        if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
      },
    );
    return () => {
      active = false;
    };
  }, [core, reference, code, reloads]);

  if (load.kind === 'loading') {
    return (
      <p role="status" data-testid="product-loading">
        {t('accounts.loading')}
      </p>
    );
  }
  if (load.kind === 'failed') {
    return (
      <section data-testid="product-page">
        <PartnerAlert failure={load.failure} testId="product-failure" />
        <p>
          <Link to={partnerPath(reference)}>{t('accounts.backToPartner')}</Link>
        </p>
      </section>
    );
  }
  const { product, partner, history } = load;
  const versions = [...product.versions].sort((a, b) => b.version - a.version);
  const latest: PartnerProductVersion | undefined = versions[0];
  const editable =
    latest !== undefined && latest.status !== 'UNDER_REVIEW' && partner.status !== 'CLOSED';
  const title = latest ? localized(latest.content.name, i18n.language) : '';

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="product-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={title || product.productCode}
        description={t('accounts.product.description', {
          code: product.productCode,
          type: t(`accounts.productType.${product.productType}`),
        })}
      />
      <AccountBoundary />
      <p>
        {partner.legalName} <AccountStatus status={partner.status} />{' '}
        <span className="muted">
          · {t('accounts.product.publicReference')}:{' '}
          <span data-testid="product-public-reference">{product.publicReference}</span>
        </span>
      </p>

      {editable && (
        <section aria-labelledby={`${ids}-edit`}>
          <h2 id={`${ids}-edit`}>{t('accounts.editor.title')}</h2>
          <ContentEditor
            key={`${latest.version}-${latest.rowVersion}`}
            product={product}
            partner={partner}
            version={latest}
            saved={saved}
            onSavedChange={setSaved}
            onSaved={reload}
          />
        </section>
      )}

      <section aria-labelledby={`${ids}-versions`}>
        <h2 id={`${ids}-versions`}>{t('accounts.product.versions')}</h2>
        <ul className="partner-versions" data-testid="versions">
          {versions.map((v) => (
            <li
              key={v.version}
              className="card"
              data-testid="version"
              data-version={v.version}
              data-status={v.status}
            >
              <h3>
                {t('accounts.product.version', { version: v.version })}{' '}
                <ProductStatus status={v.status} />
              </h3>
              <p className="muted">
                <time dateTime={v.updatedAt}>{date(v.updatedAt)}</time>
              </p>
              <ContentSummary version={v} />
              <VersionTransition
                key={`${v.version}-${v.rowVersion}`}
                product={product}
                version={v}
                onChanged={() => {
                  setSaved(false);
                  reload();
                }}
              />
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby={`${ids}-history`}>
        <h2 id={`${ids}-history`}>{t('accounts.detail.history')}</h2>
        <ScrollRegion label={t('accounts.product.historyLabel')}>
          <table className="timesheet-table" data-testid="product-history">
            <caption className="visually-hidden">{t('accounts.product.historyLabel')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('accounts.detail.when')}</th>
                <th scope="col">{t('accounts.product.versionColumn')}</th>
                <th scope="col">{t('accounts.detail.event')}</th>
                <th scope="col">{t('accounts.detail.reason')}</th>
              </tr>
            </thead>
            <tbody>
              {history.map((entry) => (
                <tr key={entry.sequence} data-testid="product-history-row">
                  <td>
                    <time dateTime={entry.occurredAt}>{date(entry.occurredAt)}</time>
                  </td>
                  <td>{t('accounts.product.versionShort', { version: entry.version })}</td>
                  <td>{t(`accounts.productEvent.${entry.eventType}`)}</td>
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
        <Link to={partnerPath(reference)}>{t('accounts.backToPartner')}</Link>
      </p>
    </section>
  );
}
