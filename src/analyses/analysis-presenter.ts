// DB 행 → API 응답 (TRD §10.2·10.4·10.5). 0–1 원본은 여기서 0–100으로 바꾼다.
import { Roi, concernLabel, skinTypeLabel } from '../common/enums';
import {
  ANALYSIS_FAILURE_CODES,
  AnalysisFailureCode,
  ERROR_DEFINITIONS,
  ErrorCode,
} from '../common/errors/error-codes';
import { kstDate, toIsoUtc, toIsoUtcOrNull } from '../common/utils/time';
import type {
  AnalysisDetailDto,
  AnalysisErrorDto,
  RecommendationDto,
  TrendDeltaDto,
  TrendPointDto,
} from './dto/analysis-response.dto';
import type { Analysis } from './entities/analysis.entity';
import type { Recommendation } from './entities/recommendation.entity';
import type { SkinMetric } from './entities/skin-metric.entity';
import type { Survey } from './entities/survey.entity';
import { MetricBp, ScoreReport, buildScoreReport, metricBp } from './scoring';
import { normalizeSurvey } from './survey';

export interface QueueInfo {
  queuePosition: number | null;
  estimatedWaitSec: number | null;
}

export const NO_QUEUE_INFO: QueueInfo = { queuePosition: null, estimatedWaitSec: null };

/** DB 값(4자리)도 bp로 복원해서 측정 시점과 같은 함수로 계산한다 */
export function scoreReportFromRows(rows: SkinMetric[]): ScoreReport {
  const byRoi: Partial<Record<Roi, MetricBp>> = {};
  for (const row of rows) byRoi[row.roi] = metricBp(row);
  return buildScoreReport(byRoi);
}

export function presentRecommendation(
  analysis: Analysis,
  recommendation: Recommendation | null,
): RecommendationDto {
  switch (analysis.status) {
    case 'queued':
    case 'recommending':
      return { status: 'pending' };
    case 'failed':
      return { status: 'failed' };
    case 'done':
      break;
  }
  if (!recommendation) throw new Error('done 분석에 추천 행이 없습니다');
  return {
    status: 'done',
    skinType: { code: recommendation.skinType, label: skinTypeLabel(recommendation.skinType) },
    concerns: recommendation.concerns.map((code) => ({ code, label: concernLabel(code) })),
    ingredients: recommendation.ingredients,
    routine: recommendation.routine,
    reasoning: recommendation.reasoning,
    answer: recommendation.answer,
    inputNotes: recommendation.inputNotes ?? [],
    disclaimer: recommendation.disclaimer,
    filtered: recommendation.filtered,
    usedFallback: recommendation.usedFallback,
    modelVersion: recommendation.modelVersion,
  };
}

function presentError(analysis: Analysis): AnalysisErrorDto | null {
  if (analysis.status !== 'failed') return null;
  const stored = analysis.errorCode;
  const code: AnalysisFailureCode = (ANALYSIS_FAILURE_CODES as readonly (string | null)[]).includes(
    stored,
  )
    ? (stored as AnalysisFailureCode)
    : ErrorCode.E4001;
  return { code, message: ERROR_DEFINITIONS[code].message };
}

export function presentAnalysisDetail(input: {
  analysis: Analysis;
  metrics: SkinMetric[];
  survey: Survey;
  recommendation: Recommendation | null;
  queue: QueueInfo;
}): AnalysisDetailDto {
  const { analysis, metrics, survey, recommendation, queue } = input;
  const report = scoreReportFromRows(metrics);
  const isQueued = analysis.status === 'queued';
  return {
    analysisId: analysis.id,
    status: analysis.status,
    createdAt: toIsoUtc(analysis.createdAt),
    completedAt: toIsoUtcOrNull(analysis.completedAt),
    deviceType: analysis.deviceType,
    queuePosition: isQueued ? queue.queuePosition : null,
    estimatedWaitSec: isQueued ? queue.estimatedWaitSec : null,
    measurement: {
      isDemo: analysis.isDemo,
      confidence: analysis.confidence,
      calibrationApplied: analysis.calibrationApplied,
      overallScore: analysis.overallScore,
      summary: report.summary,
      byRoi: report.byRoi,
      modelVersion: analysis.skinModelVersion,
    },
    recommendation: presentRecommendation(analysis, recommendation),
    // JSONB는 키 순서를 바꿔 돌려주므로 명세 순서로 다시 맞춘다
    survey: normalizeSurvey(survey.payload),
    error: presentError(analysis),
  };
}

export function presentTrendPoint(analysis: Analysis, metrics: SkinMetric[]): TrendPointDto {
  const report = scoreReportFromRows(metrics);
  const value = (metric: string) => report.summary.find((s) => s.metric === metric)?.value ?? 0;
  return {
    analysisId: analysis.id,
    date: kstDate(analysis.createdAt),
    overallScore: analysis.overallScore,
    summary: {
      moisture: value('moisture'),
      elasticity: value('elasticity'),
      pigmentation: value('pigmentation'),
      pore: value('pore'),
    },
  };
}

/** delta = current − previous (정수끼리). pigmentation·pore는 음수가 좋아진 것 */
export function trendDelta(current: TrendPointDto, previous: TrendPointDto): TrendDeltaDto {
  return {
    overallScore: current.overallScore - previous.overallScore,
    moisture: current.summary.moisture - previous.summary.moisture,
    elasticity: current.summary.elasticity - previous.summary.elasticity,
    pigmentation: current.summary.pigmentation - previous.summary.pigmentation,
    pore: current.summary.pore - previous.summary.pore,
  };
}
