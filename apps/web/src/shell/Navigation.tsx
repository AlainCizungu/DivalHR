import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { Icon } from '../ui/Icon';
import { BrandMark, Wordmark } from './controls';
import { NavGroup, Skeleton } from '../ui/primitives';
import type { NavGroupDef } from './navigation';

/**
 * The grouped navigation links, used by the sidebar and the mobile drawer (never both at once in
 * the accessibility tree). In rail mode the label stays in the link as visually hidden text and a
 * decorative tooltip repeats it on hover and focus.
 */
export function NavigationList({
  groups,
  activePath,
  loading,
  onNavigate,
}: {
  groups: NavGroupDef[];
  activePath: string | null;
  loading: boolean;
  onNavigate?: () => void;
}) {
  const { t } = useTranslation();
  return (
    <>
      {groups.map((group) => (
        <NavGroup key={group.id} label={t(group.labelKey)}>
          {group.items.map((item) => {
            const label = t(item.labelKey);
            return (
              <li key={item.to}>
                <Link
                  className="nav-link"
                  to={item.to}
                  aria-current={item.to === activePath ? 'page' : undefined}
                  onClick={onNavigate}
                >
                  <Icon name={item.icon} />
                  <span className="nav-link__label">{label}</span>
                  <span className="nav-link__tooltip" aria-hidden="true">
                    {label}
                  </span>
                </Link>
              </li>
            );
          })}
        </NavGroup>
      ))}
      {loading && <Skeleton lines={3} />}
    </>
  );
}

export function Brand() {
  const { t } = useTranslation();
  return (
    <Link className="brand" to="/">
      <BrandMark />
      <span className="brand__text">
        <span className="brand__name">
          <Wordmark />
        </span>
        <span className="brand__tagline">{t('shell.tagline')}</span>
      </span>
    </Link>
  );
}

export function Sidebar({
  groups,
  activePath,
  loading,
  collapsed,
  onToggle,
}: {
  groups: NavGroupDef[];
  activePath: string | null;
  loading: boolean;
  collapsed: boolean;
  onToggle: () => void;
}) {
  const { t } = useTranslation();
  const toggleLabel = collapsed ? t('shell.expandNavigation') : t('shell.collapseNavigation');
  return (
    <div className="sidebar" data-collapsed={collapsed}>
      <div className="sidebar__inner">
        <Brand />
        <nav className="sidebar__nav" aria-label={t('nav.primary')} id="primary-navigation">
          <NavigationList groups={groups} activePath={activePath} loading={loading} />
        </nav>
        <div className="sidebar__footer">
          <button
            type="button"
            className="sidebar__toggle"
            aria-expanded={!collapsed}
            aria-controls="primary-navigation"
            onClick={onToggle}
          >
            <Icon name={collapsed ? 'expand' : 'collapse'} />
            <span className="sidebar__toggle-label">{toggleLabel}</span>
          </button>
        </div>
      </div>
    </div>
  );
}
