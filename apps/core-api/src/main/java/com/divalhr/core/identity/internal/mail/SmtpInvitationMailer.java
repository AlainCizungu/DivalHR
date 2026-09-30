package com.divalhr.core.identity.internal.mail;

import com.divalhr.core.identity.application.InvitationMailer;
import com.divalhr.core.identity.domain.DeliveryState;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP adapter for {@link InvitationMailer} (decision D-7). One attempt per call with bounded
 * timeouts and no retry: the link's token exists only in memory for this attempt. Development sends
 * to the loopback-bound Mailpit capture (decision D-8). Never logs addresses or links.
 */
@Component
public final class SmtpInvitationMailer implements InvitationMailer {

  private final JavaMailSenderImpl sender;
  private final InvitationTemplates templates;
  private final InternetAddress from;

  /**
   * Creates the mailer.
   *
   * @param properties mail settings (validated here: fail closed)
   * @param environment deployment environment
   * @param templates templates
   */
  public SmtpInvitationMailer(
      MailProperties properties,
      @Value("${divalhr.environment}") String environment,
      InvitationTemplates templates) {
    properties.validate(environment);
    this.templates = templates;
    this.sender = new JavaMailSenderImpl();
    sender.setHost(properties.host());
    sender.setPort(properties.port());
    sender.setProtocol(Boolean.TRUE.equals(properties.ssl()) ? "smtps" : "smtp");
    boolean authenticated = properties.username() != null && !properties.username().isBlank();
    if (authenticated) {
      sender.setUsername(properties.username());
      sender.setPassword(properties.password());
    }
    sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
    Properties smtp = new Properties();
    String timeout = Long.toString(properties.timeout().toMillis());
    String prefix = "mail." + sender.getProtocol() + ".";
    smtp.put(prefix + "connectiontimeout", timeout);
    smtp.put(prefix + "timeout", timeout);
    smtp.put(prefix + "writetimeout", timeout);
    smtp.put(prefix + "auth", Boolean.toString(authenticated));
    smtp.put(prefix + "starttls.enable", Boolean.toString(properties.starttls()));
    smtp.put(prefix + "starttls.required", Boolean.toString(properties.starttls()));
    sender.setJavaMailProperties(smtp);
    try {
      this.from = new InternetAddress(properties.from(), properties.fromName(), "UTF-8");
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @Override
  public DeliveryState send(InvitationMessage message) {
    try {
      InvitationTemplates.Rendered rendered = templates.render(message);
      MimeMessage mime = sender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
      helper.setFrom(from);
      helper.setTo(message.to().value());
      helper.setSubject(rendered.subject());
      helper.setText(rendered.text(), rendered.html());
      mime.setHeader("Content-Language", message.locale().tag());
      mime.setHeader("X-DivalHR-Template", "invitation-" + InvitationTemplates.VERSION);
      sender.send(mime);
      return DeliveryState.SENT;
    } catch (jakarta.mail.MessagingException | RuntimeException failed) {
      return DeliveryState.FAILED;
    }
  }
}
