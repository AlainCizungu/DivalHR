import type { IconName } from '../../ui/Icon';

/**
 * LAUNCH-004: the integration entries of the commercial page. A brand with a `logo` shows the
 * locally served mark from public/brands/ (sources and usage basis in docs/BRAND-ASSETS.md); a brand
 * without one is shown by its official name in a neutral tile. Each entry points at the launch-truth
 * capability that decides its status; a logo never implies a commercial relationship.
 */
export type IntegrationEntry =
  | { kind: 'generic'; key: string; capability: string; icon: IconName }
  | { kind: 'brand'; name: string; logo?: string; capability: string };

export const INTEGRATION_GROUPS: readonly { id: string; entries: readonly IntegrationEntry[] }[] = [
  {
    id: 'available',
    entries: [
      { kind: 'generic', key: 'webhooks', capability: 'webhooks', icon: 'network' },
      {
        kind: 'brand',
        name: 'WhatsApp Business',
        logo: 'whatsapp.svg',
        capability: 'whatsapp-notifications',
      },
    ],
  },
  {
    id: 'designed',
    entries: [
      { kind: 'generic', key: 'journal', capability: 'accounting-journal-export', icon: 'folder' },
    ],
  },
  {
    id: 'partner',
    entries: [
      { kind: 'generic', key: 'banks', capability: 'real-payment-delivery', icon: 'bank' },
      { kind: 'generic', key: 'mobileMoney', capability: 'real-payment-delivery', icon: 'phone' },
      {
        kind: 'brand',
        name: 'Airtel Money',
        logo: 'airtel.svg',
        capability: 'real-payment-delivery',
      },
      {
        kind: 'brand',
        name: 'Orange Money',
        logo: 'orange.svg',
        capability: 'real-payment-delivery',
      },
      { kind: 'brand', name: 'M-PESA', capability: 'real-payment-delivery' },
    ],
  },
  {
    id: 'planned',
    entries: [
      {
        kind: 'generic',
        key: 'accounting',
        capability: 'accounting-system-connectors',
        icon: 'folder',
      },
      { kind: 'brand', name: 'Sage', logo: 'sage.svg', capability: 'accounting-system-connectors' },
      {
        kind: 'brand',
        name: 'QuickBooks',
        logo: 'quickbooks.svg',
        capability: 'accounting-system-connectors',
      },
      { kind: 'brand', name: 'Odoo', logo: 'odoo.svg', capability: 'accounting-system-connectors' },
      { kind: 'brand', name: 'SAP', logo: 'sap.svg', capability: 'accounting-system-connectors' },
      { kind: 'brand', name: 'Oracle', capability: 'accounting-system-connectors' },
      { kind: 'generic', key: 'sso', capability: 'sso-federation', icon: 'key' },
    ],
  },
];
