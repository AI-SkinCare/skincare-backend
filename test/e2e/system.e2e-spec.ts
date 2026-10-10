import { AddressInfo } from 'node:net';
import { Server, createServer } from 'node:http';
import { HttpAiClient } from '../../src/ai-client/http-ai-client';
import { createUser, login, submit, userWithToken } from './helpers/factories';
import { TestApp, createTestApp } from './helpers/test-app';
import { captureStdout } from './helpers/capture-stdout';

describe('TC-SYS-01: AI 서버가 꺼져도 서비스는 산다 (fixture ai-down)', () => {
  let t: TestApp;
  beforeAll(async () => {
    t = await createTestApp({
      config: (c) => ({ ...c, ai: { ...c.ai, fixtureScenario: 'ai-down' } }),
    });
  });
  afterAll(() => t.close());
  beforeEach(() => t.truncate());

  it('aiAvailable false, 제출 503 E3001(Retry-After 30), 로그인·이력은 정상', async () => {
    const status = await t.http().get('/api/v1/system/status').expect(200);
    expect(status.body.data).toEqual({
      aiAvailable: false,
      queueLength: 0,
      estimatedWaitSec: 0,
      checkedAt: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/),
    });

    const me = await login(t, await createUser(t.dataSource));
    const res = await submit(t, me).expect(503);
    expect(res.body.error).toMatchObject({
      code: 'E3001',
      message: '지금은 분석 서버 점검 중이에요. 잠시 후 다시 시도해 주세요.',
    });
    expect(res.headers['retry-after']).toBe('30');

    await t.http().get('/api/v1/analyses').set(me.auth).expect(200);
    await t.http().get('/api/v1/users/me').set(me.auth).expect(200);
  });

  it('가드 순서: 진행 중 분석(E4004)이 AI 가용성(E3001)보다 먼저', async () => {
    const me = await userWithToken(t);
    await t.dataSource.query(
      `INSERT INTO analyses (id, user_id, status, device_type, is_demo, confidence, overall_score, skin_model_version, measure_ms)
       VALUES ('01J9Z3K5V8Q2M4N6P8R0S2T4V6', $1, 'queued', 'smartphone', true, 0, 50, 'x', 1)`,
      [me.user.id],
    );
    expect((await submit(t, me).expect(429)).body.error.code).toBe('E4004');
  });
});

describe('TC-ANA-10: AI 401(내부 토큰 불일치) → 클라이언트 503 E3001 + ERROR 로그', () => {
  let server: Server;
  let t: TestApp;
  let skinStatus = 401;

  beforeAll(async () => {
    server = createServer((req, res) => {
      req.resume();
      req.on('end', () => {
        res.setHeader('Content-Type', 'application/json');
        if (req.url === '/health') {
          res.end(
            JSON.stringify({
              status: 'ok',
              rec_loaded: true,
              skin_loaded: true,
              rec_backend: 'sllm',
              skin_backend: 'stub',
            }),
          );
          return;
        }
        res.statusCode = skinStatus;
        res.end(
          skinStatus === 401
            ? JSON.stringify({
                success: false,
                error: {
                  code: 'E1001',
                  message: '내부 인증 토큰이 없거나 올바르지 않습니다.',
                  detail: null,
                },
              })
            : '<html>bad gateway</html>',
        );
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const { port } = server.address() as AddressInfo;
    await captureStdout(async () => {
      t = await createTestApp({
        config: (c) => ({ ...c, logLevel: 'info' }),
        aiClient: new HttpAiClient({
          baseUrl: `http://127.0.0.1:${port}`,
          internalToken: 'wrong-internal-token',
          skinTimeoutMs: 2000,
          recTimeoutMs: 2000,
        }),
      });
    });
  });
  afterAll(async () => {
    await captureStdout(() => t.close());
    await new Promise((r) => server.close(r));
  });
  beforeEach(() => t.truncate());

  it('401을 그대로 내보내지 않는다 (앱이 로그아웃시키므로)', async () => {
    skinStatus = 401;
    const logs = await captureStdout(async () => {
      const me = await userWithToken(t);
      const res = await submit(t, me).expect(503);
      expect(res.body.error.code).toBe('E3001');
    });
    const errorLine = logs.lines.find((l) => l.msg === 'ai_measure_failed');
    expect(errorLine).toMatchObject({
      level: 50,
      code: 'E3001',
      ai: { kind: 'http', status: 401, code: 'E1001' },
    });
    expect(logs.raw).not.toContain('wrong-internal-token');
    expect(Number((await t.dataSource.query('SELECT COUNT(*) FROM analyses'))[0].count)).toBe(0);
  });

  it('Cloudflare HTML 502 → connection → 503 E3001, aiAvailable을 즉시 false로', async () => {
    skinStatus = 502;
    await captureStdout(async () => {
      const me = await userWithToken(t);
      expect((await submit(t, me).expect(503)).body.error.code).toBe('E3001');
    });
    expect(t.aiHealth.isAvailable()).toBe(false);
    await captureStdout(() => t.aiHealth.check().then(() => undefined));
    expect(t.aiHealth.isAvailable()).toBe(true);
  });
});
