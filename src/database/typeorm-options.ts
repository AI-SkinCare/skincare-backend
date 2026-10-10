import { join } from 'node:path';
import type { DataSourceOptions } from 'typeorm';
import { ENTITIES } from './entities';

/**
 * 앱·CLI·시드가 같이 쓰는 옵션. synchronize는 모든 환경에서 false (TRD §8.2).
 * 앱 연결에만 statement_timeout 5초 — 마이그레이션·시드는 제외.
 */
export function buildDataSourceOptions(
  databaseUrl: string,
  options: { statementTimeoutMs?: number } = {},
): DataSourceOptions {
  return {
    type: 'postgres',
    url: databaseUrl,
    entities: ENTITIES,
    migrations: [join(__dirname, 'migrations', '*.{ts,js}')],
    synchronize: false,
    migrationsRun: false,
    logging: false,
    extra: {
      max: 10,
      ...(options.statementTimeoutMs ? { statement_timeout: options.statementTimeoutMs } : {}),
    },
  };
}

export const APP_STATEMENT_TIMEOUT_MS = 5000;
