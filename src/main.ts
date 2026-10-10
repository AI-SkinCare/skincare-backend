import { NestFactory } from '@nestjs/core';
import type { NestExpressApplication } from '@nestjs/platform-express';
import { Logger } from 'nestjs-pino';
import { AppModule } from './app.module';
import { configureApp } from './app.setup';
import { APP_CONFIG, AppConfig } from './config/app-config';

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create<NestExpressApplication>(AppModule, { bufferLogs: true });
  app.useLogger(app.get(Logger));
  const config = app.get<AppConfig>(APP_CONFIG);
  configureApp(app, config);
  // SIGTERM: 폴링만 멈추고 진행 중 추천은 기다리지 않는다 (재시작 복구가 다시 큐에 넣음)
  app.enableShutdownHooks();
  // 로컬 실기기 테스트를 위해 0.0.0.0 (TRD §17.3)
  await app.listen(config.port, '0.0.0.0');
}

void bootstrap();
