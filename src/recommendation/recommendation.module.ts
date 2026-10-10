import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { AiClientModule } from '../ai-client/ai-client.module';
import { Analysis } from '../analyses/entities/analysis.entity';
import { SkinMetric } from '../analyses/entities/skin-metric.entity';
import { Survey } from '../analyses/entities/survey.entity';
import { SystemModule } from '../system/system.module';
import { QueueMaintenanceService } from './queue-maintenance.service';
import { RecommendationQueueModule } from './recommendation-queue.module';
import { RecommendationWorker } from './recommendation.worker';

@Module({
  imports: [
    TypeOrmModule.forFeature([Analysis, SkinMetric, Survey]),
    AiClientModule,
    SystemModule,
    RecommendationQueueModule,
  ],
  providers: [RecommendationWorker, QueueMaintenanceService],
  exports: [RecommendationWorker, QueueMaintenanceService],
})
export class RecommendationModule {}
