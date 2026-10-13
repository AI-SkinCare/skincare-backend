package com.aiskincare.backend.analyses;

import com.aiskincare.backend.aiclient.AiModels.Ingredient;
import com.aiskincare.backend.aiclient.AiModels.MetricRatios;
import com.aiskincare.backend.aiclient.AiModels.RoutineStep;
import com.aiskincare.backend.analyses.AnalysisRows.AnalysisRow;
import com.aiskincare.backend.analyses.AnalysisRows.RecommendationRow;
import com.aiskincare.backend.analyses.AnalysisRows.SkinMetricRow;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DeviceType;
import com.aiskincare.backend.common.enums.Roi;
import com.aiskincare.backend.common.enums.SkinType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * analyses·skin_metrics·surveys·recommendations (TRD §8.1). 사용자 데이터 조회는 전부 user_id 조건 (IDOR, TRD
 * §15.1).
 */
@Repository
public class AnalysisRepository {
  private static final String ANALYSIS_COLUMNS =
      "id, user_id, status, device_type, is_demo, confidence, calibration_applied, overall_score,"
          + " skin_model_version, measure_ms, queued_at, completed_at, attempt_count, error_code,"
          + " created_at";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public AnalysisRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  // ── 생성 (트랜잭션은 서비스가) ──

  public void insertAnalysis(
      String id,
      long userId,
      DeviceType deviceType,
      boolean isDemo,
      double confidence,
      boolean calibrationApplied,
      int overallScore,
      String skinModelVersion,
      int measureMs) {
    jdbc.sql(
            """
            INSERT INTO analyses (id, user_id, status, device_type, is_demo, confidence,
                                  calibration_applied, overall_score, skin_model_version, measure_ms)
            VALUES (:id, :userId, 'queued', :deviceType, :isDemo, :confidence,
                    :calibration, :overall, :model, :measureMs)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("deviceType", deviceType.code())
        .param("isDemo", isDemo)
        .param("confidence", confidence)
        .param("calibration", calibrationApplied)
        .param("overall", overallScore)
        .param("model", skinModelVersion)
        .param("measureMs", measureMs)
        .update();
  }

  /** 0–1 원본을 4자리로 저장 (bp / 10000) */
  public void insertMetric(String analysisId, Roi roi, Scoring.MetricBp bp) {
    jdbc.sql(
            """
            INSERT INTO skin_metrics (analysis_id, roi, moisture, elasticity, pigmentation, pore)
            VALUES (:id, :roi, :m, :e, :p, :o)
            """)
        .param("id", analysisId)
        .param("roi", roi.code())
        .param("m", Scoring.bpToRatio(bp.moisture()))
        .param("e", Scoring.bpToRatio(bp.elasticity()))
        .param("p", Scoring.bpToRatio(bp.pigmentation()))
        .param("o", Scoring.bpToRatio(bp.pore()))
        .update();
  }

  public void insertSurvey(String analysisId, NormalizedSurvey survey) {
    jdbc.sql(
            """
            INSERT INTO surveys (analysis_id, gender, age_band, skin_type, payload)
            VALUES (:id, :gender, :ageBand, :skinType, CAST(:payload AS jsonb))
            """)
        .param("id", analysisId)
        .param("gender", survey.gender().code())
        .param("ageBand", survey.ageBand().code())
        .param("skinType", survey.skinType() == null ? null : survey.skinType().code())
        .param("payload", json.writeValueAsString(survey))
        .update();
  }

  // ── 조회 ──

  /** 없거나 남의 것이면 빈 값 (IDOR) */
  public Optional<AnalysisRow> findOwned(String id, long userId) {
    return jdbc.sql(
            "SELECT " + ANALYSIS_COLUMNS + " FROM analyses WHERE id = :id AND user_id = :userId")
        .param("id", id)
        .param("userId", userId)
        .query(AnalysisRepository::mapAnalysis)
        .optional();
  }

  /** 워커 전용 (요청 맥락 없음) */
  public Optional<AnalysisRow> findById(String id) {
    return jdbc.sql("SELECT " + ANALYSIS_COLUMNS + " FROM analyses WHERE id = :id")
        .param("id", id)
        .query(AnalysisRepository::mapAnalysis)
        .optional();
  }

  public boolean existsOwned(String id, long userId) {
    return jdbc.sql("SELECT EXISTS(SELECT 1 FROM analyses WHERE id = :id AND user_id = :userId)")
        .param("id", id)
        .param("userId", userId)
        .query(Boolean.class)
        .single();
  }

  public List<SkinMetricRow> metrics(String analysisId) {
    return jdbc.sql(
            """
            SELECT roi, moisture, elasticity, pigmentation, pore
            FROM skin_metrics WHERE analysis_id = :id
            """)
        .param("id", analysisId)
        .query(
            (rs, i) ->
                new SkinMetricRow(
                    Roi.fromCode(rs.getString("roi")),
                    new MetricRatios(
                        rs.getBigDecimal("moisture").doubleValue(),
                        rs.getBigDecimal("elasticity").doubleValue(),
                        rs.getBigDecimal("pigmentation").doubleValue(),
                        rs.getBigDecimal("pore").doubleValue())))
        .list();
  }

  public Optional<NormalizedSurvey> survey(String analysisId) {
    return jdbc.sql("SELECT payload FROM surveys WHERE analysis_id = :id")
        .param("id", analysisId)
        .query((rs, i) -> json.readValue(rs.getString("payload"), NormalizedSurvey.class))
        .optional();
  }

  public Optional<RecommendationRow> recommendation(String analysisId) {
    return jdbc.sql(
            """
            SELECT skin_type, concerns, ingredients, routine, reasoning, answer, disclaimer,
                   input_notes, filtered, used_fallback, model_version
            FROM recommendations WHERE analysis_id = :id
            """)
        .param("id", analysisId)
        .query(
            (rs, i) ->
                new RecommendationRow(
                    SkinType.fromCode(rs.getString("skin_type")),
                    json
                        .readValue(rs.getString("concerns"), new TypeReference<List<String>>() {})
                        .stream()
                        .map(Concern::fromCode)
                        .toList(),
                    json.readValue(
                        rs.getString("ingredients"), new TypeReference<List<Ingredient>>() {}),
                    json.readValue(
                        rs.getString("routine"), new TypeReference<List<RoutineStep>>() {}),
                    json.readValue(rs.getString("reasoning"), new TypeReference<List<String>>() {}),
                    rs.getString("answer"),
                    rs.getString("disclaimer"),
                    json.readValue(
                        rs.getString("input_notes"), new TypeReference<List<String>>() {}),
                    rs.getBoolean("filtered"),
                    rs.getBoolean("used_fallback"),
                    rs.getString("model_version")))
        .optional();
  }

  /** 이력 한 줄 (TRD §10.4) — concerns는 추천 완료 시에만 */
  public record ListRow(
      String id,
      Instant createdAt,
      AnalysisStatus status,
      int overallScore,
      boolean isDemo,
      List<Concern> concerns) {}

  public List<ListRow> list(long userId, int page, int size) {
    return jdbc.sql(
            """
            SELECT a.id, a.created_at, a.status, a.overall_score, a.is_demo, r.concerns
            FROM analyses a
            LEFT JOIN recommendations r ON r.analysis_id = a.id AND a.status = 'done'
            WHERE a.user_id = :userId
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT :size OFFSET :offset
            """)
        .param("userId", userId)
        .param("size", size)
        .param("offset", (page - 1) * size)
        .query(
            (rs, i) -> {
              String concerns = rs.getString("concerns");
              return new ListRow(
                  rs.getString("id"),
                  instant(rs, "created_at"),
                  AnalysisStatus.fromCode(rs.getString("status")),
                  rs.getInt("overall_score"),
                  rs.getBoolean("is_demo"),
                  concerns == null
                      ? List.of()
                      : json.readValue(concerns, new TypeReference<List<String>>() {}).stream()
                          .map(Concern::fromCode)
                          .toList());
            })
        .list();
  }

  public long count(long userId) {
    return jdbc.sql("SELECT COUNT(*) FROM analyses WHERE user_id = :userId")
        .param("userId", userId)
        .query(Long.class)
        .single();
  }

  /** 추이: 같은 사용자의 done·is_demo=false·더 이전 것 중 최신 1건 (TRD §10.5) */
  public Optional<AnalysisRow> previousDone(long userId, Instant before) {
    return jdbc.sql(
            "SELECT "
                + ANALYSIS_COLUMNS
                + """
                 FROM analyses
                WHERE user_id = :userId AND status = 'done' AND is_demo = false
                  AND created_at < :before
                ORDER BY created_at DESC, id DESC LIMIT 1
                """)
        .param("userId", userId)
        .param("before", OffsetDateTime.ofInstant(before, java.time.ZoneOffset.UTC))
        .query(AnalysisRepository::mapAnalysis)
        .optional();
  }

  /** 상태 무관 삭제 (TRD §10.6). 지운 행 수 */
  public int deleteOwned(String id, long userId) {
    return jdbc.sql("DELETE FROM analyses WHERE id = :id AND user_id = :userId")
        .param("id", id)
        .param("userId", userId)
        .update();
  }

  // ── 요청 제한 (TRD §10.1 처리 순서 2) ──

  public boolean hasInProgress(long userId) {
    return jdbc.sql(
            """
            SELECT EXISTS(SELECT 1 FROM analyses
                          WHERE user_id = :userId AND status IN ('queued', 'recommending'))
            """)
        .param("userId", userId)
        .query(Boolean.class)
        .single();
  }

  public long countCreatedSince(long userId, Instant since) {
    return jdbc.sql(
            "SELECT COUNT(*) FROM analyses WHERE user_id = :userId AND created_at >= :since")
        .param("userId", userId)
        .param("since", OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC))
        .query(Long.class)
        .single();
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  static AnalysisRow mapAnalysis(ResultSet rs, int row) throws SQLException {
    return new AnalysisRow(
        rs.getString("id"),
        rs.getLong("user_id"),
        AnalysisStatus.fromCode(rs.getString("status")),
        DeviceType.fromCode(rs.getString("device_type")),
        rs.getBoolean("is_demo"),
        rs.getBigDecimal("confidence").doubleValue(),
        rs.getBoolean("calibration_applied"),
        rs.getInt("overall_score"),
        rs.getString("skin_model_version"),
        rs.getInt("measure_ms"),
        instant(rs, "queued_at"),
        instant(rs, "completed_at"),
        rs.getInt("attempt_count"),
        rs.getString("error_code"),
        instant(rs, "created_at"));
  }
}
