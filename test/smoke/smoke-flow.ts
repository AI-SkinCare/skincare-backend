// 실행 중인 백엔드에 대한 전체 흐름 스모크 (M3 배포 서버 + 실제 AI 연동 점검용).
//
//   SMOKE_BASE_URL=https://app.<도메인>/api/v1 SMOKE_EMAIL=test02@skincare.dev SMOKE_PASSWORD=... npm run smoke
//
// 환경변수
//   SMOKE_BASE_URL     기본 http://localhost:3000/api/v1
//   SMOKE_EMAIL        기본 test02@skincare.dev (시드 계정)
//   SMOKE_PASSWORD     기본 SEED_PASSWORD
//   SMOKE_IMAGE        JPEG/PNG 경로. 없으면 합성 회색 PNG (stub 측정에서만 통과 — 회귀 모델 연결 후엔 얼굴 사진 필요)
//   SMOKE_TIMEOUT_SEC  추천 완료까지 기다릴 최대 초 (기본 300)
//   SMOKE_SCENARIO     X-Fixture-Scenario (fixture 모드 개발 서버에서만 의미)
//
// 성공하면 0, 하나라도 기대와 다르면 1로 끝난다. 토큰·설문 원문은 출력하지 않는다.
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { solidPng } from '../fixtures/images';

if (existsSync('.env')) process.loadEnvFile('.env');

const BASE = (process.env.SMOKE_BASE_URL ?? 'http://localhost:3000/api/v1').replace(/\/+$/, '');
const EMAIL = process.env.SMOKE_EMAIL ?? 'test02@skincare.dev';
const PASSWORD = process.env.SMOKE_PASSWORD ?? process.env.SEED_PASSWORD ?? '';
const TIMEOUT_SEC = Number(process.env.SMOKE_TIMEOUT_SEC ?? 300);

type Json = Record<string, unknown>;
interface Envelope {
  success: boolean;
  data?: Json;
  error?: { code: string; message: string };
}

let failures = 0;
const check = (ok: boolean, label: string, detail = '') => {
  console.log(`${ok ? '✔' : '✘'} ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures++;
};

async function call(
  method: string,
  path: string,
  options: { token?: string; json?: unknown; form?: FormData } = {},
): Promise<{ status: number; body: Envelope | null; requestId: string | null }> {
  const headers: Record<string, string> = {
    'X-Client': 'ios',
    'X-Request-Id': `smoke-${randomUUID()}`,
  };
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  if (options.json !== undefined) headers['Content-Type'] = 'application/json';
  if (process.env.SMOKE_SCENARIO) headers['X-Fixture-Scenario'] = process.env.SMOKE_SCENARIO;
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: options.form ?? (options.json !== undefined ? JSON.stringify(options.json) : undefined),
  });
  const text = await res.text();
  return {
    status: res.status,
    body: text ? (JSON.parse(text) as Envelope) : null,
    requestId: res.headers.get('x-request-id'),
  };
}

function loadImage(): { buffer: Buffer; type: string; name: string } {
  const path = process.env.SMOKE_IMAGE;
  if (!path) return { buffer: solidPng(1080, 1440), type: 'image/png', name: 'smoke.png' };
  const buffer = readFileSync(path);
  const isPng = buffer[0] === 0x89;
  return {
    buffer,
    type: isPng ? 'image/png' : 'image/jpeg',
    name: isPng ? 'smoke.png' : 'smoke.jpg',
  };
}

async function main(): Promise<void> {
  console.log(`대상: ${BASE}`);
  const status = await call('GET', '/system/status');
  check(status.status === 200, 'GET /system/status', JSON.stringify(status.body?.data));
  check(status.requestId?.startsWith('smoke-') === true, 'X-Request-Id를 그대로 돌려줌');

  const login = await call('POST', '/auth/login', { json: { email: EMAIL, password: PASSWORD } });
  check(
    login.status === 200,
    `로그인 (${EMAIL.replace(/^(.).*@/, '$1***@')})`,
    login.body?.error?.code ?? '',
  );
  if (login.status !== 200) return;
  const accessToken = login.body?.data?.accessToken as string;
  const refreshToken = login.body?.data?.refreshToken as string | undefined;
  check(typeof refreshToken === 'string', '[앱 협의] X-Client: ios → data.refreshToken');

  const image = loadImage();
  const form = new FormData();
  form.append('image', new Blob([image.buffer], { type: image.type }), image.name);
  form.append('deviceType', 'smartphone');
  form.append(
    'survey',
    JSON.stringify({
      gender: 'female',
      ageBand: '20s',
      skinType: 'combination',
      concerns: ['pore'],
      note: '스모크 테스트',
    }),
  );
  const startedAt = Date.now();
  const created = await call('POST', '/analyses', { token: accessToken, form });
  const submitMs = Date.now() - startedAt;
  check(
    created.status === 202,
    `POST /analyses ${submitMs}ms`,
    JSON.stringify(created.body?.data ?? created.body?.error),
  );
  if (created.status !== 202) return;
  const id = created.body?.data?.analysisId as string;

  let last = '';
  let detail: Json | undefined;
  while (Date.now() - startedAt < TIMEOUT_SEC * 1000) {
    const res = await call('GET', `/analyses/${id}`, { token: accessToken });
    detail = res.body?.data;
    const state = `${String(detail?.status)} (순번 ${String(detail?.queuePosition)}, 예상 ${String(detail?.estimatedWaitSec)}초)`;
    if (state !== last) console.log(`  … ${Math.round((Date.now() - startedAt) / 1000)}s ${state}`);
    last = state;
    if (detail?.status === 'done' || detail?.status === 'failed') break;
    await new Promise((r) => setTimeout(r, 3000));
  }
  const measurement = detail?.measurement as Json | undefined;
  const recommendation = detail?.recommendation as Json | undefined;
  check(
    detail?.status === 'done',
    `추천 완료 (${Math.round((Date.now() - startedAt) / 1000)}s)`,
    JSON.stringify(detail?.error),
  );
  check(
    typeof measurement?.overallScore === 'number',
    '측정 결과',
    `isDemo=${String(measurement?.isDemo)} overall=${String(measurement?.overallScore)} model=${String(measurement?.modelVersion)}`,
  );
  if (recommendation?.status === 'done') {
    console.log(
      `  추천: ${JSON.stringify(recommendation.skinType)} 고민 ${JSON.stringify(recommendation.concerns)} 성분 ${(recommendation.ingredients as unknown[]).length}개 루틴 ${(recommendation.routine as unknown[]).length}개 근거 ${(recommendation.reasoning as unknown[]).length}문단 model=${String(recommendation.modelVersion)}`,
    );
    check(
      typeof recommendation.disclaimer === 'string' && recommendation.disclaimer.length > 0,
      '면책 문구 있음',
    );
  }

  const list = await call('GET', '/analyses?size=1', { token: accessToken });
  const firstItem = (list.body?.data?.items as Json[] | undefined)?.[0];
  check(list.status === 200 && firstItem?.analysisId === id, 'GET /analyses?size=1 최신 1건');
  const trend = await call('GET', `/analyses/${id}/trend`, { token: accessToken });
  check(
    trend.status === 200,
    'GET /analyses/{id}/trend',
    `previous=${trend.body?.data?.previous ? '있음' : 'null'}`,
  );

  if (refreshToken) {
    const refreshed = await call('POST', '/auth/refresh', { json: { refreshToken } });
    check(
      refreshed.status === 200 && typeof refreshed.body?.data?.refreshToken === 'string',
      '본문 refreshToken으로 재발급',
    );
  }
}

main()
  .catch((error: unknown) => {
    console.error('✘ 예외:', error instanceof Error ? error.message : error);
    failures++;
  })
  .finally(() => {
    console.log(failures === 0 ? '\n스모크 통과' : `\n실패 ${failures}건`);
    process.exit(failures === 0 ? 0 : 1);
  });
