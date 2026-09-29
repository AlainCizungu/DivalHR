package com.divalhr.core.platform.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CursorCodecTest {

  private static final String KEY = "test-only-cursor-signing-key-0000000000000001";
  private static final TenantId TENANT_A =
      new TenantId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
  private static final TenantId TENANT_B =
      new TenantId(UUID.fromString("22222222-2222-4222-8222-222222222222"));
  private static final UUID PARENT_1 = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID PARENT_2 = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final KeysetPosition POSITION =
      new KeysetPosition("KIN-01", UUID.fromString("55555555-5555-4555-8555-555555555555"));
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private static final CursorCodec CODEC = codec(KEY);

  private static CursorCodec codec(String key) {
    return new CursorCodec(new CursorProperties(key), "test", JsonMapper.builder().build());
  }

  private static CursorScope legalEntities(TenantId tenant) {
    return new CursorScope("legal-entity.list", tenant, Map.of());
  }

  private static CursorScope sites(TenantId tenant, UUID parent) {
    return new CursorScope("site.list", tenant, Map.of("legalEntityId", parent.toString()));
  }

  @Test
  void roundTripsForTheIssuingScope() {
    String cursor = CODEC.encode(sites(TENANT_A, PARENT_1), POSITION);
    assertThat(cursor).matches("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$").hasSizeLessThan(512);
    assertThat(CODEC.decode(cursor, sites(TENANT_A, PARENT_1))).isEqualTo(POSITION);
    // Opaque: no tenant, parent or raw scope in the token.
    String payload =
        new String(
            Base64.getUrlDecoder().decode(cursor.substring(0, cursor.indexOf('.'))),
            StandardCharsets.UTF_8);
    assertThat(payload).doesNotContain(TENANT_A.toString()).doesNotContain(PARENT_1.toString());
  }

  @Test
  void rejectsCrossTenantCrossOperationAndCrossParentUse() {
    String siteCursor = CODEC.encode(sites(TENANT_A, PARENT_1), POSITION);
    String legalCursor = CODEC.encode(legalEntities(TENANT_A), POSITION);
    assertInvalid(siteCursor, sites(TENANT_B, PARENT_1));
    assertInvalid(siteCursor, sites(TENANT_A, PARENT_2));
    assertInvalid(siteCursor, legalEntities(TENANT_A));
    assertInvalid(legalCursor, legalEntities(TENANT_B));
    assertInvalid(legalCursor, sites(TENANT_A, PARENT_1));
  }

  @Test
  void rejectsSignatureFailuresTruncationAndOtherKeys() {
    String cursor = CODEC.encode(legalEntities(TENANT_A), POSITION);
    int dot = cursor.indexOf('.');
    String payload = cursor.substring(0, dot);
    String signature = cursor.substring(dot + 1);
    assertInvalid(payload + "." + flip(signature), legalEntities(TENANT_A));
    assertInvalid(flip(payload) + "." + signature, legalEntities(TENANT_A));
    assertInvalid(cursor.substring(0, cursor.length() - 1), legalEntities(TENANT_A));
    assertInvalid(
        payload.substring(0, payload.length() - 4) + "." + signature, legalEntities(TENANT_A));
    assertInvalid(payload, legalEntities(TENANT_A));
    assertInvalid(payload + ".", legalEntities(TENANT_A));
    assertInvalid(
        codec("test-only-a-different-signing-key-00000000000002")
            .encode(legalEntities(TENANT_A), POSITION),
        legalEntities(TENANT_A));
  }

  @Test
  void rejectsMalformedAndOversizeInputBeforeDecoding() {
    assertInvalid("", legalEntities(TENANT_A));
    assertInvalid(null, legalEntities(TENANT_A));
    assertInvalid("not base64!.sig", legalEntities(TENANT_A));
    assertInvalid("a.b.c", legalEntities(TENANT_A));
    assertInvalid("A.B", legalEntities(TENANT_A));
    assertInvalid("a".repeat(CursorCodec.MAX_LENGTH) + ".b", legalEntities(TENANT_A));
    assertInvalid("====.====", legalEntities(TENANT_A));
  }

  @Test
  void rejectsValidlySignedPayloadsOutsideTheAllowListedSchema() {
    CursorScope scope = legalEntities(TENANT_A);
    String binding = Fingerprints.sha256(scope.canonical());
    String id = POSITION.id().toString();
    // Sanity: a hand-built valid payload is accepted, so the rejections below are schema-driven.
    assertThat(
            CODEC.decode(
                signed("{\"v\":1,\"c\":\"KIN-01\",\"i\":\"" + id + "\",\"q\":\"" + binding + "\"}"),
                scope))
        .isEqualTo(POSITION);
    for (String payload :
        new String[] {
          "{\"v\":2,\"c\":\"KIN-01\",\"i\":\"" + id + "\",\"q\":\"" + binding + "\"}",
          "{\"v\":\"1\",\"c\":\"KIN-01\",\"i\":\"" + id + "\",\"q\":\"" + binding + "\"}",
          "{\"v\":1.0,\"c\":\"KIN-01\",\"i\":\"" + id + "\",\"q\":\"" + binding + "\"}",
          "{\"v\":1,\"c\":\"KIN-01\",\"i\":\"" + id + "\",\"q\":\"" + binding + "\",\"x\":1}",
          "{\"v\":1,\"c\":\"KIN-01\",\"i\":\"" + id + "\",\"z\":\"" + binding + "\"}",
          "{\"v\":1,\"c\":\"KIN-01\",\"i\":\"" + id + "\"}",
          "{\"v\":1,\"c\":\"kin-01\",\"i\":\"" + id + "\",\"q\":\"" + binding + "\"}",
          "{\"v\":1,\"c\":\"KIN' OR 1=1\",\"i\":\"" + id + "\",\"q\":\"" + binding + "\"}",
          "{\"v\":1,\"c\":7,\"i\":\"" + id + "\",\"q\":\"" + binding + "\"}",
          "{\"v\":1,\"c\":\"KIN-01\",\"i\":\"not-a-uuid\",\"q\":\"" + binding + "\"}",
          "{\"v\":1,\"c\":\"KIN-01\",\"i\":\"" + id + "\",\"q\":\"" + "0".repeat(64) + "\"}",
          "[1,2,3,4]",
          "\"text\"",
          "{not json",
        }) {
      assertInvalid(signed(payload), scope);
    }
  }

  @Test
  void failuresCarryNoParametersOrInput() {
    String cursor = CODEC.encode(legalEntities(TENANT_A), POSITION);
    assertThatThrownBy(() -> CODEC.decode(cursor, legalEntities(TENANT_B)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.CURSOR_INVALID);
              assertThat(e.params()).isEmpty();
              assertThat(String.valueOf(e.getMessage())).doesNotContain(cursor);
            });
  }

  private static void assertInvalid(String cursor, CursorScope scope) {
    assertThatThrownBy(() -> CODEC.decode(cursor, scope))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CURSOR_INVALID));
  }

  private static String signed(String payload) {
    try {
      byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return B64.encodeToString(bytes) + "." + B64.encodeToString(mac.doFinal(bytes));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String flip(String part) {
    char replacement = part.charAt(0) == 'A' ? 'B' : 'A';
    return replacement + part.substring(1);
  }
}
