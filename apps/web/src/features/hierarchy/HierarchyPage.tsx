import type { LegalEntity, Site } from '@divalhr/api-client';
import { useCallback, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { usePeriodText } from './HierarchyFields';
import { LegalEntityForm } from './HierarchyForms';
import { PagedList, usePagedList } from './pagedList';
import { SiteUnitsOf } from './SiteUnitsSection';
import { SitesOf } from './SitesSection';

export function HierarchyPage() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const periodText = usePeriodText();
  // The single polite live region for this page: every announcement goes here exactly once.
  const [announcement, setAnnouncement] = useState('');
  const [selected, setSelected] = useState<LegalEntity | null>(null);
  const [selectedSite, setSelectedSite] = useState<Site | null>(null);
  const sitesHeading = useRef<HTMLHeadingElement>(null);
  const unitsHeading = useRef<HTMLHeadingElement>(null);

  const fetchLegalEntities = useCallback(
    (cursor?: string) => core.GET('/legal-entities', { params: { query: { cursor } } }),
    [core],
  );
  const legalEntities = usePagedList<LegalEntity>(fetchLegalEntities);

  const selectEntity = (entity: LegalEntity) => {
    setSelected(entity);
    // A site belongs to exactly one legal entity: a new legal entity clears the site selection.
    if (entity.id !== selected?.id) setSelectedSite(null);
    // Moves the reading position to the sites section; the heading is focusable only
    // programmatically (tabIndex -1), so Tab continues normally from there.
    requestAnimationFrame(() => sitesHeading.current?.focus());
  };

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('hierarchy.title')}</h1>
      <p className="muted">{t('hierarchy.description')}</p>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      <section aria-labelledby={`${ids}-le`} className="card">
        <h2 id={`${ids}-le`}>{t('hierarchy.legalEntities.title')}</h2>
        <PagedList
          list={legalEntities}
          emptyKey="hierarchy.legalEntities.empty"
          loadMoreKey="hierarchy.legalEntities.loadMore"
          testId="legal-entity-list"
          render={(entity, ref) => (
            <>
              <span className="hierarchy-item__main">
                <strong>{entity.name}</strong> <span className="badge">{entity.code}</span>
              </span>
              <span className="muted">
                {t(`country.${entity.countryCode}`)} ·{' '}
                {periodText(entity.effectiveFrom, entity.effectiveTo)}
              </span>
              <button
                ref={ref}
                type="button"
                className="button button--secondary"
                aria-pressed={selected?.id === entity.id}
                aria-label={t('hierarchy.legalEntities.select', {
                  name: entity.name,
                  code: entity.code,
                })}
                onClick={() => {
                  selectEntity(entity);
                }}
              >
                {t('hierarchy.legalEntities.selectShort')}
              </button>
            </>
          )}
        />
        <LegalEntityForm
          onCreated={(entity) => {
            legalEntities.prepend(entity);
            setAnnouncement(
              t('hierarchy.legalEntities.created', { name: entity.name, code: entity.code }),
            );
          }}
        />
      </section>

      {selected && (
        <section aria-labelledby={`${ids}-sites`} className="card" data-testid="sites-section">
          <h2 id={`${ids}-sites`} ref={sitesHeading} tabIndex={-1}>
            {t('hierarchy.sites.title', { name: selected.name, code: selected.code })}
          </h2>
          <SitesOf
            key={selected.id}
            parent={selected}
            selectedSiteId={selectedSite?.id}
            onSelectSite={(site) => {
              setSelectedSite(site);
              // Same focus rule as for legal entities: programmatic, never a trap.
              requestAnimationFrame(() => unitsHeading.current?.focus());
            }}
            onAnnounce={setAnnouncement}
          />
        </section>
      )}

      {selected && selectedSite && (
        <section aria-labelledby={`${ids}-units`} className="card" data-testid="site-units-section">
          <h2 id={`${ids}-units`} ref={unitsHeading} tabIndex={-1}>
            {t('hierarchy.siteUnits.title', { name: selectedSite.name, code: selectedSite.code })}
          </h2>
          <SiteUnitsOf key={selectedSite.id} site={selectedSite} onAnnounce={setAnnouncement} />
        </section>
      )}
    </section>
  );
}
