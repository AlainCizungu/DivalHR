package com.divalhr.core.platform.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes. Clients translate these; the API never returns translated
 * text as an identifier.
 */
public enum ErrorCode {
  /** The request failed validation. */
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
  /** No valid bearer token was presented. */
  AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
  /** The caller is authenticated but not permitted to perform the action. */
  ACCESS_DENIED(HttpStatus.FORBIDDEN),
  /** The verified token does not carry a tenant context. */
  TENANT_CONTEXT_MISSING(HttpStatus.FORBIDDEN),
  /** The caller attempted to access another tenant's resource. */
  TENANT_ACCESS_DENIED(HttpStatus.FORBIDDEN),
  /** The resource does not exist or is not visible to the caller. */
  NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The idempotency key was already used with a different payload. */
  IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
  /** The country is not supported. */
  COUNTRY_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
  /** The locale is not supported. */
  LOCALE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
  /** The time zone is not supported for the country. */
  TIMEZONE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
  /** A currency is not supported for the country. */
  CURRENCY_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
  /** The legal entity does not exist in the caller's tenant (missing and foreign look alike). */
  LEGAL_ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** A legal entity with this code already exists in the tenant, in any letter case. */
  DUPLICATE_LEGAL_ENTITY_CODE(HttpStatus.CONFLICT),
  /** A site with this code already exists in the tenant, in any letter case. */
  DUPLICATE_SITE_CODE(HttpStatus.CONFLICT),
  /** The effective period ends before it starts. */
  EFFECTIVE_DATE_INVALID(HttpStatus.BAD_REQUEST),
  /** The site period is not contained in its legal entity's period. */
  SITE_PERIOD_OUTSIDE_LEGAL_ENTITY(HttpStatus.BAD_REQUEST),
  /** The pagination cursor is malformed, tampered with or bound to another query. */
  CURSOR_INVALID(HttpStatus.BAD_REQUEST),
  /** The site does not exist in the caller's tenant (missing and foreign look the same). */
  SITE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** A department with this code already exists in the tenant, in any letter case. */
  DUPLICATE_DEPARTMENT_CODE(HttpStatus.CONFLICT),
  /** A cost center with this code already exists in the tenant, in any letter case. */
  DUPLICATE_COST_CENTER_CODE(HttpStatus.CONFLICT),
  /** The department's effective period is not contained in its site's. */
  DEPARTMENT_PERIOD_OUTSIDE_SITE(HttpStatus.BAD_REQUEST),
  /** The cost center's effective period is not contained in its site's. */
  COST_CENTER_PERIOD_OUTSIDE_SITE(HttpStatus.BAD_REQUEST),
  /** The referenced region does not exist in the caller's tenant (missing or foreign). */
  REGION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** A region with this code already exists in the tenant, in any letter case. */
  DUPLICATE_REGION_CODE(HttpStatus.CONFLICT),
  /** The region's effective period is not contained in its legal entity's. */
  REGION_PERIOD_OUTSIDE_LEGAL_ENTITY(HttpStatus.BAD_REQUEST),
  /** The site's effective period is not contained in the region's. */
  SITE_PERIOD_OUTSIDE_REGION(HttpStatus.BAD_REQUEST),
  /** The region belongs to another legal entity than the site. */
  SITE_REGION_LEGAL_ENTITY_MISMATCH(HttpStatus.BAD_REQUEST),
  /** The site already has a different region; changing or clearing it is not supported. */
  SITE_REGION_ALREADY_ASSIGNED(HttpStatus.CONFLICT),
  /** A team names no parent: neither departmentId nor costCenterId was supplied. */
  TEAM_PARENT_REQUIRED(HttpStatus.BAD_REQUEST),
  /** A team names two parents: both departmentId and costCenterId were supplied. */
  TEAM_PARENT_AMBIGUOUS(HttpStatus.BAD_REQUEST),
  /** The referenced department does not exist in the caller's tenant (missing or foreign). */
  DEPARTMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The referenced cost center does not exist in the caller's tenant (missing or foreign). */
  COST_CENTER_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** A team with this code already exists in the tenant, in any letter case. */
  DUPLICATE_TEAM_CODE(HttpStatus.CONFLICT),
  /** The team's effective period is not contained in its department's. */
  TEAM_PERIOD_OUTSIDE_DEPARTMENT(HttpStatus.BAD_REQUEST),
  /** The team's effective period is not contained in its cost center's. */
  TEAM_PERIOD_OUTSIDE_COST_CENTER(HttpStatus.BAD_REQUEST),
  /** The invitation does not exist in the caller's tenant (missing and foreign are identical). */
  INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** An open invitation for the address already exists in the caller's tenant. */
  INVITATION_ALREADY_PENDING(HttpStatus.CONFLICT),
  /** The address already belongs to a member of the caller's tenant. */
  INVITATION_RECIPIENT_ALREADY_MEMBER(HttpStatus.CONFLICT),
  /** The invitation is accepted, expired or being accepted, so it cannot change. */
  INVITATION_NOT_PENDING(HttpStatus.CONFLICT),
  /** The tenant's invitation quota is used up. */
  INVITATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
  /** The invitation was reissued too often or too recently. */
  INVITATION_RESEND_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
  /** The invitation token is unknown, malformed, expired, revoked or already used. */
  INVITATION_INVALID(HttpStatus.NOT_FOUND),
  /** The invitation cannot be accepted with this address (no reason is disclosed). */
  INVITATION_CANNOT_BE_ACCEPTED(HttpStatus.CONFLICT),
  /** Another acceptance of the same invitation is in progress; retry. */
  INVITATION_ACCEPTANCE_IN_PROGRESS(HttpStatus.CONFLICT),
  /** The identity provider could not be reached; retry. */
  IDENTITY_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
  /** The organization is malformed, unknown or not active (platform operations, MVP-014). */
  ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /**
   * The organization already has a tenant administrator or an open tenant-admin invitation; which
   * one is not disclosed (MVP-014).
   */
  TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE(HttpStatus.CONFLICT),
  /** Too many anonymous requests from one client. */
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
  /** A privileged operation requires multifactor authentication for this session (MVP-011). */
  MFA_REQUIRED(HttpStatus.FORBIDDEN),
  /** An unexpected server error occurred. */
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
  /** The employee import file is not an accepted CSV (params.reason, params.column) (MVP-020). */
  IMPORT_FILE_INVALID(HttpStatus.BAD_REQUEST),
  /** The employee import body exceeds the size cap (MVP-020). */
  IMPORT_FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
  /** The employee import body was not received in time; nothing was stored (MVP-020). */
  IMPORT_UPLOAD_TIMEOUT(HttpStatus.REQUEST_TIMEOUT),
  /** The tenant already has the maximum number of open imports (MVP-020). */
  IMPORT_LIMIT_REACHED(HttpStatus.CONFLICT),
  /** The import does not exist in the caller's tenant (missing, foreign, malformed) (MVP-020). */
  EMPLOYEE_IMPORT_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The import is no longer open (params.status) (MVP-020). */
  IMPORT_NOT_COMMITTABLE(HttpStatus.CONFLICT),
  /** The commit does not repeat the staged preview (MVP-020). */
  IMPORT_PREVIEW_CHANGED(HttpStatus.CONFLICT),
  /** A row became invalid since the preview; nothing was created (MVP-020). */
  IMPORT_STALE(HttpStatus.CONFLICT),
  /** The import has no valid row (MVP-020). */
  IMPORT_NOTHING_TO_COMMIT(HttpStatus.CONFLICT),
  /** The import's database work timed out and was rolled back entirely (MVP-020). */
  IMPORT_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
  /** The employee does not exist in the caller's tenant (missing, foreign, malformed) (MVP-021). */
  EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The change does not exist for this employee in the caller's tenant (MVP-021). */
  EMPLOYMENT_CHANGE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The employment changed since the caller read its version (MVP-021). */
  EMPLOYMENT_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** The commit does not repeat the current preview (MVP-021). */
  EMPLOYMENT_PREVIEW_CHANGED(HttpStatus.CONFLICT),
  /** A row of the kind already starts on the effective date (params.field) (MVP-021). */
  EMPLOYMENT_CHANGE_DATE_TAKEN(HttpStatus.CONFLICT),
  /** Only an active scheduled business change can be cancelled (MVP-021). */
  EMPLOYMENT_CHANGE_NOT_CANCELLABLE(HttpStatus.CONFLICT),
  /** A later change depends on the change to cancel (MVP-021, M21-2). */
  EMPLOYMENT_CHANGE_HAS_DEPENDENTS(HttpStatus.CONFLICT),
  /** The change would not alter the timeline (params.field) (MVP-021). */
  EMPLOYMENT_CHANGE_NO_EFFECT(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The effective date is outside the employment (MVP-021). */
  EMPLOYMENT_DATE_OUTSIDE_EMPLOYMENT(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The date is earlier than the configured retroactive window allows (MVP-021). */
  RETROACTIVE_WINDOW_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The manager cannot be assigned (params.reason) (MVP-021). */
  MANAGER_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The placement cannot be assigned (params.field, params.reason) (MVP-021). */
  PLACEMENT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The change's database work timed out and was rolled back entirely (MVP-021). */
  EMPLOYMENT_CHANGE_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
  /** The separation does not exist for that employee in the tenant (MVP-022). */
  SEPARATION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The checklist task does not exist for that separation (MVP-022). */
  SEPARATION_TASK_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** No membership of the caller's tenant holds the address (MVP-022). */
  MEMBERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The employment already has a separation that is not cancelled (MVP-022). */
  SEPARATION_EXISTS(HttpStatus.CONFLICT),
  /** Future effects after the last day must be cancelled first (params.count) (MVP-022). */
  SEPARATION_FUTURE_CHANGES(HttpStatus.CONFLICT),
  /** The linked access is protected (params.reason SELF or ADMIN_ACCESS) (MVP-022). */
  SEPARATION_PROTECTED(HttpStatus.CONFLICT),
  /** The recomputed separation differs from the preview (MVP-022). */
  SEPARATION_PREVIEW_CHANGED(HttpStatus.CONFLICT),
  /** The separation can no longer be cancelled (params.reason) (MVP-022). */
  SEPARATION_NOT_CANCELLABLE(HttpStatus.CONFLICT),
  /** The checklist task changed since it was read (MVP-022). */
  SEPARATION_TASK_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** The checklist of a cancelled separation is closed (MVP-022). */
  SEPARATION_TASK_CLOSED(HttpStatus.CONFLICT),
  /** The link cannot be created (params.reason) (MVP-022). */
  ACCESS_LINK_CONFLICT(HttpStatus.CONFLICT),
  /** A separation holds the link (MVP-022). */
  ACCESS_LINK_LOCKED(HttpStatus.CONFLICT),
  /** The link changed since it was read (MVP-022). */
  ACCESS_LINK_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** The sign-in removal does not need a manual retry (MVP-022). */
  ACCESS_REVOCATION_NOT_RETRYABLE(HttpStatus.CONFLICT),
  /** The last day is outside the permitted window (MVP-022). */
  SEPARATION_DATE_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_CONTENT),
  /** IMMEDIATELY requires a last day of today or earlier (MVP-022, A22-1). */
  SEPARATION_ACCESS_TIMING_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** An acknowledgement the preview required is missing (params.acknowledgement) (MVP-022). */
  SEPARATION_ACKNOWLEDGEMENT_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** Direct-report intervals need a plan (MVP-022). */
  SEPARATION_REPORT_PLAN_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** More direct-report intervals than one separation may rewrite (params.count) (MVP-022). */
  SEPARATION_TOO_MANY_INTERVALS(HttpStatus.UNPROCESSABLE_CONTENT),
  /** Unknown, malformed or foreign template or version (MVP-030). */
  CONTRACT_TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** Unknown, malformed, foreign or another employee's contract (MVP-030). */
  CONTRACT_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The template version changed since it was read (MVP-030). */
  CONTRACT_TEMPLATE_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** Only a draft version is edited, approved or deleted (MVP-030). */
  CONTRACT_TEMPLATE_NOT_DRAFT(HttpStatus.CONFLICT),
  /** Only an approved version is issued or retired (MVP-030). */
  CONTRACT_TEMPLATE_NOT_APPROVED(HttpStatus.CONFLICT),
  /** The template code is already used in the organization, or a draft already exists (MVP-030). */
  CONTRACT_TEMPLATE_CODE_TAKEN(HttpStatus.CONFLICT),
  /** The result no longer matches the preview (MVP-030). */
  CONTRACT_PREVIEW_CHANGED(HttpStatus.CONFLICT),
  /** The contract changed since it was read (MVP-030). */
  CONTRACT_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** Another non-void contract of the employment overlaps the period (MVP-030, D11). */
  CONTRACT_PERIOD_OVERLAP(HttpStatus.CONFLICT),
  /** A separation is recorded for the employee (MVP-030). */
  CONTRACT_EMPLOYMENT_ENDED(HttpStatus.CONFLICT),
  /** Only an issued, unacknowledged contract is voided (MVP-030). */
  CONTRACT_NOT_VOIDABLE(HttpStatus.CONFLICT),
  /** A void contract is never acknowledged (MVP-030). */
  CONTRACT_NOT_ACKNOWLEDGEABLE(HttpStatus.CONFLICT),
  /** The displayed snapshot or statement does not match the server's (MVP-030, A30-4). */
  CONTRACT_ACKNOWLEDGEMENT_CHANGED(HttpStatus.CONFLICT),
  /** The template text breaks grammar v1 (params.reason, params.line) (MVP-030, A30-2). */
  CONTRACT_TEMPLATE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** A placeholder has no value for this employee (params.placeholder) (MVP-030, D9). */
  CONTRACT_VALUE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The contract dates break the rules (params.field) (MVP-030, D10). */
  CONTRACT_DATES_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** Approval needs the TEXT_VERIFIED acknowledgement (MVP-030). */
  CONTRACT_TEMPLATE_ACKNOWLEDGEMENT_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The caller's employee membership has no active employee link (MVP-030, A30-1). */
  EMPLOYEE_LINK_REQUIRED(HttpStatus.FORBIDDEN),
  /** The contract operation took too long and nothing was written (MVP-030). */
  CONTRACT_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
  /** A leave policy with this code already exists in the organization (MVP-040A). */
  LEAVE_POLICY_CODE_EXISTS(HttpStatus.CONFLICT),
  /** The policy is unknown, ended or does not cover the whole interval (MVP-041A; no params). */
  LEAVE_POLICY_NOT_REQUESTABLE(HttpStatus.CONFLICT),
  /**
   * The employee is not eligible (params.reason EMPLOYMENT_PERIOD or MINIMUM_SERVICE; MVP-041A).
   */
  LEAVE_REQUEST_NOT_ELIGIBLE(HttpStatus.CONFLICT),
  /** Another pending request of the employee overlaps these dates (MVP-041A; no params). */
  LEAVE_REQUEST_OVERLAP(HttpStatus.CONFLICT),
  /**
   * No leave request the caller may see or decide: unknown, another tenant's, another route's or
   * not a report's on its first day are indistinguishable (MVP-041B; no params).
   */
  LEAVE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The leave request already has its decision (MVP-041B; no params). */
  LEAVE_REQUEST_ALREADY_DECIDED(HttpStatus.CONFLICT),
  /** The leave request was already cancelled by its employee (MVP-041C; no params). */
  LEAVE_REQUEST_ALREADY_CANCELLED(HttpStatus.CONFLICT),
  /** The leave request was already replaced by its employee's amendment (MVP-041D; no params). */
  LEAVE_REQUEST_ALREADY_AMENDED(HttpStatus.CONFLICT),
  /** The approved leave was already withdrawn by its employee (MVP-041F; no params). */
  LEAVE_REQUEST_ALREADY_WITHDRAWN(HttpStatus.CONFLICT),
  /** Only approved leave can be withdrawn; this request is still pending (MVP-041F; no params). */
  LEAVE_REQUEST_NOT_APPROVED(HttpStatus.CONFLICT),
  /**
   * The approved leave has started (the organization's business date reached its first day), so it
   * can no longer be withdrawn (MVP-041F; no params).
   */
  LEAVE_REQUEST_WITHDRAWAL_WINDOW_CLOSED(HttpStatus.CONFLICT),
  /**
   * No leave approval substitute appointment of the organization has this ID: unknown and another
   * tenant's are indistinguishable (MVP-041G; no params).
   */
  LEAVE_APPROVAL_SUBSTITUTION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The manager and the substitute are the same employee (MVP-041G; no params). */
  LEAVE_APPROVAL_SUBSTITUTION_SAME_EMPLOYEE(HttpStatus.UNPROCESSABLE_CONTENT),
  /**
   * The manager or the substitute is not employed on every day of the appointment (MVP-041G; no
   * params).
   */
  LEAVE_APPROVAL_SUBSTITUTION_EMPLOYMENT_PERIOD(HttpStatus.UNPROCESSABLE_CONTENT),
  /**
   * The manager has no active reporting line as manager during the appointment (MVP-041G; no
   * params).
   */
  LEAVE_APPROVAL_SUBSTITUTION_NO_REPORTING_LINE(HttpStatus.UNPROCESSABLE_CONTENT),
  /** Another unrevoked appointment of the manager overlaps these dates (MVP-041G; no params). */
  LEAVE_APPROVAL_SUBSTITUTION_OVERLAP(HttpStatus.CONFLICT),
  /** The appointment was already revoked (MVP-041G; no params). */
  LEAVE_APPROVAL_SUBSTITUTION_ALREADY_REVOKED(HttpStatus.CONFLICT),
  /** Unknown and foreign-tenant work schedules are indistinguishable (MVP-050A; no params). */
  WORK_SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /**
   * A fact the reviewed preview bound changed before publication: preview again (MVP-050A; no
   * params).
   */
  WORK_SCHEDULE_PREVIEW_STALE(HttpStatus.CONFLICT),
  /**
   * The schedule's dates are not in order, start before the business date, or span more than 31
   * days (MVP-050A; params.reason ORDER, PAST or TOO_LONG).
   */
  WORK_SCHEDULE_PERIOD_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /**
   * The site is not a site of the organization in effect on every date; unknown and foreign alike
   * (MVP-050A; no params).
   */
  WORK_SCHEDULE_SITE_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
  /** A published schedule already covers the site on one of the dates (MVP-050A; no params). */
  WORK_SCHEDULE_OVERLAP(HttpStatus.CONFLICT),
  /**
   * A slot's local times are outside the period, not in order, too long, or do not exist exactly
   * once in the time zone (MVP-050A; params.position and params.reason).
   */
  WORK_SCHEDULE_SLOT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /**
   * A slot's employee is not eligible: unknown or foreign, separated, not employed through the slot
   * or not placed at the site (MVP-050A; params.position and params.reason).
   */
  WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT),
  /**
   * A slot's employee already has an overlapping published or proposed assignment (MVP-050A;
   * params.position and params.reason PUBLISHED or IN_SCHEDULE, or no params when the database
   * refused a race).
   */
  WORK_SCHEDULE_EMPLOYEE_CONFLICT(HttpStatus.CONFLICT),
  /** More than 500 slots in one publication (MVP-050A; params.maximum). */
  WORK_SCHEDULE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The schedule is already cancelled: nothing more can change (MVP-050B; no params). */
  WORK_SCHEDULE_ALREADY_CANCELLED(HttpStatus.CONFLICT),
  /** The schedule is already replaced: change its successor instead (MVP-050B; no params). */
  WORK_SCHEDULE_ALREADY_REPLACED(HttpStatus.CONFLICT),
  /**
   * A slot of the schedule has already started: it can no longer be cancelled or replaced
   * (MVP-050B; no params).
   */
  WORK_SCHEDULE_CHANGE_WINDOW_CLOSED(HttpStatus.CONFLICT),
  /**
   * MVP-051: malformed, unknown, foreign-tenant, uncovered, another employee's or otherwise
   * undisclosable shift (all the same).
   */
  WORK_SCHEDULE_SHIFT_NOT_FOUND(HttpStatus.NOT_FOUND),
  /**
   * MVP-051: malformed, unknown, foreign-tenant, another manager's or otherwise undisclosable
   * substitution request (all the same).
   */
  WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /**
   * MVP-051: the shift cannot be substituted; params.reason NOT_OPERATIONAL, SUBSTITUTED,
   * EMPLOYMENT or NO_MANAGER (non-personal states only).
   */
  WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE(HttpStatus.CONFLICT),
  /** MVP-051: the shift already has a pending substitution request. */
  WORK_SCHEDULE_SUBSTITUTION_ALREADY_PENDING(HttpStatus.CONFLICT),
  /** MVP-051: the substitution request was already decided. */
  WORK_SCHEDULE_SUBSTITUTION_ALREADY_DECIDED(HttpStatus.CONFLICT),
  /** MVP-051: the shift has started; it can no longer be requested or decided. */
  WORK_SCHEDULE_SUBSTITUTION_WINDOW_CLOSED(HttpStatus.CONFLICT),
  /**
   * MVP-051: the chosen substitute is not an eligible direct report for the shift (generic; no
   * detail about foreign employees or other managers' reports).
   */
  WORK_SCHEDULE_SUBSTITUTE_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT),
  /** MVP-051: the chosen substitute has an overlapping operational assignment (no detail). */
  WORK_SCHEDULE_SUBSTITUTE_CONFLICT(HttpStatus.CONFLICT),
  /**
   * MVP-060A: an unknown, malformed, foreign, cancelled, replaced, uncovered or other employee's
   * slot (one indistinguishable answer).
   */
  ATTENDANCE_SHIFT_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-060A: the check-in window has not opened yet (2 hours before the scheduled start). */
  ATTENDANCE_CHECK_IN_TOO_EARLY(HttpStatus.CONFLICT),
  /** MVP-060A: the check-in window closed at the scheduled end. */
  ATTENDANCE_CHECK_IN_WINDOW_CLOSED(HttpStatus.CONFLICT),
  /** MVP-060A: the caller already checked in for this shift. */
  ATTENDANCE_ALREADY_CHECKED_IN(HttpStatus.CONFLICT),
  /** MVP-060A: another of the caller's records is open within its check-out window. */
  ATTENDANCE_OPEN_RECORD_EXISTS(HttpStatus.CONFLICT),
  /** MVP-060A: an unknown, malformed, foreign or other employee's record. */
  ATTENDANCE_RECORD_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-060A: the record already has its check-out. */
  ATTENDANCE_ALREADY_CHECKED_OUT(HttpStatus.CONFLICT),
  /** MVP-060A: the check-out window closed 24 hours after the scheduled end. */
  ATTENDANCE_CHECK_OUT_WINDOW_CLOSED(HttpStatus.CONFLICT),
  /** MVP-060A: the command does not fit the current state (no detail). */
  ATTENDANCE_STATE_CONFLICT(HttpStatus.CONFLICT),
  /**
   * MVP-060B: an unknown, malformed, foreign, non-report, own or no-longer-routed shift or record
   * for a supervisor-assisted action (one indistinguishable not-found).
   */
  TEAM_ATTENDANCE_ACTION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-060B: the caller manages nobody around the actionable dates. */
  TEAM_ATTENDANCE_MANAGER_REQUIRED(HttpStatus.FORBIDDEN),
  /**
   * MVP-060C: the attendance-exception review scope (a site) is unknown, foreign or malformed; the
   * same answer for each.
   */
  ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /**
   * MVP-061A: an offline attendance event was received more than 72 hours after its claimed device
   * time, or more than 72 hours after its attendance window closed.
   */
  OFFLINE_ATTENDANCE_TOO_OLD(HttpStatus.CONFLICT),
  /** MVP-061A: the claimed device time is more than 5 minutes after the server's receipt. */
  OFFLINE_ATTENDANCE_FUTURE_TIME(HttpStatus.CONFLICT),
  /** MVP-061A: the offset of {@code deviceOccurredAt} differs from {@code deviceOffsetMinutes}. */
  OFFLINE_ATTENDANCE_OFFSET_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
  /**
   * MVP-061A: another accepted attendance event makes this offline event impossible to apply
   * exactly (never used for authentication, foreign resources or validation).
   */
  OFFLINE_ATTENDANCE_CONFLICT(HttpStatus.CONFLICT),
  /**
   * MVP-062: the correction's target (a record, or a slot and its effective assignee) is unknown,
   * foreign, malformed or not disclosable; never distinguishes which.
   */
  ATTENDANCE_CORRECTION_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND),
  /**
   * MVP-062: the target cannot receive this correction now (a record already exists, the schedule
   * was cancelled or replaced, no single qualifying employment, another open record overlaps, or
   * nothing would change).
   */
  ATTENDANCE_CORRECTION_NOT_ALLOWED(HttpStatus.CONFLICT),
  /** MVP-062: the target changed since the preview; preview again. */
  ATTENDANCE_CORRECTION_PREVIEW_STALE(HttpStatus.CONFLICT),
  /**
   * MVP-070A: the site, generation or timesheet does not exist in the caller's tenant, or is
   * malformed, foreign or not disclosable; never distinguishes which.
   */
  TIMESHEET_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-070A: the week has no included slot to generate a draft for. */
  TIMESHEET_GENERATION_NOT_ALLOWED(HttpStatus.CONFLICT),
  /** MVP-070A: the week exceeds the per-request bound of drafts or source rows; nothing written. */
  TIMESHEET_GENERATION_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** MVP-070A: the generation or its sources changed since the refresh preview; preview again. */
  TIMESHEET_REFRESH_PREVIEW_STALE(HttpStatus.CONFLICT),
  /**
   * MVP-070B/C: the timesheet is not a submittable draft now (not a DRAFT, a blocking row, or no
   * READY row).
   */
  TIMESHEET_SUBMISSION_NOT_ALLOWED(HttpStatus.CONFLICT),
  /**
   * MVP-070B/C: no single eligible manager is effective for the week (none, several, the employee,
   * or without an active employee link), or the employment no longer qualifies; never names anyone.
   */
  TIMESHEET_APPROVAL_ROUTE_UNAVAILABLE(HttpStatus.CONFLICT),
  /**
   * MVP-070B/C: the revision, generation version, cycle, submission or sources changed since the
   * timesheet was opened; open it again.
   */
  TIMESHEET_DECISION_STALE(HttpStatus.CONFLICT),
  /** MVP-070B/C: the timesheet is not awaiting a decision (already approved or returned). */
  TIMESHEET_DECISION_NOT_ALLOWED(HttpStatus.CONFLICT),
  /**
   * MVP-070E: the generated week has no timesheet whose current state is APPROVED; nothing to
   * export.
   */
  TIMESHEET_EXPORT_EMPTY(HttpStatus.CONFLICT),
  /**
   * MVP-070E: the eligible approvals or their evidence changed since the export preview, or the
   * preview expired; preview again.
   */
  TIMESHEET_EXPORT_PREVIEW_STALE(HttpStatus.CONFLICT),
  /**
   * MVP-071: the generated week cannot be locked now (no timesheet, a timesheet that is not
   * currently APPROVED, or inconsistent evidence); the preview names the blockers.
   */
  TIMESHEET_PERIOD_NOT_READY(HttpStatus.CONFLICT),
  /**
   * MVP-071: the generated week is locked; corrections of its shifts, refreshes and every timesheet
   * transition are refused until a tenant administrator unlocks it.
   */
  TIMESHEET_PERIOD_LOCKED(HttpStatus.CONFLICT),
  /** MVP-071: the generated week is not locked; there is nothing to unlock. */
  TIMESHEET_PERIOD_NOT_LOCKED(HttpStatus.CONFLICT),
  /**
   * MVP-071: the approvals, the generation or lock version, or the unlock reason changed since the
   * lock or unlock preview, or the preview expired; preview again.
   */
  TIMESHEET_PERIOD_PREVIEW_STALE(HttpStatus.CONFLICT),
  /**
   * MVP-090A: the legal entity, period or payroll-input run is unknown, malformed or outside the
   * caller's tenant (one safe 404, nothing disclosed).
   */
  PAYROLL_INPUT_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND),
  /**
   * MVP-090A: the period cannot be generated now: a week that matters is not generated or not
   * locked, a locked week's evidence changed, an employee row cannot be attributed to the legal
   * entity, or no approved time lies in the period; the preview names the blockers.
   */
  PAYROLL_INPUT_NOT_READY(HttpStatus.CONFLICT),
  /**
   * MVP-090A: the locked evidence or its source versions changed since the preview, or the preview
   * expired; preview again.
   */
  PAYROLL_INPUT_PREVIEW_STALE(HttpStatus.CONFLICT),
  /**
   * MVP-090A: a payroll-input run of this legal entity and period already exists with different
   * evidence; generated evidence is never replaced (a superseding version is a later story).
   */
  PAYROLL_INPUT_RUN_EXISTS(HttpStatus.CONFLICT),
  /**
   * MVP-092: the payroll-input run is not the latest version of its legal entity and period (a
   * later version exists); only the latest version can be superseded or locked.
   */
  PAYROLL_INPUT_NOT_LATEST(HttpStatus.CONFLICT),
  /**
   * MVP-092: the payroll-input run is already locked; a locked run is final and is never superseded
   * or locked again.
   */
  PAYROLL_INPUT_RUN_LOCKED(HttpStatus.CONFLICT),
  /** MVP-092: only a locked payroll-input run can be exported as worked-time inputs. */
  PAYROLL_INPUT_NOT_LOCKED(HttpStatus.CONFLICT),
  /** MVP-092: the run holds more rows than one export can carry; nothing is truncated. */
  PAYROLL_INPUT_EXPORT_TOO_LARGE(HttpStatus.CONFLICT),
  /**
   * MVP-091: the current or comparison payroll-input run is unknown, malformed, outside the
   * caller's tenant or not comparable (another legal entity, not ending before the current period,
   * another length), or no eligible comparison run exists (one safe 404, nothing disclosed).
   */
  PAYROLL_VARIANCE_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND),
  /**
   * MVP-100: the operational-status filter (a site or a legal entity) is unknown, foreign,
   * malformed or of another kind; the same answer for each.
   */
  OPERATIONAL_STATUS_SCOPE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-101: the requested briefing language is not exactly {@code en} or {@code fr}. */
  OPERATIONAL_SUMMARY_LANGUAGE_INVALID(HttpStatus.BAD_REQUEST),
  /** MVP-101: the governed snapshot violated an MVP-100 invariant, so no briefing was requested. */
  OPERATIONAL_SUMMARY_SOURCE_INVALID(HttpStatus.INTERNAL_SERVER_ERROR),
  /**
   * MVP-101: no valid briefing could be produced (service authentication, the AI service, or its
   * answer failed); nothing partial is returned.
   */
  OPERATIONAL_SUMMARY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
  /** MVP-110: the webhook is missing or foreign (indistinguishable). */
  WEBHOOK_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-110: the delivery is missing for this webhook. */
  WEBHOOK_DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-110: the tenant already has 20 webhooks. */
  WEBHOOK_LIMIT_REACHED(HttpStatus.CONFLICT),
  /** MVP-110: the endpoint is not an acceptable HTTPS URL. */
  WEBHOOK_URL_INVALID(HttpStatus.BAD_REQUEST),
  /** MVP-110: the endpoint resolves to a forbidden destination. */
  WEBHOOK_DESTINATION_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** MVP-110: an event type is outside the closed catalogue, or none was chosen. */
  WEBHOOK_EVENT_TYPE_INVALID(HttpStatus.BAD_REQUEST),
  /** MVP-110: the configuration version is not the current one. */
  WEBHOOK_VERSION_STALE(HttpStatus.CONFLICT),
  /** MVP-110: the webhook is already disabled. */
  WEBHOOK_ALREADY_DISABLED(HttpStatus.CONFLICT),
  /** MVP-110: the webhook is already active. */
  WEBHOOK_ALREADY_ENABLED(HttpStatus.CONFLICT),
  /** MVP-110: the webhook is disabled, so no test can be sent. */
  WEBHOOK_DISABLED(HttpStatus.CONFLICT),
  /** MVP-110: the webhook was suspended after repeated terminal failures. */
  WEBHOOK_SUSPENDED(HttpStatus.CONFLICT),
  /** MVP-110: a test-delivery limit was reached (which one is not disclosed). */
  WEBHOOK_TEST_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
  /** MVP-110: the webhook encryption key is not configured. */
  WEBHOOK_SECRET_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
  /** MVP-111: the tenant has no WhatsApp connector (or the id is foreign). */
  MESSAGING_CONNECTOR_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-111: the tenant already has its one WhatsApp connector. */
  MESSAGING_CONNECTOR_EXISTS(HttpStatus.CONFLICT),
  /** MVP-111: the connector is a draft or not verified, so it cannot send. */
  MESSAGING_CONNECTOR_INCOMPLETE(HttpStatus.CONFLICT),
  /** MVP-111: the connector is disabled. */
  MESSAGING_CONNECTOR_DISABLED(HttpStatus.CONFLICT),
  /** MVP-111: the connector was suspended after repeated terminal failures. */
  MESSAGING_CONNECTOR_SUSPENDED(HttpStatus.CONFLICT),
  /** MVP-111: the connector configuration cannot change while it is active. */
  MESSAGING_CONNECTOR_ACTIVE(HttpStatus.CONFLICT),
  /** MVP-111: the configuration version is not the current one. */
  MESSAGING_CONNECTOR_VERSION_STALE(HttpStatus.CONFLICT),
  /** MVP-111: the connector or recipient is already disabled. */
  MESSAGING_ALREADY_DISABLED(HttpStatus.CONFLICT),
  /** MVP-111: the connector or recipient is already active. */
  MESSAGING_ALREADY_ENABLED(HttpStatus.CONFLICT),
  /** MVP-111: the provider could not be reached or answered unexpectedly. */
  MESSAGING_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
  /** MVP-111: the provider refused the supplied credential or identifiers. */
  MESSAGING_PROVIDER_CREDENTIAL_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** MVP-111: a template is missing, not approved, or has an unsupported shape. */
  MESSAGING_TEMPLATE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** MVP-111: the recipient is missing or foreign. */
  MESSAGING_RECIPIENT_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-111: the tenant already has 25 recipients. */
  MESSAGING_RECIPIENT_LIMIT_REACHED(HttpStatus.CONFLICT),
  /** MVP-111: a recipient with this number already exists. */
  MESSAGING_RECIPIENT_EXISTS(HttpStatus.CONFLICT),
  /** MVP-111: the recipient is disabled. */
  MESSAGING_RECIPIENT_DISABLED(HttpStatus.CONFLICT),
  /** MVP-111: the recipient version is not the current one. */
  MESSAGING_RECIPIENT_VERSION_STALE(HttpStatus.CONFLICT),
  /** MVP-111: the consent attestation is missing or incomplete. */
  MESSAGING_CONSENT_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** MVP-111: the number is not a valid E.164 number. */
  MESSAGING_PHONE_INVALID(HttpStatus.BAD_REQUEST),
  /** MVP-111: a notification type is outside the closed catalogue, or none was chosen. */
  MESSAGING_NOTIFICATION_TYPE_INVALID(HttpStatus.BAD_REQUEST),
  /** MVP-111: a test-message limit was reached (which one is not disclosed). */
  MESSAGING_TEST_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
  /** MVP-111: the delivery is missing or foreign. */
  MESSAGING_DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-111: the messaging encryption key is not configured. */
  MESSAGING_SECRET_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
  /**
   * MVP-080: the task is unknown, malformed, foreign, or another employee's (indistinguishable).
   */
  TASK_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-080: the assignee does not qualify (which condition failed is never disclosed). */
  TASK_ASSIGNEE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-080: the expected version is stale. */
  TASK_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** MVP-080: the task's state does not allow the command. */
  TASK_TRANSITION_NOT_ALLOWED(HttpStatus.CONFLICT),
  /** MVP-080: the tenant holds the maximum number of non-terminal tasks. */
  TASK_LIMIT_REACHED(HttpStatus.CONFLICT),
  /**
   * MVP-081: the expense is unknown, malformed, foreign, not the caller's, or no longer routed to
   * the caller (indistinguishable).
   */
  EXPENSE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-081: the expected version is stale. */
  EXPENSE_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** MVP-081: the expense's state does not allow the command. */
  EXPENSE_TRANSITION_NOT_ALLOWED(HttpStatus.CONFLICT),
  /** MVP-081: no single eligible manager can decide the expense (nobody is named). */
  EXPENSE_APPROVAL_ROUTE_UNAVAILABLE(HttpStatus.CONFLICT),
  /** MVP-081: the employee or the tenant holds the maximum number of open expenses. */
  EXPENSE_LIMIT_REACHED(HttpStatus.CONFLICT),
  /** MVP-081: the receipt is not an accepted, well-formed PDF, JPEG or PNG. */
  EXPENSE_RECEIPT_INVALID(HttpStatus.BAD_REQUEST),
  /** MVP-081: the receipt exceeds 2 MiB. */
  EXPENSE_RECEIPT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
  /** MVP-081: the receipt body was not received in time. */
  EXPENSE_RECEIPT_UPLOAD_TIMEOUT(HttpStatus.REQUEST_TIMEOUT),
  /** MVP-081: the receipt is not one of the accepted media types. */
  EXPENSE_RECEIPT_MEDIA_UNSUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
  /** MVP-093A: the employee or compensation change is unknown or not visible to the caller. */
  COMPENSATION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-093A: the amount is malformed or outside the currency's bounds. */
  COMPENSATION_INVALID_AMOUNT(HttpStatus.BAD_REQUEST),
  /** MVP-093A: an effective date is outside the permitted window or employment. */
  COMPENSATION_DATE_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_CONTENT),
  /** MVP-093A: the change conflicts with the employee's employment history. */
  COMPENSATION_EMPLOYMENT_CONFLICT(HttpStatus.CONFLICT),
  /** MVP-093A: the preview expired or no longer matches the current state. */
  COMPENSATION_PREVIEW_STALE(HttpStatus.CONFLICT),
  /** MVP-093A: the compensation version differs from the one the caller saw. */
  COMPENSATION_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** MVP-093A: the action is not legal in the current timeline. */
  COMPENSATION_TRANSITION_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** MVP-093A: the per-employee limit of changes or scheduled changes is reached. */
  COMPENSATION_LIMIT_REACHED(HttpStatus.CONFLICT),
  /** MVP-094A: no payroll policy is in force on the last day of the month. */
  PAYROLL_POLICY_NOT_FOUND(HttpStatus.CONFLICT),
  /** MVP-094A: the policy version moved, the policy is identical, or its effective date is used. */
  PAYROLL_POLICY_CONFLICT(HttpStatus.CONFLICT),
  /** MVP-094A: the month is not a fully closed month of the prior 24, or a date is out of range. */
  PAYROLL_PERIOD_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** MVP-094A: the payroll run is unknown or not visible to the caller. */
  PAYROLL_RUN_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-094A: an employee blocker forbids creating the run. */
  PAYROLL_RUN_BLOCKED(HttpStatus.CONFLICT),
  /** MVP-094A: the preview expired or the inputs changed since it was shown. */
  PAYROLL_RUN_PREVIEW_STALE(HttpStatus.CONFLICT),
  /** MVP-094A (R178-1): the recalculation equals the current draft, so nothing would change. */
  PAYROLL_RUN_NO_CHANGE(HttpStatus.CONFLICT),
  /** MVP-094A: another command changed the run chain first. */
  PAYROLL_RUN_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** MVP-094A: 60 policies, 25 versions of a chain, or 5,000 employees would be exceeded. */
  PAYROLL_RUN_LIMIT_REACHED(HttpStatus.CONFLICT),
  /** MVP-094B: the statutory rule pack is unknown or malformed. */
  STATUTORY_RULE_PACK_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** MVP-094B: the rule pack is DRAFT or RETIRED, or no verified pack is in effect. */
  STATUTORY_RULE_PACK_NOT_VERIFIED(HttpStatus.CONFLICT),
  /** MVP-094B: the month is outside the pack interval, or its country or currency differs. */
  STATUTORY_RULE_PACK_NOT_APPLICABLE(HttpStatus.CONFLICT),
  /** MVP-094B: an employee of the run has no statutory profile in force. */
  STATUTORY_PROFILE_MISSING(HttpStatus.CONFLICT),
  /** MVP-094B: an employee of the run has an unsupported statutory classification. */
  STATUTORY_PROFILE_UNSUPPORTED(HttpStatus.CONFLICT),
  /** MVP-094B: a blocker other than a missing or unsupported profile forbids the calculation. */
  STATUTORY_CALCULATION_BLOCKED(HttpStatus.CONFLICT),
  /** MVP-094B: the preview expired or the run, profiles or rule pack changed since it was shown. */
  STATUTORY_PREVIEW_STALE(HttpStatus.CONFLICT),
  /** MVP-094B: the recalculation equals the current draft, so nothing would change. */
  STATUTORY_CALCULATION_NO_CHANGE(HttpStatus.CONFLICT),
  /**
   * MVP-094B: another command changed the activations, profile timeline or calculation chain first.
   */
  STATUTORY_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /**
   * MVP-094B: 60 activations, 100 profile entries, 25 versions of a chain or 5,000 lines would be
   * exceeded.
   */
  STATUTORY_LIMIT_REACHED(HttpStatus.CONFLICT),
  /** MVP-094B: the statutory calculation is unknown or not visible to the caller. */
  STATUTORY_CALCULATION_NOT_FOUND(HttpStatus.NOT_FOUND);

  private final HttpStatus status;

  ErrorCode(HttpStatus status) {
    this.status = status;
  }

  /**
   * Returns the HTTP status associated with this code.
   *
   * @return the HTTP status
   */
  public HttpStatus status() {
    return status;
  }
}
