import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { AiClientModule } from '../ai-client/ai-client.module';
import { RecommendationQueueModule } from '../recommendation/recommendation-queue.module';
import { SystemModule } from '../system/system.module';
import { AnalysesController } from './analyses.controller';
import { AnalysesService } from './analyses.service';
import { Analysis } from './entities/analysis.entity';
import { Recommendation } from './entities/recommendation.entity';
import { SkinMetric } from './entities/skin-metric.entity';
import { Survey } from './entities/survey.entity';
import { AiAvailabilityGuard } from './guards/ai-availability.guard';
import { AnalysisLimitGuard } from './guards/analysis-limit.guard';
import { QueueCapacityGuard } from './guards/queue-capacity.guard';
import { SubmissionLock } from './guards/submission-lock';

@Module({
  imports: [
    TypeOrmModule.forFeature([Analysis, SkinMetric, Survey, Recommendation]),
    AiClientModule,
    SystemModule,
    RecommendationQueueModule,
  ],
  controllers: [AnalysesController],
  providers: [
    AnalysesService,
    SubmissionLock,
    AnalysisLimitGuard,
    QueueCapacityGuard,
    AiAvailabilityGuard,
  ],
})
export class AnalysesModule {}
