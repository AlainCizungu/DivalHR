import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useSession, useSessionRetry } from '../../app/SessionProvider';
import type { Role } from '../../app/routes';
import { useAuth } from '../../auth/AuthProvider';
import { Icon, type IconName } from '../../ui/Icon';
import {
  Card,
  EmptyState,
  ErrorPanel,
  LinkCard,
  PageHeader,
  Skeleton,
  StatusBadge,
} from '../../ui/primitives';
import { EMPLOYEE_ROADMAP, TENANT_ADMIN_ROADMAP, type RoadmapItem } from './roadmap';

/**
 * UI-001 role homes. Static entry points only: no counts, totals, alerts or activity (D10). Each
 * section appears for the role that can open its destinations; users with several roles see each
 * of their sections (D13).
 */

function Roadmap({ items }: { items: RoadmapItem[] }) {
  const { t } = useTranslation();
  const id = useId();
  return (
    <section className="home-section" aria-labelledby={id} data-testid="roadmap">
      <div className="home-section__header">
        <h2 id={id}>{t('roadmap.title')}</h2>
        <p>{t('roadmap.description')}</p>
      </div>
      <ul className="roadmap">
        {items.map((item) => (
          <li key={item.id} className="roadmap__item">
            <Icon name={item.icon} />
            <div>
              <p className="roadmap__title">{t(`roadmap.items.${item.id}.title`)}</p>
              <p className="roadmap__description">{t(`roadmap.items.${item.id}.description`)}</p>
              <StatusBadge tone="neutral">{t('roadmap.badge')}</StatusBadge>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

interface CardDef {
  to: string;
  icon: IconName;
  titleKey: string;
  descriptionKey: string;
  testId: string;
}

function CardGrid({ cards }: { cards: CardDef[] }) {
  const { t } = useTranslation();
  return (
    <ul className="card-grid">
      {cards.map((card) => (
        <LinkCard
          key={card.to}
          to={card.to}
          icon={card.icon}
          title={t(card.titleKey)}
          description={t(card.descriptionKey)}
          more={t('home.open')}
          testId={card.testId}
        />
      ))}
    </ul>
  );
}

const PLATFORM_CARDS: CardDef[] = [
  {
    to: '/admin/organizations/new',
    icon: 'organization',
    titleKey: 'home.platform.createOrganization.title',
    descriptionKey: 'home.platform.createOrganization.description',
    testId: 'home-card-create-organization',
  },
  {
    to: '/admin/organizations/first-admin',
    icon: 'key',
    titleKey: 'home.platform.firstAdmin.title',
    descriptionKey: 'home.platform.firstAdmin.description',
    testId: 'home-card-first-admin',
  },
  {
    to: '/status',
    icon: 'pulse',
    titleKey: 'nav.status',
    descriptionKey: 'home.platform.status.description',
    testId: 'home-card-status',
  },
];

function PlatformSection() {
  const { t } = useTranslation();
  const id = useId();
  return (
    <section className="home-section" aria-labelledby={id}>
      <div className="home-section__header">
        <h2 id={id}>{t('home.platform.tasksTitle')}</h2>
      </div>
      <CardGrid cards={PLATFORM_CARDS} />
    </section>
  );
}

const QUICK_ACTIONS: { to: string; icon: IconName; key: string }[] = [
  { to: '/admin/people/import', icon: 'import', key: 'importEmployees' },
  { to: '/admin/users', icon: 'invite', key: 'inviteUser' },
  { to: '/admin/contract-templates', icon: 'contract', key: 'createTemplate' },
  { to: '/admin/access', icon: 'shield', key: 'reviewAccess' },
];

const TENANT_CARDS: CardDef[] = [
  {
    to: '/admin/people',
    icon: 'people',
    titleKey: 'nav.employees',
    descriptionKey: 'home.tenant.cards.employees',
    testId: 'home-card-employees',
  },
  {
    to: '/admin/hierarchy',
    icon: 'structure',
    titleKey: 'nav.hierarchy',
    descriptionKey: 'home.tenant.cards.hierarchy',
    testId: 'home-card-hierarchy',
  },
  {
    to: '/admin/users',
    icon: 'invite',
    titleKey: 'nav.users',
    descriptionKey: 'home.tenant.cards.users',
    testId: 'home-card-users',
  },
  {
    to: '/admin/access',
    icon: 'shield',
    titleKey: 'nav.accessReview',
    descriptionKey: 'home.tenant.cards.accessReview',
    testId: 'home-card-access-review',
  },
  {
    to: '/admin/contract-templates',
    icon: 'contract',
    titleKey: 'nav.contractTemplates',
    descriptionKey: 'home.tenant.cards.contractTemplates',
    testId: 'home-card-contract-templates',
  },
  {
    to: '/admin/people/import',
    icon: 'import',
    titleKey: 'nav.employeeImport',
    descriptionKey: 'home.tenant.cards.employeeImport',
    testId: 'home-card-employee-import',
  },
];

function TenantAdminSections() {
  const { t } = useTranslation();
  const quickId = useId();
  const modulesId = useId();
  return (
    <>
      <section className="home-section" aria-labelledby={quickId}>
        <h2 id={quickId} className="visually-hidden">
          {t('home.tenant.quickActions')}
        </h2>
        <ul className="quick-actions" data-testid="quick-actions">
          {QUICK_ACTIONS.map((action, index) => (
            <li key={action.key}>
              <Link
                to={action.to}
                className={index === 0 ? 'quick-action quick-action--primary' : 'quick-action'}
              >
                <Icon name={action.icon} />
                {t(`home.tenant.actions.${action.key}`)}
              </Link>
            </li>
          ))}
        </ul>
      </section>
      <section className="home-section" aria-labelledby={modulesId}>
        <div className="home-section__header">
          <h2 id={modulesId}>{t('home.tenant.modulesTitle')}</h2>
          <p>{t('home.tenant.modulesDescription')}</p>
        </div>
        <CardGrid cards={TENANT_CARDS} />
      </section>
    </>
  );
}

function EmployeeSection() {
  const { t } = useTranslation();
  const id = useId();
  return (
    <Card labelledBy={id} className="feature-card" testId="home-my-contracts">
      <span className="feature-card__icon">
        <Icon name="contract" />
      </span>
      <div className="feature-card__body">
        <h2 id={id}>{t('nav.myContracts')}</h2>
        <p>{t('home.employee.contractsDescription')}</p>
        <Link to="/me/contracts" className="button">
          {t('home.employee.contractsAction')}
          <Icon name="chevronRight" />
        </Link>
      </div>
    </Card>
  );
}

/** The page heading belongs to the most privileged role held. */
function headerFor(roles: readonly Role[]): 'platform' | 'tenant' | 'employee' {
  if (roles.includes('platform-admin')) return 'platform';
  if (roles.includes('tenant-admin')) return 'tenant';
  return 'employee';
}

function PublicWelcome() {
  const { t } = useTranslation();
  const { signIn } = useAuth();
  return (
    <section className="public-welcome" aria-labelledby="home-title">
      <PageHeader titleId="home-title" title={t('home.title')} description={t('app.tagline')} />
      <Card className="public-welcome__card">
        <p>{t('auth.required')}</p>
        <div className="button-row">
          <button
            type="button"
            className="button"
            onClick={() => {
              void signIn('/');
            }}
          >
            {t('auth.signIn')}
          </button>
          <Link to="/status">{t('nav.status')}</Link>
        </div>
      </Card>
    </section>
  );
}

export function HomePage() {
  const { t } = useTranslation();
  const session = useSession();
  const retry = useSessionRetry();
  const { signOut } = useAuth();

  if (session.kind === 'anonymous') return <PublicWelcome />;
  if (session.kind === 'loading') {
    return (
      <div className="home-loading">
        <p role="status">{t('session.loading')}</p>
        <Skeleton lines={4} />
      </div>
    );
  }
  if (session.kind === 'error') {
    return (
      <>
        <h1 className="visually-hidden">{t('home.title')}</h1>
        <ErrorPanel
          title={t('home.sessionError')}
          message={t(`errors.${session.code}`, { defaultValue: t('errors.generic') })}
          action={
            <div className="button-row">
              <button type="button" className="button" onClick={retry}>
                {t('home.retry')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                onClick={() => void signOut()}
              >
                {t('auth.signOut')}
              </button>
            </div>
          }
        />
      </>
    );
  }

  const roles = session.session.roles;
  if (roles.length === 0) {
    return (
      <EmptyState
        icon="user"
        title={t('home.noAccess.title')}
        titleLevel={1}
        description={t('home.noAccess.description')}
        testId="home-no-access"
        action={
          <button type="button" className="button button--secondary" onClick={() => void signOut()}>
            {t('auth.signOut')}
          </button>
        }
      />
    );
  }

  const primary = headerFor(roles);
  const isTenantAdmin = roles.includes('tenant-admin');
  const isEmployee = roles.includes('employee');
  return (
    <div className="home" data-testid={`home-${primary}`}>
      <PageHeader
        eyebrow={t(`home.${primary}.eyebrow`)}
        title={t(`home.${primary}.title`)}
        description={t(`home.${primary}.description`)}
      />
      {roles.includes('platform-admin') && <PlatformSection />}
      {isTenantAdmin && <TenantAdminSections />}
      {isEmployee && <EmployeeSection />}
      {isTenantAdmin && <Roadmap items={TENANT_ADMIN_ROADMAP} />}
      {isEmployee && !isTenantAdmin && <Roadmap items={EMPLOYEE_ROADMAP} />}
    </div>
  );
}
