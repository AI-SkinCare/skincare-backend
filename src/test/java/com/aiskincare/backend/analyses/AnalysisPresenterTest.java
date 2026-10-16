package com.aiskincare.backend.analyses;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.aiclient.AiModels.Ingredient;
import com.aiskincare.backend.aiclient.AiModels.RoutineStep;
import com.aiskincare.backend.analyses.AnalysisPresenter.QueueInfo;
import com.aiskincare.backend.analyses.AnalysisRepository.ListRow;
import com.aiskincare.backend.analyses.AnalysisRows.AnalysisRow;
import com.aiskincare.backend.analyses.AnalysisRows.RecommendationRow;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisError;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.CodeLabel;
import com.aiskincare.backend.common.enums.AgeBand;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DiseaseLabel;
import com.aiskincare.backend.common.enums.Gender;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.enums.RoutineTime;
import com.aiskincare.backend.common.enums.SkinType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** TRD §10.2·10.4, §11.1 상태 표 */
class AnalysisPresenterTest {
  private static final Instant CREATED = Instant.parse("2026-10-15T09:12:33.456Z");
  private static final List<String> GUIDANCE = List.of("이미지 분류 참고 결과이며 의학적 진단이 아닙니다.");

  private static DiseaseResult disease() {
    Map<String, Double> probabilities = new LinkedHashMap<>();
    for (DiseaseLabel label : DiseaseLabel.values()) {
      probabilities.put(label.label(), label == DiseaseLabel.ATOPIC ? 0.9 : 0.02);
    }
    return new DiseaseResult(
        DiseaseLabel.ATOPIC, 0.9, false, probabilities, "densenet121_s2_3ea9506", 45);
  }

  /** 저장된 값은 상태와 관계없이 채워 둔다 — presenter가 상태 표대로 걸러야 한다 */
  private static AnalysisRow analysis(
      AnalysisStatus status, RecommendationStatus recStatus, Instant completedAt, String error) {
    return new AnalysisRow(
        "01J9Z3K5V8Q2M4N6P8R0S2T4V6",
        1,
        status,
        disease(),
        800,
        recStatus,
        GUIDANCE,
        CREATED,
        completedAt,
        1,
        error,
        CREATED);
  }

  private static final NormalizedSurvey SURVEY =
      new NormalizedSurvey(
          Gender.FEMALE,
          AgeBand.TWENTIES,
          SkinType.DRY,
          null,
          null,
          null,
          null,
          List.of(),
          List.of(),
          null);

  private static final RecommendationRow RECOMMENDATION =
      new RecommendationRow(
          SkinType.DRY,
          List.of(Concern.SENSITIVITY),
          List.of(new Ingredient("판테놀", null, 1)),
          List.of(new RoutineStep(1, RoutineTime.ANYTIME, "보습합니다.")),
          List.of("a", "b", "c"),
          "요약",
          "면책",
          List.of(),
          true,
          false,
          "rec_sllm_v2",
          43000);

  @Test
  @DisplayName("COMPLETED: 분류 + generated + 추천 전체 + guidance, 시간은 밀리초 없는 UTC")
  void completed() {
    var dto =
        AnalysisPresenter.view(
            analysis(
                AnalysisStatus.COMPLETED,
                RecommendationStatus.GENERATED,
                Instant.parse("2026-10-15T09:13:31.999Z"),
                null),
            SURVEY,
            RECOMMENDATION,
            QueueInfo.NONE);
    assertThat(dto.createdAt()).isEqualTo("2026-10-15T09:12:33Z");
    assertThat(dto.completedAt()).isEqualTo("2026-10-15T09:13:31Z");
    assertThat(dto.disease().label()).isEqualTo("아토피");
    assertThat(dto.disease().labelEn()).isEqualTo(DiseaseLabel.ATOPIC);
    assertThat(dto.recommendationStatus()).isEqualTo(RecommendationStatus.GENERATED);
    assertThat(dto.recommendation().skinType()).isEqualTo(new CodeLabel<>(SkinType.DRY, "건성"));
    assertThat(dto.recommendation().concerns())
        .containsExactly(new CodeLabel<>(Concern.SENSITIVITY, "민감성(트러블/자극감)"));
    assertThat(dto.recommendation().ingredients().get(0).purpose()).isNull();
    assertThat(dto.recommendation().inferenceMs()).isEqualTo(43000);
    assertThat(dto.guidance()).isEqualTo(GUIDANCE);
    assertThat(dto.error()).isNull();
    assertThat(dto.queuePosition()).isNull();
  }

  @Test
  @DisplayName("WITHHELD: 보류 사유 + guidance, 추천 null, 오류 아님")
  void withheld() {
    var dto =
        AnalysisPresenter.view(
            analysis(AnalysisStatus.WITHHELD, RecommendationStatus.UNCERTAIN, CREATED, null),
            SURVEY,
            null,
            QueueInfo.NONE);
    assertThat(dto.recommendationStatus()).isEqualTo(RecommendationStatus.UNCERTAIN);
    assertThat(dto.recommendation()).isNull();
    assertThat(dto.guidance()).isEqualTo(GUIDANCE);
    assertThat(dto.error()).isNull();
  }

  @Test
  @DisplayName("QUEUED: 분류만 + 순번·예상 대기, 추천 상태·guidance는 비움")
  void queued() {
    var dto =
        AnalysisPresenter.view(
            analysis(AnalysisStatus.QUEUED, RecommendationStatus.GENERATED, null, null),
            SURVEY,
            RECOMMENDATION,
            new QueueInfo(2L, 110L));
    assertThat(dto.disease()).isNotNull();
    assertThat(dto.recommendationStatus()).isNull();
    assertThat(dto.recommendation()).isNull();
    assertThat(dto.guidance()).isEmpty();
    assertThat(dto.queuePosition()).isEqualTo(2L);
    assertThat(dto.estimatedWaitSec()).isEqualTo(110L);
  }

  @Test
  @DisplayName("GENERATING: 순번 null")
  void generating() {
    var dto =
        AnalysisPresenter.view(
            analysis(AnalysisStatus.GENERATING, null, null, null),
            SURVEY,
            null,
            new QueueInfo(1L, 55L));
    assertThat(dto.recommendation()).isNull();
    assertThat(dto.queuePosition()).isNull();
    assertThat(dto.estimatedWaitSec()).isNull();
  }

  @Test
  @DisplayName("FAILED: error code·message, 분류는 그대로, 추천 상태 없음")
  void failed() {
    var dto =
        AnalysisPresenter.view(
            analysis(AnalysisStatus.FAILED, null, CREATED, "E3002"), SURVEY, null, QueueInfo.NONE);
    assertThat(dto.error()).isEqualTo(new AnalysisError("E3002", "추천을 만들지 못했어요. 다시 시도해 주세요."));
    assertThat(dto.recommendationStatus()).isNull();
    assertThat(dto.guidance()).isEmpty();
    assertThat(dto.disease().confidence()).isEqualTo(0.9);
  }

  @Test
  @DisplayName("FAILED인데 저장된 코드가 실패 코드가 아니면 E4001")
  void failedUnknownCode() {
    var dto =
        AnalysisPresenter.view(
            analysis(AnalysisStatus.FAILED, null, CREATED, "E2001"), SURVEY, null, QueueInfo.NONE);
    assertThat(dto.error().code()).isEqualTo("E4001");
  }

  @Test
  @DisplayName("JSON: 개정 B.1 필드가 먼저, status 대문자·recommendationStatus 소문자, labelEn 코드, 확률 6종 순서")
  void json() {
    var dto =
        AnalysisPresenter.view(
            analysis(AnalysisStatus.WITHHELD, RecommendationStatus.NO_CONCERN, CREATED, null),
            SURVEY,
            null,
            QueueInfo.NONE);
    var node = JsonMapper.builder().build().valueToTree(dto);
    List<String> keys = new ArrayList<>();
    node.propertyNames().forEach(keys::add);
    assertThat(keys)
        .containsExactly(
            "analysisId",
            "status",
            "disease",
            "recommendationStatus",
            "recommendation",
            "guidance",
            "error",
            "queuePosition",
            "estimatedWaitSec",
            "createdAt",
            "completedAt",
            "survey");
    assertThat(node.get("status").asString()).isEqualTo("WITHHELD");
    assertThat(node.get("recommendationStatus").asString()).isEqualTo("no_concern");
    assertThat(node.get("recommendation").isNull()).isTrue();
    assertThat(node.get("disease").get("labelEn").asString()).isEqualTo("atopic");
    List<String> labels = new ArrayList<>();
    node.get("disease").get("probabilities").propertyNames().forEach(labels::add);
    assertThat(labels).containsExactly("건선", "아토피", "여드름", "정상", "주사", "지루");
  }

  @Test
  @DisplayName("이력 항목: 분류 + 추천 상태 + 고민 코드")
  void listItem() {
    var item =
        AnalysisPresenter.listItem(
            new ListRow(
                analysis(AnalysisStatus.COMPLETED, RecommendationStatus.GENERATED, CREATED, null),
                List.of(Concern.SENSITIVITY)));
    assertThat(item.status()).isEqualTo(AnalysisStatus.COMPLETED);
    assertThat(item.recommendationStatus()).isEqualTo(RecommendationStatus.GENERATED);
    assertThat(item.disease().labelEn()).isEqualTo(DiseaseLabel.ATOPIC);
    assertThat(item.concerns()).containsExactly(Concern.SENSITIVITY);
    assertThat(item.createdAt()).isEqualTo("2026-10-15T09:12:33Z");
  }
}
