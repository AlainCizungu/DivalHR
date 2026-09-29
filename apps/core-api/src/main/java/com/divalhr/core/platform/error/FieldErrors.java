package com.divalhr.core.platform.error;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects {@code VALIDATION_FAILED} field problems as safe {@code {field, constraint}} pairs.
 * Field names are ours and constraints come from a fixed vocabulary; submitted values are never
 * included.
 */
public final class FieldErrors {

  /** Fixed constraint vocabulary (docs/API-SPEC.yaml, BadRequest). */
  public enum Constraint {
    /** Missing value. */
    REQUIRED,
    /** Wrong length. */
    LENGTH,
    /** Wrong shape. */
    FORMAT,
    /** Outside the allowed range. */
    RANGE,
    /** Repeated item. */
    DUPLICATE,
    /** Property not in the contract. */
    UNKNOWN_PROPERTY
  }

  private final List<Map<String, String>> fields = new ArrayList<>();

  /**
   * Records a problem.
   *
   * @param field contract field name
   * @param constraint constraint
   * @return this collector
   */
  public FieldErrors add(String field, Constraint constraint) {
    Map<String, String> entry = new LinkedHashMap<>();
    entry.put("field", field);
    entry.put("constraint", constraint.name());
    fields.add(entry);
    return this;
  }

  /**
   * Whether no problem was recorded.
   *
   * @return true when valid
   */
  public boolean isEmpty() {
    return fields.isEmpty();
  }

  /**
   * Throws {@code VALIDATION_FAILED} listing every problem, if any.
   *
   * @throws ApiException when at least one problem was recorded
   */
  public void throwIfAny() {
    if (!fields.isEmpty()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.copyOf(fields)));
    }
  }
}
