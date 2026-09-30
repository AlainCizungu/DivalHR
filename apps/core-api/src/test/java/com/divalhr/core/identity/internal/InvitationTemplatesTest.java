package com.divalhr.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.InvitationMailer.InvitationMessage;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.identity.internal.mail.InvitationTemplates;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Versioned bilingual templates: parity, escaping, the link and no address in the body. */
class InvitationTemplatesTest {

  private final InvitationTemplates templates = new InvitationTemplates();

  private static InvitationMessage message(InvitationLocale locale, String organization) {
    return new InvitationMessage(
        EmailAddress.parse("ana.mbuyi@example.cd").orElseThrow(),
        locale,
        organization,
        "Africa/Kinshasa",
        TenantRole.TENANT_ADMIN,
        Instant.parse("2026-10-07T08:30:00Z"),
        "https://app.example.com/invitation#token=" + "A".repeat(43));
  }

  @Test
  void frenchAndEnglishAreBothCompleteAndLocalized() {
    InvitationTemplates.Rendered fr =
        templates.render(message(InvitationLocale.FR, "Société Minière de Kinshasa"));
    InvitationTemplates.Rendered en =
        templates.render(message(InvitationLocale.EN, "Société Minière de Kinshasa"));
    assertThat(fr.subject())
        .isEqualTo("Invitation à rejoindre Société Minière de Kinshasa sur DivalHR");
    assertThat(en.subject()).isEqualTo("Invitation to join Société Minière de Kinshasa on DivalHR");
    assertThat(fr.text())
        .contains("Administrateur de l’organisation")
        .contains("7 octobre 2026")
        .contains("09:30")
        .contains("(Africa/Kinshasa)")
        .contains("#token=" + "A".repeat(43));
    assertThat(en.text())
        .contains("Organization administrator")
        .contains("October 7, 2026")
        .contains("#token=" + "A".repeat(43));
    for (InvitationTemplates.Rendered rendered : new InvitationTemplates.Rendered[] {fr, en}) {
      assertThat(rendered.text() + rendered.html() + rendered.subject())
          .doesNotContain("ana.mbuyi")
          .doesNotContain("{{")
          .doesNotContain("<img")
          .doesNotContain("http://");
    }
  }

  @Test
  void organizationNamesAreEscapedInHtml() {
    InvitationTemplates.Rendered rendered =
        templates.render(message(InvitationLocale.EN, "<script>alert('x')</script> & Co"));
    assertThat(rendered.html())
        .doesNotContain("<script>")
        .contains("&lt;script&gt;")
        .contains("&amp; Co");
  }
}
