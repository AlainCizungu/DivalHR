package com.divalhr.core.platform.devseed;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Outside development the application starts without any development fixture. */
@IntegrationTest
class DevelopmentSeedAbsenceIntegrationTest {

  @Autowired private DevelopmentSeedFlywayCustomizer customizer;
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
  }
}
