package com.aiskincare.backend.analyses;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.aiclient.AiModels.Explanation;
import com.aiskincare.backend.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ExplanationIT extends IntegrationTestBase {
  @Autowired AnalysisRepository repository;

  @Test
  void savedExplanationIsOwnedAndStableAndOmittedFromHistory() {
    var owner = factories.userWithToken();
    var other = factories.userWithToken();
    var id = factories.submitOk(owner);
    var old = repository.findOwned(id, owner.user().id()).orElseThrow().disease();
    var survey = repository.survey(id).orElseThrow();
    var explanation =
        new Explanation(
            false,
            "grad_cam",
            old.label().label(),
            old.modelVersion(),
            null,
            "모델이 참고한 영역이며 병변 위치나 심각도를 뜻하지 않습니다.",
            "constant_map");
    var disease =
        new DiseaseResult(
            old.label(),
            old.confidence(),
            old.uncertain(),
            old.probabilities(),
            old.modelVersion(),
            old.inferenceMs(),
            explanation);
    assertThat(repository.deleteOwned(id, owner.user().id())).isEqualTo(1);
    repository.insertAnalysis(id, owner.user().id(), disease, 10);
    repository.insertSurvey(id, survey);
    assertThat(repository.findOwned(id, owner.user().id()).orElseThrow().disease())
        .isEqualTo(disease);
    var detail = http.get("/api/v1/analyses/" + id).bearer(owner.accessToken()).send();
    assertThat(detail.status()).isEqualTo(200);
    assertThat(
            detail.data().get("disease").get("explanation").get("unavailableReason").stringValue())
        .isEqualTo("constant_map");
    var history = http.get("/api/v1/analyses").bearer(owner.accessToken()).send().data();
    assertThat(history.get("items").get(0).get("disease").get("explanation").isNull()).isTrue();
    assertThat(http.get("/api/v1/analyses/" + id).bearer(other.accessToken()).send().status())
        .isEqualTo(404);
    assertThat(http.delete("/api/v1/analyses/" + id).bearer(other.accessToken()).send().status())
        .isEqualTo(404);
    assertThat(repository.findOwned(id, owner.user().id())).isPresent();
    assertThat(http.delete("/api/v1/analyses/" + id).bearer(owner.accessToken()).send().status())
        .isEqualTo(204);
    assertThat(repository.findOwned(id, owner.user().id())).isEmpty();
  }

  @Test
  void legacyRowHasNoExplanation() {
    var owner = factories.userWithToken();
    var id = factories.submitOk(owner);
    var detail = http.get("/api/v1/analyses/" + id).bearer(owner.accessToken()).send();
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.data().get("disease").get("explanation").isNull()).isTrue();
  }
}
