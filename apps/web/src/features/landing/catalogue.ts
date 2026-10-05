import type { IconName } from '../../ui/Icon';

/**
 * UI-002 (Issue #63, UI2-1, UI2-2): the single typed catalogue behind the public landing page.
 * Availability is reviewed against the product: a module is `available` only when signed-in users
 * can use it today. Everything else is `later` and is shown as "Coming later" / « À venir ».
 */
export type Availability = 'available' | 'later';

export interface ModuleEntry {
  id: string;
  icon: IconName;
  status: Availability;
  /** One of the twelve vision modules approved by the owner (UI2-1), in their approved order. */
  vision: boolean;
}

export const MODULES: readonly ModuleEntry[] = [
  // Records, employment history (MVP-021), bulk import (MVP-020) and separation (MVP-022).
  { id: 'employees', icon: 'people', status: 'available', vision: true },
  { id: 'leave', icon: 'calendar', status: 'later', vision: true },
  { id: 'payroll', icon: 'payroll', status: 'later', vision: true },
  { id: 'loans', icon: 'bank', status: 'later', vision: true },
  { id: 'benefits', icon: 'benefits', status: 'later', vision: true },
  { id: 'performance', icon: 'target', status: 'later', vision: true },
  // Contracts can be issued and acknowledged (MVP-030); the general document module is not built.
  { id: 'documents', icon: 'folder', status: 'later', vision: true },
  { id: 'analytics', icon: 'chart', status: 'later', vision: true },
  { id: 'workflow', icon: 'workflow', status: 'later', vision: true },
  { id: 'ai', icon: 'spark', status: 'later', vision: true },
  { id: 'integrations', icon: 'network', status: 'later', vision: true },
  // Native iOS and Android applications are roadmap items.
  { id: 'mobile', icon: 'phone', status: 'later', vision: true },
  // Shipped capabilities shown in addition to the vision modules (MVP-002, MVP-030, MVP-011/012).
  { id: 'organization', icon: 'structure', status: 'available', vision: false },
  { id: 'contracts', icon: 'contract', status: 'available', vision: false },
  { id: 'access', icon: 'shield', status: 'available', vision: false },
];

export const VISION_MODULES = MODULES.filter((module) => module.vision);
export const AVAILABLE_MODULES = MODULES.filter((module) => module.status === 'available');

/**
 * Integration entries. A brand entry with a `logo` shows the locally served mark from
 * `public/brands/` (sources in docs/BRAND-ASSETS.md); a brand without one is a documented
 * exception and shows its official name in a neutral tile. Generic entries are standards or
 * methods, not brands, and use an interface icon with a localized label.
 */
export type IntegrationEntry =
  | { kind: 'brand'; name: string; logo?: string }
  | { kind: 'generic'; labelKey: string; icon: IconName };

export interface IntegrationGroup {
  id: 'payments' | 'accounting' | 'work' | 'identity';
  entries: readonly IntegrationEntry[];
}

export const INTEGRATION_GROUPS: readonly IntegrationGroup[] = [
  {
    id: 'payments',
    entries: [
      { kind: 'brand', name: 'M-PESA' },
      { kind: 'brand', name: 'Airtel Money', logo: 'airtel.svg' },
      { kind: 'brand', name: 'Orange Money', logo: 'orange.svg' },
      { kind: 'generic', labelKey: 'bankTransfer', icon: 'bank' },
      { kind: 'generic', labelKey: 'isoFiles', icon: 'folder' },
    ],
  },
  {
    id: 'accounting',
    entries: [
      { kind: 'brand', name: 'Sage', logo: 'sage.svg' },
      { kind: 'brand', name: 'QuickBooks', logo: 'quickbooks.svg' },
      { kind: 'brand', name: 'Odoo', logo: 'odoo.svg' },
      { kind: 'brand', name: 'SAP', logo: 'sap.svg' },
      { kind: 'brand', name: 'Oracle' },
    ],
  },
  {
    id: 'work',
    entries: [
      { kind: 'brand', name: 'Microsoft Teams' },
      { kind: 'brand', name: 'WhatsApp Business', logo: 'whatsapp.svg' },
      { kind: 'brand', name: 'Slack' },
      { kind: 'brand', name: 'Microsoft 365' },
      { kind: 'brand', name: 'Google Workspace' },
    ],
  },
  {
    id: 'identity',
    entries: [
      { kind: 'brand', name: 'Microsoft Entra ID' },
      { kind: 'brand', name: 'Keycloak', logo: 'keycloak.svg' },
      { kind: 'generic', labelKey: 'restApi', icon: 'code' },
      { kind: 'generic', labelKey: 'webhooks', icon: 'network' },
      { kind: 'generic', labelKey: 'sso', icon: 'key' },
    ],
  },
];

/** Planned payout rails (UI2-5): brand names are not translated. */
export const RAILS = ['M-PESA', 'Airtel Money', 'Orange Money'] as const;

/** Approved demo contact (UI2-5, D6). */
export const CONTACT_EMAIL = 'contact@dival.ai';

export function demoMailto(subject: string): string {
  return `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(subject)}`;
}

/** In-page sections reachable from the header navigation. */
export const SECTIONS = ['platform', 'payroll', 'integrations', 'why'] as const;
export type SectionId = (typeof SECTIONS)[number];

/** Copy keys of the static sections, kept here so the components hold no literal strings. */
export const SECTORS: readonly { key: string; icon: IconName }[] = [
  { key: 'healthcare', icon: 'pulse' },
  { key: 'education', icon: 'school' },
  { key: 'ngos', icon: 'globe' },
  { key: 'financial', icon: 'bank' },
  { key: 'manufacturing', icon: 'factory' },
];

export interface StepDef {
  key: string;
  icon: IconName;
}

export const PLATFORM_STEPS: readonly StepDef[] = [
  { key: 'people', icon: 'user' },
  { key: 'operations', icon: 'clock' },
  { key: 'pay', icon: 'payroll' },
  { key: 'intelligence', icon: 'spark' },
];

export const PAYROLL_STEPS: readonly StepDef[] = [
  { key: 'approved', icon: 'check' },
  { key: 'instructions', icon: 'workflow' },
  { key: 'rails', icon: 'phone' },
  { key: 'reconciliation', icon: 'chart' },
];

export const FINANCE_STEPS: readonly StepDef[] = [
  { key: 'consent', icon: 'user' },
  { key: 'verified', icon: 'check' },
  { key: 'partner', icon: 'handshake' },
  { key: 'repayment', icon: 'payroll' },
];

export const PREVIEW_NAV: readonly StepDef[] = [
  { key: 'home', icon: 'home' },
  { key: 'employees', icon: 'people' },
  { key: 'organization', icon: 'structure' },
  { key: 'contracts', icon: 'contract' },
  { key: 'access', icon: 'shield' },
];

export const PREVIEW_ACTIONS = ['import', 'invite', 'template'] as const;

export const PREVIEW_CARDS: readonly StepDef[] = [
  { key: 'employees', icon: 'people' },
  { key: 'organization', icon: 'structure' },
  { key: 'contracts', icon: 'contract' },
];

export const AI_GUARANTEES = ['sources', 'authorization', 'approval'] as const;
export const AI_EXAMPLES = ['staffing', 'approvals', 'contracts'] as const;
export const PRINCIPLES = ['languages', 'currency', 'offline', 'multisite'] as const;
export const OUTCOMES = ['hr', 'operations', 'finance', 'employees'] as const;
