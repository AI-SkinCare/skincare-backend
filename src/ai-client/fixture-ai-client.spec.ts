import { AiError } from './ai-error';
import {
  FIXTURE_REC_DELAY_MS,
  FIXTURE_REC_SLOW_DELAY_MS,
  FixtureAiClient,
} from './fixture-ai-client';

const input = {
  image: Buffer.alloc(4),
  mimeType: 'image/png' as const,
  deviceType: 'smartphone' as const,
};
const request = { metrics: null, survey: { gender: 'female' as const, ageBand: '20s' as const } };

describe('FixtureAiClient (TRD §12.7)', () => {
  const sleeps: number[] = [];
  const sleep = async (ms: number) => {
    sleeps.push(ms);
  };
  beforeEach(() => (sleeps.length = 0));

  it('ok: 측정 is_stub, 추천 1초 지연', async () => {
    const ai = new FixtureAiClient('ok', sleep);
    expect((await ai.measureSkin(input, { requestId: 'r' })).isStub).toBe(true);
    expect((await ai.recommend(request, { requestId: 'r' })).skinType).toBe('combination');
    expect(sleeps).toEqual([FIXTURE_REC_DELAY_MS]);
    expect(ai.recommendRequests).toEqual([request]);
  });

  it('no-face: 측정 422 E2001', async () => {
    await expect(
      new FixtureAiClient('ok', sleep).measureSkin(input, { requestId: 'r', scenario: 'no-face' }),
    ).rejects.toMatchObject({
      kind: 'http',
      status: 422,
      code: 'E2001',
    });
  });

  it('rec-timeout: 추천 504 E3002 즉시', async () => {
    await expect(
      new FixtureAiClient('rec-timeout', sleep).recommend(request, { requestId: 'r' }),
    ).rejects.toMatchObject({
      status: 504,
      code: 'E3002',
    });
    expect(sleeps).toEqual([]);
  });

  it('rec-slow: 60초 지연 후 정상', async () => {
    await new FixtureAiClient('ok', sleep).recommend(request, {
      requestId: 'r',
      scenario: 'rec-slow',
    });
    expect(sleeps).toEqual([FIXTURE_REC_SLOW_DELAY_MS]);
  });

  it('ai-down: 측정·추천·health 모두 connection', async () => {
    const ai = new FixtureAiClient('ai-down', sleep);
    await expect(ai.measureSkin(input, { requestId: 'r' })).rejects.toBeInstanceOf(AiError);
    await expect(ai.recommend(request, { requestId: 'r' })).rejects.toMatchObject({
      kind: 'connection',
    });
    await expect(ai.health()).rejects.toMatchObject({ kind: 'connection' });
  });

  it('health는 env 시나리오만 따른다', async () => {
    await expect(new FixtureAiClient('no-face', sleep).health()).resolves.toMatchObject({
      status: 'ok',
    });
  });
});
