package com.divalhr.core.documents.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of contract templates and contracts (MVP-030), mirroring the contract schemas.
 * Template text is Confidential organization data; snapshots, periods, types and evidence are
 * Restricted HR data. Served only with {@code Cache-Control: private, no-store}, never logged.
 * Codes (types, languages, states, reasons) are stable API codes, never translated.
 */
public final class ContractResponses {

  private ContractResponses() {}

  /**
   * One language line of a template.
   *
   * @param locale language
   * @param approvedVersionId the approved version, or {@code null}
   * @param approvedVersionNumber its number, or {@code null}
   * @param draftVersionId the open draft, or {@code null}
   */
  @Schema(name = "ContractTemplateLine")
  public record TemplateLine(
      String locale,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID approvedVersionId,
      @JsonInclude(JsonInclude.Include.ALWAYS) Integer approvedVersionNumber,
      @JsonInclude(JsonInclude.Include.ALWAYS) UUID draftVersionId) {}

  /**
   * A template in a list.
   *
   * @param id template
   * @param code code
   * @param name administrative label
   * @param contractType type code
   * @param lines French and English lines
   * @param createdAt creation
   */
  @Schema(name = "ContractTemplateSummary")
  public record TemplateSummary(
      UUID id,
      String code,
      String name,
      String contractType,
      List<TemplateLine> lines,
      Instant createdAt) {

    /** Copies the lines. */
    public TemplateSummary {
      lines = List.copyOf(lines);
    }
  }

  /**
   * A page of templates.
   *
   * @param items templates by code
   * @param nextCursor continuation, or {@code null}
   */
  @Schema(name = "ContractTemplatePage")
  public record TemplatePage(
      List<TemplateSummary> items, @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {

    /** Copies the items. */
    public TemplatePage {
      items = List.copyOf(items);
    }
  }

  /**
   * A version without its text.
   *
   * @param id version
   * @param locale language
   * @param versionNumber number per language
   * @param state DRAFT, APPROVED or RETIRED
   * @param placeholders placeholder keys used
   * @param createdAt creation
   * @param updatedAt last draft edit
   * @param approvedAt approval, or {@code null}
   * @param retiredAt retirement, or {@code null}
   * @param version row version
   */
  @Schema(name = "ContractTemplateVersionSummary")
  public record VersionSummary(
      UUID id,
      String locale,
      int versionNumber,
      String state,
      List<String> placeholders,
      Instant createdAt,
      Instant updatedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant approvedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant retiredAt,
      long version) {

    /** Copies the placeholders. */
    public VersionSummary {
      placeholders = List.copyOf(placeholders);
    }
  }

  /**
   * A template with its versions (without text).
   *
   * @param id template
   * @param code code
   * @param name administrative label
   * @param contractType type code
   * @param createdAt creation
   * @param versions versions, per language newest first
   */
  @Schema(name = "ContractTemplate")
  public record Template(
      UUID id,
      String code,
      String name,
      String contractType,
      Instant createdAt,
      List<VersionSummary> versions) {

    /** Copies the versions. */
    public Template {
      versions = List.copyOf(versions);
    }
  }

  /**
   * A version with its text.
   *
   * @param id version
   * @param templateId template
   * @param locale language
   * @param versionNumber number per language
   * @param state DRAFT, APPROVED or RETIRED
   * @param title title
   * @param body grammar v1 body
   * @param placeholders placeholder keys used
   * @param bodySha256 text digest
   * @param grammarVersion grammar version
   * @param digestVersion digest version
   * @param createdAt creation
   * @param updatedAt last draft edit
   * @param approvedAt approval, or {@code null}
   * @param retiredAt retirement, or {@code null}
   * @param version row version
   */
  @Schema(name = "ContractTemplateVersion")
  public record Version(
      UUID id,
      UUID templateId,
      String locale,
      int versionNumber,
      String state,
      String title,
      String body,
      List<String> placeholders,
      String bodySha256,
      int grammarVersion,
      int digestVersion,
      Instant createdAt,
      Instant updatedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant approvedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant retiredAt,
      long version) {

    /** Copies the placeholders. */
    public Version {
      placeholders = List.copyOf(placeholders);
    }

    @Override
    public String toString() {
      return "Version[" + id + "]";
    }
  }

  /**
   * One grammar problem: a closed reason and a line number, never the text.
   *
   * @param reason closed reason
   * @param line 1-based body line, 0 for the title
   */
  @Schema(name = "ContractTemplateProblem")
  public record Problem(String reason, int line) {}

  /**
   * A validation report.
   *
   * @param valid whether there is no problem
   * @param problems problems (at most 50)
   * @param placeholders placeholder keys used
   */
  @Schema(name = "ContractTemplateValidation")
  public record Validation(boolean valid, List<Problem> problems, List<String> placeholders) {

    /** Copies the lists. */
    public Validation {
      problems = List.copyOf(problems);
      placeholders = List.copyOf(placeholders);
    }
  }

  /**
   * One rendered block (plain text; clients render text nodes only).
   *
   * @param type h1, h2, p or li
   * @param text text
   */
  @Schema(name = "ContractBlock")
  public record Block(String type, String text) {}

  /**
   * A rendered snapshot.
   *
   * @param title title
   * @param blocks blocks
   */
  @Schema(name = "ContractSnapshot")
  public record Snapshot(String title, List<Block> blocks) {

    /** Copies the blocks. */
    public Snapshot {
      blocks = List.copyOf(blocks);
    }

    @Override
    public String toString() {
      return "Snapshot[redacted]";
    }
  }

  /**
   * The integrity reference of a snapshot (A30-4).
   *
   * @param snapshotSha256 snapshot digest
   * @param digestAlgorithm SHA-256
   * @param digestVersion digest version
   * @param grammarVersion grammar version
   * @param rendererVersion renderer version
   */
  @Schema(name = "ContractIntegrity")
  public record Integrity(
      String snapshotSha256,
      String digestAlgorithm,
      int digestVersion,
      int grammarVersion,
      int rendererVersion) {}

  /**
   * A contract preview.
   *
   * @param employmentId employment
   * @param employmentVersion employment version to confirm
   * @param templateVersionId approved version
   * @param contractType the template's type
   * @param locale the version's language
   * @param startDate start
   * @param endDate end, or {@code null}
   * @param snapshot the exact snapshot an issue would store
   * @param integrity its integrity reference
   * @param warnings closed warnings
   * @param previewDigest digest to confirm
   */
  @Schema(name = "ContractPreview")
  public record Preview(
      UUID employmentId,
      long employmentVersion,
      UUID templateVersionId,
      String contractType,
      String locale,
      LocalDate startDate,
      @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate,
      Snapshot snapshot,
      Integrity integrity,
      List<String> warnings,
      String previewDigest) {

    /** Copies the warnings. */
    public Preview {
      warnings = List.copyOf(warnings);
    }

    @Override
    public String toString() {
      return "Preview[" + templateVersionId + "]";
    }
  }

  /**
   * Acknowledgement evidence as shown: never membership, link, subject or correlation IDs.
   *
   * @param acknowledgedAt server time
   * @param statementCode statement code
   * @param statementVersion statement version
   * @param statementLocale language the statement was shown in
   * @param statementText the statement, re-rendered from its code, version and language
   * @param statementSha256 statement digest
   * @param evidenceSha256 evidence digest
   */
  @Schema(name = "ContractAcknowledgementEvidence")
  public record Evidence(
      Instant acknowledgedAt,
      String statementCode,
      int statementVersion,
      String statementLocale,
      String statementText,
      String statementSha256,
      String evidenceSha256) {}

  /**
   * A contract in an administrator's list.
   *
   * @param id contract
   * @param employmentId employment
   * @param templateId template
   * @param templateVersionId template version
   * @param contractType type code
   * @param locale language
   * @param startDate start
   * @param endDate end, or {@code null}
   * @param state ISSUED, ACKNOWLEDGED or VOID
   * @param issuedAt issue time
   * @param acknowledgedAt acknowledgement, or {@code null}
   * @param voidedAt void, or {@code null}
   * @param voidReason void reason, or {@code null}
   * @param version row version
   */
  @Schema(name = "ContractSummary")
  public record Summary(
      UUID id,
      UUID employmentId,
      UUID templateId,
      UUID templateVersionId,
      String contractType,
      String locale,
      LocalDate startDate,
      @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate,
      String state,
      Instant issuedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant acknowledgedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant voidedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) String voidReason,
      long version) {

    @Override
    public String toString() {
      return "Summary[" + id + "]";
    }
  }

  /**
   * A page of an employee's contracts.
   *
   * @param items contracts newest first
   * @param nextCursor continuation, or {@code null}
   */
  @Schema(name = "ContractPage")
  public record Page(
      List<Summary> items, @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {

    /** Copies the items. */
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * A contract with its snapshot and evidence (administrator).
   *
   * @param id contract
   * @param employmentId employment
   * @param templateId template
   * @param templateVersionId template version
   * @param contractType type code
   * @param locale language
   * @param startDate start
   * @param endDate end, or {@code null}
   * @param state ISSUED, ACKNOWLEDGED or VOID
   * @param issuedAt issue time
   * @param acknowledgedAt acknowledgement, or {@code null}
   * @param voidedAt void, or {@code null}
   * @param voidReason void reason, or {@code null}
   * @param version row version
   * @param snapshot snapshot
   * @param integrity integrity reference
   * @param acknowledgement evidence, or {@code null}
   */
  @Schema(name = "Contract")
  public record Contract(
      UUID id,
      UUID employmentId,
      UUID templateId,
      UUID templateVersionId,
      String contractType,
      String locale,
      LocalDate startDate,
      @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate,
      String state,
      Instant issuedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant acknowledgedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant voidedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) String voidReason,
      long version,
      Snapshot snapshot,
      Integrity integrity,
      @JsonInclude(JsonInclude.Include.ALWAYS) Evidence acknowledgement) {

    @Override
    public String toString() {
      return "Contract[" + id + "]";
    }
  }

  /**
   * One of the caller's own contracts in a list.
   *
   * @param id contract
   * @param contractType type code
   * @param locale language
   * @param startDate start
   * @param endDate end, or {@code null}
   * @param state ISSUED, ACKNOWLEDGED or VOID
   * @param issuedAt issue time
   * @param acknowledgedAt acknowledgement, or {@code null}
   */
  @Schema(name = "MyContractSummary")
  public record MySummary(
      UUID id,
      String contractType,
      String locale,
      LocalDate startDate,
      @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate,
      String state,
      Instant issuedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant acknowledgedAt) {

    @Override
    public String toString() {
      return "MySummary[" + id + "]";
    }
  }

  /**
   * A page of the caller's own contracts.
   *
   * @param items contracts newest first
   * @param nextCursor continuation, or {@code null}
   */
  @Schema(name = "MyContractPage")
  public record MyPage(
      List<MySummary> items, @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {

    /** Copies the items. */
    public MyPage {
      items = List.copyOf(items);
    }
  }

  /**
   * The acknowledgement statement in one language (server-owned, versioned).
   *
   * @param code statement code
   * @param version statement version
   * @param locale language
   * @param text exact text
   * @param sha256 statement digest
   */
  @Schema(name = "AcknowledgementStatement")
  public record Statement(String code, int version, String locale, String text, String sha256) {}

  /**
   * One of the caller's own contracts with its snapshot.
   *
   * @param id contract
   * @param contractType type code
   * @param locale language
   * @param startDate start
   * @param endDate end, or {@code null}
   * @param state ISSUED, ACKNOWLEDGED or VOID
   * @param issuedAt issue time
   * @param acknowledgedAt acknowledgement, or {@code null}
   * @param snapshot snapshot
   * @param integrity integrity reference
   * @param statements the current statement in French and English
   * @param acknowledgement evidence, or {@code null}
   */
  @Schema(name = "MyContract")
  public record MyContract(
      UUID id,
      String contractType,
      String locale,
      LocalDate startDate,
      @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate,
      String state,
      Instant issuedAt,
      @JsonInclude(JsonInclude.Include.ALWAYS) Instant acknowledgedAt,
      Snapshot snapshot,
      Integrity integrity,
      List<Statement> statements,
      @JsonInclude(JsonInclude.Include.ALWAYS) Evidence acknowledgement) {

    /** Copies the statements. */
    public MyContract {
      statements = List.copyOf(statements);
    }

    @Override
    public String toString() {
      return "MyContract[" + id + "]";
    }
  }

  /**
   * The result of an acknowledgement.
   *
   * @param contract the contract with its evidence
   * @param alreadyAcknowledged whether it had been acknowledged before this request
   */
  @Schema(name = "ContractAcknowledgementResult")
  public record AcknowledgementResult(MyContract contract, boolean alreadyAcknowledged) {}
}
