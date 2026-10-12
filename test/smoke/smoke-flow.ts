// 실행 중인 백엔드의 API 전체 점검 (M3 배포 서버 + 실제 AI 연동 확인용).
//
//   SMOKE_BASE_URL=https://app.<도메인>/api/v1 npm run smoke
//
// 기본은 임시 계정을 가입시켜 쓰고 끝나면 탈퇴까지 한다 (시드 계정 불필요). 13개 엔드포인트와 주요 오류 경로를 본다.
//
// 환경변수
//   SMOKE_BASE_URL     기본 http://localhost:3000/api/v1
//   SMOKE_EMAIL        기존 계정을 쓸 때 (이때는 가입·탈퇴를 건너뛴다)
//   SMOKE_PASSWORD     SMOKE_EMAIL의 비밀번호 (기본 SEED_PASSWORD)
//   SMOKE_IMAGE        JPEG/PNG 경로. 없으면 합성 회색 PNG (stub 측정에서만 통과 — 회귀 모델 연결 후엔 얼굴 사진 필요)
//   SMOKE_TIMEOUT_SEC  추천 완료까지 기다릴 최대 초 (기본 300)
//   SMOKE_SCENARIO     X-Fixture-Scenario (fixture 모드 개발 서버에서만 의미)
//
// 모두 통과하면 0, 하나라도 기대와 다르면 1로 끝난다. 토큰·설문 원문은 출력하지 않는다.
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { gifHeader, solidPng } from '../fixtures/images';

if (existsSync('.env')) process.loadEnvFile('.env');

const BASE = (process.env.SMOKE_BASE_URL ?? 'http://localhost:3000/api/v1').replace(/\/+$/, '');
const EXISTING_EMAIL = process.env.SMOKE_EMAIL;
const TIMEOUT_SEC = Number(process.env.SMOKE_TIMEOUT_SEC ?? 300);

type Json = Record<string, unknown>;
interface Envelope {
  success: boolean;
  data?: Json;
  error?: { code: string; message: string; detail: unknown };
}
interface Res {
  status: number;
  body: Envelope | null;
  headers: Headers;
}

let failures = 0;
let checks = 0;
const check = (ok: boolean, label: string, detail = '') => {
  checks++;
  console.log(`${ok ? '✔' : '✘'} ${label}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures++;
  return ok;
};
const expectError = (res: Res, status: number, code: string, label: string) =>
  check(
    res.status === status && res.body?.error?.code === code,
    `${label} → ${status} ${code}`,
    res.status === status ? '' : `실제 ${res.status} ${res.body?.error?.code ?? ''}`,
  );

async function call(
  method: string,
  path: string,
  options: { token?: string; json?: unknown; form?: FormData } = {},
): Promise<Res> {
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
    headers: res.headers,
  };
}

function imageForm(
  image: { buffer: Buffer; type: string; name: string },
  survey: unknown,
): FormData {
  const form = new FormData();
  form.append('image', new Blob([image.buffer], { type: image.type }), image.name);
  form.append('deviceType', 'smartphone');
  form.append('survey', JSON.stringify(survey));
  return form;
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

const SURVEY = {
  gender: 'female',
  ageBand: '20s',
  skinType: 'combination',
  concerns: ['pore'],
  note: '스모크 테스트',
};

async function main(): Promise<void> {
  console.log(`대상: ${BASE}\n`);

  // 1. 시스템 상태 (인증 없음)
  const status = await call('GET', '/system/status');
  check(status.status === 200, 'GET /system/status', JSON.stringify(status.body?.data));
  check(
    status.headers.get('x-request-id')?.startsWith('smoke-') === true,
    'X-Request-Id를 그대로 돌려줌',
  );
  if (status.body?.data?.aiAvailable !== true)
    console.log('  ! aiAvailable=false — 분석 제출은 503 E3001이 정상');

  // 2. 가입 (임시 계정) — 기존 계정을 주면 건너뜀
  const email = EXISTING_EMAIL ?? `smoke-${randomUUID().slice(0, 8)}@example.com`;
  const password = EXISTING_EMAIL
    ? (process.env.SMOKE_PASSWORD ?? process.env.SEED_PASSWORD ?? '')
    : `Smoke!${randomUUID().slice(0, 8)}`;
  if (!EXISTING_EMAIL) {
    const agreements = { terms: true, privacy: true, imageProcessing: true, over14: true };
    const signup = await call('POST', '/auth/signup', {
      json: { email, password, nickname: '스모크', agreements },
    });
    check(
      signup.status === 201 && typeof signup.body?.data?.userId === 'number',
      'POST /auth/signup → 201',
    );
    expectError(
      await call('POST', '/auth/signup', {
        json: { email: email.toUpperCase(), password, nickname: '스모크', agreements },
      }),
      409,
      'E1004',
      '같은 이메일(대소문자만 다름) 재가입',
    );
    expectError(
      await call('POST', '/auth/signup', {
        json: {
          email: `x${email}`,
          password,
          nickname: '스모크',
          agreements: { ...agreements, over14: false },
        },
      }),
      422,
      'E4002',
      '동의 누락 가입',
    );
  }

  // 3. 로그인
  expectError(
    await call('POST', '/auth/login', { json: { email, password: 'Wrong!pass1' } }),
    401,
    'E1003',
    '틀린 비밀번호 로그인',
  );
  const login = await call('POST', '/auth/login', { json: { email, password } });
  if (
    !check(
      login.status === 200,
      `POST /auth/login → 200 (${email.replace(/^(.).*@/, '$1***@')})`,
      login.body?.error?.code ?? '',
    )
  )
    return;
  let accessToken = login.body?.data?.accessToken as string;
  const refreshToken1 = login.body?.data?.refreshToken as string | undefined;
  check(typeof refreshToken1 === 'string', '[앱 협의] X-Client: ios → data.refreshToken');
  check(
    (login.headers.get('set-cookie') ?? '').includes('Path=/api/v1/auth'),
    'Set-Cookie refresh_token (Path=/api/v1/auth)',
  );

  // 4. 내 정보
  const me = await call('GET', '/users/me', { token: accessToken });
  check(me.status === 200 && me.body?.data?.email === email.toLowerCase(), 'GET /users/me → 200');
  expectError(await call('GET', '/users/me'), 401, 'E1001', '토큰 없이 /users/me');

  // 5. 분석 제출 — 오류 경로 먼저 (행이 생기지 않는다)
  const image = loadImage();
  expectError(
    await call('POST', '/analyses', {
      token: accessToken,
      form: imageForm({ buffer: gifHeader(), type: 'image/gif', name: 'x.gif' }, SURVEY),
    }),
    415,
    'E2004',
    'GIF 제출',
  );
  const noGender = await call('POST', '/analyses', {
    token: accessToken,
    form: imageForm(image, { ageBand: '20s' }),
  });
  expectError(noGender, 422, 'E4002', '성별 없는 설문');
  check(
    JSON.stringify(noGender.body?.error?.detail ?? '').includes('survey.gender'),
    '  detail에 survey.gender',
  );

  const startedAt = Date.now();
  const created = await call('POST', '/analyses', {
    token: accessToken,
    form: imageForm(image, SURVEY),
  });
  check(
    created.status === 202,
    `POST /analyses → 202 (${Date.now() - startedAt}ms)`,
    JSON.stringify(created.body?.data ?? created.body?.error),
  );
  if (created.status !== 202) return;
  const id = created.body?.data?.analysisId as string;
  expectError(
    await call('POST', '/analyses', { token: accessToken, form: imageForm(image, SURVEY) }),
    429,
    'E4004',
    '진행 중 두 번째 제출',
  );

  // 6. 결과 폴링 (앱과 같은 3초 간격)
  let last = '';
  let detail: Json | undefined;
  while (Date.now() - startedAt < TIMEOUT_SEC * 1000) {
    detail = (await call('GET', `/analyses/${id}`, { token: accessToken })).body?.data;
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
    `GET /analyses/{id} → done (${Math.round((Date.now() - startedAt) / 1000)}s)`,
    JSON.stringify(detail?.error),
  );
  check(
    typeof measurement?.overallScore === 'number' &&
      Array.isArray(measurement?.summary) &&
      (measurement.summary as unknown[]).length === 4,
    '  측정 결과 (summary 4개)',
    `isDemo=${String(measurement?.isDemo)} overall=${String(measurement?.overallScore)} model=${String(measurement?.modelVersion)}`,
  );
  if (recommendation?.status === 'done') {
    check(
      [
        'skinType',
        'concerns',
        'ingredients',
        'routine',
        'reasoning',
        'answer',
        'disclaimer',
        'inputNotes',
      ].every((k) => k in recommendation),
      '  추천 필드 모두',
      `${JSON.stringify(recommendation.skinType)} 고민 ${(recommendation.concerns as unknown[]).length} 성분 ${(recommendation.ingredients as unknown[]).length} 루틴 ${(recommendation.routine as unknown[]).length} 근거 ${(recommendation.reasoning as unknown[]).length} model=${String(recommendation.modelVersion)}`,
    );
  }
  expectError(
    await call('GET', '/analyses/NOT-A-ULID', { token: accessToken }),
    404,
    'E4003',
    '형식이 틀린 분석 id',
  );

  // 7–9. 이력·추이·재요청
  const list = await call('GET', '/analyses?page=1&size=1', { token: accessToken });
  check(
    list.status === 200 && (list.body?.data?.items as Json[] | undefined)?.[0]?.analysisId === id,
    'GET /analyses?size=1 → 최신 1건',
  );
  expectError(
    await call('GET', '/analyses?size=51', { token: accessToken }),
    422,
    'E4002',
    'size=51',
  );
  const trend = await call('GET', `/analyses/${id}/trend`, { token: accessToken });
  check(
    trend.status === 200 && 'current' in (trend.body?.data ?? {}),
    'GET /analyses/{id}/trend → 200',
    `previous=${trend.body?.data?.previous ? '있음' : 'null'}`,
  );
  expectError(
    await call('POST', `/analyses/${id}/recommendation/retry`, { token: accessToken }),
    409,
    'E4005',
    'done 분석 추천 재요청',
  );

  // 10. 토큰 재발급·재사용 감지·로그아웃
  if (refreshToken1) {
    const refreshed = await call('POST', '/auth/refresh', {
      json: { refreshToken: refreshToken1 },
    });
    const refreshToken2 = refreshed.body?.data?.refreshToken as string | undefined;
    check(
      refreshed.status === 200 &&
        typeof refreshToken2 === 'string' &&
        refreshToken2 !== refreshToken1,
      'POST /auth/refresh (본문 토큰) → 새 토큰',
    );
    accessToken = (refreshed.body?.data?.accessToken as string) ?? accessToken;
    const logout = await call('POST', '/auth/logout', {
      token: accessToken,
      json: { refreshToken: refreshToken2 },
    });
    check(logout.status === 204, 'POST /auth/logout → 204');
    expectError(
      await call('POST', '/auth/refresh', { json: { refreshToken: refreshToken2 } }),
      401,
      'E1001',
      '로그아웃한 토큰으로 재발급',
    );
    expectError(
      await call('POST', '/auth/refresh', { json: { refreshToken: refreshToken1 } }),
      401,
      'E1001',
      '교체된 토큰 재사용',
    );
  }

  // 11. 분석 삭제 (액세스 토큰은 만료 전까지 유효)
  check(
    (await call('DELETE', `/analyses/${id}`, { token: accessToken })).status === 204,
    'DELETE /analyses/{id} → 204',
  );
  expectError(
    await call('GET', `/analyses/${id}`, { token: accessToken }),
    404,
    'E4003',
    '삭제한 분석 조회',
  );

  // 12. 탈퇴 (임시 계정만)
  if (!EXISTING_EMAIL) {
    check(
      (await call('DELETE', '/users/me', { token: accessToken })).status === 204,
      'DELETE /users/me → 204 (임시 계정 정리)',
    );
    expectError(
      await call('GET', '/users/me', { token: accessToken }),
      401,
      'E1001',
      '탈퇴 후 남은 토큰',
    );
  }
}

main()
  .catch((error: unknown) => {
    console.error('✘ 예외:', error instanceof Error ? error.message : error);
    failures++;
  })
  .finally(() => {
    console.log(
      failures === 0 ? `\n스모크 통과 (${checks}개 확인)` : `\n실패 ${failures}건 / ${checks}개`,
    );
    process.exit(failures === 0 ? 0 : 1);
  });
