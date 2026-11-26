import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { Icon, type IconName } from '../../ui/Icon';
import { DemoLink, PilotLink } from './CommercialFrame';
import { Gallery, HeroShot, ProductReel } from './CommercialMedia';
import { TruthBadge } from './TruthBadge';
import { capabilityOf, langOf, PILLARS, SECTION_CAPABILITIES } from './truth';

/**
 * LAUNCH-004 (Issue #228): the public commercial page. Copy lives in the `commercial.*` locale keys;
 * capability names, wording and status come from the launch-truth source, so a claim cannot be
 * written here without a truth record. No figures, customers, logos-as-endorsement, ratings or
 * testimonials appear anywhere (C3).
 */

const PILLAR_ICON: Record<string, IconName> = {
  people: 'people',
  'leave-scheduling': 'calendar',
  'attendance-time': 'clock',
  payroll: 'payroll',
  'payments-accounting': 'bank',
  'employee-services': 'handshake',
  intelligence: 'spark',
};

const PILLAR_TARGET: Record<string, string> = {
  people: 'solutions',
  'leave-scheduling': 'solutions',
  'attendance-time': 'solutions',
  payroll: 'payroll',
  'payments-accounting': 'payroll',
  'employee-services': 'services',
  intelligence: 'ai',
};

const TRUST: readonly { key: string; icon: IconName; capability?: string }[] = [
  { key: 'languages', icon: 'globe', capability: 'bilingual-experience' },
  { key: 'isolation', icon: 'shield', capability: 'security-tenant-isolation' },
  { key: 'mfa', icon: 'key', capability: 'access-mfa' },
  { key: 'selfService', icon: 'user', capability: 'employee-self-service' },
  { key: 'referrals', icon: 'contract', capability: 'partner-referral' },
  { key: 'realities', icon: 'structure' },
];

const OUTCOMES = [
  'spreadsheets',
  'selfService',
  'payrollInputs',
  'traceable',
  'distributed',
  'partners',
] as const;
const SECTORS = ['healthcare', 'education', 'ngo', 'finance', 'field', 'multisite'] as const;
const PAYROLL_POINTS: readonly { key: string; capability: string }[] = [
  { key: 'history', capability: 'compensation-history' },
  { key: 'time', capability: 'timesheets' },
  { key: 'review', capability: 'payroll-preparation' },
  { key: 'rules', capability: 'statutory-rule-packs' },
  { key: 'payslips', capability: 'payroll-approval-payslips' },
  { key: 'batches', capability: 'payment-preparation' },
  { key: 'reconciliation', capability: 'payment-reconciliation' },
  { key: 'journal', capability: 'accounting-journal-export' },
];
const WELLNESS_STEPS = ['activate', 'consent', 'disclose', 'decide', 'repay'] as const;
const WELLNESS_STATEMENTS = [
  'notLender',
  'noGuarantee',
  'noHidden',
  'privateChoices',
  'availability',
] as const;
const PARTNER_TYPES = [
  'banks',
  'mfi',
  'payments',
  'insurance',
  'health',
  'telecom',
  'software',
] as const;
const PARTNER_PATH = [
  'apply',
  'diligence',
  'scope',
  'review',
  'activate',
  'limited',
  'broader',
] as const;
const SECURITY_ITEMS: readonly { key: string; capability: string }[] = [
  { key: 'isolation', capability: 'security-tenant-isolation' },
  { key: 'mfa', capability: 'access-mfa' },
  { key: 'scoped', capability: 'access-mfa' },
  { key: 'appendOnly', capability: 'security-append-only-history' },
  { key: 'encrypted', capability: 'security-encrypted-data' },
  { key: 'exports', capability: 'security-controlled-exports' },
  { key: 'requests', capability: 'privacy-requests' },
  { key: 'retention', capability: 'retention-governance' },
  { key: 'backups', capability: 'backup-recovery' },
];
const AI_FUTURE = ['guided', 'exceptions', 'policy', 'recommendations', 'selfService'] as const;
const PILOT_FOR = ['collaborative', 'synthetic', 'bounded', 'scope', 'admins', 'feedback'] as const;
const PILOT_NEXT = [
  'discovery',
  'readiness',
  'plan',
  'rehearsal',
  'onboarding',
  'acceptance',
] as const;

type IntegrationEntry =
  | { kind: 'generic'; key: string; capability: string; icon: IconName }
  | { kind: 'brand'; name: string; logo?: string; capability: string };

const INTEGRATION_GROUPS: readonly { id: string; entries: readonly IntegrationEntry[] }[] = [
  {
    id: 'available',
    entries: [
      { kind: 'generic', key: 'webhooks', capability: 'webhooks', icon: 'network' },
      {
        kind: 'brand',
        name: 'WhatsApp Business',
        logo: 'whatsapp.svg',
        capability: 'whatsapp-notifications',
      },
    ],
  },
  {
    id: 'designed',
    entries: [
      { kind: 'generic', key: 'journal', capability: 'accounting-journal-export', icon: 'folder' },
    ],
  },
  {
    id: 'partner',
    entries: [
      { kind: 'generic', key: 'banks', capability: 'real-payment-delivery', icon: 'bank' },
      { kind: 'generic', key: 'mobileMoney', capability: 'real-payment-delivery', icon: 'phone' },
      {
        kind: 'brand',
        name: 'Airtel Money',
        logo: 'airtel.svg',
        capability: 'real-payment-delivery',
      },
      {
        kind: 'brand',
        name: 'Orange Money',
        logo: 'orange.svg',
        capability: 'real-payment-delivery',
      },
      { kind: 'brand', name: 'M-PESA', capability: 'real-payment-delivery' },
    ],
  },
  {
    id: 'planned',
    entries: [
      {
        kind: 'generic',
        key: 'accounting',
        capability: 'accounting-system-connectors',
        icon: 'folder',
      },
      { kind: 'brand', name: 'Sage', logo: 'sage.svg', capability: 'accounting-system-connectors' },
      {
        kind: 'brand',
        name: 'QuickBooks',
        logo: 'quickbooks.svg',
        capability: 'accounting-system-connectors',
      },
      { kind: 'brand', name: 'Odoo', logo: 'odoo.svg', capability: 'accounting-system-connectors' },
      { kind: 'brand', name: 'SAP', logo: 'sap.svg', capability: 'accounting-system-connectors' },
      { kind: 'brand', name: 'Oracle', capability: 'accounting-system-connectors' },
      { kind: 'generic', key: 'sso', capability: 'sso-federation', icon: 'key' },
    ],
  },
];

function Kicker({ children }: { children: string }) {
  return <p className="commercial-kicker">{children}</p>;
}

function CapabilityList({ ids }: { ids: readonly string[] }) {
  const { i18n } = useTranslation();
  const lang = langOf(i18n.resolvedLanguage);
  return (
    <ul className="commercial-capabilities">
      {ids.map((id) => {
        const capability = capabilityOf(id);
        return (
          <li key={id} data-capability={id} data-status={capability.status}>
            <span>{capability.name[lang]}</span>
            <TruthBadge id={id} />
          </li>
        );
      })}
    </ul>
  );
}

function Hero() {
  const { t } = useTranslation();
  return (
    <section className="commercial-hero" aria-labelledby="commercial-hero-title">
      <div className="commercial-container commercial-hero__grid">
        <div>
          <p className="commercial-badge-pill" data-testid="commercial-pilot-badge">
            <Icon name="shield" />
            {t('commercial.badge.pilot')}
          </p>
          <h1 id="commercial-hero-title">{t('commercial.hero.title')}</h1>
          <p className="commercial-hero__body">{t('commercial.hero.body')}</p>
          <div className="commercial-hero__actions">
            <DemoLink className="button" />
            <PilotLink className="button button--secondary" />
          </div>
          <p className="commercial-hero__note">{t('commercial.hero.badgeNote')}</p>
        </div>
        <HeroShot />
      </div>
    </section>
  );
}

function TrustStrip() {
  const { t } = useTranslation();
  return (
    <section className="commercial-trust" aria-label={t('commercial.trust.label')}>
      <ul className="commercial-container">
        {TRUST.map(({ key, icon, capability }) => (
          <li key={key} data-capability={capability}>
            <Icon name={icon} />
            {t(`commercial.trust.${key}`)}
          </li>
        ))}
      </ul>
    </section>
  );
}

function StatusLegend() {
  const { t } = useTranslation();
  const statuses = [
    'PILOT_AVAILABLE',
    'CONFIGURATION_REQUIRED',
    'PARTNER_REQUIRED',
    'PLANNED',
  ] as const;
  const sample: Record<(typeof statuses)[number], string> = {
    PILOT_AVAILABLE: 'bilingual-experience',
    CONFIGURATION_REQUIRED: 'statutory-rule-packs',
    PARTNER_REQUIRED: 'real-payment-delivery',
    PLANNED: 'native-mobile-apps',
  };
  return (
    <details className="commercial-legend">
      <summary>{t('commercial.status.legendTitle')}</summary>
      <dl>
        {statuses.map((status) => (
          <div key={status}>
            <dt>
              <TruthBadge id={sample[status]} />
            </dt>
            <dd>{t(`commercial.status.legend.${status}`)}</dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

function Platform() {
  const { t } = useTranslation();
  return (
    <section
      id="product"
      className="commercial-section"
      aria-labelledby="commercial-product-title"
      tabIndex={-1}
    >
      <div className="commercial-container">
        <Kicker>{t('commercial.platform.kicker')}</Kicker>
        <h2 id="commercial-product-title">{t('commercial.platform.title')}</h2>
        <p className="commercial-lead">{t('commercial.platform.intro')}</p>
        <StatusLegend />
        <ul className="commercial-pillars">
          {PILLARS.map((pillar) => (
            <li key={pillar.id} data-pillar={pillar.id}>
              <span className="commercial-tile-icon">
                <Icon name={PILLAR_ICON[pillar.id] ?? 'home'} />
              </span>
              <h3>{t(`commercial.platform.pillars.${pillar.id}.title`)}</h3>
              <p>{t(`commercial.platform.pillars.${pillar.id}.outcome`)}</p>
              <CapabilityList ids={pillar.capabilities} />
              <a className="commercial-more" href={`#${PILLAR_TARGET[pillar.id] ?? 'product'}`}>
                {t('commercial.cta.learnMore')}
                <span className="visually-hidden">
                  {' '}
                  — {t(`commercial.platform.pillars.${pillar.id}.title`)}
                </span>
              </a>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function Solutions() {
  const { t } = useTranslation();
  return (
    <section
      id="solutions"
      className="commercial-section commercial-section--alt"
      aria-labelledby="commercial-solutions-title"
      tabIndex={-1}
    >
      <div className="commercial-container">
        <Kicker>{t('commercial.outcomes.kicker')}</Kicker>
        <h2 id="commercial-solutions-title">{t('commercial.outcomes.title')}</h2>
        <ul className="commercial-outcomes">
          {OUTCOMES.map((key) => (
            <li key={key}>
              <h3>{t(`commercial.outcomes.items.${key}.title`)}</h3>
              <p>{t(`commercial.outcomes.items.${key}.description`)}</p>
            </li>
          ))}
        </ul>
        <h3 className="commercial-subtitle">{t('commercial.outcomes.sectorsTitle')}</h3>
        <ul className="commercial-chips">
          {SECTORS.map((key) => (
            <li key={key}>{t(`commercial.outcomes.sectors.${key}`)}</li>
          ))}
        </ul>
        <p className="commercial-legend-note">{t('commercial.outcomes.sectorsNote')}</p>
      </div>
    </section>
  );
}

function Payroll() {
  const { t, i18n } = useTranslation();
  const lang = langOf(i18n.resolvedLanguage);
  return (
    <section
      id="payroll"
      className="commercial-section"
      aria-labelledby="commercial-payroll-title"
      tabIndex={-1}
      data-testid="commercial-payroll"
    >
      <div className="commercial-container">
        <Kicker>{t('commercial.payroll.kicker')}</Kicker>
        <h2 id="commercial-payroll-title">{t('commercial.payroll.title')}</h2>
        <p className="commercial-lead">{t('commercial.payroll.intro')}</p>
        <ol className="commercial-flow">
          {PAYROLL_POINTS.map(({ key, capability }) => (
            <li
              key={key}
              data-capability={capability}
              data-status={capabilityOf(capability).status}
            >
              <span className="commercial-flow__label">
                {t(`commercial.payroll.points.${key}`)}
              </span>
              <span className="commercial-flow__detail">{capabilityOf(capability)[lang]}</span>
              <TruthBadge id={capability} />
            </li>
          ))}
        </ol>
        <p className="commercial-disclosure" data-testid="commercial-payroll-disclosure">
          <Icon name="info" />
          {t('commercial.payroll.disclosure')}
        </p>
      </div>
    </section>
  );
}

function Services() {
  const { t } = useTranslation();
  return (
    <section
      id="services"
      className="commercial-section commercial-section--alt"
      aria-labelledby="commercial-services-title"
      tabIndex={-1}
      data-testid="commercial-wellness"
    >
      <div className="commercial-container">
        <div className="commercial-kicker-row">
          <Kicker>{t('commercial.wellness.kicker')}</Kicker>
          <TruthBadge id="salary-advances" />
        </div>
        <h2 id="commercial-services-title">{t('commercial.wellness.title')}</h2>
        <p className="commercial-lead">{t('commercial.wellness.intro')}</p>
        <ol className="commercial-steps">
          {WELLNESS_STEPS.map((key) => (
            <li key={key}>
              <h3>{t(`commercial.wellness.steps.${key}.title`)}</h3>
              <p>{t(`commercial.wellness.steps.${key}.description`)}</p>
            </li>
          ))}
        </ol>
        <ul className="commercial-statements">
          {WELLNESS_STATEMENTS.map((key) => (
            <li key={key}>
              <Icon name="check" />
              {t(`commercial.wellness.statements.${key}`)}
            </li>
          ))}
        </ul>
        <p className="commercial-disclosure" data-testid="commercial-lending-disclosure">
          <Icon name="info" />
          {t('commercial.wellness.disclosure')}
        </p>
      </div>
    </section>
  );
}

function IntegrationItem({ entry }: { entry: IntegrationEntry }) {
  const { t } = useTranslation();
  if (entry.kind === 'generic') {
    return (
      <li className="commercial-integration" data-kind="generic" data-capability={entry.capability}>
        <Icon name={entry.icon} />
        <span>{t(`commercial.integrations.entries.${entry.key}`)}</span>
      </li>
    );
  }
  return (
    <li
      className="commercial-integration"
      data-kind={entry.logo ? 'logo' : 'name'}
      data-testid="commercial-integration-brand"
      data-capability={entry.capability}
    >
      {entry.logo && (
        // The visible name carries the meaning; the mark is not announced a second time.
        <img
          className="commercial-integration__logo"
          src={`${import.meta.env.BASE_URL}brands/${entry.logo}`}
          alt=""
          width="20"
          height="20"
          loading="lazy"
        />
      )}
      <span>{entry.name}</span>
    </li>
  );
}

function Integrations() {
  const { t } = useTranslation();
  return (
    <div className="commercial-integrations" data-testid="commercial-integrations">
      <Kicker>{t('commercial.integrations.kicker')}</Kicker>
      <h3>{t('commercial.integrations.title')}</h3>
      <p className="commercial-lead">{t('commercial.integrations.intro')}</p>
      <div className="commercial-integration-groups">
        {INTEGRATION_GROUPS.map((group) => {
          const first = group.entries[0];
          return (
            <section
              key={group.id}
              data-integration-group={group.id}
              aria-labelledby={`commercial-int-${group.id}`}
            >
              <h4 id={`commercial-int-${group.id}`}>
                {t(`commercial.integrations.groups.${group.id}`)}
              </h4>
              {first && <TruthBadge id={first.capability} />}
              <ul>
                {group.entries.map((entry) => (
                  <IntegrationItem
                    key={entry.kind === 'generic' ? entry.key : entry.name}
                    entry={entry}
                  />
                ))}
              </ul>
            </section>
          );
        })}
      </div>
      <p className="commercial-legend-note">{t('commercial.integrations.logoNote')}</p>
    </div>
  );
}

function Partners() {
  const { t } = useTranslation();
  return (
    <section
      id="partners"
      className="commercial-section"
      aria-labelledby="commercial-partners-title"
      tabIndex={-1}
      data-testid="commercial-partners"
    >
      <div className="commercial-container">
        <div className="commercial-kicker-row">
          <Kicker>{t('commercial.partners.kicker')}</Kicker>
          <TruthBadge id="partner-network-activation" />
        </div>
        <h2 id="commercial-partners-title">{t('commercial.partners.title')}</h2>
        <p className="commercial-lead">{t('commercial.partners.intro')}</p>
        <h3 className="commercial-subtitle">{t('commercial.partners.typesTitle')}</h3>
        <ul className="commercial-chips">
          {PARTNER_TYPES.map((key) => (
            <li key={key}>{t(`commercial.partners.types.${key}`)}</li>
          ))}
        </ul>
        <h3 className="commercial-subtitle">{t('commercial.partners.pathTitle')}</h3>
        <ol className="commercial-path">
          {PARTNER_PATH.map((key) => (
            <li key={key}>{t(`commercial.partners.path.${key}`)}</li>
          ))}
        </ol>
        <CapabilityList ids={SECTION_CAPABILITIES.partners ?? []} />
        <p className="commercial-disclosure" data-testid="commercial-partner-disclaimer">
          <Icon name="info" />
          {t('commercial.partners.disclaimer')}
        </p>
        <p className="commercial-legend-note">{t('commercial.partners.statusNote')}</p>
        <p>
          <Link className="button" data-testid="commercial-partner-cta" to="/partners/apply">
            {t('commercial.cta.partner')}
          </Link>
        </p>
        <Integrations />
      </div>
    </section>
  );
}

function Security() {
  const { t } = useTranslation();
  return (
    <section
      id="security"
      className="commercial-section commercial-section--alt"
      aria-labelledby="commercial-security-title"
      tabIndex={-1}
      data-testid="commercial-security"
    >
      <div className="commercial-container">
        <Kicker>{t('commercial.security.kicker')}</Kicker>
        <h2 id="commercial-security-title">{t('commercial.security.title')}</h2>
        <p className="commercial-lead">{t('commercial.security.intro')}</p>
        <ul className="commercial-security">
          {SECURITY_ITEMS.map(({ key, capability }) => (
            <li
              key={key}
              data-capability={capability}
              data-status={capabilityOf(capability).status}
            >
              <h3>{t(`commercial.security.items.${key}.title`)}</h3>
              <p>{t(`commercial.security.items.${key}.description`)}</p>
              <TruthBadge id={capability} />
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function Intelligence() {
  const { t } = useTranslation();
  return (
    <section
      id="ai"
      className="commercial-section"
      aria-labelledby="commercial-ai-title"
      tabIndex={-1}
    >
      <div className="commercial-container commercial-split">
        <div>
          <Kicker>{t('commercial.ai.kicker')}</Kicker>
          <h2 id="commercial-ai-title">{t('commercial.ai.title')}</h2>
          <h3 className="commercial-subtitle">{t('commercial.ai.nowTitle')}</h3>
          <p>{t('commercial.ai.now')}</p>
          <TruthBadge id="grounded-summaries" />
        </div>
        <div>
          <h3 className="commercial-subtitle">{t('commercial.ai.futureTitle')}</h3>
          <ul className="commercial-future">
            {AI_FUTURE.map((key) => (
              <li key={key}>
                <Icon name="spark" />
                {t(`commercial.ai.future.${key}`)}
              </li>
            ))}
          </ul>
          <TruthBadge id="ai-guided-assistance" />
        </div>
      </div>
    </section>
  );
}

function Mobile() {
  const { t } = useTranslation();
  return (
    <section
      className="commercial-section commercial-section--alt"
      aria-labelledby="commercial-mobile-title"
      data-testid="commercial-mobile"
    >
      <div className="commercial-container">
        <Kicker>{t('commercial.mobile.kicker')}</Kicker>
        <h2 id="commercial-mobile-title">{t('commercial.mobile.title')}</h2>
        <p className="commercial-lead">{t('commercial.mobile.body')}</p>
        <CapabilityList ids={SECTION_CAPABILITIES.mobile ?? []} />
      </div>
    </section>
  );
}

function Pilot() {
  const { t } = useTranslation();
  return (
    <section
      id="pilot"
      className="commercial-section"
      aria-labelledby="commercial-pilot-title"
      tabIndex={-1}
      data-testid="commercial-pilot"
    >
      <div className="commercial-container">
        <Kicker>{t('commercial.pilot.kicker')}</Kicker>
        <h2 id="commercial-pilot-title">{t('commercial.pilot.title')}</h2>
        <p className="commercial-lead">{t('commercial.pilot.intro')}</p>
        <div className="commercial-split">
          <div>
            <h3 className="commercial-subtitle">{t('commercial.pilot.forTitle')}</h3>
            <ul className="commercial-statements">
              {PILOT_FOR.map((key) => (
                <li key={key}>
                  <Icon name="check" />
                  {t(`commercial.pilot.for.${key}`)}
                </li>
              ))}
            </ul>
          </div>
          <div>
            <h3 className="commercial-subtitle">{t('commercial.pilot.nextTitle')}</h3>
            <ol className="commercial-path commercial-path--stacked">
              {PILOT_NEXT.map((key) => (
                <li key={key}>{t(`commercial.pilot.next.${key}`)}</li>
              ))}
            </ol>
          </div>
        </div>
        <div className="commercial-hero__actions">
          <DemoLink className="button" />
          <PilotLink className="button button--secondary" />
        </div>
        <p className="commercial-legend-note">{t('commercial.pilot.contactNote')}</p>
      </div>
    </section>
  );
}

function FinalCta() {
  const { t } = useTranslation();
  return (
    <section className="commercial-cta" aria-labelledby="commercial-cta-title">
      <div className="commercial-container">
        <h2 id="commercial-cta-title">{t('commercial.finalCta.title')}</h2>
        <p>{t('commercial.finalCta.body')}</p>
        <div className="commercial-hero__actions">
          <DemoLink className="button" />
          <PilotLink className="button button--secondary" />
        </div>
      </div>
    </section>
  );
}

export default function CommercialPage() {
  const { t } = useTranslation();
  return (
    <>
      <Hero />
      <TrustStrip />
      <section className="commercial-positioning" aria-label={t('commercial.nav.product')}>
        <div className="commercial-container">
          <p>{t('commercial.hero.positioning')}</p>
        </div>
      </section>
      <Platform />
      <Gallery />
      <ProductReel />
      <Intelligence />
      <Mobile />
      <Solutions />
      <Payroll />
      <Services />
      <Partners />
      <Security />
      <Pilot />
      <FinalCta />
    </>
  );
}
