import type {
  CostCenter,
  Department,
  LegalEntity,
  Problem,
  Region,
  Site,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { COUNTRIES, TIMEZONES_BY_COUNTRY } from '../admin/organizationForm';
import { Field } from './HierarchyFields';
import {
  ASSIGNMENT_FIELDS,
  LEGAL_ENTITY_FIELDS,
  REGION_FIELDS,
  SITE_FIELDS,
  SITE_UNIT_FIELDS,
  fieldErrorsFromProblem,
  toAssignmentPayload,
  toLegalEntityPayload,
  toRegionPayload,
  toSitePayload,
  toSiteUnitPayload,
  validateAssignment,
  validateLegalEntity,
  validateRegion,
  validateSite,
  validateSiteUnit,
  type AssignmentField,
  type Errors,
  type LegalEntityField,
  type LegalEntityValues,
  type RegionField,
  type RegionValues,
  type SiteField,
  type SiteUnitField,
  type SiteUnitKind,
  type SiteUnitValues,
  type SiteValues,
} from './hierarchyForm';
import type { RegionOptionsStatus } from './regionOptions';
import { useIdempotencyKey } from './useIdempotencyKey';

type Phase = { kind: 'editing' } | { kind: 'submitting' } | { kind: 'failed'; messageKey: string };

function failureKey(status: number, problem: Partial<Problem> | undefined): string {
  if (status === 403) return 'hierarchy.unauthorized';
  return problem?.code ? `errors.${problem.code}` : 'errors.generic';
}

/**
 * Shared submit flow: client validation, one idempotency key per payload, server field errors
 * mapped back to fields, and focus moved to the summary (which is not a live region, so errors
 * are announced once, through focus).
 */
function useCreateForm<F extends string>(fields: readonly F[]) {
  const [errors, setErrors] = useState<Errors<F>>({});
  const [phase, setPhase] = useState<Phase>({ kind: 'editing' });
  const summaryRef = useRef<HTMLDivElement>(null);
  const keys = useIdempotencyKey();

  const focusSummary = () => requestAnimationFrame(() => summaryRef.current?.focus());

  const submit = async <T,>(
    clientErrors: Errors<F>,
    payload: unknown,
    send: (key: string) => Promise<{ data?: T; error?: unknown; response: Response }>,
    onCreated: (created: T) => void,
  ) => {
    if (phase.kind === 'submitting') return;
    if (Object.keys(clientErrors).length > 0) {
      setErrors(clientErrors);
      focusSummary();
      return;
    }
    setErrors({});
    setPhase({ kind: 'submitting' });
    try {
      const { data, error, response } = await send(keys.keyFor(payload));
      if (data) {
        keys.reset();
        setPhase({ kind: 'editing' });
        onCreated(data);
        return;
      }
      const problem = error as Partial<Problem> | undefined;
      const mapped = problem ? fieldErrorsFromProblem(problem, fields) : null;
      if (mapped) {
        setPhase({ kind: 'editing' });
        setErrors(mapped);
      } else {
        setPhase({ kind: 'failed', messageKey: failureKey(response.status, problem) });
      }
      focusSummary();
    } catch {
      setPhase({ kind: 'failed', messageKey: 'errors.network' });
      focusSummary();
    }
  };

  return { errors, setErrors, phase, setPhase, summaryRef, submit };
}

function Summary<F extends string>({
  summaryRef,
  fields,
  errors,
  phase,
  fieldId,
  message,
}: {
  summaryRef: React.RefObject<HTMLDivElement | null>;
  fields: readonly F[];
  errors: Errors<F>;
  phase: Phase;
  fieldId: (field: F) => string;
  message: (field: F) => string | null;
}) {
  const { t } = useTranslation();
  const errorFields = fields.filter((field) => errors[field]);
  return (
    <div ref={summaryRef} tabIndex={-1} className="form-summary" data-testid="form-summary">
      {errorFields.length > 0 && (
        <div className="error-summary">
          <p>{t('hierarchy.errorSummary')}</p>
          <ul>
            {errorFields.map((field) => (
              <li key={field}>
                <a href={`#${fieldId(field)}`}>{message(field)}</a>
              </li>
            ))}
          </ul>
        </div>
      )}
      {phase.kind === 'failed' && (
        <p className="error-summary" data-testid="form-error">
          {t(phase.messageKey)}
        </p>
      )}
    </div>
  );
}

function useMessage<F extends string>(errors: Errors<F>) {
  const { t } = useTranslation();
  return (field: F) =>
    errors[field]
      ? t([`hierarchy.validation.${field}.${errors[field]}`, 'hierarchy.validation.generic'])
      : null;
}

const emptyLegalEntity = (): LegalEntityValues => ({
  code: '',
  name: '',
  countryCode: 'CD',
  effectiveFrom: '',
  effectiveTo: '',
});

export function LegalEntityForm({ onCreated }: { onCreated: (entity: LegalEntity) => void }) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [values, setValues] = useState<LegalEntityValues>(emptyLegalEntity);
  const form = useCreateForm<LegalEntityField>(LEGAL_ENTITY_FIELDS);
  const message = useMessage(form.errors);
  const fieldId = (field: string) => `${ids}-le-${field}`;
  const update = (patch: Partial<LegalEntityValues>) => {
    setValues((current) => ({ ...current, ...patch }));
    if (form.phase.kind === 'failed') form.setPhase({ kind: 'editing' });
  };

  const onSubmit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const payload = toLegalEntityPayload(values);
    void form.submit(
      validateLegalEntity(values),
      payload,
      (key) =>
        core.POST('/legal-entities', {
          params: { header: { 'Idempotency-Key': key } },
          body: payload,
        }),
      (created) => {
        setValues(emptyLegalEntity());
        onCreated(created);
      },
    );
  };
  const submitting = form.phase.kind === 'submitting';

  return (
    <form
      noValidate
      aria-labelledby={`${ids}-le-title`}
      aria-busy={submitting}
      onSubmit={onSubmit}
      data-testid="legal-entity-form"
    >
      <h3 id={`${ids}-le-title`}>{t('hierarchy.legalEntityForm.title')}</h3>
      <Summary
        summaryRef={form.summaryRef}
        fields={LEGAL_ENTITY_FIELDS}
        errors={form.errors}
        phase={form.phase}
        fieldId={fieldId}
        message={message}
      />
      <Field
        id={fieldId('code')}
        label={t('hierarchy.fields.code.label')}
        help={t('hierarchy.fields.code.help')}
        error={form.errors.code}
        errorMessage={message('code')}
      >
        {(describedBy, invalid) => (
          <input
            id={fieldId('code')}
            type="text"
            autoComplete="off"
            maxLength={40}
            value={values.code}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              update({ code: event.target.value });
            }}
          />
        )}
      </Field>
      <Field
        id={fieldId('name')}
        label={t('hierarchy.fields.legalName.label')}
        error={form.errors.name}
        errorMessage={message('name')}
      >
        {(describedBy, invalid) => (
          <input
            id={fieldId('name')}
            type="text"
            autoComplete="organization"
            maxLength={200}
            value={values.name}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              update({ name: event.target.value });
            }}
          />
        )}
      </Field>
      <Field
        id={fieldId('countryCode')}
        label={t('hierarchy.fields.country.label')}
        error={form.errors.countryCode}
        errorMessage={message('countryCode')}
      >
        {(describedBy, invalid) => (
          <select
            id={fieldId('countryCode')}
            value={values.countryCode}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              update({ countryCode: event.target.value });
            }}
          >
            {COUNTRIES.map((code) => (
              <option key={code} value={code}>
                {t(`country.${code}`)}
              </option>
            ))}
          </select>
        )}
      </Field>
      <DateFields
        idFor={fieldId}
        values={values}
        errors={form.errors}
        message={message}
        onChange={update}
      />
      <button type="submit" className="button" disabled={submitting}>
        {submitting ? t('hierarchy.submitting') : t('hierarchy.legalEntityForm.submit')}
      </button>
    </form>
  );
}

function DateFields<F extends 'effectiveFrom' | 'effectiveTo'>({
  idFor,
  values,
  errors,
  message,
  onChange,
}: {
  idFor: (field: string) => string;
  values: { effectiveFrom: string; effectiveTo: string };
  errors: Errors<F>;
  message: (field: F) => string | null;
  onChange: (patch: { effectiveFrom?: string; effectiveTo?: string }) => void;
}) {
  const { t } = useTranslation();
  const from = 'effectiveFrom' as F;
  const to = 'effectiveTo' as F;
  return (
    <>
      <Field
        id={idFor('effectiveFrom')}
        label={t('hierarchy.fields.effectiveFrom.label')}
        help={t('hierarchy.fields.effectiveFrom.help')}
        error={errors[from]}
        errorMessage={message(from)}
      >
        {(describedBy, invalid) => (
          <input
            id={idFor('effectiveFrom')}
            type="date"
            min="1900-01-01"
            max="2999-12-31"
            value={values.effectiveFrom}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              onChange({ effectiveFrom: event.target.value });
            }}
          />
        )}
      </Field>
      <Field
        id={idFor('effectiveTo')}
        label={t('hierarchy.fields.effectiveTo.label')}
        help={t('hierarchy.fields.effectiveTo.help')}
        error={errors[to]}
        errorMessage={message(to)}
      >
        {(describedBy, invalid) => (
          <input
            id={idFor('effectiveTo')}
            type="date"
            min="1900-01-01"
            max="2999-12-31"
            value={values.effectiveTo}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              onChange({ effectiveTo: event.target.value });
            }}
          />
        )}
      </Field>
    </>
  );
}

export function SiteForm({
  parent,
  regions,
  regionsStatus,
  onCreated,
}: {
  parent: LegalEntity;
  /** Regions of the legal entity offered for the optional region select. */
  regions: readonly Region[];
  /** The select is shown only when the regions are loaded; never empty as if there were none. */
  regionsStatus: RegionOptionsStatus;
  onCreated: (site: Site) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const zones =
    (TIMEZONES_BY_COUNTRY as Record<string, readonly string[] | undefined>)[parent.countryCode] ??
    [];
  const empty = (): SiteValues => ({
    code: '',
    name: '',
    timezone: zones[0] ?? '',
    regionId: '',
    effectiveFrom: parent.effectiveFrom,
    effectiveTo: parent.effectiveTo ?? '',
  });
  const [values, setValues] = useState<SiteValues>(empty);
  const form = useCreateForm<SiteField>(SITE_FIELDS);
  const message = useMessage(form.errors);
  const fieldId = (field: string) => `${ids}-site-${field}`;
  const update = (patch: Partial<SiteValues>) => {
    setValues((current) => ({ ...current, ...patch }));
    if (form.phase.kind === 'failed') form.setPhase({ kind: 'editing' });
  };

  const onSubmit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const payload = toSitePayload(
      parent.id,
      regionsStatus === 'ready' ? values : { ...values, regionId: '' },
    );
    void form.submit(
      validateSite(values, parent.countryCode),
      payload,
      (key) =>
        core.POST('/sites', {
          params: { header: { 'Idempotency-Key': key } },
          body: payload,
        }),
      (created) => {
        setValues(empty());
        onCreated(created);
      },
    );
  };
  const submitting = form.phase.kind === 'submitting';

  return (
    <form
      noValidate
      aria-labelledby={`${ids}-site-title`}
      aria-busy={submitting}
      onSubmit={onSubmit}
      data-testid="site-form"
    >
      <h3 id={`${ids}-site-title`}>
        {t('hierarchy.siteForm.title', { name: parent.name, code: parent.code })}
      </h3>
      <p className="muted">{t('hierarchy.siteForm.periodHint')}</p>
      <Summary
        summaryRef={form.summaryRef}
        fields={SITE_FIELDS}
        errors={form.errors}
        phase={form.phase}
        fieldId={fieldId}
        message={message}
      />
      <Field
        id={fieldId('code')}
        label={t('hierarchy.fields.code.label')}
        help={t('hierarchy.fields.code.help')}
        error={form.errors.code}
        errorMessage={message('code')}
      >
        {(describedBy, invalid) => (
          <input
            id={fieldId('code')}
            type="text"
            autoComplete="off"
            maxLength={40}
            value={values.code}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              update({ code: event.target.value });
            }}
          />
        )}
      </Field>
      <Field
        id={fieldId('name')}
        label={t('hierarchy.fields.siteName.label')}
        error={form.errors.name}
        errorMessage={message('name')}
      >
        {(describedBy, invalid) => (
          <input
            id={fieldId('name')}
            type="text"
            autoComplete="off"
            maxLength={200}
            value={values.name}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              update({ name: event.target.value });
            }}
          />
        )}
      </Field>
      <Field
        id={fieldId('timezone')}
        label={t('hierarchy.fields.timezone.label')}
        error={form.errors.timezone}
        errorMessage={message('timezone')}
      >
        {(describedBy, invalid) => (
          <select
            id={fieldId('timezone')}
            value={values.timezone}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              update({ timezone: event.target.value });
            }}
          >
            {zones.map((zone) => (
              <option key={zone} value={zone}>
                {t(`timezones.${zone}`)}
              </option>
            ))}
          </select>
        )}
      </Field>
      {regionsStatus === 'ready' ? (
        <Field
          id={fieldId('regionId')}
          label={t('hierarchy.fields.region.label')}
          help={t('hierarchy.fields.region.help')}
          error={form.errors.regionId}
          errorMessage={message('regionId')}
        >
          {(describedBy, invalid) => (
            <select
              id={fieldId('regionId')}
              value={values.regionId}
              aria-invalid={invalid}
              aria-describedby={describedBy}
              onChange={(event) => {
                update({ regionId: event.target.value });
              }}
            >
              <option value="">{t('hierarchy.fields.region.none')}</option>
              {regions.map((region) => (
                <option key={region.id} value={region.id}>
                  {t('hierarchy.regions.option', { name: region.name, code: region.code })}
                </option>
              ))}
            </select>
          )}
        </Field>
      ) : (
        <div className="field">
          <p className="muted" data-testid="region-unavailable">
            {regionsStatus === 'loading'
              ? t('hierarchy.regionOptions.loading')
              : t('hierarchy.regionOptions.unavailable')}
          </p>
        </div>
      )}
      <DateFields
        idFor={fieldId}
        values={values}
        errors={form.errors}
        message={message}
        onChange={update}
      />
      <button type="submit" className="button" disabled={submitting}>
        {submitting ? t('hierarchy.submitting') : t('hierarchy.siteForm.submit')}
      </button>
    </form>
  );
}

/**
 * Creates a department or cost center beneath the selected site. Both share one form shape; the
 * kind selects the endpoint and the translated labels.
 */
export function SiteUnitForm({
  kind,
  site,
  onCreated,
}: {
  kind: SiteUnitKind;
  site: Site;
  onCreated: (unit: Department | CostCenter) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const empty = (): SiteUnitValues => ({
    code: '',
    name: '',
    effectiveFrom: site.effectiveFrom,
    effectiveTo: site.effectiveTo ?? '',
  });
  const [values, setValues] = useState<SiteUnitValues>(empty);
  const form = useCreateForm<SiteUnitField>(SITE_UNIT_FIELDS);
  const message = useMessage(form.errors);
  const fieldId = (field: string) => `${ids}-${kind}-${field}`;
  const update = (patch: Partial<SiteUnitValues>) => {
    setValues((current) => ({ ...current, ...patch }));
    if (form.phase.kind === 'failed') form.setPhase({ kind: 'editing' });
  };

  const onSubmit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const payload = toSiteUnitPayload(site.id, values);
    void form.submit(
      validateSiteUnit(values),
      payload,
      (key) =>
        kind === 'department'
          ? core.POST('/departments', {
              params: { header: { 'Idempotency-Key': key } },
              body: payload,
            })
          : core.POST('/cost-centers', {
              params: { header: { 'Idempotency-Key': key } },
              body: payload,
            }),
      (created) => {
        setValues(empty());
        onCreated(created);
      },
    );
  };
  const submitting = form.phase.kind === 'submitting';

  return (
    <form
      noValidate
      aria-labelledby={`${ids}-${kind}-title`}
      aria-busy={submitting}
      onSubmit={onSubmit}
      data-testid={`${kind}-form`}
    >
      <h4 id={`${ids}-${kind}-title`}>
        {t(`hierarchy.${kind}Form.title`, { name: site.name, code: site.code })}
      </h4>
      <p className="muted">{t('hierarchy.siteUnits.periodHint')}</p>
      <Summary
        summaryRef={form.summaryRef}
        fields={SITE_UNIT_FIELDS}
        errors={form.errors}
        phase={form.phase}
        fieldId={fieldId}
        message={message}
      />
      <Field
        id={fieldId('code')}
        label={t('hierarchy.fields.code.label')}
        help={t('hierarchy.fields.code.help')}
        error={form.errors.code}
        errorMessage={message('code')}
      >
        {(describedBy, invalid) => (
          <input
            id={fieldId('code')}
            type="text"
            autoComplete="off"
            maxLength={40}
            value={values.code}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              update({ code: event.target.value });
            }}
          />
        )}
      </Field>
      <Field
        id={fieldId('name')}
        label={t(`hierarchy.fields.${kind}Name.label`)}
        error={form.errors.name}
        errorMessage={message('name')}
      >
        {(describedBy, invalid) => (
          <input
            id={fieldId('name')}
            type="text"
            autoComplete="off"
            maxLength={200}
            value={values.name}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              update({ name: event.target.value });
            }}
          />
        )}
      </Field>
      <DateFields
        idFor={fieldId}
        values={values}
        errors={form.errors}
        message={message}
        onChange={update}
      />
      <button type="submit" className="button" disabled={submitting}>
        {submitting ? t('hierarchy.submitting') : t(`hierarchy.${kind}Form.submit`)}
      </button>
    </form>
  );
}

/**
 * Creates a region beneath the selected legal entity (MVP-002 Increment 3A). Same shape as the
 * department and cost-center form.
 */
export function RegionForm({
  parent,
  onCreated,
}: {
  parent: LegalEntity;
  onCreated: (region: Region) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const empty = (): RegionValues => ({
    code: '',
    name: '',
    effectiveFrom: parent.effectiveFrom,
    effectiveTo: parent.effectiveTo ?? '',
  });
  const [values, setValues] = useState<RegionValues>(empty);
  const form = useCreateForm<RegionField>(REGION_FIELDS);
  const message = useMessage(form.errors);
  const fieldId = (field: string) => `${ids}-region-${field}`;
  const update = (patch: Partial<RegionValues>) => {
    setValues((current) => ({ ...current, ...patch }));
    if (form.phase.kind === 'failed') form.setPhase({ kind: 'editing' });
  };

  const onSubmit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const payload = toRegionPayload(parent.id, values);
    void form.submit(
      validateRegion(values),
      payload,
      (key) =>
        core.POST('/regions', {
          params: { header: { 'Idempotency-Key': key } },
          body: payload,
        }),
      (created) => {
        setValues(empty());
        onCreated(created);
      },
    );
  };
  const submitting = form.phase.kind === 'submitting';

  return (
    <form
      noValidate
      aria-labelledby={`${ids}-region-title`}
      aria-busy={submitting}
      onSubmit={onSubmit}
      data-testid="region-form"
    >
      <h3 id={`${ids}-region-title`}>
        {t('hierarchy.regionForm.title', { name: parent.name, code: parent.code })}
      </h3>
      <p className="muted">{t('hierarchy.regionForm.periodHint')}</p>
      <Summary
        summaryRef={form.summaryRef}
        fields={REGION_FIELDS}
        errors={form.errors}
        phase={form.phase}
        fieldId={fieldId}
        message={message}
      />
      <Field
        id={fieldId('code')}
        label={t('hierarchy.fields.code.label')}
        help={t('hierarchy.fields.code.help')}
        error={form.errors.code}
        errorMessage={message('code')}
      >
        {(describedBy, invalid) => (
          <input
            id={fieldId('code')}
            type="text"
            autoComplete="off"
            maxLength={40}
            value={values.code}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              update({ code: event.target.value });
            }}
          />
        )}
      </Field>
      <Field
        id={fieldId('name')}
        label={t('hierarchy.fields.regionName.label')}
        error={form.errors.name}
        errorMessage={message('name')}
      >
        {(describedBy, invalid) => (
          <input
            id={fieldId('name')}
            type="text"
            autoComplete="off"
            maxLength={200}
            value={values.name}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              update({ name: event.target.value });
            }}
          />
        )}
      </Field>
      <DateFields
        idFor={fieldId}
        values={values}
        errors={form.errors}
        message={message}
        onChange={update}
      />
      <button type="submit" className="button" disabled={submitting}>
        {submitting ? t('hierarchy.submitting') : t('hierarchy.regionForm.submit')}
      </button>
    </form>
  );
}

/**
 * Inline (non-modal, so never a focus trap) first-region assignment for one unassigned site. The
 * idempotency key is bound to the site and the chosen region; the select receives focus on open.
 */
export function AssignRegionForm({
  id,
  site,
  regions,
  onAssigned,
  onCancel,
  onReload,
}: {
  id: string;
  site: Site;
  regions: readonly Region[];
  onAssigned: (site: Site) => void;
  onCancel: () => void;
  onReload: () => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const [regionId, setRegionId] = useState('');
  const selectRef = useRef<HTMLSelectElement>(null);
  const form = useCreateForm<AssignmentField>(ASSIGNMENT_FIELDS);
  const message = useMessage(form.errors);
  const fieldId = (field: string) => `${id}-${field}`;

  useEffect(() => {
    requestAnimationFrame(() => selectRef.current?.focus());
  }, []);

  const onSubmit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const payload = toAssignmentPayload(regionId);
    void form.submit(
      validateAssignment(regionId),
      { siteId: site.id, ...payload },
      (key) =>
        core.PUT('/sites/{siteId}/region', {
          params: { path: { siteId: site.id }, header: { 'Idempotency-Key': key } },
          body: payload,
        }),
      onAssigned,
    );
  };
  const submitting = form.phase.kind === 'submitting';
  const alreadyAssigned =
    form.phase.kind === 'failed' && form.phase.messageKey === 'errors.SITE_REGION_ALREADY_ASSIGNED';

  return (
    <form
      id={id}
      noValidate
      aria-labelledby={`${id}-title`}
      aria-busy={submitting}
      onSubmit={onSubmit}
      className="hierarchy-assign"
      data-testid="assign-region-form"
    >
      <h3 id={`${id}-title`}>
        {t('hierarchy.assignment.title', { name: site.name, code: site.code })}
      </h3>
      <p className="muted">{t('hierarchy.assignment.hint')}</p>
      <Summary
        summaryRef={form.summaryRef}
        fields={ASSIGNMENT_FIELDS}
        errors={form.errors}
        phase={form.phase}
        fieldId={fieldId}
        message={message}
      />
      {alreadyAssigned && (
        <button type="button" className="button button--secondary" onClick={onReload}>
          {t('hierarchy.assignment.reload')}
        </button>
      )}
      <Field
        id={fieldId('regionId')}
        label={t('hierarchy.fields.assignRegion.label')}
        error={form.errors.regionId}
        errorMessage={message('regionId')}
      >
        {(describedBy, invalid) => (
          <select
            ref={selectRef}
            id={fieldId('regionId')}
            value={regionId}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            onChange={(event) => {
              setRegionId(event.target.value);
              if (form.phase.kind === 'failed') form.setPhase({ kind: 'editing' });
            }}
          >
            <option value="">{t('hierarchy.assignment.choose')}</option>
            {regions.map((region) => (
              <option key={region.id} value={region.id}>
                {t('hierarchy.regions.option', { name: region.name, code: region.code })}
              </option>
            ))}
          </select>
        )}
      </Field>
      <div className="hierarchy-assign__actions">
        <button type="submit" className="button" disabled={submitting}>
          {submitting ? t('hierarchy.submitting') : t('hierarchy.assignment.submit')}
        </button>
        <button type="button" className="button button--secondary" onClick={onCancel}>
          {t('hierarchy.assignment.cancel')}
        </button>
      </div>
    </form>
  );
}
