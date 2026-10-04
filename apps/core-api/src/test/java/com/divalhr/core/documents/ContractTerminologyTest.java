package com.divalhr.core.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.documents.domain.AcknowledgementStatement;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A30-3: the contract's API never claims a signature. In every MVP-030 operation and schema
 * (descriptions, summaries, enum values), "sign", "signed", "Sign contract", "Contract signed", «
 * Signer », « Contrat signé » and « signé(e) » are forbidden; "signature" appears only in the
 * negative disclaimer ("not an electronic signature"). The version-1 statements say the same.
 */
class ContractTerminologyTest {

  private static final List<Pattern> FORBIDDEN =
      List.of(
          Pattern.compile("\\bsign(?:s|ed|ing)?\\b(?!\\s*(?:in|out)\\b)", Pattern.CASE_INSENSITIVE),
          Pattern.compile("sign contract", Pattern.CASE_INSENSITIVE),
          Pattern.compile("contract signed", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bsigner\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
          Pattern.compile("contrat signé", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
          Pattern.compile(
              "\\bsigné(?:e|s|es)?\\b",
              Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS));

  private static final Pattern SIGNATURE = Pattern.compile("signature", Pattern.CASE_INSENSITIVE);
  private static final Pattern DISCLAIMER =
      Pattern.compile("not an electronic signature|n’est pas une signature électronique");

  @Test
  @SuppressWarnings("unchecked")
  void theContractApiNeverClaimsASignature() throws Exception {
    Map<String, Object> spec;
    Path path = Path.of(System.getProperty("divalhr.apiSpecPath", "../../docs/API-SPEC.yaml"));
    try (InputStream in = Files.newInputStream(path)) {
      spec = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    }
    List<String> texts = new ArrayList<>();
    for (Map.Entry<String, Object> entry : ((Map<String, Object>) spec.get("paths")).entrySet()) {
      if (!entry.getKey().contains("contract")) {
        continue;
      }
      collect(entry.getValue(), texts);
    }
    Map<String, Object> schemas =
        (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
    schemas.forEach(
        (name, schema) -> {
          if (name.contains("Contract") || name.equals("AcknowledgementStatement")) {
            texts.add(name);
            collect(schema, texts);
          }
        });
    assertThat(texts).hasSizeGreaterThan(100);
    for (String text : texts) {
      for (Pattern forbidden : FORBIDDEN) {
        assertThat(forbidden.matcher(text).find()).as(text).isFalse();
      }
      if (SIGNATURE.matcher(text).find()) {
        assertThat(DISCLAIMER.matcher(text).find()).as(text).isTrue();
      }
    }
  }

  @Test
  void theStatementsOnlyDisclaimAndNeverClaimASignature() {
    for (AcknowledgementStatement statement : AcknowledgementStatement.current()) {
      for (Pattern forbidden : FORBIDDEN) {
        assertThat(forbidden.matcher(statement.text()).find()).isFalse();
      }
      assertThat(statement.text()).containsPattern(DISCLAIMER);
    }
  }

  @SuppressWarnings("unchecked")
  private static void collect(Object node, List<String> texts) {
    if (node instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = String.valueOf(entry.getKey());
        if (key.equals("description") || key.equals("summary") || key.equals("title")) {
          texts.add(String.valueOf(entry.getValue()));
        } else if (key.equals("enum") && entry.getValue() instanceof List<?> values) {
          values.forEach(value -> texts.add(String.valueOf(value)));
        } else if (key.equals("operationId")) {
          texts.add(String.valueOf(entry.getValue()));
        } else {
          collect(entry.getValue(), texts);
        }
      }
    } else if (node instanceof List<?> list) {
      list.forEach(item -> collect(item, texts));
    }
  }
}
