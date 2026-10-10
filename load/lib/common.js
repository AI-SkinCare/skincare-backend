// k6 공통 도구 — TRD §18.1 Phase 7, SC-08 §5.
import http from 'k6/http';
import { sleep } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:3000/api/v1').replace(/\/+$/, '');
export const PASSWORD = __ENV.SEED_PASSWORD || '';
// 측정이 진짜 회귀 모델이면 얼굴 사진이 필요하다 (레포에 커밋 금지). 기본은 npm run load:image로 만든 합성 PNG
// open()은 이 파일(load/lib) 기준 상대 경로 — IMAGE는 절대 경로로 주는 것이 안전하다
const IMAGE_PATH = __ENV.IMAGE || '../.tmp/face.png';
const IMAGE = open(IMAGE_PATH, 'b');
const IMAGE_TYPE = IMAGE_PATH.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg';

export const seedEmail = (n) => `test${String(n).padStart(2, '0')}@skincare.dev`;

const LOGIN_PER_WINDOW = 9; // 로그인 제한(IP당 분당 10회) 아래로
const LOGIN_WINDOW_SEC = 61;

/**
 * 계정별 액세스 토큰을 미리 발급한다 (setup에서 호출).
 * 스테이징은 Cloudflare가 CF-Connecting-IP를 덮어쓰므로 헤더로 우회하지 않고 창마다 쉰다.
 */
export function issueTokens(accountNumbers) {
  const tokens = [];
  accountNumbers.forEach((n, i) => {
    if (i > 0 && i % LOGIN_PER_WINDOW === 0) sleep(LOGIN_WINDOW_SEC);
    const res = http.post(
      `${BASE_URL}/auth/login`,
      JSON.stringify({ email: seedEmail(n), password: PASSWORD }),
      { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
    );
    if (res.status !== 200)
      throw new Error(`로그인 실패 ${seedEmail(n)}: ${res.status} ${res.body}`);
    tokens.push(res.json('data.accessToken'));
  });
  return tokens;
}

export function headers(token, extra = {}) {
  return { Authorization: `Bearer ${token}`, ...extra };
}

const SURVEY = JSON.stringify({
  gender: 'female',
  ageBand: '20s',
  skinType: 'combination',
  sleepHours: 6,
  waterIntakeLevel: 'low',
  sunExposureLevel: 'high',
  stressLevel: 'medium',
  concerns: ['pore', 'pigmentation'],
});

export function submit(token, extraHeaders = {}) {
  return http.post(
    `${BASE_URL}/analyses`,
    {
      image: http.file(IMAGE, IMAGE_TYPE === 'image/png' ? 'face.png' : 'face.jpg', IMAGE_TYPE),
      survey: SURVEY,
      deviceType: 'smartphone',
    },
    {
      headers: headers(token, extraHeaders),
      tags: { name: 'submit' },
      responseCallback: http.expectedStatuses(202, 503),
    },
  );
}

export function getAnalysis(token, id) {
  return http.get(`${BASE_URL}/analyses/${id}`, {
    headers: headers(token),
    tags: { name: 'poll' },
  });
}

/** done·failed가 될 때까지 폴링 (앱과 같은 간격: 3분까지 3초, 이후 10초). 상태 기록을 돌려준다 */
export function pollUntilFinished(token, id, timeoutSec) {
  const started = Date.now();
  const positions = [];
  let data = null;
  while ((Date.now() - started) / 1000 < timeoutSec) {
    const res = getAnalysis(token, id);
    if (res.status === 200) {
      data = res.json('data');
      if (data.queuePosition !== null) positions.push(data.queuePosition);
      if (data.status === 'done' || data.status === 'failed') break;
    }
    sleep((Date.now() - started) / 1000 < 180 ? 3 : 10);
  }
  return { data, positions, elapsedSec: (Date.now() - started) / 1000 };
}
