package com.divalhr.core.identity.application;

import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.identity.internal.JdbcMembershipRepository;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DEVELOPMENT ONLY (MVP-012A, M8). Gives the four published tenant seed users of the development
 * realm ({@code infrastructure/docker/keycloak/realm-divalhr-dev.json}, fixed user IDs) their
 * tenant memberships, so that they keep working behind the membership gate. Runs after Flyway and
 * only when {@code divalhr.environment=development}; in every other environment it does nothing,
 * and V10 itself contains no development rows. Idempotent. The seed rows have no source invitation
 * and therefore no stored address (V10 rejects unanchored addresses); their lookups are computed
 * from the published seed addresses with the configured key. No platform-administrator membership.
 */
@Component
public class DevelopmentMembershipSeeder implements ApplicationRunner {

  /** Published development seed memberships (fixed realm user IDs; tenants A and B). */
  static final List<Seed> SEEDS =
      List.of(
          new Seed(
              "00000000-0000-4000-8000-0000000001a1",
              "00000000-0000-4000-8000-0000000000a1",
              "00000000-0000-4000-8000-00000000000a",
              TenantRole.TENANT_ADMIN,
              "dev-admin-a@example.com"),
          new Seed(
              "00000000-0000-4000-8000-0000000001a2",
              "00000000-0000-4000-8000-0000000000a2",
              "00000000-0000-4000-8000-00000000000a",
              TenantRole.EMPLOYEE,
              "dev-employee-a@example.com"),
          new Seed(
              "00000000-0000-4000-8000-0000000001b1",
              "00000000-0000-4000-8000-0000000000b1",
              "00000000-0000-4000-8000-00000000000b",
              TenantRole.TENANT_ADMIN,
              "dev-admin-b@example.com"),
          new Seed(
              "00000000-0000-4000-8000-0000000001b2",
              "00000000-0000-4000-8000-0000000000b2",
              "00000000-0000-4000-8000-00000000000b",
              TenantRole.EMPLOYEE,
              "dev-employee-b@example.com"));

  private final boolean development;
  private final JdbcMembershipRepository memberships;
  private final EmailLookup lookups;
  private final TransactionTemplate transactions;

  /**
   * Creates the seeder.
   *
   * @param environment deployment environment
   * @param memberships membership repository
   * @param lookups address lookup
   * @param transactions transaction template
   */
  public DevelopmentMembershipSeeder(
      @Value("${divalhr.environment}") String environment,
      JdbcMembershipRepository memberships,
      EmailLookup lookups,
      TransactionTemplate transactions) {
    this.development =
        environment != null && "development".equals(environment.trim().toLowerCase(Locale.ROOT));
    this.memberships = memberships;
    this.lookups = lookups;
    this.transactions = transactions;
  }

  /**
   * Whether the seeder runs (for start-up tests).
   *
   * @return true only in development
   */
  public boolean enabled() {
    return development;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    if (!development) {
      return;
    }
    Instant now = Instant.now();
    transactions.executeWithoutResult(
        status -> {
          for (Seed seed : SEEDS) {
            EmailAddress address =
                EmailAddress.parse(seed.email())
                    .orElseThrow(() -> new IllegalStateException("seed address"));
            memberships.insertDevelopmentSeed(
                new TenantId(UUID.fromString(seed.tenant())),
                UUID.fromString(seed.membership()),
                seed.subject(),
                seed.role(),
                lookups.of(address),
                now);
          }
        });
  }

  /**
   * One seed membership.
   *
   * @param membership membership id
   * @param subject fixed realm user id
   * @param tenant tenant id
   * @param role tenant role
   * @param email published seed address (only its lookup is stored)
   */
  record Seed(String membership, String subject, String tenant, TenantRole role, String email) {}
}
