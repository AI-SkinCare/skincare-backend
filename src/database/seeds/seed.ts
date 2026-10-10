// npm run seed — 개발·스테이징 전용 (TRD §8.4). production이면 즉시 종료.
// test01@skincare.dev ~ test40@skincare.dev (비밀번호 SEED_PASSWORD), test01에 완료 분석 25건.
// 다시 실행해도 안전하다: 시드 계정을 지우고(CASCADE) 다시 만든다.
import 'reflect-metadata';
import { existsSync } from 'node:fs';
import * as bcrypt from 'bcrypt';
import { DataSource } from 'typeorm';
import { BCRYPT_COST } from '../../config/constants';
import { User } from '../../users/user.entity';
import { buildDataSourceOptions } from '../typeorm-options';
import { insertDoneAnalyses } from './seed-data';

const ACCOUNT_COUNT = 40;
const TEST01_ANALYSES = 25;
const SEED_EMAIL_PATTERN = '^test[0-9]{2}@skincare\\.dev$';

export const seedEmail = (n: number) => `test${String(n).padStart(2, '0')}@skincare.dev`;

async function main(): Promise<void> {
  if (existsSync('.env')) process.loadEnvFile('.env');
  if (process.env.NODE_ENV === 'production') {
    throw new Error('production에서는 시드를 실행하지 않습니다');
  }
  const { DATABASE_URL, SEED_PASSWORD } = process.env;
  if (!DATABASE_URL) throw new Error('DATABASE_URL이 필요합니다');
  if (!SEED_PASSWORD) throw new Error('SEED_PASSWORD가 필요합니다');

  const dataSource = new DataSource(buildDataSourceOptions(DATABASE_URL));
  await dataSource.initialize();
  try {
    const passwordHash = await bcrypt.hash(SEED_PASSWORD, BCRYPT_COST);
    await dataSource.transaction(async (manager) => {
      await manager.query(`DELETE FROM users WHERE email ~ $1`, [SEED_EMAIL_PATTERN]);
      const now = new Date();
      const userIds: number[] = [];
      for (let n = 1; n <= ACCOUNT_COUNT; n++) {
        const user = await manager.save(
          manager.create(User, {
            email: seedEmail(n),
            passwordHash,
            nickname: `테스트${String(n).padStart(2, '0')}`,
            termsAgreedAt: now,
            privacyAgreedAt: now,
            imageConsentAt: now,
            over14ConfirmedAt: now,
          }),
        );
        userIds.push(user.id);
      }
      await insertDoneAnalyses(manager, userIds[0], TEST01_ANALYSES, now);
    });
    console.log(
      `시드 완료: ${seedEmail(1)} ~ ${seedEmail(ACCOUNT_COUNT)}, ${seedEmail(1)}에 완료 분석 ${TEST01_ANALYSES}건`,
    );
  } finally {
    await dataSource.destroy();
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
