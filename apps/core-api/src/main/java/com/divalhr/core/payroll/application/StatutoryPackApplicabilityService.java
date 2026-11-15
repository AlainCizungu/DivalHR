package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.StatutoryPackResponses.Applicability;
import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.internal.JdbcStatutoryPackGovernanceRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryPackGovernanceRepository.VersionRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The read-only view of the statutory pack that applies to a legal entity and a payroll period
 * (MVP-097A, D97A-6 and D97A-12). The pack is selected by the legal entity's country and the
 * period among governed ACTIVE versions, never by a client-supplied pack identifier. A tenant
 * administrator sees the public provenance only: no formula, rate, reviewer, note or raw document.
 */
@Service
public class StatutoryPackApplicabilityService {

  /** Operation and audit action of the read. */
  public static final String READ = StatutoryPackRecorder.APPLICABILITY_READ;

  private static final Logger LOG = LoggerFactory.getLogger(StatutoryPackApplicabilityService.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  private final JdbcStatutoryPackGovernanceRepository governance;
  private final JdbcStatutoryRepository statutory;
  private final PayrollEntities entities;
  private final StatutoryPackRecorder recorder;
  private final CompensationCalendar calendar;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param governance governance storage
   * @param statutory tenant statutory storage (the legal entity's country)
   * @param entities legal entity resolver
   * @param recorder audit
   * @param calendar server clock
   * @param plumbing transactions, metrics and logs
   */
  public StatutoryPackApplicabilityService(
      JdbcStatutoryPackGovernanceRepository governance,
      JdbcStatutoryRepository statutory,
      PayrollEntities entities,
      StatutoryPackRecorder recorder,
      CompensationCalendar calendar,
      PayrollOperations plumbing) {
    this.governance = governance;
    this.statutory = statutory;
    this.entities = entities;
    this.recorder = recorder;
    this.calendar = calendar;
    this.plumbing = plumbing;
  }

  /**
   * The applicable pack of a legal entity and a payroll period.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param payrollPeriod raw {@code YYYY-MM} query value
   * @return the view, after its disclosure audit committed
   */
  public Applicability read(PayrollCaller caller, String legalEntityId, String payrollPeriod) {
    return plumbing.observed(
        LOG,
        "statutory_pack_applicability_read",
        READ,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          FieldErrors errors = new FieldErrors();
          PayrollPeriod period = periodOf(payrollPeriod, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                String country =
                    statutory
                        .legalEntityCountry(tenant, entity)
                        .orElseThrow(
                            () -> new ApiException(ErrorCode.LEGAL_ENTITY_NOT_FOUND, Map.of()));
                Optional<VersionRow> found = governance.applicable(country, period.first());
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("legalEntityId", entity.toString());
                metadata.put("period", period.text());
                metadata.put("country", country);
                metadata.put("view", "applicability");
                metadata.put("resultCount", found.isPresent() ? 1 : 0);
                recorder.disclosed(
                    caller.subject(),
                    READ,
                    entity,
                    tenant.value(),
                    metadata,
                    caller.correlationId(),
                    calendar.now());
                if (found.isEmpty()) {
                  return new Applicability(
                      entity, period.text(), country, false, null, null, null, null, null, null,
                      null, null, null, null);
                }
                VersionRow row = found.get();
                return new Applicability(
                    entity,
                    period.text(),
                    country,
                    true,
                    row.code(),
                    row.version(),
                    row.semanticVersion(),
                    row.state(),
                    row.activeFrom(),
                    row.activeTo(),
                    row.sourceAuthority(),
                    row.sourceTitle(),
                    row.sourcePublishedOn(),
                    row.sourceReference());
              });
        });
  }

  private static PayrollPeriod periodOf(String raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("payrollPeriod", FieldErrors.Constraint.REQUIRED);
      return null;
    }
    Optional<PayrollPeriod> period = PayrollPeriod.parse(raw);
    if (period.isEmpty()) {
      errors.add("payrollPeriod", FieldErrors.Constraint.FORMAT);
      return null;
    }
    return period.get();
  }
}
