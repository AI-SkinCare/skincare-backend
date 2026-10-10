import { insertDoneAnalyses } from '../../src/database/seeds/seed-data';
import { gifHeader, headerOnlyJpeg, heicHeader, solidPng } from '../fixtures/images';
import { DEFAULT_SURVEY, submit, submitOk, userWithToken } from './helpers/factories';
import { TestApp, createTestApp } from './helpers/test-app';

describe('분석 생성·조회 (TRD §10.1·10.2)', () => {
  let t: TestApp;
  beforeAll(async () => {
    t = await createTestApp();
  });
  afterAll(() => t.close());
  beforeEach(() => t.truncate());

  const count = async (table: string) =>
    Number((await t.dataSource.query(`SELECT COUNT(*) FROM ${table}`))[0].count);

  it('TC-ANA-01: 202, queued, queuePosition ≥ 1, analyses·skin_metrics·surveys 저장', async () => {
    const me = await userWithToken(t);
    const res = await submit(t, me).expect(202);
    expect(res.body).toEqual({
      success: true,
      data: {
        analysisId: expect.stringMatching(/^[0-9A-HJKMNP-TV-Z]{26}$/),
        status: 'queued',
        queuePosition: 1,
        estimatedWaitSec: 55,
      },
    });
    const id = res.body.data.analysisId as string;
    const [row] = await t.dataSource.query('SELECT * FROM analyses WHERE id = $1', [id]);
    expect(row).toMatchObject({
      user_id: String(me.user.id),
      status: 'queued',
      device_type: 'smartphone',
      is_demo: true,
      attempt_count: 0,
    });
    expect(row.measure_ms).toEqual(expect.any(Number));
    expect(await count('skin_metrics')).toBeGreaterThan(0);
    const [survey] = await t.dataSource.query('SELECT * FROM surveys WHERE analysis_id = $1', [id]);
    expect(survey).toMatchObject({ gender: 'female', age_band: '20s', skin_type: 'combination' });
    expect(survey.payload).toEqual(DEFAULT_SURVEY);
  });

  it('JPEG도 받는다 (시그니처로 판별, 파일명·mimetype 무관)', async () => {
    const me = await userWithToken(t);
    await submit(t, me, { image: headerOnlyJpeg(1080, 1920), filename: 'photo.heic' }).expect(202);
  });

  it('GET /analyses/{id}: queued면 측정 결과 + recommendation pending + 순번', async () => {
    const me = await userWithToken(t);
    const id = await submitOk(t, me);
    const res = await t.http().get(`/api/v1/analyses/${id}`).set(me.auth).expect(200);
    expect(res.body.data).toMatchObject({
      analysisId: id,
      status: 'queued',
      completedAt: null,
      deviceType: 'smartphone',
      queuePosition: 1,
      estimatedWaitSec: 55,
      measurement: {
        isDemo: true,
        confidence: 0,
        calibrationApplied: false,
        modelVersion: 'skin_reg_stub',
        byRoi: [],
      },
      recommendation: { status: 'pending' },
      survey: DEFAULT_SURVEY,
      error: null,
    });
    // fixture full_face (0.697, 0.493, 0.434, 0.658) → overall round(52.45) = 52
    expect(res.body.data.measurement.overallScore).toBe(52);
    expect(res.body.data.measurement.summary).toEqual([
      { metric: 'moisture', value: 70, score: 70, grade: 'good' },
      { metric: 'elasticity', value: 49, score: 49, grade: 'normal' },
      { metric: 'pigmentation', value: 43, score: 57, grade: 'normal' },
      { metric: 'pore', value: 66, score: 34, grade: 'care' },
    ]);
  });

  it('TC-ANA-02: gender 누락 → 422 E4002, detail에 survey.gender, 행 없음', async () => {
    const me = await userWithToken(t);
    const { gender: _omit, ...withoutGender } = DEFAULT_SURVEY;
    const res = await submit(t, me, { survey: withoutGender }).expect(422);
    expect(res.body.error).toEqual({
      code: 'E4002',
      message: '입력값을 확인해 주세요.',
      detail: { fields: [{ field: 'survey.gender', reason: 'required' }] },
    });
    expect(await count('analyses')).toBe(0);
  });

  it.each([
    ['survey JSON 파싱 실패', { survey: '{not json' }, 'survey', 'invalid_format'],
    ['survey 누락', { survey: null }, 'survey', 'required'],
    ['deviceType 누락', { deviceType: null }, 'deviceType', 'required'],
    ['deviceType 값 오류', { deviceType: 'webcam' }, 'deviceType', 'invalid_value'],
    [
      '고민 4개',
      { survey: { ...DEFAULT_SURVEY, concerns: ['pore', 'acne', 'wrinkle', 'redness'] } },
      'survey.concerns',
      'too_many',
    ],
  ])('%s → 422 E4002', async (_name, options, field, reason) => {
    const me = await userWithToken(t);
    const res = await submit(t, me, options as Parameters<typeof submit>[2]).expect(422);
    expect(res.body.error.code).toBe('E4002');
    expect(res.body.error.detail.fields).toContainEqual({ field, reason });
  });

  it('image 없음 → 422 E4002 (field image)', async () => {
    const me = await userWithToken(t);
    const res = await t
      .http()
      .post('/api/v1/analyses')
      .set(me.auth)
      .field('survey', JSON.stringify(DEFAULT_SURVEY))
      .field('deviceType', 'smartphone')
      .expect(422);
    expect(res.body.error.detail.fields).toContainEqual({ field: 'image', reason: 'required' });
  });

  it('TC-ANA-03: no-face → 422 E2001, analyses 행 없음', async () => {
    const me = await userWithToken(t);
    const res = await submit(t, me, { scenario: 'no-face' }).expect(422);
    expect(res.body.error).toMatchObject({
      code: 'E2001',
      message: '얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요.',
    });
    expect(await count('analyses')).toBe(0);
  });

  it('TC-ANA-07: 11MB → 413 E2003', async () => {
    const me = await userWithToken(t);
    const res = await submit(t, me, {
      image: headerOnlyJpeg(1080, 1440, 11 * 1024 * 1024),
      filename: 'big.jpg',
    }).expect(413);
    expect(res.body.error.code).toBe('E2003');
    expect(await count('analyses')).toBe(0);
  });

  it.each([
    ['TC-ANA-07: GIF', gifHeader()],
    ['HEIC (앱이 JPEG로 바꿔 보내야 함)', heicHeader()],
  ])('%s → 415 E2004', async (_name, image) => {
    const me = await userWithToken(t);
    const res = await submit(t, me, { image, filename: 'x.jpg' }).expect(415);
    expect(res.body.error).toMatchObject({
      code: 'E2004',
      message: 'JPEG 또는 PNG 사진만 올릴 수 있어요.',
    });
  });

  it('짧은 변 < 720px → 422 E2002 (해상도 문구)', async () => {
    const me = await userWithToken(t);
    const res = await submit(t, me, { image: solidPng(600, 600) }).expect(422);
    expect(res.body.error).toMatchObject({
      code: 'E2002',
      message: '사진 해상도가 낮아요. 다시 찍어 주세요.',
    });
  });

  it('TC-ANA-06: 진행 중 분석이 있으면 429 E4004 in_progress (Retry-After 30)', async () => {
    const me = await userWithToken(t);
    await submitOk(t, me);
    const res = await submit(t, me).expect(429);
    expect(res.body.error).toMatchObject({ code: 'E4004', detail: { reason: 'in_progress' } });
    expect(res.headers['retry-after']).toBe('30');
  });

  it('처리 중 제출 잠금: 측정 중 동시에 두 번 제출하면 하나만 202', async () => {
    const me = await userWithToken(t);
    const results = await Promise.all([submit(t, me), submit(t, me)]);
    expect(results.map((r) => r.status).sort()).toEqual([202, 429]);
  });

  it('일일 한도(KST): 오늘 20건이면 429 E4004 daily_limit, Retry-After = 다음 KST 자정까지', async () => {
    const me = await userWithToken(t);
    await insertDoneAnalyses(t.dataSource.manager, me.user.id, 20);
    await t.dataSource.query('UPDATE analyses SET created_at = now()');
    const res = await submit(t, me).expect(429);
    expect(res.body.error).toMatchObject({ code: 'E4004', detail: { reason: 'daily_limit' } });
    expect(Number(res.headers['retry-after'])).toBeGreaterThan(0);
    expect(Number(res.headers['retry-after'])).toBeLessThanOrEqual(86400);
  });

  it('토큰 없음 → 401 E1001 (요청 제한보다 먼저)', async () => {
    const res = await t.http().post('/api/v1/analyses').expect(401);
    expect(res.body.error.code).toBe('E1001');
  });

  describe('TC-SEC-01: IDOR — 남의 분석은 없는 것과 똑같이 404 E4003', () => {
    it('조회·삭제·재요청·추이', async () => {
      const a = await userWithToken(t);
      const b = await userWithToken(t);
      const [bId] = await insertDoneAnalyses(t.dataSource.manager, b.user.id, 1);
      const requests = [
        () => t.http().get(`/api/v1/analyses/${bId}`),
        () => t.http().delete(`/api/v1/analyses/${bId}`),
        () => t.http().post(`/api/v1/analyses/${bId}/recommendation/retry`),
        () => t.http().get(`/api/v1/analyses/${bId}/trend`),
      ];
      for (const req of requests) {
        const res = await req().set(a.auth).expect(404);
        expect(res.body.error).toEqual({
          code: 'E4003',
          message: '결과를 찾을 수 없어요.',
          detail: null,
        });
      }
      // B의 분석은 그대로
      await t.http().get(`/api/v1/analyses/${bId}`).set(b.auth).expect(200);
    });

    it('ULID 형식이 아닌 id·없는 id도 404 E4003 (422 아님)', async () => {
      const me = await userWithToken(t);
      for (const id of ['abc', '01J9Z3K5V8Q2M4N6P8R0S2T4VU', '01J9Z3K5V8Q2M4N6P8R0S2T4V6']) {
        const res = await t.http().get(`/api/v1/analyses/${id}`).set(me.auth).expect(404);
        expect(res.body.error.code).toBe('E4003');
      }
    });
  });
});
