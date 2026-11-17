package com.aiskincare.backend.analyses;

import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.aiclient.AiModels.Ingredient;
import com.aiskincare.backend.aiclient.AiModels.RoutineStep;
import com.aiskincare.backend.analyses.AnalysisRows.AnalysisRow;
import com.aiskincare.backend.analyses.AnalysisRows.RecommendationRow;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DiseaseLabel;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import com.aiskincare.backend.common.enums.SkinType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * analyses·surveys·recommendations (TRD §8.1). 사용자 데이터 조회는 전부 user_id 조건 (IDOR, TRD §15.1). 분류
 * JSON은 반올림하지 않고 저장한다 — 추천 호출·재시도 때 그대로 다시 보낸다.
 */
@Repository
public class AnalysisRepository {
  private static final List<String> ANALYSIS_COLUMNS =
      List.of(
          "id",
          "user_id",
          "status",
          "disease_label",
          "disease_confidence",
          "disease_uncertain",
          "disease_probabilities",
          "disease_model_version",
          "disease_inference_ms",
          "classify_ms",
          "recommendation_status",
          "guidance",
          "queued_at",
          "completed_at",
          "attempt_count",
          "error_code",
          "created_at");
  private static final String COLUMNS = String.join(", ", ANALYSIS_COLUMNS);

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public AnalysisRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  // ── 생성 (트랜잭션은 서비스가) ──

  public void insertAnalysis(String id, long userId, DiseaseResult disease, int classifyMs) {
    jdbc.sql(
            """
            INSERT INTO analyses (id, user_id, status, disease_label, disease_confidence,
                                  disease_uncertain, disease_probabilities, disease_model_version,
                                  disease_inference_ms, classify_ms)
            VALUES (:id, :userId, 'QUEUED', :label, :confidence, :uncertain,
                    CAST(:probabilities AS jsonb), :model, :inferenceMs, :classifyMs)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("label", disease.label().code())
        .param("confidence", disease.confidence())
        .param("uncertain", disease.uncertain())
        .param("probabilities", json.writeValueAsString(disease.probabilities()))
        .param("model", disease.modelVersion())
        .param("inferenceMs", disease.inferenceMs())
        .param("classifyMs", classifyMs)
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
    return jdbc.sql("SELECT " + COLUMNS + " FROM analyses WHERE id = :id AND user_id = :userId")
        .param("id", id)
        .param("userId", userId)
        .query(this::mapAnalysis)
        .optional();
  }

  /** 워커 전용 (요청 맥락 없음) */
  public Optional<AnalysisRow> findById(String id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM analyses WHERE id = :id")
        .param("id", id)
        .query(this::mapAnalysis)
        .optional();
  }

  public boolean existsOwned(String id, long userId) {
    return jdbc.sql("SELECT EXISTS(SELECT 1 FROM analyses WHERE id = :id AND user_id = :userId)")
        .param("id", id)
        .param("userId", userId)
        .query(Boolean.class)
        .single();
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
                   input_notes, filtered, used_fallback, model_version, inference_ms
            FROM recommendations WHERE analysis_id = :id
            """)
        .param("id", analysisId)
        .query(
            (rs, i) ->
                new RecommendationRow(
                    SkinType.fromCode(rs.getString("skin_type")),
                    concerns(rs.getString("concerns")),
                    json.readValue(
                        rs.getString("ingredients"), new TypeReference<List<Ingredient>>() {}),
                    json.readValue(
                        rs.getString("routine"), new TypeReference<List<RoutineStep>>() {}),
                    strings(rs.getString("reasoning")),
                    rs.getString("answer"),
                    rs.getString("disclaimer"),
                    strings(rs.getString("input_notes")),
                    rs.getBoolean("filtered"),
                    rs.getBoolean("used_fallback"),
                    rs.getString("model_version"),
                    rs.getInt("inference_ms")))
        .optional();
  }

  /** 이력 한 줄 (TRD §10.4) — concerns는 추천 완료(COMPLETED) 시에만 */
  public record ListRow(AnalysisRow analysis, List<Concern> concerns) {}

  public List<ListRow> list(long userId, int page, int size) {
    String columns = String.join(", ", ANALYSIS_COLUMNS.stream().map(c -> "a." + c).toList());
    return jdbc.sql(
            "SELECT "
                + columns
                + """
                , r.concerns AS rec_concerns
                FROM analyses a
                LEFT JOIN recommendations r ON r.analysis_id = a.id AND a.status = 'COMPLETED'
                WHERE a.user_id = :userId
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT :size OFFSET :offset
                """)
        .param("userId", userId)
        .param("size", size)
        .param("offset", (page - 1) * size)
        .query(
            (rs, i) -> {
              String concerns = rs.getString("rec_concerns");
              return new ListRow(
                  mapAnalysis(rs, i), concerns == null ? List.of() : concerns(concerns));
            })
        .list();
  }

  public long count(long userId) {
    return jdbc.sql("SELECT COUNT(*) FROM analyses WHERE user_id = :userId")
        .param("userId", userId)
        .query(Long.class)
        .single();
  }

  /** 상태 무관 삭제 (TRD §10.5). 지운 행 수 */
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
                          WHERE user_id = :userId AND status IN ('QUEUED', 'GENERATING'))
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

  AnalysisRow mapAnalysis(ResultSet rs, int row) throws SQLException {
    return new AnalysisRow(
        rs.getString("id"),
        rs.getLong("user_id"),
        AnalysisStatus.fromCode(rs.getString("status")),
        new DiseaseResult(
            DiseaseLabel.fromCode(rs.getString("disease_label")),
            rs.getDouble("disease_confidence"),
            rs.getBoolean("disease_uncertain"),
            probabilities(rs.getString("disease_probabilities")),
            rs.getString("disease_model_version"),
            rs.getInt("disease_inference_ms")),
        rs.getInt("classify_ms"),
        RecommendationStatus.fromCode(rs.getString("recommendation_status")),
        strings(rs.getString("guidance")),
        instant(rs, "queued_at"),
        instant(rs, "completed_at"),
        rs.getInt("attempt_count"),
        rs.getString("error_code"),
        instant(rs, "created_at"));
  }

  /** JSONB는 키 순서를 보존하지 않는다 → 부록 A.2(DiseaseLabel) 순서로 다시 놓는다 */
  private Map<String, Double> probabilities(String value) {
    Map<String, Double> stored = json.readValue(value, new TypeReference<Map<String, Double>>() {});
    Map<String, Double> ordered = new LinkedHashMap<>();
    for (DiseaseLabel label : DiseaseLabel.values()) {
      Double probability = stored.get(label.label());
      if (probability != null) ordered.put(label.label(), probability);
    }
    return Collections.unmodifiableMap(ordered);
  }

  private List<String> strings(String value) {
    return json.readValue(value, new TypeReference<List<String>>() {});
  }

  private List<Concern> concerns(String value) {
    return strings(value).stream().map(Concern::fromCode).toList();
  }
}
