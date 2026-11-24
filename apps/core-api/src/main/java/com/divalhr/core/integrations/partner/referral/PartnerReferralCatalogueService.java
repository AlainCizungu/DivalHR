package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CatalogueProduct;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CatalogueProductPage;
import com.divalhr.core.integrations.partner.catalogue.PartnerCatalogueService;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.Product;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.ProductPage;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The employee's partner-product discovery (MVP-120F, D120F-1 and D120F-2). It is the existing
 * eligible catalogue of the caller's organization (approved and activated partner, active and
 * currently effective version, the organization's country) with the partner's categories. It reads
 * nothing about the employee, offers no category shortcut, and an unknown, malformed, foreign or
 * unavailable product is the same safe 404.
 */
@Service
public class PartnerReferralCatalogueService {

  private final PartnerCatalogueService catalogue;
  private final PartnerReferralRepository repository;

  /**
   * Creates the service.
   *
   * @param catalogue the organization catalogue
   * @param repository referral storage
   */
  public PartnerReferralCatalogueService(
      PartnerCatalogueService catalogue, PartnerReferralRepository repository) {
    this.catalogue = catalogue;
    this.repository = repository;
  }

  /**
   * A page of eligible products.
   *
   * @param cursor opaque cursor
   * @param limit raw page size
   * @param productType raw product type filter
   * @return the page
   */
  @Transactional(readOnly = true)
  public ProductPage list(String cursor, String limit, String productType) {
    CatalogueProductPage page = catalogue.products(cursor, limit, productType);
    List<Product> items = page.items().stream().map(this::product).toList();
    return new ProductPage(items, page.nextCursor(), page.availability());
  }

  /**
   * One eligible product.
   *
   * @param productId raw opaque product reference
   * @return the product
   */
  @Transactional(readOnly = true)
  public Product get(String productId) {
    try {
      return product(catalogue.product(productId));
    } catch (ApiException unavailable) {
      if (unavailable.code() == ErrorCode.PARTNER_PRODUCT_NOT_AVAILABLE) {
        throw new ApiException(ErrorCode.PARTNER_REFERRAL_PRODUCT_UNAVAILABLE, Map.of());
      }
      throw unavailable;
    }
  }

  private Product product(CatalogueProduct product) {
    return new Product(product, repository.categoriesOf(product.publicReference()), true);
  }
}
