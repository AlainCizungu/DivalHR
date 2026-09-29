package com.divalhr.core.tenant.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A team's parent as read (and locked) from the verified tenant: its type, id, site and period. The
 * team's site is always taken from here, never from a request.
 *
 * @param kind parent type
 * @param id parent id
 * @param siteId the parent's site
 * @param period the parent's effective period
 */
public record TeamParent(TeamParentKind kind, UUID id, UUID siteId, EffectivePeriod period) {

  /** Requires every component. */
  public TeamParent {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(siteId, "siteId");
    Objects.requireNonNull(period, "period");
  }
}
