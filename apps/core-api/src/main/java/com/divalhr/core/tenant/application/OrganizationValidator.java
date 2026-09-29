package com.divalhr.core.tenant.application;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.tenant.api.CreateOrganizationRequest;
import com.divalhr.core.tenant.domain.SupportedConfiguration;
import com.divalhr.core.tenant.domain.SupportedConfiguration.CountryRules;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates {@code createOrganization} requests into stable error codes.
 *
 * <p>Precedence (approved in Issue #10): any format problem returns {@code VALIDATION_FAILED} with
 * every failing field; otherwise the first unsupported value in the order country, locale, time
 * zone, currency returns its {@code *_NOT_SUPPORTED} code. Submitted values never appear in the
 * error parameters.
 */
@Component
public class OrganizationValidator {

  /** Constraint vocabulary for {@code params.fields[].constraint}. */
  public enum Constraint {
    /** Missing value. */
    REQUIRED,
    /** Wrong length. */
    LENGTH,
    /** Wrong shape. */
    FORMAT,
    /** Repeated item. */
    DUPLICATE,
    /** Property not in the contract. */
    UNKNOWN_PROPERTY
  }

  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}$");
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
  private static final Set<String> IANA_ZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

  /**
   * Validates and normalizes a request.
   *
   * @param idempotencyKey header value
   * @param request request body
   * @return normalized command
   * @throws ApiException with a stable code on any failure
   */
  public CreateOrganizationCommand validate(
      String idempotencyKey, CreateOrganizationRequest request) {
    List<Map<String, String>> fields = new ArrayList<>();

    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      add(fields, IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      add(fields, IdempotencyKeys.HEADER, Constraint.FORMAT);
    }

    if (!request.unknownProperties().isEmpty()) {
      // Property names are caller-controlled, so they are not echoed either.
      add(fields, "body", Constraint.UNKNOWN_PROPERTY);
    }

    String name = request.getName() == null ? null : request.getName().strip();
    if (name == null || name.isEmpty()) {
      add(fields, "name", Constraint.REQUIRED);
    } else if (CONTROL.matcher(name).find()) {
      add(fields, "name", Constraint.FORMAT);
    } else {
      int length = name.codePointCount(0, name.length());
      if (length < 2 || length > 160) {
        add(fields, "name", Constraint.LENGTH);
      }
    }

    String country = request.getCountryCode();
    if (country == null || country.isEmpty()) {
      add(fields, "countryCode", Constraint.REQUIRED);
    } else if (!COUNTRY.matcher(country).matches()) {
      add(fields, "countryCode", Constraint.FORMAT);
    }

    String locale = request.getDefaultLocale();
    if (locale == null || locale.isEmpty()) {
      add(fields, "defaultLocale", Constraint.REQUIRED);
    } else if (!LOCALE.matcher(locale).matches()) {
      add(fields, "defaultLocale", Constraint.FORMAT);
    }

    String timezone = request.getTimezone();
    if (timezone == null || timezone.isEmpty()) {
      add(fields, "timezone", Constraint.REQUIRED);
    } else if (timezone.length() > 64 || !IANA_ZONES.contains(timezone)) {
      add(fields, "timezone", Constraint.FORMAT);
    }

    List<String> currencies = request.getCurrencies();
    if (currencies == null || currencies.isEmpty()) {
      add(fields, "currencies", Constraint.REQUIRED);
    } else if (currencies.stream().anyMatch(c -> c == null || !CURRENCY.matcher(c).matches())) {
      add(fields, "currencies", Constraint.FORMAT);
    } else if (new HashSet<>(currencies).size() != currencies.size()) {
      add(fields, "currencies", Constraint.DUPLICATE);
    }

    if (!fields.isEmpty()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.copyOf(fields)));
    }

    CountryRules rules =
        SupportedConfiguration.country(country)
            .orElseThrow(
                () ->
                    unsupported(
                        ErrorCode.COUNTRY_NOT_SUPPORTED,
                        "countryCode",
                        List.copyOf(SupportedConfiguration.COUNTRIES.keySet())));
    if (!SupportedConfiguration.LOCALES.contains(locale)) {
      throw unsupported(
          ErrorCode.LOCALE_NOT_SUPPORTED, "defaultLocale", SupportedConfiguration.LOCALES);
    }
    if (!rules.timezones().contains(timezone)) {
      throw unsupported(ErrorCode.TIMEZONE_NOT_SUPPORTED, "timezone", rules.timezones());
    }
    if (!rules.currencies().containsAll(currencies)) {
      throw unsupported(ErrorCode.CURRENCY_NOT_SUPPORTED, "currencies", rules.currencies());
    }
    return new CreateOrganizationCommand(name, country, locale, timezone, currencies);
  }

  private static void add(List<Map<String, String>> fields, String field, Constraint constraint) {
    Map<String, String> entry = new LinkedHashMap<>();
    entry.put("field", field);
    entry.put("constraint", constraint.name());
    fields.add(entry);
  }

  private static ApiException unsupported(ErrorCode code, String field, List<String> supported) {
    return new ApiException(
        code, Map.of("field", field, "supported", supported.stream().sorted().toList()));
  }
}
