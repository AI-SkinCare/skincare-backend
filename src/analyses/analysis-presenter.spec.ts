import { presentAnalysisDetail, presentTrendPoint, trendDelta } from './analysis-presenter';
import type { Analysis } from './entities/analysis.entity';
import type { Recommendation } from './entities/recommendation.entity';
import type { SkinMetric } from './entities/skin-metric.entity';
import type { Survey } from './entities/survey.entity';

const analysis = (overrides: Partial<Analysis> = {}): Analysis =>
  ({
    id: '01J9Z3K5V8Q2M4N6P8R0S2T4V6',
    userId: 1,
    status: 'done',
    deviceType: 'smartphone',
    isDemo: false,
    confidence: 0.87,
    calibrationApplied: true,
    overallScore: 63,
    skinModelVersion: 'skin_reg_v1',
    measureMs: 800,
    queuedAt: new Date('2026-10-15T09:12:33.456Z'),
    recStartedAt: null,
    completedAt: new Date('2026-10-15T09:13:31.999Z'),
    queueWaitMs: null,
    recommendMs: null,
    attemptCount: 1,
    errorCode: null,
    createdAt: new Date('2026-10-15T09:12:33.456Z'),
    ...overrides,
  }) as Analysis;

const metrics = [
  {
    analysisId: 'a',
    roi: 'cheek_left',
    moisture: 0.66,
    elasticity: 0.76,
    pigmentation: 0.41,
    pore: 0.38,
  },
  {
    analysisId: 'a',
    roi: 'full_face',
    moisture: 0.61,
    elasticity: 0.74,
    pigmentation: 0.38,
    pore: 0.45,
  },
  {
    analysisId: 'a',
    roi: 'forehead',
    moisture: 0.58,
    elasticity: 0.71,
    pigmentation: 0.35,
    pore: 0.52,
  },
] as SkinMetric[];

const survey = {
  analysisId: 'a',
  gender: 'female',
  ageBand: '20s',
  skinType: 'combination',
  // JSONB가 키 순서를 바꿔 돌려준 상황
  payload: {
    note: null,
    gender: 'female',
    ageBand: '20s',
    concerns: ['pore'],
    skinType: 'combination',
    sleepHours: null,
    stressLevel: null,
    currentProducts: [],
    sunExposureLevel: null,
    waterIntakeLevel: null,
  },
} as unknown as Survey;

const recommendation = {
  analysisId: 'a',
  skinType: 'combination',
  concerns: ['pore', 'pigmentation'],
  ingredients: [{ name: '나이아신아마이드', purpose: null, priority: 1 }],
  routine: [{ step: 1, time: 'morning', action: '세안합니다.' }],
  reasoning: ['a', 'b', 'c'],
  answer: '요약',
  disclaimer: '면책',
  inputNotes: [],
  filtered: false,
  usedFallback: false,
  modelVersion: 'rec_sllm_v2',
  inferenceMs: 1,
  createdAt: new Date(),
} as unknown as Recommendation;

const noQueue = { queuePosition: null, estimatedWaitSec: null };

describe('presentAnalysisDetail (TRD §10.2)', () => {
  it('done: 측정·추천 전체, 시간은 밀리초 없는 UTC, byRoi는 full_face 제외·순서 고정', () => {
    const dto = presentAnalysisDetail({
      analysis: analysis(),
      metrics,
      survey,
      recommendation,
      queue: noQueue,
    });
    expect(dto.createdAt).toBe('2026-10-15T09:12:33Z');
    expect(dto.completedAt).toBe('2026-10-15T09:13:31Z');
    expect(dto.measurement.summary.map((s) => [s.value, s.score, s.grade])).toEqual([
      [61, 61, 'normal'],
      [74, 74, 'good'],
      [38, 62, 'normal'],
      [45, 55, 'normal'],
    ]);
    expect(dto.measurement.byRoi.map((r) => [r.roi, r.overallScore])).toEqual([
      ['forehead', 61],
      ['cheek_left', 66],
    ]);
    expect(dto.recommendation).toMatchObject({
      status: 'done',
      skinType: { code: 'combination', label: '복합성' },
      concerns: [
        { code: 'pore', label: '모공' },
        { code: 'pigmentation', label: '미백(색소침착/기미/칙칙함)' },
      ],
    });
    expect(dto.error).toBeNull();
  });

  it('설문은 명세 순서로 다시 맞춘다', () => {
    const dto = presentAnalysisDetail({
      analysis: analysis(),
      metrics,
      survey,
      recommendation,
      queue: noQueue,
    });
    expect(Object.keys(dto.survey)).toEqual([
      'gender',
      'ageBand',
      'skinType',
      'sleepHours',
      'waterIntakeLevel',
      'sunExposureLevel',
      'stressLevel',
      'concerns',
      'currentProducts',
      'note',
    ]);
  });

  it('queued: recommendation pending + 순번·예상 대기', () => {
    const dto = presentAnalysisDetail({
      analysis: analysis({ status: 'queued', completedAt: null }),
      metrics,
      survey,
      recommendation: null,
      queue: { queuePosition: 2, estimatedWaitSec: 110 },
    });
    expect(dto.recommendation).toEqual({ status: 'pending' });
    expect([dto.queuePosition, dto.estimatedWaitSec]).toEqual([2, 110]);
  });

  it('recommending: pending, 순번 null', () => {
    const dto = presentAnalysisDetail({
      analysis: analysis({ status: 'recommending' }),
      metrics,
      survey,
      recommendation: null,
      queue: { queuePosition: 1, estimatedWaitSec: 55 },
    });
    expect(dto.recommendation).toEqual({ status: 'pending' });
    expect([dto.queuePosition, dto.estimatedWaitSec]).toEqual([null, null]);
  });

  it('failed: recommendation failed + error code·message, 측정은 그대로', () => {
    const dto = presentAnalysisDetail({
      analysis: analysis({ status: 'failed', errorCode: 'E3002' }),
      metrics,
      survey,
      recommendation: null,
      queue: noQueue,
    });
    expect(dto.recommendation).toEqual({ status: 'failed' });
    expect(dto.error).toEqual({
      code: 'E3002',
      message: '추천을 만들지 못했어요. 다시 시도해 주세요.',
    });
    expect(dto.measurement.overallScore).toBe(63);
  });
});

describe('trend', () => {
  it('date는 KST 날짜, delta = current − previous', () => {
    const current = presentTrendPoint(
      analysis({ createdAt: new Date('2026-10-15T16:00:00Z') }),
      metrics,
    );
    expect(current.date).toBe('2026-10-16'); // UTC 16시 = KST 다음 날 01시
    const previous = {
      ...current,
      overallScore: 60,
      summary: { moisture: 54, elasticity: 72, pigmentation: 40, pore: 47 },
    };
    expect(trendDelta(current, previous)).toEqual({
      overallScore: 3,
      moisture: 7,
      elasticity: 2,
      pigmentation: -2,
      pore: -2,
    });
  });
});
