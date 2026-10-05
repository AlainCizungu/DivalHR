import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { Icon, type IconName } from './Icon';

/**
 * UI-001 primitives. Only the ones the shell, the role homes and the minimal wrapper adjustments
 * use (architect amendment UI1-3); tables, action menus, form sections, confirmation dialogs and
 * success feedback arrive with the first feature redesign that needs them.
 */

export function PageHeader({
  eyebrow,
  title,
  description,
  titleId,
  actions,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  titleId?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="page-header">
      <div className="page-header__text">
        {eyebrow && <p className="page-header__eyebrow">{eyebrow}</p>}
        <h1 id={titleId} className="page-header__title">
          {title}
        </h1>
        {description && <p className="page-header__description">{description}</p>}
      </div>
      {actions && <div className="page-header__actions">{actions}</div>}
    </div>
  );
}

export interface Crumb {
  label: string;
  /** Pages have a link; group labels and the current page do not. */
  to?: string;
}

export function Breadcrumbs({ items }: { items: Crumb[] }) {
  const { t } = useTranslation();
  if (items.length === 0) return null;
  return (
    <nav className="breadcrumbs" aria-label={t('shell.breadcrumb')}>
      <ol>
        {items.map((item, index) => {
          const current = index === items.length - 1;
          return (
            <li key={`${String(index)}-${item.label}`}>
              {current ? (
                <span aria-current="page">{item.label}</span>
              ) : item.to ? (
                <Link to={item.to}>{item.label}</Link>
              ) : (
                <span>{item.label}</span>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/** A labelled group of navigation links. */
export function NavGroup({ label, children }: { label: string; children: ReactNode }) {
  const id = useId();
  return (
    <div className="nav-group" role="group" aria-labelledby={id}>
      <p className="nav-group__label" id={id}>
        {label}
      </p>
      <ul className="nav-group__list">{children}</ul>
    </div>
  );
}

/** A card whose whole surface opens one destination; the title link is its only control. */
export function LinkCard({
  to,
  icon,
  title,
  description,
  more,
  testId,
}: {
  to: string;
  icon: IconName;
  title: string;
  description: string;
  more: string;
  testId?: string;
}) {
  return (
    <li className="link-card" data-testid={testId}>
      <span className="link-card__icon">
        <Icon name={icon} />
      </span>
      <h3 className="link-card__title">
        <Link to={to}>{title}</Link>
      </h3>
      <p className="link-card__description">{description}</p>
      <span className="link-card__more" aria-hidden="true">
        {more} <Icon name="chevronRight" />
      </span>
    </li>
  );
}

export function Card({
  children,
  labelledBy,
  className,
  testId,
}: {
  children: ReactNode;
  labelledBy?: string;
  className?: string;
  testId?: string;
}) {
  return (
    <section
      className={className ? `surface-card ${className}` : 'surface-card'}
      aria-labelledby={labelledBy}
      data-testid={testId}
    >
      {children}
    </section>
  );
}

export type StatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

/** Status text with a tone. The text always carries the meaning; colour only reinforces it. */
export function StatusBadge({
  tone,
  icon,
  children,
  testId,
}: {
  tone: StatusTone;
  icon?: IconName;
  children: ReactNode;
  testId?: string;
}) {
  return (
    <span className={`status-badge status-badge--${tone}`} data-testid={testId}>
      {icon && <Icon name={icon} />}
      {children}
    </span>
  );
}

export function EmptyState({
  icon,
  title,
  titleLevel = 2,
  description,
  action,
  testId,
}: {
  icon: IconName;
  title: string;
  titleLevel?: 1 | 2;
  description?: string;
  action?: ReactNode;
  testId?: string;
}) {
  const Heading = titleLevel === 1 ? 'h1' : 'h2';
  return (
    <div className="empty-state" data-testid={testId}>
      <span className="empty-state__icon">
        <Icon name={icon} />
      </span>
      <Heading className="empty-state__title">{title}</Heading>
      {description && <p className="empty-state__description">{description}</p>}
      {action && <div className="empty-state__action">{action}</div>}
    </div>
  );
}

/** Decorative placeholder blocks; the caller provides one text status for assistive technology. */
export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="skeleton" aria-hidden="true">
      {Array.from({ length: lines }, (_, index) => (
        <span key={index} className="skeleton__line" />
      ))}
    </div>
  );
}

/**
 * An error or refusal message announced as an alert, with an optional next step. Existing
 * features pass their own test id so wrapped pages keep their identifiers (UI1-3).
 */
export function ErrorPanel({
  message,
  title,
  action,
  testId,
}: {
  message: string;
  title?: string;
  action?: ReactNode;
  testId?: string;
}) {
  return (
    <div className="error-panel">
      <Icon name="alert" className="error-panel__icon" />
      <div className="error-panel__body">
        {title && <p className="error-panel__title">{title}</p>}
        <p role="alert" data-testid={testId} className="error-panel__message">
          {message}
        </p>
        {action && <div className="error-panel__action">{action}</div>}
      </div>
    </div>
  );
}
