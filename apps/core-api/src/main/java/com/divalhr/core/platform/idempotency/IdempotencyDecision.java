package com.divalhr.core.platform.idempotency;

/** Result of reserving an idempotency key inside the caller's transaction. */
public sealed interface IdempotencyDecision {

  /** The key is new: perform the operation and then call {@code complete}. */
  record Proceed() implements IdempotencyDecision {}

  /**
   * The same request already succeeded: replay its stored response.
   *
   * @param response stored response
   */
  record Replay(StoredResponse response) implements IdempotencyDecision {}
}
