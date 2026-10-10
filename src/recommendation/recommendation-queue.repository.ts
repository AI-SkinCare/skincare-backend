import { Injectable } from '@nestjs/common';
import { DataSource, EntityManager } from 'typeorm';
import type { RecommendResult } from '../ai-client/ai-client.interface';
import type { AnalysisFailureCode } from '../common/errors/error-codes';
import { DEFAULT_RECOMMEND_MS } from '../config/constants';
import { rowsAndCount, toNumber } from '../database/raw-query';

/**
 * 추천 작업 큐 SQL (TRD §13.3, SC-04 §4). PostgreSQL만 쓴다 — Redis·Bull 금지.
 * FOR UPDATE SKIP LOCKED를 쓰므로 QueryBuilder 대신 raw query.
 */
@Injectable()
export class RecommendationQueueRepository {
  constructor(private readonly dataSource: DataSource) {}

  /** 가장 오래된 queued 1건을 recommending으로 바꾸고 id를 돌려준다 */
  async acquire(): Promise<string | null> {
    const { rows } = rowsAndCount<{ id: string }>(
      await this.dataSource.query(`
        UPDATE analyses SET status = 'recommending', rec_started_at = now(),
               queue_wait_ms = (EXTRACT(EPOCH FROM now() - queued_at) * 1000)::int,
               attempt_count = attempt_count + 1
        WHERE id = (SELECT id FROM analyses WHERE status = 'queued'
                    ORDER BY queued_at LIMIT 1 FOR UPDATE SKIP LOCKED)
        RETURNING id
      `),
    );
    return rows[0]?.id ?? null;
  }

  /** 재시도로 추천을 한 번 더 부를 때 */
  async incrementAttempt(analysisId: string): Promise<void> {
    await this.dataSource.query(
      `UPDATE analyses SET attempt_count = attempt_count + 1
       WHERE id = $1 AND status = 'recommending'`,
      [analysisId],
    );
  }

  /**
   * 추천 결과 저장. 분석 행 갱신이 0행이면(탈퇴·삭제됨) 결과를 버린다 (TRD §13.7).
   * 이 순서 덕분에 FK 오류로 500이 나지 않는다.
   */
  async markDone(
    analysisId: string,
    recommendMs: number,
    result: RecommendResult,
  ): Promise<boolean> {
    return this.dataSource.transaction(async (manager) => {
      const { count } = rowsAndCount(
        await manager.query(
          `UPDATE analyses SET status = 'done', completed_at = now(), recommend_ms = $2
           WHERE id = $1 AND status = 'recommending' RETURNING id`,
          [analysisId, recommendMs],
        ),
      );
      if (count === 0) return false;
      await this.upsertRecommendation(manager, analysisId, result);
      return true;
    });
  }

  async markFailed(
    analysisId: string,
    code: AnalysisFailureCode,
    recommendMs: number | null,
  ): Promise<boolean> {
    const { count } = rowsAndCount(
      await this.dataSource.query(
        `UPDATE analyses SET status = 'failed', error_code = $2, completed_at = now(),
                recommend_ms = $3
         WHERE id = $1 AND status = 'recommending' RETURNING id`,
        [analysisId, code, recommendMs],
      ),
    );
    return count > 0;
  }

  /** 사용자 재요청: failed → queued (TRD §10.3). attempt_count는 누적 유지 */
  async requeueFailed(analysisId: string, userId: number): Promise<boolean> {
    const { count } = rowsAndCount(
      await this.dataSource.query(
        `UPDATE analyses
         SET status = 'queued', queued_at = now(), error_code = NULL, completed_at = NULL,
             rec_started_at = NULL, queue_wait_ms = NULL, recommend_ms = NULL
         WHERE id = $1 AND user_id = $2 AND status = 'failed'
         RETURNING id`,
        [analysisId, userId],
      ),
    );
    return count > 0;
  }

  /** 내 queued_at보다 앞선 queued 건수 + 1 */
  async queuePosition(analysisId: string): Promise<number> {
    const rows: { position: string }[] = await this.dataSource.query(
      `SELECT COUNT(*) + 1 AS position FROM analyses
       WHERE status = 'queued' AND queued_at < (SELECT queued_at FROM analyses WHERE id = $1)`,
      [analysisId],
    );
    return toNumber(rows[0].position);
  }

  /** 최근 완료 20건 평균 추천 시간 (없으면 55000ms) */
  async averageRecommendMs(): Promise<number> {
    const rows: { avg: string }[] = await this.dataSource.query(
      `SELECT COALESCE(AVG(recommend_ms), $1) AS avg FROM
         (SELECT recommend_ms FROM analyses WHERE status = 'done' AND recommend_ms IS NOT NULL
          ORDER BY completed_at DESC LIMIT 20) t`,
      [DEFAULT_RECOMMEND_MS],
    );
    return toNumber(rows[0].avg);
  }

  async queuedCount(): Promise<number> {
    const rows: { count: string }[] = await this.dataSource.query(
      `SELECT COUNT(*) AS count FROM analyses WHERE status = 'queued'`,
    );
    return toNumber(rows[0].count);
  }

  /** 서버 기동 시: recommending → queued (queued_at 유지로 순서 보존) */
  async recoverInterrupted(): Promise<number> {
    const { count } = rowsAndCount(
      await this.dataSource.query(
        `UPDATE analyses SET status = 'queued' WHERE status = 'recommending' RETURNING id`,
      ),
    );
    return count;
  }

  /** 장기 대기: queued가 maxWaitMin분을 넘으면 failed E3001 */
  async expireStale(maxWaitMin: number): Promise<string[]> {
    const { rows } = rowsAndCount<{ id: string }>(
      await this.dataSource.query(
        `UPDATE analyses SET status = 'failed', error_code = 'E3001', completed_at = now()
         WHERE status = 'queued' AND queued_at < now() - make_interval(mins => $1)
         RETURNING id`,
        [maxWaitMin],
      ),
    );
    return rows.map((row) => row.id);
  }

  private async upsertRecommendation(
    manager: EntityManager,
    analysisId: string,
    r: RecommendResult,
  ): Promise<void> {
    await manager.query(
      `INSERT INTO recommendations
         (analysis_id, skin_type, concerns, ingredients, routine, reasoning, answer, disclaimer,
          input_notes, filtered, used_fallback, model_version, inference_ms)
       VALUES ($1, $2, $3::jsonb, $4::jsonb, $5::jsonb, $6::jsonb, $7, $8, $9::jsonb, $10, $11, $12, $13)
       ON CONFLICT (analysis_id) DO UPDATE SET
         skin_type = EXCLUDED.skin_type, concerns = EXCLUDED.concerns,
         ingredients = EXCLUDED.ingredients, routine = EXCLUDED.routine,
         reasoning = EXCLUDED.reasoning, answer = EXCLUDED.answer,
         disclaimer = EXCLUDED.disclaimer, input_notes = EXCLUDED.input_notes,
         filtered = EXCLUDED.filtered, used_fallback = EXCLUDED.used_fallback,
         model_version = EXCLUDED.model_version, inference_ms = EXCLUDED.inference_ms,
         created_at = now()`,
      [
        analysisId,
        r.skinType,
        JSON.stringify(r.concerns),
        JSON.stringify(r.ingredients),
        JSON.stringify(r.routine),
        JSON.stringify(r.reasoning),
        r.answer,
        r.disclaimer,
        JSON.stringify(r.inputNotes),
        r.filtered,
        r.usedFallback,
        r.modelVersion,
        r.inferenceMs,
      ],
    );
  }
}
