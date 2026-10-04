package com.divalhr.core.documents.application;

import com.divalhr.core.documents.api.ContractResponses.Block;
import com.divalhr.core.documents.api.ContractResponses.Contract;
import com.divalhr.core.documents.api.ContractResponses.Evidence;
import com.divalhr.core.documents.api.ContractResponses.Integrity;
import com.divalhr.core.documents.api.ContractResponses.MyContract;
import com.divalhr.core.documents.api.ContractResponses.MySummary;
import com.divalhr.core.documents.api.ContractResponses.Snapshot;
import com.divalhr.core.documents.api.ContractResponses.Statement;
import com.divalhr.core.documents.api.ContractResponses.Summary;
import com.divalhr.core.documents.api.ContractResponses.Version;
import com.divalhr.core.documents.api.ContractResponses.VersionSummary;
import com.divalhr.core.documents.domain.AcknowledgementStatement;
import com.divalhr.core.documents.domain.ContractDigests;
import com.divalhr.core.documents.domain.ContractValueFormats;
import com.divalhr.core.documents.domain.RenderedSnapshot;
import com.divalhr.core.documents.domain.TemplateGrammar;
import com.divalhr.core.documents.internal.JdbcContractRepository.AcknowledgementRow;
import com.divalhr.core.documents.internal.JdbcContractRepository.ContractRow;
import com.divalhr.core.documents.internal.JdbcContractTemplateRepository.VersionRow;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds MVP-030 response bodies from stored rows. A stored snapshot is returned exactly as its
 * canonical text says; the evidence never exposes membership, link, subject or correlation IDs.
 */
@Component
public class ContractViews {

  private final JsonMapper json;

  /**
   * Creates the builder.
   *
   * @param json JSON mapper
   */
  public ContractViews(JsonMapper json) {
    this.json = json;
  }

  static Version version(UUID templateId, VersionRow row) {
    return new Version(
        row.id(),
        templateId,
        row.locale(),
        row.number(),
        row.state(),
        row.title(),
        row.body(),
        row.placeholders(),
        row.bodySha256(),
        TemplateGrammar.VERSION,
        ContractDigests.VERSION,
        row.createdAt(),
        row.updatedAt(),
        row.approvedAt(),
        row.retiredAt(),
        row.version());
  }

  static VersionSummary versionSummary(VersionRow row) {
    return new VersionSummary(
        row.id(),
        row.locale(),
        row.number(),
        row.state(),
        row.placeholders(),
        row.createdAt(),
        row.updatedAt(),
        row.approvedAt(),
        row.retiredAt(),
        row.version());
  }

  static Integrity integrity(String snapshotSha256) {
    return new Integrity(
        snapshotSha256,
        ContractDigests.ALGORITHM,
        ContractDigests.VERSION,
        TemplateGrammar.VERSION,
        ContractValueFormats.RENDERER_VERSION);
  }

  static Snapshot snapshot(RenderedSnapshot rendered) {
    List<Block> blocks = new ArrayList<>(rendered.blocks().size());
    rendered.blocks().forEach(b -> blocks.add(new Block(b.type().code(), b.text())));
    return new Snapshot(rendered.title(), blocks);
  }

  /** The snapshot exactly as its stored canonical text says. */
  Snapshot snapshot(String canonical) {
    JsonNode root = json.readTree(canonical);
    List<Block> blocks = new ArrayList<>();
    for (JsonNode block : root.get("blocks")) {
      blocks.add(new Block(block.get("t").asString(), block.get("x").asString()));
    }
    return new Snapshot(root.get("title").asString(), blocks);
  }

  static Evidence evidence(AcknowledgementRow row) {
    if (row == null) {
      return null;
    }
    String text =
        AcknowledgementStatement.find(
                row.statementCode(), row.statementVersion(), row.statementLocale())
            .map(AcknowledgementStatement::text)
            .orElseThrow(() -> new IllegalStateException("unknown acknowledgement statement"));
    return new Evidence(
        row.acknowledgedAt(),
        row.statementCode(),
        row.statementVersion(),
        row.statementLocale(),
        text,
        row.statementSha256(),
        row.evidenceSha256());
  }

  static Summary summary(ContractRow row) {
    return new Summary(
        row.id(),
        row.employmentId(),
        row.templateId(),
        row.templateVersionId(),
        row.contractType(),
        row.locale(),
        row.startDate(),
        row.endDate(),
        row.state(),
        row.issuedAt(),
        row.acknowledgedAt(),
        row.voidedAt(),
        row.voidReason(),
        row.version());
  }

  Contract contract(ContractRow row, AcknowledgementRow acknowledgement) {
    return new Contract(
        row.id(),
        row.employmentId(),
        row.templateId(),
        row.templateVersionId(),
        row.contractType(),
        row.locale(),
        row.startDate(),
        row.endDate(),
        row.state(),
        row.issuedAt(),
        row.acknowledgedAt(),
        row.voidedAt(),
        row.voidReason(),
        row.version(),
        snapshot(row.canonical()),
        integrity(row.snapshotSha256()),
        evidence(acknowledgement));
  }

  static MySummary mySummary(ContractRow row) {
    return new MySummary(
        row.id(),
        row.contractType(),
        row.locale(),
        row.startDate(),
        row.endDate(),
        row.state(),
        row.issuedAt(),
        row.acknowledgedAt());
  }

  MyContract myContract(ContractRow row, AcknowledgementRow acknowledgement) {
    List<Statement> statements = new ArrayList<>();
    for (AcknowledgementStatement statement : AcknowledgementStatement.current()) {
      statements.add(
          new Statement(
              statement.code(),
              statement.version(),
              statement.locale(),
              statement.text(),
              statement.sha256()));
    }
    return new MyContract(
        row.id(),
        row.contractType(),
        row.locale(),
        row.startDate(),
        row.endDate(),
        row.state(),
        row.issuedAt(),
        row.acknowledgedAt(),
        snapshot(row.canonical()),
        integrity(row.snapshotSha256()),
        statements,
        evidence(acknowledgement));
  }
}
