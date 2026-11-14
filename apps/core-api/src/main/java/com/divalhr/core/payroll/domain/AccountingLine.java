package com.divalhr.core.payroll.domain;

/**
 * One aggregated journal line (MVP-096, ADR 0039 section 4): an account code, a side and a positive
 * minor-unit amount, with the optional cost center and site codes a frozen payroll line carried
 * (none does today, so both are {@code null}). No employee data.
 *
 * @param lineNumber 1 to the line count, in journal order
 * @param accountCode mapped account code
 * @param direction DEBIT or CREDIT
 * @param amountMinor amount in minor units, positive
 * @param costCenterCode cost center code, or {@code null}
 * @param siteCode site code, or {@code null}
 */
public record AccountingLine(
    int lineNumber,
    String accountCode,
    AccountingDirection direction,
    long amountMinor,
    String costCenterCode,
    String siteCode) {}
