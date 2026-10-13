-- 분석별 행 CSV (그래프용, 사용자 정보 없음). latency-report.sql과 같은 기간 변수.
--   docker compose exec -T db psql -U skincare -d skincare --csv -v since=2026-11-10T00:00:00Z -f - < load/latency-rows.sql > latency.csv
\set ON_ERROR_STOP on
\if :{?since}
\else
  \set since '-infinity'
\endif
\if :{?until}
\else
  \set until 'infinity'
\endif
SELECT id, to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS created_at, status, is_demo,
       measure_ms, queue_wait_ms, recommend_ms, attempt_count, error_code
FROM analyses
WHERE created_at >= :'since'::timestamptz AND created_at < :'until'::timestamptz
ORDER BY created_at;
