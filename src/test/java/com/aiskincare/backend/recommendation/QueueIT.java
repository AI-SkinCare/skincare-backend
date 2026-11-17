package com.aiskincare.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.aiclient.AiClient;
import com.aiskincare.backend.aiclient.FixtureAiClient;
import com.aiskincare.backend.aiclient.FixtureScenarioStore;
import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.config.Constants;
import com.aiskincare.backend.support.Factories.LoggedIn;
import com.aiskincare.backend.support.IntegrationTestBase;
import com.aiskincare.backend.support.TestHttp.Res;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** 추천 큐·워커 (TRD §13). 워커는 자동 시작하지 않고 tick()을 직접 부른다. */
class QueueIT extends IntegrationTestBase {
  @Autowired RecommendationWorker worker;
  @Autowired QueueMaintenance maintenance;
  @Autowired FixtureScenarioStore scenarios;
  @Autowired AiClient ai;

  private FixtureAiClient fixtureAi() {
    return (FixtureAiClient) ai;
  }

  private Map<String, Object> row(String id) {
    return jdbc.sql("SELECT * FROM analyses WHERE id = :id").param("id", id).query().singleRow();
  }

  private JsonNode detail(LoggedIn me, String id) {
    Res res = http.get("/api/v1/analyses/" + id).bearer(me.accessToken()).send();
    assertThat(res.status()).isEqualTo(200);
    return res.data();
  }

  private Res retry(LoggedIn me, String id, String scenario) {
    var req =
        http.post("/api/v1/analyses/" + id + "/recommendation/retry").bearer(me.accessToken());
    if (scenario != null) req.header("X-Fixture-Scenario", scenario);
    return req.send();
  }

  @Test
  @DisplayName("TC-ANA-04: QUEUED → GENERATING → COMPLETED, 완료 시 추천 필드 모두")
  void flow() throws Exception {
    sleeper.holdSlow();
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me, "rec-slow");
    assertThat(statusOf(id)).isEqualTo("QUEUED");

    var tick = CompletableFuture.runAsync(worker::tick);
    until(() -> "GENERATING".equals(statusOf(id)));
    JsonNode during = detail(me, id);
    assertThat(during.get("status").stringValue()).isEqualTo("GENERATING");
    assertThat(during.get("queuePosition").isNull()).isTrue();
    assertThat(during.get("estimatedWaitSec").isNull()).isTrue();
    assertThat(during.get("recommendationStatus").isNull()).isTrue();
    assertThat(during.get("recommendation").isNull()).isTrue();
    assertThat(during.get("disease").get("labelEn").stringValue()).isEqualTo("atopic");

    sleeper.releaseSlow();
    tick.join();
    assertThat(sleeper.calls()).contains(FixtureAiClient.REC_SLOW_DELAY);
    JsonNode done = detail(me, id);
    assertThat(done.get("status").stringValue()).isEqualTo("COMPLETED");
    assertThat(done.get("recommendationStatus").stringValue()).isEqualTo("generated");
    assertThat(done.get("completedAt").stringValue()).endsWith("Z");
    assertThat(done.get("guidance").size()).isEqualTo(2);
    assertThat(done.get("error").isNull()).isTrue();
    var keys = new TreeSet<String>();
    done.get("recommendation").propertyNames().forEach(keys::add);
    assertThat(keys)
        .containsExactly(
            "answer",
            "concerns",
            "disclaimer",
            "filtered",
            "inferenceMs",
            "ingredients",
            "inputNotes",
            "modelVersion",
            "reasoning",
            "routine",
            "skinType",
            "usedFallback");
    JsonNode rec = done.get("recommendation");
    assertThat(rec.get("skinType").toString()).isEqualTo("{\"code\":\"dry\",\"label\":\"건성\"}");
    assertThat(rec.get("concerns").toString())
        .isEqualTo("[{\"code\":\"sensitivity\",\"label\":\"민감성(트러블/자극감)\"}]");
    assertThat(rec.get("modelVersion").stringValue()).isEqualTo("rec_sllm_v2");
    assertThat(rec.get("ingredients").size()).isEqualTo(4);
    assertThat(rec.get("ingredients").get(1).get("purpose").isNull()).isTrue();
    assertThat(rec.get("routine").get(0).get("time").stringValue()).isEqualTo("anytime");

    var row = row(id);
    assertThat(row)
        .containsEntry("attempt_count", 1)
        .containsEntry("error_code", null)
        .containsEntry("recommendation_status", "generated");
    assertThat(row.get("queue_wait_ms")).isInstanceOf(Integer.class);
    assertThat(row.get("recommend_ms")).isInstanceOf(Integer.class);
    assertThat(row.get("rec_started_at")).isInstanceOf(Timestamp.class);
    assertThat(count("recommendations")).isEqualTo(1);
  }

  @Test
  @DisplayName("(개정) 추천 요청 = 저장한 분류 JSON 전체(값 그대로) + 설문 라벨, metrics 없음")
  void recommendRequest() {
    LoggedIn me = factories.userWithToken();
    factories.submitOk(me);
    fixtureAi().clearRecordedRequests();
    worker.tick();
    var requests = fixtureAi().recommendRequests();
    assertThat(requests).hasSize(1);
    Map<String, Object> disease = requests.get(0).disease();
    assertThat(disease)
        .containsEntry("label", "아토피")
        .containsEntry("label_en", "atopic")
        .containsEntry("confidence", 0.9)
        .containsEntry("uncertain", false)
        .containsEntry("model_version", "quality_test_fixture_not_a_patient_prediction");
    assertThat(disease.get("probabilities"))
        .isEqualTo(
            Map.of(
                "건선", 0.019999999999999997,
                "아토피", 0.9,
                "여드름", 0.019999999999999997,
                "정상", 0.019999999999999997,
                "주사", 0.019999999999999997,
                "지루", 0.019999999999999997));
    assertThat(requests.get(0).survey())
        .containsEntry("skinType", "combination")
        .containsEntry("selfReportedConcerns", List.of("모공", "미백(색소침착/기미/칙칙함)"))
        .doesNotContainKey("concerns");
    assertThat(sleeper.calls()).containsExactly(FixtureAiClient.REC_DELAY);
  }

  @Test
  @DisplayName("(개정) 분류 불확실 → 접수는 QUEUED, 워커가 WITHHELD uncertain 저장 (추천 행 없음, 재요청 409)")
  void withheldUncertain() {
    LoggedIn me = factories.userWithToken();
    Res created = factories.submit(me, "uncertain");
    assertThat(created.status()).isEqualTo(202);
    assertThat(created.data().get("status").stringValue()).isEqualTo("QUEUED");
    assertThat(created.data().get("disease").get("uncertain").booleanValue()).isTrue();
    String id = created.data().get("analysisId").stringValue();

    worker.tick();
    assertThat(sleeper.calls()).isEmpty();
    assertThat(row(id))
        .containsEntry("status", "WITHHELD")
        .containsEntry("recommendation_status", "uncertain")
        .containsEntry("error_code", null);
    assertThat(count("recommendations")).isZero();

    JsonNode data = detail(me, id);
    assertThat(data.get("status").stringValue()).isEqualTo("WITHHELD");
    assertThat(data.get("recommendationStatus").stringValue()).isEqualTo("uncertain");
    assertThat(data.get("recommendation").isNull()).isTrue();
    assertThat(data.get("error").isNull()).isTrue();
    assertThat(data.get("completedAt").stringValue()).endsWith("Z");
    assertThat(data.get("guidance").toString()).contains("자동 성분 추천을 보류했습니다");

    Res retry = retry(me, id, null);
    assertThat(retry.status()).isEqualTo(409);
    assertThat(retry.errorCode()).isEqualTo("E4005");
  }

  @Test
  @DisplayName("(개정) 정상·고민 없음 → WITHHELD no_concern")
  void withheldNoConcern() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me, "no-concern");
    worker.tick();
    JsonNode data = detail(me, id);
    assertThat(data.get("status").stringValue()).isEqualTo("WITHHELD");
    assertThat(data.get("recommendationStatus").stringValue()).isEqualTo("no_concern");
    assertThat(data.get("disease").get("labelEn").stringValue()).isEqualTo("normal");
    assertThat(data.get("recommendation").isNull()).isTrue();
  }

  @Test
  @DisplayName("(개정) 보류는 평균 추천 시간(예상 대기)에 넣지 않는다")
  void withheldNotInAverage() {
    LoggedIn first = factories.userWithToken();
    String id = factories.submitOk(first, "uncertain");
    worker.tick();
    jdbc.sql("UPDATE analyses SET recommend_ms = 10 WHERE id = :id").param("id", id).update();
    LoggedIn second = factories.userWithToken();
    Res res = factories.submit(second);
    assertThat(res.data().get("estimatedWaitSec").intValue()).isEqualTo(55);
  }

  @Test
  @DisplayName(
      "TC-ANA-05: rec-timeout → FAILED E3002 (attempt 1, 재시도 없음), 분류 유지 → ok로 재요청 → COMPLETED (같은 분류)")
  void timeoutThenRetry() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me, "rec-timeout");
    fixtureAi().clearRecordedRequests();
    worker.tick();
    assertThat(row(id))
        .containsEntry("status", "FAILED")
        .containsEntry("error_code", "E3002")
        .containsEntry("attempt_count", 1);
    assertThat(sleeper.calls()).doesNotContain(Constants.WORKER_RETRY_DELAY);

    JsonNode failed = detail(me, id);
    assertThat(failed.get("status").stringValue()).isEqualTo("FAILED");
    assertThat(failed.get("recommendation").isNull()).isTrue();
    assertThat(failed.get("recommendationStatus").isNull()).isTrue();
    assertThat(failed.get("error").toString())
        .isEqualTo("{\"code\":\"E3002\",\"message\":\"추천을 만들지 못했어요. 다시 시도해 주세요.\"}");
    assertThat(failed.get("disease").get("labelEn").stringValue()).isEqualTo("atopic");

    Res retry = retry(me, id, "ok");
    assertThat(retry.status()).isEqualTo(202);
    assertThat(retry.data().get("status").stringValue()).isEqualTo("QUEUED");
    assertThat(retry.data().get("queuePosition").intValue()).isEqualTo(1);
    assertThat(retry.data().get("estimatedWaitSec").intValue()).isEqualTo(55);
    assertThat(retry.data().get("error").isNull()).isTrue();
    assertThat(row(id))
        .containsEntry("status", "QUEUED")
        .containsEntry("error_code", null)
        .containsEntry("completed_at", null)
        .containsEntry("recommend_ms", null);

    worker.tick();
    assertThat(row(id)).containsEntry("status", "COMPLETED").containsEntry("attempt_count", 2);
    // 재시도는 분류를 다시 하지 않고 저장한 값을 그대로 보낸다
    var requests = fixtureAi().recommendRequests();
    assertThat(requests).hasSize(2);
    assertThat(requests.get(1).disease()).isEqualTo(requests.get(0).disease());
  }

  @Test
  @DisplayName("재요청은 FAILED일 때만 — COMPLETED면 409 E4005")
  void retryOnlyFailed() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    worker.tick();
    Res res = retry(me, id, null);
    assertThat(res.status()).isEqualTo(409);
    assertThat(res.errorCode()).isEqualTo("E4005");
  }

  @Test
  @DisplayName("연결 오류는 5초 후 1회 재시도(attempt +1) → 실패하면 FAILED E3001")
  void connectionRetry() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    scenarios.remember(id, FixtureScenario.AI_DOWN);
    worker.tick();
    assertThat(sleeper.calls()).containsExactly(Constants.WORKER_RETRY_DELAY);
    assertThat(row(id))
        .containsEntry("status", "FAILED")
        .containsEntry("error_code", "E3001")
        .containsEntry("attempt_count", 2);
  }

  @Test
  @DisplayName("TC-ANA-08: GENERATING 중 재시작(복구 함수) → QUEUED(queued_at 유지) → COMPLETED")
  void recovery() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    Object queuedAt = row(id).get("queued_at");
    jdbc.sql("UPDATE analyses SET status = 'GENERATING', rec_started_at = now() WHERE id = :id")
        .param("id", id)
        .update();

    assertThat(maintenance.recoverInterrupted()).isEqualTo(1);
    assertThat(row(id)).containsEntry("status", "QUEUED").containsEntry("queued_at", queuedAt);
    worker.tick();
    assertThat(statusOf(id)).isEqualTo("COMPLETED");
  }

  @Test
  @DisplayName("처리 중 삭제(§13.7): 결과를 버리고 FK 오류 없이 끝난다")
  void deletedWhileProcessing() throws Exception {
    sleeper.holdSlow();
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me, "rec-slow");
    var tick = CompletableFuture.runAsync(worker::tick);
    until(() -> "GENERATING".equals(statusOf(id)));
    assertThat(http.delete("/api/v1/analyses/" + id).bearer(me.accessToken()).send().status())
        .isEqualTo(204);
    sleeper.releaseSlow();
    tick.join();
    assertThat(count("recommendations")).isZero();
    assertThat(worker.isBusy()).isFalse();
  }

  @Test
  @DisplayName("설문 행이 없는 어긋난 데이터는 GENERATING에 멈추지 않고 FAILED E4001")
  void surveyMissing() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    jdbc.sql("DELETE FROM surveys WHERE analysis_id = :id").param("id", id).update();
    worker.tick();
    assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("error_code", "E4001");
  }

  @Test
  @DisplayName("장기 대기: QUEUED가 60분을 넘으면 FAILED E3001")
  void expiry() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    jdbc.sql("UPDATE analyses SET queued_at = now() - interval '61 minutes' WHERE id = :id")
        .param("id", id)
        .update();
    assertThat(maintenance.expireStale()).isEqualTo(1);
    assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("error_code", "E3001");
  }

  @Test
  @DisplayName("AI 비가용이면 워커는 큐를 건드리지 않는다")
  void aiUnavailable() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    aiHealth.markUnavailable("test");
    worker.tick();
    assertThat(statusOf(id)).isEqualTo("QUEUED");
    aiHealth.check();
  }

  @Test
  @DisplayName("대기 순번·예상 대기: 오래된 순, 평균 추천 시간 반영")
  void queuePosition() {
    List<LoggedIn> users =
        List.of(factories.userWithToken(), factories.userWithToken(), factories.userWithToken());
    List<String> ids = new ArrayList<>();
    for (LoggedIn u : users) ids.add(factories.submitOk(u));
    JsonNode third = detail(users.get(2), ids.get(2));
    assertThat(third.get("queuePosition").intValue()).isEqualTo(3);
    assertThat(third.get("estimatedWaitSec").intValue()).isEqualTo(165);
    JsonNode status = http.get("/api/v1/system/status").send().data();
    assertThat(status.get("aiAvailable").booleanValue()).isTrue();
    assertThat(status.get("queueLength").intValue()).isEqualTo(3);
    assertThat(status.get("estimatedWaitSec").intValue()).isEqualTo(165);
  }

  @Test
  @DisplayName("TC-PERF-04: rec-slow로 1건 처리 중 + 대기 10건 → 12번째부터 503 E3003 (Retry-After 60)")
  void queueLimit() throws Exception {
    sleeper.holdSlow();
    List<LoggedIn> sessions = new ArrayList<>();
    for (int i = 0; i < 15; i++) sessions.add(factories.userWithToken());

    String first = factories.submitOk(sessions.get(0), "rec-slow");
    var tick = CompletableFuture.runAsync(worker::tick);
    until(() -> "GENERATING".equals(statusOf(first)));

    List<Integer> statuses = new ArrayList<>();
    for (LoggedIn session : sessions.subList(1, 15)) {
      Res res = factories.submit(session);
      statuses.add(res.status());
      if (res.status() == 503) {
        assertThat(res.errorCode()).isEqualTo("E3003");
        assertThat(res.header("Retry-After")).isEqualTo("60");
      }
    }
    // 2–11번째 계정: 202 (대기 10건), 12–15번째: 503
    List<Integer> expected = new ArrayList<>();
    for (int i = 0; i < 10; i++) expected.add(202);
    for (int i = 0; i < 4; i++) expected.add(503);
    assertThat(statuses).isEqualTo(expected);

    sleeper.releaseSlow();
    tick.join();
  }
}
