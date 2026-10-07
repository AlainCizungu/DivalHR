package com.divalhr.core.documents.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of the contract expiration queue (MVP-031A, Issue #73). Employee numbers and
 * names are Confidential; end dates are Restricted HR data. Served only with {@code Cache-Control:
 * private, no-store}, never logged. Categories and unit kinds are stable API codes.
 */
public final class ContractExpirationResponses {

  private ContractExpirationResponses() {}

  /**
   * Eligible contracts per category (and in total) under the same rules and filters as the list.
   *
   * @param expired end date before the business date
   * @param next30Days ending today through day 30
   * @param days31To60 ending in 31 to 60 days
   * @param days61To90 ending in 61 to 90 days
   * @param total every eligible contract
   */
  @Schema(name = "ContractExpirationCounts")
  public record Counts(
      long expired, long next30Days, long days31To60, long days61To90, long total) {}

  /**
   * The department or cost center of an employee's current placement (organizational data).
   *
   * @param id unit
   * @param kind {@code DEPARTMENT} or {@code COST_CENTER}
   * @param code unit code
   * @param name display name
   */
  @Schema(name = "ContractExpirationUnit")
  public record Unit(UUID id, String kind, String code, String name) {}

  /**
   * One contract needing attention (the coverage head of an employment).
   *
   * @param contractId contract
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   * @param unit current department or cost center, or {@code null}
   * @param endDate contract end date
   * @param category expiration category
   * @param daysUntilEnd days from the business date to the end date (negative when overdue)
   */
  @Schema(name = "ContractExpiration")
  public record Item(
      UUID contractId,
      UUID employeeId,
      String employeeNumber,
      String givenNames,
      String familyName,
      @JsonInclude(JsonInclude.Include.ALWAYS) Unit unit,
      LocalDate endDate,
      String category,
      long daysUntilEnd) {

    @Override
    public String toString() {
      return "Item[" + contractId + "]";
    }
  }

  /**
   * A page of the queue with the counts of the same filters (before the category filter).
   *
   * @param asOf business date the categories were computed for
   * @param timezone the organization's IANA time zone
   * @param counts counts of the same search and unit, every category
   * @param items contracts, most urgent first
   * @param nextCursor continuation, or {@code null}
   */
  @Schema(name = "ContractExpirationPage")
  public record Page(
      LocalDate asOf,
      String timezone,
      Counts counts,
      List<Item> items,
      @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {

    /** Copies the items. */
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * The unfiltered counts (the administrator home card).
   *
   * @param asOf business date the categories were computed for
   * @param timezone the organization's IANA time zone
   * @param counts counts, every category
   */
  @Schema(name = "ContractExpirationSummary")
  public record Summary(LocalDate asOf, String timezone, Counts counts) {}
}
