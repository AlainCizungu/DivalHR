import type { IconName } from '../../ui/Icon';

/**
 * UI-002 (Issue #63, UI2-1, UI2-2) refreshed by MVP-093A (Issue #175, D93-14): the single typed
 * catalogue behind the public landing page. A module is reviewed against the product and carries
 * one of three truthful states, shown as « Disponible maintenant » / "Available now",
 * « Fonctionnalités disponibles » / "Capabilities available" and « Prochainement » / "Coming next":
 *
 * - `available`: the module's scope is usable by signed-in users today;
 * - `capabilities`: some capabilities are usable today and the rest of the vision module is not
 *   built, so the card also lists what remains upcoming (never the whole module claimed);
 * - `later`: nothing is built.
 */
export type Availability = 'available' | 'capabilities' | 'later';

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
  // Policies, requests, approvals, amendments, cancellations, withdrawals, routing exceptions and
  // substitute approvers (MVP-040 to MVP-041G). Balances and team calendars are not built.
  { id: 'leave', icon: 'calendar', status: 'available', vision: true },
  // Period locking, payroll-input generation, variance review, locked export (MVP-071 to MVP-092)
  // and monthly compensation records (MVP-093A). Calculation, statutory rules, payslips and
  // payment are not built.
  { id: 'payroll', icon: 'payroll', status: 'capabilities', vision: true },
  { id: 'loans', icon: 'bank', status: 'later', vision: true },
  { id: 'benefits', icon: 'benefits', status: 'later', vision: true },
  { id: 'performance', icon: 'target', status: 'later', vision: true },
  // Contract templates, issuing, acknowledgement and expiration tracking (MVP-030, MVP-031A);
  // general document storage and e-signature are not built.
  { id: 'documents', icon: 'folder', status: 'capabilities', vision: true },
  // Today's operational status and the governed daily briefing (MVP-100, MVP-101); general
  // workforce analytics and trends are not built.
  { id: 'analytics', icon: 'chart', status: 'capabilities', vision: true },
  // Task lifecycle and closed approval workflows (MVP-080, leave, time, expenses); configurable
  // workflow automation is not built.
  { id: 'workflow', icon: 'workflow', status: 'capabilities', vision: true },
  // Available only as the governed, grounded daily briefing (MVP-101); never a general assistant.
  { id: 'ai', icon: 'spark', status: 'available', vision: true },
  // Signed webhooks (MVP-110) and the WhatsApp Business connector (MVP-111); no partnership.
  { id: 'integrations', icon: 'network', status: 'capabilities', vision: true },
  // Native iOS and Android applications are roadmap items.
  { id: 'mobile', icon: 'phone', status: 'later', vision: true },
  // Shipped capabilities shown in addition to the vision modules (MVP-002, MVP-030, MVP-011/012,
  // MVP-050A/B, MVP-060A to MVP-062, MVP-070A to MVP-070E, MVP-080, MVP-081). Work scheduling,
  // attendance, time, tasks and expenses are listed here, never as a thirteenth vision module.
  { id: 'organization', icon: 'structure', status: 'available', vision: false },
  { id: 'scheduling', icon: 'rotation', status: 'available', vision: false },
  { id: 'attendance', icon: 'clock', status: 'available', vision: false },
  { id: 'time', icon: 'check', status: 'available', vision: false },
  { id: 'tasks', icon: 'check', status: 'available', vision: false },
  { id: 'expenses', icon: 'payroll', status: 'available', vision: false },
  { id: 'contracts', icon: 'contract', status: 'available', vision: false },
  { id: 'access', icon: 'shield', status: 'available', vision: false },
];

export const VISION_MODULES = MODULES.filter((module) => module.vision);
export const AVAILABLE_MODULES = MODULES.filter((module) => module.status === 'available');

/**
 * MVP-093A (D93-14): the two plain lists. "Available now" names, in grouped concise copy, only
 * what signed-in users can do through MVP-093A; "Coming next" names what is on the roadmap and not
 * built. Both are plain text with no date and no control.
 */
export const AVAILABLE_NOW = [
  'people',
  'contracts',
  'leave',
  'scheduling',
  'timesheets',
  'payrollPreparation',
  'compensation',
  'operations',
  'integrations',
  'tasks',
  'expenses',
  'languages',
] as const;

export const COMING_NEXT = [
  'payrollCalculation',
  'statutory',
  'payslips',
  'disbursement',
  'advances',
  'platform',
] as const;

/**
 * Integration entries. A brand entry with a `logo` shows the locally served mark from
 * `public/brands/` (sources in docs/BRAND-ASSETS.md); a brand without one is a documented
 * exception and shows its official name in a neutral tile. Generic entries are standards or
 * methods, not brands, and use an interface icon with a localized label.
 */
export type IntegrationState = 'capabilities' | 'later';

export type IntegrationEntry =
  | { kind: 'brand'; name: string; logo?: string; state: IntegrationState }
  | { kind: 'generic'; labelKey: string; icon: IconName; state: IntegrationState };

export interface IntegrationGroup {
  id: 'payments' | 'accounting' | 'work' | 'identity';
  entries: readonly IntegrationEntry[];
}

export const INTEGRATION_GROUPS: readonly IntegrationGroup[] = [
  {
    id: 'payments',
    entries: [
      { kind: 'brand', name: 'M-PESA', state: 'later' },
      { kind: 'brand', name: 'Airtel Money', logo: 'airtel.svg', state: 'later' },
      { kind: 'brand', name: 'Orange Money', logo: 'orange.svg', state: 'later' },
      { kind: 'generic', labelKey: 'bankTransfer', icon: 'bank', state: 'later' },
      { kind: 'generic', labelKey: 'isoFiles', icon: 'folder', state: 'later' },
    ],
  },
  {
    id: 'accounting',
    entries: [
      { kind: 'brand', name: 'Sage', logo: 'sage.svg', state: 'later' },
      { kind: 'brand', name: 'QuickBooks', logo: 'quickbooks.svg', state: 'later' },
      { kind: 'brand', name: 'Odoo', logo: 'odoo.svg', state: 'later' },
      { kind: 'brand', name: 'SAP', logo: 'sap.svg', state: 'later' },
      { kind: 'brand', name: 'Oracle', state: 'later' },
    ],
  },
  {
    id: 'work',
    entries: [
      { kind: 'brand', name: 'Microsoft Teams', state: 'later' },
      { kind: 'brand', name: 'WhatsApp Business', logo: 'whatsapp.svg', state: 'capabilities' },
      { kind: 'brand', name: 'Slack', state: 'later' },
      { kind: 'brand', name: 'Microsoft 365', state: 'later' },
      { kind: 'brand', name: 'Google Workspace', state: 'later' },
    ],
  },
  {
    id: 'identity',
    entries: [
      { kind: 'brand', name: 'Microsoft Entra ID', state: 'later' },
      { kind: 'brand', name: 'Keycloak', logo: 'keycloak.svg', state: 'later' },
      { kind: 'generic', labelKey: 'restApi', icon: 'code', state: 'capabilities' },
      { kind: 'generic', labelKey: 'webhooks', icon: 'network', state: 'capabilities' },
      { kind: 'generic', labelKey: 'sso', icon: 'key', state: 'later' },
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
  /** MVP-093A: when set, the step shows its own truthful state (Available now or Coming next). */
  state?: 'available' | 'later';
}

export const PLATFORM_STEPS: readonly StepDef[] = [
  { key: 'people', icon: 'user' },
  { key: 'operations', icon: 'clock' },
  { key: 'pay', icon: 'payroll' },
  { key: 'intelligence', icon: 'spark' },
];

/** MVP-093A (D93-14): preparation and compensation records are active; the rest is planned. */
export const PAYROLL_STEPS: readonly StepDef[] = [
  { key: 'preparation', icon: 'check', state: 'available' },
  { key: 'compensation', icon: 'payroll', state: 'available' },
  { key: 'calculation', icon: 'workflow', state: 'later' },
  { key: 'payment', icon: 'phone', state: 'later' },
];

export const FINANCE_STEPS: readonly StepDef[] = [
  { key: 'consent', icon: 'user' },
  { key: 'verified', icon: 'check' },
  { key: 'partner', icon: 'handshake' },
  { key: 'repayment', icon: 'payroll' },
];

/**
 * UI-003 (UI3-3), refreshed by UI-004 (U4-2): the illustrative administrator home shows the
 * shipped work areas as the application's navigation names them (People and Operations).
 */
export const PREVIEW_NAV: readonly StepDef[] = [
  { key: 'home', icon: 'home' },
  { key: 'employees', icon: 'people' },
  { key: 'leave', icon: 'calendar' },
  { key: 'schedules', icon: 'rotation' },
  { key: 'attendance', icon: 'clock' },
  { key: 'timesheets', icon: 'check' },
  { key: 'exports', icon: 'payroll' },
  { key: 'tasks', icon: 'check' },
  { key: 'expenses', icon: 'payroll' },
];

export const PREVIEW_ACTIONS = ['import', 'reviewLeave', 'openTimesheets', 'exportTime'] as const;

export const PREVIEW_CARDS: readonly StepDef[] = [
  { key: 'employees', icon: 'people' },
  { key: 'attendance', icon: 'clock' },
  { key: 'timesheets', icon: 'check' },
  { key: 'exports', icon: 'payroll' },
  { key: 'tasks', icon: 'check' },
  { key: 'expenses', icon: 'payroll' },
];

/** MVP-093A: the governed daily briefing only; no free-form chat, no employee-level conclusion. */
export const AI_GUARANTEES = ['metrics', 'templates', 'sources', 'fallback'] as const;
export const AI_TOPICS = ['shifts', 'coverage', 'approvals'] as const;
export const PRINCIPLES = ['languages', 'currency', 'offline', 'multisite'] as const;
export const OUTCOMES = ['hr', 'operations', 'finance', 'employees'] as const;
