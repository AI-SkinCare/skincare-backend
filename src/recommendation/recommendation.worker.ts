import { randomUUID } from 'node:crypto';
import {
  BeforeApplicationShutdown,
  Inject,
  Injectable,
  Logger,
  OnApplicationBootstrap,
} from '@nestjs/common';
import { SchedulerRegistry } from '@nestjs/schedule';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import {
  AI_CLIENT,
  AiCallContext,
  AiClient,
  RecommendRequest,
  RecommendResult,
} from '../ai-client/ai-client.interface';
import { AiError } from '../ai-client/ai-error';
import { isRecommendRetryable, mapRecommendError } from '../ai-client/ai-error.mapper';
import { buildRecommendRequest, metricsFromRows } from '../ai-client/ai-transform';
import { FixtureScenarioStore } from '../ai-client/fixture-scenario.store';
import { Analysis } from '../analyses/entities/analysis.entity';
import { SkinMetric } from '../analyses/entities/skin-metric.entity';
import { Survey } from '../analyses/entities/survey.entity';
import { ErrorCode } from '../common/errors/error-codes';
import { SLEEP, Sleep } from '../common/utils/sleep';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { WORKER_POLL_INTERVAL_MS, WORKER_RETRY_DELAY_MS } from '../config/constants';
import { AiHealthService } from '../system/ai-health.service';
import { QueueMaintenanceService } from './queue-maintenance.service';
import { RecommendationQueueRepository } from './recommendation-queue.repository';

const POLL_INTERVAL_NAME = 'recommendation-worker';

type CallOutcome =
  { ok: true; result: RecommendResult; ms: number } | { ok: false; error: AiError; ms: number };

/**
 * 추천 작업 워커 (TRD §13.2). 동시성 1 — GPU 생성이 직렬이다.
 * NODE_ENV=test에서는 자동 시작하지 않고 테스트가 tick()을 직접 부른다.
 */
@Injectable()
export class RecommendationWorker implements OnApplicationBootstrap, BeforeApplicationShutdown {
  private readonly logger = new Logger(RecommendationWorker.name);
  private busy = false;

  constructor(
    private readonly queue: RecommendationQueueRepository,
    private readonly maintenance: QueueMaintenanceService,
    private readonly aiHealth: AiHealthService,
    private readonly scenarios: FixtureScenarioStore,
    private readonly scheduler: SchedulerRegistry,
    @Inject(AI_CLIENT) private readonly ai: AiClient,
    @Inject(SLEEP) private readonly sleep: Sleep,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @InjectRepository(Analysis) private readonly analyses: Repository<Analysis>,
    @InjectRepository(SkinMetric) private readonly skinMetrics: Repository<SkinMetric>,
    @InjectRepository(Survey) private readonly surveys: Repository<Survey>,
  ) {}

  /** 기동 순서: ① 복구 쿼리 ② 첫 헬스체크 ③ 폴링 시작 */
  async onApplicationBootstrap(): Promise<void> {
    if (this.config.nodeEnv === 'test') return;
    await this.maintenance.recoverInterrupted();
    await this.aiHealth.start();
    const handle = setInterval(() => void this.tick(), WORKER_POLL_INTERVAL_MS);
    this.scheduler.addInterval(POLL_INTERVAL_NAME, handle);
    this.maintenance.startExpiryJob();
    this.logger.log({ msg: 'worker_started' });
  }

  /** SIGTERM: 폴링만 멈추고 진행 중 작업은 기다리지 않는다 — 재시작 복구가 다시 큐에 넣는다 */
  beforeApplicationShutdown(): void {
    if (this.scheduler.doesExist('interval', POLL_INTERVAL_NAME)) {
      this.scheduler.deleteInterval(POLL_INTERVAL_NAME);
    }
  }

  get isBusy(): boolean {
    return this.busy;
  }

  async tick(): Promise<void> {
    if (this.busy || !this.aiHealth.isAvailable()) return;
    this.busy = true;
    try {
      const analysisId = await this.queue.acquire();
      if (analysisId) await this.process(analysisId);
    } catch (error) {
      this.logger.error({ msg: 'worker_tick_failed', err: error });
    } finally {
      this.busy = false;
    }
  }

  private async process(analysisId: string): Promise<void> {
    // 워커 작업은 요청 맥락이 없으므로 작업마다 새 requestId (TRD §7.5)
    const requestId = randomUUID();
    const ctx = { analysisId, requestId };
    try {
      const analysis = await this.analyses.findOneBy({ id: analysisId });
      if (!analysis) {
        this.logger.log({ msg: 'recommendation_skipped_deleted', ...ctx });
        return;
      }
      const [metricRows, survey] = await Promise.all([
        this.skinMetrics.findBy({ analysisId }),
        this.surveys.findOneBy({ analysisId }),
      ]);
      if (!survey) {
        // 같은 트랜잭션으로 저장하므로 정상이라면 없을 수 없다. 방금 삭제된 경우면 0행이라 아무 일도 없고,
        // 데이터가 어긋난 경우면 recommending에 멈추지 않게 failed로 끝낸다.
        if (await this.queue.markFailed(analysisId, ErrorCode.E4001, null)) {
          this.logger.error({ msg: 'recommendation_survey_missing', ...ctx });
        } else {
          this.logger.log({ msg: 'recommendation_skipped_deleted', ...ctx });
        }
        return;
      }

      // is_demo=true면 metrics: null (FR-MEA-04)
      const request = buildRecommendRequest(
        analysis.isDemo ? null : metricsFromRows(metricRows),
        survey.payload,
      );
      const outcome = await this.callWithPolicy(analysisId, request, {
        requestId,
        scenario: this.scenarios.get(analysisId),
      });
      // TC-ANA-09를 원문 없이 확인할 수 있게 측정값 전송 여부(ROI 목록 또는 null)만 남긴다
      const metricsSent = request.metrics === null ? null : Object.keys(request.metrics);

      if (outcome.ok) {
        if (outcome.result.warnings.length > 0) {
          this.logger.warn({
            msg: 'ai_recommend_count_warning',
            warnings: outcome.result.warnings,
            ...ctx,
          });
        }
        const saved = await this.queue.markDone(analysisId, outcome.ms, outcome.result);
        this.logger.log({
          msg: saved ? 'recommendation_done' : 'recommendation_discarded',
          recommendMs: outcome.ms,
          metricsSent,
          ...ctx,
        });
        return;
      }

      const failure = mapRecommendError(outcome.error);
      const fields = {
        msg: 'recommendation_failed',
        code: failure.code,
        ai: outcome.error.toLog(),
        recommendMs: outcome.ms,
        metricsSent,
        ...ctx,
      };
      if (failure.logLevel === 'error') this.logger.error(fields);
      else this.logger.warn(fields);
      const saved = await this.queue.markFailed(analysisId, failure.code, outcome.ms);
      if (!saved) this.logger.log({ msg: 'recommendation_discarded', ...ctx });
    } catch (error) {
      this.logger.error({ msg: 'recommendation_unexpected_error', err: error, ...ctx });
      await this.queue.markFailed(analysisId, ErrorCode.E4001, null).catch(() => undefined);
    } finally {
      this.scenarios.forget(analysisId);
    }
  }

  /**
   * TRD §12.5 워커 열: 연결 오류·503만 5초 후 1회 재시도(attempt_count + 1).
   * 504·422·우리 쪽 타임아웃은 재시도하지 않는다 — AI는 타임아웃 뒤에도 GPU를 붙잡고 생성을 계속한다.
   * recommend_ms는 마지막 호출의 왕복 시간.
   */
  private async callWithPolicy(
    analysisId: string,
    request: RecommendRequest,
    ctx: AiCallContext,
  ): Promise<CallOutcome> {
    for (let attempt = 1; ; attempt++) {
      const started = Date.now();
      try {
        const result = await this.ai.recommend(request, ctx);
        return { ok: true, result, ms: Date.now() - started };
      } catch (error) {
        if (!(error instanceof AiError)) throw error;
        const ms = Date.now() - started;
        if (attempt === 1 && isRecommendRetryable(error)) {
          this.logger.warn({
            msg: 'ai_recommend_retry',
            ai: error.toLog(),
            analysisId,
            requestId: ctx.requestId,
          });
          await this.sleep(WORKER_RETRY_DELAY_MS);
          await this.queue.incrementAttempt(analysisId);
          continue;
        }
        return { ok: false, error, ms };
      }
    }
  }
}
