package com.aiskincare.backend.analyses;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.common.enums.AgeBand;
import com.aiskincare.backend.common.enums.Gender;
import com.aiskincare.backend.common.validation.FieldIssue;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** TRD §10.1 */
class SurveyParserTest {
  private static final String OK = "\"gender\":\"female\",\"ageBand\":\"20s\"";

  @Test
  @DisplayName("정규화: 모든 키, 미입력 null, 배열 []")
  void normalize() {
    var result = SurveyParser.parse("{" + OK + "}");
    assertThat(result.ok()).isTrue();
    assertThat(result.survey())
        .isEqualTo(
            new NormalizedSurvey(
                Gender.FEMALE,
                AgeBand.TWENTIES,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null));
  }

  @Test
  @DisplayName("TC-ANA-02: gender 누락 → survey.gender required")
  void genderMissing() {
    assertThat(SurveyParser.parse("{\"ageBand\":\"20s\"}").errors())
        .containsExactly(new FieldIssue("survey.gender", "required"));
  }

  @Test
  @DisplayName("JSON 파싱 실패·배열·누락")
  void invalidFormat() {
    assertThat(SurveyParser.parse("{bad").errors())
        .containsExactly(new FieldIssue("survey", "invalid_format"));
    assertThat(SurveyParser.parse("[]").errors())
        .containsExactly(new FieldIssue("survey", "invalid_format"));
    assertThat(SurveyParser.parse(null).errors())
        .containsExactly(new FieldIssue("survey", "required"));
  }

  @ParameterizedTest(name = "{0} → {1} {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "\"concerns\":[\"pore\",\"acne\",\"wrinkle\",\"redness\"] | survey.concerns | too_many",
        "\"concerns\":[\"pore\",\"pore\"] | survey.concerns | duplicate",
        "\"concerns\":[\"여드름\"] | survey.concerns | invalid_value",
        "\"sleepHours\":15 | survey.sleepHours | out_of_range",
        "\"sleepHours\":6.5 | survey.sleepHours | invalid_type",
        "\"skinType\":\"복합성\" | survey.skinType | invalid_value",
        "\"currentProducts\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"] | survey.currentProducts | too_many",
        "\"gender\":\"x\" | survey.gender | invalid_value"
      })
  void fieldErrors(String extra, String field, String reason) {
    assertThat(SurveyParser.parse("{" + OK + "," + extra + "}").errors())
        .contains(new FieldIssue(field, reason));
  }

  @Test
  void noteTooLong() {
    String note = "a".repeat(301);
    assertThat(SurveyParser.parse("{" + OK + ",\"note\":\"" + note + "\"}").errors())
        .contains(new FieldIssue("survey.note", "too_long"));
  }

  @Test
  @DisplayName("skinType null은 미입력, 모르는 키는 버린다")
  void nullAndUnknownKeys() {
    var result = SurveyParser.parse("{" + OK + ",\"skinType\":null,\"extra\":\"x\"}");
    assertThat(result.ok()).isTrue();
    assertThat(result.survey().skinType()).isNull();
  }
}
