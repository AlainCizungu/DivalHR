import '@divalhr/design-system/tokens.css';
import './styles.css';
import './ui/ui.css';
import './shell/shell.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';
import { PwaPrompt } from './app/PwaPrompt';
import { createUserManager } from './auth/oidc';
import { getRuntimeConfig } from './config/runtime';
import { publicOriginRedirect } from './features/commercial/origins';
import { initI18n } from './i18n';

const config = getRuntimeConfig();
const userManager = createUserManager(config);

// LAUNCH-002: the public commercial origin serves the commercial pages only.
const elsewhere = publicOriginRedirect(window.location);

if (elsewhere) {
  window.location.replace(elsewhere);
} else {
  void start();
}

async function start() {
  await initI18n();
  const root = document.getElementById('root');
  if (!root) throw new Error('Root element missing');
  createRoot(root).render(
    <StrictMode>
      <App config={config} userManager={userManager} />
      <PwaPrompt />
    </StrictMode>,
  );
}
