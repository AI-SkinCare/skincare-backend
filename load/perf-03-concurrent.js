// TC-PERF-03 (NFR-PERF-03): 10명이 동시에 1건씩 제출.
// 실패 0건, 10건 모두 done, 마지막 완료 약 10분 이내, 대기 순번은 줄어들기만 한다.
//
//   k6 run -e BASE_URL=... -e SEED_PASSWORD=... [-e IMAGE=face.jpg] load/perf-03-concurrent.js
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { issueTokens, pollUntilFinished, submit } from './lib/common.js';

const USERS = Number(__ENV.USERS || 10);
const FIRST_ACCOUNT = Number(__ENV.FIRST_ACCOUNT || 11); // test11 ~ test20
const timeToDone = new Trend('time_to_done', true);
const notDone = new Counter('not_done');

export const options = {
  scenarios: {
    burst: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '20m' },
  },
  setupTimeout: '5m',
  thresholds: {
    not_done: ['count==0'],
    time_to_done: [`max<${Number(__ENV.MAX_DONE_SEC || 660) * 1000}`],
    checks: ['rate==1'],
  },
};

export function setup() {
  const accounts = Array.from({ length: USERS }, (_, i) => FIRST_ACCOUNT + i);
  return { tokens: issueTokens(accounts) };
}

export default function ({ tokens }) {
  const token = tokens[exec.vu.idInTest - 1];
  const started = Date.now();
  const res = submit(token);
  const accepted = check(res, {
    '제출 202': (r) => r.status === 202,
    '대기 순번 1–10': (r) =>
      r.status === 202 &&
      r.json('data.queuePosition') >= 1 &&
      r.json('data.queuePosition') <= USERS,
  });
  if (!accepted) {
    notDone.add(1);
    return;
  }
  const { data, positions } = pollUntilFinished(token, res.json('data.analysisId'), 1200);
  const done = data !== null && data.status === 'done';
  if (!done) notDone.add(1);
  timeToDone.add(Date.now() - started);
  check(positions, { '대기 순번이 늘지 않음': (p) => p.every((v, i) => i === 0 || v <= p[i - 1]) });
}
