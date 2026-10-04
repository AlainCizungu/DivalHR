package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A new contract template (MVP-030). Mirrors {@code CreateContractTemplate}. Values stay raw JSON
 * values until the service validates them; they are never logged.
 */
@Schema(name = "CreateContractTemplate")
public class CreateContractTemplateRequest extends StrictRequest {

  private Object code;
  private Object name;
  private Object contractType;

  /**
   * Tenant-unique code.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCode() {
    return code;
  }

  /**
   * Sets: tenant-unique code.
   *
   * @param code raw value
   */
  public void setCode(Object code) {
    this.code = code;
  }

  /**
   * Administrative label.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getName() {
    return name;
  }

  /**
   * Sets: administrative label.
   *
   * @param name raw value
   */
  public void setName(Object name) {
    this.name = name;
  }

  /**
   * Contract type code.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getContractType() {
    return contractType;
  }

  /**
   * Sets: contract type code.
   *
   * @param contractType raw value
   */
  public void setContractType(Object contractType) {
    this.contractType = contractType;
  }
}
