package com.divalhr.core.tenant.application;

import com.divalhr.core.tenant.domain.TeamParentKind;
import java.util.Objects;
import java.util.UUID;

/**
 * A validated team-list parent filter: exactly one parent type and id.
 *
 * @param kind parent type
 * @param id parent id
 */
public record TeamParentRef(TeamParentKind kind, UUID id) {

  /** Requires every component. */
  public TeamParentRef {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
  }
}
