import type { IconName } from '../../ui/Icon';
import type { StatusTone } from '../../ui/primitives';
import source from './launch-truth.json';

/**
 * LAUNCH-004 (Issue #228): the typed view of the single launch-truth source. Every capability the
 * commercial page names comes from here, so the public wording, the status and the Markdown files
 * in docs/ cannot drift apart (scripts/ops/commercial-truth.test.mjs enforces it).
 */
export const CAPABILITY_STATUSES = [
  'PILOT_AVAILABLE',
  'CONFIGURATION_REQUIRED',
  'PARTNER_REQUIRED',
  'PLANNED',
] as const;
export type CapabilityStatus = (typeof CAPABILITY_STATUSES)[number];
export type Lang = 'en' | 'fr';

export interface Capability {
  id: string;
  name: Record<Lang, string>;
  status: CapabilityStatus;
  en: string;
  fr: string;
}

interface TruthSource {
  statuses: Record<CapabilityStatus, Record<Lang, string>>;
  pillars: { id: string; capabilities: string[] }[];
  sections: Record<string, string[]>;
  capabilities: Capability[];
}

const TRUTH = source as unknown as TruthSource;
const BY_ID = new Map(TRUTH.capabilities.map((capability) => [capability.id, capability]));

export const PILLARS = TRUTH.pillars;
export const SECTION_CAPABILITIES = TRUTH.sections;

export function capabilityOf(id: string): Capability {
  const capability = BY_ID.get(id);
  if (!capability) throw new Error(`Unknown commercial capability: ${id}`);
  return capability;
}

export function langOf(resolved: string | undefined): Lang {
  return resolved === 'fr' ? 'fr' : 'en';
}

/** Status presentation. The label text always carries the meaning; tone and icon only reinforce it. */
export const STATUS_STYLE: Record<CapabilityStatus, { tone: StatusTone; icon: IconName }> = {
  PILOT_AVAILABLE: { tone: 'success', icon: 'check' },
  CONFIGURATION_REQUIRED: { tone: 'info', icon: 'workflow' },
  PARTNER_REQUIRED: { tone: 'warning', icon: 'handshake' },
  PLANNED: { tone: 'neutral', icon: 'clock' },
};
