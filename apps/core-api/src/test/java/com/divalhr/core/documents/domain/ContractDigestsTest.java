package com.divalhr.core.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.documents.domain.ContractDigests.EvidenceBinding;
import com.divalhr.core.documents.domain.ContractDigests.PreviewBinding;
import com.divalhr.core.documents.domain.TemplateGrammar.BlockType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A30-4: golden digests (computed independently from the documented preimages), distinct domains,
 * and sensitivity to every version, locale and field.
 */
class ContractDigestsTest {

  private static final UUID VERSION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID EMPLOYEE = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID EMPLOYMENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CONTRACT = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID MEMBERSHIP = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID LINK = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final RenderedSnapshot SNAPSHOT =
      new RenderedSnapshot(
          "fr",
          "Contrat de travail",
          List.of(
              new RenderedSnapshot.Block(BlockType.H1, "Article 1"),
              new RenderedSnapshot.Block(BlockType.P, "Le salarié Élodie N’Kanza.")));
  private static final String CANONICAL =
      "{\"blocks\":[{\"t\":\"h1\",\"x\":\"Article 1\"},{\"t\":\"p\",\"x\":\"Le salarié Élodie"
          + " N’Kanza.\"}],\"grammar\":1,\"locale\":\"fr\",\"renderer\":1,\"title\":\"Contrat de"
          + " travail\"}";
  private static final String SNAPSHOT_SHA =
      "8f82c39adc630d8761139366bd615b94bb870675e9e9b0255aebe7ba37f9d436";
  private static final String STATEMENT_FR =
      "45f289d8c3a6c674638e256ab57ed2e801f91e9902ddbdd0adaba3cc56b7d486";
  private static final String STATEMENT_EN =
      "38ab7ae392ef6e41de7c53d677663edf571c0d5f486b06c457db6e474a1879d3";

  @Test
  void theCanonicalSnapshotAndEachDigestArePinned() {
    assertThat(CanonicalSnapshot.of(SNAPSHOT)).isEqualTo(CANONICAL);
    assertThat(ContractDigests.snapshot(CANONICAL)).isEqualTo(SNAPSHOT_SHA);
    assertThat(
            ContractDigests.templateBody(
                "fr", "Contrat de travail", "# Article 1\nLe salarié {{employee.fullName}}."))
        .isEqualTo("d92be7ad13813a81798ceaa694b26d275c4cae90bf8ed407dfc903843a9b4fc1");
    assertThat(ContractDigests.preview(preview(7, "fr", null)))
        .isEqualTo("19268c930c618ea4be6bbd6022f7c0398841d08ba0fa9d3bf280b537163b3682");
    assertThat(ContractDigests.evidence(evidence("fr", STATEMENT_FR)))
        .isEqualTo("73f7b5e8ef2d56eceea1ee776070631ca733eb2a5262d196761a6aabdb3cd7c6");
  }

  @Test
  void theVersionOneStatementsAndTheirDigestsArePinned() {
    List<AcknowledgementStatement> current = AcknowledgementStatement.current();
    assertThat(current)
        .extracting(AcknowledgementStatement::locale, AcknowledgementStatement::text)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "fr",
                "Je confirme avoir reçu ce contrat et en avoir pris connaissance tel qu’il est"
                    + " affiché. Cette confirmation n’est pas une signature électronique."),
            org.assertj.core.groups.Tuple.tuple(
                "en",
                "I confirm that I have received and reviewed this contract as displayed. This"
                    + " confirmation is not an electronic signature."));
    assertThat(current)
        .extracting(AcknowledgementStatement::sha256)
        .containsExactly(STATEMENT_FR, STATEMENT_EN);
    assertThat(AcknowledgementStatement.find("RECEIVED_AND_REVIEWED", 2, "fr")).isEmpty();
    assertThat(AcknowledgementStatement.find("SIGNED", 1, "fr")).isEmpty();
    assertThat(AcknowledgementStatement.find("RECEIVED_AND_REVIEWED", 1, "de")).isEmpty();
  }

  @Test
  void domainsNeverCollideAndEveryFieldCounts() {
    // The same payload under two domains gives two digests.
    Set<String> digests = new HashSet<>();
    digests.add(ContractDigests.snapshot("x"));
    digests.add(ContractDigests.statement("RECEIVED_AND_REVIEWED", 1, "fr", "x"));
    digests.add(ContractDigests.templateBody("fr", "x", "x"));
    assertThat(digests).hasSize(3);
    assertThat(ContractDigests.preimage("A", "1", "x"))
        .isNotEqualTo(ContractDigests.preimage("B", "1", "x"));
    // Statement: code, version and locale count.
    String base = ContractDigests.statement("RECEIVED_AND_REVIEWED", 1, "fr", "texte");
    assertThat(ContractDigests.statement("RECEIVED_AND_REVIEWED", 2, "fr", "texte"))
        .isNotEqualTo(base);
    assertThat(ContractDigests.statement("RECEIVED_AND_REVIEWED", 1, "en", "texte"))
        .isNotEqualTo(base);
    assertThat(ContractDigests.statement("OTHER", 1, "fr", "texte")).isNotEqualTo(base);
    // Preview: version, locale and end date count.
    String preview = ContractDigests.preview(preview(7, "fr", null));
    assertThat(ContractDigests.preview(preview(8, "fr", null))).isNotEqualTo(preview);
    assertThat(ContractDigests.preview(preview(7, "en", null))).isNotEqualTo(preview);
    assertThat(ContractDigests.preview(preview(7, "fr", LocalDate.of(2027, 1, 1))))
        .isNotEqualTo(preview);
    // Evidence: the statement locale and digest count.
    assertThat(ContractDigests.evidence(evidence("en", STATEMENT_FR)))
        .isNotEqualTo(ContractDigests.evidence(evidence("fr", STATEMENT_FR)));
    assertThat(ContractDigests.evidence(evidence("fr", STATEMENT_EN)))
        .isNotEqualTo(ContractDigests.evidence(evidence("fr", STATEMENT_FR)));
  }

  @Test
  void comparisonIsExactAndNullSafe() {
    assertThat(ContractDigests.equal(SNAPSHOT_SHA, SNAPSHOT_SHA)).isTrue();
    assertThat(ContractDigests.equal(SNAPSHOT_SHA, SNAPSHOT_SHA.toUpperCase())).isFalse();
    assertThat(ContractDigests.equal(SNAPSHOT_SHA, null)).isFalse();
    assertThat(ContractDigests.equal(null, SNAPSHOT_SHA)).isFalse();
  }

  private static PreviewBinding preview(long version, String locale, LocalDate end) {
    return new PreviewBinding(
        VERSION_ID,
        "b".repeat(64),
        EMPLOYEE,
        EMPLOYMENT,
        version,
        "PERMANENT",
        locale,
        LocalDate.of(2026, 11, 1),
        end,
        SNAPSHOT_SHA);
  }

  private static EvidenceBinding evidence(String locale, String statement) {
    return new EvidenceBinding(
        CONTRACT,
        EMPLOYEE,
        MEMBERSHIP,
        LINK,
        SNAPSHOT_SHA,
        "RECEIVED_AND_REVIEWED",
        1,
        locale,
        statement,
        Instant.parse("2026-10-04T09:30:00.123456Z"));
  }
}
