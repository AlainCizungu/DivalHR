import type { IconName } from '../../ui/Icon';

/**
 * UI-001 (D7): capabilities on the DivalHR roadmap, shown on the homes as "Coming later" tiles.
 * Static text only: no link, button, date or availability promise. UI-003 (UI3-4, UI3-5) removed
 * what has shipped since: leave, scheduling and contract expiration, and the employee's leave.
 */
export interface RoadmapItem {
  id: string;
  icon: IconName;
}

export const TENANT_ADMIN_ROADMAP: RoadmapItem[] = [
  { id: 'attendance', icon: 'clock' },
  { id: 'payrollInputs', icon: 'payroll' },
  { id: 'expenses', icon: 'expenses' },
];

export const EMPLOYEE_ROADMAP: RoadmapItem[] = [
  { id: 'myDocuments', icon: 'folder' },
  { id: 'benefits', icon: 'benefits' },
  { id: 'payslips', icon: 'payroll' },
];
