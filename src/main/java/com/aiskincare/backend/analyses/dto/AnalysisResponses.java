package com.aiskincare.backend.analyses.dto;

import com.aiskincare.backend.aiclient.AiModels.Ingredient;
import com.aiskincare.backend.aiclient.AiModels.RoutineStep;
import com.aiskincare.backend.analyses.NormalizedSurvey;
import com.aiskincare.backend.analyses.Scoring.RoiScore;
import com.aiskincare.backend.analyses.Scoring.SummaryItem;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DeviceType;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.enums.SkinType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** TRD §10 응답 모양. Swagger(/api/docs-json)가 실제 계약이고 앱이 이것으로 타입을 만든다. */
public final class AnalysisResponses {
  private AnalysisResponses() {}

  private static final String ULID_EXAMPLE = "01J9Z3K5V8Q2M4N6P8R0S2T4V6";

  public record AnalysisCreated(
      @Schema(example = ULID_EXAMPLE) String analysisId,
      @Schema(implementation = String.class, allowableValues = "queued") AnalysisStatus status,
      @Schema(example = "2", description = "내 앞 queued 건수 + 1") long queuePosition,
      @Schema(example = "110", description = "round(queuePosition × 최근 평균 추천ms / 1000)")
          long estimatedWaitSec) {}

  public record Measurement(
      @Schema(description = "데모(stub) 값이면 true") boolean isDemo,
      @Schema(example = "0.87") double confidence,
      boolean calibrationApplied,
      @Schema(example = "63") int overallScore,
      @Schema(description = "moisture → elasticity → pigmentation → pore")
          List<SummaryItem> summary,
      @Schema(description = "full_face 제외, forehead → … → chin. 비어 있을 수 있음") List<RoiScore> byRoi,
      @Schema(example = "skin_reg_v1") String modelVersion) {}

  public record CodeLabel<T>(
      T code, @Schema(description = "AI 라벨. 화면 표기는 앱이 code로 매핑") String label) {}

  /** status가 done일 때만 나머지 필드가 있다 (pending·failed면 {@code { "status": … }}만) */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Recommendation(
      RecommendationStatus status,
      CodeLabel<SkinType> skinType,
      List<CodeLabel<Concern>> concerns,
      @Schema(description = "0–5개") List<Ingredient> ingredients,
      List<RoutineStep> routine,
      @Schema(description = "문제 분석·원인 / 성분 선택·근거 / 사용법·관리 방안") List<String> reasoning,
      String answer,
      List<String> inputNotes,
      String disclaimer,
      @Schema(description = "화면 비표시(분석용)") Boolean filtered,
      @Schema(description = "화면 비표시(분석용)") Boolean usedFallback,
      @Schema(example = "rec_sllm_v2") String modelVersion) {

    public static Recommendation status(RecommendationStatus status) {
      return new Recommendation(
          status, null, null, null, null, null, null, null, null, null, null, null);
    }
  }

  public record AnalysisError(
      @Schema(allowableValues = {"E3001", "E3002", "E3003", "E4001"}) String code,
      @Schema(example = "추천을 만들지 못했어요. 다시 시도해 주세요.") String message) {}

  public record AnalysisDetail(
      @Schema(example = ULID_EXAMPLE) String analysisId,
      AnalysisStatus status,
      @Schema(example = "2026-10-15T09:12:33Z") String createdAt,
      @Schema(example = "2026-10-15T09:13:31Z", nullable = true) String completedAt,
      DeviceType deviceType,
      @Schema(nullable = true, description = "queued일 때만") Long queuePosition,
      @Schema(nullable = true, description = "queued일 때만") Long estimatedWaitSec,
      @Schema(description = "추천 상태와 무관하게 항상 포함") Measurement measurement,
      Recommendation recommendation,
      @Schema(description = "저장한 정규화 설문(클라이언트 코드). 앱이 다음 측정의 기본값으로 쓴다") NormalizedSurvey survey,
      @Schema(nullable = true, description = "failed일 때만") AnalysisError error) {}

  public record ListItem(
      @Schema(example = ULID_EXAMPLE) String analysisId,
      @Schema(example = "2026-10-15T09:12:33Z") String createdAt,
      AnalysisStatus status,
      @Schema(example = "63") int overallScore,
      boolean isDemo,
      @Schema(description = "추천 완료 시 고민 코드, 아니면 []") List<Concern> concerns) {}

  public record PageInfo(
      @Schema(example = "1") int page,
      @Schema(example = "20") int size,
      @Schema(example = "3") long totalItems,
      @Schema(example = "1", description = "ceil(totalItems / size), 0건이면 0") long totalPages) {}

  public record AnalysisList(List<ListItem> items, PageInfo page) {}

  public record TrendSummary(int moisture, int elasticity, int pigmentation, int pore) {}

  public record TrendPoint(
      @Schema(example = ULID_EXAMPLE) String analysisId,
      @Schema(example = "2026-10-15", description = "created_at의 KST 날짜") String date,
      @Schema(example = "63") int overallScore,
      @Schema(description = "value(원래 값)") TrendSummary summary) {}

  public record TrendDelta(
      int overallScore,
      int moisture,
      int elasticity,
      @Schema(description = "음수가 좋아진 것") int pigmentation,
      @Schema(description = "음수가 좋아진 것") int pore) {}

  public record AnalysisTrend(
      TrendPoint current,
      @Schema(nullable = true, description = "직전 done·비데모 분석. 없으면 null") TrendPoint previous,
      @Schema(nullable = true, description = "current − previous. previous가 없으면 null")
          TrendDelta delta) {}
}
