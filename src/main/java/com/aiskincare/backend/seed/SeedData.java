package com.aiskincare.backend.seed;

import com.aiskincare.backend.analyses.Scoring;
import com.aiskincare.backend.analyses.Scoring.MetricBp;
import com.aiskincare.backend.common.enums.Concern;
import com.aiskincare.backend.common.enums.Metric;
import com.aiskincare.backend.common.enums.Roi;
import com.aiskincare.backend.common.util.Ulid;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시드·통합 테스트가 같이 쓰는 완료(done) 분석 생성기 (TRD §8.4). 날짜를 최근 수 주에 분산, is_demo=false(이력·추이 화면 확인용),
 * full_face + 일부 ROI, 설문·추천 포함.
 */
public final class SeedData {
  private SeedData() {}

  private static final List<Roi> SEED_ROIS =
      List.of(Roi.FULL_FACE, Roi.FOREHEAD, Roi.CHEEK_LEFT, Roi.CHEEK_RIGHT, Roi.CHIN);
  private static final List<List<Concern>> CONCERN_SETS =
      List.of(
          List.of(Concern.PORE, Concern.PIGMENTATION),
          List.of(Concern.DRYNESS),
          List.of(Concern.PORE),
          List.of(Concern.REDNESS, Concern.SENSITIVITY),
          List.of(Concern.WRINKLE, Concern.SAGGING));

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

  private static int clampBp(double bp) {
    return (int) Math.max(0, Math.min(10000, Math.round(bp)));
  }

  /** userId에게 done 분석 count건. 0번이 가장 최근, 약 1.5일 간격으로 과거로 간다 (과거일수록 지표가 조금 나쁨) */
  public static List<String> insertDoneAnalyses(
      JdbcClient jdbc, JsonMapper json, long userId, int count, Instant now) {
    Prng random = new Prng((int) (userId * 7919 + count));
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Instant createdAt = now.minus(Duration.ofHours(i * 36L + 2));
      String id = Ulid.generate(createdAt.toEpochMilli());
      int drift = i * 60;
      MetricBp base =
          new MetricBp(
              clampBp(6200 - drift + random.next() * 800),
              clampBp(7000 - drift + random.next() * 600),
              clampBp(3800 + drift + random.next() * 600),
              clampBp(4400 + drift + random.next() * 600));
      Map<Roi, MetricBp> byRoi = new EnumMap<>(Roi.class);
      for (Roi roi : SEED_ROIS) {
        if (roi == Roi.FULL_FACE) {
          byRoi.put(roi, base);
          continue;
        }
        int[] v = new int[4];
        for (Metric metric : Metric.values()) {
          v[metric.ordinal()] = clampBp(base.get(metric) + (random.next() - 0.5) * 1200);
        }
        byRoi.put(roi, new MetricBp(v[0], v[1], v[2], v[3]));
      }
      var report = Scoring.report(byRoi);
      long queueWaitMs = Math.round(2000 + random.next() * 20000);
      long recommendMs = Math.round(48000 + random.next() * 12000);
      Instant recStartedAt = createdAt.plusMillis(queueWaitMs);
      Instant completedAt = recStartedAt.plusMillis(recommendMs);
      List<Concern> concerns = CONCERN_SETS.get(i % CONCERN_SETS.size());

      jdbc.sql(
              """
              INSERT INTO analyses (id, user_id, status, device_type, is_demo, confidence,
                calibration_applied, overall_score, skin_model_version, measure_ms, queued_at,
                rec_started_at, completed_at, queue_wait_ms, recommend_ms, attempt_count, created_at)
              VALUES (:id, :userId, 'done', 'smartphone', false, 0.85, true, :overall, 'skin_reg_v1',
                :measureMs, :createdAt, :recStartedAt, :completedAt, :queueWaitMs, :recommendMs, 1,
                :createdAt)
              """)
          .param("id", id)
          .param("userId", userId)
          .param("overall", report.overallScore())
          .param("measureMs", Math.round(800 + random.next() * 1500))
          .param("createdAt", utc(createdAt))
          .param("recStartedAt", utc(recStartedAt))
          .param("completedAt", utc(completedAt))
          .param("queueWaitMs", queueWaitMs)
          .param("recommendMs", recommendMs)
          .update();
      byRoi.forEach(
          (roi, bp) ->
              jdbc.sql(
                      """
                      INSERT INTO skin_metrics (analysis_id, roi, moisture, elasticity, pigmentation, pore)
                      VALUES (:id, :roi, :m, :e, :p, :o)
                      """)
                  .param("id", id)
                  .param("roi", roi.code())
                  .param("m", Scoring.bpToRatio(bp.moisture()))
                  .param("e", Scoring.bpToRatio(bp.elasticity()))
                  .param("p", Scoring.bpToRatio(bp.pigmentation()))
                  .param("o", Scoring.bpToRatio(bp.pore()))
                  .update());
      Map<String, Object> survey = new java.util.LinkedHashMap<>();
      survey.put("gender", "female");
      survey.put("ageBand", "20s");
      survey.put("skinType", "combination");
      survey.put("sleepHours", 6);
      survey.put("waterIntakeLevel", "low");
      survey.put("sunExposureLevel", "high");
      survey.put("stressLevel", "medium");
      survey.put("concerns", concerns.stream().map(Concern::code).toList());
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
                CAST(:routine AS jsonb), CAST(:reasoning AS jsonb), :answer, :disclaimer, '[]'::jsonb,
                false, false, 'rec_sllm_v2', :inferenceMs, :createdAt)
              """)
          .param("id", id)
          .param("concerns", json.writeValueAsString(concerns.stream().map(Concern::code).toList()))
          .param(
              "ingredients",
              json.writeValueAsString(
                  List.of(
                      Map.of(
                          "name",
                          "나이아신아마이드",
                          "purpose",
                          "피지 분비와 모공 관리에 도움을 줄 수 있습니다.",
                          "priority",
                          1),
                      Map.of(
                          "name", "판테놀", "purpose", "피부 장벽을 지키는 데 도움을 줄 수 있습니다.", "priority", 2))))
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
                      "시드 데이터입니다. 피지 분비와 생활 습관이 피부 상태에 영향을 줄 수 있습니다.",
                      "나이아신아마이드와 판테놀은 피부 관리에 도움을 줄 수 있습니다.",
                      "아침에는 자외선 차단제를, 저녁에는 보습제를 챙겨 주세요.")))
          .param("answer", "피지 관리와 자외선 차단을 함께 챙기는 것이 도움이 될 수 있습니다.")
          .param("disclaimer", "본 결과는 참고용 정보이며 의학적 진단이나 치료를 대신하지 않습니다. 피부 이상이 지속되면 피부과 전문의와 상담하세요.")
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
