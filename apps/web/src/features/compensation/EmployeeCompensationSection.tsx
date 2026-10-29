import type { CompensationInterval } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { CompensationCancelPanel } from './CompensationCancelPanel';
import { CompensationForm, type FormMode } from './CompensationForm';
import {
  CompensationAlert,
  HistoryBlock,
  IntervalItem,
  useCompensationRecord,
} from './CompensationBits';

type Panel =
  | { kind: 'form'; mode: FormMode; target: CompensationInterval | null }
  | { kind: 'cancel'; interval: CompensationInterval };

/**
 * MVP-093A: the « Rémunération » / "Compensation" section of the employee record
 * (administrator, exact MFA). The current monthly base compensation, the scheduled changes, the
 * immutable history with the administrator's explanations, and the establish / change / correct /
 * cancel actions, each previewed and then confirmed. Recording compensation does not calculate
 * payroll or initiate payment. Nothing is kept in browser storage.
 */
export function EmployeeCompensationSection({
  employeeId,
  revision,
  onChanged,
}: {
  employeeId: string;
  revision: number;
  onChanged: (message: string) => void;
}) {
  const { t } = useTranslation();
  const ids = useId();
  const [version, setVersion] = useState(0);
  const { load, reload, loadMore, moreBusy, moreFailure } = useCompensationRecord(
    employeeId,
    revision + version,
  );
  const [panel, setPanel] = useState<Panel | null>(null);
  const trigger = useRef<HTMLElement | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const focusHeading = useRef(false);

  useEffect(() => {
    if (focusHeading.current && headingRef.current) {
      focusHeading.current = false;
      headingRef.current.focus();
    }
  });

  const open = (next: Panel, from: HTMLElement) => {
    trigger.current = from;
    setPanel(next);
  };

  /** Closing returns focus to the control that opened the panel when it is still there. */
  const close = () => {
    setPanel(null);
    const element = trigger.current;
    trigger.current = null;
    if (element?.isConnected) element.focus();
    else focusHeading.current = true;
  };

  const done = (messageKey: string) => {
    setPanel(null);
    trigger.current = null;
    focusHeading.current = true;
    setVersion((value) => value + 1);
    onChanged(t(messageKey));
  };

  const record = load.kind === 'ready' ? load.record : null;
  const none = record !== null && record.version === 0;

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="compensation-section">
      <h2 id={`${ids}-title`} tabIndex={-1} ref={headingRef}>
        {t('compensation.title')}
      </h2>
      <p className="muted" data-testid="compensation-privacy">
        {t('compensation.privacy')}
      </p>
      <p className="muted" data-testid="compensation-notice">
        {t('compensation.notice')}
      </p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="compensation-loading">
          {t('compensation.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <>
          <CompensationAlert failure={load.failure} testId="compensation-load-error" />
          <div className="dialog-actions">
            <button
              type="button"
              className="button button--secondary"
              onClick={() => void reload()}
            >
              {t('employees.retry')}
            </button>
          </div>
        </>
      )}
      {record && (
        <>
          <h3 id={`${ids}-current`}>{t('compensation.current.title')}</h3>
          {record.current ? (
            <ul className="plain-list" aria-labelledby={`${ids}-current`}>
              <IntervalItem
                interval={record.current}
                testId="current-interval"
                actions={
                  <button
                    type="button"
                    className="button button--secondary"
                    data-testid="compensation-correct"
                    onClick={(event) => {
                      open(
                        { kind: 'form', mode: 'CORRECTED', target: record.current },
                        event.currentTarget,
                      );
                    }}
                  >
                    {t('compensation.actions.correct')}
                  </button>
                }
              />
            </ul>
          ) : (
            <p className="muted" data-testid="compensation-none">
              {none ? t('compensation.current.empty') : t('compensation.current.none')}
            </p>
          )}

          {record.scheduled.length > 0 && (
            <>
              <h3 id={`${ids}-scheduled`}>{t('compensation.scheduled.title')}</h3>
              <ul className="plain-list" aria-labelledby={`${ids}-scheduled`}>
                {record.scheduled.map((interval) => (
                  <IntervalItem
                    key={interval.originChangeId}
                    interval={interval}
                    testId="scheduled-interval"
                    actions={
                      <span className="dialog-actions">
                        <button
                          type="button"
                          className="button button--secondary"
                          data-testid="compensation-correct"
                          onClick={(event) => {
                            open(
                              { kind: 'form', mode: 'CORRECTED', target: interval },
                              event.currentTarget,
                            );
                          }}
                        >
                          {t('compensation.actions.correct')}
                        </button>
                        {interval.cancellable && (
                          <button
                            type="button"
                            className="button button--secondary"
                            data-testid="compensation-cancel"
                            onClick={(event) => {
                              open({ kind: 'cancel', interval }, event.currentTarget);
                            }}
                          >
                            {t('compensation.actions.cancel')}
                          </button>
                        )}
                      </span>
                    }
                  />
                ))}
              </ul>
            </>
          )}

          {panel === null && (
            <div className="dialog-actions">
              <button
                type="button"
                className="button"
                data-testid={none ? 'compensation-open-establish' : 'compensation-open-change'}
                onClick={(event) => {
                  open(
                    { kind: 'form', mode: none ? 'ESTABLISHED' : 'CHANGED', target: null },
                    event.currentTarget,
                  );
                }}
              >
                {none ? t('compensation.actions.establish') : t('compensation.actions.change')}
              </button>
            </div>
          )}

          {panel?.kind === 'form' && (
            <CompensationForm
              key={`${panel.mode}-${panel.target?.originChangeId ?? 'new'}`}
              employeeId={employeeId}
              mode={panel.mode}
              target={panel.target}
              businessDate={record.businessDate}
              onDone={(receipt) => {
                done(`compensation.announce.${receipt.action}`);
              }}
              onClose={close}
              onStale={() => void reload()}
            />
          )}
          {panel?.kind === 'cancel' && (
            <CompensationCancelPanel
              key={panel.interval.originChangeId}
              employeeId={employeeId}
              interval={panel.interval}
              onDone={(receipt) => {
                done(`compensation.announce.${receipt.action}`);
              }}
              onClose={close}
              onStale={() => void reload()}
            />
          )}

          <HistoryBlock
            record={record}
            showExplanation
            moreBusy={moreBusy}
            moreFailure={moreFailure}
            onMore={() => void loadMore()}
            headingId={`${ids}-history`}
            level={3}
          />
        </>
      )}
    </section>
  );
}
