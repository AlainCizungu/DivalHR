package com.divalhr.core.identity.internal.mail;

import com.divalhr.core.identity.application.InvitationMailer.InvitationMessage;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.TenantRole;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Versioned, per-locale invitation templates ({@code mail/invitation-v1-<locale>.txt|html}, I18N
 * rule 10). Values are HTML-escaped in the HTML part. No tracking pixels, no external resources and
 * no email address in the body.
 */
@Component
public class InvitationTemplates {

  /** Template version, recorded with the message. */
  public static final String VERSION = "v1";

  private final Map<InvitationLocale, String[]> templates = new EnumMap<>(InvitationLocale.class);

  /** Loads every template at start-up (a missing template fails fast). */
  public InvitationTemplates() {
    for (InvitationLocale locale : InvitationLocale.values()) {
      templates.put(
          locale,
          new String[] {
            read("mail/invitation-" + VERSION + "-" + locale.tag() + ".txt"),
            read("mail/invitation-" + VERSION + "-" + locale.tag() + ".html")
          });
    }
  }

  /**
   * A rendered message.
   *
   * @param subject subject line
   * @param text plain-text body
   * @param html HTML body
   */
  public record Rendered(String subject, String text, String html) {}

  /**
   * Renders an invitation.
   *
   * @param message message
   * @return subject and bodies
   */
  public Rendered render(InvitationMessage message) {
    String[] pair = templates.get(message.locale());
    String role = roleLabel(message.role(), message.locale());
    ZoneId zone = zone(message.timezone());
    String expires =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
                .withLocale(message.locale().locale())
                .withZone(zone)
                .format(message.expiresAt())
            + " ("
            + zone.getId()
            + ")";
    String text = pair[0];
    int newline = text.indexOf('\n');
    String subject =
        fill(
            text.substring(0, newline).replaceFirst("^Subject: ", ""),
            message,
            role,
            expires,
            false);
    String body = fill(text.substring(newline + 1), message, role, expires, false);
    String html = fill(pair[1], message, role, expires, true);
    return new Rendered(subject, body, html);
  }

  /**
   * The role label shown in the email (kept in step with the web locales).
   *
   * @param role role
   * @param locale language
   * @return label
   */
  static String roleLabel(TenantRole role, InvitationLocale locale) {
    return switch (locale) {
      case FR ->
          switch (role) {
            case TENANT_ADMIN -> "Administrateur de l’organisation";
            case EMPLOYEE -> "Employé";
          };
      case EN ->
          switch (role) {
            case TENANT_ADMIN -> "Organization administrator";
            case EMPLOYEE -> "Employee";
          };
    };
  }

  private static String fill(
      String template, InvitationMessage message, String role, String expires, boolean html) {
    return template
        .replace("{{organization}}", escape(message.organizationName(), html))
        .replace("{{role}}", escape(role, html))
        .replace("{{expiresAt}}", escape(expires, html))
        .replace("{{link}}", escape(message.link(), html));
  }

  private static String escape(String value, boolean html) {
    return html ? HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name()) : value;
  }

  private static ZoneId zone(String timezone) {
    try {
      return ZoneId.of(timezone);
    } catch (RuntimeException invalid) {
      return ZoneId.of("UTC");
    }
  }

  private static String read(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException missing) {
      throw new UncheckedIOException("missing invitation template " + path, missing);
    }
  }
}
