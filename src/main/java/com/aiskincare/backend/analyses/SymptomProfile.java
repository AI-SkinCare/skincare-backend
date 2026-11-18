package com.aiskincare.backend.analyses;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 선택형 자가 보고. 모델의 판별 결과나 진단 기록으로 사용하지 않는다. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record SymptomProfile(
    @Schema(allowableValues = {"none", "occasional", "frequent"}) String itch,
    @Schema(allowableValues = {"none", "dry", "greasy", "both"}) String flaking,
    Boolean recurringRedness,
    Boolean visiblePlugs,
    @Schema(allowableValues = {"under_one_week", "one_to_four_weeks", "over_four_weeks"})
        String onset,
    @Schema(description = "직접 선택한 얼굴·두피 부위 코드(최대 6개)") List<String> areas) {

  public SymptomProfile {
    areas = areas == null ? List.of() : List.copyOf(areas);
  }

  boolean hasAnswers() {
    return itch != null
        || flaking != null
        || recurringRedness != null
        || visiblePlugs != null
        || onset != null
        || !areas.isEmpty();
  }
}
