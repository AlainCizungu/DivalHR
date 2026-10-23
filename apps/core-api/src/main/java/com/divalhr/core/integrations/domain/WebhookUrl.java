package com.divalhr.core.integrations.domain;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A validated, normalized webhook endpoint (D110-8): {@code https} only, port 443 or an
 * allow-listed port, no user information, no fragment, no IP-literal host, an ASCII (IDNA)
 * lower-case host without a trailing dot. The normalized form is what is stored and what is
 * contacted.
 *
 * @param host normalized ASCII host
 * @param port TCP port
 * @param normalized the stored URL
 */
public record WebhookUrl(String host, int port, String normalized) {

  /** Maximum stored length. */
  public static final int MAX_LENGTH = 2048;

  private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
  private static final Pattern IPV4_LIKE = Pattern.compile("^[0-9.]+$|^0[xX][0-9a-fA-F]+$");
  private static final Pattern PATH_CHARS = Pattern.compile("^[\\x21-\\x7e]*$");

  /**
   * Parses and normalizes.
   *
   * @param raw the submitted text
   * @param extraPorts HTTPS ports permitted besides 443
   * @return the endpoint, or empty when the text is not an acceptable endpoint
   */
  public static Optional<WebhookUrl> parse(String raw, Collection<Integer> extraPorts) {
    if (raw == null || raw.isBlank() || raw.length() > MAX_LENGTH || !raw.equals(raw.strip())) {
      return Optional.empty();
    }
    if (raw.chars().anyMatch(c -> c <= 0x20 || c == 0x7f || c == '\\')) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = new URI(raw);
    } catch (URISyntaxException malformed) {
      return Optional.empty();
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null
        || uri.getRawAuthority() == null
        || uri.getHost() == null) {
      return Optional.empty();
    }
    String host = uri.getHost();
    if (host.startsWith("[") || host.contains(":")) {
      return Optional.empty();
    }
    String ascii;
    try {
      ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    if (ascii.endsWith(".")) {
      return Optional.empty();
    }
    if (ascii.length() > 253 || !ascii.contains(".") || IPV4_LIKE.matcher(ascii).matches()) {
      return Optional.empty();
    }
    String[] labels = ascii.split("\\.", -1);
    for (String label : labels) {
      if (!LABEL.matcher(label).matches()) {
        return Optional.empty();
      }
    }
    if (labels[labels.length - 1].chars().allMatch(Character::isDigit)) {
      return Optional.empty();
    }
    int port = uri.getPort() == -1 ? 443 : uri.getPort();
    if (port != 443 && (extraPorts == null || !extraPorts.contains(port))) {
      return Optional.empty();
    }
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
    if (!PATH_CHARS.matcher(path + query).matches()) {
      return Optional.empty();
    }
    String authority = port == 443 ? ascii : ascii + ":" + port;
    return Optional.of(new WebhookUrl(ascii, port, "https://" + authority + path + query));
  }

  /**
   * @return path and query for the request line
   */
  public String requestTarget() {
    int start = normalized.indexOf('/', "https://".length());
    return normalized.substring(start);
  }

  /**
   * @return the stored form; never logged by callers
   */
  @Override
  public String toString() {
    return "WebhookUrl[redacted]";
  }
}
