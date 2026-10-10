// TRD §11 — 점수·등급 순수 함수. 측정 저장(overall_score)과 응답(summary·byRoi)이 같은 함수를 쓴다.
//
// `0.565 * 100`은 56.49999…라 Math.round가 56을 낸다(정답 57). 그래서 0–1 값을
// 정수 basis point(bp, 0–10000)로 확정한 뒤 정수끼리 계산하고, 나눗셈은 마지막에 한 번만 한다.

import { BY_ROI_ORDER, GOOD_DIRECTION_METRICS, Grade, METRICS, Metric, Roi } from '../common/enums';

export type MetricRatios = Record<Metric, number>;
export type MetricBp = Record<Metric, number>;

export interface SummaryItem {
  metric: Metric;
  value: number;
  score: number;
  grade: Grade;
}

export interface RoiScore {
  roi: Roi;
  moisture: number;
  elasticity: number;
  pigmentation: number;
  pore: number;
  overallScore: number;
  grade: Grade;
}

export interface ScoreReport {
  overallScore: number;
  summary: SummaryItem[];
  byRoi: RoiScore[];
}

/** 0–1 원본 → bp. AI 원본(4자리 초과 가능)과 DB 값(4자리) 모두 이것으로 복원한다. */
export function toBp(ratio: number): number {
  return Math.round(ratio * 10000);
}

/** bp → 저장할 0–1 값 (4자리 확정). DB 값과 계산값이 일치한다. */
export function bpToRatio(bp: number): number {
  return Number((bp / 10000).toFixed(4));
}

export function metricBp(ratios: MetricRatios): MetricBp {
  return {
    moisture: toBp(ratios.moisture),
    elasticity: toBp(ratios.elasticity),
    pigmentation: toBp(ratios.pigmentation),
    pore: toBp(ratios.pore),
  };
}

export function isGoodDirection(metric: Metric): boolean {
  return GOOD_DIRECTION_METRICS.has(metric);
}

export function grade(score: number): Grade {
  if (score >= 70) return 'good';
  if (score >= 40) return 'normal';
  return 'care';
}

/** 표시용 정수 value (0–100) */
export function valueFromBp(bp: number): number {
  return Math.round(bp / 100);
}

/** 좋은 방향으로 통일한 정수 score */
export function scoreFromValue(metric: Metric, value: number): number {
  return isGoodDirection(metric) ? value : 100 - value;
}

function scoreBp(metric: Metric, bp: number): number {
  return isGoodDirection(metric) ? bp : 10000 - bp;
}

/** ROI 하나의 종합 점수 = round(평균(반올림 전 score 4개)) */
export function overallFromBp(bp: MetricBp): number {
  const sum = METRICS.reduce((acc, metric) => acc + scoreBp(metric, bp[metric]), 0);
  return Math.round(sum / 400);
}

/**
 * 여러 ROI의 평균 — full_face가 없을 때 (TRD §11.2).
 * 지표별 합(sumBp)으로 계산하고 나눗셈은 한 번만 해서 오차를 없앤다.
 */
function averaged(bps: MetricBp[]): { values: MetricBp; overall: number } {
  const n = bps.length;
  const values = {} as MetricBp;
  let scoreSum = 0;
  for (const metric of METRICS) {
    const sumBp = bps.reduce((acc, bp) => acc + bp[metric], 0);
    values[metric] = Math.round(sumBp / (100 * n));
    scoreSum += isGoodDirection(metric) ? sumBp : 10000 * n - sumBp;
  }
  return { values, overall: Math.round(scoreSum / (400 * n)) };
}

function summaryFromValues(values: MetricBp): SummaryItem[] {
  return METRICS.map((metric) => {
    const value = values[metric];
    const score = scoreFromValue(metric, value);
    return { metric, value, score, grade: grade(score) };
  });
}

/**
 * ROI별 지표(bp) → 종합 점수·summary·byRoi.
 * 종합은 full_face 기준, 없으면 검출된 ROI 평균. byRoi는 full_face 제외·순서 고정.
 */
export function buildScoreReport(metricsByRoi: Partial<Record<Roi, MetricBp>>): ScoreReport {
  const fullFace = metricsByRoi.full_face;
  const detected = BY_ROI_ORDER.filter((roi) => metricsByRoi[roi] !== undefined);

  let overallScore: number;
  let summaryValues: MetricBp;
  if (fullFace) {
    overallScore = overallFromBp(fullFace);
    summaryValues = {
      moisture: valueFromBp(fullFace.moisture),
      elasticity: valueFromBp(fullFace.elasticity),
      pigmentation: valueFromBp(fullFace.pigmentation),
      pore: valueFromBp(fullFace.pore),
    };
  } else if (detected.length > 0) {
    const avg = averaged(detected.map((roi) => metricsByRoi[roi] as MetricBp));
    overallScore = avg.overall;
    summaryValues = avg.values;
  } else {
    throw new Error('지표가 있는 ROI가 없습니다');
  }

  const byRoi: RoiScore[] = detected.map((roi) => {
    const bp = metricsByRoi[roi] as MetricBp;
    const overall = overallFromBp(bp);
    return {
      roi,
      moisture: valueFromBp(bp.moisture),
      elasticity: valueFromBp(bp.elasticity),
      pigmentation: valueFromBp(bp.pigmentation),
      pore: valueFromBp(bp.pore),
      overallScore: overall,
      grade: grade(overall),
    };
  });

  return { overallScore, summary: summaryFromValues(summaryValues), byRoi };
}
