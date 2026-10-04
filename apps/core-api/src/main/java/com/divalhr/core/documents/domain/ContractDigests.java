package com.divalhr.core.documents.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The domain-separated, versioned digests of MVP-030 (A30-4). Every preimage starts with its own
 * domain string and its digest version, names every version it depends on, and joins its fields
 * with LF; text fields are NFC with LF line breaks; the algorithm is SHA-256. A digest of one kind
 * can therefore never be accepted as another, and a change of grammar, renderer, statement or
 * digest semantics changes every digest. Golden tests pin each preimage.
 *
 * <p>Client-supplied digests are confirmations against concurrent change only: the server
 * recomputes every value from its own data and compares in constant time ({@link #equal}).
 */
public final class ContractDigests {

  /** Digest algorithm name, as returned by the API. */
  public static final String ALGORITHM = "SHA-256";

  /** Digest semantics version (pinned in the database). */
  public static final int VERSION = 1;

  static final String TEMPLATE_BODY = "DIVALHR-CONTRACT-TEMPLATE-BODY";
  static final String SNAPSHOT = "DIVALHR-CONTRACT-SNAPSHOT";
  static final String PREVIEW = "DIVALHR-CONTRACT-PREVIEW";
  static final String STATEMENT = "DIVALHR-CONTRACT-ACKNOWLEDGEMENT-STATEMENT";
  static final String EVIDENCE = "DIVALHR-CONTRACT-ACKNOWLEDGEMENT";

  private static final DateTimeFormatter MICROS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

  private ContractDigests() {}

  /**
   * A template version's text.
   *
   * @param locale language
   * @param title normalized title
   * @param body normalized body
   * @return hex digest
   */
  public static String templateBody(String locale, String title, String body) {
    return sha256(
        TEMPLATE_BODY,
        Integer.toString(VERSION),
        "grammar=" + TemplateGrammar.VERSION,
        locale,
        title,
        body);
  }

  /**
   * A snapshot, over its canonical JSON.
   *
   * @param canonical {@link CanonicalSnapshot#of}
   * @return hex digest
   */
  public static String snapshot(String canonical) {
    return sha256(
        SNAPSHOT,
        Integer.toString(VERSION),
        ALGORITHM,
        "grammar=" + TemplateGrammar.VERSION,
        "renderer=" + ContractValueFormats.RENDERER_VERSION,
        canonical);
  }

  /**
   * What a preview binds the commit to.
   *
   * @param binding the preview's identifiers, versions, command and snapshot digest
   * @return hex digest
   */
  public static String preview(PreviewBinding binding) {
    return sha256(
        PREVIEW,
        Integer.toString(VERSION),
        binding.templateVersionId().toString(),
        binding.bodySha256(),
        binding.employeeId().toString(),
        binding.employmentId().toString(),
        Long.toString(binding.employmentVersion()),
        binding.contractType(),
        binding.locale(),
        binding.startDate().toString(),
        binding.endDate() == null ? "-" : binding.endDate().toString(),
        "snapshot-digest-version=" + VERSION,
        "grammar=" + TemplateGrammar.VERSION,
        "renderer=" + ContractValueFormats.RENDERER_VERSION,
        binding.snapshotSha256());
  }

  /**
   * The fields a preview digest covers.
   *
   * @param templateVersionId approved version
   * @param bodySha256 the version's text digest
   * @param employeeId employee
   * @param employmentId employment
   * @param employmentVersion employment version
   * @param contractType closed type code
   * @param locale language
   * @param startDate contract start
   * @param endDate contract end, or {@code null}
   * @param snapshotSha256 the rendered snapshot's digest
   */
  public record PreviewBinding(
      UUID templateVersionId,
      String bodySha256,
      UUID employeeId,
      UUID employmentId,
      long employmentVersion,
      String contractType,
      String locale,
      LocalDate startDate,
      LocalDate endDate,
      String snapshotSha256) {}

  /**
   * An acknowledgement statement (exact text, no line breaks).
   *
   * @param code statement code
   * @param version statement version
   * @param locale language
   * @param text exact NFC text
   * @return hex digest
   */
  public static String statement(String code, int version, String locale, String text) {
    return sha256(STATEMENT, Integer.toString(version), code, locale, text);
  }

  /**
   * The acknowledgement evidence.
   *
   * @param evidence what the evidence binds
   * @return hex digest
   */
  public static String evidence(EvidenceBinding evidence) {
    return sha256(
        EVIDENCE,
        Integer.toString(VERSION),
        evidence.contractId().toString(),
        evidence.employeeId().toString(),
        evidence.membershipId().toString(),
        evidence.linkId().toString(),
        "snapshot-digest-version=" + VERSION,
        "grammar=" + TemplateGrammar.VERSION,
        "renderer=" + ContractValueFormats.RENDERER_VERSION,
        evidence.snapshotSha256(),
        "statement="
            + evidence.statementCode()
            + "/"
            + evidence.statementVersion()
            + "/"
            + evidence.statementLocale(),
        evidence.statementSha256(),
        MICROS.format(evidence.acknowledgedAt()));
  }

  /**
   * The fields an evidence digest covers.
   *
   * @param contractId contract
   * @param employeeId employee
   * @param membershipId the caller's membership
   * @param linkId the caller's active link
   * @param snapshotSha256 the contract's snapshot digest
   * @param statementCode statement code
   * @param statementVersion statement version
   * @param statementLocale statement language
   * @param statementSha256 statement digest
   * @param acknowledgedAt server time (microseconds)
   */
  public record EvidenceBinding(
      UUID contractId,
      UUID employeeId,
      UUID membershipId,
      UUID linkId,
      String snapshotSha256,
      String statementCode,
      int statementVersion,
      String statementLocale,
      String statementSha256,
      Instant acknowledgedAt) {}

  /**
   * Constant-time comparison of two hex digests.
   *
   * @param expected server value
   * @param supplied client value
   * @return true when equal
   */
  public static boolean equal(String expected, String supplied) {
    if (expected == null || supplied == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * The preimage of a digest (golden tests pin it).
   *
   * @param fields fields, the first being the domain
   * @return the LF-joined text
   */
  static String preimage(String... fields) {
    return String.join("\n", fields);
  }

  private static String sha256(String... fields) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(preimage(fields).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
