package com.divalhr.core.people.api;

import com.divalhr.core.people.domain.ImportColumn;
import com.divalhr.core.people.domain.RowErrorCode;
import com.divalhr.core.people.domain.RowStatus;
import com.divalhr.core.people.domain.RowValues;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * A page of import rows (MVP-020). Mirrors {@code EmployeeImportRowPage}. Valid rows of an open
 * import carry confidential personal data: served only with {@code Cache-Control: private,
 * no-store} and never logged. Invalid rows carry only column keys and codes.
 *
 * @param items rows in row order
 * @param nextCursor continuation, or {@code null} on the last page
 */
@Schema(name = "EmployeeImportRowPage")
public record EmployeeImportRowPageResponse(
    List<Row> items, @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {

  /** Copies the rows. */
  public EmployeeImportRowPageResponse {
    items = List.copyOf(items);
  }

  /**
   * One row.
   *
   * @param rowNumber 1-based data row number
   * @param status status
   * @param errors errors (column key and code only)
   * @param values normalized values of a valid row of an open import, else {@code null}
   */
  @Schema(name = "EmployeeImportRow")
  public record Row(
      int rowNumber,
      RowStatus status,
      List<Error> errors,
      @JsonInclude(JsonInclude.Include.ALWAYS) Values values) {

    /** Copies the errors. */
    public Row {
      errors = List.copyOf(errors);
    }

    @Override
    public String toString() {
      return "Row[" + rowNumber + "]";
    }
  }

  /**
   * One error.
   *
   * @param column column key
   * @param code row error code
   */
  @Schema(name = "EmployeeImportRowError")
  public record Error(String column, RowErrorCode code) {

    /**
     * Builds the error.
     *
     * @param column column
     * @param code code
     * @return the error
     */
    public static Error of(ImportColumn column, RowErrorCode code) {
      return new Error(column.key(), code);
    }
  }

  /**
   * Normalized values (confidential).
   *
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   * @param startDate start date
   * @param legalEntityCode legal entity code
   * @param siteCode site code
   * @param departmentCode department code or null
   * @param costCenterCode cost center code or null
   * @param teamCode team code or null
   */
  @Schema(name = "EmployeeImportRowValues")
  public record Values(
      String employeeNumber,
      String givenNames,
      String familyName,
      LocalDate startDate,
      String legalEntityCode,
      String siteCode,
      @JsonInclude(JsonInclude.Include.ALWAYS) String departmentCode,
      @JsonInclude(JsonInclude.Include.ALWAYS) String costCenterCode,
      @JsonInclude(JsonInclude.Include.ALWAYS) String teamCode) {

    /**
     * Builds the values.
     *
     * @param v row values
     * @return response values
     */
    public static Values of(RowValues v) {
      return new Values(
          v.employeeNumber(),
          v.givenNames(),
          v.familyName(),
          v.startDate(),
          v.legalEntityCode(),
          v.siteCode(),
          v.departmentCode(),
          v.costCenterCode(),
          v.teamCode());
    }

    @Override
    public String toString() {
      return "Values[redacted]";
    }
  }
}
