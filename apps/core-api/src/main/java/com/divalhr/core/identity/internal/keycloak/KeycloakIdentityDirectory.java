package com.divalhr.core.identity.internal.keycloak;

import com.divalhr.core.identity.application.IdentityDirectory;
import com.divalhr.core.identity.application.IdentityProviderUnavailableException;
import com.divalhr.core.platform.web.CorrelationId;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
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
 * Keycloak adapter for {@link IdentityDirectory}: the only class that knows Keycloak concepts.
 *
 * <p>Issue #31: the provisioner client holds no Keycloak admin permission. It calls only the narrow
 * extension {@code divalhr-provisioning} (contract {@code
 * packages/shared-contracts/openapi/keycloak-provisioning.yaml}), whose three operations are keyed
 * by invitation ID and act only on the identity created for that invitation. The extension decides
 * the role group, attributes, required actions and setup-email settings; this adapter sends only
 * the normalized address, the tenant role, the tenant ID from the invitation and the locale.
 *
 * <p>Response bodies, addresses and tokens are never logged.
 */
@Component
public class KeycloakIdentityDirectory implements IdentityDirectory {

  private static final Logger LOG = LoggerFactory.getLogger(KeycloakIdentityDirectory.class);
  private static final Pattern ID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final KeycloakProperties properties;
  private final RestClient http;
  private final JsonMapper json;
  private final Clock clock;
  private final Object tokenLock = new Object();
  private String accessToken;
  private Instant accessTokenExpiresAt = Instant.EPOCH;

  /**
   * Creates the adapter.
   *
   * @param properties provider settings (validated here: fail closed)
   * @param environment deployment environment
   * @param json JSON mapper
   */
  public KeycloakIdentityDirectory(
      KeycloakProperties properties,
      @Value("${divalhr.environment}") String environment,
      JsonMapper json) {
    properties.validate(environment);
    this.properties = properties;
    this.json = json;
    this.clock = Clock.systemUTC();
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(properties.readTimeout());
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public ProvisioningResult provision(ProvisioningRequest request) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("email", request.email().value());
    body.put("role", request.role().wireName());
    body.put("tenantId", request.tenant().toString());
    body.put("locale", request.locale().tag());
    Reply reply = call("PUT", identity(request.invitationId()), json.writeValueAsString(body));
    return switch (reply.status()) {
      case 200, 201 -> new Provisioned(subject(reply));
      case 409 -> {
        // Only the extension's proven differing binding is a business conflict. Ambiguity, and any
        // 409 without that code (for example Keycloak's own duplicate-key answer when a concurrent
        // identical call wins a race), stays retryable: it must never deny a legitimate invitation.
        if ("IDENTITY_CONFLICT".equals(reply.code())) {
          yield new IdentityConflict();
        }
        throw failure(
            "IDENTITY_AMBIGUOUS".equals(reply.code()) ? "identity_ambiguous" : "conflict_unproven");
      }
      default -> throw failure(reply);
    };
  }

  @Override
  public CredentialSetupOutcome requestCredentialSetup(UUID invitationId) {
    // No body: the extension derives the role from the identity's role group (PR #32 review).
    Reply reply = call("POST", identity(invitationId) + "/credential-setup", null);
    return switch (reply.status()) {
      case 202 -> CredentialSetupOutcome.EMAIL_SENT;
      case 200 -> CredentialSetupOutcome.COMPLETED;
      case 409 -> {
        if ("SETUP_STATE_INVALID".equals(reply.code())) {
          yield CredentialSetupOutcome.STATE_INVALID;
        }
        throw failure(reply);
      }
      default -> throw failure(reply);
    };
  }

  @Override
  public CompensationOutcome compensate(UUID invitationId) {
    Reply reply = call("DELETE", identity(invitationId), null);
    if (reply.status() == 204) {
      return CompensationOutcome.DELETED_OR_ABSENT;
    }
    if (reply.status() == 409 && "COMPENSATION_REFUSED".equals(reply.code())) {
      return CompensationOutcome.REFUSED;
    }
    throw failure(reply);
  }

  // ---------------------------------------------------------------------------------------------

  /** HTTP status and stable error code (no other content is kept). */
  private record Reply(int status, String code, String subject) {}

  private String identity(UUID invitationId) {
    return "/realms/"
        + properties.realm()
        + "/divalhr-provisioning/v1/invitations/"
        + invitationId
        + "/identity";
  }

  private Reply call(String method, String path, String body) {
    String correlationId = MDC.get(CorrelationId.MDC_KEY);
    try {
      RestClient.RequestBodySpec spec =
          http.method(org.springframework.http.HttpMethod.valueOf(method))
              .uri(base(path))
              .headers(
                  headers -> {
                    headers.setBearerAuth(token());
                    if (correlationId != null) {
                      headers.set(CorrelationId.HEADER, correlationId);
                    }
                  });
      if (body != null) {
        spec.contentType(MediaType.APPLICATION_JSON).body(body);
      }
      return spec.exchange(
          (request, response) -> {
            int status = response.getStatusCode().value();
            String text =
                new String(
                    response.getBody().readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode node = text.isBlank() ? json.createObjectNode() : json.readTree(text);
            return new Reply(
                status, node.path("code").asString(null), node.path("subject").asString(null));
          });
    } catch (IdentityProviderUnavailableException unavailable) {
      throw unavailable;
    } catch (ResourceAccessException io) {
      throw new IdentityProviderUnavailableException("io");
    } catch (RestClientResponseException rejected) {
      throw unavailable(rejected.getStatusCode());
    } catch (RuntimeException unreadable) {
      throw new IdentityProviderUnavailableException("unreadable_response");
    }
  }

  private static String subject(Reply reply) {
    String subject = reply.subject();
    if (subject == null || !ID.matcher(subject).matches()) {
      throw new IdentityProviderUnavailableException("unexpected_id");
    }
    return subject;
  }

  private IdentityProviderUnavailableException failure(Reply reply) {
    if (reply.status() == 401 || reply.status() == 403) {
      // A configuration fault (capability, audience, realm), never a business outcome: alert.
      LOG.atError()
          .addKeyValue("status", reply.status())
          .log("identity_provider_provisioner_unauthorized");
      return failure("provisioner_unauthorized");
    }
    return unavailable(HttpStatusCode.valueOf(reply.status()));
  }

  private static IdentityProviderUnavailableException failure(String reason) {
    return new IdentityProviderUnavailableException(reason);
  }

  private String token() {
    synchronized (tokenLock) {
      Instant now = Instant.now(clock);
      if (accessToken != null && now.isBefore(accessTokenExpiresAt)) {
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
                .uri(base("/realms/" + properties.realm() + "/protocol/openid-connect/token"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
      } catch (RestClientResponseException rejected) {
        throw unavailable(rejected.getStatusCode());
      } catch (ResourceAccessException io) {
        throw new IdentityProviderUnavailableException("io");
      }
      JsonNode node = json.readTree(body == null ? "{}" : body);
      String value = node.path("access_token").asString(null);
      long expiresIn = node.path("expires_in").asLong(0);
      if (value == null || value.isBlank()) {
        throw new IdentityProviderUnavailableException("token_missing");
      }
      accessToken = value;
      accessTokenExpiresAt = now.plusSeconds(Math.max(0, expiresIn - 30));
      return accessToken;
    }
  }

  private URI base(String path) {
    String root = properties.adminBaseUrl();
    return URI.create((root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + path);
  }

  private static IdentityProviderUnavailableException unavailable(HttpStatusCode status) {
    return new IdentityProviderUnavailableException("status_" + status.value() / 100 + "xx");
  }
}
