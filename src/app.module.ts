import { Module, ValidationPipe } from '@nestjs/common';
import { APP_FILTER, APP_INTERCEPTOR, APP_PIPE } from '@nestjs/core';
import { ScheduleModule } from '@nestjs/schedule';
import { TypeOrmModule } from '@nestjs/typeorm';
import { LoggerModule } from 'nestjs-pino';
import { AnalysesModule } from './analyses/analyses.module';
import { AuthModule } from './auth/auth.module';
import { CommonModule } from './common/common.module';
import { ApiExceptionFilter } from './common/errors/api-exception.filter';
import { ResponseEnvelopeInterceptor } from './common/interceptors/response-envelope.interceptor';
import { buildLoggerParams } from './common/logging/logger.config';
import { validationExceptionFactory } from './common/validation/validation-errors';
import { APP_CONFIG, AppConfig } from './config/app-config';
import { AppConfigModule } from './config/app-config.module';
import { APP_STATEMENT_TIMEOUT_MS, buildDataSourceOptions } from './database/typeorm-options';
import { RecommendationModule } from './recommendation/recommendation.module';
import { SystemModule } from './system/system.module';
import { UsersModule } from './users/users.module';

@Module({
  imports: [
    AppConfigModule,
    CommonModule,
    LoggerModule.forRootAsync({
      inject: [APP_CONFIG],
      useFactory: (config: AppConfig) => buildLoggerParams(config),
    }),
    TypeOrmModule.forRootAsync({
      inject: [APP_CONFIG],
      useFactory: (config: AppConfig) =>
        buildDataSourceOptions(config.databaseUrl, {
          statementTimeoutMs: APP_STATEMENT_TIMEOUT_MS,
        }),
    }),
    ScheduleModule.forRoot(),
    AuthModule,
    UsersModule,
    AnalysesModule,
    RecommendationModule,
    SystemModule,
  ],
  providers: [
    { provide: APP_FILTER, useClass: ApiExceptionFilter },
    { provide: APP_INTERCEPTOR, useClass: ResponseEnvelopeInterceptor },
    {
      provide: APP_PIPE,
      // 검증 실패는 400이 아니라 422 E4002. 모르는 필드는 제거 (TRD §7.3)
      useFactory: () =>
        new ValidationPipe({
          transform: true,
          whitelist: true,
          forbidNonWhitelisted: false,
          exceptionFactory: validationExceptionFactory,
        }),
    },
  ],
})
export class AppModule {}
