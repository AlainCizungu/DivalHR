package com.divalhr.core.integrations;

import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.TestTokens;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MVP-120F test fixtures. Every organization, employee, address and partner here is SYNTHETIC. The
 * prerequisite rows (an activated partner with one live product) are written with triggers off, so
 * the tests exercise the referral code and the V57 guards, not the partner onboarding flow that
 * MVP-120D and MVP-120E already cover.
 */
final class PartnerReferralWorld {

  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  /** A product that can be referred to. */
  record Product(UUID partner, UUID product, UUID version, String reference) {}

  /** An employee of an organization, with a bearer token. */
  record Employee(UUID tenant, UUID employee, UUID membership, String subject, String bearer) {}

  private PartnerReferralWorld() {}

  static String ref(String prefix) {
    Random random = new Random(UUID.randomUUID().getMostSignificantBits());
    StringBuilder b = new StringBuilder(prefix);
    for (int i = 0; i < 16; i++) {
      b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return b.toString();
  }

  static UUID organization(JdbcTemplate jdbc, String country, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
            + " created_at, created_by) VALUES (?, ?, ?, 'en', 'Africa/Kinshasa', now(),"
            + " 'sub-referral')",
        id,
        name,
        country);
    return id;
  }

  /** An employee with a linked employee membership and a verified address. */
  static Employee employee(
      JdbcTemplate jdbc, UUID tenant, String given, String family, String email) {
    UUID employee = UUID.randomUUID();
    String subject = "sub-employee-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " search_key, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, now(), 'test')",
        employee,
        tenant,
        "E" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
        given,
        family,
        " " + given.toLowerCase() + " " + family.toLowerCase() + " ");
    Memberships.grant(tenant, subject, "employee");
    UUID membership =
        jdbc.queryForObject(
            "SELECT id FROM identity.tenant_membership WHERE tenant_id = ? AND subject = ?",
            UUID.class,
            tenant,
            subject);
    if (email != null) {
      withoutTriggers(
          jdbc,
          "UPDATE identity.tenant_membership SET email = '"
              + email
              + "' WHERE id = '"
              + membership
              + "'");
    }
    jdbc.update(
        "INSERT INTO identity.employee_access_link (id, tenant_id, employee_id, membership_id,"
            + " linked_at, linked_by) VALUES (?, ?, ?, ?, now(), 'test')",
        UUID.randomUUID(),
        tenant,
        employee,
        membership);
    String bearer =
        "Bearer "
            + TestTokens.token().subject(subject).tenant(tenant).roles(List.of("employee")).build();
    return new Employee(tenant, employee, membership, subject, bearer);
  }

  static String admin(JdbcTemplate jdbc, UUID tenant) {
    String subject = "sub-admin-" + UUID.randomUUID();
    Memberships.grant(tenant, subject, "tenant-admin");
    return "Bearer "
        + TestTokens.token().subject(subject).tenant(tenant).roles(List.of("tenant-admin")).build();
  }

  /**
   * An activated partner with one live (ACTIVE, effective yesterday) product offered in countries.
   */
  static Product product(
      JdbcTemplate jdbc, String status, String versionStatus, List<String> countries) {
    UUID partner = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    UUID version = UUID.randomUUID();
    String reference = ref("PP-");
    StringBuilder sql = new StringBuilder();
    sql.append(
            "INSERT INTO integrations.partner (id, reference, application_id, status, row_version,")
        .append(" primary_category, legal_name, trading_name, country_of_registration, website,")
        .append(" supported_languages, created_at, updated_at) VALUES ('")
        .append(partner)
        .append("', '")
        .append(ref("PT-"))
        .append("', '")
        .append(UUID.randomUUID())
        .append("', '")
        .append(status)
        .append("', 0, 'BANK', 'Synthetic Partner Legal',")
        .append(" 'Synthetic Partner', 'CD', 'https://partner.example.test/',")
        .append(" ARRAY['en','fr'], now(), now());");
    sql.append("INSERT INTO integrations.partner_category (partner_id, category, position)")
        .append(" VALUES ('")
        .append(partner)
        .append("', 'MICROFINANCE', 1);");
    sql.append("INSERT INTO integrations.partner_product (id, partner_id, product_code,")
        .append(" public_reference, product_type, created_at) VALUES ('")
        .append(product)
        .append("', '")
        .append(partner)
        .append("', 'SYN_PRODUCT', '")
        .append(reference)
        .append("', 'OTHER_BENEFIT', now());");
    sql.append("INSERT INTO integrations.partner_product_version (id, product_id, version, status,")
        .append(" row_version, name_en, name_fr, summary_en, summary_fr, eligibility_en,")
        .append(" eligibility_fr, disclosure_en, disclosure_fr, effective_date, created_at,")
        .append(" updated_at) VALUES ('")
        .append(version)
        .append("', '")
        .append(product)
        .append("', 1, '")
        .append(versionStatus)
        .append("', 0, 'Synthetic benefit',")
        .append(" 'Avantage synthetique', 'A synthetic benefit for tests.',")
        .append(" 'Un avantage synthetique de test.', 'Open to every synthetic employee.',")
        .append(" 'Ouvert a tout employe synthetique.',")
        .append(" 'Synthetic disclosure text for the referral test only.',")
        .append(" 'Texte synthetique de divulgation pour le test de renvoi.', '")
        .append(Date.valueOf(LocalDate.now().minusDays(1)))
        .append("', now(), now());");
    for (String country : countries) {
      sql.append("INSERT INTO integrations.partner_product_country (version_id, country_code)")
          .append(" VALUES ('")
          .append(version)
          .append("', '")
          .append(country)
          .append("');");
    }
    withoutTriggers(jdbc, sql.toString());
    return new Product(partner, product, version, reference);
  }

  static void withoutTriggers(JdbcTemplate jdbc, String statements) {
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              try (java.sql.Statement statement = connection.createStatement()) {
                statement.setEscapeProcessing(false);
                statement.execute("SET session_replication_role = replica");
                try {
                  statement.execute(statements);
                } finally {
                  statement.execute("RESET session_replication_role");
                }
              }
              return null;
            });
  }
}
