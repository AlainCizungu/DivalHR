package com.divalhr.core.support;

import com.divalhr.core.identity.application.IdentityDirectory;
import com.divalhr.core.identity.application.IdentityProviderUnavailableException;
import com.divalhr.core.identity.domain.TenantRole;
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
    CREDENTIAL_SETUP_DOWN,
    /** The provider reports an invalid setup state (Issue #31, A1). */
    CREDENTIAL_SETUP_INVALID,
    /** The provider reports that setup is already complete. */
    CREDENTIAL_SETUP_COMPLETED,
    /** The provider refuses compensation (identity not pristine). */
    COMPENSATION_REFUSED,
    /**
     * This call loses a creation race: an identical concurrent call has committed the identity, and
     * this one gets the extension's retryable {@code 503 IDENTITY_BUSY} (PR #32 review).
     */
    PROVISION_RACED
  }

  /** A provisioned identity. */
  public record Identity(
      String subject, UUID invitationId, String tenant, String role, String locale) {}

  private final Map<String, Identity> byAddress = new ConcurrentHashMap<>();
  private final Object provisioning = new Object();
  private final Set<String> preexisting = ConcurrentHashMap.newKeySet();
  private final List<UUID> credentialSetups = new CopyOnWriteArrayList<>();
  private final Map<UUID, TenantRole> credentialSetupRoles = new ConcurrentHashMap<>();
  private final List<UUID> compensations = new CopyOnWriteArrayList<>();
  private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.UP);
  private final AtomicReference<Runnable> beforeProvision = new AtomicReference<>(() -> {});

  /** Resets every switch and record. */
  public void reset() {
    byAddress.clear();
    preexisting.clear();
    credentialSetups.clear();
    credentialSetupRoles.clear();
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
   * Invitations for which a credential setup was requested (Issue #31: keyed by invitation).
   *
   * @return invitation ids
   */
  public List<UUID> credentialSetups() {
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
    synchronized (provisioning) {
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
      if (mode.get() == Mode.PROVISION_RACED) {
        throw new IdentityProviderUnavailableException("status_5xx");
      }
      return new Provisioned(created.subject());
    }
  }

  @Override
  public CredentialSetupOutcome requestCredentialSetup(UUID invitationId) {
    Mode current = mode.get();
    if (current == Mode.DOWN || current == Mode.CREDENTIAL_SETUP_DOWN) {
      throw new IdentityProviderUnavailableException("fake_down");
    }
    credentialSetups.add(invitationId);
    // Like the extension: the role comes from the identity this invitation created, never from
    // the caller (PR #32 review).
    byAddress.values().stream()
        .filter(identity -> identity.invitationId().equals(invitationId))
        .findFirst()
        .ifPresent(
            identity ->
                TenantRole.fromWire(identity.role())
                    .ifPresent(role -> credentialSetupRoles.put(invitationId, role)));
    return switch (current) {
      case CREDENTIAL_SETUP_INVALID -> CredentialSetupOutcome.STATE_INVALID;
      case CREDENTIAL_SETUP_COMPLETED -> CredentialSetupOutcome.COMPLETED;
      default -> CredentialSetupOutcome.EMAIL_SENT;
    };
  }

  /**
   * The role the provider derived from the identity for each credential-setup request.
   *
   * @return copy of the roles
   */
  public Map<UUID, TenantRole> credentialSetupRoles() {
    return Map.copyOf(credentialSetupRoles);
  }

  @Override
  public CompensationOutcome compensate(UUID invitationId) {
    if (mode.get() == Mode.DOWN) {
      throw new IdentityProviderUnavailableException("fake_down");
    }
    compensations.add(invitationId);
    if (mode.get() == Mode.COMPENSATION_REFUSED) {
      return CompensationOutcome.REFUSED;
    }
    byAddress.values().removeIf(identity -> identity.invitationId().equals(invitationId));
    return CompensationOutcome.DELETED_OR_ABSENT;
  }
}
