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

  @Bean
  @Primary
  DnsResolver fixedResolver() {
    return host -> {
      if (host.startsWith("private-")) {
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
    };
  }
}
