package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.PackageDigests;
import com.divalhr.core.payroll.domain.PackageDigests.ApprovalBasis;
import com.divalhr.core.payroll.domain.PackageDigests.Slip;
import com.divalhr.core.payroll.domain.PayrollVoidReason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-094C: the approval digest equals the V46 SQL function; the preview digests bind inputs. */
class PackageDigestsTest {

  private static final UUID TENANT = uuid('1');
  private static final UUID ENTITY = uuid('2');
  private static final UUID RUN = uuid('3');
  private static final UUID CALCULATION = uuid('4');
  private static final UUID PACK = uuid('5');
  private static final UUID POLICY = uuid('6');
  private static final Instant EXPIRES = Instant.parse("2026-04-01T10:26:00Z");

  // Computed by an independent script that follows the text of
  // payroll.payroll_package_approval_digest in V46 (header, tenant, entity, period, currency, run,
  // calculation, pack, policy, totals, then one line per payslip in employee-number byte order and
  // employee id order), not by calling the production class.
  private static final String APPROVAL_GOLDEN =
      "074c7c754a8b1e9936e47e3a1246e2fec019b746d6d1c5126abec3a3993af223";
  private static final String PREVIEW_GOLDEN =
      "0d52c6f055fa0b46e8dbee45b1d156b2ac6079798f4844947c052b50961a8939";
  private static final String PREVIEW_NO_PACK_GOLDEN =
      "d0702e2c3e4ac78b755e229f02b6783ee1db025aa15096a05235ad20be4271ac";
  private static final String VOID_OTHER_GOLDEN =
      "5b97393ad074f89caea5d68b830d61a93f76bd825212b65b04087c28987d9479";
  private static final String VOID_SOURCE_GOLDEN =
      "1bf68ae12fa04af41d64308c2d7ec5dfeecf18d674de366f03ffc20bb380ba82";

  private static UUID uuid(char digit) {
    String block = String.valueOf(digit);
    return UUID.fromString(
        block.repeat(8)
            + "-"
            + block.repeat(4)
            + "-4"
            + block.repeat(3)
            + "-8"
            + block.repeat(3)
            + "-"
            + block.repeat(12));
  }

  private static UUID slipId(int n) {
    return UUID.fromString("00000000-0000-4000-8000-00000000000" + n);
  }

  private static String hex(char digit) {
    return String.valueOf(digit).repeat(64);
  }

  private static ApprovalBasis basis() {
    return new ApprovalBasis(
        TENANT,
        ENTITY,
        "2026-03",
        RUN,
        2,
        hex('1'),
        CALCULATION,
        3,
        hex('2'),
        hex('3'),
        hex('4'),
        hex('5'),
        PACK,
        hex('6'),
        POLICY,
        4,
        383000L,
        29960L,
        48590L,
        353040L,
        431590L,
        3);
  }

  /** The same basis with the field at the given position changed. */
  private static ApprovalBasis changed(int position) {
    Object[] f = {
      TENANT,
      ENTITY,
      "2026-03",
      RUN,
      2,
      hex('1'),
      CALCULATION,
      3,
      hex('2'),
      hex('3'),
      hex('4'),
      hex('5'),
      PACK,
      hex('6'),
      POLICY,
      4,
      383000L,
      29960L,
      48590L,
      353040L,
      431590L,
      3
    };
    if (f[position] instanceof UUID) {
      f[position] = uuid('9');
    } else if (f[position] instanceof Integer number) {
      f[position] = number + 1;
    } else if (f[position] instanceof Long number) {
      f[position] = number + 1;
    } else {
      f[position] = hex('9');
    }
    return new ApprovalBasis(
        (UUID) f[0],
        (UUID) f[1],
        (String) f[2],
        (UUID) f[3],
        (Integer) f[4],
        (String) f[5],
        (UUID) f[6],
        (Integer) f[7],
        (String) f[8],
        (String) f[9],
        (String) f[10],
        (String) f[11],
        (UUID) f[12],
        (String) f[13],
        (UUID) f[14],
        (Integer) f[15],
        (Long) f[16],
        (Long) f[17],
        (Long) f[18],
        (Long) f[19],
        (Long) f[20],
        (Integer) f[21]);
  }

  private static List<Slip> slips() {
    return List.of(
        new Slip(slipId(3), "a-1", "c".repeat(64)),
        new Slip(slipId(2), "B-2", "b".repeat(64)),
        new Slip(slipId(4), "A-10", "d".repeat(64)),
        new Slip(slipId(1), "A-10", "a".repeat(64)));
  }

  @Test
  void theApprovalDigestIsByteIdenticalToTheDatabaseFunction() {
    assertThat(PackageDigests.approval(basis(), slips())).isEqualTo(APPROVAL_GOLDEN);
  }

  @Test
  void slipsAreOrderedByEmployeeNumberInByteOrderThenByEmployeeId() {
    // Byte order puts every upper-case letter before lower case: A-10, A-10, B-2, a-1; the two
    // A-10 slips are ordered by id. The golden digest encodes exactly that order.
    List<Slip> shuffled = new ArrayList<>(slips());
    for (int round = 0; round < 6; round++) {
      Collections.rotate(shuffled, 1);
      assertThat(PackageDigests.approval(basis(), shuffled)).isEqualTo(APPROVAL_GOLDEN);
    }
    Collections.reverse(shuffled);
    assertThat(PackageDigests.approval(basis(), shuffled)).isEqualTo(APPROVAL_GOLDEN);
  }

  @Test
  void theInputOrderOfTheSlipsIsNeverMutated() {
    List<Slip> input = new ArrayList<>(slips());
    PackageDigests.approval(basis(), input);
    assertThat(input).isEqualTo(slips());
  }

  @Test
  void theApprovalDigestChangesWithEveryBasisFieldAndEverySlipFact() {
    Set<String> digests = new HashSet<>();
    digests.add(PackageDigests.approval(basis(), slips()));
    for (int position = 0; position < 22; position++) {
      String digest = PackageDigests.approval(changed(position), slips());
      assertThat(digests.add(digest)).as("basis field " + position).isTrue();
    }
    List<Slip> otherDigest = new ArrayList<>(slips());
    otherDigest.set(0, new Slip(slipId(3), "a-1", "e".repeat(64)));
    assertThat(digests.add(PackageDigests.approval(basis(), otherDigest))).isTrue();
    List<Slip> otherNumber = new ArrayList<>(slips());
    otherNumber.set(0, new Slip(slipId(3), "a-2", "c".repeat(64)));
    assertThat(digests.add(PackageDigests.approval(basis(), otherNumber))).isTrue();
    List<Slip> otherId = new ArrayList<>(slips());
    otherId.set(0, new Slip(slipId(5), "a-1", "c".repeat(64)));
    assertThat(digests.add(PackageDigests.approval(basis(), otherId))).isTrue();
    List<Slip> fewer = slips().subList(1, 4);
    assertThat(digests.add(PackageDigests.approval(basis(), fewer))).isTrue();
    assertThat(digests.add(PackageDigests.approval(basis(), List.of()))).isTrue();
  }

  @Test
  void theApprovalDigestIsLowerCaseHexOf64Characters() {
    assertThat(PackageDigests.approval(basis(), slips())).matches("[0-9a-f]{64}");
  }

  @Test
  void theSnapshotDigestIsSha256OfTheUtf8Text() {
    assertThat(PackageDigests.snapshotDigest("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(PackageDigests.snapshotDigest(""))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assertThat(PackageDigests.sha256("é"))
        .isEqualTo("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c");
  }

  private static String preview(Object... changes) {
    Object[] a = {
      TENANT,
      ENTITY,
      RUN,
      2,
      CALCULATION,
      3,
      hex('1'),
      hex('2'),
      hex('4'),
      hex('5'),
      POLICY,
      4,
      true,
      2,
      EXPIRES
    };
    for (int i = 0; i < changes.length; i += 2) {
      a[(Integer) changes[i]] = changes[i + 1];
    }
    return PackageDigests.approvalPreview(
        (UUID) a[0],
        (UUID) a[1],
        (UUID) a[2],
        (Integer) a[3],
        (UUID) a[4],
        (Integer) a[5],
        (String) a[6],
        (String) a[7],
        (String) a[8],
        (String) a[9],
        (UUID) a[10],
        (Integer) a[11],
        (Boolean) a[12],
        (Integer) a[13],
        (Instant) a[14]);
  }

  @Test
  void theApprovalPreviewDigestIsStableAndHasAGoldenValue() {
    assertThat(preview()).isEqualTo(PREVIEW_GOLDEN);
    assertThat(preview()).isEqualTo(preview());
    assertThat(
            PackageDigests.approvalPreview(
                TENANT,
                ENTITY,
                RUN,
                2,
                CALCULATION,
                3,
                hex('1'),
                hex('2'),
                hex('4'),
                null,
                POLICY,
                4,
                false,
                0,
                EXPIRES))
        .isEqualTo(PREVIEW_NO_PACK_GOLDEN);
  }

  @Test
  void theApprovalPreviewDigestChangesWhenAnyInputChanges() {
    Set<String> digests = new HashSet<>();
    digests.add(preview());
    Object[][] variants = {
      {0, uuid('9')},
      {1, uuid('9')},
      {2, uuid('9')},
      {3, 3},
      {4, uuid('9')},
      {5, 4},
      {6, hex('9')},
      {7, hex('9')},
      {8, hex('9')},
      {9, hex('9')},
      {9, "null"},
      {10, uuid('9')},
      {11, 5},
      {12, false},
      {13, 3},
      {14, EXPIRES.plusSeconds(60)}
    };
    for (Object[] variant : variants) {
      assertThat(digests.add(preview(variant))).as("input " + variant[0]).isTrue();
    }
    assertThat(digests).hasSize(17);
  }

  @Test
  void aMissingRulePackDigestIsDistinguishedFromAnyRealDigest() {
    String missing =
        PackageDigests.approvalPreview(
            TENANT,
            ENTITY,
            RUN,
            2,
            CALCULATION,
            3,
            hex('1'),
            hex('2'),
            hex('4'),
            null,
            POLICY,
            4,
            true,
            2,
            EXPIRES);
    assertThat(missing).isNotEqualTo(preview());
  }

  private static String voidPreview(Object... changes) {
    Object[] a = {
      TENANT,
      ENTITY,
      PACK,
      2,
      hex('6'),
      PayrollVoidReason.OTHER_CONTROLLED,
      "Wrong month selected",
      EXPIRES
    };
    for (int i = 0; i < changes.length; i += 2) {
      a[(Integer) changes[i]] = changes[i + 1];
    }
    return PackageDigests.voidPreview(
        (UUID) a[0],
        (UUID) a[1],
        (UUID) a[2],
        (Integer) a[3],
        (String) a[4],
        (PayrollVoidReason) a[5],
        (String) a[6],
        (Instant) a[7]);
  }

  @Test
  void theVoidPreviewDigestHasGoldenValuesAndIsStable() {
    assertThat(voidPreview()).isEqualTo(VOID_OTHER_GOLDEN);
    assertThat(voidPreview()).isEqualTo(voidPreview());
    assertThat(voidPreview(5, PayrollVoidReason.SOURCE_CORRECTION, 6, null))
        .isEqualTo(VOID_SOURCE_GOLDEN);
  }

  @Test
  void theVoidPreviewDigestChangesWhenAnyInputChanges() {
    Set<String> digests = new HashSet<>();
    digests.add(voidPreview());
    Object[][] variants = {
      {0, uuid('9')},
      {1, uuid('9')},
      {2, uuid('9')},
      {3, 3},
      {4, hex('9')},
      {5, PayrollVoidReason.ADMINISTRATIVE_ERROR},
      {6, "Wrong month selected!"},
      {6, null},
      {6, ""},
      {7, EXPIRES.plusSeconds(60)}
    };
    for (Object[] variant : variants) {
      assertThat(digests.add(voidPreview(variant))).as("input " + variant[0]).isTrue();
    }
  }

  @Test
  void theVoidPreviewDigestCarriesTheExplanationOnlyAsItsOwnHash() {
    // Two explanations that differ only in a hidden detail never collide, and the digest never
    // equals a digest computed over the plain text of the explanation.
    String secret = "Contains-a-restricted-explanation";
    String digest = voidPreview(6, secret);
    assertThat(digest).doesNotContain("restricted");
    assertThat(digest).isNotEqualTo(PackageDigests.sha256(secret));
    assertThat(digest).matches("[0-9a-f]{64}");
  }
}
