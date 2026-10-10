// 시드·e2e가 같이 쓰는 완료(done) 분석 생성기 (TRD §8.4).
// 날짜를 최근 수 주에 분산, is_demo=false(이력·추이 화면 확인용), full_face + 일부 ROI, 설문·추천 포함.
import { EntityManager } from 'typeorm';
import { Analysis } from '../../analyses/entities/analysis.entity';
import { Recommendation } from '../../analyses/entities/recommendation.entity';
import { SkinMetric } from '../../analyses/entities/skin-metric.entity';
import { Survey } from '../../analyses/entities/survey.entity';
import { MetricBp, bpToRatio, buildScoreReport } from '../../analyses/scoring';
import type { NormalizedSurvey } from '../../analyses/survey';
import { ConcernCode, METRICS, Roi } from '../../common/enums';
import { newUlid } from '../../common/utils/ulid';

const HOUR_MS = 60 * 60 * 1000;
const SEED_ROIS: Roi[] = ['full_face', 'forehead', 'cheek_left', 'cheek_right', 'chin'];
const CONCERN_PAIRS: ConcernCode[][] = [
  ['pore', 'pigmentation'],
  ['dryness'],
  ['pore'],
  ['redness', 'sensitivity'],
  ['wrinkle', 'sagging'],
];

/** 결정적 의사 난수 (mulberry32) — 다시 돌려도 같은 값 */
function prng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const clampBp = (bp: number) => Math.max(0, Math.min(10000, Math.round(bp)));

/**
 * userId에게 done 분석 count건을 만든다. 0번이 가장 최근, 약 1.5일 간격으로 과거로 간다.
 * 오래된 것일수록 지표가 조금 나쁘게 해서 추이 화면에 변화가 보이게 한다.
 */
export async function insertDoneAnalyses(
  manager: EntityManager,
  userId: number,
  count: number,
  now: Date = new Date(),
): Promise<string[]> {
  const random = prng(userId * 7919 + count);
  const ids: string[] = [];
  for (let i = 0; i < count; i++) {
    const createdAt = new Date(now.getTime() - (i * 36 + 2) * HOUR_MS);
    const id = newUlid(createdAt.getTime());
    const drift = i * 60; // 과거로 갈수록 0.6%p씩 나쁘게
    const base: MetricBp = {
      moisture: clampBp(6200 - drift + random() * 800),
      elasticity: clampBp(7000 - drift + random() * 600),
      pigmentation: clampBp(3800 + drift + random() * 600),
      pore: clampBp(4400 + drift + random() * 600),
    };
    const byRoi: Partial<Record<Roi, MetricBp>> = {};
    for (const roi of SEED_ROIS) {
      byRoi[roi] =
        roi === 'full_face'
          ? base
          : (Object.fromEntries(
              METRICS.map((m) => [m, clampBp(base[m] + (random() - 0.5) * 1200)]),
            ) as MetricBp);
    }
    const report = buildScoreReport(byRoi);
    const queueWaitMs = Math.round(2000 + random() * 20000);
    const recommendMs = Math.round(48000 + random() * 12000);
    const recStartedAt = new Date(createdAt.getTime() + queueWaitMs);
    const concerns = CONCERN_PAIRS[i % CONCERN_PAIRS.length];

    await manager.insert(Analysis, {
      id,
      userId,
      status: 'done',
      deviceType: 'smartphone',
      isDemo: false,
      confidence: 0.85,
      calibrationApplied: true,
      overallScore: report.overallScore,
      skinModelVersion: 'skin_reg_v1',
      measureMs: Math.round(800 + random() * 1500),
      queuedAt: createdAt,
      recStartedAt,
      completedAt: new Date(recStartedAt.getTime() + recommendMs),
      queueWaitMs,
      recommendMs,
      attemptCount: 1,
      errorCode: null,
      createdAt,
    });
    await manager.insert(
      SkinMetric,
      (Object.entries(byRoi) as [Roi, MetricBp][]).map(([roi, bp]) => ({
        analysisId: id,
        roi,
        moisture: bpToRatio(bp.moisture),
        elasticity: bpToRatio(bp.elasticity),
        pigmentation: bpToRatio(bp.pigmentation),
        pore: bpToRatio(bp.pore),
      })),
    );
    const survey: NormalizedSurvey = {
      gender: 'female',
      ageBand: '20s',
      skinType: 'combination',
      sleepHours: 6,
      waterIntakeLevel: 'low',
      sunExposureLevel: 'high',
      stressLevel: 'medium',
      concerns,
      currentProducts: ['토너', '수분크림'],
      note: null,
    };
    await manager.insert(Survey, {
      analysisId: id,
      gender: survey.gender,
      ageBand: survey.ageBand,
      skinType: survey.skinType,
      payload: survey,
    });
    await manager.insert(Recommendation, {
      analysisId: id,
      skinType: 'combination',
      concerns,
      ingredients: [
        {
          name: '나이아신아마이드',
          purpose: '피지 분비와 모공 관리에 도움을 줄 수 있습니다.',
          priority: 1,
        },
        { name: '판테놀', purpose: '피부 장벽을 지키는 데 도움을 줄 수 있습니다.', priority: 2 },
      ],
      routine: [
        { step: 1, time: 'morning', action: '약산성 클렌저로 가볍게 세안합니다.' },
        { step: 2, time: 'morning', action: '자외선 차단제를 바릅니다.' },
        { step: 3, time: 'evening', action: '보습제를 충분히 바릅니다.' },
      ],
      reasoning: [
        '시드 데이터입니다. 피지 분비와 생활 습관이 피부 상태에 영향을 줄 수 있습니다.',
        '나이아신아마이드와 판테놀은 피부 관리에 도움을 줄 수 있습니다.',
        '아침에는 자외선 차단제를, 저녁에는 보습제를 챙겨 주세요.',
      ],
      answer: '피지 관리와 자외선 차단을 함께 챙기는 것이 도움이 될 수 있습니다.',
      disclaimer:
        '본 결과는 참고용 정보이며 의학적 진단이나 치료를 대신하지 않습니다. 피부 이상이 지속되면 피부과 전문의와 상담하세요.',
      inputNotes: [],
      filtered: false,
      usedFallback: false,
      modelVersion: 'rec_sllm_v2',
      inferenceMs: recommendMs - 300,
      createdAt: new Date(recStartedAt.getTime() + recommendMs),
    });
    ids.push(id);
  }
  return ids;
}
