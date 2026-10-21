import { lazy, Suspense, useId } from 'react';
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
import { ContractAttentionCard } from './ContractAttentionCard';
import { TeamAttendanceCard } from './TeamAttendanceCard';
import { EMPLOYEE_ROADMAP, TENANT_ADMIN_ROADMAP, type RoadmapItem } from './roadmap';

/**
 * UI-001 role homes, refreshed by UI-003 (Issue #103) with the shipped leave and scheduling
 * destinations and by UI-004 (Issue #136) with attendance, timesheets and approved-time exports
 * (an operations launchpad with a static "Recent platform progress" panel; still no figures). Static entry points only: no counts, totals, alerts or activity (D10, UI3-7), with
 * two approved exceptions: the organization administrator's live "Contracts needing attention"
 * card (MVP-031A, C3b) and the manager's conditional "Team attendance to record" card (MVP-060B,
 * B60B-8, shown only while the caller's queue is not empty, without a count). Each section appears for the role that can open its destinations; users with
 * several roles see each of their sections (D13, UI3-6).
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

function CardGrid({ cards, pairs = false }: { cards: CardDef[]; pairs?: boolean }) {
  const { t } = useTranslation();
  return (
    <ul className={pairs ? 'card-grid card-grid--pairs' : 'card-grid'}>
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

/**
 * UI-003 (UI3-4), UI-004 (U4-3): the administrator's four quick actions, the first one primary.
 * Four keep one row on a desktop (a second row would sit under the open account menu, where a
 * partially covered link fails the touch-target check); every other destination is a card below.
 */
const QUICK_ACTIONS: { to: string; icon: IconName; key: string }[] = [
  { to: '/admin/people/import', icon: 'import', key: 'importEmployees' },
  { to: '/admin/leave-approvals', icon: 'calendar', key: 'reviewLeave' },
  { to: '/admin/timesheets', icon: 'check', key: 'openTimesheets' },
  { to: '/admin/time-exports', icon: 'payroll', key: 'exportTime' },
];

function tenantCard(id: string, to: string, icon: IconName, testId: string): CardDef {
  return {
    to,
    icon,
    titleKey: `home.tenant.cards.${id}.title`,
    descriptionKey: `home.tenant.cards.${id}.description`,
    testId,
  };
}

/**
 * UI-003 (UI3-4), UI-004 (U4-3): three groups, each card a destination the tenant administrator
 * can open through existing navigation; descriptive text and links only, never a total.
 */
const TENANT_GROUPS: { id: 'people' | 'operations' | 'governance'; cards: CardDef[] }[] = [
  {
    id: 'people',
    cards: [
      tenantCard('employees', '/admin/people', 'people', 'home-card-employees'),
      tenantCard('hierarchy', '/admin/hierarchy', 'structure', 'home-card-hierarchy'),
      tenantCard('leavePolicies', '/admin/leave-policies', 'calendar', 'home-card-leave-policies'),
      tenantCard('leaveApprovals', '/admin/leave-approvals', 'check', 'home-card-leave-approvals'),
    ],
  },
  {
    id: 'operations',
    cards: [
      tenantCard('workSchedules', '/admin/work-schedules', 'rotation', 'home-card-work-schedules'),
      tenantCard('operationsStatus', '/admin/operations', 'pulse', 'home-card-operations-status'),
      tenantCard(
        'attendanceReview',
        '/admin/attendance-review',
        'clock',
        'home-card-attendance-review',
      ),
      tenantCard('timesheets', '/admin/timesheets', 'check', 'home-card-timesheets'),
      tenantCard('timeExports', '/admin/time-exports', 'payroll', 'home-card-time-exports'),
      tenantCard(
        'payrollPreparation',
        '/admin/payroll-preparation',
        'payroll',
        'home-card-payroll-preparation',
      ),
    ],
  },
  {
    id: 'governance',
    cards: [
      tenantCard(
        'contractTemplates',
        '/admin/contract-templates',
        'contract',
        'home-card-contract-templates',
      ),
      tenantCard('users', '/admin/users', 'invite', 'home-card-users'),
      tenantCard('accessReview', '/admin/access', 'shield', 'home-card-access-review'),
    ],
  },
];

/** UI-004 (U4-3): what was delivered recently; static text with no count, status or alert. */
const PROGRESS: { id: string; icon: IconName }[] = [
  { id: 'attendance', icon: 'clock' },
  { id: 'approvals', icon: 'check' },
  { id: 'exports', icon: 'payroll' },
];

function ProgressPanel() {
  const { t } = useTranslation();
  const id = useId();
  return (
    <section className="home-section" aria-labelledby={id} data-testid="home-progress">
      <div className="home-section__header">
        <h2 id={id}>{t('home.tenant.progress.title')}</h2>
        <p>{t('home.tenant.progress.description')}</p>
      </div>
      <ul className="roadmap roadmap--delivered">
        {PROGRESS.map((item) => (
          <li key={item.id} className="roadmap__item">
            <Icon name={item.icon} />
            <div>
              <p className="roadmap__title">{t(`home.tenant.progress.items.${item.id}.title`)}</p>
              <p className="roadmap__description">
                {t(`home.tenant.progress.items.${item.id}.description`)}
              </p>
              <StatusBadge tone="success" icon="check">
                {t('landing.modules.available')}
              </StatusBadge>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function TenantGroup({ group }: { group: (typeof TENANT_GROUPS)[number] }) {
  const { t } = useTranslation();
  const id = useId();
  return (
    <section className="home-section" aria-labelledby={id} data-testid={`home-group-${group.id}`}>
      <div className="home-section__header">
        <h2 id={id}>{t(`home.tenant.groups.${group.id}.title`)}</h2>
        <p>{t(`home.tenant.groups.${group.id}.description`)}</p>
      </div>
      <CardGrid cards={group.cards} pairs />
    </section>
  );
}

function TenantAdminSections() {
  const { t } = useTranslation();
  const quickId = useId();
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
      <ContractAttentionCard />
      {TENANT_GROUPS.map((group) => (
        <TenantGroup key={group.id} group={group} />
      ))}
      <ProgressPanel />
    </>
  );
}

/**
 * UI-003 (UI3-5), UI-004 (U4-4): the employee's own destinations, in the order of a working
 * week (schedule, leave, attendance, timesheets, contracts); each page loads its own data.
 */
const EMPLOYEE_CARDS: CardDef[] = [
  {
    to: '/me/schedule',
    icon: 'rotation',
    titleKey: 'nav.mySchedule',
    descriptionKey: 'home.employee.cards.schedule',
    testId: 'home-card-my-schedule',
  },
  {
    to: '/me/leave',
    icon: 'calendar',
    titleKey: 'nav.myLeave',
    descriptionKey: 'home.employee.cards.leave',
    testId: 'home-card-my-leave',
  },
  {
    to: '/me/attendance',
    icon: 'clock',
    titleKey: 'nav.myAttendance',
    descriptionKey: 'home.employee.cards.attendance',
    testId: 'home-card-my-attendance',
  },
  {
    to: '/me/timesheets',
    icon: 'check',
    titleKey: 'nav.myTimesheets',
    descriptionKey: 'home.employee.cards.timesheets',
    testId: 'home-card-my-timesheets',
  },
  {
    to: '/me/team-operations',
    icon: 'pulse',
    titleKey: 'nav.teamOperations',
    descriptionKey: 'home.employee.cards.teamOperations',
    testId: 'home-card-team-operations',
  },
  {
    to: '/me/contracts',
    icon: 'contract',
    titleKey: 'nav.myContracts',
    descriptionKey: 'home.employee.cards.contracts',
    testId: 'home-card-my-contracts',
  },
];

function EmployeeSection() {
  const { t } = useTranslation();
  const workId = useId();
  const approvalsId = useId();
  const substitutionsId = useId();
  return (
    <>
      <section className="home-section" aria-labelledby={workId} data-testid="home-my-work">
        <div className="home-section__header">
          <h2 id={workId}>{t('home.employee.myWork')}</h2>
        </div>
        <CardGrid cards={EMPLOYEE_CARDS} />
      </section>
      {/* MVP-060B: shown only while the caller has team-attendance actions (no count). */}
      <TeamAttendanceCard />
      {/* Conditional wording: the inbox is empty unless the employee is an approver (UI3-5). */}
      <Card labelledBy={approvalsId} className="feature-card" testId="home-leave-approvals">
        <span className="feature-card__icon">
          <Icon name="check" />
        </span>
        <div className="feature-card__body">
          <h2 id={approvalsId}>{t('home.employee.approvals.title')}</h2>
          <p>{t('home.employee.approvals.description')}</p>
          <Link to="/me/leave/approvals" className="button button--secondary">
            {t('home.employee.approvals.action')}
            <Icon name="chevronRight" />
          </Link>
        </div>
      </Card>
      {/* MVP-051: the same conditional wording; the home loads no approval data (UI3-7). */}
      <Card labelledBy={substitutionsId} className="feature-card" testId="home-shift-substitutions">
        <span className="feature-card__icon">
          <Icon name="rotation" />
        </span>
        <div className="feature-card__body">
          <h2 id={substitutionsId}>{t('home.employee.substitutions.title')}</h2>
          <p>{t('home.employee.substitutions.description')}</p>
          <Link to="/me/schedule/substitutions" className="button button--secondary">
            {t('home.employee.substitutions.action')}
            <Icon name="chevronRight" />
          </Link>
        </div>
      </Card>
    </>
  );
}

/** The page heading belongs to the most privileged role held. */
function headerFor(roles: readonly Role[]): 'platform' | 'tenant' | 'employee' {
  if (roles.includes('platform-admin')) return 'platform';
  if (roles.includes('tenant-admin')) return 'tenant';
  return 'employee';
}

/** UI-002: the public landing page, loaded only for anonymous visitors (D8). */
const LandingContent = lazy(() => import('../landing/LandingContent'));

export function HomePage() {
  const { t } = useTranslation();
  const session = useSession();
  const retry = useSessionRetry();
  const { signOut } = useAuth();

  if (session.kind === 'anonymous') {
    return (
      <Suspense fallback={<Skeleton lines={4} />}>
        <LandingContent />
      </Suspense>
    );
  }
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
