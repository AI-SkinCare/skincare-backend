// TC-PERF-04 (SC-02 §4.2): 로컬, fixture rec-slow로 처리 1 + 대기 10을 채운 뒤 15개 계정이 1건씩 제출
// → 12번째부터 503 E3003, 서버 정상.
// X-Fixture-Scenario 헤더를 쓰므로 production이 아닌 서버(AI_MODE=fixture)에서만 의미가 있다.
//
//   k6 run -e BASE_URL=http://localhost:3000/api/v1 -e SEED_PASSWORD=... load/perf-04-queue-limit.js
import { check, sleep } from 'k6';
import { getAnalysis, issueTokens, submit } from './lib/common.js';

const FIRST_ACCOUNT = Number(__ENV.FIRST_ACCOUNT || 21); // test21 ~ test35
const SLOW = { 'X-Fixture-Scenario': 'rec-slow' };

export const options = {
  scenarios: { once: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
  setupTimeout: '5m',
  thresholds: { checks: ['rate==1'] },
};

export function setup() {
  return { tokens: issueTokens(Array.from({ length: 15 }, (_, i) => FIRST_ACCOUNT + i)) };
}

export default function ({ tokens }) {
  const first = submit(tokens[0], SLOW);
  check(first, { '1번째 202': (r) => r.status === 202 });
  // 첫 건이 recommending으로 넘어가야 "처리 1 + 대기 10"이 성립한다
  const id = first.json('data.analysisId');
  for (let i = 0; i < 30 && getAnalysis(tokens[0], id).json('data.status') !== 'recommending'; i++)
    sleep(1);

  const statuses = tokens.slice(1).map((token) => {
    const res = submit(token, SLOW);
    return { status: res.status, code: res.status === 503 ? res.json('error.code') : null };
  });
  console.log(`2–15번째 응답: ${statuses.map((s) => s.code || s.status).join(', ')}`);
  check(statuses, {
    '2–11번째 202': (s) => s.slice(0, 10).every((x) => x.status === 202),
    '12–15번째 503 E3003': (s) => s.slice(10).every((x) => x.status === 503 && x.code === 'E3003'),
  });
}
