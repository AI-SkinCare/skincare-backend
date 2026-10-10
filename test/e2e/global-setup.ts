import { Client } from 'pg';
import { DataSource } from 'typeorm';
import { buildDataSourceOptions } from '../../src/database/typeorm-options';
import { resolveE2eDatabaseUrl } from './e2e-env';

/** 테스트 DB가 없으면 만들고 마이그레이션을 적용한다 (TRD §16.1) */
export default async function globalSetup(): Promise<void> {
  const url = resolveE2eDatabaseUrl();
  const target = new URL(url);
  const dbName = target.pathname.slice(1);

  const admin = new URL(url);
  admin.pathname = '/postgres';
  const client = new Client({ connectionString: admin.toString() });
  await client.connect();
  try {
    const { rowCount } = await client.query('SELECT 1 FROM pg_database WHERE datname = $1', [
      dbName,
    ]);
    if (!rowCount) await client.query(`CREATE DATABASE "${dbName.replace(/"/g, '')}"`);
  } finally {
    await client.end();
  }

  const dataSource = new DataSource(buildDataSourceOptions(url));
  await dataSource.initialize();
  try {
    await dataSource.runMigrations();
  } finally {
    await dataSource.destroy();
  }
}
