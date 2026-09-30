package com.divalhr.core.identity.internal.keycloak;

import com.divalhr.core.identity.application.IdentityDirectory;
import com.divalhr.core.identity.application.IdentityProviderUnavailableException;
import com.divalhr.core.identity.domain.TenantRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keycloak adapter for {@link IdentityDirectory}: the only class that knows Keycloak concepts.
 *
 * <p>The provisioner client authenticates with client credentials. Keycloak fine-grained admin
 * permissions (v2) let it create users only inside the two role groups {@code
 * divalhr-role-employee} and {@code divalhr-role-tenant-admin} and manage only their members; it
 * has no role-mapping permission at all, so it can never grant {@code platform-admin} and never
 * touch another identity (see infrastructure/docker/keycloak/README.md). The role is chosen here by
 * a closed switch over {@link TenantRole} and given in the same call that creates the user, so an
 * identity never exists without its role.
 *
 * <p>Idempotent per invitation: the invitation id is stored in the admin-only {@code
 * divalhr_invitation_id} attribute and looked up first. {@code emailVerified} is set because the
 * invitee proved control of the mailbox by presenting the single-use token sent to it. Response
 * bodies, addresses and tokens are never logged.
 */
@Component
public class KeycloakIdentityDirectory implements IdentityDirectory {

  static final String INVITATION_ATTRIBUTE = "divalhr_invitation_id";
  static final String TENANT_ATTRIBUTE = "tenant_id";

  private static final Pattern ID = Pattern.compile("^[0-9a-fA-F-]{36}$");

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

  /**
   * The group that carries a tenant role. Closed mapping: there is no group for {@code
   * platform-admin}.
   *
   * @param role tenant role
   * @return group path
   */
  static String groupPath(TenantRole role) {
    return switch (role) {
      case TENANT_ADMIN -> "/divalhr-role-tenant-admin";
      case EMPLOYEE -> "/divalhr-role-employee";
    };
  }

  @Override
  public ProvisioningResult provision(ProvisioningRequest request) {
    Optional<String> existing = findByInvitation(request.invitationId());
    if (existing.isPresent()) {
      return new Provisioned(existing.get());
    }
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("username", request.email().value());
    user.put("email", request.email().value());
    // Proven by possession of the single-use token that was sent to this address.
    user.put("emailVerified", true);
    user.put("enabled", true);
    user.put("groups", List.of(groupPath(request.role())));
    user.put(
        "attributes",
        Map.of(
            TENANT_ATTRIBUTE,
            List.of(request.tenant().toString()),
            INVITATION_ATTRIBUTE,
            List.of(request.invitationId().toString()),
            "locale",
            List.of(request.locale().tag())));
    user.put("requiredActions", List.of("UPDATE_PASSWORD"));
    try {
      http.post()
          .uri(admin("/users"))
          .headers(h -> h.setBearerAuth(token()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(json.writeValueAsString(user))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException rejected) {
      if (rejected.getStatusCode().value() == 409) {
        // Same address (or username) already exists. It could be this invitation's own identity
        // created by a lost earlier response: look again before reporting a conflict.
        return findByInvitation(request.invitationId())
            .<ProvisioningResult>map(Provisioned::new)
            .orElseGet(IdentityConflict::new);
      }
      throw unavailable(rejected.getStatusCode());
    } catch (ResourceAccessException io) {
      throw new IdentityProviderUnavailableException("io");
    }
    return findByInvitation(request.invitationId())
        .<ProvisioningResult>map(Provisioned::new)
        .orElseThrow(() -> new IdentityProviderUnavailableException("created_user_not_found"));
  }

  @Override
  public void requestCredentialSetup(String subject) {
    requireId(subject);
    URI uri =
        UriComponentsBuilder.fromUri(admin("/users/" + subject + "/execute-actions-email"))
            .queryParam("client_id", properties.webClientId())
            .queryParam("redirect_uri", properties.webRedirectUri())
            .queryParam("lifespan", properties.actionLifespan().toSeconds())
            .encode()
            .build()
            .toUri();
    try {
      http.put()
          .uri(uri)
          .headers(h -> h.setBearerAuth(token()))
          .contentType(MediaType.APPLICATION_JSON)
          .body("[\"UPDATE_PASSWORD\"]")
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException rejected) {
      throw unavailable(rejected.getStatusCode());
    } catch (ResourceAccessException io) {
      throw new IdentityProviderUnavailableException("io");
    }
  }

  @Override
  public void compensate(UUID invitationId) {
    Optional<String> created = findByInvitation(invitationId);
    if (created.isEmpty()) {
      return;
    }
    try {
      http.delete()
          .uri(admin("/users/" + requireId(created.get())))
          .headers(h -> h.setBearerAuth(token()))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException rejected) {
      if (rejected.getStatusCode().value() != 404) {
        throw unavailable(rejected.getStatusCode());
      }
    } catch (ResourceAccessException io) {
      throw new IdentityProviderUnavailableException("io");
    }
  }

  /** The user created for an invitation, verified by its exact attribute value. */
  private Optional<String> findByInvitation(UUID invitationId) {
    URI uri =
        UriComponentsBuilder.fromUri(admin("/users"))
            .queryParam("q", INVITATION_ATTRIBUTE + ":" + invitationId)
            .queryParam("exact", "true")
            .queryParam("briefRepresentation", "false")
            .queryParam("max", "2")
            .encode()
            .build()
            .toUri();
    String body;
    try {
      body =
          http.get().uri(uri).headers(h -> h.setBearerAuth(token())).retrieve().body(String.class);
    } catch (RestClientResponseException rejected) {
      throw unavailable(rejected.getStatusCode());
    } catch (ResourceAccessException io) {
      throw new IdentityProviderUnavailableException("io");
    }
    JsonNode users = json.readTree(body == null ? "[]" : body);
    for (JsonNode user : users) {
      JsonNode values = user.path("attributes").path(INVITATION_ATTRIBUTE);
      if (values.isArray()
          && values.size() == 1
          && invitationId.toString().equals(values.get(0).asString())) {
        return Optional.of(requireId(user.path("id").asString()));
      }
    }
    return Optional.empty();
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

  private URI admin(String path) {
    return base("/admin/realms/" + properties.realm() + path);
  }

  private URI base(String path) {
    String root = properties.adminBaseUrl();
    return URI.create((root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + path);
  }

  private static String requireId(String id) {
    if (id == null || !ID.matcher(id).matches()) {
      throw new IdentityProviderUnavailableException("unexpected_id");
    }
    return id;
  }

  private static IdentityProviderUnavailableException unavailable(HttpStatusCode status) {
    return new IdentityProviderUnavailableException("status_" + status.value() / 100 + "xx");
  }
}
