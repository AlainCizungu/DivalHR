import type {
  Assignment,
  AssignmentKind,
  CompensationBasis,
  ContractClassification,
  EmploymentChange,
  EmploymentChangeCommand,
  EmploymentChangePreview,
  EmploymentChangeReason,
  PlacementInput,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { HistoryAlert } from './HistoryAlert';
import {
  CHANGE_REASONS,
  COMPENSATIONS,
  CONTRACTS,
  CORRECTION_REASONS,
  formatDate,
  formatPeriod,
  HISTORY_NETWORK_FAILURE,
  historyFailureOf,
  KINDS,
  valueText,
  type HistoryFailure,
} from './history';
import { ManagerPicker, type ManagerChoice } from './ManagerPicker';
import { EMPTY_PLACEMENT, PlacementFields } from './PlacementFields';
import { PreviewTable } from './PreviewTable';

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/u;

/** Failures after which the preview no longer applies and must be repeated. */
const STALE = new Set(['EMPLOYMENT_PREVIEW_CHANGED', 'EMPLOYMENT_VERSION_CONFLICT']);

type Problem =
  | 'date'
  | 'kinds'
  | 'placement'
  | 'manager'
  | 'contract'
  | 'compensation'
  | 'reason'
  | 'acknowledge';

/**
 * MVP-021: records an effective-dated change (one or more kinds from a date) or, with a target
 * row, a correction of that row's value for its own dates. The command is previewed first: the
 * exact rows before and after, the timing (scheduled, current or retroactive) and warnings. The
 * commit repeats the preview's version and digest, so anything that changed in between is refused
 * and must be previewed again. A retroactive change needs a reason and an explicit
 * acknowledgement. Retries of the same commit reuse its idempotency key.
 */
export function ChangeForm({
  employeeId,
  businessDate,
  target,
  onRecorded,
  onClose,
}: {
  employeeId: string;
  businessDate: string;
  target: Assignment | null;
  onRecorded: (change: EmploymentChange) => void;
  onClose?: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const correction = target !== null;
  const [effectiveFrom, setEffectiveFrom] = useState(target?.effectiveFrom ?? '');
  const [kinds, setKinds] = useState<ReadonlySet<AssignmentKind>>(
    new Set(target ? [target.kind] : []),
  );
  const [placement, setPlacement] = useState<PlacementInput>(EMPTY_PLACEMENT);
  const [manager, setManager] = useState<ManagerChoice>({ mode: 'set', employee: null });
  const [contract, setContract] = useState<ContractClassification | ''>('');
  const [compensation, setCompensation] = useState<CompensationBasis | ''>('');
  const [reason, setReason] = useState<EmploymentChangeReason | ''>('');
  const [problems, setProblems] = useState<ReadonlySet<Problem>>(new Set());
  const [preview, setPreview] = useState<EmploymentChangePreview | null>(null);
  const [previewed, setPreviewed] = useState<EmploymentChangeCommand | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<HistoryFailure | null>(null);
  const previewHeading = useRef<HTMLHeadingElement>(null);
  const failureBox = useRef<HTMLDivElement>(null);
  const summaryBox = useRef<HTMLDivElement>(null);
  const title = useRef<HTMLHeadingElement>(null);
  const focusNext = useRef<'preview' | 'failure' | 'summary' | null>(null);

  // A correction opens from a timeline row: move to the form.
  useEffect(() => {
    if (correction) title.current?.focus();
  }, [correction]);

  useEffect(() => {
    const element = {
      preview: previewHeading.current,
      failure: failureBox.current,
      summary: summaryBox.current,
    }[focusNext.current ?? 'preview'];
    if (focusNext.current && element) {
      focusNext.current = null;
      element.focus();
    }
  });

  /** Any edit invalidates the preview. */
  const edited = () => {
    setPreview(null);
    setPreviewed(null);
    setAcknowledged(false);
    setFailure(null);
  };

  const retroactive = ISO_DATE.test(effectiveFrom) && effectiveFrom < businessDate;
  const reasons = correction ? CORRECTION_REASONS : CHANGE_REASONS;
  const reasonRequired = correction || retroactive;

  const validate = (): ReadonlySet<Problem> => {
    const found = new Set<Problem>();
    if (!ISO_DATE.test(effectiveFrom)) found.add('date');
    if (kinds.size === 0) found.add('kinds');
    if (kinds.has('PLACEMENT') && (!placement.legalEntityId || !placement.siteId)) {
      found.add('placement');
    }
    if (kinds.has('MANAGER') && manager.mode === 'set' && !manager.employee) found.add('manager');
    if (kinds.has('CONTRACT') && !contract) found.add('contract');
    if (kinds.has('COMPENSATION') && !compensation) found.add('compensation');
    if (reasonRequired && !reason) found.add('reason');
    return found;
  };

  const command = (): EmploymentChangeCommand => ({
    type: correction ? 'CORRECTION' : 'CHANGE',
    effectiveFrom,
    placement: kinds.has('PLACEMENT') ? placement : null,
    manager: kinds.has('MANAGER')
      ? { employeeId: manager.mode === 'set' ? (manager.employee?.id ?? null) : null }
      : null,
    contractClassification: kinds.has('CONTRACT') && contract ? contract : null,
    compensationBasis: kinds.has('COMPENSATION') && compensation ? compensation : null,
    reasonCode: reason || null,
    correctsAssignmentId: target?.id ?? null,
  });

  const onPreview = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    const found = validate();
    setProblems(found);
    if (found.size > 0) {
      focusNext.current = 'summary';
      return;
    }
    const body = command();
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/employment-changes/preview',
        { params: { path: { employeeId } }, body, cache: 'no-store' },
      );
      if (data) {
        setPreview(data);
        setPreviewed(body);
        setAcknowledged(false);
        focusNext.current = 'preview';
      } else {
        setFailure(historyFailureOf(response, error));
        focusNext.current = 'failure';
      }
    } catch {
      setFailure(HISTORY_NETWORK_FAILURE);
      focusNext.current = 'failure';
    } finally {
      setBusy(null);
    }
  };

  const onCommit = async () => {
    if (!preview || !previewed || busy) return;
    if (preview.requiresAcknowledgement && !acknowledged) {
      setProblems(new Set<Problem>(['acknowledge']));
      focusNext.current = 'summary';
      return;
    }
    setProblems(new Set());
    const body = {
      ...previewed,
      expectedVersion: preview.expectedVersion,
      previewDigest: preview.previewDigest,
      acknowledgeRetroactive: preview.requiresAcknowledgement ? acknowledged : false,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/employment-changes',
        {
          params: {
            path: { employeeId },
            header: { 'Idempotency-Key': commitKey.keyFor({ employeeId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        commitKey.reset();
        onRecorded(data.change);
        return;
      }
      const refused = historyFailureOf(response, error);
      if (refused.code && STALE.has(refused.code)) {
        commitKey.reset();
        setPreview(null);
        setPreviewed(null);
      }
      setFailure(refused);
      focusNext.current = 'failure';
    } catch {
      setFailure(HISTORY_NETWORK_FAILURE);
      focusNext.current = 'failure';
    } finally {
      setBusy(null);
    }
  };

  const toggleKind = (kind: AssignmentKind, on: boolean) => {
    const next = new Set(kinds);
    if (on) next.add(kind);
    else next.delete(kind);
    setKinds(next);
    edited();
  };

  const problemText = (problem: Problem) => t(`employees.form.problems.${problem}`);
  const titleId = `${ids}-title`;

  return (
    <section aria-labelledby={titleId} className="card" data-testid="change-form">
      <h2 id={titleId} tabIndex={-1} ref={title}>
        {correction ? t('employees.form.correctTitle') : t('employees.form.title')}
      </h2>
      {correction && (
        <p data-testid="correction-target">
          {t('employees.form.correcting', {
            kind: t(`employees.kinds.${target.kind}`),
            period: formatPeriod(t, i18n.language, target.effectiveFrom, target.effectiveTo),
            value: valueText(t, target),
          })}
        </p>
      )}
      <p className="muted">
        {t('employees.form.help', { date: formatDate(i18n.language, businessDate) })}
      </p>
      {problems.size > 0 && (
        <div
          className="error-summary"
          role="alert"
          tabIndex={-1}
          ref={summaryBox}
          data-testid="form-problems"
        >
          <p>{t('employees.form.problems.title')}</p>
          <ul>
            {[...problems].map((problem) => (
              <li key={problem}>{problemText(problem)}</li>
            ))}
          </ul>
        </div>
      )}
      <form noValidate onSubmit={(event) => void onPreview(event)}>
        <div className="field">
          <label htmlFor={`${ids}-date`}>{t('employees.form.effectiveFrom')}</label>
          <p id={`${ids}-date-help`} className="field__help">
            {correction ? t('employees.form.correctionDateHelp') : t('employees.form.dateHelp')}
          </p>
          <input
            id={`${ids}-date`}
            type="date"
            value={effectiveFrom}
            readOnly={correction}
            aria-invalid={problems.has('date') ? true : undefined}
            aria-describedby={`${ids}-date-help`}
            onChange={(event) => {
              setEffectiveFrom(event.target.value);
              edited();
            }}
          />
        </div>
        {!correction && (
          <fieldset className="field">
            <legend>{t('employees.form.kinds')}</legend>
            {KINDS.map((kind) => (
              <div className="choice" key={kind}>
                <input
                  id={`${ids}-kind-${kind}`}
                  type="checkbox"
                  checked={kinds.has(kind)}
                  aria-invalid={problems.has('kinds') ? true : undefined}
                  onChange={(event) => {
                    toggleKind(kind, event.target.checked);
                  }}
                />
                <label htmlFor={`${ids}-kind-${kind}`}>{t(`employees.kinds.${kind}`)}</label>
              </div>
            ))}
          </fieldset>
        )}
        {kinds.has('PLACEMENT') && (
          <PlacementFields
            idPrefix={`${ids}-placement`}
            value={placement}
            invalid={problems.has('placement')}
            onChange={(value) => {
              setPlacement(value);
              edited();
            }}
          />
        )}
        {kinds.has('MANAGER') && (
          <ManagerPicker
            idPrefix={`${ids}-manager`}
            employeeId={employeeId}
            value={manager}
            allowClear={!correction}
            invalid={problems.has('manager')}
            onChange={(value) => {
              setManager(value);
              edited();
            }}
          />
        )}
        {kinds.has('CONTRACT') && (
          <div className="field">
            <label htmlFor={`${ids}-contract`}>{t('employees.kinds.CONTRACT')}</label>
            <p id={`${ids}-contract-help`} className="field__help">
              {t('employees.form.contractHelp')}
            </p>
            <select
              id={`${ids}-contract`}
              value={contract}
              aria-describedby={`${ids}-contract-help`}
              aria-invalid={problems.has('contract') ? true : undefined}
              onChange={(event) => {
                setContract(event.target.value as ContractClassification | '');
                edited();
              }}
            >
              <option value="">{t('employees.placement.choose')}</option>
              {CONTRACTS.map((code) => (
                <option key={code} value={code}>
                  {t(`employees.contract.${code}`)}
                </option>
              ))}
            </select>
          </div>
        )}
        {kinds.has('COMPENSATION') && (
          <div className="field">
            <label htmlFor={`${ids}-compensation`}>{t('employees.kinds.COMPENSATION')}</label>
            <p id={`${ids}-compensation-help`} className="field__help">
              {t('employees.form.compensationHelp')}
            </p>
            <select
              id={`${ids}-compensation`}
              value={compensation}
              aria-describedby={`${ids}-compensation-help`}
              aria-invalid={problems.has('compensation') ? true : undefined}
              onChange={(event) => {
                setCompensation(event.target.value as CompensationBasis | '');
                edited();
              }}
            >
              <option value="">{t('employees.placement.choose')}</option>
              {COMPENSATIONS.map((code) => (
                <option key={code} value={code}>
                  {t(`employees.compensation.${code}`)}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="field">
          <label htmlFor={`${ids}-reason`}>
            {reasonRequired
              ? t('employees.form.reasonRequired')
              : t('employees.form.reasonOptional')}
          </label>
          <select
            id={`${ids}-reason`}
            value={reason}
            aria-invalid={problems.has('reason') ? true : undefined}
            onChange={(event) => {
              setReason(event.target.value as EmploymentChangeReason | '');
              edited();
            }}
          >
            <option value="">{t('employees.placement.choose')}</option>
            {reasons.map((code) => (
              <option key={code} value={code}>
                {t(`employees.reasons.${code}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="actions">
          <button type="submit" className="button" disabled={busy !== null}>
            {busy === 'preview' ? t('employees.form.previewing') : t('employees.form.preview')}
          </button>
          {onClose && (
            <button type="button" className="button button--secondary" onClick={onClose}>
              {t('employees.form.close')}
            </button>
          )}
        </div>
      </form>

      {failure && <HistoryAlert failure={failure} ref={failureBox} data-testid="change-error" />}

      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="change-preview">
          <h3 id={`${ids}-preview`} tabIndex={-1} ref={previewHeading}>
            {t('employees.preview.title')}
          </h3>
          <p data-testid="preview-timing">{t(`employees.timing.${preview.timing}`)}</p>
          {preview.warnings.includes('LATER_CHANGE_LIMITS_PERIOD') && (
            <p className="notice">{t('employees.preview.laterChange')}</p>
          )}
          <PreviewTable
            kinds={preview.kinds}
            beforeLabel={t('employees.preview.before')}
            afterLabel={t('employees.preview.after')}
            data-testid="preview-table"
          />
          {preview.requiresAcknowledgement && (
            <div className="choice">
              <input
                id={`${ids}-ack`}
                type="checkbox"
                checked={acknowledged}
                aria-invalid={problems.has('acknowledge') ? true : undefined}
                onChange={(event) => {
                  setAcknowledged(event.target.checked);
                }}
              />
              <label htmlFor={`${ids}-ack`}>{t('employees.preview.acknowledge')}</label>
            </div>
          )}
          <div className="actions">
            <button
              type="button"
              className="button"
              disabled={busy !== null}
              onClick={() => void onCommit()}
            >
              {busy === 'commit' ? t('employees.form.saving') : t('employees.form.confirm')}
            </button>
          </div>
        </section>
      )}
    </section>
  );
}
