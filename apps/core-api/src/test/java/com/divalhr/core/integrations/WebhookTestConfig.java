package com.divalhr.core.integrations;

import com.divalhr.core.integrations.application.DeliveryTransport;
import com.divalhr.core.integrations.application.DnsResolver;
import com.divalhr.core.integrations.domain.FailureClass;
import com.divalhr.core.integrations.domain.WebhookUrl;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A fixed public resolver and a scripted transport: no test touches the network. */
@TestConfiguration
public class WebhookTestConfig {

  /** One recorded outbound POST. */
  public record Sent(String url, Map<String, String> headers, byte[] body) {}

  /** Scripted transport. */
  public static final class ScriptedTransport implements DeliveryTransport {
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final AtomicReference<Outcome> next = new AtomicReference<>(new Outcome(204, null, 5));

    public List<Sent> sent() {
      return sent;
    }

    public void respond(Outcome outcome) {
      next.set(outcome);
    }

    public void fail(FailureClass failure) {
      next.set(new Outcome(null, failure, 5));
    }

    public void reset() {
      sent.clear();
      next.set(new Outcome(204, null, 5));
    }

    @Override
    public Outcome post(WebhookUrl url, Map<String, String> headers, byte[] body) {
      sent.add(new Sent(url.normalized(), Map.copyOf(headers), body.clone()));
      return next.get();
    }
  }

  @Bean
  @Primary
  ScriptedTransport scriptedTransport() {
    return new ScriptedTransport();
  }

  /** The fixed public resolver, switchable per test, counting lookups per host (R162-1). */
  public static final class SwitchableDns implements DnsResolver {
    /** What the resolver answers. */
    public enum Mode {
      NORMAL,
      BLOCKED,
      DOWN
    }

    private volatile Mode mode = Mode.NORMAL;
    private final java.util.concurrent.ConcurrentHashMap<
            String, java.util.concurrent.atomic.AtomicInteger>
        lookups = new java.util.concurrent.ConcurrentHashMap<>();

    public void mode(Mode value) {
      mode = value;
    }

    public int lookups(String host) {
      var count = lookups.get(host);
      return count == null ? 0 : count.get();
    }

    public void reset() {
      mode = Mode.NORMAL;
      lookups.clear();
    }

    @Override
    public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
      lookups
          .computeIfAbsent(host, h -> new java.util.concurrent.atomic.AtomicInteger())
          .incrementAndGet();
      if (mode == Mode.DOWN) {
        throw new UnknownHostException(host);
      }
      if (mode == Mode.BLOCKED || host.startsWith("private-")) {
        return new InetAddress[] {InetAddress.getByAddress(host, new byte[] {10, 0, 0, 7})};
      }
      if (host.startsWith("mixed-")) {
        return new InetAddress[] {
          InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34}),
          InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1})
        };
      }
      if (host.startsWith("missing-")) {
        throw new UnknownHostException(host);
      }
      return new InetAddress[] {
        InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34})
      };
    }
  }

  @Bean
  @Primary
  SwitchableDns fixedResolver() {
    return new SwitchableDns();
  }
}
