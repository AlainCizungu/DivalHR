package com.divalhr.core.integrations.partner.referral;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Storage of the consented partner referral (MVP-120F, ADR 0045). Every statement is parameterized
 * and touches only the integrations tables; the employee, membership and organization facts come
 * through platform ports.
 */
@Repository
public class PartnerReferralRepository {

  /**
   * An eligible product version with the identities a referral binds to.
   *
   * @param partnerId partner
   * @param productId product
   * @param versionId live version
   * @param version version number
   * @param publicReference opaque product reference
   * @param productType product type
   * @param partnerDisplayName partner display name
   * @param nameEn English product name
   * @param nameFr French product name
   * @param primaryCategory the partner's primary category
   */
  public record ProductIdentity(
      UUID partnerId,
      UUID productId,
      UUID versionId,
      int version,
      String publicReference,
      String productType,
      String partnerDisplayName,
      String nameEn,
      String nameFr,
      String primaryCategory) {}

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public PartnerReferralRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The eligible product (approved and activated partner, active and currently effective version,
   * offered in the country) with a public reference. Anything else is absent.
   *
   * @param country organization country
   * @param today UTC date
   * @param publicReference opaque product reference
   * @return the identity
   */
  public Optional<ProductIdentity> eligibleIdentity(
      String country, LocalDate today, String publicReference) {
    return jdbc.sql(
            """
            SELECT p.id AS partner_id, x.id AS product_id, v.id AS version_id, v.version,
                   x.public_reference, x.product_type,
                   coalesce(p.trading_name, p.legal_name) AS display,
                   v.name_en, v.name_fr, p.primary_category
              FROM integrations.partner_product_version v
              JOIN integrations.partner_product x ON x.id = v.product_id
              JOIN integrations.partner p ON p.id = x.partner_id
             WHERE v.status = 'ACTIVE' AND p.status = 'ACTIVE'
               AND v.effective_date <= :today AND (v.end_date IS NULL OR v.end_date >= :today)
               AND EXISTS (SELECT 1 FROM integrations.partner_product_country c
                            WHERE c.version_id = v.id AND c.country_code = :country)
               AND x.public_reference = :reference
             ORDER BY v.version DESC LIMIT 1
            """)
        .param("today", Date.valueOf(today))
        .param("country", country)
        .param("reference", publicReference)
        .query(
            (rs, n) ->
                new ProductIdentity(
                    rs.getObject("partner_id", UUID.class),
                    rs.getObject("product_id", UUID.class),
                    rs.getObject("version_id", UUID.class),
                    rs.getInt("version"),
                    rs.getString("public_reference"),
                    rs.getString("product_type"),
                    rs.getString("display"),
                    rs.getString("name_en"),
                    rs.getString("name_fr"),
                    rs.getString("primary_category")))
        .optional();
  }

  /**
   * The categories of the partner that offers a product: the primary category first, then the
   * additional ones, without repeats.
   *
   * @param partnerId partner
   * @param primary the primary category
   * @return the categories
   */
  public List<String> categories(UUID partnerId, String primary) {
    List<String> categories = new ArrayList<>();
    categories.add(primary);
    for (String extra :
        jdbc.sql(
                "SELECT category FROM integrations.partner_category WHERE partner_id = :id"
                    + " ORDER BY position")
            .param("id", partnerId)
            .query(String.class)
            .list()) {
      if (!categories.contains(extra)) {
        categories.add(extra);
      }
    }
    return List.copyOf(categories);
  }

  /**
   * The categories of the partner that offers a product, by the product's public reference.
   *
   * @param publicReference opaque product reference
   * @return the categories, or empty when the product is unknown
   */
  public List<String> categoriesOf(String publicReference) {
    List<Object[]> rows =
        jdbc.sql(
                "SELECT p.id, p.primary_category FROM integrations.partner_product x"
                    + " JOIN integrations.partner p ON p.id = x.partner_id"
                    + " WHERE x.public_reference = :reference")
            .param("reference", publicReference)
            .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)})
            .list();
    if (rows.isEmpty()) {
      return List.of();
    }
    return categories((UUID) rows.get(0)[0], (String) rows.get(0)[1]);
  }
}
