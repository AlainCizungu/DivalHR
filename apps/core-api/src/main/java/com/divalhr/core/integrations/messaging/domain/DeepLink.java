package com.divalhr.core.integrations.messaging.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one deep link a notification carries (D111-7): the operator-controlled public base URL plus
 * the allow-listed relative path of the notification type. It carries no token, no tenant id and no
 * resource id; the recipient authenticates normally when they open it. The template's URL button
 * has a fixed base and a {@code {{1}}} suffix, so only the path suffix is sent as a parameter.
 */
public final class DeepLink {

  private static final Pattern BASE = Pattern.compile("^https://[a-z0-9.-]+(:[0-9]{1,5})?$");
  private static final Pattern LOCAL_BASE = Pattern.compile("^http://localhost(:[0-9]{1,5})?$");

  private DeepLink() {}

  /**
   * Normalizes an operator-supplied public base URL.
   *
   * @param raw candidate such as {@code https://hr.example.com}
   * @param allowLocalHttp whether plain {@code http://localhost} is acceptable (development only)
   * @return the base without a trailing slash, or empty when it is not an acceptable origin
   */
  public static Optional<String> normalizeBase(String raw, boolean allowLocalHttp) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.strip();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    boolean acceptable =
        BASE.matcher(trimmed).matches() || allowLocalHttp && LOCAL_BASE.matcher(trimmed).matches();
    return acceptable && trimmed.length() <= 253 ? Optional.of(trimmed) : Optional.empty();
  }

  /**
   * The absolute link.
   *
   * @param base normalized base
   * @param type the notification type
   * @return the absolute URL
   */
  public static String absolute(String base, NotificationType type) {
    return base + type.path();
  }

  /**
   * The URL-button parameter: the path without its leading slash, appended by Meta to the fixed
   * template base {@code <base>/{{1}}}.
   *
   * @param type the notification type
   * @return the suffix
   */
  public static String buttonSuffix(NotificationType type) {
    return type.path().substring(1);
  }

  /**
   * The base a template's URL button must declare so that base + suffix equals the absolute link.
   *
   * @param base normalized base
   * @return {@code <base>/{{1}}}
   */
  public static String expectedButtonUrl(String base) {
    return base + "/{{1}}";
  }
}
