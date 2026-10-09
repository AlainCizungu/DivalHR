package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Leave policy creation command (MVP-040A). Mirrors {@code CreateLeavePolicy}. Every value is kept
 * raw so that each defect maps to a stable {@code {field, constraint}} pair; unknown properties are
 * captured and rejected.
 */
@Schema(name = "CreateLeavePolicy")
public class CreateLeavePolicyRequest extends StrictRequest {
  private Object code;
  private Object names;
  private Object unit;
  private Object balanceMode;
  private Object annualEntitlement;
  private Object minimumServiceDays;
  private Object approvalRoute;
  private Object payrollEffect;
  private Object effectiveFrom;
  private Object effectiveTo;

  /**
   * Code: 2 to 20 characters, letters, digits, hyphen and underscore; normalized to upper case.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCode() {
    return code;
  }

  /**
   * Sets: code: 2 to 20 characters, letters, digits, hyphen and underscore; normalized to upper
   * case.
   *
   * @param code raw value
   */
  public void setCode(Object code) {
    this.code = code;
  }

  /**
   * English and French names.
   *
   * @return raw value
   */
  @Schema(implementation = LeavePolicyNamesInput.class)
  public Object getNames() {
    return names;
  }

  /**
   * Sets: english and French names.
   *
   * @param names raw value
   */
  public void setNames(Object names) {
    this.names = names;
  }

  /**
   * {@code DAYS} or {@code HOURS}.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getUnit() {
    return unit;
  }

  /**
   * Sets: {@code DAYS} or {@code HOURS}.
   *
   * @param unit raw value
   */
  public void setUnit(Object unit) {
    this.unit = unit;
  }

  /**
   * {@code TRACKED} or {@code UNTRACKED}.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getBalanceMode() {
    return balanceMode;
  }

  /**
   * Sets: {@code TRACKED} or {@code UNTRACKED}.
   *
   * @param balanceMode raw value
   */
  public void setBalanceMode(Object balanceMode) {
    this.balanceMode = balanceMode;
  }

  /**
   * Annual entitlement (tracked policies only).
   *
   * @return raw value
   */
  @Schema(type = "number")
  public Object getAnnualEntitlement() {
    return annualEntitlement;
  }

  /**
   * Sets: annual entitlement (tracked policies only).
   *
   * @param annualEntitlement raw value
   */
  public void setAnnualEntitlement(Object annualEntitlement) {
    this.annualEntitlement = annualEntitlement;
  }

  /**
   * Service days before eligibility (0 to 3650).
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getMinimumServiceDays() {
    return minimumServiceDays;
  }

  /**
   * Sets: service days before eligibility (0 to 3650).
   *
   * @param minimumServiceDays raw value
   */
  public void setMinimumServiceDays(Object minimumServiceDays) {
    this.minimumServiceDays = minimumServiceDays;
  }

  /**
   * {@code MANAGER} or {@code TENANT_ADMIN}.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getApprovalRoute() {
    return approvalRoute;
  }

  /**
   * Sets: {@code MANAGER} or {@code TENANT_ADMIN}.
   *
   * @param approvalRoute raw value
   */
  public void setApprovalRoute(Object approvalRoute) {
    this.approvalRoute = approvalRoute;
  }

  /**
   * {@code PAID} or {@code UNPAID} (descriptive only).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getPayrollEffect() {
    return payrollEffect;
  }

  /**
   * Sets: {@code PAID} or {@code UNPAID} (descriptive only).
   *
   * @param payrollEffect raw value
   */
  public void setPayrollEffect(Object payrollEffect) {
    this.payrollEffect = payrollEffect;
  }

  /**
   * First day (ISO date).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEffectiveFrom() {
    return effectiveFrom;
  }

  /**
   * Sets: first day (ISO date).
   *
   * @param effectiveFrom raw value
   */
  public void setEffectiveFrom(Object effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  /**
   * Last day (ISO date), optional.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEffectiveTo() {
    return effectiveTo;
  }

  /**
   * Sets: last day (ISO date), optional.
   *
   * @param effectiveTo raw value
   */
  public void setEffectiveTo(Object effectiveTo) {
    this.effectiveTo = effectiveTo;
  }
}
