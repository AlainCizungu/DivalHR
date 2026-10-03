package com.divalhr.core.people.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/**
 * The columns of the employee import (MVP-020), with their stable keys and the French and English
 * header labels the import accepts. The template is generated from this table, so the two cannot
 * drift. Header matching ignores accents, letter case, apostrophe and dash variants and repeated
 * whitespace; the key itself is also accepted.
 */
public enum ImportColumn {
  /** Tenant-unique employee number. */
  EMPLOYEE_NUMBER("employee_number", true, "Matricule", "Employee number"),
  /** Given names. */
  GIVEN_NAMES("given_names", true, "Prénoms", "Given names"),
  /** Family name. */
  FAMILY_NAME("family_name", true, "Nom de famille", "Family name"),
  /** First day of employment (ISO date). */
  START_DATE("start_date", true, "Date d’entrée", "Start date"),
  /** Legal entity code. */
  LEGAL_ENTITY_CODE("legal_entity_code", true, "Code de l’entité juridique", "Legal entity code"),
  /** Site code. */
  SITE_CODE("site_code", true, "Code du site", "Site code"),
  /** Optional department code. */
  DEPARTMENT_CODE("department_code", false, "Code du département", "Department code"),
  /** Optional cost center code. */
  COST_CENTER_CODE("cost_center_code", false, "Code du centre de coût", "Cost center code"),
  /** Optional team code. */
  TEAM_CODE("team_code", false, "Code de l’équipe", "Team code");

  private final String key;
  private final boolean required;
  private final String french;
  private final String english;

  ImportColumn(String key, boolean required, String french, String english) {
    this.key = key;
    this.required = required;
    this.french = french;
    this.english = english;
  }

  /**
   * Stable, language-neutral key (API and database).
   *
   * @return key
   */
  public String key() {
    return key;
  }

  /**
   * Whether every row must have a value.
   *
   * @return true for required columns
   */
  public boolean required() {
    return required;
  }

  /**
   * The header label in a template language.
   *
   * @param french French when true, English otherwise
   * @return label
   */
  public String label(boolean french) {
    return french ? this.french : english;
  }

  /** Language a header matched in. */
  public enum HeaderLanguage {
    /** French label. */
    FR,
    /** English label. */
    EN,
    /** The language-neutral key. */
    KEY
  }

  /**
   * A header cell that matched a column.
   *
   * @param column the column
   * @param language the language of the label it matched
   */
  public record Match(ImportColumn column, HeaderLanguage language) {}

  /**
   * Matches a header cell.
   *
   * @param header raw header cell
   * @return the column, or empty when unknown
   */
  public static Optional<Match> match(String header) {
    String wanted = normalizeLabel(header);
    for (ImportColumn column : values()) {
      if (wanted.equals(normalizeLabel(column.french))) {
        return Optional.of(new Match(column, HeaderLanguage.FR));
      }
      if (wanted.equals(normalizeLabel(column.english))) {
        return Optional.of(new Match(column, HeaderLanguage.EN));
      }
      if (wanted.equals(normalizeLabel(column.key))) {
        return Optional.of(new Match(column, HeaderLanguage.KEY));
      }
    }
    return Optional.empty();
  }

  /**
   * The comparison form of a header label: accents removed, case-folded, apostrophes, dashes and
   * underscores unified, whitespace collapsed.
   *
   * @param label raw label
   * @return comparison form
   */
  public static String normalizeLabel(String label) {
    String decomposed = Normalizer.normalize(label, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
    return decomposed
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('`', '\'')
        .replace('‐', '-')
        .replace('–', '-')
        .replace('_', ' ')
        .replace('-', ' ')
        .replace('\'', ' ')
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .strip();
  }
}
