import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { Icon } from '../../ui/Icon';

/**
 * LAUNCH-004 (Issue #228): the privacy notice and terms of the controlled pilot, labelled as pilot
 * documents pending final counsel review. They exist so that no footer link is dead; they make no
 * legal claim and create no contract.
 */
const ITEMS = {
  privacy: ['collect', 'email', 'product', 'partners'],
  terms: ['status', 'scope', 'labels', 'partners', 'advice'],
} as const;

export function LegalPage({ kind }: { kind: 'privacy' | 'terms' }) {
  const { t } = useTranslation();
  return (
    <section
      className="commercial-section commercial-legal"
      aria-labelledby="commercial-legal-title"
    >
      <div className="commercial-container commercial-legal__body">
        <h1 id="commercial-legal-title">{t(`commercial.legal.${kind}.title`)}</h1>
        <p className="commercial-lead">{t(`commercial.legal.${kind}.intro`)}</p>
        <p className="commercial-disclosure" data-testid="commercial-legal-notice">
          <Icon name="info" />
          {t('commercial.legal.pilotNotice')}
        </p>
        <ul className="commercial-statements">
          {ITEMS[kind].map((key) => (
            <li key={key}>
              <Icon name="check" />
              {t(`commercial.legal.${kind}.items.${key}`)}
            </li>
          ))}
        </ul>
        <p>
          <Link className="button button--secondary" to="/">
            {t('commercial.legal.back')}
          </Link>
        </p>
      </div>
    </section>
  );
}
