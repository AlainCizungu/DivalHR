package com.divalhr.core.integrations.messaging;

import com.divalhr.core.integrations.messaging.application.MessagingProvider;
import com.divalhr.core.integrations.messaging.domain.MessagingLanguage;
import com.divalhr.core.integrations.messaging.domain.SendFailure;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A scripted WhatsApp provider: no test touches the network or Meta. */
@TestConfiguration
public class MessagingTestConfig {

  /** One recorded outbound send (the token is recorded only to assert where it never appears). */
  public record Sent(
      String token,
      String phoneNumberId,
      String to,
      String templateName,
      MessagingLanguage language,
      String bodyLabel,
      String buttonSuffix) {}

  /** The scripted provider. */
  public static final class ScriptedProvider implements MessagingProvider {
    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final List<String> checkedTokens = new CopyOnWriteArrayList<>();
    private final AtomicReference<CredentialResult> credential =
        new AtomicReference<>(CredentialResult.VALID);
    private final AtomicReference<TemplateCheck> template =
        new AtomicReference<>(new TemplateCheck(TemplateResult.OK, 0));
    private final AtomicReference<SendOutcome> next = new AtomicReference<>();

    public List<Sent> sent() {
      return sent;
    }

    public List<String> checkedTokens() {
      return checkedTokens;
    }

    public void credential(CredentialResult result) {
      credential.set(result);
    }

    public void template(TemplateResult result, int bodyParameters) {
      template.set(new TemplateCheck(result, bodyParameters));
    }

    /** The next sends fail with this class until reset. */
    public void fail(SendFailure failure) {
      next.set(new SendOutcome(null, failure, failure.retryable() ? 503 : 400, null, 5));
    }

    /** The next sends fail with this class and this provider hint. */
    public void fail(SendFailure failure, Duration retryAfter) {
      next.set(new SendOutcome(null, failure, 429, retryAfter, 5));
    }

    /** The next sends are accepted (the default). */
    public void accept() {
      next.set(null);
    }

    public void reset() {
      sent.clear();
      checkedTokens.clear();
      credential.set(CredentialResult.VALID);
      template.set(new TemplateCheck(TemplateResult.OK, 0));
      next.set(null);
    }

    @Override
    public CredentialResult checkCredential(String token, String phoneNumberId) {
      checkedTokens.add(token);
      return credential.get();
    }

    @Override
    public TemplateCheck checkTemplate(
        String token,
        String wabaId,
        String templateName,
        MessagingLanguage language,
        String expectedButtonUrl) {
      return credential.get() == CredentialResult.VALID
          ? template.get()
          : new TemplateCheck(TemplateResult.CREDENTIAL_REJECTED, 0);
    }

    @Override
    public SendOutcome send(SendRequest request) {
      sent.add(
          new Sent(
              request.token(),
              request.phoneNumberId(),
              request.to().e164(),
              request.templateName(),
              request.language(),
              request.bodyLabel(),
              request.buttonSuffix()));
      SendOutcome scripted = next.get();
      return scripted != null
          ? scripted
          : new SendOutcome(
              "wamid." + UUID.randomUUID().toString().replace("-", ""), null, 200, null, 5);
    }
  }

  @Bean
  @Primary
  ScriptedProvider scriptedMessagingProvider() {
    return new ScriptedProvider();
  }
}
