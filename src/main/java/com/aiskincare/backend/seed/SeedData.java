package com.aiskincare.backend.seed;

import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.DiseaseLabel;
import com.aiskincare.backend.common.util.Ulid;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시드·통합 테스트가 같이 쓰는 완료(COMPLETED) 분석 생성기 (TRD §8.4). 날짜를 최근 수 주에 분산, 분류 라벨을 돌려 가며(정상 제외 5종), 설문·추천 행
 * 포함. 분류는 시험용 값이고 실제 판정이 아니다.
 */
public final class SeedData {
  private SeedData() {}

  /** 시드 분류의 model_version — 실제 분류기 출력이 아님을 드러낸다 */
  public static final String SEED_MODEL_VERSION = "seed_not_a_patient_prediction";

  private record Sample(DiseaseLabel label, List<Concern> concerns) {}

  private static final List<Sample> SAMPLES =
      List.of(
          new Sample(DiseaseLabel.ATOPIC, List.of(Concern.SENSITIVITY)),
          new Sample(DiseaseLabel.ACNE, List.of(Concern.ACNE)),
          new Sample(DiseaseLabel.ROSACEA, List.of(Concern.REDNESS, Concern.SENSITIVITY)),
          new Sample(DiseaseLabel.SEBORRHEIC, List.of(Concern.SENSITIVITY)),
          new Sample(DiseaseLabel.PSORIASIS, List.of(Concern.SENSITIVITY)));

  private static final List<String> GUIDANCE =
      List.of(
          "이미지 모델의 6종 분류 참고 결과이며 의학적 진단이 아닙니다. 분류 점수만으로 다른 질환을 배제할 수 없습니다.",
          "성분 추천은 화장품 수준의 보조 관리 정보입니다. 질환 확인과 치료는 피부과 전문의와 상담하세요.");

  /** 결정적 의사 난수 (mulberry32) — 다시 돌려도 같은 값 */
  private static final class Prng {
    private int state;

    Prng(int seed) {
      this.state = seed;
    }

    double next() {
      state += 0x6D2B79F5;
      int t = state;
      t = (t ^ (t >>> 15)) * (t | 1);
      t ^= t + (t ^ (t >>> 7)) * (t | 61);
      return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }
  }

  /** 선택 라벨이 confidence, 나머지 5종이 남은 값을 나눠 가진다 (합 1, 선택 라벨이 최고 점수) */
  private static Map<String, Double> probabilities(DiseaseLabel chosen, double confidence) {
    double rest = (1 - confidence) / (DiseaseLabel.values().length - 1);
    Map<String, Double> out = new LinkedHashMap<>();
    for (DiseaseLabel label : DiseaseLabel.values()) {
      out.put(label.label(), label == chosen ? confidence : rest);
    }
    return out;
  }

  /** userId에게 COMPLETED 분석 count건. 0번이 가장 최근, 약 1.5일 간격으로 과거로 간다 */
  public static List<String> insertCompletedAnalyses(
      JdbcClient jdbc, JsonMapper json, long userId, int count, Instant now) {
    Prng random = new Prng((int) (userId * 7919 + count));
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Instant createdAt = now.minus(Duration.ofHours(i * 36L + 2));
      String id = Ulid.generate(createdAt.toEpochMilli());
      Sample sample = SAMPLES.get(i % SAMPLES.size());
      double confidence = Math.round((0.7 + random.next() * 0.25) * 100) / 100.0;
      long queueWaitMs = Math.round(2000 + random.next() * 20000);
      long recommendMs = Math.round(40000 + random.next() * 12000);
      Instant recStartedAt = createdAt.plusMillis(queueWaitMs);
      Instant completedAt = recStartedAt.plusMillis(recommendMs);

      jdbc.sql(
              """
              INSERT INTO analyses (id, user_id, status, disease_label, disease_confidence,
                disease_uncertain, disease_probabilities, disease_model_version, disease_inference_ms,
                classify_ms, recommendation_status, guidance, queued_at, rec_started_at,
                completed_at, queue_wait_ms, recommend_ms, attempt_count, created_at)
              VALUES (:id, :userId, 'COMPLETED', :label, :confidence, false,
                CAST(:probabilities AS jsonb), :model, :inferenceMs, :classifyMs, 'generated',
                CAST(:guidance AS jsonb), :createdAt, :recStartedAt, :completedAt, :queueWaitMs,
                :recommendMs, 1, :createdAt)
              """)
          .param("id", id)
          .param("userId", userId)
          .param("label", sample.label().code())
          .param("confidence", confidence)
          .param(
              "probabilities", json.writeValueAsString(probabilities(sample.label(), confidence)))
          .param("model", SEED_MODEL_VERSION)
          .param("inferenceMs", Math.round(30 + random.next() * 40))
          .param("classifyMs", Math.round(300 + random.next() * 1500))
          .param("guidance", json.writeValueAsString(GUIDANCE))
          .param("createdAt", utc(createdAt))
          .param("recStartedAt", utc(recStartedAt))
          .param("completedAt", utc(completedAt))
          .param("queueWaitMs", queueWaitMs)
          .param("recommendMs", recommendMs)
          .update();
      Map<String, Object> survey = new LinkedHashMap<>();
      survey.put("gender", "female");
      survey.put("ageBand", "20s");
      survey.put("skinType", "combination");
      survey.put("sleepHours", 6);
      survey.put("waterIntakeLevel", "low");
      survey.put("sunExposureLevel", "high");
      survey.put("stressLevel", "medium");
      survey.put("concerns", List.of());
      survey.put("currentProducts", List.of("토너", "수분크림"));
      survey.put("note", null);
      jdbc.sql(
              """
              INSERT INTO surveys (analysis_id, gender, age_band, skin_type, payload)
              VALUES (:id, 'female', '20s', 'combination', CAST(:payload AS jsonb))
              """)
          .param("id", id)
          .param("payload", json.writeValueAsString(survey))
          .update();
      jdbc.sql(
              """
              INSERT INTO recommendations (analysis_id, skin_type, concerns, ingredients, routine,
                reasoning, answer, disclaimer, input_notes, filtered, used_fallback, model_version,
                inference_ms, created_at)
              VALUES (:id, 'combination', CAST(:concerns AS jsonb), CAST(:ingredients AS jsonb),
                CAST(:routine AS jsonb), CAST(:reasoning AS jsonb), :answer, :disclaimer,
                CAST(:notes AS jsonb), false, false, 'rec_sllm_v2', :inferenceMs, :createdAt)
              """)
          .param("id", id)
          .param(
              "concerns",
              json.writeValueAsString(sample.concerns().stream().map(Concern::code).toList()))
          .param(
              "ingredients",
              json.writeValueAsString(
                  List.of(
                      Map.of("name", "판테놀", "purpose", "피부 장벽을 지키는 데 도움을 줄 수 있습니다.", "priority", 1),
                      Map.of("name", "세라마이드", "purpose", "보습 관리에 도움을 줄 수 있습니다.", "priority", 2))))
          .param(
              "routine",
              json.writeValueAsString(
                  List.of(
                      Map.of("step", 1, "time", "morning", "action", "약산성 클렌저로 가볍게 세안합니다."),
                      Map.of("step", 2, "time", "morning", "action", "자외선 차단제를 바릅니다."),
                      Map.of("step", 3, "time", "evening", "action", "보습제를 충분히 바릅니다."))))
          .param(
              "reasoning",
              json.writeValueAsString(
                  List.of(
                      "시드 데이터입니다. 건조함과 생활 습관이 피부 상태에 영향을 줄 수 있습니다.",
                      "판테놀과 세라마이드는 피부 관리에 도움을 줄 수 있습니다.",
                      "아침에는 자외선 차단제를, 저녁에는 보습제를 챙겨 주세요.")))
          .param("answer", "순한 세안과 충분한 보습을 함께 챙기는 것이 도움이 될 수 있습니다.")
          .param("disclaimer", "본 결과는 참고용 정보이며 의학적 진단이나 치료를 대신하지 않습니다. 피부 이상이 지속되면 피부과 전문의와 상담하세요.")
          .param("notes", json.writeValueAsString(List.of("시드 데이터 (실제 판정 아님)")))
          .param("inferenceMs", recommendMs - 300)
          .param("createdAt", utc(completedAt))
          .update();
      ids.add(id);
    }
    return ids;
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
