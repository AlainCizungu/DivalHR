package com.divalhr.core.support;

import com.divalhr.core.identity.application.InvitationMailer;
import com.divalhr.core.identity.domain.DeliveryState;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Captures invitation emails in memory instead of sending them. */
public class RecordingInvitationMailer implements InvitationMailer {

  /** Behaviour switches. */
  public enum Mode {
    /** Accepted by the (fake) server. */
    SENT,
    /** Refused or timed out. */
    FAILED,
    /** The process "dies" after the invitation committed, before anything is sent. */
    CRASH_BEFORE_SEND,
    /** The message is captured, then the process "dies" before the result is recorded. */
    CRASH_AFTER_SEND
  }

  /** Thrown to simulate a process crash around delivery (an Error, so nothing catches it). */
  public static final class SimulatedCrash extends Error {
    private static final long serialVersionUID = 1L;

    SimulatedCrash(String when) {
      super("simulated crash " + when);
    }
  }

  private final List<InvitationMessage> sent = new CopyOnWriteArrayList<>();
  private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.SENT);

  /** Clears captured messages and restores normal behaviour. */
  public void reset() {
    sent.clear();
    mode.set(Mode.SENT);
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
   * Captured messages, oldest first.
   *
   * @return messages
   */
  public List<InvitationMessage> sent() {
    return List.copyOf(sent);
  }

  /**
   * The token in the last link sent to an address.
   *
   * @param address normalized address
   * @return token
   */
  public Optional<String> lastTokenFor(String address) {
    for (int i = sent.size() - 1; i >= 0; i--) {
      InvitationMessage message = sent.get(i);
      if (message.to().value().equals(address)) {
        String link = message.link();
        return Optional.of(link.substring(link.indexOf("#token=") + "#token=".length()));
      }
    }
    return Optional.empty();
  }

  @Override
  public DeliveryState send(InvitationMessage message) {
    Mode current = mode.get();
    if (current == Mode.FAILED) {
      return DeliveryState.FAILED;
    }
    if (current == Mode.CRASH_BEFORE_SEND) {
      throw new SimulatedCrash("before send");
    }
    sent.add(message);
    if (current == Mode.CRASH_AFTER_SEND) {
      throw new SimulatedCrash("after SMTP success");
    }
    return DeliveryState.SENT;
  }
}
