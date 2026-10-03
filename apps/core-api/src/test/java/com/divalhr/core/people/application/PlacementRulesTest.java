package com.divalhr.core.people.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.people.domain.RowErrorCode;
import com.divalhr.core.people.domain.RowValues;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.PlacementUnit;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.Placements;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-020 (section 10): placement consistency and effective periods. */
class PlacementRulesTest {

  private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
  private final UUID le = UUID.randomUUID();
  private final UUID site = UUID.randomUUID();
  private final UUID dept = UUID.randomUUID();
  private final UUID cc = UUID.randomUUID();
  private final UUID team = UUID.randomUUID();

  private Placements units(LocalDate siteTo) {
    return new Placements(
        Map.of("LE", new PlacementUnit(le, FROM, null, null, null, null, null)),
        Map.of("ST", new PlacementUnit(site, FROM, siteTo, le, null, null, null)),
        Map.of("DP", new PlacementUnit(dept, FROM, null, null, site, null, null)),
        Map.of("CC", new PlacementUnit(cc, FROM, null, null, site, null, null)),
        Map.of("TM", new PlacementUnit(team, FROM, null, null, site, dept, null)));
  }

  private static RowValues row(String date, String dept, String cc, String team) {
    return new RowValues("E", "A", "B", LocalDate.parse(date), "LE", "ST", dept, cc, team);
  }

  @Test
  void aTeamAloneTakesItsParentAndConsistentPlacementsResolve() {
    var outcome = PlacementRules.check(row("2026-02-01", null, null, "TM"), units(null));
    assertThat(outcome.errors()).isEmpty();
    assertThat(outcome.placement().departmentId()).isEqualTo(dept);
    assertThat(outcome.placement().teamId()).isEqualTo(team);
    assertThat(
            PlacementRules.check(row("2026-02-01", null, "CC", null), units(null))
                .placement()
                .costCenterId())
        .isEqualTo(cc);
  }

  @Test
  void mismatchesUnknownCodesAndPeriodsAreReported() {
    assertThat(PlacementRules.check(row("2026-02-01", null, "CC", "TM"), units(null)).errors())
        .extracting(e -> e.code())
        .containsExactly(RowErrorCode.ROW_UNIT_MISMATCH);
    assertThat(PlacementRules.check(row("2026-02-01", "XX", null, null), units(null)).errors())
        .extracting(e -> e.code())
        .containsExactly(RowErrorCode.ROW_UNIT_NOT_FOUND);
    assertThat(PlacementRules.check(row("2025-12-31", null, null, null), units(null)).errors())
        .extracting(e -> e.code())
        .contains(RowErrorCode.ROW_UNIT_NOT_EFFECTIVE);
    assertThat(
            PlacementRules.check(
                    row("2026-06-01", null, null, null), units(LocalDate.of(2026, 5, 31)))
                .errors())
        .extracting(e -> e.code())
        .containsExactly(RowErrorCode.ROW_UNIT_NOT_EFFECTIVE);
  }
}
