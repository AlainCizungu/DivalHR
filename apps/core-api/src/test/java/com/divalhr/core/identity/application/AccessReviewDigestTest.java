package com.divalhr.core.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Golden vectors of canonical digest v1 (MVP-012B, R1 and B1): the exact bytes and their lower-case
 * SHA-256, computed independently. Any change here requires a new digest version.
 */
class AccessReviewDigestTest {

  private static final UUID UPPER = UUID.fromString("0B6C8A3E-6F0E-4C1A-9D2B-3F4E5A6B7C8D");
  private static final UUID ONE = UUID.fromString("00000000-0000-4000-8000-000000000001");

  @Test
  void listPageVector() {
    String text = AccessReviewDigest.listText(true, true, List.of(UPPER, ONE));
    assertThat(text)
        .isEqualTo(
            "DIVALHR-ACCESS-REVIEW-DIGEST\n"
                + "version=1\n"
                + "view=list\n"
                + "page=first\n"
                + "more=true\n"
                + "count=2\n"
                + "m=0b6c8a3e-6f0e-4c1a-9d2b-3f4e5a6b7c8d\n"
                + "m=00000000-0000-4000-8000-000000000001\n");
    assertThat(AccessReviewDigest.sha256(text))
        .isEqualTo("7d240b13dc6c621cdcb1e30e557d214678ef4de24184b86ad2cc32dc02b10d52");
  }

  @Test
  void nextPageVector() {
    String text = AccessReviewDigest.listText(false, false, List.of(ONE));
    assertThat(text)
        .isEqualTo(
            "DIVALHR-ACCESS-REVIEW-DIGEST\nversion=1\nview=list\npage=next\nmore=false\ncount=1\n"
                + "m=00000000-0000-4000-8000-000000000001\n");
    assertThat(AccessReviewDigest.sha256(text))
        .isEqualTo("8658a94f70c626f7007ecef032ae65400da54c57c35057d0ca328a4d7d394bf8");
  }

  @Test
  void emptyLookupVectorIsDeterministic() {
    String text = AccessReviewDigest.lookupText(List.of());
    assertThat(text)
        .isEqualTo(
            "DIVALHR-ACCESS-REVIEW-DIGEST\nversion=1\nview=lookup\npage=single\nmore=false\n"
                + "count=0\n");
    assertThat(AccessReviewDigest.sha256(text))
        .isEqualTo("d97073d92f6f0ef38e781a7bebd0c1e1cc4f80149607708eb9363fc3bbf4c4e5");
  }

  @Test
  void summaryVectorKeepsTheFixedRoleOrder() {
    String text = AccessReviewDigest.summaryText(3, 12);
    assertThat(text)
        .isEqualTo(
            "DIVALHR-ACCESS-REVIEW-DIGEST\nversion=1\nview=summary\npage=single\nmore=false\n"
                + "count=2\nrole=tenant-admin;n=3\nrole=employee;n=12\n");
    assertThat(AccessReviewDigest.sha256(text))
        .isEqualTo("cbd91d596fc4d2e9e5b672cd8fd524fed3ff2e0ed48a9c68f16a3deabf740d08");
  }

  @Test
  void bytesAreUtf8LfOnlyWithAFinalLfAndNoBlankLines() {
    for (String text :
        List.of(
            AccessReviewDigest.listText(true, false, List.of(ONE)),
            AccessReviewDigest.lookupText(List.of()),
            AccessReviewDigest.summaryText(0, 0))) {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      assertThat(bytes[bytes.length - 1]).isEqualTo((byte) 0x0A);
      assertThat(text).doesNotContain("\r").doesNotContain("\n\n");
      assertThat(new String(bytes, StandardCharsets.US_ASCII)).isEqualTo(text);
    }
    assertThat(AccessReviewDigest.sha256("x")).matches("^[0-9a-f]{64}$");
    assertThatThrownBy(() -> AccessReviewDigest.summaryText(-1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
