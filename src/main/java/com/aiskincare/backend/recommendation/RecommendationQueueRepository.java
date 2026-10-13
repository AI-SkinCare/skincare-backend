package com.aiskincare.backend.recommendation;

import com.aiskincare.backend.aiclient.AiModels.RecommendResult;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.config.Constants;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 추천 작업 큐 SQL (TRD §13.3, SC-04 §4). PostgreSQL만 쓴다 — Redis·Bull 금지. FOR UPDATE SKIP LOCKED라 raw
 * SQL.
 */
@Repository
public class RecommendationQueueRepository {
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;

  public RecommendationQueueRepository(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
  }

  /** 가장 오래된 queued 1건을 recommending으로 바꾸고 id를 돌려준다 */
  public Optional<String> acquire() {
    return jdbc.sql(
            """
            UPDATE analyses SET status = 'recommending', rec_started_at = now(),
                   queue_wait_ms = (EXTRACT(EPOCH FROM now() - queued_at) * 1000)::int,
                   attempt_count = attempt_count + 1
            WHERE id = (SELECT id FROM analyses WHERE status = 'queued'
                        ORDER BY queued_at LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING id
            """)
        .query(String.class)
        .optional();
  }

  /** 재시도로 추천을 한 번 더 부를 때 */
  public void incrementAttempt(String analysisId) {
    jdbc.sql(
            """
            UPDATE analyses SET attempt_count = attempt_count + 1
            WHERE id = :id AND status = 'recommending'
            """)
        .param("id", analysisId)
        .update();
  }

  /** 추천 결과 저장. 분석 행 갱신이 0행이면(탈퇴·삭제됨) 결과를 버린다 (TRD §13.7) — 이 순서 덕분에 FK 오류로 500이 나지 않는다. */
  public boolean markDone(String analysisId, long recommendMs, RecommendResult r) {
    Boolean saved =
        tx.execute(
            status -> {
              int updated =
                  jdbc.sql(
                          """
                          UPDATE analyses SET status = 'done', completed_at = now(), recommend_ms = :ms
                          WHERE id = :id AND status = 'recommending'
                          """)
                      .param("id", analysisId)
                      .param("ms", recommendMs)
                      .update();
              if (updated == 0) return false;
              jdbc.sql(
                      """
                      INSERT INTO recommendations
                        (analysis_id, skin_type, concerns, ingredients, routine, reasoning, answer,
                         disclaimer, input_notes, filtered, used_fallback, model_version, inference_ms)
                      VALUES (:id, :skinType, CAST(:concerns AS jsonb), CAST(:ingredients AS jsonb),
                              CAST(:routine AS jsonb), CAST(:reasoning AS jsonb), :answer, :disclaimer,
                              CAST(:notes AS jsonb), :filtered, :fallback, :model, :inferenceMs)
                      ON CONFLICT (analysis_id) DO UPDATE SET
                        skin_type = EXCLUDED.skin_type, concerns = EXCLUDED.concerns,
                        ingredients = EXCLUDED.ingredients, routine = EXCLUDED.routine,
                        reasoning = EXCLUDED.reasoning, answer = EXCLUDED.answer,
                        disclaimer = EXCLUDED.disclaimer, input_notes = EXCLUDED.input_notes,
                        filtered = EXCLUDED.filtered, used_fallback = EXCLUDED.used_fallback,
                        model_version = EXCLUDED.model_version,
                        inference_ms = EXCLUDED.inference_ms, created_at = now()
                      """)
                  .param("id", analysisId)
                  .param("skinType", r.skinType().code())
                  .param(
                      "concerns",
                      json.writeValueAsString(r.concerns().stream().map(Concern::code).toList()))
                  .param("ingredients", json.writeValueAsString(r.ingredients()))
                  .param("routine", json.writeValueAsString(r.routine()))
                  .param("reasoning", json.writeValueAsString(r.reasoning()))
                  .param("answer", r.answer())
                  .param("disclaimer", r.disclaimer())
                  .param("notes", json.writeValueAsString(r.inputNotes()))
                  .param("filtered", r.filtered())
                  .param("fallback", r.usedFallback())
                  .param("model", r.modelVersion())
                  .param("inferenceMs", r.inferenceMs())
                  .update();
              return true;
            });
    return Boolean.TRUE.equals(saved);
  }

  public boolean markFailed(String analysisId, ErrorCode code, Long recommendMs) {
    return jdbc.sql(
                """
                UPDATE analyses SET status = 'failed', error_code = :code, completed_at = now(),
                       recommend_ms = :ms
                WHERE id = :id AND status = 'recommending'
                """)
            .param("id", analysisId)
            .param("code", code.name())
            .param("ms", recommendMs)
            .update()
        > 0;
  }

  /** 사용자 재요청: failed → queued (TRD §10.3). attempt_count는 누적 유지 */
  public boolean requeueFailed(String analysisId, long userId) {
    return jdbc.sql(
                """
                UPDATE analyses
                SET status = 'queued', queued_at = now(), error_code = NULL, completed_at = NULL,
                    rec_started_at = NULL, queue_wait_ms = NULL, recommend_ms = NULL
                WHERE id = :id AND user_id = :userId AND status = 'failed'
                """)
            .param("id", analysisId)
            .param("userId", userId)
            .update()
        > 0;
  }

  /** 내 queued_at보다 앞선 queued 건수 + 1 */
  public long queuePosition(String analysisId) {
    return jdbc.sql(
            """
            SELECT COUNT(*) + 1 FROM analyses
            WHERE status = 'queued' AND queued_at < (SELECT queued_at FROM analyses WHERE id = :id)
            """)
        .param("id", analysisId)
        .query(Long.class)
        .single();
  }

  /** 최근 완료 20건 평균 추천 시간 (없으면 55000ms) */
  public double averageRecommendMs() {
    return jdbc.sql(
            """
            SELECT COALESCE(AVG(recommend_ms), :fallback) FROM
              (SELECT recommend_ms FROM analyses WHERE status = 'done' AND recommend_ms IS NOT NULL
               ORDER BY completed_at DESC LIMIT 20) t
            """)
        .param("fallback", Constants.DEFAULT_RECOMMEND_MS)
        .query(Double.class)
        .single();
  }

  public long queuedCount() {
    return jdbc.sql("SELECT COUNT(*) FROM analyses WHERE status = 'queued'")
        .query(Long.class)
        .single();
  }

  /** 서버 기동 시: recommending → queued (queued_at 유지로 순서 보존) */
  public int recoverInterrupted() {
    return jdbc.sql("UPDATE analyses SET status = 'queued' WHERE status = 'recommending'").update();
  }

  /** 장기 대기: queued가 maxWaitMin분을 넘으면 failed E3001 */
  public List<String> expireStale(int maxWaitMin) {
    return jdbc.sql(
            """
            UPDATE analyses SET status = 'failed', error_code = 'E3001', completed_at = now()
            WHERE status = 'queued' AND queued_at < now() - make_interval(mins => :minutes)
            RETURNING id
            """)
        .param("minutes", maxWaitMin)
        .query(String.class)
        .list();
  }
}
