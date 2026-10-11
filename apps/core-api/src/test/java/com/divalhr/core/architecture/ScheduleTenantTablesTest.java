package com.divalhr.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * MVP-050A R100-1: the scheduling package reads the site's time zone (like its code, name and
 * period) only through the {@code OrganizationPlacementDirectory} port. No SQL text in the package
 * names a table of the tenant module's {@code tenant} schema; the database-level site key and time
 * zone check of V25 are the documented integrity exception (ADR 0014), not application reads.
 */
class ScheduleTenantTablesTest {

  private static final Path SCHEDULE = Path.of("src/main/java/com/divalhr/core/people/schedule");

  /** String literals and text blocks. */
  private static final Pattern LITERAL =
      Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", Pattern.DOTALL);

  private static final Pattern TENANT_TABLE = Pattern.compile("\\btenant\\.[a-z_]+\\b");

  @Test
  void noSqlInTheSchedulePackageNamesATenantTable() throws IOException {
    assertThat(SCHEDULE).isDirectory();
    List<Path> sources;
    try (Stream<Path> files = Files.walk(SCHEDULE)) {
      sources = files.filter(file -> file.toString().endsWith(".java")).sorted().toList();
    }
    assertThat(sources).isNotEmpty();
    List<String> found = new ArrayList<>();
    for (Path source : sources) {
      Matcher literal = LITERAL.matcher(Files.readString(source, StandardCharsets.UTF_8));
      while (literal.find()) {
        String text = literal.group(1) != null ? literal.group(1) : literal.group(2);
        Matcher table = TENANT_TABLE.matcher(text);
        while (table.find()) {
          found.add(source.getFileName() + ": " + table.group());
        }
      }
    }
    assertThat(found).isEmpty();
  }

  @Test
  void theCheckRecognizesATenantTableInSql() {
    Matcher literal =
        LITERAL.matcher("String sql = \"SELECT timezone FROM tenant.site WHERE id = :id\";");
    assertThat(literal.find()).isTrue();
    assertThat(TENANT_TABLE.matcher(literal.group(2)).find()).isTrue();
    Matcher block = LITERAL.matcher("\"\"\"\n  SELECT 1 FROM tenant.organization\n\"\"\"");
    assertThat(block.find()).isTrue();
    assertThat(TENANT_TABLE.matcher(block.group(1)).find()).isTrue();
    // Java code that uses the verified tenant is not SQL text and is not inspected.
    assertThat(LITERAL.matcher("schedules.lock(tenant); tenant.value();").find()).isFalse();
  }
}
