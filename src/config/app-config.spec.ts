import { loadAppConfig } from './app-config';

const base = {
  DATABASE_URL: 'postgres://u:p@localhost:5432/db',
  JWT_ACCESS_SECRET: 'access-secret',
  JWT_REFRESH_SECRET: 'refresh-secret',
};

describe('loadAppConfig (TRD §6 기동 시 검증)', () => {
  it('기본값', () => {
    const config = loadAppConfig(base);
    expect(config).toMatchObject({
      port: 3000,
      ai: { mode: 'fixture', fixtureScenario: 'ok' },
      queue: { limit: 10, maxWaitMin: 60 },
      analysisDailyLimit: 20,
      cookieSecure: true,
    });
  });

  it('필수값 누락이면 실패 (값은 메시지에 넣지 않음)', () => {
    expect(() => loadAppConfig({ ...base, DATABASE_URL: '' })).toThrow(/DATABASE_URL/);
  });

  it('AI_MODE=real인데 주소·토큰 없음 → 실패', () => {
    expect(() => loadAppConfig({ ...base, AI_MODE: 'real' })).toThrow(/AI_BASE_URL/);
  });

  it('두 JWT 비밀키가 같으면 실패', () => {
    let message = '';
    try {
      loadAppConfig({ ...base, JWT_REFRESH_SECRET: 'access-secret' });
    } catch (error) {
      message = (error as Error).message;
    }
    expect(message).toMatch(/서로 달라야/);
    expect(message).not.toContain('access-secret');
  });

  it('REC_WORKER_CONCURRENCY≠1 → 실패', () => {
    expect(() => loadAppConfig({ ...base, REC_WORKER_CONCURRENCY: '2' })).toThrow(
      /REC_WORKER_CONCURRENCY/,
    );
  });

  it('production은 COOKIE_SECURE=false여도 Secure', () => {
    expect(
      loadAppConfig({ ...base, NODE_ENV: 'production', COOKIE_SECURE: 'false' }).cookieSecure,
    ).toBe(true);
    expect(loadAppConfig({ ...base, COOKIE_SECURE: 'false' }).cookieSecure).toBe(false);
  });
});
