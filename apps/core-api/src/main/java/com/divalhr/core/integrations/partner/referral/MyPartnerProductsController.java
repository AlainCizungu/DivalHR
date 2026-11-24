package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.Product;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.ProductPage;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The employee's partner-product discovery (MVP-120F, ADR 0045). Only an active employee member of
 * the organization has a route here: tenant administrators and platform administrators have none.
 * Every response is {@code Cache-Control: private, no-store}. Nothing about the employee is read.
 */
@RestController
public class MyPartnerProductsController {

  /** Base path. */
  public static final String PATH = "/api/v1/me/partner-products";

  /** Rate-limit bucket of discovery reads (requests per minute per subject). */
  public static final String READ_BUCKET = "partner-referral-read";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PartnerReferralCatalogueService catalogue;

  /**
   * Creates the controller.
   *
   * @param catalogue eligible products
   */
  public MyPartnerProductsController(PartnerReferralCatalogueService catalogue) {
    this.catalogue = catalogue;
  }

  /**
   * Eligible products for the caller's organization.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param productType product type filter
   * @return a page of products
   */
  @Operation(operationId = "listMyPartnerProducts")
  @GetMapping(PATH)
  @EmployeeSelfOperation(operation = "partner-referral.products")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<ProductPage> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @RequestParam(name = "productType", required = false) String productType) {
    return ok(catalogue.list(cursor, limit, productType));
  }

  /**
   * One eligible product.
   *
   * @param productId opaque product reference
   * @return the product
   */
  @Operation(operationId = "getMyPartnerProduct")
  @GetMapping(PATH + "/{productId}")
  @EmployeeSelfOperation(operation = "partner-referral.product")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<Product> get(@PathVariable("productId") String productId) {
    return ok(catalogue.get(productId));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }
}
