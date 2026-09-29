import { createAiServiceStatusClient, createCoreApiClient } from '@divalhr/api-client';
import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthProvider';
import type { RuntimeConfig } from '../config/runtime';

interface Clients {
  core: ReturnType<typeof createCoreApiClient>;
  aiStatus: ReturnType<typeof createAiServiceStatusClient>;
}

const ApiContext = createContext<Clients | null>(null);

export function ApiProvider({ config, children }: { config: RuntimeConfig; children: ReactNode }) {
  const { getAccessToken } = useAuth();
  const clients = useMemo<Clients>(
    () => ({
      core: createCoreApiClient({ baseUrl: config.coreApiUrl, getAccessToken }),
      aiStatus: createAiServiceStatusClient({ baseUrl: config.aiServiceUrl }),
    }),
    [config.coreApiUrl, config.aiServiceUrl, getAccessToken],
  );
  return <ApiContext value={clients}>{children}</ApiContext>;
}

export function useApi(): Clients {
  const context = useContext(ApiContext);
  if (!context) throw new Error('useApi must be used inside ApiProvider');
  return context;
}
