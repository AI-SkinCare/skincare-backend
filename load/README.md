# 부하·성능 테스트 (k6)

SC-08 §5 성능 테스트와 TRD §18.1 Phase 7을 위한 스크립트입니다. 일정은 11/10–11/20(WBS 4.8)이고, 스테이징은 정도희 서버 배포본 + 김우주 PC AI 서버(`REC_BACKEND=sllm`)입니다.

| 스크립트 | 테스트 | 합격 기준 | 계정 |
|---|---|---|---|
| `perf-01-submit-latency.js` | TC-PERF-01: 1명이 20회 제출 (직전 분석이 끝난 뒤 다음 제출) | 제출 응답 p50 ≤ 3초, p95 ≤ 5초 (`submit_duration`) | test02 |
| `npm run report:latency` | TC-PERF-02: 위 20건의 `recommend_ms` | p95 ≤ 90초 (스크립트가 판정 출력) | — |
| `perf-03-concurrent.js` | TC-PERF-03: 10명이 동시에 1건씩 | 실패 0, 10건 모두 done, 마지막 완료 약 10분 이내, 대기 순번이 늘지 않음 | test11–test20 |
| `perf-04-queue-limit.js` | TC-PERF-04: rec-slow로 처리 1 + 대기 10 → 15명 제출 | 12번째부터 503 E3003 | test21–test35 |

## 준비

1. **시드 계정**: 로컬은 `npm run seed` (test01–40, 비밀번호 `SEED_PASSWORD`).
   배포 서버(스테이징)는 backend가 `NODE_ENV=production`이라 시드가 거부된다(실수 방지, TRD §6). 성능 테스트 기간에만 일회용 컨테이너로 명시적으로 넣고, 끝나면 지운다:
   ```bash
   # 넣기 (.env에 SEED_PASSWORD 필요 — 공개되지 않은 값으로)
   docker compose run --rm -e NODE_ENV=development backend node dist/database/seeds/seed.js
   # 지우기 (CASCADE로 분석까지)
   docker compose exec db psql -U skincare -d skincare -c "DELETE FROM users WHERE email ~ '^test[0-9]{2}@skincare\.dev$'"
   ```
2. **일일 한도**: 스테이징 성능 테스트 동안 `ANALYSIS_DAILY_LIMIT=100` (기본 20이면 PERF-01이 막힘).
3. **이미지**
   - stub 측정(`is_stub: true`)이면 합성 이미지로 충분하다: `npm run load:image` → `load/.tmp/face.png`
   - 회귀 모델이 연결된 서버는 얼굴이 있어야 한다. **본인이 동의한 얼굴 사진**을 `-e IMAGE=/절대/경로/face.jpg`로 준다 (레포에 커밋 금지, `.gitignore`가 `*.jpg`·`*.png`를 막음).
4. **로그인 제한**: IP당 분당 10회라서 스크립트는 시작 단계(setup)에서 계정별 토큰을 미리 받고 9건마다 61초 쉰다. 스테이징은 Cloudflare가 `CF-Connecting-IP`를 덮어쓰므로 헤더로 우회하지 않는다. 액세스 토큰이 1시간이라 PERF-01(약 20분)까지는 문제없다.

## 실행

k6를 설치했으면 `k6 run …`, 아니면 Docker로 실행한다.

```bash
# 레포 루트에서
npm run load:image

k6() {
  docker run --rm -v "$PWD/load":/load -w /load grafana/k6:0.54.0 run \
    -e BASE_URL="${BASE_URL:-http://host.docker.internal:3000/api/v1}" -e SEED_PASSWORD="$SEED_PASSWORD" "$@"
}

BASE_URL=https://app.<도메인>/api/v1 k6 perf-01-submit-latency.js      # 약 20분 (추천 1건 ≈ 55초)
BASE_URL=https://app.<도메인>/api/v1 k6 perf-03-concurrent.js          # 약 10분
k6 perf-04-queue-limit.js                                               # 로컬, AI_MODE=fixture 서버
```

- `IMAGE`로 얼굴 사진을 줄 때는 컨테이너 안 경로가 되도록 `-v /사진/폴더:/images -e IMAGE=/images/face.jpg`를 더한다.
- PERF-04는 `X-Fixture-Scenario: rec-slow` 헤더를 쓰므로 production이 아닌 fixture 서버에서만 의미가 있다. 끝난 뒤 대기열이 60초 × 11건 동안 비워지니, 다시 돌리기 전에 기다리거나 `npm run seed`로 시드 계정을 초기화한다.
- 결과 열(SC-08 §4·§5)에는 k6 요약의 `✓/✗`와 `submit_duration`, `time_to_done`을 적는다.

## 응답 시간 분포 (TC-PERF-02, 최종 보고서 그래프)

```bash
# 로컬
npm run report:latency -- --since 2026-11-10T00:00:00Z --csv latency.csv

# 배포 서버 (DB가 외부에 열려 있지 않으므로 backend 컨테이너 안에서)
docker compose exec backend node dist/database/scripts/latency-report.js --since 2026-11-10T00:00:00Z --csv /tmp/latency.csv
docker compose cp backend:/tmp/latency.csv ./latency.csv
```

`measure_ms`(측정 왕복), `queue_wait_ms`(대기), `recommend_ms`(추천 왕복)의 p50/p95/p99와 상태·에러 코드별 건수를 출력하고, `--csv`로 분석별 행(사용자 정보 없음)을 내보낸다.

## 로컬에서 실행해 본 결과 (2026-10-10, AI_MODE=fixture)

| 스크립트 | 결과 |
|---|---|
| perf-01 (`ITERATIONS=3`) | 체크 100%, submit_duration p95 52ms |
| perf-03 | 10건 모두 done, 대기 순번 단조 감소, 체크 30/30 |
| perf-04 | 2–11번째 202, 12–15번째 503 E3003 |

fixture는 측정이 즉시, 추천이 1초라서 실제 수치는 스테이징에서 다시 잰다.
