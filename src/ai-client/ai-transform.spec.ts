import type { NormalizedSurvey } from '../analyses/survey';
import type { SkinMetric } from '../analyses/entities/skin-metric.entity';
import { buildRecommendRequest, metricsFromRows, toAiSurvey } from './ai-transform';

const survey = (overrides: Partial<NormalizedSurvey> = {}): NormalizedSurvey => ({
  gender: 'female',
  ageBand: '20s',
  skinType: 'combination',
  sleepHours: 6,
  waterIntakeLevel: 'low',
  sunExposureLevel: 'high',
  stressLevel: 'medium',
  concerns: ['pore', 'pigmentation'],
  currentProducts: ['토너', '수분크림'],
  note: '여름에 번들거림이 심해요',
  ...overrides,
});

describe('toAiSurvey (TRD §12.4 변환 규칙, SC-03 §5.2 모양)', () => {
  it('skinType 코드 → 라벨, concerns → selfReportedConcerns 라벨', () => {
    expect(toAiSurvey(survey())).toEqual({
      gender: 'female',
      ageBand: '20s',
      skinType: '복합성',
      sleepHours: 6,
      waterIntakeLevel: 'low',
      sunExposureLevel: 'high',
      stressLevel: 'medium',
      currentProducts: ['토너', '수분크림'],
      selfReportedConcerns: ['모공', '미백(색소침착/기미/칙칙함)'],
      note: '여름에 번들거림이 심해요',
    });
  });

  it('코드를 그대로 보내지 않는다 (concerns 키 없음)', () => {
    expect(toAiSurvey(survey())).not.toHaveProperty('concerns');
  });

  it('미입력 선택 필드는 키를 생략', () => {
    const result = toAiSurvey(
      survey({
        skinType: null,
        sleepHours: null,
        waterIntakeLevel: null,
        sunExposureLevel: null,
        stressLevel: null,
        concerns: [],
        currentProducts: [],
        note: null,
      }),
    );
    expect(result).toEqual({ gender: 'female', ageBand: '20s' });
  });
});

describe('buildRecommendRequest', () => {
  const rows = [
    {
      analysisId: 'x',
      roi: 'forehead',
      moisture: 0.58,
      elasticity: 0.71,
      pigmentation: 0.35,
      pore: 0.52,
    },
    {
      analysisId: 'x',
      roi: 'full_face',
      moisture: 0.61,
      elasticity: 0.74,
      pigmentation: 0.38,
      pore: 0.45,
    },
  ] as SkinMetric[];

  it('저장된 모든 ROI를 0–1 그대로 넘긴다', () => {
    expect(buildRecommendRequest(metricsFromRows(rows), survey()).metrics).toEqual({
      full_face: { moisture: 0.61, elasticity: 0.74, pigmentation: 0.38, pore: 0.45 },
      forehead: { moisture: 0.58, elasticity: 0.71, pigmentation: 0.35, pore: 0.52 },
    });
  });

  it('TC-ANA-09: 데모 측정이면 metrics: null', () => {
    expect(buildRecommendRequest(null, survey())).toHaveProperty('metrics', null);
  });
});
