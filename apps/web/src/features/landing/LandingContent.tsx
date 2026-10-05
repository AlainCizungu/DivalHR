import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../auth/AuthProvider';
import { BrandMark, Wordmark } from '../../shell/controls';
import { Icon } from '../../ui/Icon';
import { StatusBadge } from '../../ui/primitives';
import {
  AI_EXAMPLES,
  AI_GUARANTEES,
  AVAILABLE_MODULES,
  CONTACT_EMAIL,
  demoMailto,
  FINANCE_STEPS,
  INTEGRATION_GROUPS,
  OUTCOMES,
  PAYROLL_STEPS,
  PLATFORM_STEPS,
  PREVIEW_ACTIONS,
  PREVIEW_CARDS,
  PREVIEW_NAV,
  PRINCIPLES,
  RAILS,
  SECTORS,
  VISION_MODULES,
  type Availability,
  type IntegrationEntry,
  type StepDef,
} from './catalogue';

/**
 * UI-002 (Issue #63): the content of the public landing page. Static copy only: no figures,
 * people, customers or metrics (UI2-4). Planned capabilities are labelled "Coming later"
 * (UI2-1); integrations and payout rails are planned (UI2-2, UI2-5).
 */

function AvailabilityBadge({ status }: { status: Availability }) {
  const { t } = useTranslation();
  return status === 'available' ? (
    <StatusBadge tone="success" icon="check">
      {t('landing.modules.available')}
    </StatusBadge>
  ) : (
    <StatusBadge tone="neutral" icon="clock">
      {t('landing.modules.later')}
    </StatusBadge>
  );
}

function Kicker({ children }: { children: string }) {
  return <p className="landing-kicker">{children}</p>;
}

/** Numbered steps; the numbers come from a CSS counter so the text holds no figures. */
function Steps({ prefix, steps }: { prefix: string; steps: readonly StepDef[] }) {
  const { t } = useTranslation();
  return (
    <ol className="landing-steps">
      {steps.map((step) => (
        <li key={step.key}>
          <span className="landing-tile-icon">
            <Icon name={step.icon} />
          </span>
          <h3>{t(`${prefix}.${step.key}.title`)}</h3>
          <p>{t(`${prefix}.${step.key}.description`)}</p>
        </li>
      ))}
    </ol>
  );
}

function ProductPreview() {
  const { t } = useTranslation();
  const captionId = useId();
  return (
    <figure className="landing-preview" aria-labelledby={captionId} data-testid="landing-preview">
      {/* Decorative: the caption is the only text announced for the illustration (UI2-4). */}
      <div className="landing-preview__frame" aria-hidden="true">
        <div className="landing-preview__side">
          <span className="landing-brand">
            <BrandMark />
            <span className="landing-brand__name">
              <Wordmark />
            </span>
          </span>
          {PREVIEW_NAV.map(({ key, icon }, index) => (
            <span
              key={key}
              className={`landing-preview__link${index === 0 ? ' landing-preview__link--on' : ''}`}
            >
              <Icon name={icon} />
              {t(`landing.preview.nav.${key}`)}
            </span>
          ))}
        </div>
        <div className="landing-preview__main">
          <span className="landing-preview__eyebrow">{t('landing.preview.eyebrow')}</span>
          <span className="landing-preview__title">{t('landing.preview.title')}</span>
          <span className="landing-preview__actions">
            {PREVIEW_ACTIONS.map((key, index) => (
              <span
                key={key}
                className={`landing-preview__chip${index === 0 ? ' landing-preview__chip--primary' : ''}`}
              >
                {t(`landing.preview.actions.${key}`)}
              </span>
            ))}
          </span>
          <span className="landing-preview__cards">
            {PREVIEW_CARDS.map(({ key, icon }) => (
              <span key={key} className="landing-preview__card">
                <span className="landing-tile-icon">
                  <Icon name={icon} />
                </span>
                <b>{t(`landing.preview.cards.${key}.title`)}</b>
                <span>{t(`landing.preview.cards.${key}.description`)}</span>
              </span>
            ))}
          </span>
        </div>
      </div>
      <figcaption id={captionId}>{t('landing.preview.caption')}</figcaption>
    </figure>
  );
}

function IntegrationItem({ entry }: { entry: IntegrationEntry }) {
  const { t } = useTranslation();
  if (entry.kind === 'generic') {
    return (
      <li className="landing-integration landing-integration--generic" data-kind="generic">
        <Icon name={entry.icon} />
        <span>{t(`landing.integrations.generic.${entry.labelKey}`)}</span>
      </li>
    );
  }
  return (
    <li
      className="landing-integration"
      data-kind={entry.logo ? 'logo' : 'name'}
      data-testid="landing-integration-brand"
    >
      {entry.logo && (
        // The visible name carries the meaning; the mark is not announced again (UI2-2).
        <img
          className="landing-integration__logo"
          src={`${import.meta.env.BASE_URL}brands/${entry.logo}`}
          alt=""
          width={20}
          height={20}
          loading="lazy"
          decoding="async"
        />
      )}
      <span lang="en">{entry.name}</span>
    </li>
  );
}

export default function LandingContent() {
  const { t } = useTranslation();
  const { signIn } = useAuth();
  const mailto = demoMailto(t('landing.demoSubject'));
  return (
    <>
      <section className="landing-hero" aria-labelledby="landing-hero-title">
        <div className="landing-container landing-hero__grid">
          <div>
            <p className="landing-eyebrow">{t('landing.hero.eyebrow')}</p>
            <h1 id="landing-hero-title">
              <span>{t('landing.hero.line1')}</span>{' '}
              <span className="landing-hero__accent">{t('landing.hero.line2')}</span>
            </h1>
            <p className="landing-hero__intro">{t('landing.hero.intro')}</p>
            <div className="landing-hero__actions">
              <a className="button" href={mailto}>
                {t('landing.requestDemo')}
                <Icon name="arrowUpRight" />
              </a>
              <button
                type="button"
                className="button landing-button--on-navy"
                onClick={() => {
                  void signIn('/');
                }}
              >
                <Icon name="signIn" />
                {t('landing.hero.signIn')}
              </button>
            </div>
            <p className="landing-hero__note">{t('landing.hero.note')}</p>
          </div>
          <ProductPreview />
        </div>
      </section>

      <section className="landing-sectors" aria-labelledby="landing-sectors-title">
        <div className="landing-container landing-sectors__row">
          <h2 id="landing-sectors-title">{t('landing.sectors.label')}</h2>
          <ul>
            {SECTORS.map(({ key, icon }) => (
              <li key={key}>
                <Icon name={icon} />
                {t(`landing.sectors.${key}`)}
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section
        id="platform"
        className="landing-section"
        aria-labelledby="landing-platform-title"
        tabIndex={-1}
      >
        <div className="landing-container">
          <div className="landing-split">
            <div>
              <Kicker>{t('landing.platform.kicker')}</Kicker>
              <h2 id="landing-platform-title">{t('landing.platform.title')}</h2>
            </div>
            <p className="landing-lead">{t('landing.platform.body')}</p>
          </div>
          <Steps prefix="landing.platform.steps" steps={PLATFORM_STEPS} />
        </div>
      </section>

      <section
        className="landing-section landing-section--alt"
        aria-labelledby="landing-modules-title"
        data-testid="landing-modules"
      >
        <div className="landing-container">
          <Kicker>{t('landing.modules.kicker')}</Kicker>
          <h2 id="landing-modules-title">{t('landing.modules.title')}</h2>
          <p className="landing-lead">{t('landing.modules.body')}</p>
          <ul className="landing-modules">
            {VISION_MODULES.map((module) => (
              <li key={module.id} data-module={module.id} data-status={module.status}>
                <div className="landing-modules__head">
                  <span className="landing-tile-icon">
                    <Icon name={module.icon} />
                  </span>
                  <AvailabilityBadge status={module.status} />
                </div>
                <h3>{t(`landing.modules.items.${module.id}.title`)}</h3>
                <p>{t(`landing.modules.items.${module.id}.description`)}</p>
              </li>
            ))}
          </ul>
          <div className="landing-today" data-testid="landing-today">
            <h3>{t('landing.modules.todayTitle')}</h3>
            <ul>
              {AVAILABLE_MODULES.map((module) => (
                <li key={module.id} data-module={module.id}>
                  <Icon name={module.icon} />
                  <span>
                    <b>{t(`landing.modules.items.${module.id}.title`)}</b>
                    {t(`landing.modules.items.${module.id}.description`)}
                  </span>
                </li>
              ))}
            </ul>
          </div>
          <p className="landing-legend">{t('landing.modules.legend')}</p>
        </div>
      </section>

      <section
        id="payroll"
        className="landing-section"
        aria-labelledby="landing-payroll-title"
        tabIndex={-1}
      >
        <div className="landing-container landing-payout">
          <div>
            <div className="landing-kicker-row">
              <Kicker>{t('landing.payroll.kicker')}</Kicker>
              <AvailabilityBadge status="later" />
            </div>
            <h2 id="landing-payroll-title">{t('landing.payroll.title')}</h2>
            <p className="landing-lead">{t('landing.payroll.body')}</p>
            <ul className="landing-rails" aria-label={t('landing.payroll.railsLabel')}>
              {RAILS.map((rail) => (
                <li key={rail} lang="en">
                  {rail}
                </li>
              ))}
              <li>{t('landing.payroll.bankAccount')}</li>
              <li>{t('landing.payroll.bankFile')}</li>
            </ul>
            <p className="landing-note" data-testid="landing-payout-note">
              <Icon name="info" />
              <span>{t('landing.payroll.note')}</span>
            </p>
          </div>
          <Steps prefix="landing.payroll.steps" steps={PAYROLL_STEPS} />
        </div>
      </section>

      <section
        id="integrations"
        className="landing-section landing-section--alt"
        aria-labelledby="landing-integrations-title"
        tabIndex={-1}
        data-testid="landing-integrations"
      >
        <div className="landing-container">
          <div className="landing-split">
            <div>
              <Kicker>{t('landing.integrations.kicker')}</Kicker>
              <h2 id="landing-integrations-title">{t('landing.integrations.title')}</h2>
            </div>
            <p className="landing-lead">{t('landing.integrations.body')}</p>
          </div>
          <div className="landing-integrations">
            {INTEGRATION_GROUPS.map((group) => (
              <section key={group.id} aria-labelledby={`landing-integrations-${group.id}`}>
                <div className="landing-integrations__head">
                  <h3 id={`landing-integrations-${group.id}`}>
                    {t(`landing.integrations.groups.${group.id}`)}
                  </h3>
                  <StatusBadge tone="neutral" icon="clock">
                    {t('landing.integrations.planned')}
                  </StatusBadge>
                </div>
                <ul>
                  {group.entries.map((entry) => (
                    <IntegrationItem
                      key={entry.kind === 'brand' ? entry.name : entry.labelKey}
                      entry={entry}
                    />
                  ))}
                </ul>
              </section>
            ))}
          </div>
          <p className="landing-note" data-testid="landing-integrations-note">
            <Icon name="info" />
            <span>{t('landing.integrations.note')}</span>
          </p>
        </div>
      </section>

      <section className="landing-section landing-ai" aria-labelledby="landing-ai-title">
        <div className="landing-container landing-ai__grid">
          <div>
            <div className="landing-kicker-row">
              <Kicker>{t('landing.ai.kicker')}</Kicker>
              <AvailabilityBadge status="later" />
            </div>
            <h2 id="landing-ai-title">{t('landing.ai.title')}</h2>
            <p className="landing-lead">{t('landing.ai.body')}</p>
            <ul className="landing-guarantees">
              {AI_GUARANTEES.map((key) => (
                <li key={key}>
                  <Icon name="check" />
                  {t(`landing.ai.guarantees.${key}`)}
                </li>
              ))}
            </ul>
          </div>
          <ul className="landing-prompts" aria-label={t('landing.ai.examplesLabel')}>
            {AI_EXAMPLES.map((key) => (
              <li key={key}>
                <Icon name="spark" />
                <span>{t(`landing.ai.examples.${key}`)}</span>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section
        id="why"
        className="landing-section"
        aria-labelledby="landing-why-title"
        tabIndex={-1}
      >
        <div className="landing-container">
          <div className="landing-split">
            <div>
              <Kicker>{t('landing.why.kicker')}</Kicker>
              <h2 id="landing-why-title">{t('landing.why.title')}</h2>
            </div>
            <p className="landing-lead">{t('landing.why.body')}</p>
          </div>
          <ul className="landing-principles">
            {PRINCIPLES.map((key) => (
              <li key={key}>
                <b>{t(`landing.why.principles.${key}.title`)}</b>
                <span>{t(`landing.why.principles.${key}.description`)}</span>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section
        className="landing-section landing-section--alt"
        aria-labelledby="landing-finance-title"
      >
        <div className="landing-container">
          <div className="landing-split">
            <div>
              <div className="landing-kicker-row">
                <Kicker>{t('landing.finance.kicker')}</Kicker>
                <AvailabilityBadge status="later" />
              </div>
              <h2 id="landing-finance-title">{t('landing.finance.title')}</h2>
            </div>
            <p className="landing-lead">{t('landing.finance.body')}</p>
          </div>
          <Steps prefix="landing.finance.steps" steps={FINANCE_STEPS} />
        </div>
      </section>

      <section className="landing-section" aria-labelledby="landing-outcomes-title">
        <div className="landing-container">
          <h2 id="landing-outcomes-title">{t('landing.outcomes.title')}</h2>
          <ul className="landing-outcomes">
            {OUTCOMES.map((key) => (
              <li key={key}>
                <h3>{t(`landing.outcomes.items.${key}.title`)}</h3>
                <p>{t(`landing.outcomes.items.${key}.description`)}</p>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="landing-cta" aria-labelledby="landing-cta-title">
        <div className="landing-container">
          <BrandMark />
          <h2 id="landing-cta-title">{t('landing.cta.title')}</h2>
          <p>{t('landing.cta.body')}</p>
          <div className="landing-cta__actions">
            <a className="button" href={mailto}>
              {t('landing.requestDemo')}
              <Icon name="arrowUpRight" />
            </a>
            <span>
              {t('landing.cta.or')} <a href={mailto}>{CONTACT_EMAIL}</a>
            </span>
          </div>
        </div>
      </section>
    </>
  );
}
