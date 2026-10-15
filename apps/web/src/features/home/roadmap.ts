import type { IconName } from '../../ui/Icon';

/**
 * UI-001 (D7): capabilities on the DivalHR roadmap, shown on the homes as "Coming next" tiles.
 * Static text only: no link, button, date or availability promise. UI-003 (UI3-4, UI3-5) removed
 * what had shipped: leave, scheduling and contract expiration, and the employee's leave. UI-004
 * (U4-1) removed attendance and timesheets (MVP-060A to MVP-070E) and names what comes next:
 * period locking, payroll preparation and calculations, and salary disbursement.
 */
export interface RoadmapItem {
  id: string;
  icon: IconName;
}

export const TENANT_ADMIN_ROADMAP: RoadmapItem[] = [
  { id: 'periodLocking', icon: 'shield' },
  { id: 'payrollInputs', icon: 'payroll' },
  { id: 'disbursement', icon: 'bank' },
];

export const EMPLOYEE_ROADMAP: RoadmapItem[] = [
  { id: 'myDocuments', icon: 'folder' },
  { id: 'benefits', icon: 'benefits' },
  { id: 'payslips', icon: 'payroll' },
];
