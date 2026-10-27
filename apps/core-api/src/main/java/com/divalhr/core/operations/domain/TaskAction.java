package com.divalhr.core.operations.domain;

import java.time.Instant;

/**
 * One immutable action of a task's history (MVP-080, D80-6). It carries no user text: the note and
 * the explanation live in the task projection.
 *
 * @param seq increasing sequence from 1
 * @param action action
 * @param occurredAt server instant
 * @param actorSubject verified subject of the actor
 * @param actorRole {@code tenant-admin} or {@code employee}
 * @param fromState state before, {@code null} for the creation
 * @param toState state after
 */
public record TaskAction(
    int seq,
    TaskActionType action,
    Instant occurredAt,
    String actorSubject,
    String actorRole,
    TaskState fromState,
    TaskState toState) {

  /**
   * Safe text.
   *
   * @return sequence and action only
   */
  @Override
  public String toString() {
    return "TaskAction[" + seq + "," + action + "]";
  }
}
