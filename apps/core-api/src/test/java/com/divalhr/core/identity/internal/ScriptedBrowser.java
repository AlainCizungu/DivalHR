package com.divalhr.core.identity.internal;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A scripted browser for Keycloak's hosted pages: follows redirects, keeps cookies like a browser
 * profile (including {@code Secure} cookies on the loopback HTTP development host, which {@link
 * java.net.CookieManager} would drop) and fills the login, OTP, password-update and
 * authenticator-setup forms. Test-only.
 *
 * <p>TOTP secrets and codes stay in memory: they are never logged, and {@link Page#toString()}
 * never prints page content, so they cannot leak through assertion messages.
 */
final class ScriptedBrowser {

  static final String CLIENT_ID = "divalhr-web";
  static final String REDIRECT_URI = "http://localhost:5173/auth/callback";

  private static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private static final Pattern FORM = Pattern.compile("<form\\b[^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern PROCEED =
      Pattern.compile("href=\"([^\"]*/login-actions/action-token[^\"]*)\"");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final String baseUrl;
  private final String realm;
  private final Map<String, String> cookies = new LinkedHashMap<>();
  private String verifier;

  ScriptedBrowser(String baseUrl, String realm) {
    this.baseUrl = baseUrl;
    this.realm = realm;
  }

  /** A Keycloak page, or the redirect back to the application. */
  record Page(String url, String body, String formId, String action, Map<String, String> hidden) {

    boolean isCallback() {
      return url.startsWith(REDIRECT_URI);
    }

    String callbackParameter(String name) {
      String query = URI.create(url).getRawQuery();
      if (query == null) {
        return null;
      }
      for (String pair : query.split("&")) {
        int eq = pair.indexOf('=');
        if (eq > 0 && pair.substring(0, eq).equals(name)) {
          return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
      }
      return null;
    }

    /** Visible text with entities decoded, for message assertions. */
    String text() {
      return unescape(body.replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ");
    }

    @Override
    public String toString() {
      return "Page[form=" + formId + ", callback=" + isCallback() + "]";
    }
  }

  /** What a login attempt ended on, with the forms passed through (never their values). */
  record Outcome(Page last, java.util.List<String> forms) {

    boolean reachedApplication() {
      return last.isCallback() && last.callbackParameter("code") != null;
    }
  }

  /**
   * Starts an authorization-code + PKCE request.
   *
   * @param extra extra authorization parameters (for example {@code ui_locales})
   * @return the first page
   * @throws Exception on I/O failure
   */
  Page authorize(Map<String, String> extra) throws Exception {
    byte[] bytes = new byte[48];
    RANDOM.nextBytes(bytes);
    verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Map<String, String> params = new LinkedHashMap<>();
    params.put("client_id", CLIENT_ID);
    params.put("response_type", "code");
    params.put("scope", "openid");
    params.put("redirect_uri", REDIRECT_URI);
    params.put("code_challenge", challenge(verifier));
    params.put("code_challenge_method", "S256");
    params.put("state", "s");
    params.put("nonce", "n");
    params.putAll(extra);
    return get(baseUrl + "/realms/" + realm + "/protocol/openid-connect/auth?" + form(params));
  }

  /**
   * Opens a link, for example an action link from an email.
   *
   * @param url absolute URL
   * @return the resulting page
   * @throws Exception on I/O failure
   */
  Page open(String url) throws Exception {
    return get(url);
  }

  /**
   * Drives the hosted pages until the application is reached or a page repeats or is unknown.
   *
   * @param start first page
   * @param username username or email
   * @param password password (also used when a new password is required)
   * @param otp code for the OTP form; {@code null} when none is expected
   * @param enrolledSecret receives the secret of an authenticator set up on the way
   * @return the outcome
   * @throws Exception on I/O failure
   */
  Outcome drive(
      Page start,
      String username,
      String password,
      Supplier<String> otp,
      java.util.function.Consumer<String> enrolledSecret)
      throws Exception {
    java.util.List<String> forms = new java.util.ArrayList<>();
    Page page = start;
    for (int step = 0; step < 10; step++) {
      if (page.isCallback()) {
        return new Outcome(page, forms);
      }
      Matcher proceed = PROCEED.matcher(page.body());
      if (page.formId() == null && proceed.find()) {
        forms.add("proceed-link");
        page = get(absolute(unescape(proceed.group(1))));
        continue;
      }
      String id = page.formId();
      if (id == null
          || (!forms.isEmpty()
              && id.equals(forms.get(forms.size() - 1))
              && !id.equals("kc-totp-settings-form"))) {
        return new Outcome(page, forms);
      }
      forms.add(id);
      Map<String, String> fields = new LinkedHashMap<>(page.hidden());
      switch (id) {
        case "kc-form-login" -> {
          fields.put("username", username);
          fields.put("password", password);
        }
        case "kc-otp-login-form" -> {
          if (otp == null) {
            return new Outcome(page, forms);
          }
          fields.put("otp", otp.get());
        }
        case "kc-totp-settings-form" -> {
          String secret = fields.get("totpSecret");
          enrolledSecret.accept(secret);
          fields.put("totp", totp(secret, System.currentTimeMillis() / 1000));
          // Unique: Keycloak refuses a second authenticator with the same device name.
          fields.put(
              "userLabel", "Test authenticator " + UUID.randomUUID().toString().substring(0, 8));
          fields.put("mode", "manual");
        }
        case "kc-passwd-update-form" -> {
          fields.put("password-new", password);
          fields.put("password-confirm", password);
        }
        default -> {
          return new Outcome(page, forms);
        }
      }
      page = post(page.action(), fields);
    }
    return new Outcome(page, forms);
  }

  /**
   * Exchanges the authorization code of a callback for tokens.
   *
   * @param callback the callback page
   * @return the token response body
   * @throws Exception on I/O failure
   */
  String exchange(Page callback) throws Exception {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("grant_type", "authorization_code");
    params.put("code", callback.callbackParameter("code"));
    params.put("client_id", CLIENT_ID);
    params.put("redirect_uri", REDIRECT_URI);
    params.put("code_verifier", verifier);
    return tokenRequest(params);
  }

  /**
   * Refreshes tokens as the public web client.
   *
   * @param refreshToken refresh token
   * @return the token response body
   * @throws Exception on I/O failure
   */
  String refresh(String refreshToken) throws Exception {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("grant_type", "refresh_token");
    params.put("refresh_token", refreshToken);
    params.put("client_id", CLIENT_ID);
    return tokenRequest(params);
  }

  private String tokenRequest(Map<String, String> params) throws Exception {
    return HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl + "/realms/" + realm + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .body();
  }

  private Page get(String url) throws Exception {
    return follow(send(HttpRequest.newBuilder(URI.create(url)).GET()));
  }

  private Page post(String url, Map<String, String> fields) throws Exception {
    return follow(
        send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields)))));
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    if (!cookies.isEmpty()) {
      StringBuilder header = new StringBuilder();
      cookies.forEach(
          (name, value) ->
              header.append(header.isEmpty() ? "" : "; ").append(name).append('=').append(value));
      builder.header("Cookie", header.toString());
    }
    HttpResponse<String> response =
        HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    for (String cookie : response.headers().allValues("Set-Cookie")) {
      String pair = cookie.split(";", 2)[0];
      int eq = pair.indexOf('=');
      String name = pair.substring(0, eq).trim();
      String value = pair.substring(eq + 1);
      boolean expired = cookie.contains("Max-Age=0") || cookie.contains("1970") || value.isEmpty();
      if (expired) {
        cookies.remove(name);
      } else {
        cookies.put(name, value);
      }
    }
    return response;
  }

  private Page follow(HttpResponse<String> first) throws Exception {
    HttpResponse<String> response = first;
    for (int hop = 0; hop < 10; hop++) {
      int status = response.statusCode();
      if (status != 301 && status != 302 && status != 303) {
        return page(response.uri().toString(), response.body());
      }
      String location = response.headers().firstValue("Location").orElseThrow();
      String target = response.uri().resolve(location).toString();
      if (target.startsWith(REDIRECT_URI)) {
        return page(target, "");
      }
      response = send(HttpRequest.newBuilder(URI.create(target)).GET());
    }
    throw new IllegalStateException("too many redirects");
  }

  private Page page(String url, String body) {
    Matcher form = FORM.matcher(body);
    String id = null;
    String action = null;
    Map<String, String> hidden = new LinkedHashMap<>();
    if (form.find()) {
      id = attribute(form.group(), "id");
      action = unescape(attribute(form.group(), "action"));
      int end = body.indexOf("</form>", form.end());
      Matcher input = INPUT.matcher(body.substring(form.end(), end < 0 ? body.length() : end));
      while (input.find()) {
        if ("hidden".equalsIgnoreCase(attribute(input.group(), "type"))
            && attribute(input.group(), "name") != null) {
          String value = attribute(input.group(), "value");
          hidden.put(attribute(input.group(), "name"), value == null ? "" : unescape(value));
        }
      }
    }
    return new Page(url, body, id, action, hidden);
  }

  private String absolute(String url) {
    return URI.create(baseUrl + "/").resolve(url).toString();
  }

  private static String attribute(String tag, String name) {
    Matcher m = Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(tag);
    return m.find() ? m.group(1) : null;
  }

  static String unescape(String text) {
    Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String entity = m.group(1);
      String replacement =
          switch (entity) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            default ->
                new String(
                    Character.toChars(
                        entity.startsWith("#x")
                            ? Integer.parseInt(entity.substring(2), 16)
                            : Integer.parseInt(entity.substring(1))));
          };
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String form(Map<String, String> params) {
    StringBuilder out = new StringBuilder();
    params.forEach(
        (name, value) ->
            out.append(out.isEmpty() ? "" : "&")
                .append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
    return out.toString();
  }

  private static String challenge(String verifier) throws NoSuchAlgorithmException {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  /**
   * RFC 6238 TOTP with HMAC-SHA1, six digits and 30-second steps, as configured in the realm.
   * Keycloak's setup form carries the raw secret; authenticator apps receive the same bytes
   * base32-encoded in the QR code.
   *
   * @param secret raw secret
   * @param epochSeconds time
   * @return the code
   */
  static String totp(String secret, long epochSeconds) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(epochSeconds / 30).array());
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
