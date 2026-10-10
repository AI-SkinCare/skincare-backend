import type { NestExpressApplication } from '@nestjs/platform-express';
import { Test } from '@nestjs/testing';
import { Logger } from 'nestjs-pino';
import request from 'supertest';
import { DataSource } from 'typeorm';
import { AI_CLIENT, AiClient } from '../../../src/ai-client/ai-client.interface';
import {
  FIXTURE_REC_SLOW_DELAY_MS,
  FixtureAiClient,
} from '../../../src/ai-client/fixture-ai-client';
import { AppModule } from '../../../src/app.module';
import { configureApp } from '../../../src/app.setup';
import { SLEEP } from '../../../src/common/utils/sleep';
import { APP_CONFIG, AppConfig, loadAppConfig } from '../../../src/config/app-config';
import { QueueMaintenanceService } from '../../../src/recommendation/queue-maintenance.service';
import { RecommendationWorker } from '../../../src/recommendation/recommendation.worker';
import { AiHealthService } from '../../../src/system/ai-health.service';

/**
 * 지연 주입 — 실제로 기다리지 않는다. 호출된 지연(ms)을 기록한다.
 * holdSlow()를 켜면 rec-slow(60초) 지연만 releaseSlow() 전까지 붙잡는다 (TC-PERF-04).
 */
export class FakeSleep {
  readonly calls: number[] = [];
  private held: (() => void)[] = [];
  private holding = false;

  readonly sleep = (ms: number): Promise<void> => {
    this.calls.push(ms);
    if (this.holding && ms === FIXTURE_REC_SLOW_DELAY_MS) {
      return new Promise((resolve) => this.held.push(resolve));
    }
    return Promise.resolve();
  };

  holdSlow(): void {
    this.holding = true;
  }

  releaseSlow(): void {
    this.holding = false;
    this.held.splice(0).forEach((resolve) => resolve());
  }
}

export interface TestApp {
  app: NestExpressApplication;
  http: () => ReturnType<typeof request>;
  dataSource: DataSource;
  worker: RecommendationWorker;
  maintenance: QueueMaintenanceService;
  aiHealth: AiHealthService;
  sleep: FakeSleep;
  config: AppConfig;
  fixtureAi: () => FixtureAiClient;
  truncate: () => Promise<void>;
  close: () => Promise<void>;
}

export interface TestAppOptions {
  /** 설정 일부를 바꾼다 (예: logLevel, ai.fixtureScenario) */
  config?: (config: AppConfig) => AppConfig;
  /** AI 클라이언트를 바꿔 끼운다 (예: 로컬 가짜 서버를 보는 HttpAiClient) */
  aiClient?: AiClient;
  /** 기본 true — 테스트에선 헬스체크가 자동으로 돌지 않으므로 한 번 돌려 aiAvailable을 채운다 */
  checkHealth?: boolean;
}

export async function createTestApp(options: TestAppOptions = {}): Promise<TestApp> {
  const sleep = new FakeSleep();
  const baseConfig = loadAppConfig(process.env);
  const config = options.config ? options.config(baseConfig) : baseConfig;

  let builder = Test.createTestingModule({ imports: [AppModule] })
    .overrideProvider(SLEEP)
    .useValue(sleep.sleep)
    .overrideProvider(APP_CONFIG)
    .useValue(config);
  if (options.aiClient) builder = builder.overrideProvider(AI_CLIENT).useValue(options.aiClient);
  const moduleRef = await builder.compile();

  const app = moduleRef.createNestApplication<NestExpressApplication>();
  app.useLogger(app.get(Logger));
  configureApp(app, config);
  await app.init();

  const aiHealth = app.get(AiHealthService);
  if (options.checkHealth !== false) await aiHealth.check();
  const dataSource = app.get(DataSource);

  return {
    app,
    http: () => request(app.getHttpServer()),
    dataSource,
    worker: app.get(RecommendationWorker),
    maintenance: app.get(QueueMaintenanceService),
    aiHealth,
    sleep,
    config,
    fixtureAi: () => {
      const ai = app.get<AiClient>(AI_CLIENT);
      if (!(ai instanceof FixtureAiClient)) throw new Error('fixture 모드가 아니다');
      return ai;
    },
    truncate: () =>
      dataSource.query(
        'TRUNCATE users, refresh_tokens, analyses, skin_metrics, surveys, recommendations RESTART IDENTITY CASCADE',
      ),
    close: () => app.close(),
  };
}
