import { useTranslation } from 'react-i18next';
import { useRegisterSW } from 'virtual:pwa-register/react';

export function PwaPrompt() {
  const { t } = useTranslation();
  const {
    offlineReady: [offlineReady, setOfflineReady],
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW();
  if (!offlineReady && !needRefresh) return null;
  const close = () => {
    setOfflineReady(false);
    setNeedRefresh(false);
  };
  return (
    <div className="toast" role="status">
      <p>{needRefresh ? t('pwa.updateAvailable') : t('pwa.offlineReady')}</p>
      {needRefresh && (
        <button type="button" className="button" onClick={() => void updateServiceWorker(true)}>
          {t('pwa.reload')}
        </button>
      )}
      <button type="button" className="button button--secondary" onClick={close}>
        {t('pwa.dismiss')}
      </button>
    </div>
  );
}
