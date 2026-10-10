import { CanActivate, Inject, Injectable } from '@nestjs/common';
import { ApiException } from '../../common/errors/api.exception';
import { ErrorCode } from '../../common/errors/error-codes';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { RecommendationQueueRepository } from '../../recommendation/recommendation-queue.repository';

/**
 * TRD §10.1 처리 순서 3 — queued 건수 ≥ REC_QUEUE_LIMIT → 503 E3003.
 * 검사와 INSERT 사이 경합으로 1–2건 넘을 수 있다(시연 규모에서 허용).
 */
@Injectable()
export class QueueCapacityGuard implements CanActivate {
  constructor(
    private readonly queue: RecommendationQueueRepository,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  async canActivate(): Promise<boolean> {
    if ((await this.queue.queuedCount()) >= this.config.queue.limit) {
      throw new ApiException(ErrorCode.E3003);
    }
    return true;
  }
}
