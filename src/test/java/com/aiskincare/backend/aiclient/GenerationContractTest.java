package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiskincare.backend.analyses.SurveyParser;
import com.aiskincare.backend.common.enums.RecommendationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class GenerationContractTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static ObjectNode generated() throws Exception {
    try (var in =
        new ClassPathResource("fixtures/ai/disease-pipeline/generated.response.json")
            .getInputStream()) {
      return (ObjectNode) JSON.readTree(in);
    }
  }

  @Test
  void noEligibleClaimsIsTerminalWithheldNotFailure() {
    var result =
        AiResponseParser.diseaseRecommend(
            JSON.readTree(
                """
        {"recommendation_status":"quality_rejected", "recommendation":null,
         "generation_origin":"not_generated", "guidance":["회피 조건에 맞는 근거가 없습니다."],
         "quality_issues":["evidence_no_eligible_claims"]}
        """));
    assertThat(result.status()).isEqualTo(RecommendationStatus.QUALITY_REJECTED);
    assertThat(result.recommendation()).isNull();
    assertThat(result.generation().origin()).isEqualTo("not_generated");
    assertThat(result.generation().qualityIssues()).containsExactly("evidence_no_eligible_claims");
  }

  @Test
  void sourceCardsAndLongVersionSurviveParsing() throws Exception {
    var body = generated();
    var rec = (ObjectNode) body.get("recommendation");
    rec.put("generation_origin", "source_cards");
    rec.put("quality_status", "pass");
    rec.put("evidence_status", "pass");
    rec.put("model_version", "source_cards_ingredient_evidence_20261110_v1");
    ((ObjectNode) rec.get("ingredients").get(0)).put("purpose_source", "source_card");
    var result = AiResponseParser.diseaseRecommend(body);
    assertThat(result.generation().origin()).isEqualTo("source_cards");
    assertThat(result.recommendation().modelVersion()).hasSizeGreaterThan(40);
    assertThat(result.recommendation().ingredients().getFirst().purposeSource())
        .isEqualTo("source_card");
  }

  @Test
  void generatedCannotContainNotGeneratedOrWithheld() throws Exception {
    for (String key : new String[] {"generation_origin", "quality_status", "evidence_status"}) {
      var body = generated();
      ((ObjectNode) body.get("recommendation"))
          .put(key, key.equals("generation_origin") ? "not_generated" : "withheld");
      assertThatThrownBy(() -> AiResponseParser.diseaseRecommend(body)).isInstanceOf(AiError.class);
    }
  }

  @Test
  void legacyProvenanceRemainsUnknown() throws Exception {
    assertThat(AiResponseParser.diseaseRecommend(generated()).generation().origin()).isNull();
  }

  @Test
  void surveyAvoidanceRoundTripsAndReachesAi() {
    var result =
        SurveyParser.parse(
            """
        {"gender":"female","ageBand":"30s","avoidIngredients":[" 살리실산 ","살리실산","레티놀"]}
        """);
    assertThat(result.ok()).isTrue();
    assertThat(result.survey().avoidIngredients()).containsExactly("살리실산", "레티놀");
    assertThat(AiTransform.toAiSurvey(result.survey()).get("avoidIngredients"))
        .isEqualTo(result.survey().avoidIngredients());
    assertThat(
            JSON.readValue(
                JSON.writeValueAsString(result.survey()),
                com.aiskincare.backend.analyses.NormalizedSurvey.class))
        .isEqualTo(result.survey());
  }

  @Test
  void invalidAvoidanceIsRejectedRatherThanDropped() {
    for (String value :
        new String[] {"[\" \" ]", "[42]", "\"살리실산\"", "[\"" + "x".repeat(101) + "\"]"}) {
      assertThat(
              SurveyParser.parse(
                      "{\"gender\":\"female\",\"ageBand\":\"30s\",\"avoidIngredients\":"
                          + value
                          + "}")
                  .ok())
          .isFalse();
    }
  }
}
