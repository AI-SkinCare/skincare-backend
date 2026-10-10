import { existsSync } from 'node:fs';
import { DataSource } from 'typeorm';
import { buildDataSourceOptions } from './typeorm-options';

// TypeORM CLI(migration:*)용. Nest 밖에서 돌므로 .env를 직접 읽는다.
if (!process.env.DATABASE_URL && existsSync('.env')) {
  process.loadEnvFile('.env');
}
if (!process.env.DATABASE_URL) {
  throw new Error('DATABASE_URL이 필요합니다');
}

export const AppDataSource = new DataSource(buildDataSourceOptions(process.env.DATABASE_URL));
