package com.divalhr.core.platform.devseed;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.DevelopmentMembershipSeeder;
import com.divalhr.core.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Outside development the application starts without any development fixture. */
@IntegrationTest
class DevelopmentSeedAbsenceIntegrationTest {

  @Autowired private DevelopmentSeedFlywayCustomizer customizer;
  @Autowired private DevelopmentMembershipSeeder memberships;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void fixturesAreAbsentInTheTestEnvironment() {
    assertThat(customizer.enabled()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM tenant.organization WHERE created_by = 'dev-seed'"
                    + " OR name LIKE 'DEV-ONLY%'",
                Integer.class))
        .isZero();
    // MVP-012A (M8): the membership seeder is disabled and no seed membership exists.
    assertThat(memberships.enabled()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_membership"
                    + " WHERE id::text LIKE '00000000-0000-4000-8000-0000000001%'"
                    + " OR subject LIKE '00000000-0000-4000-8000-0000000000%'",
                Integer.class))
        .isZero();
  }
}
