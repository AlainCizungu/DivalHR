package com.divalhr.core.people.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.SearchKeyGolden;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-021 (H16): the search-key normalizer against the shared golden vectors. */
class EmployeeSearchKeyTest {

  @Test
  void storedKeysMatchTheGoldenVectors() {
    List<SearchKeyGolden.Vector> vectors = SearchKeyGolden.vectors();
    assertThat(vectors).hasSizeGreaterThanOrEqualTo(10);
    for (SearchKeyGolden.Vector vector : vectors) {
      assertThat(EmployeeSearchKey.of(vector.givenNames(), vector.familyName()))
          .as(vector.key())
          .isEqualTo(vector.key());
    }
  }

  @Test
  void queriesSplitIntoTheSameWords() {
    assertThat(EmployeeSearchKey.words("  ÉLO  n'kan-MBU. ")).containsExactly("elo", "nkan", "mbu");
    assertThat(EmployeeSearchKey.words("'’.-")).isEmpty();
    assertThat(EmployeeSearchKey.words("Ἀθη")).containsExactly("αθη");
  }
}
