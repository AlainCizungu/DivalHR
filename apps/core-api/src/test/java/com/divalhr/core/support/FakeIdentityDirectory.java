package com.divalhr.core.support;

import com.divalhr.core.identity.application.IdentityDirectory;
import com.divalhr.core.identity.application.IdentityProviderUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory identity provider for integration tests. Behaves like the Keycloak adapter: one
 * identity per address, idempotent per invitation, and compensation removes only identities an
 * invitation created. Tests can make it unavailable or pre-register addresses (existing identities
 * of any tenant).
 */
public class FakeIdentityDirectory implements IdentityDirectory {

  /** Behaviour switches. */
  public enum Mode {
    /** Normal. */
    UP,
    /** Every call fails as unreachable. */
    DOWN,
    /** Provisioning works but the credential-setup request fails. */
    CREDENTIAL_SETUP_DOWN
  }

  /** A provisioned identity. */
  public record Identity(
      String subject, UUID invitationId, String tenant, String role, String locale) {}

  private final Map<String, Identity> byAddress = new ConcurrentHashMap<>();
  private final Set<String> preexisting = ConcurrentHashMap.newKeySet();
  private final List<String> credentialSetups = new CopyOnWriteArrayList<>();
  private final List<UUID> compensations = new CopyOnWriteArrayList<>();
  private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.UP);
  private final AtomicReference<Runnable> beforeProvision = new AtomicReference<>(() -> {});

  /** Resets every switch and record. */
  public void reset() {
    byAddress.clear();
    preexisting.clear();
    credentialSetups.clear();
    compensations.clear();
    mode.set(Mode.UP);
    beforeProvision.set(() -> {});
  }

  /**
   * Sets the behaviour.
   *
   * @param value mode
   */
  public void mode(Mode value) {
    mode.set(value);
  }

  /**
   * Registers an address that already has an identity (any tenant).
   *
   * @param address normalized address
   */
  public void existing(String address) {
    preexisting.add(address);
  }

  /**
   * Runs before each provisioning (for concurrency tests).
   *
   * @param hook hook
   */
  public void beforeProvision(Runnable hook) {
    beforeProvision.set(hook);
  }

  /**
   * Identities created so far.
   *
   * @return by address
   */
  public Map<String, Identity> identities() {
    return Map.copyOf(byAddress);
  }

  /**
   * Subjects for which a credential setup was requested.
   *
   * @return subjects
   */
  public List<String> credentialSetups() {
    return List.copyOf(credentialSetups);
  }

  /**
   * Invitations compensated.
   *
   * @return invitation ids
   */
  public List<UUID> compensations() {
    return List.copyOf(compensations);
  }

  @Override
  public ProvisioningResult provision(ProvisioningRequest request) {
    beforeProvision.get().run();
    if (mode.get() == Mode.DOWN) {
      throw new IdentityProviderUnavailableException("fake_down");
    }
    String address = request.email().value();
    if (preexisting.contains(address)) {
      return new IdentityConflict();
    }
    synchronized (byAddress) {
      Identity existing = byAddress.get(address);
      if (existing != null) {
        return existing.invitationId().equals(request.invitationId())
            ? new Provisioned(existing.subject())
            : new IdentityConflict();
      }
      Identity created =
          new Identity(
              UUID.randomUUID().toString(),
              request.invitationId(),
              request.tenant().toString(),
              request.role().wireName(),
              request.locale().tag());
      byAddress.put(address, created);
      return new Provisioned(created.subject());
    }
  }

  @Override
  public void requestCredentialSetup(String subject) {
    if (mode.get() != Mode.UP) {
      throw new IdentityProviderUnavailableException("fake_down");
    }
    credentialSetups.add(subject);
  }

  @Override
  public void compensate(UUID invitationId) {
    if (mode.get() == Mode.DOWN) {
      throw new IdentityProviderUnavailableException("fake_down");
    }
    compensations.add(invitationId);
    byAddress.values().removeIf(identity -> identity.invitationId().equals(invitationId));
  }
}
