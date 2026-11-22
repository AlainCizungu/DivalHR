package com.divalhr.core.integrations.partner.catalogue;

import com.divalhr.core.integrations.partner.PartnerCountries;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.Bilingual;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.Content;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CurrencyLimit;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountDomain.PartnerAction;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountDomain.ProductAction;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountDomain.ProductType;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountDomain.Reason;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Strict parsing of the command bodies of MVP-120E. Unknown fields, wrong types and out-of-range
 * values are refused with a field name and a constraint only; no value is ever echoed. Shape errors
 * of partner commands are {@code VALIDATION_FAILED}; product content errors are {@code
 * PARTNER_PRODUCT_INVALID}.
 */
final class PartnerAccountCommands {

  static final int NOTE_MAX = 500;
  private static final Pattern PRODUCT_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,39}$");
  private static final Pattern AMOUNT = Pattern.compile("^[0-9]{1,16}(\\.[0-9]{1,2})?$");
  private static final Pattern URL = Pattern.compile("^https://[^\\s]+$");
  private static final BigDecimal LIMIT = new BigDecimal("1000000000000");

  private PartnerAccountCommands() {}

  /** A parsed partner transition. */
  record PartnerTransition(
      PartnerAction action,
      int expectedVersion,
      Reason reason,
      String note,
      boolean confirmed,
      List<String> countries) {}

  /** A parsed product creation. */
  record ProductCreate(String code, ProductType type, Content content) {}

  /** A parsed draft save. */
  record DraftSave(int expectedVersion, int expectedRowVersion, Content content) {}

  /** A parsed product transition. */
  record ProductTransition(
      ProductAction action,
      int version,
      int expectedRowVersion,
      Reason reason,
      String note,
      boolean confirmed) {}

  static boolean parseCreate(JsonNode body) {
    only(body, Set.of("confirmed"), false);
    JsonNode node = body.get("confirmed");
    if (node == null || !node.isBoolean()) {
      throw shape("confirmed", node == null ? "REQUIRED" : "FORMAT");
    }
    if (!node.booleanValue()) {
      throw shape("confirmed", "REQUIRED");
    }
    return true;
  }

  static PartnerTransition parseTransition(JsonNode body) {
    only(
        body,
        Set.of("action", "expectedVersion", "reasonCode", "note", "confirmed", "countries"),
        false);
    PartnerAction action = enumOf(body, "action", PartnerAction.class, false);
    int version = integer(body, "expectedVersion", 0, 100000, false);
    Reason reason = optionalReason(body, false);
    String note = note(body, reason, false);
    boolean confirmed = confirmed(body, false);
    List<String> countries = List.of();
    JsonNode node = body.get("countries");
    if (action == PartnerAction.AMEND_SCOPE) {
      if (node == null || !node.isArray() || node.size() < 1 || node.size() > 30) {
        throw shape("countries", node == null ? "REQUIRED" : "FORMAT");
      }
      List<String> codes = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (JsonNode item : node) {
        if (!item.isString()
            || !PartnerCountries.contains(item.asString())
            || !seen.add(item.asString())) {
          throw shape("countries", "FORMAT");
        }
        codes.add(item.asString());
      }
      countries = List.copyOf(codes);
    } else if (node != null && !node.isNull()) {
      throw shape("countries", "FORMAT");
    }
    switch (action) {
      case SUSPEND -> {
        if (reason == null) {
          throw shape("reasonCode", "REQUIRED");
        }
      }
      case CLOSE -> {}
      default -> {
        if (reason != null) {
          throw shape("reasonCode", "FORMAT");
        }
      }
    }
    if (action.needsConfirmation() && !confirmed) {
      throw shape("confirmed", "REQUIRED");
    }
    return new PartnerTransition(action, version, reason, note, confirmed, countries);
  }

  static ProductCreate parseProductCreate(JsonNode body) {
    only(body, Set.of("productCode", "productType", "content"), false);
    JsonNode code = body.get("productCode");
    if (code == null || !code.isString() || !PRODUCT_CODE.matcher(code.asString()).matches()) {
      throw shape("productCode", code == null ? "REQUIRED" : "FORMAT");
    }
    ProductType type = enumOf(body, "productType", ProductType.class, true);
    return new ProductCreate(code.asString(), type, content(body.get("content")));
  }

  static DraftSave parseDraft(JsonNode body) {
    only(body, Set.of("expectedVersion", "expectedRowVersion", "content"), true);
    int version = integer(body, "expectedVersion", 1, 1000, true);
    int rowVersion = integer(body, "expectedRowVersion", 0, 100000, true);
    return new DraftSave(version, rowVersion, content(body.get("content")));
  }

  static ProductTransition parseProductTransition(JsonNode body) {
    only(
        body,
        Set.of("action", "version", "expectedRowVersion", "reasonCode", "note", "confirmed"),
        true);
    ProductAction action = enumOf(body, "action", ProductAction.class, true);
    int version = integer(body, "version", 1, 1000, true);
    int rowVersion = integer(body, "expectedRowVersion", 0, 100000, true);
    Reason reason = optionalReason(body, true);
    String note = note(body, reason, true);
    boolean confirmed = confirmed(body, true);
    if (action.needsReason() != (reason != null)) {
      throw invalid("reasonCode", reason == null ? "REQUIRED" : "FORMAT");
    }
    if (action.needsConfirmation() && !confirmed) {
      throw invalid("confirmed", "REQUIRED");
    }
    return new ProductTransition(action, version, rowVersion, reason, note, confirmed);
  }

  // ------------------------------------------------------------------ content

  static Content content(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw invalid("content", node == null ? "REQUIRED" : "FORMAT");
    }
    Set<String> keys =
        Set.of(
            "name",
            "summary",
            "eligibility",
            "disclosure",
            "infoUrl",
            "effectiveDate",
            "endDate",
            "minMonths",
            "maxMonths",
            "countries",
            "currencies");
    for (String name : keys) {
      if (!node.has(name)) {
        throw invalid("content." + name, "REQUIRED");
      }
    }
    node.propertyNames()
        .forEach(
            name -> {
              if (!keys.contains(name)) {
                throw invalid("content." + name, "FORMAT");
              }
            });
    Bilingual name = bilingual(node, "name", 2, 120);
    Bilingual summary = bilingual(node, "summary", 10, 300);
    Bilingual eligibility = bilingual(node, "eligibility", 10, 500);
    Bilingual disclosure = bilingual(node, "disclosure", 20, 2000);
    String url = null;
    JsonNode urlNode = node.get("infoUrl");
    if (urlNode != null && !urlNode.isNull()) {
      if (!urlNode.isString()) {
        throw invalid("content.infoUrl", "FORMAT");
      }
      url = urlNode.asString().strip();
      if (url.length() < 10 || url.length() > 300 || !URL.matcher(url).matches()) {
        throw invalid("content.infoUrl", "FORMAT");
      }
    }
    LocalDate effective = date(node, "effectiveDate");
    LocalDate end = date(node, "endDate");
    if (effective != null && end != null && end.isBefore(effective)) {
      throw invalid("content.endDate", "RANGE");
    }
    Integer min = months(node, "minMonths");
    Integer max = months(node, "maxMonths");
    if ((min == null) != (max == null) || (min != null && max < min)) {
      throw invalid("content.minMonths", "RANGE");
    }
    JsonNode countriesNode = node.get("countries");
    if (!countriesNode.isArray() || countriesNode.size() > 30) {
      throw invalid("content.countries", "FORMAT");
    }
    List<String> countries = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (JsonNode item : countriesNode) {
      if (!item.isString()
          || !PartnerCountries.contains(item.asString())
          || !seen.add(item.asString())) {
        throw invalid("content.countries", "FORMAT");
      }
      countries.add(item.asString());
    }
    JsonNode currencyNode = node.get("currencies");
    if (!currencyNode.isArray() || currencyNode.size() > 5) {
      throw invalid("content.currencies", "FORMAT");
    }
    List<CurrencyLimit> currencies = new ArrayList<>();
    Set<String> codes = new HashSet<>();
    for (JsonNode item : currencyNode) {
      currencies.add(currency(item, codes));
    }
    return new Content(
        name,
        summary,
        eligibility,
        disclosure,
        url,
        effective,
        end,
        min,
        max,
        List.copyOf(countries),
        List.copyOf(currencies));
  }

  private static CurrencyLimit currency(JsonNode item, Set<String> codes) {
    if (!item.isObject()) {
      throw invalid("content.currencies", "FORMAT");
    }
    item.propertyNames()
        .forEach(
            name -> {
              if (!Set.of("currency", "minAmount", "maxAmount").contains(name)) {
                throw invalid("content.currencies", "FORMAT");
              }
            });
    JsonNode code = item.get("currency");
    if (code == null
        || !code.isString()
        || !code.asString().matches("^[A-Z]{3}$")
        || !codes.add(code.asString())) {
      throw invalid("content.currencies", "FORMAT");
    }
    String min = amount(item.get("minAmount"));
    String max = amount(item.get("maxAmount"));
    if ((min == null) != (max == null)) {
      throw invalid("content.currencies", "RANGE");
    }
    if (min != null) {
      BigDecimal low = new BigDecimal(min);
      BigDecimal high = new BigDecimal(max);
      if (high.compareTo(low) < 0 || high.compareTo(LIMIT) > 0) {
        throw invalid("content.currencies", "RANGE");
      }
    }
    return new CurrencyLimit(code.asString(), min, max);
  }

  private static String amount(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString() || !AMOUNT.matcher(node.asString()).matches()) {
      throw invalid("content.currencies", "FORMAT");
    }
    return new BigDecimal(node.asString()).setScale(2).toPlainString();
  }

  private static Bilingual bilingual(JsonNode parent, String field, int min, int max) {
    JsonNode node = parent.get(field);
    String path = "content." + field;
    if (node == null || !node.isObject()) {
      throw invalid(path, "FORMAT");
    }
    node.propertyNames()
        .forEach(
            name -> {
              if (!name.equals("en") && !name.equals("fr")) {
                throw invalid(path, "FORMAT");
              }
            });
    if (!node.has("en") || !node.has("fr")) {
      throw invalid(path, "REQUIRED");
    }
    return new Bilingual(
        text(node.get("en"), path + ".en", min, max), text(node.get("fr"), path + ".fr", min, max));
  }

  private static String text(JsonNode node, String path, int min, int max) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      throw invalid(path, "FORMAT");
    }
    String value = node.asString().strip();
    if (value.isEmpty()) {
      return null;
    }
    if (value.length() < min || value.length() > max) {
      throw invalid(path, "LENGTH");
    }
    if (value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
      throw invalid(path, "FORMAT");
    }
    return value;
  }

  private static LocalDate date(JsonNode parent, String field) {
    JsonNode node = parent.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      throw invalid("content." + field, "FORMAT");
    }
    try {
      LocalDate value = LocalDate.parse(node.asString());
      if (value.getYear() < 2020 || value.getYear() > 2100) {
        throw invalid("content." + field, "RANGE");
      }
      return value;
    } catch (DateTimeParseException malformed) {
      throw invalid("content." + field, "FORMAT");
    }
  }

  private static Integer months(JsonNode parent, String field) {
    JsonNode node = parent.get(field);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isInt() || node.intValue() < 1 || node.intValue() > 600) {
      throw invalid("content." + field, "RANGE");
    }
    return node.intValue();
  }

  // ------------------------------------------------------------------ helpers

  private static void only(JsonNode body, Set<String> allowed, boolean product) {
    body.propertyNames()
        .forEach(
            name -> {
              if (!allowed.contains(name)) {
                throw product ? invalid(name, "FORMAT") : shape(name, "FORMAT");
              }
            });
  }

  private static <E extends Enum<E>> E enumOf(
      JsonNode body, String field, Class<E> type, boolean product) {
    JsonNode node = body.get(field);
    E value =
        node != null && node.isString() ? PartnerAccountDomain.parse(type, node.asString()) : null;
    if (value == null) {
      throw product
          ? invalid(field, node == null ? "REQUIRED" : "FORMAT")
          : shape(field, node == null ? "REQUIRED" : "FORMAT");
    }
    return value;
  }

  private static int integer(JsonNode body, String field, int min, int max, boolean product) {
    JsonNode node = body.get(field);
    if (node == null || !node.isInt() || node.intValue() < min || node.intValue() > max) {
      throw product
          ? invalid(field, node == null ? "REQUIRED" : "FORMAT")
          : shape(field, node == null ? "REQUIRED" : "FORMAT");
    }
    return node.intValue();
  }

  private static Reason optionalReason(JsonNode body, boolean product) {
    JsonNode node = body.get("reasonCode");
    if (node == null || node.isNull()) {
      return null;
    }
    Reason reason =
        node.isString() ? PartnerAccountDomain.parse(Reason.class, node.asString()) : null;
    if (reason == null) {
      throw product ? invalid("reasonCode", "FORMAT") : shape("reasonCode", "FORMAT");
    }
    return reason;
  }

  private static String note(JsonNode body, Reason reason, boolean product) {
    JsonNode node = body.get("note");
    boolean present = node != null && !node.isNull();
    if (reason == Reason.OTHER) {
      if (!present) {
        throw product ? invalid("note", "REQUIRED") : shape("note", "REQUIRED");
      }
    } else if (present) {
      throw product ? invalid("note", "FORMAT") : shape("note", "FORMAT");
    }
    if (!present) {
      return null;
    }
    String text = node.isString() ? node.asString().strip() : null;
    if (text == null
        || text.isEmpty()
        || text.length() > NOTE_MAX
        || text.chars()
            .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
      throw product ? invalid("note", "FORMAT") : shape("note", "FORMAT");
    }
    return text;
  }

  private static boolean confirmed(JsonNode body, boolean product) {
    JsonNode node = body.get("confirmed");
    if (node == null || node.isNull()) {
      return false;
    }
    if (!node.isBoolean()) {
      throw product ? invalid("confirmed", "FORMAT") : shape("confirmed", "FORMAT");
    }
    return node.booleanValue();
  }

  static ApiException shape(String field, String constraint) {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED,
        Map.of("fields", List.of(Map.of("field", field, "constraint", constraint))));
  }

  static ApiException invalid(String field, String constraint) {
    return new ApiException(
        ErrorCode.PARTNER_PRODUCT_INVALID,
        Map.of("fields", List.of(Map.of("field", field, "constraint", constraint))));
  }
}
