package com.divalhr.core.identity.application;

import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.identity.internal.JdbcInvitationRepository.CredentialSetupRow;
import com.divalhr.core.platform.web.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background invitation work (decision D-4). Each job claims rows with {@code FOR UPDATE SKIP
 * LOCKED} (or conditional updates), so several Core API instances can run them without a lock
 * service. Jobs log counts only, never addresses, tokens or subjects.
 */
@Component
public class InvitationJobs {

  /** Job outcome metric (tags {@code job}, {@code result}). */
  public static final String METRIC = "divalhr.invitation.jobs";

  private static final Logger LOG = LoggerFactory.getLogger(InvitationJobs.class);

  private final JdbcInvitationRepository invitations;
  private final InvitationExpiry expiry;
  private final InvitationAcceptance acceptance;
  private final TransactionTemplate transactions;
  private final InvitationProperties properties;
  private final MeterRegistry registry;
  private final Clock clock;

  /**
   * Creates the jobs.
   *
   * @param invitations invitation repository
   * @param expiry expiry helper
   * @param acceptance acceptance workflow
   * @param transactions transaction template
   * @param properties settings
   * @param registry metrics
   */
  public InvitationJobs(
      JdbcInvitationRepository invitations,
      InvitationExpiry expiry,
      InvitationAcceptance acceptance,
      TransactionTemplate transactions,
      InvitationProperties properties,
      MeterRegistry registry) {
    this.invitations = invitations;
    this.expiry = expiry;
    this.acceptance = acceptance;
    this.transactions = transactions;
    this.properties = properties;
    this.registry = registry;
    this.clock = Clock.systemUTC();
  }

  /**
   * Materializes the expiry of pending invitations past their expiry (token erased, audited).
   *
   * @return invitations expired
   */
  public int expireDue() {
    String correlationId = CorrelationId.resolve(null);
    Integer expired =
        transactions.execute(
            status -> {
              Instant now = now();
              int count = 0;
              for (Invitation invitation :
                  invitations.claimDueExpirations(now, properties.jobBatchSize())) {
                if (expiry.expire(invitation, now, correlationId)) {
                  count++;
                }
              }
              return count;
            });
    return report("expire", expired == null ? 0 : expired);
  }

  /**
   * Turns deliveries still queued after the stale interval into FAILED (amendment A3). They are
   * never retried: their token no longer exists anywhere.
   *
   * @return deliveries marked failed
   */
  public int failStaleDeliveries() {
    Instant now = now();
    return report(
        "delivery_stale",
        invitations.failStaleDeliveries(now.minus(properties.deliveryStaleAfter()), now));
  }

  /**
   * Deletes terminal invitations past retention (decision D-3); email addresses leave with them.
   *
   * @return invitations deleted
   */
  public int deleteRetained() {
    Instant before = now().minus(properties.retention());
    int total = 0;
    int deleted;
    do {
      deleted = invitations.deleteRetained(before, properties.jobBatchSize());
      total += deleted;
    } while (deleted == properties.jobBatchSize());
    return report("retention", total);
  }

  /**
   * Takes over acceptances whose lease expired and completes, releases or expires them.
   *
   * @return acceptances taken over
   */
  public int recoverStaleAcceptances() {
    String correlationId = CorrelationId.resolve(null);
    UUID owner = UUID.randomUUID();
    List<UUID> ids =
        transactions.execute(
            status -> {
              Instant now = now();
              return invitations.takeOverStaleAcceptances(
                  now, owner, now.plus(properties.acceptanceLease()), properties.jobBatchSize());
            });
    int count = ids == null ? 0 : ids.size();
    if (ids != null) {
      for (UUID id : ids) {
        try {
          acceptance.recover(id, owner, correlationId);
        } catch (RuntimeException deferred) {
          // Provider unreachable or similar: the lease expires again and a later run retries.
          LOG.atWarn().addKeyValue("invitationId", id).log("invitation_accept_recovery_failed");
        }
      }
    }
    return report("acceptance_recovery", count);
  }

  /**
   * Retries due "choose your password" requests (guardrail 4). Claimed rows are deferred by one
   * retry interval inside the claim, so a second instance does not repeat them concurrently.
   *
   * @return setups attempted
   */
  public int retryCredentialSetups() {
    List<CredentialSetupRow> due =
        transactions.execute(
            status -> {
              Instant now = now();
              List<CredentialSetupRow> rows =
                  invitations.claimDueCredentialSetups(now, properties.jobBatchSize());
              for (CredentialSetupRow row : rows) {
                invitations.recordCredentialSetup(
                    row.invitationId(),
                    "PENDING",
                    row.attempts(),
                    now.plus(properties.credentialSetupRetry()));
              }
              return rows;
            });
    int count = due == null ? 0 : due.size();
    if (due != null) {
      for (CredentialSetupRow row : due) {
        acceptance.requestCredentialSetup(row.invitationId(), row.subject(), row.attempts());
      }
    }
    return report("credential_setup", count);
  }

  private int report(String job, int count) {
    if (count > 0) {
      Counter.builder(METRIC)
          .description("Rows handled by invitation background jobs")
          .tag("job", job)
          .register(registry)
          .increment(count);
      LOG.atInfo().addKeyValue("job", job).addKeyValue("count", count).log("invitation_job_run");
    }
    return count;
  }

  private Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }
}
