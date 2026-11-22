package com.divalhr.core.integrations.partner.catalogue;

import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CataloguePartner;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CataloguePartnerList;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CatalogueProduct;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CatalogueProductPage;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountDomain.ProductType;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountRepository.CatalogueRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.TenantContext;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The safe, read-only product catalogue for the members of an organization (MVP-120E, ADR 0044
 * D120E-9 and D120E-10). Eligibility uses only the organization's country, the effective dates and
 * the statuses; nothing about any employee is read or shared, and no application, enrollment or
 * connection exists. Nothing unavailable is distinguishable: an unknown, malformed, ineligible,
 * future, expired, paused, retired, unpublished or cross-tenant reference is the same 404.
 */
@Service
public class PartnerCatalogueService {

  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 50;
  private static final Pattern PUBLIC_REFERENCE = Pattern.compile("^PP-[0-9A-HJKMNP-TV-Z]{16}$");
  private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,3}$");
  private static final String AVAILABLE = "AVAILABLE";
  private static final String UNKNOWN_COUNTRY = "ORGANIZATION_COUNTRY_UNKNOWN";

  private final PartnerAccountRepository repository;
  private final OrganizationDirectory organizations;
  private final TenantContextResolver tenants;
  private final CursorCodec cursors;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param repository storage
   * @param organizations organization directory
   * @param tenants verified tenant resolver
   * @param cursors signed cursors
   * @param clock clock
   */
  public PartnerCatalogueService(
      PartnerAccountRepository repository,
      OrganizationDirectory organizations,
      TenantContextResolver tenants,
      CursorCodec cursors,
      Clock clock) {
    this.repository = repository;
    this.organizations = organizations;
    this.tenants = tenants;
    this.cursors = cursors;
    this.clock = clock;
  }

  /**
   * Partners with at least one eligible product.
   *
   * @return the list
   */
  @Transactional(readOnly = true)
  public CataloguePartnerList partners() {
    TenantContext context = tenants.current();
    Optional<String> country = organizations.countryCode(context.tenantId());
    if (country.isEmpty()) {
      return new CataloguePartnerList(List.of(), UNKNOWN_COUNTRY, true);
    }
    List<CataloguePartner> items = new ArrayList<>();
    for (Object[] row : repository.eligiblePartners(country.get(), today())) {
      UUID partnerId = (UUID) row[0];
      List<String> categories = new ArrayList<>();
      categories.add((String) row[2]);
      categories.addAll(repository.additionalCategories(partnerId));
      items.add(new CataloguePartner((String) row[1], List.copyOf(categories), (Integer) row[3]));
    }
    return new CataloguePartnerList(items, AVAILABLE, true);
  }

  /**
   * A page of eligible products.
   *
   * @param cursor opaque cursor
   * @param limit raw page size
   * @param type raw product type filter
   * @return the page
   */
  @Transactional(readOnly = true)
  public CatalogueProductPage products(String cursor, String limit, String type) {
    int size = limit(limit);
    String typeFilter = null;
    if (type != null) {
      ProductType parsed = PartnerAccountDomain.parse(ProductType.class, type);
      if (parsed == null) {
        throw PartnerAccountCommands.shape("productType", "FORMAT");
      }
      typeFilter = parsed.name();
    }
    TenantContext context = tenants.current();
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "catalogue");
    filters.put("limit", Integer.toString(size));
    if (typeFilter != null) {
      filters.put("productType", typeFilter);
    }
    CursorScope scope = new CursorScope("catalogue.products", context.tenantId(), filters);
    Optional<String> country = organizations.countryCode(context.tenantId());
    if (country.isEmpty()) {
      return new CatalogueProductPage(List.of(), null, UNKNOWN_COUNTRY, true);
    }
    String after = null;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, scope);
      after =
          repository
              .publicReferenceOf(position.id())
              .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
    }
    List<CatalogueRow> rows =
        repository.eligibleProducts(country.get(), today(), typeFilter, after, size + 1);
    List<CatalogueRow> shown = rows.subList(0, Math.min(rows.size(), size));
    String next =
        rows.size() > size
            ? cursors.encode(
                scope, new KeysetPosition("PP", shown.get(shown.size() - 1).versionId()))
            : null;
    return new CatalogueProductPage(
        shown.stream().map(PartnerCatalogueService::safe).toList(), next, AVAILABLE, true);
  }

  /**
   * One eligible product.
   *
   * @param publicReference raw public reference
   * @return the product
   */
  @Transactional(readOnly = true)
  public CatalogueProduct product(String publicReference) {
    TenantContext context = tenants.current();
    if (publicReference == null || !PUBLIC_REFERENCE.matcher(publicReference).matches()) {
      throw unavailable();
    }
    String country =
        organizations
            .countryCode(context.tenantId())
            .orElseThrow(PartnerCatalogueService::unavailable);
    return repository
        .eligibleProduct(country, today(), publicReference)
        .map(PartnerCatalogueService::safe)
        .orElseThrow(PartnerCatalogueService::unavailable);
  }

  private static CatalogueProduct safe(CatalogueRow row) {
    var c = row.content();
    return new CatalogueProduct(
        row.publicReference(),
        row.partnerName(),
        row.productType(),
        c.name(),
        c.summary(),
        c.eligibility(),
        c.disclosure(),
        c.infoUrl(),
        c.effectiveDate(),
        c.endDate(),
        c.minMonths(),
        c.maxMonths(),
        c.currencies(),
        true);
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC));
  }

  private static ApiException unavailable() {
    return new ApiException(ErrorCode.PARTNER_PRODUCT_NOT_AVAILABLE, Map.of());
  }

  private static int limit(String raw) {
    if (raw == null || raw.isEmpty()) {
      return DEFAULT_LIMIT;
    }
    if (!DIGITS.matcher(raw).matches()) {
      throw PartnerAccountCommands.shape("limit", "FORMAT");
    }
    int value = Integer.parseInt(raw);
    if (value < 1 || value > MAX_LIMIT) {
      throw PartnerAccountCommands.shape("limit", "RANGE");
    }
    return value;
  }
}
