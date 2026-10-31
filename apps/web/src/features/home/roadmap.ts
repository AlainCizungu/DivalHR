import type { IconName } from '../../ui/Icon';

/**
 * UI-001 (D7): capabilities on the DivalHR roadmap, shown on the homes as "Coming next" tiles.
 * Static text only: no link, button, date or availability promise. UI-003 (UI3-4, UI3-5) removed
 * what had shipped: leave, scheduling and contract expiration, and the employee's leave. UI-004
 * (U4-1) removed attendance and timesheets (MVP-060A to MVP-070E). MVP-093A (Issue #175, D93-15)
 * removed period locking and payroll inputs (shipped through MVP-092) and names what comes next
 * for an administrator: statutory calculation, payslips and net-pay approval, disbursement and
 * reconciliation, and salary advances. Monthly compensation records are shipped, not roadmap.
 * MVP-094B (Issue #179, D94B-13) keeps statutory calculation here: the repository ships only a
 * DRAFT rule pack, so the readiness state ("Professional verification required") is shown only
 * inside the administrator's payroll-run workflow, never as an available capability.
 * MVP-094C (Issue #181, D94C-14): payroll approval and bilingual payslips exist but work only on a
 * professionally verified statutory pack; the real DRC pack is DRAFT, so "operational DRC payslips"
 * stays here as pilot and readiness, with filing, accounting posting, disbursement and salary
 * advances still next.
 */
export interface RoadmapItem {
  id: string;
  icon: IconName;
}

export const TENANT_ADMIN_ROADMAP: RoadmapItem[] = [
  { id: 'statutoryCalculation', icon: 'payroll' },
  { id: 'payslipApproval', icon: 'contract' },
  { id: 'filingAccounting', icon: 'folder' },
  { id: 'disbursement', icon: 'bank' },
  { id: 'salaryAdvances', icon: 'benefits' },
];

export const EMPLOYEE_ROADMAP: RoadmapItem[] = [
  { id: 'myDocuments', icon: 'folder' },
  { id: 'benefits', icon: 'benefits' },
  { id: 'payslips', icon: 'payroll' },
];
