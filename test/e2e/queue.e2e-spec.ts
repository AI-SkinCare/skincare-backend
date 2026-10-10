import { FixtureScenarioStore } from '../../src/ai-client/fixture-scenario.store';
import {
  FIXTURE_REC_DELAY_MS,
  FIXTURE_REC_SLOW_DELAY_MS,
} from '../../src/ai-client/fixture-ai-client';
import { WORKER_RETRY_DELAY_MS } from '../../src/config/constants';
import { createUser, login, statusOf, submit, submitOk, userWithToken } from './helpers/factories';
import { TestApp, createTestApp } from './helpers/test-app';

/** 조건이 참이 될 때까지 짧게 기다린다 (워커를 백그라운드로 돌린 테스트용) */
async function until(check: () => Promise<boolean>, timeoutMs = 3000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!(await check())) {
    if (Date.now() > deadline) throw new Error('조건을 기다리다 시간 초과');
    await new Promise((r) => setTimeout(r, 10));
  }
}

describe('추천 큐·워커 (TRD §13)', () => {
  let t: TestApp;
  beforeAll(async () => {
    t = await createTestApp();
  });
  afterAll(() => t.close());
  beforeEach(async () => {
    t.sleep.releaseSlow();
    t.sleep.calls.length = 0;
    await t.truncate();
  });

  const analysisRow = async (id: string) =>
    (await t.dataSource.query('SELECT * FROM analyses WHERE id = $1', [id]))[0];

  it('TC-ANA-04: queued → recommending → done, 완료 시 추천 필드 모두', async () => {
    t.sleep.holdSlow();
    const me = await userWithToken(t);
    const id = await submitOk(t, me, { scenario: 'rec-slow' });
    expect(await statusOf(t, id)).toBe('queued');

    const tick = t.worker.tick();
    await until(async () => (await statusOf(t, id)) === 'recommending');
    const during = await t.http().get(`/api/v1/analyses/${id}`).set(me.auth).expect(200);
    expect(during.body.data).toMatchObject({
      status: 'recommending',
      queuePosition: null,
      estimatedWaitSec: null,
      recommendation: { status: 'pending' },
    });

    t.sleep.releaseSlow();
    await tick;
    expect(t.sleep.calls).toContain(FIXTURE_REC_SLOW_DELAY_MS);
    const done = await t.http().get(`/api/v1/analyses/${id}`).set(me.auth).expect(200);
    expect(done.body.data.status).toBe('done');
    expect(done.body.data.completedAt).toMatch(/Z$/);
    expect(Object.keys(done.body.data.recommendation).sort()).toEqual([
      'answer',
      'concerns',
      'disclaimer',
      'filtered',
      'ingredients',
      'inputNotes',
      'modelVersion',
      'reasoning',
      'routine',
      'skinType',
      'status',
      'usedFallback',
    ]);
    expect(done.body.data.recommendation).toMatchObject({
      status: 'done',
      skinType: { code: 'combination', label: '복합성' },
      concerns: [
        { code: 'pore', label: '모공' },
        { code: 'pigmentation', label: '미백(색소침착/기미/칙칙함)' },
      ],
      modelVersion: 'rec_sllm_v2',
    });
    const row = await analysisRow(id);
    expect(row).toMatchObject({ attempt_count: 1, error_code: null });
    expect(row.queue_wait_ms).toEqual(expect.any(Number));
    expect(row.recommend_ms).toEqual(expect.any(Number));
    expect(row.rec_started_at).toBeInstanceOf(Date);
  });

  it('TC-ANA-09: 데모 측정(is_stub) → 추천 요청 metrics: null, 설문은 라벨로', async () => {
    const me = await userWithToken(t);
    const id = await submitOk(t, me);
    expect((await analysisRow(id)).is_demo).toBe(true);
    const ai = t.fixtureAi();
    ai.recommendRequests.length = 0;
    await t.worker.tick();
    expect(ai.recommendRequests).toHaveLength(1);
    expect(ai.recommendRequests[0].metrics).toBeNull();
    expect(ai.recommendRequests[0].survey).toMatchObject({
      skinType: '복합성',
      selfReportedConcerns: ['모공', '미백(색소침착/기미/칙칙함)'],
    });
    expect(ai.recommendRequests[0].survey).not.toHaveProperty('concerns');
    expect(t.sleep.calls).toEqual([FIXTURE_REC_DELAY_MS]);
  });

  it('데모가 아닌 측정은 저장된 모든 ROI를 0–1 그대로 보낸다', async () => {
    const me = await userWithToken(t);
    const id = await submitOk(t, me);
    await t.dataSource.query('UPDATE analyses SET is_demo = false WHERE id = $1', [id]);
    const ai = t.fixtureAi();
    ai.recommendRequests.length = 0;
    await t.worker.tick();
    expect(ai.recommendRequests[0].metrics).toEqual({
      full_face: { moisture: 0.697, elasticity: 0.493, pigmentation: 0.434, pore: 0.658 },
    });
  });

  it('TC-ANA-05: rec-timeout → failed E3002 (attempt 1, 재시도 없음), 측정 유지 → ok로 재요청 → done', async () => {
    const me = await userWithToken(t);
    const id = await submitOk(t, me, { scenario: 'rec-timeout' });
    await t.worker.tick();
    expect(await analysisRow(id)).toMatchObject({
      status: 'failed',
      error_code: 'E3002',
      attempt_count: 1,
    });
    expect(t.sleep.calls).not.toContain(WORKER_RETRY_DELAY_MS);

    const failed = await t.http().get(`/api/v1/analyses/${id}`).set(me.auth).expect(200);
    expect(failed.body.data).toMatchObject({
      status: 'failed',
      recommendation: { status: 'failed' },
      error: { code: 'E3002', message: '추천을 만들지 못했어요. 다시 시도해 주세요.' },
      measurement: { overallScore: 52 },
    });

    const retry = await t
      .http()
      .post(`/api/v1/analyses/${id}/recommendation/retry`)
      .set(me.auth)
      .set('X-Fixture-Scenario', 'ok')
      .expect(202);
    expect(retry.body.data).toEqual({
      analysisId: id,
      status: 'queued',
      queuePosition: 1,
      estimatedWaitSec: 55,
    });
    expect(await analysisRow(id)).toMatchObject({
      status: 'queued',
      error_code: null,
      completed_at: null,
      recommend_ms: null,
    });

    await t.worker.tick();
    expect(await analysisRow(id)).toMatchObject({ status: 'done', attempt_count: 2 });
  });

  it('재요청은 failed일 때만 — done이면 409 E4005', async () => {
    const me = await userWithToken(t);
    const id = await submitOk(t, me);
    await t.worker.tick();
    const res = await t
      .http()
      .post(`/api/v1/analyses/${id}/recommendation/retry`)
      .set(me.auth)
      .expect(409);
    expect(res.body.error.code).toBe('E4005');
  });

  it('연결 오류는 5초 후 1회 재시도(attempt +1) → 실패하면 failed E3001', async () => {
    const me = await userWithToken(t);
    const id = await submitOk(t, me);
    t.app.get(FixtureScenarioStore).remember(id, 'ai-down');
    await t.worker.tick();
    expect(t.sleep.calls).toEqual([WORKER_RETRY_DELAY_MS]);
    expect(await analysisRow(id)).toMatchObject({
      status: 'failed',
      error_code: 'E3001',
      attempt_count: 2,
    });
  });

  it('TC-ANA-08: recommending 중 재시작(복구 함수) → queued(queued_at 유지) → done', async () => {
    const me = await userWithToken(t);
    const id = await submitOk(t, me);
    const before = await analysisRow(id);
    await t.dataSource.query(
      `UPDATE analyses SET status = 'recommending', rec_started_at = now() WHERE id = $1`,
      [id],
    );

    expect(await t.maintenance.recoverInterrupted()).toBe(1);
    const recovered = await analysisRow(id);
    expect(recovered.status).toBe('queued');
    expect(recovered.queued_at).toEqual(before.queued_at);

    await t.worker.tick();
    expect(await statusOf(t, id)).toBe('done');
  });

  it('처리 중 삭제(§13.7): 결과를 버리고 FK 오류 없이 끝난다', async () => {
    t.sleep.holdSlow();
    const me = await userWithToken(t);
    const id = await submitOk(t, me, { scenario: 'rec-slow' });
    const tick = t.worker.tick();
    await until(async () => (await statusOf(t, id)) === 'recommending');
    await t.http().delete(`/api/v1/analyses/${id}`).set(me.auth).expect(204);
    t.sleep.releaseSlow();
    await tick;
    const [{ count }] = await t.dataSource.query('SELECT COUNT(*) FROM recommendations');
    expect(count).toBe('0');
    expect(t.worker.isBusy).toBe(false);
  });

  it('장기 대기: queued가 60분을 넘으면 failed E3001', async () => {
    const me = await userWithToken(t);
    const id = await submitOk(t, me);
    await t.dataSource.query(
      `UPDATE analyses SET queued_at = now() - interval '61 minutes' WHERE id = $1`,
      [id],
    );
    expect(await t.maintenance.expireStale()).toBe(1);
    expect(await analysisRow(id)).toMatchObject({ status: 'failed', error_code: 'E3001' });
  });

  it('AI 비가용이면 워커는 큐를 건드리지 않는다', async () => {
    const me = await userWithToken(t);
    const id = await submitOk(t, me);
    t.aiHealth.markUnavailable('test');
    await t.worker.tick();
    expect(await statusOf(t, id)).toBe('queued');
    await t.aiHealth.check();
  });

  it('대기 순번·예상 대기: 오래된 순, 평균 추천 시간 반영', async () => {
    const users = await Promise.all([1, 2, 3].map(() => userWithToken(t)));
    const ids: string[] = [];
    for (const u of users) ids.push(await submitOk(t, u));
    const third = await t.http().get(`/api/v1/analyses/${ids[2]}`).set(users[2].auth).expect(200);
    expect(third.body.data).toMatchObject({ queuePosition: 3, estimatedWaitSec: 165 });
    const status = await t.http().get('/api/v1/system/status').expect(200);
    expect(status.body.data).toMatchObject({
      aiAvailable: true,
      queueLength: 3,
      estimatedWaitSec: 165,
    });
  });

  it('TC-PERF-04: rec-slow로 1건 처리 중 + 대기 10건 → 12번째부터 503 E3003 (Retry-After 60)', async () => {
    t.sleep.holdSlow();
    const accounts = await Promise.all(Array.from({ length: 15 }, () => createUser(t.dataSource)));
    const sessions = [];
    for (const account of accounts) sessions.push(await login(t, account));

    const first = await submitOk(t, sessions[0], { scenario: 'rec-slow' });
    const tick = t.worker.tick();
    await until(async () => (await statusOf(t, first)) === 'recommending');

    const statuses: number[] = [];
    for (const session of sessions.slice(1)) {
      const res = await submit(t, session);
      statuses.push(res.status);
      if (res.status === 503) {
        expect(res.body.error.code).toBe('E3003');
        expect(res.headers['retry-after']).toBe('60');
      }
    }
    // 2–11번째 계정: 202 (대기 10건), 12–15번째: 503
    expect(statuses).toEqual([...Array(10).fill(202), ...Array(4).fill(503)]);

    t.sleep.releaseSlow();
    await tick;
  });
});
