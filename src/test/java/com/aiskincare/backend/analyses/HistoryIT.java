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

/** 이력·삭제 (TRD §10.4·10.5) */
class HistoryIT extends IntegrationTestBase {
  private LoggedIn me;
  private List<String> ids;

  @BeforeEach
  void seed() {
    me = factories.userWithToken();
    // 시드와 같은 생성기 — 0번이 최신
    ids =
        SeedData.insertCompletedAnalyses(jdbc, TestHttp.json(), me.user().id(), 25, Instant.now());
  }

  private JsonNode list(String query) {
    Res res = http.get("/api/v1/analyses" + query).bearer(me.accessToken()).send();
    assertThat(res.status()).as(res.raw()).isEqualTo(200);
    return res.data();
  }

  private static List<String> idsOf(JsonNode page) {
    List<String> out = new ArrayList<>();
    page.get("items").forEach(item -> out.add(item.get("analysisId").stringValue()));
    return out;
  }

  @Test
  @DisplayName("TC-HIS-01: 1쪽 20건, 2쪽 5건, 최신순 — 항목은 분류·추천 상태·고민")
  void pages() {
    JsonNode page1 = list("?page=1&size=20");
    assertThat(page1.get("page").toString())
        .isEqualTo("{\"page\":1,\"size\":20,\"totalItems\":25,\"totalPages\":2}");
    assertThat(idsOf(page1)).isEqualTo(ids.subList(0, 20));
    JsonNode first = page1.get("items").get(0);
    var keys = new ArrayList<String>();
    first.propertyNames().forEach(keys::add);
    assertThat(keys)
        .containsExactly(
            "analysisId", "createdAt", "status", "recommendationStatus", "disease", "concerns");
    assertThat(first.get("createdAt").stringValue()).endsWith("Z");
    assertThat(first.get("status").stringValue()).isEqualTo("COMPLETED");
    assertThat(first.get("recommendationStatus").stringValue()).isEqualTo("generated");
    assertThat(first.get("disease").get("labelEn").stringValue()).isEqualTo("atopic");
    assertThat(first.get("disease").get("modelVersion").stringValue())
        .isEqualTo(SeedData.SEED_MODEL_VERSION);
    assertThat(first.get("disease").get("probabilities").size()).isEqualTo(6);
    assertThat(first.get("concerns").toString()).isEqualTo("[\"sensitivity\"]");
    assertThat(page1.get("items").get(1).get("disease").get("labelEn").stringValue())
        .isEqualTo("acne");
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
  @DisplayName("추천 전이면 recommendationStatus null, concerns []")
  void pendingConcerns() {
    LoggedIn other = factories.userWithToken();
    factories.submitOk(other);
    JsonNode item =
        http.get("/api/v1/analyses").bearer(other.accessToken()).send().data().get("items").get(0);
    assertThat(item.get("status").stringValue()).isEqualTo("QUEUED");
    assertThat(item.get("recommendationStatus").isNull()).isTrue();
    assertThat(item.get("concerns").toString()).isEqualTo("[]");
    assertThat(item.get("disease").get("labelEn").stringValue()).isEqualTo("atopic");
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
  @DisplayName("TC-HIS-03: 삭제 204 → 목록에서 제외, 설문·추천도 삭제, 다시 조회·삭제하면 404")
  void delete() {
    Res res = http.delete("/api/v1/analyses/" + ids.get(1)).bearer(me.accessToken()).send();
    assertThat(res.status()).isEqualTo(204);
    assertThat(res.raw()).isEmpty();
    JsonNode page = list("?size=50");
    assertThat(page.get("page").get("totalItems").intValue()).isEqualTo(24);
    assertThat(idsOf(page)).doesNotContain(ids.get(1));
    assertThat(http.get("/api/v1/analyses/" + ids.get(1)).bearer(me.accessToken()).send().status())
        .isEqualTo(404);
    assertThat(
            http.delete("/api/v1/analyses/" + ids.get(1)).bearer(me.accessToken()).send().status())
        .isEqualTo(404);
    for (String table : List.of("surveys", "recommendations")) {
      assertThat(
              jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE analysis_id = :id")
                  .param("id", ids.get(1))
                  .query(Long.class)
                  .single())
          .as(table)
          .isZero();
    }
  }

  @Test
  @DisplayName("(개정) 추이 API는 없어졌다 → 404")
  void trendRemoved() {
    Res res = http.get("/api/v1/analyses/" + ids.get(0) + "/trend").bearer(me.accessToken()).send();
    assertThat(res.status()).isEqualTo(404);
  }
}
