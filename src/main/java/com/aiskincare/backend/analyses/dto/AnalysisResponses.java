package com.aiskincare.backend.analyses.dto;

import com.aiskincare.backend.aiclient.AiModels.Ingredient;
import com.aiskincare.backend.aiclient.AiModels.RoutineStep;
import com.aiskincare.backend.analyses.NormalizedSurvey;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DiseaseLabel;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.enums.SkinType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/** TRD §10 응답 모양. Swagger(/api/docs-json)가 실제 계약이고 앱이 이것으로 타입을 만든다. */
public final class AnalysisResponses {
  private AnalysisResponses() {}

  private static final String ULID_EXAMPLE = "01J9Z3K5V8Q2M4N6P8R0S2T4V6";

  /** POST /infer/disease 분류 결과를 camelCase로 (개정 A.1·B.1). 참고 정보이며 진단이 아니다 */
  public record Disease(
      @Schema(example = "아토피", description = "AI 분류 라벨(한글)") String label,
      @Schema(implementation = DiseaseLabel.class) DiseaseLabel labelEn,
      @Schema(example = "0.9", description = "모델 분류 점수 0–1. 질환 확률이 아니다 — 화면엔 백분율 + '모델 분류 점수'")
          double confidence,
      @Schema(description = "점수가 AI 임계값(0.6) 미만이면 true") boolean uncertain,
      @Schema(description = "6종 라벨(한글) → 0–1, 건선·아토피·여드름·정상·주사·지루 순서")
          Map<String, Double> probabilities,
      @Schema(example = "densenet121_s2_3ea9506") String modelVersion,
      @Schema(example = "45") int inferenceMs) {}

  public record CodeLabel<T>(
      T code, @Schema(description = "AI 라벨. 화면 표기는 앱이 code로 매핑") String label) {}

  /** recommendationStatus가 generated(COMPLETED)일 때만 있다 */
  public record Recommendation(
      CodeLabel<SkinType> skinType,
      List<CodeLabel<Concern>> concerns,
      @Schema(description = "개수 가변(0개 이상). purpose는 null일 수 있다") List<Ingredient> ingredients,
      List<RoutineStep> routine,
      @Schema(description = "생성된 설명 — 검증된 근거가 아니다") List<String> reasoning,
      String answer,
      List<String> inputNotes,
      String disclaimer,
      @Schema(description = "표현 필터 개입 여부. 화면 비표시(분석용)") boolean filtered,
      @Schema(description = "true면 규칙 기반 대체 답변. 화면 비표시(분석용)") boolean usedFallback,
      @Schema(example = "rec_sllm_v2") String modelVersion,
      @Schema(example = "43000") int inferenceMs) {}

  public record AnalysisError(
      @Schema(allowableValues = {"E3001", "E3002", "E3003", "E4001"}) String code,
      @Schema(example = "추천을 만들지 못했어요. 다시 시도해 주세요.") String message) {}

  /** POST /analyses·재요청·GET /analyses/{id}가 같이 쓰는 모양 (개정 B.1 + 기존 대기·시간·설문 필드) */
  public record AnalysisView(
      @Schema(example = ULID_EXAMPLE) String analysisId,
      @Schema(description = "작업 상태 (대문자)") AnalysisStatus status,
      @Schema(description = "접수 때 저장한 분류. 상태와 무관하게 항상 포함") Disease disease,
      @Schema(
              nullable = true,
              description = "COMPLETED(generated)·WITHHELD(uncertain·no_concern)일 때만")
          RecommendationStatus recommendationStatus,
      @Schema(nullable = true, description = "COMPLETED일 때만") Recommendation recommendation,
      @Schema(description = "AI 안내 문장. COMPLETED·WITHHELD일 때 채워지고 그 밖에는 []") List<String> guidance,
      @Schema(nullable = true, description = "FAILED일 때만") AnalysisError error,
      @Schema(nullable = true, description = "QUEUED일 때만. 내 앞 QUEUED 건수 + 1") Long queuePosition,
      @Schema(nullable = true, description = "QUEUED일 때만. round(queuePosition × 최근 평균 추천ms / 1000)")
          Long estimatedWaitSec,
      @Schema(example = "2026-10-16T09:12:33Z") String createdAt,
      @Schema(example = "2026-10-16T09:13:21Z", nullable = true) String completedAt,
      @Schema(description = "저장한 정규화 설문(클라이언트 코드). 앱이 다음 분석의 기본값으로 쓴다") NormalizedSurvey survey) {}

  public record ListItem(
      @Schema(example = ULID_EXAMPLE) String analysisId,
      @Schema(example = "2026-10-16T09:12:33Z") String createdAt,
      AnalysisStatus status,
      @Schema(nullable = true) RecommendationStatus recommendationStatus,
      Disease disease,
      @Schema(description = "COMPLETED면 추천 고민 코드, 아니면 []") List<Concern> concerns) {}

  public record PageInfo(
      @Schema(example = "1") int page,
      @Schema(example = "20") int size,
      @Schema(example = "3") long totalItems,
      @Schema(example = "1", description = "ceil(totalItems / size), 0건이면 0") long totalPages) {}

  public record AnalysisList(List<ListItem> items, PageInfo page) {}
}
