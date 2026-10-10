import { JwtService } from '@nestjs/jwt';
import { insertDoneAnalyses } from '../../src/database/seeds/seed-data';
import {
  PASSWORD,
  createUser,
  login,
  randomIp,
  refreshCookieOf,
  uniqueEmail,
  userWithToken,
} from './helpers/factories';
import { TestApp, createTestApp } from './helpers/test-app';

const AGREEMENTS = { terms: true, privacy: true, imageProcessing: true, over14: true };
const COOKIE_ATTRS = 'HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600';

describe('인증 (TRD §9)', () => {
  let t: TestApp;
  beforeAll(async () => {
    t = await createTestApp();
  });
  afterAll(() => t.close());
  beforeEach(() => t.truncate());

  const signup = (body: Record<string, unknown>) => t.http().post('/api/v1/auth/signup').send(body);
  const refreshTokensOf = (userId: number) =>
    t.dataSource.query(
      'SELECT id, revoked_at, replaced_by FROM refresh_tokens WHERE user_id = $1 ORDER BY id',
      [userId],
    ) as Promise<{ id: string; revoked_at: Date | null; replaced_by: string | null }[]>;

  describe('가입', () => {
    it('TC-AUTH-01: 201, 사용자 행과 동의 시각 4개 기록, 이메일 소문자 정규화', async () => {
      const res = await signup({
        email: '  New.User@Example.COM ',
        password: PASSWORD,
        nickname: ' 우주 ',
        agreements: AGREEMENTS,
      }).expect(201);
      expect(res.body).toEqual({ success: true, data: { userId: expect.any(Number) } });
      const [row] = await t.dataSource.query('SELECT * FROM users WHERE id = $1', [
        res.body.data.userId,
      ]);
      expect(row.email).toBe('new.user@example.com');
      expect(row.nickname).toBe('우주');
      for (const col of [
        'terms_agreed_at',
        'privacy_agreed_at',
        'image_consent_at',
        'over14_confirmed_at',
      ]) {
        expect(row[col]).toBeInstanceOf(Date);
      }
    });

    it('TC-SEC-02: password_hash가 $2b$12$로 시작', async () => {
      const res = await signup({
        email: uniqueEmail(),
        password: PASSWORD,
        nickname: '우주',
        agreements: AGREEMENTS,
      }).expect(201);
      const [row] = await t.dataSource.query('SELECT password_hash FROM users WHERE id = $1', [
        res.body.data.userId,
      ]);
      expect(row.password_hash).toMatch(/^\$2b\$12\$/);
    });

    it('TC-AUTH-02: 동의 하나라도 누락 → 422 E4002, 행 없음', async () => {
      const res = await signup({
        email: uniqueEmail(),
        password: PASSWORD,
        nickname: '우주',
        agreements: { ...AGREEMENTS, over14: false },
      }).expect(422);
      expect(res.body.error).toMatchObject({
        code: 'E4002',
        detail: { fields: [{ field: 'agreements.over14', reason: 'invalid_value' }] },
      });
      expect((await t.dataSource.query('SELECT COUNT(*) FROM users'))[0].count).toBe('0');
    });

    it('TC-AUTH-03: 대소문자만 다른 이메일 → 409 E1004', async () => {
      await signup({
        email: 'dup@example.com',
        password: PASSWORD,
        nickname: '우주',
        agreements: AGREEMENTS,
      }).expect(201);
      const res = await signup({
        email: 'DUP@Example.com',
        password: PASSWORD,
        nickname: '우주',
        agreements: AGREEMENTS,
      }).expect(409);
      expect(res.body.error.code).toBe('E1004');
    });

    it('비밀번호 규칙 위반 → 422 E4002 (detail에 password)', async () => {
      const res = await signup({
        email: uniqueEmail(),
        password: 'onlyletters',
        nickname: '우주',
        agreements: AGREEMENTS,
      }).expect(422);
      expect(res.body.error.detail.fields).toContainEqual({
        field: 'password',
        reason: 'invalid_format',
      });
    });
  });

  describe('로그인', () => {
    it('TC-AUTH-04: 200, 액세스 토큰, httpOnly 쿠키 속성 전부. X-Client 없으면 본문에 refreshToken 없음', async () => {
      const user = await createUser(t.dataSource);
      const res = await t
        .http()
        .post('/api/v1/auth/login')
        .set('CF-Connecting-IP', randomIp())
        .send({ email: user.email, password: PASSWORD })
        .expect(200);
      expect(res.body.data).toEqual({
        accessToken: expect.any(String),
        expiresIn: 3600,
        user: { id: user.id, email: user.email, nickname: user.nickname },
      });
      const cookie = (res.headers['set-cookie'] as unknown as string[])[0];
      expect(cookie).toBe(`refresh_token=${refreshCookieOf(res)}; ${COOKIE_ATTRS}`);
    });

    it('비밀번호 불일치·없는 사용자 모두 401 E1003 (E1001과 섞지 않음)', async () => {
      const user = await createUser(t.dataSource);
      for (const body of [
        { email: user.email, password: 'Wrong!2026' },
        { email: 'ghost@example.com', password: PASSWORD },
      ]) {
        const res = await t
          .http()
          .post('/api/v1/auth/login')
          .set('CF-Connecting-IP', randomIp())
          .send(body)
          .expect(401);
        expect(res.body.error.code).toBe('E1003');
        expect(res.headers['set-cookie']).toBeUndefined();
      }
    });

    it('TC-SEC-04: 같은 CF-Connecting-IP 11번째 → 429 E4004 login_rate + Retry-After, 다른 IP는 정상', async () => {
      const ip = randomIp();
      const attempt = (from: string) =>
        t
          .http()
          .post('/api/v1/auth/login')
          .set('CF-Connecting-IP', from)
          .send({ email: 'x@example.com', password: 'Wrong!2026' });
      for (let i = 0; i < 10; i++) await attempt(ip).expect(401);
      const blocked = await attempt(ip).expect(429);
      expect(blocked.body.error).toMatchObject({ code: 'E4004', detail: { reason: 'login_rate' } });
      expect(Number(blocked.headers['retry-after'])).toBeGreaterThan(0);
      await attempt(randomIp()).expect(401);
    });
  });

  describe('refresh·logout', () => {
    it('TC-AUTH-05: 만료 토큰 → 401 E1001, refresh 후 원 요청 성공', async () => {
      const me = await userWithToken(t);
      const jwt = t.app.get(JwtService, { strict: false });
      const expired = jwt.sign(
        { sub: me.user.id, exp: Math.floor(Date.now() / 1000) - 60 },
        { secret: t.config.jwt.accessSecret },
      );
      const res = await t
        .http()
        .get('/api/v1/users/me')
        .set('Authorization', `Bearer ${expired}`)
        .expect(401);
      expect(res.body.error.code).toBe('E1001');

      const refreshed = await t
        .http()
        .post('/api/v1/auth/refresh')
        .set('Cookie', `refresh_token=${me.refreshCookie}`)
        .expect(200);
      await t
        .http()
        .get('/api/v1/users/me')
        .set('Authorization', `Bearer ${refreshed.body.data.accessToken}`)
        .expect(200);
    });

    it('위조·형식 오류 토큰, 토큰 없음 → 401 E1001', async () => {
      for (const header of ['Bearer abc.def.ghi', 'Basic xyz', undefined]) {
        const req = t.http().get('/api/v1/users/me');
        const res = await (header ? req.set('Authorization', header) : req).expect(401);
        expect(res.body.error.code).toBe('E1001');
      }
    });

    it('refresh는 새 토큰으로 교체하고 이전 토큰에 replaced_by를 남긴다', async () => {
      const me = await userWithToken(t);
      const res = await t
        .http()
        .post('/api/v1/auth/refresh')
        .set('Cookie', `refresh_token=${me.refreshCookie}`)
        .expect(200);
      expect(refreshCookieOf(res)).not.toBe(me.refreshCookie);
      const rows = await refreshTokensOf(me.user.id);
      expect(rows).toHaveLength(2);
      expect(rows[0].revoked_at).not.toBeNull();
      expect(rows[0].replaced_by).toBe(rows[1].id);
    });

    it('(추가) 교체된 토큰 재사용 → 401 E1001 + 그 사용자 토큰 전부 폐기', async () => {
      const me = await userWithToken(t);
      const rotated = await t
        .http()
        .post('/api/v1/auth/refresh')
        .set('Cookie', `refresh_token=${me.refreshCookie}`)
        .expect(200);
      const reuse = await t
        .http()
        .post('/api/v1/auth/refresh')
        .set('Cookie', `refresh_token=${me.refreshCookie}`)
        .expect(401);
      expect(reuse.body.error.code).toBe('E1001');
      expect((reuse.headers['set-cookie'] as unknown as string[])[0]).toContain('Max-Age=0');
      // 새로 받은 토큰도 폐기됐다
      await t
        .http()
        .post('/api/v1/auth/refresh')
        .set('Cookie', `refresh_token=${refreshCookieOf(rotated)}`)
        .expect(401);
      expect((await refreshTokensOf(me.user.id)).every((r) => r.revoked_at !== null)).toBe(true);
    });

    it('TC-AUTH-06: 로그아웃 204 + 쿠키 삭제, 이전 토큰으로 refresh → 401 E1001', async () => {
      const me = await userWithToken(t);
      const res = await t
        .http()
        .post('/api/v1/auth/logout')
        .set(me.auth)
        .set('Cookie', `refresh_token=${me.refreshCookie}`)
        .expect(204);
      expect(res.text).toBe('');
      expect((res.headers['set-cookie'] as unknown as string[])[0]).toBe(
        'refresh_token=; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=0',
      );
      const after = await t
        .http()
        .post('/api/v1/auth/refresh')
        .set('Cookie', `refresh_token=${me.refreshCookie}`)
        .expect(401);
      expect(after.body.error.code).toBe('E1001');
      // 로그아웃 폐기는 재사용이 아니다 → replaced_by NULL
      expect((await refreshTokensOf(me.user.id))[0].replaced_by).toBeNull();
    });

    it('쿠키 없이 로그아웃해도 204', async () => {
      const me = await userWithToken(t);
      await t.http().post('/api/v1/auth/logout').set(me.auth).expect(204);
    });

    it('refresh 쿠키·본문 모두 없음 → 401 E1001 + 쿠키 삭제 헤더', async () => {
      const res = await t.http().post('/api/v1/auth/refresh').expect(401);
      expect(res.body.error.code).toBe('E1001');
      expect((res.headers['set-cookie'] as unknown as string[])[0]).toContain('Max-Age=0');
    });
  });

  describe('[앱 협의] X-Client: ios — 본문 refreshToken (TRD §9.1)', () => {
    const iosLogin = async () => {
      const user = await createUser(t.dataSource);
      const res = await t
        .http()
        .post('/api/v1/auth/login')
        .set('X-Client', 'ios')
        .set('CF-Connecting-IP', randomIp())
        .send({ email: user.email, password: PASSWORD })
        .expect(200);
      return { user, res, refreshToken: res.body.data.refreshToken as string };
    };

    it('로그인 응답 data에 refreshToken (쿠키와 같은 값), 쿠키도 그대로', async () => {
      const { res, refreshToken } = await iosLogin();
      expect(Object.keys(res.body.data)).toEqual([
        'accessToken',
        'expiresIn',
        'refreshToken',
        'user',
      ]);
      expect(refreshToken).toBe(refreshCookieOf(res));
    });

    it('쿠키 없이 본문 토큰으로 refresh → 새 refreshToken, 이전 본문 토큰 재사용 → 401 + 전부 폐기', async () => {
      const { user, refreshToken } = await iosLogin();
      const res = await t
        .http()
        .post('/api/v1/auth/refresh')
        .set('X-Client', 'ios')
        .send({ refreshToken })
        .expect(200);
      const next = res.body.data.refreshToken as string;
      expect(next).toEqual(expect.any(String));
      expect(next).not.toBe(refreshToken);

      await t.http().post('/api/v1/auth/refresh').send({ refreshToken }).expect(401);
      await t.http().post('/api/v1/auth/refresh').send({ refreshToken: next }).expect(401);
      expect((await refreshTokensOf(user.id)).every((r) => r.revoked_at !== null)).toBe(true);
    });

    it('X-Client 없이 본문 토큰으로 refresh하면 동작하되 응답에 refreshToken 없음', async () => {
      const { refreshToken } = await iosLogin();
      const res = await t.http().post('/api/v1/auth/refresh').send({ refreshToken }).expect(200);
      expect(res.body.data).not.toHaveProperty('refreshToken');
    });

    it('쿠키·본문 둘 다 있으면 쿠키 우선', async () => {
      const a = await iosLogin();
      const b = await iosLogin();
      const res = await t
        .http()
        .post('/api/v1/auth/refresh')
        .set('Cookie', `refresh_token=${a.refreshToken}`)
        .send({ refreshToken: b.refreshToken })
        .expect(200);
      expect(res.body.data.user.id).toBe(a.user.id);
    });

    it('본문 토큰으로 로그아웃 → 그 토큰 폐기', async () => {
      const { res, refreshToken } = await iosLogin();
      await t
        .http()
        .post('/api/v1/auth/logout')
        .set('Authorization', `Bearer ${res.body.data.accessToken}`)
        .send({ refreshToken })
        .expect(204);
      await t.http().post('/api/v1/auth/refresh').send({ refreshToken }).expect(401);
    });

    it('본문 refreshToken이 문자열이 아니면 422 E4002', async () => {
      const res = await t
        .http()
        .post('/api/v1/auth/refresh')
        .send({ refreshToken: 123 })
        .expect(422);
      expect(res.body.error.code).toBe('E4002');
    });
  });

  describe('사용자', () => {
    it('GET /users/me', async () => {
      const me = await userWithToken(t);
      await insertDoneAnalyses(t.dataSource.manager, me.user.id, 2);
      const res = await t.http().get('/api/v1/users/me').set(me.auth).expect(200);
      expect(res.body.data).toEqual({
        id: me.user.id,
        email: me.user.email,
        nickname: me.user.nickname,
        createdAt: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/),
        analysisCount: 2,
      });
    });

    it('TC-AUTH-07: 분석 3건 계정 탈퇴 → 6개 테이블 모두 0건, 남은 토큰은 401 E1001', async () => {
      const me = await userWithToken(t);
      await insertDoneAnalyses(t.dataSource.manager, me.user.id, 3);
      const res = await t.http().delete('/api/v1/users/me').set(me.auth).expect(204);
      expect((res.headers['set-cookie'] as unknown as string[])[0]).toContain('Max-Age=0');
      for (const table of [
        'users',
        'refresh_tokens',
        'analyses',
        'skin_metrics',
        'surveys',
        'recommendations',
      ]) {
        const [{ count }] = await t.dataSource.query(`SELECT COUNT(*) FROM ${table}`);
        expect([table, count]).toEqual([table, '0']);
      }
      const after = await t.http().get('/api/v1/users/me').set(me.auth).expect(401);
      expect(after.body.error.code).toBe('E1001');
    });
  });

  it('로그인한 다른 사용자 데이터는 보존 (탈퇴 범위 확인)', async () => {
    const a = await userWithToken(t);
    const b = await login(t, await createUser(t.dataSource));
    await t.http().delete('/api/v1/users/me').set(a.auth).expect(204);
    await t.http().get('/api/v1/users/me').set(b.auth).expect(200);
  });
});
