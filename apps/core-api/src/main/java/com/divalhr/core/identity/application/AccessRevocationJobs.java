package com.divalhr.core.identity.application;

import com.divalhr.core.identity.application.IdentityDirectory.RevocationOutcome;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.identity.internal.JdbcAccessLinkRepository;
import com.divalhr.core.identity.internal.JdbcAccessLinkRepository.Binding;
import com.divalhr.core.identity.internal.JdbcAccessLinkRepository.RevocationRow;
import com.divalhr.core.platform.web.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The access-revocation worker (MVP-022, D22-13, D22-14, A22-5).
 *
 * <ul>
 *   <li>{@link #activateDue()} records that scheduled revocations took effect (audit and outbox).
 *       The membership gate already denied them from their instant on: this job never decides
 *       access.
 *   <li>{@link #revokePending()} asks the identity provider to disable each separated identity.
 *       Immediately before every call, under the revocation's row lock and share locks on its link
 *       and membership that never wait, it revalidates that the revocation is still due and
 *       pending, that the original active link and the employee-role membership still correspond to
 *       it, and reads the subject from the membership as it is now. If anything differs, the
 *       provider is not called: the revocation goes to manual intervention with a closed code. The
 *       provider call itself happens outside any database transaction, under a lease, and its
 *       outcome is recorded only by the worker still holding that lease.
 * </ul>
 *
 * <p>Several instances may run it: rows are claimed with {@code SKIP LOCKED} and leases. Logs and
 * metrics carry job names, counts and closed outcome codes only; never a subject or an ID.
 */
@Component
public class AccessRevocationJobs {

  /** Outcome counter (tag {@code outcome}). */
  public static final String METRIC = "divalhr.access.revocation";

  /**
   * Open revocations gauge (tag {@code state}: {@code pending}, {@code manual}, {@code overdue}).
   */
  public static final String GAUGE = "divalhr.access.revocation.open";

  /** Age after which a pending revocation counts as overdue (alert threshold). */
  static final Duration OVERDUE = Duration.ofMinutes(30);

  private static final Logger LOG = LoggerFactory.getLogger(AccessRevocationJobs.class);

  private final JdbcAccessLinkRepository links;
  private final IdentityDirectory directory;
  private final AccessLinkEvents events;
  private final TransactionTemplate transactions;
  private final AccessRevocationProperties properties;
  private final MeterRegistry registry;
  private final Clock clock;
  private final AtomicLong pending = new AtomicLong();
  private final AtomicLong manual = new AtomicLong();
  private final AtomicLong overdue = new AtomicLong();

  /**
   * Creates the jobs.
   *
   * @param links link and revocation repository
   * @param directory identity provider
   * @param events audit and outbox
   * @param transactions transaction template
   * @param properties settings
   * @param registry metrics
   */
  public AccessRevocationJobs(
      JdbcAccessLinkRepository links,
      IdentityDirectory directory,
      AccessLinkEvents events,
      TransactionTemplate transactions,
      AccessRevocationProperties properties,
      MeterRegistry registry) {
    this.links = links;
    this.directory = directory;
    this.events = events;
    this.transactions = transactions;
    this.properties = properties;
    this.registry = registry;
    this.clock = Clock.systemUTC();
    Gauge.builder(GAUGE, pending, AtomicLong::get).tag("state", "pending").register(registry);
    Gauge.builder(GAUGE, manual, AtomicLong::get).tag("state", "manual").register(registry);
    Gauge.builder(GAUGE, overdue, AtomicLong::get).tag("state", "overdue").register(registry);
  }

  /**
   * Records the revocations whose instant passed (audit {@code tenant-membership.access-revoke} and
   * outbox {@code identity.membership.access-revoked.v1}) and queues them for the provider.
   *
   * @return revocations activated
   */
  public int activateDue() {
    String correlationId = CorrelationId.resolve(null);
    Integer activated =
        transactions.execute(
            status -> {
              List<RevocationRow> rows = links.activateDue(properties.batchSize());
              for (RevocationRow row : rows) {
                events.revoked(
                    row.tenant(),
                    AccessLinkEvents.SYSTEM_ACTOR,
                    row.id(),
                    row.membershipId(),
                    correlationId);
              }
              return rows.size();
            });
    return report("activated", activated == null ? 0 : activated);
  }

  /**
   * One identity-provider attempt for each claimable revocation.
   *
   * @return attempts made or refused
   */
  public int revokePending() {
    int handled = 0;
    for (UUID id : links.claimableIds(properties.batchSize())) {
      try {
        if (attempt(id)) {
          handled++;
        }
      } catch (DataAccessException busy) {
        // A link or membership row is locked by a coordinator (NOWAIT): try again next run.
        count("busy");
      }
    }
    refreshGauges();
    return handled;
  }

  /** A leased attempt: the version the lease produced and the subject read under the locks. */
  private record Lease(RevocationRow row, long version, int attempt, String subject) {

    @Override
    public String toString() {
      return "Lease[redacted]";
    }
  }

  private boolean attempt(UUID id) {
    UUID owner = UUID.randomUUID();
    String correlationId = CorrelationId.resolve(null);
    Optional<Lease> leased =
        transactions.execute(
            status -> {
              Optional<RevocationRow> locked = links.lockForWorker(id);
              if (locked.isEmpty() || !locked.get().claimable()) {
                return Optional.<Lease>empty();
              }
              RevocationRow row = locked.get();
              Binding binding = links.binding(row);
              String refusal = refusal(row, binding);
              if (refusal != null) {
                // A22-5: never call the provider for a binding that no longer holds.
                if (links.refuse(row.id(), row.version(), refusal) == 1) {
                  events.providerOutcome(
                      row.tenant(),
                      "access-revocation.manual-intervention",
                      row.id(),
                      row.attempts(),
                      refusal,
                      correlationId);
                  count(refusal.toLowerCase(java.util.Locale.ROOT));
                }
                return Optional.<Lease>empty();
              }
              Instant until = now().plus(properties.lease());
              if (links.lease(row.id(), row.version(), owner, until) != 1) {
                return Optional.<Lease>empty();
              }
              return Optional.of(
                  new Lease(row, row.version() + 1, row.attempts() + 1, binding.subject()));
            });
    if (leased == null || leased.isEmpty()) {
      return false;
    }
    Lease lease = leased.get();
    RevocationOutcome outcome;
    try {
      outcome = directory.revokeAccess(lease.row().tenant(), lease.subject(), lease.row().id());
    } catch (IdentityProviderUnavailableException unavailable) {
      outcome = null;
    }
    RevocationOutcome result = outcome;
    transactions.executeWithoutResult(status -> record(lease, owner, result, correlationId));
    return true;
  }

  private void record(Lease lease, UUID owner, RevocationOutcome outcome, String correlationId) {
    RevocationRow row = lease.row();
    String state;
    String code;
    Instant next = null;
    String action;
    if (outcome == RevocationOutcome.REVOKED || outcome == RevocationOutcome.ABSENT) {
      state = "COMPLETED";
      code = outcome.name();
      action = "access-revocation.idp-succeeded";
    } else if (outcome == RevocationOutcome.REFUSED) {
      state = "MANUAL_INTERVENTION";
      code = "REFUSED";
      action = "access-revocation.manual-intervention";
    } else if (lease.attempt() >= properties.maxAttempts()) {
      state = "MANUAL_INTERVENTION";
      code = "ATTEMPTS_EXHAUSTED";
      action = "access-revocation.manual-intervention";
    } else {
      state = "IDP_PENDING";
      code = "UNAVAILABLE";
      next = now().plus(properties.backoff(lease.attempt()));
      action = "access-revocation.idp-failed";
    }
    if (links.finish(row.id(), lease.version(), owner, state, code, next) != 1) {
      // The lease expired and another worker took the revocation over: it records its own outcome.
      count("lease_lost");
      return;
    }
    events.providerOutcome(row.tenant(), action, row.id(), lease.attempt(), code, correlationId);
    count(code.toLowerCase(java.util.Locale.ROOT));
  }

  /**
   * Why the revocation's binding no longer holds (A22-5), or null when the provider may be called.
   *
   * @param row the locked revocation
   * @param binding its link and membership as they are now
   * @return a closed refusal code, or null
   */
  static String refusal(RevocationRow row, Binding binding) {
    if (binding.linkTenant() == null
        || !binding.linkActive()
        || !binding.linkTenant().equals(row.tenant().value())
        || !row.employeeId().equals(binding.linkEmployee())
        || !row.membershipId().equals(binding.linkMembership())) {
      return "STALE_LINK";
    }
    if (binding.membershipTenant() == null) {
      return "MEMBERSHIP_CHANGED";
    }
    if (!binding.membershipTenant().equals(row.tenant().value())) {
      return "TENANT_MISMATCH";
    }
    if (!TenantRole.EMPLOYEE.wireName().equals(binding.membershipRole())
        || !binding.membershipRole().equals(row.membershipRole())
        || binding.subject() == null
        || binding.subject().isBlank()) {
      return "MEMBERSHIP_CHANGED";
    }
    return null;
  }

  private void refreshGauges() {
    try {
      long[] counts = links.openCounts(OVERDUE);
      pending.set(counts[0]);
      manual.set(counts[1]);
      overdue.set(counts[2]);
    } catch (DataAccessException unavailable) {
      LOG.atWarn().log("access_revocation_gauges_unavailable");
    }
  }

  private int report(String outcome, int count) {
    if (count > 0) {
      Counter.builder(METRIC)
          .description("Access revocation transitions and identity-provider outcomes")
          .tag("outcome", outcome)
          .register(registry)
          .increment(count);
      LOG.atInfo()
          .addKeyValue("outcome", outcome)
          .addKeyValue("count", count)
          .log("access_revocation_job_run");
    }
    return count;
  }

  private void count(String outcome) {
    report(outcome, 1);
  }

  private Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }
}
