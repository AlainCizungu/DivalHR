import type { IconName } from '../../ui/Icon';

/**
 * UI-001 (D7): capabilities on the DivalHR roadmap, shown on the homes as "Coming later" tiles.
 * Static text only: no link, button, date or availability promise.
 */
export interface RoadmapItem {
  id: string;
  icon: IconName;
}

export const TENANT_ADMIN_ROADMAP: RoadmapItem[] = [
  { id: 'leave', icon: 'calendar' },
  { id: 'attendance', icon: 'clock' },
  { id: 'scheduling', icon: 'rotation' },
  { id: 'documentExpiry', icon: 'expiry' },
  { id: 'payrollInputs', icon: 'payroll' },
  { id: 'expenses', icon: 'expenses' },
];

export const EMPLOYEE_ROADMAP: RoadmapItem[] = [
  { id: 'myLeave', icon: 'calendar' },
  { id: 'myDocuments', icon: 'folder' },
  { id: 'benefits', icon: 'benefits' },
  { id: 'payslips', icon: 'payroll' },
];
