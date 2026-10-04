import type {
  ContractLocale,
  ContractPlaceholder,
  ContractTemplate,
  ContractTemplateValidation,
  ContractTemplateVersion,
  ContractTemplateVersionSummary,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { codePoints } from '../people/history';
import { ContractAlert } from './ContractAlert';
import { ContractDocument } from './ContractDocument';
import {
  CONTRACT_NETWORK_FAILURE,
  contractFailureOf,
  formatInstant,
  LOCALES,
  PLACEHOLDERS,
  sampleBlocks,
  type ContractFailure,
} from './contracts';

type Loaded =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: ContractFailure }
  | { kind: 'ready'; template: ContractTemplate };

type Editor = {
  /** The draft being edited, or null for a new draft. */
  version: ContractTemplateVersion | null;
  locale: ContractLocale;
  title: string;
  body: string;
};

/**
 * MVP-030: one contract template with its versions. Drafts are written in a plain-text editor
 * (grammar v1, with a field picker and a server-side check that reports closed reasons and line
 * numbers), then approved with an explicit confirmation that the organization verified the text.
 * Approved and retired versions never change.
 */
export function ContractTemplatePage() {
  const { templateId = '' } = useParams();
  const location = useLocation();
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const draftKey = useIdempotencyKey();
  const approveKey = useIdempotencyKey();
  const retireKey = useIdempotencyKey();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [revision, setRevision] = useState(0);
  const [announcement, setAnnouncement] = useState(() => {
    const state = location.state as { announce?: string } | null;
    return state?.announce ? t(state.announce) : '';
  });
  const [shown, setShown] = useState<ContractTemplateVersion | null>(null);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [placeholder, setPlaceholder] = useState<ContractPlaceholder>('employee.fullName');
  const [validation, setValidation] = useState<ContractTemplateValidation | null>(null);
  const [verified, setVerified] = useState(false);
  const [tick, setTick] = useState(false);
  const [failure, setFailure] = useState<ContractFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const failureBox = useRef<HTMLDivElement>(null);
  const bodyField = useRef<HTMLTextAreaElement>(null);
  const editorHeading = useRef<HTMLHeadingElement>(null);
  const focusNext = useRef<'heading' | 'failure' | 'editor' | null>(null);

  useEffect(() => {
    const target =
      focusNext.current === 'heading'
        ? heading.current
        : focusNext.current === 'editor'
          ? editorHeading.current
          : failureBox.current;
    if (focusNext.current && target) {
      focusNext.current = null;
      target.focus();
    }
  });

  const load = useCallback(async (): Promise<Loaded> => {
    try {
      const { data, error, response } = await core.GET('/contract-templates/{templateId}', {
        params: { path: { templateId } },
        cache: 'no-store',
      });
      return data
        ? { kind: 'ready', template: data }
        : { kind: 'failed', failure: contractFailureOf(response, error) };
    } catch {
      return { kind: 'failed', failure: CONTRACT_NETWORK_FAILURE };
    }
  }, [core, templateId]);

  useEffect(() => {
    let active = true;
    void load().then((next) => {
      if (active) setLoaded(next);
    });
    return () => {
      active = false;
    };
  }, [load, revision]);

  const fail = (next: ContractFailure) => {
    setFailure(next);
    focusNext.current = 'failure';
  };

  const done = (messageKey: string) => {
    setAnnouncement(t(messageKey));
    setFailure(null);
    setEditor(null);
    setShown(null);
    setValidation(null);
    setVerified(false);
    setTick(false);
    setRevision((value) => value + 1);
    focusNext.current = 'heading';
  };

  const readVersion = async (versionId: string): Promise<ContractTemplateVersion | null> => {
    try {
      const { data, error, response } = await core.GET(
        '/contract-templates/{templateId}/versions/{versionId}',
        { params: { path: { templateId, versionId } }, cache: 'no-store' },
      );
      if (data) return data;
      fail(contractFailureOf(response, error));
    } catch {
      fail(CONTRACT_NETWORK_FAILURE);
    }
    return null;
  };

  const toggle = async (summary: ContractTemplateVersionSummary) => {
    if (shown?.id === summary.id) {
      setShown(null);
      return;
    }
    const version = await readVersion(summary.id);
    if (version) {
      setShown(version);
      setVerified(false);
      setTick(false);
    }
  };

  const openEditor = async (summary: ContractTemplateVersionSummary | null) => {
    setValidation(null);
    setFailure(null);
    if (summary === null) {
      setEditor({
        version: null,
        locale: i18n.language === 'en' ? 'en' : 'fr',
        title: '',
        body: '',
      });
    } else {
      const version = await readVersion(summary.id);
      if (!version) return;
      setEditor({ version, locale: version.locale, title: version.title, body: version.body });
    }
    focusNext.current = 'editor';
  };

  const insert = () => {
    if (!editor) return;
    const token = `{{${placeholder}}}`;
    const field = bodyField.current;
    const start = field ? field.selectionStart : editor.body.length;
    const end = field ? field.selectionEnd : editor.body.length;
    const body = editor.body.slice(0, start) + token + editor.body.slice(end);
    setEditor({ ...editor, body });
    requestAnimationFrame(() => {
      field?.focus();
      field?.setSelectionRange(start + token.length, start + token.length);
    });
  };

  const check = async () => {
    if (!editor) return;
    setFailure(null);
    try {
      const { data, error, response } = await core.POST('/contract-templates/validate', {
        body: { title: editor.title, body: editor.body },
      });
      if (data) setValidation(data);
      else fail(contractFailureOf(response, error));
    } catch {
      fail(CONTRACT_NETWORK_FAILURE);
    }
  };

  const save = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editor) return;
    const titleLength = codePoints(editor.title);
    if (titleLength < 1 || titleLength > 160 || editor.body.trim() === '') {
      await check();
      return;
    }
    setBusy(true);
    setFailure(null);
    try {
      if (editor.version) {
        const { data, error, response } = await core.PUT(
          '/contract-templates/{templateId}/versions/{versionId}',
          {
            params: { path: { templateId, versionId: editor.version.id } },
            body: {
              title: editor.title,
              body: editor.body,
              expectedVersion: editor.version.version,
            },
          },
        );
        if (data) done('contracts.templates.announce.drafted');
        else fail(contractFailureOf(response, error));
      } else {
        const body = { locale: editor.locale, title: editor.title, body: editor.body };
        const { data, error, response } = await core.POST(
          '/contract-templates/{templateId}/versions',
          {
            params: {
              path: { templateId },
              header: { 'Idempotency-Key': draftKey.keyFor({ templateId, ...body }) },
            },
            body,
          },
        );
        if (data) {
          draftKey.reset();
          done('contracts.templates.announce.drafted');
        } else fail(contractFailureOf(response, error));
      }
    } catch {
      fail(CONTRACT_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (summary: ContractTemplateVersionSummary) => {
    setBusy(true);
    try {
      const { error, response } = await core.DELETE(
        '/contract-templates/{templateId}/versions/{versionId}',
        {
          params: {
            path: { templateId, versionId: summary.id },
            query: { expectedVersion: summary.version },
          },
        },
      );
      if (response.status === 204) done('contracts.templates.announce.deleted');
      else fail(contractFailureOf(response, error));
    } catch {
      fail(CONTRACT_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const approve = async (version: ContractTemplateVersion) => {
    if (!verified) {
      setTick(true);
      return;
    }
    setBusy(true);
    const body = { expectedVersion: version.version, acknowledgements: ['TEXT_VERIFIED' as const] };
    try {
      const { data, error, response } = await core.POST(
        '/contract-templates/{templateId}/versions/{versionId}/approve',
        {
          params: {
            path: { templateId, versionId: version.id },
            header: { 'Idempotency-Key': approveKey.keyFor({ id: version.id, ...body }) },
          },
          body,
        },
      );
      if (data) {
        approveKey.reset();
        done('contracts.templates.announce.approved');
      } else fail(contractFailureOf(response, error));
    } catch {
      fail(CONTRACT_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const retire = async (version: ContractTemplateVersion) => {
    setBusy(true);
    const body = { expectedVersion: version.version };
    try {
      const { data, error, response } = await core.POST(
        '/contract-templates/{templateId}/versions/{versionId}/retire',
        {
          params: {
            path: { templateId, versionId: version.id },
            header: { 'Idempotency-Key': retireKey.keyFor({ id: version.id, ...body }) },
          },
          body,
        },
      );
      if (data) {
        retireKey.reset();
        done('contracts.templates.announce.retired');
      } else fail(contractFailureOf(response, error));
    } catch {
      fail(CONTRACT_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  if (loaded.kind === 'loading') return <p role="status">{t('contracts.loading')}</p>;
  if (loaded.kind === 'failed') {
    return (
      <section aria-labelledby={`${ids}-title`}>
        <h1 id={`${ids}-title`}>{t('contracts.templates.title')}</h1>
        <ContractAlert failure={loaded.failure} data-testid="template-error" />
        <p>
          <Link to="/admin/contract-templates">{t('contracts.templates.back')}</Link>
        </p>
      </section>
    );
  }
  const { template } = loaded;
  const label = (key: string) =>
    (PLACEHOLDERS as readonly string[]).includes(key) ? t(`contracts.fields.${key}`) : key;
  const draftOpen = (locale: string) =>
    template.versions.some((v) => v.locale === locale && v.state === 'DRAFT');

  return (
    <section aria-labelledby={`${ids}-title`}>
      <p>
        <Link to="/admin/contract-templates">{t('contracts.templates.back')}</Link>
      </p>
      <h1 id={`${ids}-title`} tabIndex={-1} ref={heading}>
        {template.name}
      </h1>
      <p role="status" data-testid="announcer">
        {announcement}
      </p>
      <dl className="summary">
        <dt>{t('contracts.templates.columns.code')}</dt>
        <dd>{template.code}</dd>
        <dt>{t('contracts.templates.columns.type')}</dt>
        <dd>{t(`employees.contract.${template.contractType}`)}</dd>
      </dl>
      {failure && (
        <ContractAlert failure={failure} ref={failureBox} data-testid="template-failure" />
      )}

      <section aria-labelledby={`${ids}-versions`} className="card" data-testid="versions">
        <h2 id={`${ids}-versions`}>{t('contracts.versions.title')}</h2>
        {template.versions.length === 0 && <p>{t('contracts.versions.none')}</p>}
        <ul className="plain-list">
          {template.versions.map((summary) => {
            const language = t(`contracts.languages.${summary.locale}`);
            const open = shown?.id === summary.id ? shown : null;
            return (
              <li key={summary.id} className="hierarchy-item" data-testid="version">
                <h3>
                  {t('contracts.versions.heading', { language, number: summary.versionNumber })}{' '}
                  <span className="badge" data-state={summary.state}>
                    {t(`contracts.versions.state.${summary.state}`)}
                  </span>
                </h3>
                <p className="muted">
                  {t('contracts.versions.updated', {
                    at: formatInstant(i18n.language, summary.updatedAt),
                  })}
                </p>
                <div className="button-row">
                  <button
                    type="button"
                    className="button button--secondary"
                    aria-expanded={open !== null}
                    onClick={() => void toggle(summary)}
                  >
                    {open ? t('contracts.versions.hide') : t('contracts.versions.view')}
                  </button>
                  {summary.state === 'DRAFT' && (
                    <>
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={busy}
                        onClick={() => void openEditor(summary)}
                      >
                        {t('contracts.versions.edit')}
                      </button>
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={busy}
                        onClick={() => void remove(summary)}
                      >
                        {t('contracts.versions.delete')}
                      </button>
                    </>
                  )}
                </div>
                {open && (
                  <div data-testid="version-text">
                    <p className="muted">{t('contracts.versions.sample')}</p>
                    <ContractDocument
                      snapshot={{ title: open.title, blocks: sampleBlocks(open.body, label) }}
                      locale={open.locale}
                      level={3}
                    />
                    <p className="muted contract-document__digest">
                      {t('contracts.versions.bodySha256', { digest: open.bodySha256 })}
                    </p>
                    {open.state === 'DRAFT' && (
                      <div className="field" data-testid="approve">
                        <p className="field__help">{t('contracts.versions.approveHelp')}</p>
                        {tick && (
                          <p className="field__error" role="alert">
                            {t('contracts.versions.tickConfirm')}
                          </p>
                        )}
                        <div className="choice">
                          <input
                            id={`${ids}-verified`}
                            type="checkbox"
                            checked={verified}
                            aria-invalid={tick ? true : undefined}
                            onChange={(event) => {
                              setVerified(event.target.checked);
                              setTick(false);
                            }}
                          />
                          <label htmlFor={`${ids}-verified`}>
                            {t('contracts.versions.approveConfirm')}
                          </label>
                        </div>
                        <button
                          type="button"
                          className="button"
                          disabled={busy}
                          onClick={() => void approve(open)}
                        >
                          {t('contracts.versions.approve')}
                        </button>
                      </div>
                    )}
                    {open.state === 'APPROVED' && (
                      <div className="field">
                        <p className="field__help">{t('contracts.versions.retireHelp')}</p>
                        <button
                          type="button"
                          className="button button--secondary"
                          disabled={busy}
                          onClick={() => void retire(open)}
                        >
                          {t('contracts.versions.retire')}
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
        {!editor && LOCALES.some((locale) => !draftOpen(locale)) && (
          <button type="button" className="button" onClick={() => void openEditor(null)}>
            {t('contracts.versions.newDraft')}
          </button>
        )}
      </section>

      {editor && (
        <section aria-labelledby={`${ids}-editor`} className="card" data-testid="editor">
          <h2 id={`${ids}-editor`} tabIndex={-1} ref={editorHeading}>
            {editor.version ? t('contracts.versions.edit') : t('contracts.versions.newDraft')}
          </h2>
          <form noValidate onSubmit={(event) => void save(event)}>
            {!editor.version && (
              <div className="field">
                <label htmlFor={`${ids}-locale`}>{t('contracts.versions.language')}</label>
                <select
                  id={`${ids}-locale`}
                  value={editor.locale}
                  onChange={(event) => {
                    setEditor({ ...editor, locale: event.target.value as ContractLocale });
                  }}
                >
                  {LOCALES.filter((locale) => !draftOpen(locale)).map((locale) => (
                    <option key={locale} value={locale}>
                      {t(`contracts.languages.${locale}`)}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="field">
              <label htmlFor={`${ids}-doc-title`}>{t('contracts.versions.titleLabel')}</label>
              <input
                id={`${ids}-doc-title`}
                type="text"
                value={editor.title}
                maxLength={160}
                lang={editor.locale}
                onChange={(event) => {
                  setEditor({ ...editor, title: event.target.value });
                  setValidation(null);
                }}
              />
            </div>
            <div className="field">
              <label htmlFor={`${ids}-body`}>{t('contracts.versions.body')}</label>
              <p id={`${ids}-body-help`} className="field__help">
                {t('contracts.versions.bodyHelp')}
              </p>
              <textarea
                id={`${ids}-body`}
                ref={bodyField}
                value={editor.body}
                rows={16}
                lang={editor.locale}
                spellCheck
                aria-describedby={`${ids}-body-help`}
                aria-invalid={validation && !validation.valid ? true : undefined}
                onChange={(event) => {
                  setEditor({ ...editor, body: event.target.value });
                  setValidation(null);
                }}
              />
            </div>
            <div className="field">
              <label htmlFor={`${ids}-placeholder`}>{t('contracts.versions.placeholders')}</label>
              <div className="button-row">
                <select
                  id={`${ids}-placeholder`}
                  value={placeholder}
                  onChange={(event) => {
                    setPlaceholder(event.target.value as ContractPlaceholder);
                  }}
                >
                  {PLACEHOLDERS.map((key) => (
                    <option key={key} value={key}>
                      {t(`contracts.fields.${key}`)}
                    </option>
                  ))}
                </select>
                <button type="button" className="button button--secondary" onClick={insert}>
                  {t('contracts.versions.insert')}
                </button>
              </div>
            </div>
            {validation && (
              <div
                className={validation.valid ? 'notice' : 'error-summary'}
                role={validation.valid ? 'status' : 'alert'}
                data-testid="validation"
              >
                {validation.valid ? (
                  <p>{t('contracts.versions.valid')}</p>
                ) : (
                  <>
                    <p>{t('contracts.versions.problemsTitle')}</p>
                    <ul>
                      {validation.problems.map((problem, index) => (
                        <li key={index}>
                          {problem.line === 0
                            ? t('contracts.versions.problemTitle', {
                                reason: t(`contracts.problems.${problem.reason}`),
                              })
                            : t('contracts.versions.problemLine', {
                                line: problem.line,
                                reason: t(`contracts.problems.${problem.reason}`),
                              })}
                        </li>
                      ))}
                    </ul>
                  </>
                )}
              </div>
            )}
            <div className="button-row">
              <button
                type="button"
                className="button button--secondary"
                onClick={() => void check()}
              >
                {t('contracts.versions.check')}
              </button>
              <button type="submit" className="button" disabled={busy}>
                {t('contracts.versions.save')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                onClick={() => {
                  setEditor(null);
                  setValidation(null);
                }}
              >
                {t('contracts.versions.cancel')}
              </button>
            </div>
          </form>
        </section>
      )}
    </section>
  );
}
