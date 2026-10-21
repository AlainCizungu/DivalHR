package com.divalhr.core.platform.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Obtains the Keycloak client-credentials access token that authenticates Core to the AI service
 * (D101-5): a dedicated client, the audience {@code divalhr-ai-service} and the capability {@code
 * generate-operational-summary} are granted by the realm, not requested here. The token is cached
 * in memory until shortly before it expires and is never logged, returned or exposed to the
 * browser. A human or browser token is never used.
 */
@Component
public class ServiceTokenProvider {

  private static final long EARLY_SECONDS = 30;

  private final AiServiceProperties properties;
  private final boolean configured;
  private final RestClient http;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Clock clock = Clock.systemUTC();
  private final Object lock = new Object();
  private String accessToken;
  private Instant expiresAt = Instant.EPOCH;

  /**
   * Creates the provider; a configured but invalid setting fails startup (fail closed).
   *
   * @param properties AI service settings
   * @param environment deployment environment
   */
  public ServiceTokenProvider(
      AiServiceProperties properties, @Value("${divalhr.environment}") String environment) {
    this.configured = properties.isConfigured();
    if (configured) {
      properties.validate(environment);
    }
    this.properties = properties;
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(properties.readTimeout());
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  /**
   * Whether the capability is configured.
   *
   * @return true when requests may be made
   */
  public boolean available() {
    return configured;
  }

  /**
   * A valid access token.
   *
   * @return the bearer value (never log it)
   * @throws AiServiceUnavailableException when the capability is not configured or Keycloak does
   *     not answer with a token
   */
  public String token() {
    if (!configured) {
      throw new AiServiceUnavailableException("not_configured");
    }
    synchronized (lock) {
      Instant now = Instant.now(clock);
      if (accessToken != null && now.isBefore(expiresAt)) {
        return accessToken;
      }
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("grant_type", "client_credentials");
      form.add("client_id", properties.clientId());
      form.add("client_secret", properties.clientSecret());
      String body;
      try {
        body =
            http.post()
                .uri(URI.create(properties.tokenUrl()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
      } catch (RestClientResponseException rejected) {
        throw new AiServiceUnavailableException("token_rejected");
      } catch (ResourceAccessException io) {
        throw new AiServiceUnavailableException("token_unreachable");
      } catch (RuntimeException unreadable) {
        throw new AiServiceUnavailableException("token_unreadable");
      }
      String value;
      long expiresIn;
      try {
        JsonNode node = json.readTree(body == null ? "{}" : body);
        value = node.path("access_token").asString(null);
        expiresIn = node.path("expires_in").asLong(0);
      } catch (RuntimeException unreadable) {
        throw new AiServiceUnavailableException("token_unreadable");
      }
      if (value == null || value.isBlank()) {
        throw new AiServiceUnavailableException("token_missing");
      }
      accessToken = value;
      expiresAt = now.plusSeconds(Math.max(0, expiresIn - EARLY_SECONDS));
      return accessToken;
    }
  }

  /** Forgets the cached token (after the AI service refused it). */
  public void invalidate() {
    synchronized (lock) {
      accessToken = null;
      expiresAt = Instant.EPOCH;
    }
  }
}
