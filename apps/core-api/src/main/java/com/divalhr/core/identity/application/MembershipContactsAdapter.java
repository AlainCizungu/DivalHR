package com.divalhr.core.identity.application;

import com.divalhr.core.identity.internal.JdbcMembershipContactRepository;
import com.divalhr.core.platform.access.MembershipContacts;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Identity's implementation of {@link MembershipContacts} (MVP-120F). */
@Component
public class MembershipContactsAdapter implements MembershipContacts {

  private final JdbcMembershipContactRepository contacts;

  /**
   * Creates the adapter.
   *
   * @param contacts membership contact storage
   */
  public MembershipContactsAdapter(JdbcMembershipContactRepository contacts) {
    this.contacts = contacts;
  }

  @Override
  public Optional<String> verifiedEmail(TenantId tenant, UUID membershipId) {
    return contacts.email(tenant, membershipId);
  }
}
