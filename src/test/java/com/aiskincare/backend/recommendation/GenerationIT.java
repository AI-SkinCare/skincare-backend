package com.aiskincare.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.aiclient.AiResponseParser;
import com.aiskincare.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class GenerationIT extends IntegrationTestBase {
  @Autowired RecommendationQueueRepository queue;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void qualityRejectedPersistsAndEndsPollingAndCannotRetry() {
    var user = factories.userWithToken();
    var id = factories.submitOk(user);
    assertThat(queue.acquire()).contains(id);
    var result =
        AiResponseParser.diseaseRecommend(
            JSON.readTree(
                """
        {"recommendation_status":"quality_rejected", "recommendation":null,
         "generation_origin":"not_generated", "guidance":["회피 조건에 맞는 근거가 없습니다."],
         "quality_issues":["evidence_no_eligible_claims"]}
        """));
    assertThat(queue.markDone(id, 1, result)).isTrue();
    var data = http.get("/api/v1/analyses/" + id).bearer(user.accessToken()).send().data();
    assertThat(data.get("status").stringValue()).isEqualTo("WITHHELD");
    assertThat(data.get("recommendationStatus").stringValue()).isEqualTo("quality_rejected");
    assertThat(data.get("generation").get("origin").stringValue()).isEqualTo("not_generated");
    assertThat(data.get("generation").get("qualityIssues").get(0).stringValue())
        .isEqualTo("evidence_no_eligible_claims");
    assertThat(data.get("recommendation").isNull()).isTrue();
    assertThat(data.get("error").isNull()).isTrue();
    assertThat(count("recommendations")).isZero();
    var retry =
        http.post("/api/v1/analyses/" + id + "/recommendation/retry")
            .bearer(user.accessToken())
            .send();
    assertThat(retry.status()).isEqualTo(409);
  }

  @Test
  void sourceProvenanceAndLongVersionSurviveDatabaseAndHttp() throws Exception {
    var user = factories.userWithToken();
    var id = factories.submitOk(user);
    queue.acquire();
    ObjectNode body;
    try (var in =
        new ClassPathResource("fixtures/ai/disease-pipeline/generated.response.json")
            .getInputStream()) {
      body = (ObjectNode) JSON.readTree(in);
    }
    var rec = (ObjectNode) body.get("recommendation");
    rec.put("generation_origin", "source_cards");
    rec.put("model_version", "source_cards_ingredient_evidence_20261110_v1");
    ((ObjectNode) rec.get("ingredients").get(0)).put("purpose_source", "source_card");
    queue.markDone(id, 100, AiResponseParser.diseaseRecommend(body));
    var data = http.get("/api/v1/analyses/" + id).bearer(user.accessToken()).send().data();
    assertThat(data.get("status").stringValue()).isEqualTo("COMPLETED");
    assertThat(data.get("generation").get("origin").stringValue()).isEqualTo("source_cards");
    assertThat(data.get("recommendation").get("modelVersion").stringValue())
        .isEqualTo("source_cards_ingredient_evidence_20261110_v1");
    assertThat(
            data.get("recommendation").get("ingredients").get(0).get("purposeSource").stringValue())
        .isEqualTo("source_card");
  }
}
