// TRD §12.4 — 클라이언트 설문 → AI 설문, AI 라벨 → 코드.
//
// | 클라이언트                    | AI 서버                                 |
// | skinType: "combination"     | skinType: "복합성"                        |
// | concerns: ["pore"]          | selfReportedConcerns: ["모공"] (키 이름도 바뀜) |
// | (저장된 측정값)               | metrics (0–1, 모든 ROI)                   |
// 코드를 그대로 보내면 AI가 인식하지 못한다. 미입력 선택 필드는 키를 생략한다.

import {
  ConcernCode,
  ROIS,
  concernFromAiLabel,
  concernLabel,
  skinTypeFromAi,
  skinTypeLabel,
} from '../common/enums';
import type { NormalizedSurvey } from '../analyses/survey';
import type { SkinMetric } from '../analyses/entities/skin-metric.entity';
import type {
  AiSurvey,
  MetricsByRoi,
  RecommendRequest,
  RecommendResult,
} from './ai-client.interface';
import type { ValidatedRecommendResponse } from './ai-response.schemas';

export function toAiSurvey(survey: NormalizedSurvey): AiSurvey {
  const result: AiSurvey = { gender: survey.gender, ageBand: survey.ageBand };
  if (survey.skinType !== null) result.skinType = skinTypeLabel(survey.skinType);
  if (survey.sleepHours !== null) result.sleepHours = survey.sleepHours;
  if (survey.waterIntakeLevel !== null) result.waterIntakeLevel = survey.waterIntakeLevel;
  if (survey.sunExposureLevel !== null) result.sunExposureLevel = survey.sunExposureLevel;
  if (survey.stressLevel !== null) result.stressLevel = survey.stressLevel;
  if (survey.currentProducts.length > 0) result.currentProducts = [...survey.currentProducts];
  if (survey.concerns.length > 0) {
    result.selfReportedConcerns = survey.concerns.map(concernLabel);
  }
  if (survey.note !== null && survey.note.length > 0) result.note = survey.note;
  return result;
}

/** skin_metrics 행 → AI에 다시 넘길 0–1 지표 (모든 ROI 그대로) */
export function metricsFromRows(rows: SkinMetric[]): MetricsByRoi {
  const metrics: MetricsByRoi = {};
  const ordered = [...rows].sort((a, b) => ROIS.indexOf(a.roi) - ROIS.indexOf(b.roi));
  for (const row of ordered) {
    metrics[row.roi] = {
      moisture: row.moisture,
      elasticity: row.elasticity,
      pigmentation: row.pigmentation,
      pore: row.pore,
    };
  }
  return metrics;
}

/** is_demo=true면 metrics는 null — 가짜 값이 피부 타입·고민 추정에 섞이지 않게 (FR-MEA-04) */
export function buildRecommendRequest(
  metrics: MetricsByRoi | null,
  survey: NormalizedSurvey,
): RecommendRequest {
  return { metrics, survey: toAiSurvey(survey) };
}

/** 검증된 AI 응답의 라벨을 코드로 바꾼다. 중복 고민은 하나로. */
export function toRecommendResult(validated: ValidatedRecommendResponse): RecommendResult {
  const skinType = skinTypeFromAi(validated.skinType);
  if (skinType === null) throw new Error('검증을 통과한 skin_type이 표에 없습니다');
  const concerns: ConcernCode[] = [];
  for (const label of validated.concerns) {
    const code = concernFromAiLabel(label);
    if (code === null) throw new Error('검증을 통과한 concern이 표에 없습니다');
    if (!concerns.includes(code)) concerns.push(code);
  }
  return {
    skinType,
    concerns,
    ingredients: validated.ingredients,
    routine: validated.routine,
    reasoning: validated.reasoning,
    answer: validated.answer,
    disclaimer: validated.disclaimer,
    filtered: validated.filtered,
    usedFallback: validated.usedFallback,
    inputNotes: validated.inputNotes,
    modelVersion: validated.modelVersion,
    inferenceMs: validated.inferenceMs,
    warnings: validated.warnings,
  };
}
