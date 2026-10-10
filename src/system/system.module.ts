import { Module } from '@nestjs/common';
import { AiClientModule } from '../ai-client/ai-client.module';
import { RecommendationQueueModule } from '../recommendation/recommendation-queue.module';
import { AiHealthService } from './ai-health.service';
import { SystemController } from './system.controller';

@Module({
  imports: [AiClientModule, RecommendationQueueModule],
  controllers: [SystemController],
  providers: [AiHealthService],
  exports: [AiHealthService],
})
export class SystemModule {}
