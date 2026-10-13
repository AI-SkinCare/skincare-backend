package com.aiskincare.backend.analyses;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.seed.SeedData;
import com.aiskincare.backend.support.Factories.LoggedIn;
import com.aiskincare.backend.support.IntegrationTestBase;
import com.aiskincare.backend.support.TestHttp;
import com.aiskincare.backend.support.TestHttp.Res;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** 이력·추이·삭제 (TRD §10.4–10.6) */
class HistoryIT extends IntegrationTestBase {
  private LoggedIn me;
  private List<String> ids;

  @BeforeEach
  void seed() {
    me = factories.userWithToken();
    // 시드와 같은 생성기 — 0번이 최신
    ids = SeedData.insertDoneAnalyses(jdbc, TestHttp.json(), me.user().id(), 25, Instant.now());
  }

  private JsonNode list(String query) {
    Res res = http.get("/api/v1/analyses" + query).bearer(me.accessToken()).send();
    assertThat(res.status()).as(res.raw()).isEqualTo(200);
    return res.data();
  }

  private JsonNode trend(String id) {
    Res res = http.get("/api/v1/analyses/" + id + "/trend").bearer(me.accessToken()).send();
    assertThat(res.status()).as(res.raw()).isEqualTo(200);
    return res.data();
  }

  private static List<String> idsOf(JsonNode page) {
    List<String> out = new ArrayList<>();
    page.get("items").forEach(item -> out.add(item.get("analysisId").stringValue()));
    return out;
  }

  @Test
  @DisplayName("TC-HIS-01: 1쪽 20건, 2쪽 5건, 최신순")
  void pages() {
    JsonNode page1 = list("?page=1&size=20");
    assertThat(page1.get("page").toString())
        .isEqualTo("{\"page\":1,\"size\":20,\"totalItems\":25,\"totalPages\":2}");
    assertThat(idsOf(page1)).isEqualTo(ids.subList(0, 20));
    JsonNode first = page1.get("items").get(0);
    var keys = new ArrayList<String>();
    first.propertyNames().forEach(keys::add);
    assertThat(keys)
        .containsExactly("analysisId", "createdAt", "status", "overallScore", "isDemo", "concerns");
    assertThat(first.get("createdAt").stringValue()).endsWith("Z");
    assertThat(first.get("status").stringValue()).isEqualTo("done");
    assertThat(first.get("overallScore").isInt()).isTrue();
    assertThat(first.get("isDemo").booleanValue()).isFalse();
    assertThat(first.get("concerns").toString()).isEqualTo("[\"pore\",\"pigmentation\"]");
    assertThat(idsOf(list("?page=2&size=20"))).isEqualTo(ids.subList(20, 25));
  }

  @Test
  @DisplayName("기본값 page=1·size=20, 홈 화면은 size=1로 최근 1건")
  void defaults() {
    assertThat(list("").get("page").toString())
        .isEqualTo("{\"page\":1,\"size\":20,\"totalItems\":25,\"totalPages\":2}");
    assertThat(list("?size=1").get("items").size()).isEqualTo(1);
  }

  @Test
  @DisplayName("추천 전이면 concerns는 []")
  void pendingConcerns() {
    LoggedIn other = factories.userWithToken();
    factories.submitOk(other);
    JsonNode item =
        http.get("/api/v1/analyses").bearer(other.accessToken()).send().data().get("items").get(0);
    assertThat(item.get("status").stringValue()).isEqualTo("queued");
    assertThat(item.get("concerns").toString()).isEqualTo("[]");
    assertThat(item.get("isDemo").booleanValue()).isTrue();
  }

  @Test
  @DisplayName("0건이면 totalPages 0")
  void empty() {
    LoggedIn other = factories.userWithToken();
    assertThat(http.get("/api/v1/analyses").bearer(other.accessToken()).send().data().toString())
        .isEqualTo(
            "{\"items\":[],\"page\":{\"page\":1,\"size\":20,\"totalItems\":0,\"totalPages\":0}}");
  }

  @ParameterizedTest(name = "{0} → 422 E4002")
  @ValueSource(strings = {"?page=0", "?size=0", "?size=51", "?page=abc", "?page=1.5"})
  void invalidPaging(String query) {
    Res res = http.get("/api/v1/analyses" + query).bearer(me.accessToken()).send();
    assertThat(res.status()).isEqualTo(422);
    assertThat(res.errorCode()).isEqualTo("E4002");
  }

  @Test
  @DisplayName("TC-HIS-02: 직전 done·비데모 분석과 비교, delta = current − previous")
  void trendDelta() {
    JsonNode data = trend(ids.get(0));
    JsonNode current = data.get("current");
    JsonNode previous = data.get("previous");
    assertThat(current.get("analysisId").stringValue()).isEqualTo(ids.get(0));
    assertThat(previous.get("analysisId").stringValue()).isEqualTo(ids.get(1));
    assertThat(current.get("date").stringValue()).matches("^\\d{4}-\\d{2}-\\d{2}$");
    JsonNode delta = data.get("delta");
    assertThat(delta.get("overallScore").intValue())
        .isEqualTo(
            current.get("overallScore").intValue() - previous.get("overallScore").intValue());
    for (String metric : List.of("moisture", "elasticity", "pigmentation", "pore")) {
      assertThat(delta.get(metric).intValue())
          .as(metric)
          .isEqualTo(
              current.get("summary").get(metric).intValue()
                  - previous.get("summary").get(metric).intValue());
    }
  }

  @Test
  @DisplayName("TC-HIS-02: 이전 분석이 없으면 previous·delta null")
  void trendWithoutPrevious() {
    JsonNode data = trend(ids.get(24));
    assertThat(data.get("previous").isNull()).isTrue();
    assertThat(data.get("delta").isNull()).isTrue();
  }

  @Test
  @DisplayName("데모·미완료 분석은 previous로 고르지 않는다, 현재가 데모면 null")
  void trendSkipsDemoAndUnfinished() {
    jdbc.sql("UPDATE analyses SET is_demo = true WHERE id = :id").param("id", ids.get(1)).update();
    jdbc.sql("UPDATE analyses SET status = 'failed', error_code = 'E3002' WHERE id = :id")
        .param("id", ids.get(2))
        .update();
    assertThat(trend(ids.get(0)).get("previous").get("analysisId").stringValue())
        .isEqualTo(ids.get(3));
    JsonNode demo = trend(ids.get(1));
    assertThat(demo.get("previous").isNull()).isTrue();
    assertThat(demo.get("delta").isNull()).isTrue();
  }

  @Test
  @DisplayName("TC-HIS-03: 삭제 204 → 목록·추이에서 제외, 다시 조회·삭제하면 404")
  void delete() {
    Res res = http.delete("/api/v1/analyses/" + ids.get(1)).bearer(me.accessToken()).send();
    assertThat(res.status()).isEqualTo(204);
    assertThat(res.raw()).isEmpty();
    JsonNode page = list("?size=50");
    assertThat(page.get("page").get("totalItems").intValue()).isEqualTo(24);
    assertThat(idsOf(page)).doesNotContain(ids.get(1));
    assertThat(trend(ids.get(0)).get("previous").get("analysisId").stringValue())
        .isEqualTo(ids.get(2));
    assertThat(http.get("/api/v1/analyses/" + ids.get(1)).bearer(me.accessToken()).send().status())
        .isEqualTo(404);
    assertThat(
            http.delete("/api/v1/analyses/" + ids.get(1)).bearer(me.accessToken()).send().status())
        .isEqualTo(404);
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM skin_metrics WHERE analysis_id = :id")
                .param("id", ids.get(1))
                .query(Long.class)
                .single())
        .isZero();
  }
}
