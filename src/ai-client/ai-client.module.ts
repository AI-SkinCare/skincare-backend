import { Module } from '@nestjs/common';
import { SLEEP, Sleep } from '../common/utils/sleep';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { AI_CLIENT, AiClient } from './ai-client.interface';
import { FixtureAiClient } from './fixture-ai-client';
import { FixtureScenarioStore } from './fixture-scenario.store';
import { HttpAiClient } from './http-ai-client';

/** DI 토큰 하나(AI_CLIENT)로 real / fixture를 바꾼다 (TRD §12.1) */
@Module({
  providers: [
    FixtureScenarioStore,
    {
      provide: AI_CLIENT,
      inject: [APP_CONFIG, SLEEP],
      useFactory: (config: AppConfig, sleep: Sleep): AiClient => {
        if (config.ai.mode === 'real') {
          return new HttpAiClient({
            baseUrl: config.ai.baseUrl as string,
            internalToken: config.ai.internalToken as string,
            skinTimeoutMs: config.ai.skinTimeoutMs,
            recTimeoutMs: config.ai.recTimeoutMs,
          });
        }
        return new FixtureAiClient(config.ai.fixtureScenario, sleep);
      },
    },
  ],
  exports: [AI_CLIENT, FixtureScenarioStore],
})
export class AiClientModule {}
