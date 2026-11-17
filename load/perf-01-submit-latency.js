// TC-PERF-01 (개정 수행계획서 2.1): 1명이 20회 제출 — 직전 분석이 끝난 뒤 다음 제출.
// 제출 응답 시간(이미지 분류 포함) p95 ≤ 3초.
// TC-PERF-02(큐 대기 제외 recommend_ms p95 ≤ 120초)는 끝난 뒤 load/latency-report.sql로 DB에서 집계한다.
//
//   k6 run -e BASE_URL=https://app.<도메인>/api/v1 -e SEED_PASSWORD=... [-e IMAGE=face.jpg] load/perf-01-submit-latency.js
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { issueTokens, pollUntilFinished, submit } from './lib/common.js';

const ITERATIONS = Number(__ENV.ITERATIONS || 20);
const ACCOUNT = Number(__ENV.ACCOUNT || 2); // test02 (test01은 이력 25건 보존용)
const submitDuration = new Trend('submit_duration', true);
const recommendWait = new Trend('recommend_wait', true);

export const options = {
  scenarios: {
    sequential: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: ITERATIONS,
      maxDuration: '3h',
    },
  },
  setupTimeout: '5m',
  thresholds: {
    submit_duration: ['p(95)<3000'],
    checks: ['rate==1'],
  },
};

export function setup() {
  return { token: issueTokens([ACCOUNT])[0] };
}

export default function ({ token }) {
  const res = submit(token);
  submitDuration.add(res.timings.duration);
  if (!check(res, { '제출 202': (r) => r.status === 202 })) return;
  const { data, elapsedSec } = pollUntilFinished(token, res.json('data.analysisId'), 900);
  recommendWait.add(elapsedSec * 1000);
  // 분류 결과에 따라 추천 완료 또는 보류 — 둘 다 정상 종료
  check(data, {
    '추천 완료·보류': (d) => d !== null && (d.status === 'COMPLETED' || d.status === 'WITHHELD'),
  });
}
