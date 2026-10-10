import { Module } from '@nestjs/common';
import { RecommendationQueueRepository } from './recommendation-queue.repository';

/** 큐 SQL만 따로 둔 모듈 — analyses·system·recommendation이 함께 쓴다 (순환 의존 방지) */
@Module({
  providers: [RecommendationQueueRepository],
  exports: [RecommendationQueueRepository],
})
export class RecommendationQueueModule {}
