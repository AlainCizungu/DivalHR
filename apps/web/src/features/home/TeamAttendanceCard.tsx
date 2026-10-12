import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { Icon } from '../../ui/Icon';
import { Card } from '../../ui/primitives';

/**
 * MVP-060B (B60B-8): the employee home's conditional task card. It reads the first item of the
 * caller's own team-attendance queue and appears only when there is one: no count, no names, no
 * detail. A non-manager (403), a failure or an empty queue shows nothing.
 */
export function TeamAttendanceCard() {
  const { t } = useTranslation();
  const { core } = useApi();
  const id = useId();
  const [shown, setShown] = useState(false);

  useEffect(() => {
    let active = true;
    core
      .GET('/me/team-attendance/actions', { params: { query: { limit: 1 } }, cache: 'no-store' })
      .then(
        ({ data }) => {
          if (active) setShown(Boolean(data && data.items.length > 0));
        },
        () => {
          if (active) setShown(false);
        },
      );
    return () => {
      active = false;
    };
  }, [core]);

  if (!shown) return null;
  return (
    <Card labelledBy={id} className="feature-card" testId="home-team-attendance">
      <span className="feature-card__icon">
        <Icon name="people" />
      </span>
      <div className="feature-card__body">
        <h2 id={id}>{t('home.employee.teamAttendance.title')}</h2>
        <p>{t('home.employee.teamAttendance.description')}</p>
        <Link to="/me/team-attendance" className="button">
          {t('home.employee.teamAttendance.action')}
          <Icon name="chevronRight" />
        </Link>
      </div>
    </Card>
  );
}
