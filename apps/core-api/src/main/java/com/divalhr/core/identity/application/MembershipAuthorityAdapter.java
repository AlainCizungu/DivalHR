package com.divalhr.core.identity.application;

import com.divalhr.core.identity.internal.JdbcMembershipRepository;
import com.divalhr.core.platform.tenancy.MembershipAuthority;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Identity's implementation of the platform {@link MembershipAuthority} port (MVP-012A, M1). One
 * indexed read of committed state per call; no cache (M4). Failures propagate so that the caller
 * fails closed (M5).
 */
@Component
public class MembershipAuthorityAdapter implements MembershipAuthority {

  private final JdbcMembershipRepository memberships;

  /**
   * Creates the adapter.
   *
   * @param memberships membership repository
   */
  public MembershipAuthorityAdapter(JdbcMembershipRepository memberships) {
    this.memberships = memberships;
  }

  @Override
  public Optional<ActiveMembership> find(String subject) {
    return memberships.findActiveBySubject(subject);
  }
}
