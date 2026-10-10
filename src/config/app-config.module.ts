import { Global, Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { APP_CONFIG, loadAppConfig } from './app-config';

/**
 * @nestjs/config는 .env를 process.env로 읽어 들이고 기동 시 검증(fail fast)만 맡는다.
 * 서비스는 타입 있는 APP_CONFIG를 주입받는다 (테스트는 이 토큰을 바꿔 끼운다).
 */
@Global()
@Module({
  imports: [
    ConfigModule.forRoot({
      ignoreEnvFile: process.env.NODE_ENV === 'test',
      validate: (raw) => {
        loadAppConfig(raw);
        return raw;
      },
    }),
  ],
  providers: [{ provide: APP_CONFIG, useFactory: () => loadAppConfig(process.env) }],
  exports: [APP_CONFIG],
})
export class AppConfigModule {}
