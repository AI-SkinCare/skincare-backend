// 실제 AI 서버(skincare-ai)와의 계약 테스트 — AI_CONTRACT_URL이 있을 때만 돈다 (CI에선 건너뜀).
//
//   AI_CONTRACT_URL=http://127.0.0.1:8000 AI_CONTRACT_TOKEN=<AI_INTERNAL_TOKEN> npm run test:e2e -- ai-contract
//
// REC_BACKEND=mock이면 수 초, sllm이면 추천 1건에 약 50초 × 9건이 걸린다.
// 백엔드가 만든 요청(코드 → 라벨 변환)을 AI가 인식하는지, AI 응답이 우리 검증을 통과하는지 본다.
import { AiError } from '../../src/ai-client/ai-error';
import { mapMeasureError, mapRecommendError } from '../../src/ai-client/ai-error.mapper';
import { buildRecommendRequest } from '../../src/ai-client/ai-transform';
import { HttpAiClient } from '../../src/ai-client/http-ai-client';
import type { NormalizedSurvey } from '../../src/analyses/survey';
import { CONCERNS, ROIS, SKIN_TYPES } from '../../src/common/enums';
import { ErrorCode } from '../../src/common/errors/error-codes';
import { solidPng } from '../fixtures/images';

const URL = process.env.AI_CONTRACT_URL;
const TOKEN = process.env.AI_CONTRACT_TOKEN ?? '';
const run = URL ? describe : describe.skip;

const client = (token = TOKEN) =>
  new HttpAiClient({
    baseUrl: URL ?? '',
    internalToken: token,
    skinTimeoutMs: 20000,
    recTimeoutMs: 130000,
  });
const ctx = { requestId: 'ai-contract-test' };

const survey = (overrides: Partial<NormalizedSurvey> = {}): NormalizedSurvey => ({
  gender: 'female',
  ageBand: '20s',
  skinType: null,
  sleepHours: null,
  waterIntakeLevel: null,
  sunExposureLevel: null,
  stressLevel: null,
  concerns: [],
  currentProducts: [],
  note: null,
  ...overrides,
});

async function aiError(promise: Promise<unknown>): Promise<AiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof AiError) return error;
    throw error;
  }
  throw new Error('AI가 오류를 내지 않았다');
}

run('AI 서버 계약 (실제 skincare-ai)', () => {
  jest.setTimeout(15 * 60 * 1000);

  it('GET /health — 인증 없이 status ok', async () => {
    expect((await client('').health()).status).toBe('ok');
  });

  it('POST /infer/skin-metrics — multipart image·device_type, 응답이 검증을 통과', async () => {
    const result = await client().measureSkin(
      { image: solidPng(1080, 1440), mimeType: 'image/png', deviceType: 'smartphone' },
      ctx,
    );
    expect(Object.keys(result.metrics).length).toBeGreaterThan(0);
    expect(typeof result.isStub).toBe('boolean');
  });

  it.each(CONCERNS.map((concern, i) => [concern, SKIN_TYPES[i % SKIN_TYPES.length]] as const))(
    '고민 %s · 피부 타입 %s 라벨을 AI가 인식한다',
    async (concern, skinType) => {
      const result = await client().recommend(
        buildRecommendRequest(null, survey({ skinType, concerns: [concern] })),
        ctx,
      );
      expect(result.skinType).toBe(skinType);
      expect(result.concerns[0]).toBe(concern);
      expect(result.disclaimer.length).toBeGreaterThan(0);
    },
  );

  it('측정값(모든 ROI) + 설문 전체 필드도 받는다', async () => {
    const metrics = Object.fromEntries(
      ROIS.map((roi) => [
        roi,
        { moisture: 0.61, elasticity: 0.74, pigmentation: 0.38, pore: 0.45 },
      ]),
    );
    const result = await client().recommend(
      buildRecommendRequest(
        metrics,
        survey({
          skinType: 'combination',
          sleepHours: 5,
          waterIntakeLevel: 'low',
          sunExposureLevel: 'high',
          stressLevel: 'high',
          concerns: ['pore', 'pigmentation'],
          currentProducts: ['토너', '수분크림'],
          note: '계약 테스트',
        }),
      ),
      ctx,
    );
    expect(result.concerns).toContain('pore');
  });

  it('필수만 있는 설문(성별·연령대)도 받는다', async () => {
    const result = await client().recommend(buildRecommendRequest(null, survey()), ctx);
    expect(SKIN_TYPES).toContain(result.skinType);
  });

  it('내부 토큰이 틀리면 AI 401 E1001 → 측정 503 E3001 / 워커 failed E3001', async () => {
    const wrong = client('wrong-token');
    const measure = await aiError(
      wrong.measureSkin(
        { image: solidPng(800, 800), mimeType: 'image/png', deviceType: 'smartphone' },
        ctx,
      ),
    );
    expect(measure).toMatchObject({ kind: 'http', status: 401, code: 'E1001' });
    expect(mapMeasureError(measure).code).toBe(ErrorCode.E3001);
    const rec = await aiError(wrong.recommend(buildRecommendRequest(null, survey()), ctx));
    expect(mapRecommendError(rec).code).toBe(ErrorCode.E3001);
  });
});
