package com.aiskincare.backend.analyses;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.aiclient.AiModels.Ingredient;
import com.aiskincare.backend.aiclient.AiModels.MetricRatios;
import com.aiskincare.backend.aiclient.AiModels.RoutineStep;
import com.aiskincare.backend.analyses.AnalysisPresenter.QueueInfo;
import com.aiskincare.backend.analyses.AnalysisRows.AnalysisRow;
import com.aiskincare.backend.analyses.AnalysisRows.RecommendationRow;
import com.aiskincare.backend.analyses.AnalysisRows.SkinMetricRow;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisError;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.CodeLabel;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.Recommendation;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.TrendDelta;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.TrendPoint;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.TrendSummary;
import com.aiskincare.backend.common.enums.AgeBand;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DeviceType;
import com.aiskincare.backend.common.enums.Gender;
import com.aiskincare.backend.common.enums.Grade;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.enums.Roi;
import com.aiskincare.backend.common.enums.RoutineTime;
import com.aiskincare.backend.common.enums.SkinType;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** TRD §10.2·10.5 */
class AnalysisPresenterTest {
  private static final Instant CREATED = Instant.parse("2026-10-15T09:12:33.456Z");

  private static AnalysisRow analysis(
      AnalysisStatus status, Instant completedAt, String errorCode) {
    return new AnalysisRow(
        "01J9Z3K5V8Q2M4N6P8R0S2T4V6",
        1,
        status,
        DeviceType.SMARTPHONE,
        false,
        0.87,
        true,
        63,
        "skin_reg_v1",
        800,
        CREATED,
        completedAt,
        1,
        errorCode,
        CREATED);
  }

  private static final List<SkinMetricRow> METRICS =
      List.of(
          new SkinMetricRow(Roi.CHEEK_LEFT, new MetricRatios(0.66, 0.76, 0.41, 0.38)),
          new SkinMetricRow(Roi.FULL_FACE, new MetricRatios(0.61, 0.74, 0.38, 0.45)),
          new SkinMetricRow(Roi.FOREHEAD, new MetricRatios(0.58, 0.71, 0.35, 0.52)));

  private static final NormalizedSurvey SURVEY =
      new NormalizedSurvey(
          Gender.FEMALE,
          AgeBand.TWENTIES,
          SkinType.COMBINATION,
          null,
          null,
          null,
          null,
          List.of(Concern.PORE),
          List.of(),
          null);

  private static final RecommendationRow RECOMMENDATION =
      new RecommendationRow(
          SkinType.COMBINATION,
          List.of(Concern.PORE, Concern.PIGMENTATION),
          List.of(new Ingredient("나이아신아마이드", null, 1)),
          List.of(new RoutineStep(1, RoutineTime.MORNING, "세안합니다.")),
          List.of("a", "b", "c"),
          "요약",
          "면책",
          List.of(),
          false,
          false,
          "rec_sllm_v2");

  @Test
  @DisplayName("done: 측정·추천 전체, 시간은 밀리초 없는 UTC, byRoi는 full_face 제외·순서 고정")
  void done() {
    var dto =
        AnalysisPresenter.detail(
            analysis(AnalysisStatus.DONE, Instant.parse("2026-10-15T09:13:31.999Z"), null),
            METRICS,
            SURVEY,
            RECOMMENDATION,
            QueueInfo.NONE);
    assertThat(dto.createdAt()).isEqualTo("2026-10-15T09:12:33Z");
    assertThat(dto.completedAt()).isEqualTo("2026-10-15T09:13:31Z");
    assertThat(dto.measurement().summary())
        .extracting(s -> List.of(s.value(), s.score(), s.grade()))
        .containsExactly(
            List.of(61, 61, Grade.NORMAL),
            List.of(74, 74, Grade.GOOD),
            List.of(38, 62, Grade.NORMAL),
            List.of(45, 55, Grade.NORMAL));
    assertThat(dto.measurement().byRoi())
        .extracting(r -> List.of(r.roi(), r.overallScore()))
        .containsExactly(List.of(Roi.FOREHEAD, 61), List.of(Roi.CHEEK_LEFT, 66));
    assertThat(dto.recommendation().status()).isEqualTo(RecommendationStatus.DONE);
    assertThat(dto.recommendation().skinType())
        .isEqualTo(new CodeLabel<>(SkinType.COMBINATION, "복합성"));
    assertThat(dto.recommendation().concerns())
        .containsExactly(
            new CodeLabel<>(Concern.PORE, "모공"),
            new CodeLabel<>(Concern.PIGMENTATION, "미백(색소침착/기미/칙칙함)"));
    assertThat(dto.error()).isNull();
  }

  @Test
  @DisplayName("JSON: 설문은 명세 순서, pending 추천은 status만, 열거형은 소문자 코드")
  void json() {
    var dto =
        AnalysisPresenter.detail(
            analysis(AnalysisStatus.QUEUED, null, null),
            METRICS,
            SURVEY,
            null,
            new QueueInfo(2L, 110L));
    var node = JsonMapper.builder().build().valueToTree(dto);
    Iterator<String> names = node.get("survey").propertyNames().iterator();
    List<String> keys = new java.util.ArrayList<>();
    names.forEachRemaining(keys::add);
    assertThat(keys)
        .containsExactly(
            "gender",
            "ageBand",
            "skinType",
            "sleepHours",
            "waterIntakeLevel",
            "sunExposureLevel",
            "stressLevel",
            "concerns",
            "currentProducts",
            "note");
    assertThat(node.get("recommendation").toString()).isEqualTo("{\"status\":\"pending\"}");
    assertThat(node.get("status").asString()).isEqualTo("queued");
    assertThat(node.get("deviceType").asString()).isEqualTo("smartphone");
    assertThat(node.get("measurement").get("summary").get(0).get("metric").asString())
        .isEqualTo("moisture");
    assertThat(node.get("completedAt").isNull()).isTrue();
    assertThat(node.get("error").isNull()).isTrue();
  }

  @Test
  @DisplayName("queued: recommendation pending + 순번·예상 대기")
  void queued() {
    var dto =
        AnalysisPresenter.detail(
            analysis(AnalysisStatus.QUEUED, null, null),
            METRICS,
            SURVEY,
            null,
            new QueueInfo(2L, 110L));
    assertThat(dto.recommendation()).isEqualTo(Recommendation.status(RecommendationStatus.PENDING));
    assertThat(dto.queuePosition()).isEqualTo(2L);
    assertThat(dto.estimatedWaitSec()).isEqualTo(110L);
  }

  @Test
  @DisplayName("recommending: pending, 순번 null")
  void recommending() {
    var dto =
        AnalysisPresenter.detail(
            analysis(AnalysisStatus.RECOMMENDING, null, null),
            METRICS,
            SURVEY,
            null,
            new QueueInfo(1L, 55L));
    assertThat(dto.recommendation()).isEqualTo(Recommendation.status(RecommendationStatus.PENDING));
    assertThat(dto.queuePosition()).isNull();
    assertThat(dto.estimatedWaitSec()).isNull();
  }

  @Test
  @DisplayName("failed: recommendation failed + error code·message, 측정은 그대로")
  void failed() {
    var dto =
        AnalysisPresenter.detail(
            analysis(AnalysisStatus.FAILED, CREATED, "E3002"),
            METRICS,
            SURVEY,
            null,
            QueueInfo.NONE);
    assertThat(dto.recommendation()).isEqualTo(Recommendation.status(RecommendationStatus.FAILED));
    assertThat(dto.error()).isEqualTo(new AnalysisError("E3002", "추천을 만들지 못했어요. 다시 시도해 주세요."));
    assertThat(dto.measurement().overallScore()).isEqualTo(63);
  }

  @Test
  @DisplayName("failed인데 저장된 코드가 실패 코드가 아니면 E4001")
  void failedUnknownCode() {
    var dto =
        AnalysisPresenter.detail(
            analysis(AnalysisStatus.FAILED, CREATED, "E2001"),
            METRICS,
            SURVEY,
            null,
            QueueInfo.NONE);
    assertThat(dto.error().code()).isEqualTo("E4001");
  }

  @Test
  @DisplayName("trend: date는 KST 날짜, delta = current − previous")
  void trend() {
    var row =
        new AnalysisRow(
            "01J9Z3K5V8Q2M4N6P8R0S2T4V6",
            1,
            AnalysisStatus.DONE,
            DeviceType.SMARTPHONE,
            false,
            0.87,
            true,
            63,
            "skin_reg_v1",
            800,
            CREATED,
            CREATED,
            1,
            null,
            Instant.parse("2026-10-15T16:00:00Z"));
    TrendPoint current = AnalysisPresenter.trendPoint(row, METRICS);
    assertThat(current.date()).isEqualTo("2026-10-16"); // UTC 16시 = KST 다음 날 01시
    TrendPoint previous = new TrendPoint("x", "2026-10-01", 60, new TrendSummary(54, 72, 40, 47));
    assertThat(AnalysisPresenter.delta(current, previous))
        .isEqualTo(new TrendDelta(3, 7, 2, -2, -2));
  }
}
