import { insertDoneAnalyses } from '../../src/database/seeds/seed-data';
import { LoggedIn, submitOk, userWithToken } from './helpers/factories';
import { TestApp, createTestApp } from './helpers/test-app';

describe('이력·추이·삭제 (TRD §10.4–10.6)', () => {
  let t: TestApp;
  let me: LoggedIn;
  let ids: string[];

  beforeAll(async () => {
    t = await createTestApp();
  });
  afterAll(() => t.close());
  beforeEach(async () => {
    await t.truncate();
    me = await userWithToken(t);
    // 시드와 같은 생성기 — 0번이 최신
    ids = await insertDoneAnalyses(t.dataSource.manager, me.user.id, 25);
  });

  const list = (query = '') => t.http().get(`/api/v1/analyses${query}`).set(me.auth);
  const trend = (id: string) => t.http().get(`/api/v1/analyses/${id}/trend`).set(me.auth);

  it('TC-HIS-01: 1쪽 20건, 2쪽 5건, 최신순', async () => {
    const page1 = await list('?page=1&size=20').expect(200);
    expect(page1.body.data.page).toEqual({ page: 1, size: 20, totalItems: 25, totalPages: 2 });
    expect(page1.body.data.items.map((i: { analysisId: string }) => i.analysisId)).toEqual(
      ids.slice(0, 20),
    );
    expect(page1.body.data.items[0]).toEqual({
      analysisId: ids[0],
      createdAt: expect.stringMatching(/Z$/),
      status: 'done',
      overallScore: expect.any(Number),
      isDemo: false,
      concerns: ['pore', 'pigmentation'],
    });
    const page2 = await list('?page=2&size=20').expect(200);
    expect(page2.body.data.items.map((i: { analysisId: string }) => i.analysisId)).toEqual(
      ids.slice(20),
    );
  });

  it('기본값 page=1·size=20, 홈 화면은 size=1로 최근 1건', async () => {
    expect((await list().expect(200)).body.data.page).toEqual({
      page: 1,
      size: 20,
      totalItems: 25,
      totalPages: 2,
    });
    expect((await list('?size=1').expect(200)).body.data.items).toHaveLength(1);
  });

  it('추천 전이면 concerns는 []', async () => {
    await t.truncate();
    me = await userWithToken(t);
    await submitOk(t, me);
    expect((await list().expect(200)).body.data.items[0]).toMatchObject({
      status: 'queued',
      concerns: [],
      isDemo: true,
    });
  });

  it('0건이면 totalPages 0', async () => {
    const other = await userWithToken(t);
    const res = await t.http().get('/api/v1/analyses').set(other.auth).expect(200);
    expect(res.body.data).toEqual({
      items: [],
      page: { page: 1, size: 20, totalItems: 0, totalPages: 0 },
    });
  });

  it.each(['?page=0', '?size=0', '?size=51', '?page=abc', '?page=1.5'])(
    '%s → 422 E4002',
    async (query) => {
      const res = await list(query).expect(422);
      expect(res.body.error.code).toBe('E4002');
    },
  );

  it('TC-HIS-02: 직전 done·비데모 분석과 비교, delta = current − previous', async () => {
    const res = await trend(ids[0]).expect(200);
    const { current, previous, delta } = res.body.data;
    expect(current.analysisId).toBe(ids[0]);
    expect(previous.analysisId).toBe(ids[1]);
    expect(current.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(delta).toEqual({
      overallScore: current.overallScore - previous.overallScore,
      moisture: current.summary.moisture - previous.summary.moisture,
      elasticity: current.summary.elasticity - previous.summary.elasticity,
      pigmentation: current.summary.pigmentation - previous.summary.pigmentation,
      pore: current.summary.pore - previous.summary.pore,
    });
  });

  it('TC-HIS-02: 이전 분석이 없으면 previous·delta null', async () => {
    expect((await trend(ids[24]).expect(200)).body.data).toMatchObject({
      previous: null,
      delta: null,
    });
  });

  it('데모·미완료 분석은 previous로 고르지 않는다, 현재가 데모면 null', async () => {
    await t.dataSource.query(`UPDATE analyses SET is_demo = true WHERE id = $1`, [ids[1]]);
    await t.dataSource.query(`UPDATE analyses SET status = 'failed' WHERE id = $1`, [ids[2]]);
    expect((await trend(ids[0]).expect(200)).body.data.previous.analysisId).toBe(ids[3]);
    expect((await trend(ids[1]).expect(200)).body.data).toMatchObject({
      previous: null,
      delta: null,
    });
  });

  it('TC-HIS-03: 삭제 204 → 목록·추이에서 제외, 다시 조회·삭제하면 404', async () => {
    const res = await t.http().delete(`/api/v1/analyses/${ids[1]}`).set(me.auth).expect(204);
    expect(res.text).toBe('');
    const page = (await list('?size=50').expect(200)).body.data;
    expect(page.page.totalItems).toBe(24);
    expect(page.items.map((i: { analysisId: string }) => i.analysisId)).not.toContain(ids[1]);
    expect((await trend(ids[0]).expect(200)).body.data.previous.analysisId).toBe(ids[2]);
    await t.http().get(`/api/v1/analyses/${ids[1]}`).set(me.auth).expect(404);
    await t.http().delete(`/api/v1/analyses/${ids[1]}`).set(me.auth).expect(404);
    const [{ count }] = await t.dataSource.query(
      'SELECT COUNT(*) FROM skin_metrics WHERE analysis_id = $1',
      [ids[1]],
    );
    expect(count).toBe('0');
  });
});
