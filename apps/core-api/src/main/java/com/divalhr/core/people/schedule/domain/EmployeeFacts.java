package com.divalhr.core.people.schedule.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The facts an assignment is checked against, read for one employee of the tenant: its directory
 * names (current data, returned but never stored with a slot), employments, placements, separations
 * and published assigned intervals overlapping the schedule's window.
 *
 * @param employeeId employee
 * @param employeeNumber employee number
 * @param givenNames given names
 * @param familyName family name
 * @param employments employments overlapping the window
 * @param placements active placements overlapping the window
 * @param separations scheduled or effective separations of those employments
 * @param published published assigned intervals overlapping the window
 */
public record EmployeeFacts(
    UUID employeeId,
    String employeeNumber,
    String givenNames,
    String familyName,
    List<Employment> employments,
    List<Placement> placements,
    List<Separation> separations,
    List<Interval> published) {

  /** Defensively copies the lists. */
  public EmployeeFacts {
    employments = List.copyOf(employments);
    placements = List.copyOf(placements);
    separations = List.copyOf(separations);
    published = List.copyOf(published);
  }

  @Override
  public String toString() {
    return "EmployeeFacts[redacted]";
  }

  /**
   * An employment period and its version (every change, correction and separation bumps it).
   *
   * @param id employment
   * @param from first day
   * @param to last day, or {@code null}
   * @param version optimistic version
   */
  public record Employment(UUID id, LocalDate from, LocalDate to, long version) {}

  /**
   * An active placement.
   *
   * @param id assignment row
   * @param siteId placed site
   * @param from first day
   * @param to last day, or {@code null}
   */
  public record Placement(UUID id, UUID siteId, LocalDate from, LocalDate to) {}

  /**
   * A scheduled or effective separation.
   *
   * @param id separation
   * @param state {@code SCHEDULED} or {@code EFFECTIVE}
   * @param effectiveAt the instant it takes effect
   */
  public record Separation(UUID id, String state, Instant effectiveAt) {}

  /**
   * A published assigned interval, half-open.
   *
   * @param slotId slot
   * @param start start instant
   * @param end end instant
   */
  public record Interval(UUID slotId, Instant start, Instant end) {}
}
