-- 응답 시간 분포 (TRD §13.4·§18.1 Phase 7, SC-08 §5 TC-PERF-02).
-- analyses의 measure_ms·queue_wait_ms·recommend_ms로 p50/p95/p99를 계산한다. 개인 정보(user_id 등)는 출력하지 않는다.
--
--   docker compose exec -T db psql -U skincare -d skincare -v since=2026-11-10T00:00:00Z [-v until=...] -f - < load/latency-report.sql
\set ON_ERROR_STOP on
\set QUIET on
\if :{?since}
\else
  \set since '-infinity'
\endif
\if :{?until}
\else
  \set until 'infinity'
\endif
\echo 기간: :since ~ :until

CREATE TEMP VIEW w AS
  SELECT * FROM analyses WHERE created_at >= :'since'::timestamptz AND created_at < :'until'::timestamptz;

\echo
\echo [지표별 분포 (ms)]
WITH v AS (
  SELECT 'measure_ms' AS metric, 1 AS ord, measure_ms AS v FROM w
  UNION ALL SELECT 'queue_wait_ms', 2, queue_wait_ms FROM w WHERE queue_wait_ms IS NOT NULL
  UNION ALL SELECT 'recommend_ms (done)', 3, recommend_ms FROM w WHERE status = 'done'
)
SELECT metric, COUNT(v) AS n, ROUND(AVG(v)) AS avg,
       ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY v)) AS p50,
       ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY v)) AS p95,
       ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY v)) AS p99,
       MAX(v) AS max
FROM v GROUP BY metric, ord ORDER BY ord;

\echo [상태·에러 코드별]
SELECT status, COALESCE(error_code, '-') AS error_code, COUNT(*) AS n, ROUND(AVG(attempt_count), 2) AS avg_attempts
FROM w GROUP BY status, error_code ORDER BY status, error_code;

-- NFR-PERF-02: 추천 생성 시간 p95 ≤ 90초
\echo [판정]
SELECT 'TC-PERF-02 recommend_ms p95 ' || COALESCE(p95::text || 'ms', '데이터 없음') || ' (목표 ≤ 90000ms): '
       || CASE WHEN p95 IS NULL THEN '-' WHEN p95 <= 90000 THEN '통과' ELSE '미달' END AS result
FROM (SELECT ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY recommend_ms)) AS p95 FROM w WHERE status = 'done') t;
