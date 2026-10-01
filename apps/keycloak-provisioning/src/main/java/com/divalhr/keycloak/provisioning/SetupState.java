package com.divalhr.keycloak.provisioning;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classifies an invitation identity's credential setup (Issue #31, amendment A1). Pure: it sees a
 * snapshot, never Keycloak models.
 */
public enum SetupState {
  /**
   * Untouched since creation: no credential and exactly the role's required actions. The only state
   * compensation may delete.
   */
  PRISTINE,
  /**
   * Consistently in progress: some actions still pending, each credential type the role needs is
   * either present once with its action gone or absent with its action pending.
   */
  PENDING,
  /** The role's proven terminal state: every credential present once, no action pending. */
  COMPLETED,
  /** Anything else: partial, contradictory or drifted. Never reported as sent or complete. */
  INVALID;

  /**
   * What the identity looks like now.
   *
   * @param roleGroups the identity's role groups (only the two tenant-role groups count)
   * @param credentialCounts stored credentials by type (all types)
   * @param requiredActions pending required actions (all actions)
   */
  public record Snapshot(
      Set<InvitationRole> roleGroups,
      Map<String, Long> credentialCounts,
      Set<String> requiredActions) {

    /** Defensive copies. */
    public Snapshot {
      roleGroups = Set.copyOf(roleGroups);
      credentialCounts = Map.copyOf(credentialCounts);
      requiredActions = Set.copyOf(requiredActions);
    }
  }

  /**
   * Classifies a snapshot against the role the caller expects.
   *
   * @param expected the invitation's role
   * @param snapshot current state
   * @return the state
   */
  public static SetupState classify(InvitationRole expected, Snapshot snapshot) {
    // Role or group drift: exactly the expected role group, nothing else.
    if (!snapshot.roleGroups().equals(Set.of(expected))) {
      return INVALID;
    }
    // Unexpected credential types (or an empty-count entry) are drift.
    for (Map.Entry<String, Long> entry : snapshot.credentialCounts().entrySet()) {
      if (entry.getValue() > 0 && !expected.credentialTypes().contains(entry.getKey())) {
        return INVALID;
      }
    }
    // Unexpected required actions are drift.
    if (!expected.requiredActions().containsAll(snapshot.requiredActions())) {
      return INVALID;
    }
    List<InvitationRole.Setup> setups = expected.setups();
    int done = 0;
    for (InvitationRole.Setup setup : setups) {
      long count = snapshot.credentialCounts().getOrDefault(setup.credentialType(), 0L);
      boolean pending = snapshot.requiredActions().contains(setup.requiredAction());
      if (count == 1 && !pending) {
        done++;
      } else if (count != 0 || !pending) {
        // A credential with its action still pending, several credentials of one type, or an
        // action removed without its credential.
        return INVALID;
      }
    }
    if (done == setups.size()) {
      return COMPLETED;
    }
    return done == 0 ? PRISTINE : PENDING;
  }
}
