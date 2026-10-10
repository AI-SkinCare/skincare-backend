import { readFileSync, readdirSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { solidPng } from '../fixtures/images';
import {
  DEFAULT_SURVEY,
  PASSWORD,
  randomIp,
  refreshCookieOf,
  submitOk,
  userWithToken,
} from './helpers/factories';
import { captureStdout } from './helpers/capture-stdout';
import { TestApp, createTestApp } from './helpers/test-app';

/** 디렉터리 아래(얕게 2단계) 파일 중 since 이후 바뀐 것 */
function recentFiles(dir: string, since: number, depth = 2): string[] {
  const found: string[] = [];
  let entries: string[] = [];
  try {
    entries = readdirSync(dir);
  } catch {
    return found;
  }
  for (const name of entries) {
    const path = join(dir, name);
    try {
      const stat = statSync(path);
      if (stat.isDirectory() && depth > 0) found.push(...recentFiles(path, since, depth - 1));
      else if (stat.isFile() && stat.mtimeMs >= since && stat.size < 50 * 1024 * 1024)
        found.push(path);
    } catch {
      // 사라진 파일 등은 무시
    }
  }
  return found;
}

describe('개인정보 (TRD §15)', () => {
  let t: TestApp;
  beforeAll(async () => {
    // 로그를 켠 앱 — 기동 로그는 버린다
    await captureStdout(async () => {
      t = await createTestApp({ config: (c) => ({ ...c, logLevel: 'debug' }) });
    });
  });
  afterAll(() => captureStdout(() => t.close()));
  beforeEach(() => t.truncate());

  it('TC-PRI-01: 분석 처리 후 업로드 관련 파일이 디스크(OS 임시 디렉터리)에 없다', async () => {
    const marker = `face-marker-${Date.now()}`;
    const since = Date.now() - 1000;
    await captureStdout(async () => {
      const me = await userWithToken(t);
      const id = await submitOk(t, me, { image: solidPng(1080, 1440, marker) });
      await t.worker.tick();
      await t.http().get(`/api/v1/analyses/${id}`).set(me.auth).expect(200);
    });

    const leaked = recentFiles(tmpdir(), since).filter((path) => {
      try {
        return readFileSync(path).includes(marker);
      } catch {
        return false;
      }
    });
    expect(leaked).toEqual([]);
  });

  it('TC-PRI-01: 코드 검사 — multer diskStorage·dest를 쓰지 않는다', () => {
    const offenders: string[] = [];
    const walk = (dir: string) => {
      for (const name of readdirSync(dir)) {
        const path = join(dir, name);
        if (statSync(path).isDirectory()) walk(path);
        else if (path.endsWith('.ts')) {
          const code = readFileSync(path, 'utf8');
          if (/diskStorage\s*\(/.test(code) || /\bdest\s*:/.test(code)) offenders.push(path);
        }
      }
    };
    walk(join(__dirname, '..', '..', 'src'));
    expect(offenders).toEqual([]);
  });

  it('TC-PRI-02: 전체 흐름의 로그에 설문 원문·토큰·비밀번호·이메일 원문이 없다', async () => {
    const note = `비밀메모-${Date.now()}`;
    const product = `비밀제품-${Date.now()}`;
    const email = `private.person${Date.now()}@example.com`;
    const secrets: string[] = [note, product, PASSWORD, email];

    const { raw, lines } = await captureStdout(async () => {
      await t
        .http()
        .post('/api/v1/auth/signup')
        .send({
          email,
          password: PASSWORD,
          nickname: '비밀',
          agreements: { terms: true, privacy: true, imageProcessing: true, over14: true },
        })
        .expect(201);
      const loginRes = await t
        .http()
        .post('/api/v1/auth/login')
        .set('X-Client', 'ios')
        .set('CF-Connecting-IP', randomIp())
        .send({ email, password: PASSWORD })
        .expect(200);
      const { accessToken, refreshToken } = loginRes.body.data as {
        accessToken: string;
        refreshToken: string;
      };
      secrets.push(accessToken, refreshToken, refreshCookieOf(loginRes));
      const auth = { Authorization: `Bearer ${accessToken}` };

      const refreshed = await t
        .http()
        .post('/api/v1/auth/refresh')
        .set('X-Client', 'ios')
        .send({ refreshToken })
        .expect(200);
      secrets.push(refreshed.body.data.accessToken, refreshed.body.data.refreshToken);
      // 재사용 감지 WARN도 남긴다
      await t.http().post('/api/v1/auth/refresh').send({ refreshToken }).expect(401);

      const res = await t
        .http()
        .post('/api/v1/analyses')
        .set(auth)
        .attach('image', solidPng(1080, 1440), 'face.png')
        .field('survey', JSON.stringify({ ...DEFAULT_SURVEY, note, currentProducts: [product] }))
        .field('deviceType', 'smartphone')
        .expect(202);
      await t.worker.tick();
      await t.http().get(`/api/v1/analyses/${res.body.data.analysisId}`).set(auth).expect(200);
      // 검증 실패 경로도 (survey 원문이 detail·로그에 들어가지 않아야 함)
      await t
        .http()
        .post('/api/v1/analyses')
        .set(auth)
        .field('survey', `{"note":"${note}"`)
        .expect(422);
      await t
        .http()
        .post('/api/v1/auth/logout')
        .set(auth)
        .send({ refreshToken: refreshed.body.data.refreshToken })
        .expect(204);
    });

    expect(lines.length).toBeGreaterThan(5);
    for (const secret of secrets) expect(raw.includes(secret)).toBe(false);
    expect(raw).toContain(`p***@example.com`);
    // TC-ANA-09 확인용: 데모 측정이면 측정값을 보내지 않았다는 사실만 로그에 남는다
    expect(lines.find((l) => l.msg === 'recommendation_done')).toMatchObject({ metricsSent: null });
    // 요청 로그 공통 필드
    expect(lines.find((l) => l.msg === 'request completed')).toMatchObject({
      requestId: expect.any(String),
      durationMs: expect.any(Number),
    });
  });
});
