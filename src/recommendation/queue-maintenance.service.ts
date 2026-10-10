import { Inject, Injectable, Logger } from '@nestjs/common';
import { SchedulerRegistry } from '@nestjs/schedule';
import { FixtureScenarioStore } from '../ai-client/fixture-scenario.store';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { QUEUE_EXPIRY_CHECK_INTERVAL_MS } from '../config/constants';
import { RecommendationQueueRepository } from './recommendation-queue.repository';

const EXPIRY_INTERVAL_NAME = 'recommendation-queue-expiry';

/** TRD §13.5 장기 대기 만료, §13.6 재시작 복구 */
@Injectable()
export class QueueMaintenanceService {
  private readonly logger = new Logger(QueueMaintenanceService.name);

  constructor(
    private readonly queue: RecommendationQueueRepository,
    private readonly scenarios: FixtureScenarioStore,
    private readonly scheduler: SchedulerRegistry,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  /** 기동 시 recommending → queued (queued_at 유지로 순서 보존) */
  async recoverInterrupted(): Promise<number> {
    const count = await this.queue.recoverInterrupted();
    if (count > 0) this.logger.warn({ msg: 'queue_recovered', count });
    return count;
  }

  /** queued가 REC_QUEUE_MAX_WAIT_MIN분을 넘으면 failed E3001 */
  async expireStale(): Promise<number> {
    try {
      const ids = await this.queue.expireStale(this.config.queue.maxWaitMin);
      ids.forEach((id) => this.scenarios.forget(id));
      if (ids.length > 0)
        this.logger.warn({ msg: 'queue_expired', count: ids.length, analysisIds: ids });
      return ids.length;
    } catch (error) {
      this.logger.error({ msg: 'queue_expiry_failed', err: error });
      return 0;
    }
  }

  startExpiryJob(): void {
    const handle = setInterval(() => void this.expireStale(), QUEUE_EXPIRY_CHECK_INTERVAL_MS);
    this.scheduler.addInterval(EXPIRY_INTERVAL_NAME, handle);
  }
}
