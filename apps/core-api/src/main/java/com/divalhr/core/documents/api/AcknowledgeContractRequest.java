package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the employee was shown when acknowledging (MVP-030, A30-4). Mirrors {@code
 * AcknowledgeContract}. Values stay raw JSON values until the service validates them; they are
 * never logged.
 */
@Schema(name = "AcknowledgeContract")
public class AcknowledgeContractRequest extends StrictRequest {

  private Object snapshotSha256;
  private Object snapshotDigestVersion;
  private Object grammarVersion;
  private Object rendererVersion;
  private Object statementCode;
  private Object statementVersion;
  private Object statementLocale;
  private Object statementSha256;

  /**
   * Snapshot digest displayed.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getSnapshotSha256() {
    return snapshotSha256;
  }

  /**
   * Sets: snapshot digest displayed.
   *
   * @param snapshotSha256 raw value
   */
  public void setSnapshotSha256(Object snapshotSha256) {
    this.snapshotSha256 = snapshotSha256;
  }

  /**
   * Snapshot digest version.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getSnapshotDigestVersion() {
    return snapshotDigestVersion;
  }

  /**
   * Sets: snapshot digest version.
   *
   * @param snapshotDigestVersion raw value
   */
  public void setSnapshotDigestVersion(Object snapshotDigestVersion) {
    this.snapshotDigestVersion = snapshotDigestVersion;
  }

  /**
   * Grammar version.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getGrammarVersion() {
    return grammarVersion;
  }

  /**
   * Sets: grammar version.
   *
   * @param grammarVersion raw value
   */
  public void setGrammarVersion(Object grammarVersion) {
    this.grammarVersion = grammarVersion;
  }

  /**
   * Renderer version.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getRendererVersion() {
    return rendererVersion;
  }

  /**
   * Sets: renderer version.
   *
   * @param rendererVersion raw value
   */
  public void setRendererVersion(Object rendererVersion) {
    this.rendererVersion = rendererVersion;
  }

  /**
   * Statement code.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getStatementCode() {
    return statementCode;
  }

  /**
   * Sets: statement code.
   *
   * @param statementCode raw value
   */
  public void setStatementCode(Object statementCode) {
    this.statementCode = statementCode;
  }

  /**
   * Statement version.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getStatementVersion() {
    return statementVersion;
  }

  /**
   * Sets: statement version.
   *
   * @param statementVersion raw value
   */
  public void setStatementVersion(Object statementVersion) {
    this.statementVersion = statementVersion;
  }

  /**
   * Language the statement was shown in.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getStatementLocale() {
    return statementLocale;
  }

  /**
   * Sets: language the statement was shown in.
   *
   * @param statementLocale raw value
   */
  public void setStatementLocale(Object statementLocale) {
    this.statementLocale = statementLocale;
  }

  /**
   * Statement digest displayed.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getStatementSha256() {
    return statementSha256;
  }

  /**
   * Sets: statement digest displayed.
   *
   * @param statementSha256 raw value
   */
  public void setStatementSha256(Object statementSha256) {
    this.statementSha256 = statementSha256;
  }
}
