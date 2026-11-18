package com.aiskincare.backend.analyses;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.aiclient.AiTransform;
import com.aiskincare.backend.support.Factories;
import com.aiskincare.backend.support.IntegrationTestBase;
import com.aiskincare.backend.support.TestHttp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SymptomProfileIT extends IntegrationTestBase {
  @Autowired AnalysisRepository repository;

  @Test
  @DisplayName("재로그인한 소유자가 분석 이력에서 선택 증상 여섯 값을 그대로 조회한다")
  void profileIsSavedForOwnerAfterReloginWithoutChangingClassification() {
    var owner = factories.userWithToken();
    var other = factories.userWithToken();
    var legacy = factories.submit(other);
    assertThat(legacy.status()).isEqualTo(202);
    assertThat(legacy.data().get("survey").has("symptomProfile")).isFalse();

    var answers =
        Map.of(
            "itch",
            "none",
            "flaking",
            "dry",
            "recurringRedness",
            false,
            "visiblePlugs",
            true,
            "onset",
            "one_to_four_weeks",
            "areas",
            List.of("eyebrows", "around_nose"));
    var survey = Factories.defaultSurvey();
    survey.put("symptomProfile", answers);
    var created =
        factories.submit(
            owner, Factories.FACE, "fixture.png", TestHttp.json().writeValueAsString(survey), null);
    assertThat(created.status()).isEqualTo(202);
    assertThat(created.data().get("disease")).isEqualTo(legacy.data().get("disease"));
    var id = created.data().get("analysisId").stringValue();
    var saved = repository.survey(id).orElseThrow();
    assertThat(saved.symptomProfile().recurringRedness()).isFalse();
    assertThat(AiTransform.toAiSurvey(saved)).doesNotContainKey("symptomProfile");

    var loggedOut =
        http.post("/api/v1/auth/logout")
            .bearer(owner.accessToken())
            .header("Cookie", "refresh_token=" + owner.refreshCookie())
            .send();
    assertThat(loggedOut.status()).isEqualTo(204);
    var relogged = factories.login(owner.user());
    var history = http.get("/api/v1/analyses").bearer(relogged.accessToken()).send();
    assertThat(history.status()).isEqualTo(200);
    assertThat(history.data().get("items").get(0).get("analysisId").stringValue()).isEqualTo(id);
    var detail = http.get("/api/v1/analyses/" + id).bearer(relogged.accessToken()).send();
    var expected = TestHttp.json().valueToTree(answers);
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.data().get("survey").get("symptomProfile")).isEqualTo(expected);
    assertThat(created.data().get("survey").get("symptomProfile")).isEqualTo(expected);
    assertThat(http.get("/api/v1/analyses/" + id).bearer(other.accessToken()).send().status())
        .isEqualTo(404);
    assertThat(http.delete("/api/v1/analyses/" + id).bearer(relogged.accessToken()).send().status())
        .isEqualTo(204);
    assertThat(repository.survey(id)).isEmpty();
  }

  @Test
  @DisplayName("(개정) 허용하지 않은 자가 응답을 원문 노출 없이 거절한다")
  void malformedProfileCannotBeSaved() {
    var owner = factories.userWithToken();
    var survey = Factories.defaultSurvey();
    survey.put("symptomProfile", Map.of("recurringRedness", "confidential-value"));
    var response =
        factories.submit(
            owner, Factories.FACE, "fixture.png", TestHttp.json().writeValueAsString(survey), null);
    assertThat(response.status()).isEqualTo(422);
    assertThat(response.raw())
        .contains("E4002", "survey.symptomProfile.recurringRedness", "invalid_type")
        .doesNotContain("confidential-value");
    assertThat(count("surveys")).isZero();
  }
}
