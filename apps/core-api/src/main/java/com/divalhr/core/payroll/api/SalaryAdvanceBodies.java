package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MVP-120A request bodies of the salary-advance API. Every value is read as {@code Object} so that
 * a wrong type is a field error, never a deserialization failure; an unknown property is recorded
 * (the contract declares {@code additionalProperties: false}). Amounts, notes and consent details
 * are never logged or echoed in an error.
 */
public final class SalaryAdvanceBodies {

  private SalaryAdvanceBodies() {}

  /** An employer participation policy (also the {@code policy} of a commit). */
  @Schema(name = "SalaryAdvancePolicyCommand")
  public static class PolicyCommand extends StrictRequest {

    private Object currency;
    private Object code;
    private Object effectiveFrom;
    private Object effectiveTo;
    private Object enabled;
    private Object minTenureMonths;
    private Object maxPercent;
    private Object absoluteMax;
    private Object minRequest;
    private Object maxOpenRequests;
    private Object waitingDays;
    private Object consentVersion;
    private Object partnerCode;

    /**
     * The properties as a map, for the common parser.
     *
     * @return the raw values by contract name
     */
    public Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("currency", currency);
      map.put("code", code);
      map.put("effectiveFrom", effectiveFrom);
      map.put("effectiveTo", effectiveTo);
      map.put("enabled", enabled);
      map.put("minTenureMonths", minTenureMonths);
      map.put("maxPercent", maxPercent);
      map.put("absoluteMax", absoluteMax);
      map.put("minRequest", minRequest);
      map.put("maxOpenRequests", maxOpenRequests);
      map.put("waitingDays", waitingDays);
      map.put("consentVersion", consentVersion);
      map.put("partnerCode", partnerCode);
      return map;
    }

    @Schema(type = "string")
    public Object getCurrency() {
      return currency;
    }

    public void setCurrency(Object currency) {
      this.currency = currency;
    }

    @Schema(type = "string")
    public Object getCode() {
      return code;
    }

    public void setCode(Object code) {
      this.code = code;
    }

    @Schema(type = "string", format = "date")
    public Object getEffectiveFrom() {
      return effectiveFrom;
    }

    public void setEffectiveFrom(Object effectiveFrom) {
      this.effectiveFrom = effectiveFrom;
    }

    @Schema(type = "string", format = "date", nullable = true)
    public Object getEffectiveTo() {
      return effectiveTo;
    }

    public void setEffectiveTo(Object effectiveTo) {
      this.effectiveTo = effectiveTo;
    }

    @Schema(type = "boolean")
    public Object getEnabled() {
      return enabled;
    }

    public void setEnabled(Object enabled) {
      this.enabled = enabled;
    }

    @Schema(type = "integer")
    public Object getMinTenureMonths() {
      return minTenureMonths;
    }

    public void setMinTenureMonths(Object minTenureMonths) {
      this.minTenureMonths = minTenureMonths;
    }

    @Schema(type = "integer")
    public Object getMaxPercent() {
      return maxPercent;
    }

    public void setMaxPercent(Object maxPercent) {
      this.maxPercent = maxPercent;
    }

    @Schema(type = "object", nullable = true)
    public Object getAbsoluteMax() {
      return absoluteMax;
    }

    public void setAbsoluteMax(Object absoluteMax) {
      this.absoluteMax = absoluteMax;
    }

    @Schema(type = "object")
    public Object getMinRequest() {
      return minRequest;
    }

    public void setMinRequest(Object minRequest) {
      this.minRequest = minRequest;
    }

    @Schema(type = "integer")
    public Object getMaxOpenRequests() {
      return maxOpenRequests;
    }

    public void setMaxOpenRequests(Object maxOpenRequests) {
      this.maxOpenRequests = maxOpenRequests;
    }

    @Schema(type = "integer")
    public Object getWaitingDays() {
      return waitingDays;
    }

    public void setWaitingDays(Object waitingDays) {
      this.waitingDays = waitingDays;
    }

    @Schema(type = "string")
    public Object getConsentVersion() {
      return consentVersion;
    }

    public void setConsentVersion(Object consentVersion) {
      this.consentVersion = consentVersion;
    }

    @Schema(type = "string")
    public Object getPartnerCode() {
      return partnerCode;
    }

    public void setPartnerCode(Object partnerCode) {
      this.partnerCode = partnerCode;
    }
  }

  /** A previewed policy with what the preview returned. */
  @Schema(name = "CommitSalaryAdvancePolicy")
  public static class CommitPolicy extends StrictRequest {

    private Object policy;
    private Object expectedVersion;
    private Object previewDigest;
    private Object expiresAt;
    private Object confirmed;

    @Schema(type = "object")
    public Object getPolicy() {
      return policy;
    }

    public void setPolicy(Object policy) {
      this.policy = policy;
    }

    @Schema(type = "integer")
    public Object getExpectedVersion() {
      return expectedVersion;
    }

    public void setExpectedVersion(Object expectedVersion) {
      this.expectedVersion = expectedVersion;
    }

    @Schema(type = "string")
    public Object getPreviewDigest() {
      return previewDigest;
    }

    public void setPreviewDigest(Object previewDigest) {
      this.previewDigest = previewDigest;
    }

    @Schema(type = "string", format = "date-time")
    public Object getExpiresAt() {
      return expiresAt;
    }

    public void setExpiresAt(Object expiresAt) {
      this.expiresAt = expiresAt;
    }

    @Schema(type = "boolean")
    public Object getConfirmed() {
      return confirmed;
    }

    public void setConfirmed(Object confirmed) {
      this.confirmed = confirmed;
    }
  }

  /** The one-time cancellation of a version that has not taken effect. */
  @Schema(name = "SalaryAdvanceCancelPolicy")
  public static class CancelPolicy extends StrictRequest {

    private Object expectedVersion;
    private Object confirmed;

    @Schema(type = "integer")
    public Object getExpectedVersion() {
      return expectedVersion;
    }

    public void setExpectedVersion(Object expectedVersion) {
      this.expectedVersion = expectedVersion;
    }

    @Schema(type = "boolean")
    public Object getConfirmed() {
      return confirmed;
    }

    public void setConfirmed(Object confirmed) {
      this.confirmed = confirmed;
    }
  }

  /** An employee's requested amount, for the preview. */
  @Schema(name = "SalaryAdvanceRequestCommand")
  public static class RequestCommand extends StrictRequest {

    private Object currency;
    private Object amount;

    @Schema(type = "string")
    public Object getCurrency() {
      return currency;
    }

    public void setCurrency(Object currency) {
      this.currency = currency;
    }

    @Schema(type = "object")
    public Object getAmount() {
      return amount;
    }

    public void setAmount(Object amount) {
      this.amount = amount;
    }
  }

  /** A previewed request with the explicit, never pre-checked consent. */
  @Schema(name = "CommitSalaryAdvanceRequest")
  public static class CommitRequest extends StrictRequest {

    private Object currency;
    private Object amount;
    private Object previewDigest;
    private Object expiresAt;
    private Object consent;

    @Schema(type = "string")
    public Object getCurrency() {
      return currency;
    }

    public void setCurrency(Object currency) {
      this.currency = currency;
    }

    @Schema(type = "object")
    public Object getAmount() {
      return amount;
    }

    public void setAmount(Object amount) {
      this.amount = amount;
    }

    @Schema(type = "string")
    public Object getPreviewDigest() {
      return previewDigest;
    }

    public void setPreviewDigest(Object previewDigest) {
      this.previewDigest = previewDigest;
    }

    @Schema(type = "string", format = "date-time")
    public Object getExpiresAt() {
      return expiresAt;
    }

    public void setExpiresAt(Object expiresAt) {
      this.expiresAt = expiresAt;
    }

    @Schema(type = "object")
    public Object getConsent() {
      return consent;
    }

    public void setConsent(Object consent) {
      this.consent = consent;
    }
  }

  /** A withdrawal or an employer confirmation: the version the caller saw and the confirmation. */
  @Schema(name = "SalaryAdvanceWithdraw")
  public static class VersionedCommand extends StrictRequest {

    private Object expectedVersion;
    private Object confirmed;

    @Schema(type = "integer")
    public Object getExpectedVersion() {
      return expectedVersion;
    }

    public void setExpectedVersion(Object expectedVersion) {
      this.expectedVersion = expectedVersion;
    }

    @Schema(type = "boolean")
    public Object getConfirmed() {
      return confirmed;
    }

    public void setConfirmed(Object confirmed) {
      this.confirmed = confirmed;
    }
  }

  /** An employer refusal with one closed reason and an optional internal note. */
  @Schema(name = "SalaryAdvanceEmployerRefuse")
  public static class RefuseCommand extends StrictRequest {

    private Object expectedVersion;
    private Object reasonCode;
    private Object note;
    private Object confirmed;

    @Schema(type = "integer")
    public Object getExpectedVersion() {
      return expectedVersion;
    }

    public void setExpectedVersion(Object expectedVersion) {
      this.expectedVersion = expectedVersion;
    }

    @Schema(type = "string")
    public Object getReasonCode() {
      return reasonCode;
    }

    public void setReasonCode(Object reasonCode) {
      this.reasonCode = reasonCode;
    }

    @Schema(type = "string", nullable = true)
    public Object getNote() {
      return note;
    }

    public void setNote(Object note) {
      this.note = note;
    }

    @Schema(type = "boolean")
    public Object getConfirmed() {
      return confirmed;
    }

    public void setConfirmed(Object confirmed) {
      this.confirmed = confirmed;
    }
  }
}
