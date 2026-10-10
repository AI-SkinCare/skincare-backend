import { existsSync, readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';

/**
 * e2e DB 주소. CI는 DATABASE_URL을 직접 준다. 로컬은 .env의 DATABASE_URL에서 DB 이름 뒤에 `_test`를 붙인다
 * (개발 DB를 TRUNCATE하지 않도록). DB 이름에 test가 없으면 실행을 거부한다.
 */
export function resolveE2eDatabaseUrl(): string {
  let url = process.env.DATABASE_URL;
  if (!url && existsSync('.env')) {
    const devUrl = parseEnv(readFileSync('.env', 'utf8')).DATABASE_URL;
    if (devUrl) {
      const parsed = new URL(devUrl);
      parsed.pathname = `${parsed.pathname}_test`;
      url = parsed.toString();
    }
  }
  url ??= 'postgres://skincare:local-dev-password@localhost:5432/skincare_test';
  const dbName = new URL(url).pathname.slice(1);
  if (!dbName.includes('test')) {
    throw new Error(
      `e2e는 이름에 test가 들어간 DB에서만 돈다 (현재: ${dbName}) — 테이블을 TRUNCATE하기 때문`,
    );
  }
  return url;
}

/** TRD §16.1 — AI_MODE=fixture, NODE_ENV=test. 워커는 자동 시작하지 않는다. */
export const E2E_ENV: Record<string, string> = {
  NODE_ENV: 'test',
  AI_MODE: 'fixture',
  AI_FIXTURE_SCENARIO: 'ok',
  JWT_ACCESS_SECRET: 'e2e-access-secret-not-real',
  JWT_REFRESH_SECRET: 'e2e-refresh-secret-not-real',
  COOKIE_SECURE: 'true',
  TRUST_PROXY: 'true',
  LOG_LEVEL: 'silent',
};
