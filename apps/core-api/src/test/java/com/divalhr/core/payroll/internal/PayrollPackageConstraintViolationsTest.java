package com.divalhr.core.payroll.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.platform.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** MVP-094C: each named V46 constraint maps to its stable code; anything else is a defect. */
class PayrollPackageConstraintViolationsTest {

  private static final Path MIGRATION =
      Path.of("src/main/resources/db/migration/V46__payroll_approval_payslips.sql");

  @Test
  void theMappedConstraintsAreExactlyTheDocumentedSets() {
    assertThat(PayrollPackageConstraintViolations.LIMITS)
        .containsExactlyInAnyOrder(
            "payroll_package_version_range", "payroll_package_payslip_count");
    assertThat(PayrollPackageConstraintViolations.VERSION_CONFLICT)
        .containsExactlyInAnyOrder("payroll_package_version_unique", "payroll_package_version");
    assertThat(PayrollPackageConstraintViolations.ALREADY_APPROVED)
        .containsExactlyInAnyOrder(
            "payroll_package_one_approved",
            "payroll_package_one_approved_run",
            "payroll_package_one_approved_calculation",
            "payroll_package_source_locked");
    assertThat(PayrollPackageConstraintViolations.STALE)
        .containsExactlyInAnyOrder(
            "payroll_package_run_state",
            "payroll_package_calculation_state",
            "payroll_package_totals_source");
    assertThat(PayrollPackageConstraintViolations.BLOCKED)
        .containsExactlyInAnyOrder("payroll_package_pack_state", "payroll_package_source_voided");
    assertThat(PayrollPackageConstraintViolations.VOIDED)
        .containsExactlyInAnyOrder(
            "payroll_package_void_package_unique", "payroll_package_void_state");
  }

  @Test
  void everyMappedConstraintAnswersWithItsStableCode() {
    for (String name : PayrollPackageConstraintViolations.LIMITS) {
      assertThat(PayrollPackageConstraintViolations.answer(name))
          .as(name)
          .contains(ErrorCode.PAYROLL_PACKAGE_LIMIT_REACHED);
    }
    for (String name : PayrollPackageConstraintViolations.VERSION_CONFLICT) {
      assertThat(PayrollPackageConstraintViolations.answer(name))
          .as(name)
          .contains(ErrorCode.PAYROLL_PACKAGE_VERSION_CONFLICT);
    }
    for (String name : PayrollPackageConstraintViolations.ALREADY_APPROVED) {
      assertThat(PayrollPackageConstraintViolations.answer(name))
          .as(name)
          .contains(ErrorCode.PAYROLL_PACKAGE_ALREADY_APPROVED);
    }
    for (String name : PayrollPackageConstraintViolations.STALE) {
      assertThat(PayrollPackageConstraintViolations.answer(name))
          .as(name)
          .contains(ErrorCode.PAYROLL_APPROVAL_PREVIEW_STALE);
    }
    for (String name : PayrollPackageConstraintViolations.BLOCKED) {
      assertThat(PayrollPackageConstraintViolations.answer(name))
          .as(name)
          .contains(ErrorCode.PAYROLL_PACKAGE_BLOCKED);
    }
    for (String name : PayrollPackageConstraintViolations.VOIDED) {
      assertThat(PayrollPackageConstraintViolations.answer(name))
          .as(name)
          .contains(ErrorCode.PAYROLL_PACKAGE_VOIDED);
    }
  }

  private static List<Set<String>> sets() {
    return List.of(
        PayrollPackageConstraintViolations.LIMITS,
        PayrollPackageConstraintViolations.VERSION_CONFLICT,
        PayrollPackageConstraintViolations.ALREADY_APPROVED,
        PayrollPackageConstraintViolations.STALE,
        PayrollPackageConstraintViolations.BLOCKED,
        PayrollPackageConstraintViolations.VOIDED);
  }

  @Test
  void noConstraintIsMappedTwiceAndEveryOneIsAPackageConstraint() {
    Set<String> seen = new TreeSet<>();
    for (Set<String> set : sets()) {
      for (String name : set) {
        assertThat(seen.add(name)).as("mapped twice: " + name).isTrue();
        assertThat(name).startsWith("payroll_package_");
      }
    }
    assertThat(seen).hasSize(15);
  }

  @Test
  void aConstraintThatIsNotMappedIsADefectAndStaysUnmapped() {
    for (String name :
        List.of(
            "payroll_package_exact",
            "payroll_package_approval_digest",
            "payroll_package_lifecycle_only",
            "payroll_package_immutable",
            "payroll_package_void_evidence",
            "payroll_package_sources_exact",
            "payroll_package_source_binding",
            "payroll_package_same_preparer",
            "payroll_package_approved_at",
            "payroll_payslip_arithmetic",
            "payroll_payslip_immutable",
            "payroll_payslip_package_state",
            "payroll_payslip_source_line",
            "payroll_payslip_employee_unique",
            "payroll_statutory_calculation_exact",
            "something_else",
            "",
            "PAYROLL_PACKAGE_ONE_APPROVED",
            "payroll_package_one_approved ",
            "payroll_package")) {
      assertThat(PayrollPackageConstraintViolations.answer(name)).as(name).isEmpty();
    }
  }

  @Test
  void aFailureWithoutAPostgresqlIntegrityErrorHasNoConstraint() {
    assertThat(PayrollPackageConstraintViolations.constraint(new IllegalStateException("x")))
        .isEmpty();
    assertThat(
            PayrollPackageConstraintViolations.constraint(
                new RuntimeException("outer", new IllegalStateException("inner"))))
        .isEmpty();
  }

  @Test
  void theSourceLockIsAnsweredAsAlreadyApprovedByTheRunAndTheStatutoryModulesToo() {
    String locked = "payroll_package_source_locked";
    assertThat(PayrollPackageConstraintViolations.answer(locked))
        .contains(ErrorCode.PAYROLL_PACKAGE_ALREADY_APPROVED);
    assertThat(PayrollRunConstraintViolations.answer(locked))
        .contains(ErrorCode.PAYROLL_PACKAGE_ALREADY_APPROVED);
    assertThat(StatutoryConstraintViolations.answer(locked))
        .contains(ErrorCode.PAYROLL_PACKAGE_ALREADY_APPROVED);
    // No other package constraint leaks into the older modules' answers.
    assertThat(PayrollRunConstraintViolations.answer("payroll_package_run_state")).isEmpty();
    assertThat(StatutoryConstraintViolations.answer("payroll_package_run_state")).isEmpty();
    assertThat(PayrollRunConstraintViolations.answer("payroll_package_one_approved")).isEmpty();
  }

  @Test
  void everyMappedNameExistsInTheMigrationAndEveryRaisedPackageGuardIsAccountedFor()
      throws IOException {
    String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
    for (Set<String> set : sets()) {
      for (String name : set) {
        assertThat(sql).as(name).contains(name);
      }
    }
    // Every constraint the migration raises by name is either mapped or deliberately a defect.
    Set<String> raised = new TreeSet<>();
    Matcher matcher = Pattern.compile("CONSTRAINT = '(payroll_[a-z_]+)'").matcher(sql);
    while (matcher.find()) {
      raised.add(matcher.group(1));
    }
    assertThat(raised).hasSizeGreaterThan(20);
    List<String> mapped = new ArrayList<>();
    for (String name : raised) {
      if (PayrollPackageConstraintViolations.answer(name).isPresent()) {
        mapped.add(name);
      }
    }
    assertThat(mapped)
        .containsExactlyInAnyOrder(
            "payroll_package_version",
            "payroll_package_source_locked",
            "payroll_package_run_state",
            "payroll_package_calculation_state",
            "payroll_package_totals_source",
            "payroll_package_pack_state",
            "payroll_package_source_voided",
            "payroll_package_void_state");
  }
}
