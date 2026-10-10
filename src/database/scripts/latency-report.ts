// 응답 시간 분포 집계 (TRD §13.4·§18.1 Phase 7, SC-08 §5 TC-PERF-02).
// analyses의 measure_ms·queue_wait_ms·recommend_ms로 p50/p95/p99를 계산한다. 개인 정보(user_id 등)는 출력하지 않는다.
//
//   로컬:      npm run report:latency -- --since 2026-11-10T00:00:00Z [--until ...] [--csv out.csv]
//   배포 서버: docker compose exec backend node dist/database/scripts/latency-report.js --since ...
import 'reflect-metadata';
import { existsSync, writeFileSync } from 'node:fs';
import { DataSource } from 'typeorm';
import { buildDataSourceOptions } from '../typeorm-options';

interface Args {
  since: string;
  until: string;
  csv?: string;
}

function parseArgs(argv: string[]): Args {
  const get = (name: string) => {
    const i = argv.indexOf(`--${name}`);
    return i >= 0 ? argv[i + 1] : undefined;
  };
  return {
    since: get('since') ?? new Date(Date.now() - 7 * 24 * 3600 * 1000).toISOString(),
    until: get('until') ?? new Date().toISOString(),
    csv: get('csv'),
  };
}

const PERCENTILES = `
  COUNT(v) AS n,
  ROUND(AVG(v)) AS avg,
  ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY v)) AS p50,
  ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY v)) AS p95,
  ROUND(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY v)) AS p99,
  MAX(v) AS max`;

const SUMMARY_SQL = `
  WITH w AS (SELECT * FROM analyses WHERE created_at >= $1 AND created_at < $2)
  SELECT 'measure_ms' AS metric, ${PERCENTILES} FROM (SELECT measure_ms AS v FROM w) t
  UNION ALL
  SELECT 'queue_wait_ms', ${PERCENTILES} FROM (SELECT queue_wait_ms AS v FROM w WHERE queue_wait_ms IS NOT NULL) t
  UNION ALL
  SELECT 'recommend_ms (done)', ${PERCENTILES} FROM (SELECT recommend_ms AS v FROM w WHERE status = 'done') t`;

const STATUS_SQL = `
  SELECT status, COALESCE(error_code, '-') AS error_code, COUNT(*) AS n,
         ROUND(AVG(attempt_count), 2) AS avg_attempts
  FROM analyses WHERE created_at >= $1 AND created_at < $2
  GROUP BY status, error_code ORDER BY status, error_code`;

const ROWS_SQL = `
  SELECT id, created_at, status, is_demo, measure_ms, queue_wait_ms, recommend_ms, attempt_count, error_code
  FROM analyses WHERE created_at >= $1 AND created_at < $2 ORDER BY created_at`;

/** pg가 문자열로 주는 COUNT·AVG·PERCENTILE을 숫자로 */
function numeric(rows: Record<string, unknown>[]): Record<string, unknown>[] {
  return rows.map((row) =>
    Object.fromEntries(
      Object.entries(row).map(([k, v]) => [
        k,
        typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v) ? Number(v) : v,
      ]),
    ),
  );
}

/** NFR-PERF-02: 추천 생성 시간 p95 ≤ 90초 */
const RECOMMEND_P95_TARGET_MS = 90_000;

async function main(): Promise<void> {
  if (existsSync('.env') && !process.env.DATABASE_URL) process.loadEnvFile('.env');
  if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL이 필요합니다');
  const args = parseArgs(process.argv.slice(2));
  const dataSource = new DataSource(buildDataSourceOptions(process.env.DATABASE_URL));
  await dataSource.initialize();
  try {
    const params = [args.since, args.until];
    console.log(`기간: ${args.since} ~ ${args.until}\n`);
    const summary = numeric(await dataSource.query(SUMMARY_SQL, params));
    console.table(summary);
    console.table(numeric(await dataSource.query(STATUS_SQL, params)));

    const recommend = summary.find((row) => row.metric === 'recommend_ms (done)');
    if (typeof recommend?.p95 === 'number') {
      const p95 = recommend.p95;
      const verdict = p95 <= RECOMMEND_P95_TARGET_MS ? '통과' : '미달';
      console.log(
        `TC-PERF-02 recommend_ms p95 ${p95}ms (목표 ≤ ${RECOMMEND_P95_TARGET_MS}ms): ${verdict}`,
      );
    }

    if (args.csv) {
      const rows: Record<string, unknown>[] = await dataSource.query(ROWS_SQL, params);
      const columns = [
        'id',
        'created_at',
        'status',
        'is_demo',
        'measure_ms',
        'queue_wait_ms',
        'recommend_ms',
        'attempt_count',
        'error_code',
      ];
      const lines = rows.map((row) =>
        columns
          .map((c) => {
            const v = row[c];
            return v instanceof Date
              ? v.toISOString()
              : v === null || v === undefined
                ? ''
                : String(v);
          })
          .join(','),
      );
      writeFileSync(args.csv, [columns.join(','), ...lines].join('\n') + '\n');
      console.log(`CSV ${rows.length}행 → ${args.csv} (그래프용)`);
    }
  } finally {
    await dataSource.destroy();
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
