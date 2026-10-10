import { Inject, Injectable, Logger } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { DataSource, Repository } from 'typeorm';
import {
  AI_CLIENT,
  AiCallContext,
  AiClient,
  SkinMetricsResult,
} from '../ai-client/ai-client.interface';
import { AiError } from '../ai-client/ai-error';
import { isMeasureRetryable, mapMeasureError } from '../ai-client/ai-error.mapper';
import { FixtureScenarioStore } from '../ai-client/fixture-scenario.store';
import { AnalysisStatus, ConcernCode, METRICS, Roi } from '../common/enums';
import { ApiException } from '../common/errors/api.exception';
import { ErrorCode } from '../common/errors/error-codes';
import { toIsoUtc } from '../common/utils/time';
import { newUlid } from '../common/utils/ulid';
import { rowsAndCount, toNumber } from '../database/raw-query';
import { RecommendationQueueRepository } from '../recommendation/recommendation-queue.repository';
import { AiHealthService } from '../system/ai-health.service';
import {
  NO_QUEUE_INFO,
  presentAnalysisDetail,
  presentTrendPoint,
  trendDelta,
} from './analysis-presenter';
import {
  AnalysisCreatedDto,
  AnalysisDetailDto,
  AnalysisListDto,
  AnalysisTrendDto,
} from './dto/analysis-response.dto';
import { Analysis } from './entities/analysis.entity';
import { Recommendation } from './entities/recommendation.entity';
import { SkinMetric } from './entities/skin-metric.entity';
import { Survey } from './entities/survey.entity';
import type { AnalysisSubmission } from './pipes/analysis-submission.pipe';
import { MetricBp, MetricRatios, bpToRatio, buildScoreReport, metricBp } from './scoring';

interface ListRow {
  id: string;
  created_at: Date;
  status: AnalysisStatus;
  overall_score: number;
  is_demo: boolean;
  concerns: ConcernCode[] | null;
}

@Injectable()
export class AnalysesService {
  private readonly logger = new Logger(AnalysesService.name);

  constructor(
    private readonly dataSource: DataSource,
    @InjectRepository(Analysis) private readonly analyses: Repository<Analysis>,
    @InjectRepository(SkinMetric) private readonly skinMetrics: Repository<SkinMetric>,
    @InjectRepository(Survey) private readonly surveys: Repository<Survey>,
    @InjectRepository(Recommendation) private readonly recommendations: Repository<Recommendation>,
    @Inject(AI_CLIENT) private readonly ai: AiClient,
    private readonly aiHealth: AiHealthService,
    private readonly queue: RecommendationQueueRepository,
    private readonly scenarios: FixtureScenarioStore,
  ) {}

  /**
   * TRD §10.1 처리 순서 7–11: AI 측정 → 버퍼 해제 → 점수 → 트랜잭션 저장 → 202.
   * 측정이 실패하면 분석 행을 만들지 않는다.
   */
  async create(
    userId: number,
    submission: AnalysisSubmission,
    ctx: AiCallContext,
  ): Promise<AnalysisCreatedDto> {
    const { result, ms } = await this.measure(submission, ctx);

    const bpByRoi: Partial<Record<Roi, MetricBp>> = {};
    for (const [roi, ratios] of Object.entries(result.metrics) as [Roi, MetricRatios][]) {
      bpByRoi[roi] = metricBp(ratios);
    }
    const report = buildScoreReport(bpByRoi);

    const analysisId = newUlid();
    // 워커가 커밋 직후 집어 갈 수 있으므로 시나리오를 먼저 기억한다.
    this.scenarios.remember(analysisId, ctx.scenario);
    try {
      await this.dataSource.transaction(async (manager) => {
        await manager.insert(Analysis, {
          id: analysisId,
          userId,
          status: 'queued',
          deviceType: submission.deviceType,
          isDemo: result.isStub,
          confidence: result.confidence,
          calibrationApplied: result.calibrationApplied,
          overallScore: report.overallScore,
          skinModelVersion: result.modelVersion,
          measureMs: ms,
        });
        await manager.insert(
          SkinMetric,
          (Object.entries(bpByRoi) as [Roi, MetricBp][]).map(([roi, bp]) => ({
            analysisId,
            roi,
            ...Object.fromEntries(METRICS.map((metric) => [metric, bpToRatio(bp[metric])])),
          })),
        );
        await manager.insert(Survey, {
          analysisId,
          gender: submission.survey.gender,
          ageBand: submission.survey.ageBand,
          skinType: submission.survey.skinType,
          payload: submission.survey,
        });
      });
    } catch (error) {
      this.scenarios.forget(analysisId);
      throw error;
    }

    this.logger.log({
      msg: 'analysis_created',
      analysisId,
      isDemo: result.isStub,
      overallScore: report.overallScore,
      measureMs: ms,
    });
    return { analysisId, status: 'queued', ...(await this.queueInfo(analysisId)) };
  }

  async findOne(userId: number, analysisId: string): Promise<AnalysisDetailDto> {
    const analysis = await this.findOwned(userId, analysisId);
    const [metrics, survey, recommendation] = await Promise.all([
      this.skinMetrics.findBy({ analysisId }),
      this.surveys.findOneBy({ analysisId }),
      analysis.status === 'done' ? this.recommendations.findOneBy({ analysisId }) : null,
    ]);
    if (!survey) throw new ApiException(ErrorCode.E4003); // 동시에 삭제됨
    const queue = analysis.status === 'queued' ? await this.queueInfo(analysisId) : NO_QUEUE_INFO;
    return presentAnalysisDetail({ analysis, metrics, survey, recommendation, queue });
  }

  /** TRD §10.3 — failed만 queued로. 0행이면 남의 것·없음 404, 상태가 다르면 409 E4005 */
  async retry(
    userId: number,
    analysisId: string,
    scenario: AiCallContext['scenario'],
  ): Promise<AnalysisCreatedDto> {
    const previous = this.scenarios.get(analysisId);
    this.scenarios.remember(analysisId, scenario);
    const requeued = await this.queue.requeueFailed(analysisId, userId);
    if (!requeued) {
      this.scenarios.remember(analysisId, previous);
      const exists = await this.analyses.existsBy({ id: analysisId, userId });
      throw new ApiException(exists ? ErrorCode.E4005 : ErrorCode.E4003);
    }
    this.logger.log({ msg: 'recommendation_retry_requested', analysisId });
    return { analysisId, status: 'queued', ...(await this.queueInfo(analysisId)) };
  }

  /** TRD §10.4 — created_at DESC, id DESC. concerns는 추천 완료 시에만 */
  async list(userId: number, page: number, size: number): Promise<AnalysisListDto> {
    const [rows, countRows] = await Promise.all([
      this.dataSource.query(
        `SELECT a.id, a.created_at, a.status, a.overall_score, a.is_demo, r.concerns
         FROM analyses a
         LEFT JOIN recommendations r ON r.analysis_id = a.id AND a.status = 'done'
         WHERE a.user_id = $1
         ORDER BY a.created_at DESC, a.id DESC
         LIMIT $2 OFFSET $3`,
        [userId, size, (page - 1) * size],
      ) as Promise<ListRow[]>,
      this.dataSource.query(`SELECT COUNT(*) AS count FROM analyses WHERE user_id = $1`, [
        userId,
      ]) as Promise<{ count: string }[]>,
    ]);
    const totalItems = toNumber(countRows[0].count);
    return {
      items: rows.map((row) => ({
        analysisId: row.id,
        createdAt: toIsoUtc(row.created_at),
        status: row.status,
        overallScore: row.overall_score,
        isDemo: row.is_demo,
        concerns: row.concerns ?? [],
      })),
      page: { page, size, totalItems, totalPages: Math.ceil(totalItems / size) },
    };
  }

  /**
   * TRD §10.5 — 이전 분석: 같은 사용자의 done·is_demo=false·더 이전 것 중 최신 1건.
   * 현재 분석이 데모 값이면 previous·delta는 null.
   */
  async trend(userId: number, analysisId: string): Promise<AnalysisTrendDto> {
    const current = await this.findOwned(userId, analysisId);
    const currentPoint = presentTrendPoint(
      current,
      await this.skinMetrics.findBy({ analysisId: current.id }),
    );
    if (current.isDemo) return { current: currentPoint, previous: null, delta: null };

    const previous = await this.analyses
      .createQueryBuilder('a')
      .where('a.user_id = :userId', { userId })
      .andWhere(`a.status = 'done'`)
      .andWhere('a.is_demo = false')
      .andWhere('a.created_at < :createdAt', { createdAt: current.createdAt })
      .orderBy('a.created_at', 'DESC')
      .addOrderBy('a.id', 'DESC')
      .limit(1)
      .getOne();
    if (!previous) return { current: currentPoint, previous: null, delta: null };

    const previousPoint = presentTrendPoint(
      previous,
      await this.skinMetrics.findBy({ analysisId: previous.id }),
    );
    return {
      current: currentPoint,
      previous: previousPoint,
      delta: trendDelta(currentPoint, previousPoint),
    };
  }

  /** TRD §10.6 — 상태 무관 삭제. 진행 중이면 워커가 결과를 버린다 (§13.7) */
  async remove(userId: number, analysisId: string): Promise<void> {
    const { count } = rowsAndCount(
      await this.dataSource.query(`DELETE FROM analyses WHERE id = $1 AND user_id = $2`, [
        analysisId,
        userId,
      ]),
    );
    if (count === 0) throw new ApiException(ErrorCode.E4003);
    this.scenarios.forget(analysisId);
    this.logger.log({ msg: 'analysis_deleted', analysisId });
  }

  /** IDOR: 없거나 남의 것이면 똑같이 404 E4003 */
  private async findOwned(userId: number, analysisId: string): Promise<Analysis> {
    const analysis = await this.analyses.findOneBy({ id: analysisId, userId });
    if (!analysis) throw new ApiException(ErrorCode.E4003);
    return analysis;
  }

  private async queueInfo(
    analysisId: string,
  ): Promise<{ queuePosition: number; estimatedWaitSec: number }> {
    const [queuePosition, avgMs] = await Promise.all([
      this.queue.queuePosition(analysisId),
      this.queue.averageRecommendMs(),
    ]);
    return { queuePosition, estimatedWaitSec: Math.round((queuePosition * avgMs) / 1000) };
  }

  /**
   * AI 측정 (TRD §12.5 측정 열): 연결 오류만 즉시 1회 재시도.
   * 성공·실패와 관계없이 끝나면 이미지 버퍼 참조를 끊는다.
   */
  private async measure(
    submission: AnalysisSubmission,
    ctx: AiCallContext,
  ): Promise<{ result: SkinMetricsResult; ms: number }> {
    const image = submission.image;
    if (!image) throw new Error('이미지가 이미 해제됐습니다');
    try {
      for (let attempt = 1; ; attempt++) {
        const started = Date.now();
        try {
          const result = await this.ai.measureSkin(
            { image: image.buffer, mimeType: image.mimeType, deviceType: submission.deviceType },
            ctx,
          );
          return { result, ms: Date.now() - started };
        } catch (error) {
          if (!(error instanceof AiError)) throw error;
          if (attempt === 1 && isMeasureRetryable(error)) continue;
          throw this.measureException(error, ctx);
        }
      }
    } finally {
      submission.image = null;
    }
  }

  private measureException(error: AiError, ctx: AiCallContext): ApiException {
    const failure = mapMeasureError(error);
    const fields = {
      msg: 'ai_measure_failed',
      code: failure.code,
      ai: error.toLog(),
      requestId: ctx.requestId,
    };
    if (failure.logLevel === 'error') this.logger.error(fields);
    else if (failure.logLevel === 'warn') this.logger.warn(fields);
    else this.logger.log(fields);
    if (error.kind === 'connection') this.aiHealth.markUnavailable('measure_connection_error');
    return new ApiException(failure.code, { message: failure.message });
  }
}
