import { TestApp, createTestApp } from './helpers/test-app';

describe('공통 규약 (TRD §7)', () => {
  let t: TestApp;
  beforeAll(async () => {
    t = await createTestApp();
  });
  afterAll(() => t.close());

  it('성공 봉투 { success: true, data }', async () => {
    const res = await t.http().get('/api/v1/system/status').expect(200);
    expect(res.body).toEqual({ success: true, data: expect.any(Object) });
  });

  it('X-Request-Id: 형식이 맞으면 그대로, 아니면 새로 만든다 — 에러 응답에도', async () => {
    const echoed = await t
      .http()
      .get('/api/v1/system/status')
      .set('X-Request-Id', 'ios-req.01_A')
      .expect(200);
    expect(echoed.headers['x-request-id']).toBe('ios-req.01_A');
    const generated = await t
      .http()
      .get('/api/v1/nope')
      .set('X-Request-Id', 'bad id with spaces')
      .expect(404);
    expect(generated.headers['x-request-id']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('없는 경로·메서드 → 404 E4003 봉투 (Nest 기본 예외 모양이 나가지 않음)', async () => {
    for (const res of [
      await t.http().get('/api/v1/nope'),
      await t.http().put('/api/v1/system/status'),
    ]) {
      expect(res.status).toBe(404);
      expect(res.body).toEqual({
        success: false,
        error: { code: 'E4003', message: '결과를 찾을 수 없어요.', detail: null },
      });
    }
  });

  it('깨진 JSON → 400이 아니라 422 E4002', async () => {
    const res = await t
      .http()
      .post('/api/v1/auth/login')
      .set('Content-Type', 'application/json')
      .send('{bad')
      .expect(422);
    expect(res.body.error.code).toBe('E4002');
  });

  it('Swagger JSON(/api/docs-json)에 봉투 구조와 [앱 협의] refreshToken이 나온다', async () => {
    const res = await t.http().get('/api/docs-json').expect(200);
    const doc = res.body as {
      paths: Record<string, unknown>;
      components: {
        schemas: Record<string, { properties?: Record<string, unknown>; required?: string[] }>;
      };
    };
    expect(Object.keys(doc.paths)).toEqual(
      expect.arrayContaining([
        '/api/v1/auth/signup',
        '/api/v1/auth/login',
        '/api/v1/auth/refresh',
        '/api/v1/auth/logout',
        '/api/v1/users/me',
        '/api/v1/analyses',
        '/api/v1/analyses/{id}',
        '/api/v1/analyses/{id}/recommendation/retry',
        '/api/v1/analyses/{id}/trend',
        '/api/v1/system/status',
      ]),
    );
    expect(doc.components.schemas.ErrorEnvelopeDto).toBeDefined();
    expect(doc.components.schemas.SuccessEnvelopeDto).toBeDefined();
    const token = doc.components.schemas.TokenResponseDto;
    expect(token.properties).toHaveProperty('refreshToken');
    expect(token.required).not.toContain('refreshToken');
  });
});
