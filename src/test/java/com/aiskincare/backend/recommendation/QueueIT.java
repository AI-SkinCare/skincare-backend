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

  @Test
  @DisplayName("TC-ANA-04: queued → recommending → done, 완료 시 추천 필드 모두")
  void flow() throws Exception {
    sleeper.holdSlow();
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me, "rec-slow");
    assertThat(statusOf(id)).isEqualTo("queued");

    var tick = CompletableFuture.runAsync(worker::tick);
    until(() -> "recommending".equals(statusOf(id)));
    JsonNode during = detail(me, id);
    assertThat(during.get("status").stringValue()).isEqualTo("recommending");
    assertThat(during.get("queuePosition").isNull()).isTrue();
    assertThat(during.get("estimatedWaitSec").isNull()).isTrue();
    assertThat(during.get("recommendation").toString()).isEqualTo("{\"status\":\"pending\"}");

    sleeper.releaseSlow();
    tick.join();
    assertThat(sleeper.calls()).contains(FixtureAiClient.REC_SLOW_DELAY);
    JsonNode done = detail(me, id);
    assertThat(done.get("status").stringValue()).isEqualTo("done");
    assertThat(done.get("completedAt").stringValue()).endsWith("Z");
    var keys = new TreeSet<String>();
    done.get("recommendation").propertyNames().forEach(keys::add);
    assertThat(keys)
        .containsExactly(
            "answer",
            "concerns",
            "disclaimer",
            "filtered",
            "ingredients",
            "inputNotes",
            "modelVersion",
            "reasoning",
            "routine",
            "skinType",
            "status",
            "usedFallback");
    JsonNode rec = done.get("recommendation");
    assertThat(rec.get("status").stringValue()).isEqualTo("done");
    assertThat(rec.get("skinType").toString())
        .isEqualTo("{\"code\":\"combination\",\"label\":\"복합성\"}");
    assertThat(rec.get("concerns").toString())
        .isEqualTo(
            "[{\"code\":\"pore\",\"label\":\"모공\"},{\"code\":\"pigmentation\",\"label\":\"미백(색소침착/기미/칙칙함)\"}]");
    assertThat(rec.get("modelVersion").stringValue()).isEqualTo("rec_sllm_v2");
    assertThat(rec.get("routine").get(0).get("time").stringValue()).isEqualTo("anytime");

    var row = row(id);
    assertThat(row).containsEntry("attempt_count", 1).containsEntry("error_code", null);
    assertThat(row.get("queue_wait_ms")).isInstanceOf(Integer.class);
    assertThat(row.get("recommend_ms")).isInstanceOf(Integer.class);
    assertThat(row.get("rec_started_at")).isInstanceOf(Timestamp.class);
  }

  @Test
  @DisplayName("TC-ANA-09: 데모 측정(is_stub) → 추천 요청 metrics: null, 설문은 라벨로")
  void demoMetricsNull() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    assertThat(row(id).get("is_demo")).isEqualTo(true);
    fixtureAi().clearRecordedRequests();
    worker.tick();
    var requests = fixtureAi().recommendRequests();
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).metrics()).isNull();
    assertThat(requests.get(0).survey())
        .containsEntry("skinType", "복합성")
        .containsEntry("selfReportedConcerns", List.of("모공", "미백(색소침착/기미/칙칙함)"))
        .doesNotContainKey("concerns");
    assertThat(sleeper.calls()).containsExactly(FixtureAiClient.REC_DELAY);
  }

  @Test
  @DisplayName("데모가 아닌 측정은 저장된 모든 ROI를 0–1 그대로 보낸다")
  void realMetrics() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    jdbc.sql("UPDATE analyses SET is_demo = false WHERE id = :id").param("id", id).update();
    fixtureAi().clearRecordedRequests();
    worker.tick();
    assertThat(fixtureAi().recommendRequests().get(0).metrics())
        .isEqualTo(
            Map.of(
                "full_face",
                Map.of(
                    "moisture", 0.697, "elasticity", 0.493, "pigmentation", 0.434, "pore", 0.658)));
  }

  @Test
  @DisplayName("TC-ANA-05: rec-timeout → failed E3002 (attempt 1, 재시도 없음), 측정 유지 → ok로 재요청 → done")
  void timeoutThenRetry() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me, "rec-timeout");
    worker.tick();
    assertThat(row(id))
        .containsEntry("status", "failed")
        .containsEntry("error_code", "E3002")
        .containsEntry("attempt_count", 1);
    assertThat(sleeper.calls()).doesNotContain(Constants.WORKER_RETRY_DELAY);

    JsonNode failed = detail(me, id);
    assertThat(failed.get("status").stringValue()).isEqualTo("failed");
    assertThat(failed.get("recommendation").toString()).isEqualTo("{\"status\":\"failed\"}");
    assertThat(failed.get("error").toString())
        .isEqualTo("{\"code\":\"E3002\",\"message\":\"추천을 만들지 못했어요. 다시 시도해 주세요.\"}");
    assertThat(failed.get("measurement").get("overallScore").intValue()).isEqualTo(52);

    Res retry =
        http.post("/api/v1/analyses/" + id + "/recommendation/retry")
            .bearer(me.accessToken())
            .header("X-Fixture-Scenario", "ok")
            .send();
    assertThat(retry.status()).isEqualTo(202);
    assertThat(retry.data().toString())
        .isEqualTo(
            "{\"analysisId\":\""
                + id
                + "\",\"status\":\"queued\",\"queuePosition\":1,\"estimatedWaitSec\":55}");
    assertThat(row(id))
        .containsEntry("status", "queued")
        .containsEntry("error_code", null)
        .containsEntry("completed_at", null)
        .containsEntry("recommend_ms", null);

    worker.tick();
    assertThat(row(id)).containsEntry("status", "done").containsEntry("attempt_count", 2);
  }

  @Test
  @DisplayName("재요청은 failed일 때만 — done이면 409 E4005")
  void retryOnlyFailed() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    worker.tick();
    Res res =
        http.post("/api/v1/analyses/" + id + "/recommendation/retry")
            .bearer(me.accessToken())
            .send();
    assertThat(res.status()).isEqualTo(409);
    assertThat(res.errorCode()).isEqualTo("E4005");
  }

  @Test
  @DisplayName("연결 오류는 5초 후 1회 재시도(attempt +1) → 실패하면 failed E3001")
  void connectionRetry() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    scenarios.remember(id, FixtureScenario.AI_DOWN);
    worker.tick();
    assertThat(sleeper.calls()).containsExactly(Constants.WORKER_RETRY_DELAY);
    assertThat(row(id))
        .containsEntry("status", "failed")
        .containsEntry("error_code", "E3001")
        .containsEntry("attempt_count", 2);
  }

  @Test
  @DisplayName("TC-ANA-08: recommending 중 재시작(복구 함수) → queued(queued_at 유지) → done")
  void recovery() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    Object queuedAt = row(id).get("queued_at");
    jdbc.sql("UPDATE analyses SET status = 'recommending', rec_started_at = now() WHERE id = :id")
        .param("id", id)
        .update();

    assertThat(maintenance.recoverInterrupted()).isEqualTo(1);
    assertThat(row(id)).containsEntry("status", "queued").containsEntry("queued_at", queuedAt);
    worker.tick();
    assertThat(statusOf(id)).isEqualTo("done");
  }

  @Test
  @DisplayName("처리 중 삭제(§13.7): 결과를 버리고 FK 오류 없이 끝난다")
  void deletedWhileProcessing() throws Exception {
    sleeper.holdSlow();
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me, "rec-slow");
    var tick = CompletableFuture.runAsync(worker::tick);
    until(() -> "recommending".equals(statusOf(id)));
    assertThat(http.delete("/api/v1/analyses/" + id).bearer(me.accessToken()).send().status())
        .isEqualTo(204);
    sleeper.releaseSlow();
    tick.join();
    assertThat(count("recommendations")).isZero();
    assertThat(worker.isBusy()).isFalse();
  }

  @Test
  @DisplayName("설문 행이 없는 어긋난 데이터는 recommending에 멈추지 않고 failed E4001")
  void surveyMissing() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    jdbc.sql("DELETE FROM surveys WHERE analysis_id = :id").param("id", id).update();
    worker.tick();
    assertThat(row(id)).containsEntry("status", "failed").containsEntry("error_code", "E4001");
  }

  @Test
  @DisplayName("장기 대기: queued가 60분을 넘으면 failed E3001")
  void expiry() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    jdbc.sql("UPDATE analyses SET queued_at = now() - interval '61 minutes' WHERE id = :id")
        .param("id", id)
        .update();
    assertThat(maintenance.expireStale()).isEqualTo(1);
    assertThat(row(id)).containsEntry("status", "failed").containsEntry("error_code", "E3001");
  }

  @Test
  @DisplayName("AI 비가용이면 워커는 큐를 건드리지 않는다")
  void aiUnavailable() {
    LoggedIn me = factories.userWithToken();
    String id = factories.submitOk(me);
    aiHealth.markUnavailable("test");
    worker.tick();
    assertThat(statusOf(id)).isEqualTo("queued");
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
    until(() -> "recommending".equals(statusOf(first)));

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
