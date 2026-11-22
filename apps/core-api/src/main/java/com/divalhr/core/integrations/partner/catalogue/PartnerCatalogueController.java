package com.divalhr.core.integrations.partner.catalogue;

import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CataloguePartnerList;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CatalogueProduct;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CatalogueProductPage;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.TenantScoped;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The safe, read-only product catalogue for the members of an organization (MVP-120E, ADR 0044).
 * Any active tenant member (tenant administrator or employee) may read it; the organization is the
 * caller's verified tenant, never a request value. Nothing here reads employee data, and nothing
 * unavailable is distinguishable from an unknown reference.
 */
@RestController
public class PartnerCatalogueController {

  /** Base path of the catalogue routes. */
  public static final String PATH = "/api/v1/catalogue";

  /** Rate-limit bucket of reads. */
  public static final String READ_BUCKET = "catalogue-read";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PartnerCatalogueService catalogue;

  /**
   * Creates the controller.
   *
   * @param catalogue catalogue service
   */
  public PartnerCatalogueController(PartnerCatalogueService catalogue) {
    this.catalogue = catalogue;
  }

  /**
   * Partners that currently offer products to the caller's organization.
   *
   * @return the partners
   */
  @Operation(operationId = "listCataloguePartners")
  @GetMapping(PATH + "/partners")
  @TenantScoped(operation = "catalogue.partners")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<CataloguePartnerList> partners() {
    return ok(catalogue.partners());
  }

  /**
   * Products currently available to the caller's organization.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param productType product type filter
   * @return a page of products
   */
  @Operation(operationId = "listCatalogueProducts")
  @GetMapping(PATH + "/products")
  @TenantScoped(operation = "catalogue.products")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<CatalogueProductPage> products(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @RequestParam(name = "productType", required = false) String productType) {
    return ok(catalogue.products(cursor, limit, productType));
  }

  /**
   * One product available to the caller's organization.
   *
   * @param publicReference opaque public reference
   * @return the product
   */
  @Operation(operationId = "getCatalogueProduct")
  @GetMapping(PATH + "/products/{publicReference}")
  @TenantScoped(operation = "catalogue.product")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<CatalogueProduct> product(
      @PathVariable("publicReference") String publicReference) {
    return ok(catalogue.product(publicReference));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }
}
