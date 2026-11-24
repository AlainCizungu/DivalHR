package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.referral.PartnerReferralDomain.Field;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Strict parsing of the referral request bodies. A wrong type, a missing value or an unknown
 * property is a {@code VALIDATION_FAILED} naming only our field and a fixed constraint; a submitted
 * value is never echoed. The phone number is the only free value the employee types.
 */
final class ReferralCommands {

  private static final Set<String> INPUT_KEYS =
      Set.of("productId", "language", "includePhone", "phone", "includeOrganizationName");

  private ReferralCommands() {}

  /**
   * The disclosure inputs the employee chose.
   *
   * @param productId opaque product reference as sent
   * @param language {@code en} or {@code fr}
   * @param includePhone whether the phone is disclosed
   * @param phone the typed phone, or {@code null}
   * @param includeOrganizationName whether the organization legal name is disclosed
   */
  record Inputs(
      String productId,
      String language,
      boolean includePhone,
      String phone,
      boolean includeOrganizationName) {}

  /**
   * Parses the preview body.
   *
   * @param body the request object
   * @return the inputs
   */
  static Inputs preview(JsonNode body) {
    List<Map<String, String>> problems = new ArrayList<>();
    Inputs inputs = inputs(body, INPUT_KEYS, problems);
    throwIfAny(problems);
    return semantic(inputs);
  }

  /**
   * Parses the shared input fields of a body that may carry more keys.
   *
   * @param body the request object
   * @param allowed every key the body may carry
   * @param problems collects the field problems
   * @return the inputs (meaningful only when no problem was collected)
   */
  static Inputs inputs(JsonNode body, Set<String> allowed, List<Map<String, String>> problems) {
    Iterator<String> names = body.propertyNames().iterator();
    while (names.hasNext()) {
      if (!allowed.contains(names.next())) {
        problems.add(problem("body", "UNKNOWN_PROPERTY"));
        break;
      }
    }
    String productId = string(body, "productId", problems);
    String language = string(body, "language", problems);
    if (language != null && PartnerReferralDomain.language(language).isEmpty()) {
      problems.add(problem("language", "FORMAT"));
    }
    Boolean includePhone = bool(body, "includePhone", problems);
    Boolean includeOrg = bool(body, "includeOrganizationName", problems);
    String phone = null;
    JsonNode phoneNode = body.get("phone");
    if (phoneNode == null) {
      problems.add(problem("phone", "REQUIRED"));
    } else if (!phoneNode.isNull()) {
      if (!phoneNode.isString()) {
        problems.add(problem("phone", "FORMAT"));
      } else {
        phone = phoneNode.asString();
      }
    }
    return new Inputs(
        productId,
        language,
        Boolean.TRUE.equals(includePhone),
        phone,
        Boolean.TRUE.equals(includeOrg));
  }

  /** The cross-field rules: a phone is typed exactly when it is included, and well formed. */
  static Inputs semantic(Inputs inputs) {
    if (inputs.includePhone() != (inputs.phone() != null)
        || (inputs.phone() != null
            && !PartnerReferralDomain.PHONE.matcher(inputs.phone()).matches())) {
      throw new ApiException(ErrorCode.PARTNER_REFERRAL_DISCLOSURE_INVALID, Map.of());
    }
    return inputs;
  }

  static String string(JsonNode body, String field, List<Map<String, String>> problems) {
    JsonNode node = body.get(field);
    if (node == null || node.isNull()) {
      problems.add(problem(field, "REQUIRED"));
      return null;
    }
    if (!node.isString()) {
      problems.add(problem(field, "FORMAT"));
      return null;
    }
    String value = node.asString();
    if (value.length() > 64) {
      problems.add(problem(field, "LENGTH"));
      return null;
    }
    return value;
  }

  static Boolean bool(JsonNode body, String field, List<Map<String, String>> problems) {
    JsonNode node = body.get(field);
    if (node == null || node.isNull()) {
      problems.add(problem(field, "REQUIRED"));
      return null;
    }
    if (!node.isBoolean()) {
      problems.add(problem(field, "FORMAT"));
      return null;
    }
    return node.asBoolean();
  }

  static ApiException fail(String field, String constraint) {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED, Map.of("fields", List.of(problem(field, constraint))));
  }

  static Map<String, String> problem(String field, String constraint) {
    return Map.of("field", field, "constraint", constraint);
  }

  static void throwIfAny(List<Map<String, String>> problems) {
    if (!problems.isEmpty()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, Map.of("fields", List.copyOf(problems)));
    }
  }

  /** Whether a field is in the allow-list (always true for the closed enum; kept for tests). */
  static boolean allowListed(String name) {
    for (Field field : Field.values()) {
      if (field.name().equals(name)) {
        return true;
      }
    }
    return false;
  }
}
