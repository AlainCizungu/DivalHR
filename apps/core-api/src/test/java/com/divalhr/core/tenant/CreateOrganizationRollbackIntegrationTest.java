package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.tenant.api.CreateOrganizationRequest;
import com.divalhr.core.tenant.application.CreateOrganizationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A failure after the organization insert leaves no partial data in any of the five tables. */
@IntegrationTest
class CreateOrganizationRollbackIntegrationTest {

  @Autowired private CreateOrganizationService service;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private OutboxWriter outbox;

  @Test
  void failureAfterOrganizationInsertRollsEverythingBack() {
    String key = Organizations.newKey();
    String name = Organizations.uniqueName();
    int auditBefore = total("platform.audit_event");
    int outboxBefore = total("platform.outbox_event");
    int currenciesBefore = total("tenant.organization_currency");
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(outbox)
        .append(any(EventEnvelope.class));

    assertThatThrownBy(
            () ->
                service.create(
                    "sub-rollback",
                    key,
                    CreateOrganizationRequest.of(
                        name, "CD", "fr", "Africa/Kinshasa", List.of("CDF")),
                    "rollback-corr-0001"))
        .hasMessageContaining("simulated outbox failure");

    assertThat(count("tenant.organization", "name = ?", name)).isZero();
    assertThat(total("tenant.organization_currency")).isEqualTo(currenciesBefore);
    assertThat(total("platform.audit_event")).isEqualTo(auditBefore);
    assertThat(total("platform.outbox_event")).isEqualTo(outboxBefore);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isZero();
  }

  private int total(String table) {
    Integer value = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return value == null ? 0 : value;
  }

  private int count(String table, String where, Object arg) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, arg);
    return value == null ? 0 : value;
  }
}
