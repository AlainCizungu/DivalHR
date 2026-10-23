package com.divalhr.core.integrations.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicEventTest {

  private static final UUID ID = UUID.fromString("6f1c1f0e-8a8b-4b43-9a3e-0f6f7b0c2a01");
  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-00000000000a");
  private static final UUID RES = UUID.fromString("7a2d3c4e-1b5f-4c6d-8e7f-9a0b1c2d3e01");

  @Test
  void canonicalBytesAreStableAndExact() {
    var event =
        new PublicEvent(
            ID,
            WebhookEventType.LEAVE_REQUEST_APPROVED,
            Instant.parse("2026-10-20T09:30:00Z"),
            TENANT,
            RES,
            1);
    assertThat(new String(event.canonicalBytes(), StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"schemaVersion\":1,\"eventId\":\"6f1c1f0e-8a8b-4b43-9a3e-0f6f7b0c2a01\","
                + "\"eventType\":\"leave.request.approved.v1\",\"occurredAt\":\"2026-10-20T09:30:00Z\","
                + "\"tenantId\":\"00000000-0000-4000-8000-00000000000a\",\"resource\":{\"kind\":\"leave-request\","
                + "\"id\":\"7a2d3c4e-1b5f-4c6d-8e7f-9a0b1c2d3e01\",\"status\":\"APPROVED\",\"version\":1},"
                + "\"links\":[\"/admin/leave-approvals\"]}");
  }

  @Test
  void testEventHasNoLink() {
    var event =
        new PublicEvent(
            ID,
            WebhookEventType.WEBHOOK_TEST,
            Instant.parse("2026-10-20T09:30:00Z"),
            TENANT,
            RES,
            1);
    assertThat(new String(event.canonicalBytes(), StandardCharsets.UTF_8))
        .endsWith("\"links\":[]}");
  }

  @Test
  void everyInternalLeaveVariantMapsToTheSameTwoPublicEvents() {
    for (String t :
        new String[] {
          "people.leave-request.approved.v1",
          "people.leave-request.routing-exception-approved.v1",
          "people.leave-request.substitute-approved.v1"
        }) {
      assertThat(WebhookEventType.fromInternal(t))
          .contains(WebhookEventType.LEAVE_REQUEST_APPROVED);
    }
    for (String t :
        new String[] {
          "people.leave-request.rejected.v1",
          "people.leave-request.routing-exception-rejected.v1",
          "people.leave-request.substitute-rejected.v1"
        }) {
      assertThat(WebhookEventType.fromInternal(t))
          .contains(WebhookEventType.LEAVE_REQUEST_REJECTED);
    }
  }

  @Test
  void otherInternalEventsAreNotPublic() {
    assertThat(WebhookEventType.fromInternal("people.leave-request.submitted.v1")).isEmpty();
    assertThat(WebhookEventType.fromInternal("tenant.organization-created.v1")).isEmpty();
    assertThat(WebhookEventType.fromInternal("webhook.test.v1")).isEmpty();
    assertThat(WebhookEventType.fromInternal(null)).isEmpty();
    assertThat(WebhookEventType.subscribableCodes()).doesNotContain("webhook.test.v1").hasSize(6);
  }
}
