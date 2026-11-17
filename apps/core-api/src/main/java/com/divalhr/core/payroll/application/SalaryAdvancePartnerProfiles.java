package com.divalhr.core.payroll.application;

import java.util.Optional;

/**
 * The active partner profile (MVP-120B). Empty means no partner is enabled and every handoff is
 * refused with {@code SALARY_ADVANCE_PARTNER_NOT_ENABLED}.
 *
 * @param profile the active synthetic profile, if enabled
 */
public record SalaryAdvancePartnerProfiles(Optional<SalaryAdvancePartnerProfile> profile) {

  /**
   * Looks the profile up by its exact code.
   *
   * @param code the partner code
   * @return the active profile, or empty
   */
  public Optional<SalaryAdvancePartnerProfile> byCode(String code) {
    return profile.filter(p -> p.code().equals(code));
  }
}
