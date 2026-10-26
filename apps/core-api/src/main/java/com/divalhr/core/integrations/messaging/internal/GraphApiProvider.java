package com.divalhr.core.integrations.messaging.internal;

import com.divalhr.core.integrations.messaging.application.MessagingProperties;
import com.divalhr.core.integrations.messaging.application.MessagingProvider;
import com.divalhr.core.integrations.messaging.domain.MessagingLanguage;
import com.divalhr.core.integrations.messaging.domain.SendFailure;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Graph API client (D111-8). The origin and the version are operator configuration (validated
 * at start-up: in production only {@code https://graph.facebook.com}), so no tenant input chooses a
 * host. There is no redirect, no proxy inheritance, a 3 s connect and 10 s whole-request deadline,
 * a bounded response read, and only allow-listed fields are parsed: a provider message id, a
 * numeric error code, and template metadata. Response bodies and headers are never stored or
 * logged, and the access token only travels in the {@code Authorization} header of the request.
 */
@Component
public final class GraphApiProvider implements MessagingProvider {

  /** Largest response body read. */
  static final int MAX_RESPONSE_BYTES = 64 * 1024;

  private static final Duration MAX_RETRY_AFTER = Duration.ofHours(2);
  private static final Pattern DIGITS = Pattern.compile("^[0-9]{5,20}$");
  private static final Pattern MESSAGE_ID = Pattern.compile("^[A-Za-z0-9=_.:+/-]{8,256}$");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([0-9]+)\\}\\}");

  /** Documented Meta throttle codes (no HTTP status or Retry-After is documented for them). */
  private static final Set<Integer> THROTTLE_CODES = Set.of(4, 80007, 130429, 131056);

  /** Token and permission problems. */
  private static final Set<Integer> CREDENTIAL_CODES = Set.of(0, 10, 102, 190, 200, 368, 80008);

  /** Recipient-side refusals. */
  private static final Set<Integer> RECIPIENT_CODES =
      Set.of(131026, 131030, 131021, 131047, 131049, 131050, 131051, 131052, 131053, 131009);

  private final HttpClient client;
  private final String origin;
  private final String version;
  private final JsonMapper json;
  private final Duration requestTimeout;

  /**
   * Creates the provider.
   *
   * @param properties settings (already validated)
   * @param json JSON mapper
   */
  @org.springframework.beans.factory.annotation.Autowired
  public GraphApiProvider(MessagingProperties properties, JsonMapper json) {
    this(properties, json, httpClient(properties));
  }

  /**
   * Creates the provider with an explicit client (tests).
   *
   * @param properties settings
   * @param json JSON mapper
   * @param client HTTP client
   */
  GraphApiProvider(MessagingProperties properties, JsonMapper json, HttpClient client) {
    this.origin = properties.graphBaseUrl();
    this.version = properties.graphVersion();
    this.json = json;
    this.client = client;
    this.requestTimeout = properties.requestTimeout();
  }

  private static HttpClient httpClient(MessagingProperties properties) {
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.connectTimeout())
            .proxy(
                new ProxySelector() {
                  @Override
                  public List<java.net.Proxy> select(URI uri) {
                    return List.of(java.net.Proxy.NO_PROXY);
                  }

                  @Override
                  public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {
                    // no proxy is ever used
                  }
                });
    MessagingProperties.TestProvider synthetic = properties.testProvider();
    if (synthetic.configured() && synthetic.caFile() != null && !synthetic.caFile().isBlank()) {
      builder.sslContext(trustWith(Path.of(synthetic.caFile())));
    }
    return builder.build();
  }

  private static SSLContext trustWith(Path caFile) {
    try {
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      TrustManagerFactory defaults =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      defaults.init((KeyStore) null);
      int i = 0;
      for (var tm : defaults.getTrustManagers()) {
        if (tm instanceof javax.net.ssl.X509TrustManager x509) {
          for (var cert : x509.getAcceptedIssuers()) {
            store.setCertificateEntry("default-" + i++, cert);
          }
        }
      }
      try (InputStream in = Files.newInputStream(caFile)) {
        Collection<? extends Certificate> extra =
            CertificateFactory.getInstance("X.509").generateCertificates(in);
        for (Certificate cert : extra) {
          store.setCertificateEntry("synthetic-" + i++, cert);
        }
      }
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(store);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, tmf.getTrustManagers(), null);
      return context;
    } catch (GeneralSecurityException | IOException failure) {
      throw new IllegalStateException("messaging TLS trust could not be initialised");
    }
  }

  // ------------------------------------------------------------------------------------------
  // Credential and template checks
  // ------------------------------------------------------------------------------------------

  @Override
  public CredentialResult checkCredential(String token, String phoneNumberId) {
    if (!DIGITS.matcher(phoneNumberId).matches()) {
      return CredentialResult.REJECTED;
    }
    Reply reply = get(token, "/" + phoneNumberId + "?fields=id");
    if (reply.failure != null) {
      return CredentialResult.UNAVAILABLE;
    }
    if (reply.status == 200 && phoneNumberId.equals(string(reply.body, "id"))) {
      return CredentialResult.VALID;
    }
    if (reply.status >= 500 || reply.status == 429) {
      return CredentialResult.UNAVAILABLE;
    }
    return reply.status >= 400 ? CredentialResult.REJECTED : CredentialResult.UNAVAILABLE;
  }

  @Override
  public TemplateCheck checkTemplate(
      String token,
      String wabaId,
      String templateName,
      MessagingLanguage language,
      String expectedButtonUrl) {
    if (!DIGITS.matcher(wabaId).matches()) {
      return new TemplateCheck(TemplateResult.CREDENTIAL_REJECTED, 0);
    }
    Reply reply =
        get(
            token,
            "/"
                + wabaId
                + "/message_templates?fields=name,status,language,components&limit=50&name="
                + URLEncoder.encode(templateName, StandardCharsets.UTF_8));
    if (reply.failure != null || reply.status >= 500 || reply.status == 429) {
      return new TemplateCheck(TemplateResult.UNAVAILABLE, 0);
    }
    if (reply.status == 401
        || reply.status == 403
        || reply.status == 400 && reply.errorCode != null) {
      return new TemplateCheck(TemplateResult.CREDENTIAL_REJECTED, 0);
    }
    if (reply.status != 200 || !(reply.body.get("data") instanceof List<?> data)) {
      return new TemplateCheck(TemplateResult.UNAVAILABLE, 0);
    }
    return judge(data, templateName, language, expectedButtonUrl);
  }

  /** Judges the provider's template list; package-private for tests. */
  static TemplateCheck judge(
      List<?> data, String templateName, MessagingLanguage language, String expectedButtonUrl) {
    boolean named = false;
    boolean languageSeen = false;
    boolean approvedSeen = false;
    for (Object item : data) {
      if (!(item instanceof Map<?, ?> template) || !templateName.equals(template.get("name"))) {
        continue;
      }
      named = true;
      if (!language.code().equals(template.get("language"))) {
        continue;
      }
      languageSeen = true;
      if (!"APPROVED".equals(template.get("status"))) {
        continue;
      }
      approvedSeen = true;
      int parameters = bodyParameters(template.get("components"), expectedButtonUrl);
      if (parameters >= 0) {
        return new TemplateCheck(TemplateResult.OK, parameters);
      }
    }
    if (!named) {
      return new TemplateCheck(TemplateResult.MISSING, 0);
    }
    if (!languageSeen) {
      return new TemplateCheck(TemplateResult.WRONG_LANGUAGE, 0);
    }
    return new TemplateCheck(
        approvedSeen ? TemplateResult.WRONG_SHAPE : TemplateResult.NOT_APPROVED, 0);
  }

  /**
   * The exact supported shape (D111-7): one BODY with no variable or only {@code {{1}}}, optionally
   * a static FOOTER, and one BUTTONS component holding exactly one URL button whose URL is the
   * expected base plus {@code {{1}}}. No header (it could carry media or variables).
   *
   * @return the number of body parameters (0 or 1), or -1 when the shape is not supported
   */
  private static int bodyParameters(Object components, String expectedButtonUrl) {
    if (!(components instanceof List<?> list) || list.isEmpty() || list.size() > 3) {
      return -1;
    }
    int body = -1;
    boolean buttons = false;
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> component)) {
        return -1;
      }
      String type = component.get("type") instanceof String t ? t.toUpperCase(Locale.ROOT) : "";
      switch (type) {
        case "BODY" -> {
          if (body != -1 || !(component.get("text") instanceof String text)) {
            return -1;
          }
          body = placeholders(text);
          if (body < 0) {
            return -1;
          }
        }
        case "FOOTER" -> {
          if (component.get("text") instanceof String text && placeholders(text) != 0) {
            return -1;
          }
        }
        case "BUTTONS" -> {
          if (buttons
              || !(component.get("buttons") instanceof List<?> all)
              || all.size() != 1
              || !(all.get(0) instanceof Map<?, ?> button)
              || !"URL".equals(button.get("type"))
              || !expectedButtonUrl.equals(button.get("url"))) {
            return -1;
          }
          buttons = true;
        }
        default -> {
          return -1;
        }
      }
    }
    return body >= 0 && buttons ? body : -1;
  }

  // 0 without variables, 1 with only the first variable (once), -1 otherwise
  private static int placeholders(String text) {
    Matcher matcher = PLACEHOLDER.matcher(text);
    int count = 0;
    while (matcher.find()) {
      if (!"1".equals(matcher.group(1))) {
        return -1;
      }
      count++;
    }
    return count > 1 ? -1 : count;
  }

  // ------------------------------------------------------------------------------------------
  // Send
  // ------------------------------------------------------------------------------------------

  @Override
  public SendOutcome send(SendRequest request) {
    long started = System.nanoTime();
    if (!DIGITS.matcher(request.phoneNumberId()).matches()) {
      return new SendOutcome(null, SendFailure.CREDENTIAL_INVALID, null, null, 0);
    }
    List<Object> components = new ArrayList<>();
    if (request.bodyLabel() != null) {
      components.add(
          Map.of(
              "type",
              "body",
              "parameters",
              List.of(Map.of("type", "text", "text", request.bodyLabel()))));
    }
    components.add(
        Map.of(
            "type",
            "button",
            "sub_type",
            "url",
            "index",
            "0",
            "parameters",
            List.of(Map.of("type", "text", "text", request.buttonSuffix()))));
    Map<String, Object> payload =
        Map.of(
            "messaging_product",
            "whatsapp",
            "to",
            request.to().digits(),
            "type",
            "template",
            "template",
            Map.of(
                "name",
                request.templateName(),
                "language",
                Map.of("code", request.language().code()),
                "components",
                components));
    byte[] bytes;
    try {
      bytes = json.writeValueAsBytes(payload);
    } catch (RuntimeException unexpected) {
      return new SendOutcome(null, SendFailure.INTERNAL_FAILURE, null, null, 0);
    }
    HttpRequest http =
        HttpRequest.newBuilder(
                URI.create(origin + "/" + version + "/" + request.phoneNumberId() + "/messages"))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer " + request.token())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "DivalHR-Messaging/1")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build();
    Reply reply = exchange(http);
    long latency = (System.nanoTime() - started) / 1_000_000L;
    if (reply.failure != null) {
      return new SendOutcome(null, reply.failure, null, null, latency);
    }
    if (reply.status >= 200 && reply.status <= 299) {
      String id = firstMessageId(reply.body);
      return id != null
          ? new SendOutcome(id, null, reply.status, null, latency)
          : new SendOutcome(null, SendFailure.PROVIDER_FAILED, reply.status, null, latency);
    }
    return new SendOutcome(
        null, classify(reply.status, reply.errorCode), reply.status, reply.retryAfter, latency);
  }

  /**
   * Reduces an error answer to a closed class (ADR 0029). Meta documents throttle errors by code
   * (4, 80007, 130429, 131056) without a documented HTTP status or {@code Retry-After}, so both the
   * status 429 and those codes mean "rate limited, retry".
   *
   * @param status HTTP status
   * @param errorCode the provider's numeric error code, or null
   * @return the class
   */
  public static SendFailure classify(int status, Integer errorCode) {
    if (status == 429 || errorCode != null && THROTTLE_CODES.contains(errorCode)) {
      return SendFailure.RATE_LIMITED;
    }
    if (status >= 500) {
      return SendFailure.PROVIDER_FAILED;
    }
    if (status == 401 || errorCode != null && CREDENTIAL_CODES.contains(errorCode)) {
      return SendFailure.CREDENTIAL_INVALID;
    }
    if (errorCode != null && errorCode >= 132000 && errorCode <= 132999) {
      return SendFailure.TEMPLATE_INVALID;
    }
    if (errorCode != null && RECIPIENT_CODES.contains(errorCode)) {
      return SendFailure.RECIPIENT_REJECTED;
    }
    return SendFailure.HTTP_RESPONSE;
  }

  // ------------------------------------------------------------------------------------------
  // HTTP plumbing
  // ------------------------------------------------------------------------------------------

  private record Reply(
      int status, Map<?, ?> body, Integer errorCode, Duration retryAfter, SendFailure failure) {}

  private Reply get(String token, String pathAndQuery) {
    HttpRequest http =
        HttpRequest.newBuilder(URI.create(origin + "/" + version + pathAndQuery))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .header("User-Agent", "DivalHR-Messaging/1")
            .GET()
            .build();
    return exchange(http);
  }

  private Reply exchange(HttpRequest http) {
    try {
      HttpResponse<InputStream> response =
          client.send(http, HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (InputStream in = response.body()) {
        body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
      }
      if (body.length > MAX_RESPONSE_BYTES) {
        return new Reply(response.statusCode(), Map.of(), null, null, SendFailure.PROVIDER_FAILED);
      }
      Map<?, ?> parsed = parse(body);
      return new Reply(
          response.statusCode(),
          parsed,
          errorCode(parsed),
          retryAfter(response.headers().firstValue("Retry-After").orElse(null)),
          null);
    } catch (HttpConnectTimeoutException timeout) {
      return failed(SendFailure.CONNECT_TIMEOUT);
    } catch (HttpTimeoutException timeout) {
      return failed(SendFailure.REQUEST_TIMEOUT);
    } catch (SSLException tls) {
      return failed(SendFailure.TLS_FAILED);
    } catch (ConnectException refused) {
      return failed(
          refused.getCause() instanceof UnknownHostException
              ? SendFailure.DNS_FAILED
              : SendFailure.CONNECTION_FAILED);
    } catch (UnknownHostException unknown) {
      return failed(SendFailure.DNS_FAILED);
    } catch (IOException io) {
      return failed(SendFailure.CONNECTION_FAILED);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return failed(SendFailure.INTERNAL_FAILURE);
    } catch (RuntimeException unexpected) {
      return failed(SendFailure.INTERNAL_FAILURE);
    }
  }

  private static Reply failed(SendFailure failure) {
    return new Reply(0, Map.of(), null, null, failure);
  }

  private Map<?, ?> parse(byte[] body) {
    if (body.length == 0) {
      return Map.of();
    }
    try {
      Object root = json.readValue(body, Object.class);
      return root instanceof Map<?, ?> map ? map : Map.of();
    } catch (RuntimeException malformed) {
      return Map.of();
    }
  }

  private static Integer errorCode(Map<?, ?> body) {
    if (body.get("error") instanceof Map<?, ?> error && error.get("code") instanceof Number code) {
      return code.intValue();
    }
    return null;
  }

  private static String firstMessageId(Map<?, ?> body) {
    if (body.get("messages") instanceof List<?> messages
        && !messages.isEmpty()
        && messages.get(0) instanceof Map<?, ?> first
        && first.get("id") instanceof String id
        && MESSAGE_ID.matcher(id).matches()) {
      return id;
    }
    return null;
  }

  private static String string(Map<?, ?> body, String key) {
    return body.get(key) instanceof String text ? text : null;
  }

  private static Duration retryAfter(String header) {
    if (header == null || !header.matches("^[0-9]{1,6}$")) {
      return null;
    }
    Duration hint = Duration.ofSeconds(Long.parseLong(header));
    return hint.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : hint;
  }
}
