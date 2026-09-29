package com.divalhr.core.platform.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality outcome counters. Tags are limited to a fixed operation name and a fixed outcome
 * vocabulary: never organization names, idempotency keys, tokens, request bodies or subjects.
 */
@Component
public class OperationMetrics {

  /** Counter name. */
  public static final String METRIC = "divalhr.operation.outcomes";

  /** Fixed outcome vocabulary. */
  public enum Outcome {
    /** A new resource was created. */
    CREATED,
    /** An earlier successful response was replayed. */
    REPLAYED,
    /** The idempotency key was reused with a different payload. */
    IDEMPOTENCY_CONFLICT,
    /** The request failed validation. */
    VALIDATION_FAILED,
    /** The caller was authenticated but not authorized. */
    DENIED,
    /** A business key (such as a code) already exists. */
    DUPLICATE_CONFLICT,
    /** A referenced resource does not exist in the caller's tenant. */
    NOT_FOUND,
    /** A page was returned. */
    LISTED,
    /** An existing resource was changed (for example a site's first region assignment). */
    UPDATED,
    /** The requested state already held, so nothing was changed or recorded. */
    UNCHANGED,
    /** The resource's current state forbids the change (for example a region already assigned). */
    STATE_CONFLICT,
    /** An unexpected server-side failure. */
    FAILURE
  }

  private final MeterRegistry registry;

  /**
   * Creates the recorder.
   *
   * @param registry meter registry
   */
  public OperationMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Increments the counter for an operation outcome.
   *
   * @param operation fixed operation name
   * @param outcome outcome
   */
  public void record(String operation, Outcome outcome) {
    Counter.builder(METRIC)
        .description("Outcomes of DivalHR business operations")
        .tag("operation", operation)
        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
        .register(registry)
        .increment();
  }
}
