package com.aiskincare.backend.analyses;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.seed.SeedData;
import com.aiskincare.backend.support.Factories;
import com.aiskincare.backend.support.Factories.LoggedIn;
import com.aiskincare.backend.support.IntegrationTestBase;
import com.aiskincare.backend.support.TestHttp;
import com.aiskincare.backend.support.TestHttp.Multipart;
import com.aiskincare.backend.support.TestHttp.Res;
import com.aiskincare.backend.support.TestImages;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** 분석 생성·조회 (TRD §10.1·10.2) */
class AnalysesIT extends IntegrationTestBase {
  private static String surveyJson(Map<String, Object> survey) {
    return TestHttp.json().writeValueAsString(survey);
  }

  private static final String SURVEY = surveyJson(Factories.defaultSurvey());

  private Res submitImage(LoggedIn me, byte[] image, String filename) {
    return factories.submit(me, image, filename, SURVEY, null);
  }

  @Test
  @DisplayName("TC-ANA-01: 202, QUEUED, 분류 결과, queuePosition ≥ 1, analyses(분류)·surveys 저장")
  void created() {
    LoggedIn me = factories.userWithToken();
    Res res = factories.submit(me);
    assertThat(res.status()).isEqualTo(202);
    assertThat(res.body().get("success").booleanValue()).isTrue();
    JsonNode data = res.data();
    String id = data.get("analysisId").stringValue();
    assertThat(id).matches("^[0-9A-HJKMNP-TV-Z]{26}$");
    assertThat(data.get("status").stringValue()).isEqualTo("QUEUED");
    assertThat(data.get("disease").toString())
        .isEqualTo(
            "{\"label\":\"아토피\",\"labelEn\":\"atopic\",\"confidence\":0.9,\"uncertain\":false,"
                + "\"probabilities\":{\"건선\":0.019999999999999997,\"아토피\":0.9,"
                + "\"여드름\":0.019999999999999997,\"정상\":0.019999999999999997,"
                + "\"주사\":0.019999999999999997,\"지루\":0.019999999999999997},"
                + "\"modelVersion\":\"quality_test_fixture_not_a_patient_prediction\",\"inferenceMs\":0}");
    assertThat(data.get("recommendationStatus").isNull()).isTrue();
    assertThat(data.get("recommendation").isNull()).isTrue();
    assertThat(data.get("guidance").toString()).isEqualTo("[]");
    assertThat(data.get("error").isNull()).isTrue();
    assertThat(data.get("queuePosition").intValue()).isEqualTo(1);
    assertThat(data.get("estimatedWaitSec").intValue()).isEqualTo(55);

    var row =
        jdbc.sql(
                """
                SELECT user_id, status, disease_label, disease_confidence, disease_uncertain,
                       disease_probabilities::text AS probabilities, disease_model_version,
                       recommendation_status, guidance::text AS guidance, attempt_count, classify_ms
                FROM analyses WHERE id = :id
                """)
            .param("id", id)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("user_id", me.user().id())
        .containsEntry("status", "QUEUED")
        .containsEntry("disease_label", "atopic")
        .containsEntry("disease_confidence", 0.9)
        .containsEntry("disease_uncertain", false)
        .containsEntry("disease_model_version", "quality_test_fixture_not_a_patient_prediction")
        .containsEntry("recommendation_status", null)
        .containsEntry("guidance", "[]")
        .containsEntry("attempt_count", 0);
    assertThat(row.get("classify_ms")).isInstanceOf(Integer.class);
    // 반올림하지 않고 받은 값 그대로 (추천 호출 때 다시 보낸다)
    assertThat(TestHttp.json().readTree((String) row.get("probabilities")).get("건선").doubleValue())
        .isEqualTo(0.019999999999999997);

    var survey =
        jdbc.sql(
                "SELECT gender, age_band, skin_type, payload::text AS payload FROM surveys WHERE analysis_id = :id")
            .param("id", id)
            .query()
            .singleRow();
    assertThat(survey)
        .containsEntry("gender", "female")
        .containsEntry("age_band", "20s")
        .containsEntry("skin_type", "combination");
    assertThat(TestHttp.json().readTree((String) survey.get("payload")))
        .isEqualTo(TestHttp.json().valueToTree(Factories.defaultSurvey()));
  }

  @Test
  @DisplayName("JPEG도 받는다 (시그니처로 판별, 파일명·mimetype 무관)")
  void jpeg() {
    LoggedIn me = factories.userWithToken();
    assertThat(submitImage(me, TestImages.headerOnlyJpeg(1080, 1920), "photo.heic").status())
        .isEqualTo(202);
  }

  @Test
  @DisplayName("GET /analyses/{id}: POST와 같은 형태 — QUEUED면 분류 + 순번, 추천·guidance 비어 있음")
  void detailQueued() {
    LoggedIn me = factories.userWithToken();
    Res created = factories.submit(me);
    String id = created.data().get("analysisId").stringValue();
    Res res = http.get("/api/v1/analyses/" + id).bearer(me.accessToken()).send();
    assertThat(res.status()).isEqualTo(200);
    JsonNode data = res.data();
    assertThat(data).isEqualTo(created.data());
    assertThat(data.get("status").stringValue()).isEqualTo("QUEUED");
    assertThat(data.get("createdAt").stringValue())
        .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");
    assertThat(data.get("completedAt").isNull()).isTrue();
    assertThat(data.has("measurement")).isFalse();
    assertThat(data.has("deviceType")).isFalse();
    assertThat(data.get("survey"))
        .isEqualTo(TestHttp.json().valueToTree(Factories.defaultSurvey()));
  }

  @Test
  @DisplayName("(개정) deviceType을 보내도 무시하고 202 (개정 전 앱 호환)")
  void deviceTypeIgnored() {
    LoggedIn me = factories.userWithToken();
    Res res =
        http.post("/api/v1/analyses")
            .bearer(me.accessToken())
            .multipart(
                new Multipart()
                    .file("image", "face.png", "image/png", Factories.FACE)
                    .field("survey", SURVEY)
                    .field("deviceType", "webcam"))
            .send();
    assertThat(res.status()).isEqualTo(202);
  }

  @Test
  @DisplayName("TC-ANA-02: gender 누락 → 422 E4002, detail에 survey.gender, 행 없음")
  void genderMissing() {
    LoggedIn me = factories.userWithToken();
    Map<String, Object> survey = new LinkedHashMap<>(Factories.defaultSurvey());
    survey.remove("gender");
    Res res = factories.submit(me, Factories.FACE, "face.png", surveyJson(survey), null);
    assertThat(res.status()).isEqualTo(422);
    assertThat(res.body().get("error").toString())
        .isEqualTo(
            "{\"code\":\"E4002\",\"message\":\"입력값을 확인해 주세요.\","
                + "\"detail\":{\"fields\":[{\"field\":\"survey.gender\",\"reason\":\"required\"}]}}");
    assertThat(count("analyses")).isZero();
  }

  static Stream<Arguments> invalidInputs() {
    Map<String, Object> tooMany = new LinkedHashMap<>(Factories.defaultSurvey());
    tooMany.put("concerns", List.of("pore", "acne", "wrinkle", "redness"));
    return Stream.of(
        Arguments.of("survey JSON 파싱 실패", "{not json", "survey", "invalid_format"),
        Arguments.of("survey 누락", null, "survey", "required"),
        Arguments.of("고민 4개", surveyJson(tooMany), "survey.concerns", "too_many"));
  }

  @ParameterizedTest(name = "{0} → 422 E4002")
  @MethodSource("invalidInputs")
  void invalid(String name, String survey, String field, String reason) {
    LoggedIn me = factories.userWithToken();
    Res res = factories.submit(me, Factories.FACE, "face.png", survey, null);
    assertThat(res.status()).isEqualTo(422);
    assertThat(res.errorCode()).isEqualTo("E4002");
    assertThat(res.body().get("error").get("detail").get("fields").toString())
        .contains("{\"field\":\"" + field + "\",\"reason\":\"" + reason + "\"}");
  }

  @Test
  @DisplayName("image 없음 → 422 E4002 (field image)")
  void imageMissing() {
    LoggedIn me = factories.userWithToken();
    Res res =
        http.post("/api/v1/analyses")
            .bearer(me.accessToken())
            .multipart(new Multipart().field("survey", SURVEY))
            .send();
    assertThat(res.status()).isEqualTo(422);
    assertThat(res.body().get("error").get("detail").get("fields").toString())
        .contains("{\"field\":\"image\",\"reason\":\"required\"}");
  }

  @Test
  @DisplayName("TC-ANA-03: fixture no-face → 422 E2001, analyses 행 없음 (AI에는 아직 없는 오류 경로)")
  void noFace() {
    LoggedIn me = factories.userWithToken();
    Res res = factories.submit(me, "no-face");
    assertThat(res.status()).isEqualTo(422);
    assertThat(res.errorCode()).isEqualTo("E2001");
    assertThat(res.body().get("error").get("message").stringValue())
        .isEqualTo("얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요.");
    assertThat(count("analyses")).isZero();
  }

  @Test
  @DisplayName("TC-ANA-07: 11MB → 413 E2003")
  void tooLarge() {
    LoggedIn me = factories.userWithToken();
    Res res = submitImage(me, TestImages.headerOnlyJpeg(1080, 1440, 11 * 1024 * 1024), "big.jpg");
    assertThat(res.status()).isEqualTo(413);
    assertThat(res.errorCode()).isEqualTo("E2003");
    assertThat(count("analyses")).isZero();
  }

  static Stream<Arguments> unsupported() {
    return Stream.of(
        Arguments.of("TC-ANA-07: GIF", TestImages.gif()),
        Arguments.of("HEIC (앱이 JPEG로 바꿔 보내야 함)", TestImages.heic()));
  }

  @ParameterizedTest(name = "{0} → 415 E2004")
  @MethodSource
  void unsupported(String name, byte[] image) {
    LoggedIn me = factories.userWithToken();
    Res res = submitImage(me, image, "x.jpg");
    assertThat(res.status()).isEqualTo(415);
    assertThat(res.errorCode()).isEqualTo("E2004");
    assertThat(res.body().get("error").get("message").stringValue())
        .isEqualTo("JPEG 또는 PNG 사진만 올릴 수 있어요.");
  }

  @Test
  @DisplayName("짧은 변 < 720px → 422 E2002 (해상도 문구)")
  void lowResolution() {
    LoggedIn me = factories.userWithToken();
    Res res = submitImage(me, TestImages.png(600, 600), "small.png");
    assertThat(res.status()).isEqualTo(422);
    assertThat(res.errorCode()).isEqualTo("E2002");
    assertThat(res.body().get("error").get("message").stringValue())
        .isEqualTo("사진 해상도가 낮아요. 다시 찍어 주세요.");
  }

  @Test
  @DisplayName("TC-ANA-06: 진행 중 분석이 있으면 429 E4004 in_progress (Retry-After 30)")
  void inProgress() {
    LoggedIn me = factories.userWithToken();
    factories.submitOk(me);
    Res res = factories.submit(me);
    assertThat(res.status()).isEqualTo(429);
    assertThat(res.errorCode()).isEqualTo("E4004");
    assertThat(res.body().get("error").get("detail").get("reason").stringValue())
        .isEqualTo("in_progress");
    assertThat(res.header("Retry-After")).isEqualTo("30");
  }

  @Test
  @DisplayName("처리 중 제출 잠금: 동시에 두 번 제출하면 하나만 202")
  void submissionLock() {
    LoggedIn me = factories.userWithToken();
    var a = CompletableFuture.supplyAsync(() -> factories.submit(me));
    var b = CompletableFuture.supplyAsync(() -> factories.submit(me));
    List<Integer> statuses = new ArrayList<>(List.of(a.join().status(), b.join().status()));
    statuses.sort(null);
    assertThat(statuses).containsExactly(202, 429);
    assertThat(count("analyses")).isEqualTo(1);
  }

  @Test
  @DisplayName("일일 한도(KST): 오늘 20건이면 429 E4004 daily_limit, Retry-After = 다음 KST 자정까지")
  void dailyLimit() {
    LoggedIn me = factories.userWithToken();
    SeedData.insertCompletedAnalyses(jdbc, TestHttp.json(), me.user().id(), 20, Instant.now());
    jdbc.sql("UPDATE analyses SET created_at = now()").update();
    Res res = factories.submit(me);
    assertThat(res.status()).isEqualTo(429);
    assertThat(res.body().get("error").get("detail").get("reason").stringValue())
        .isEqualTo("daily_limit");
    assertThat(Integer.parseInt(res.header("Retry-After"))).isBetween(1, 86400);
  }

  @Test
  @DisplayName("토큰 없음 → 401 E1001 (요청 제한·본문 검사보다 먼저)")
  void unauthorized() {
    Res res = http.post("/api/v1/analyses").send();
    assertThat(res.status()).isEqualTo(401);
    assertThat(res.errorCode()).isEqualTo("E1001");
    Res withBody =
        http.post("/api/v1/analyses")
            .multipart(new Multipart().file("image", "x.gif", "image/gif", TestImages.gif()))
            .send();
    assertThat(withBody.errorCode()).isEqualTo("E1001");
  }

  @Nested
  @DisplayName("TC-SEC-01: IDOR — 남의 분석은 없는 것과 똑같이 404 E4003")
  class Idor {
    @Test
    @DisplayName("조회·삭제·재요청")
    void otherUsers() {
      LoggedIn a = factories.userWithToken();
      LoggedIn b = factories.userWithToken();
      String bId =
          SeedData.insertCompletedAnalyses(jdbc, TestHttp.json(), b.user().id(), 1, Instant.now())
              .get(0);
      List<TestHttp.Req> requests =
          List.of(
              http.get("/api/v1/analyses/" + bId),
              http.delete("/api/v1/analyses/" + bId),
              http.post("/api/v1/analyses/" + bId + "/recommendation/retry"));
      for (TestHttp.Req req : requests) {
        Res res = req.bearer(a.accessToken()).send();
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.body().get("error").toString())
            .isEqualTo("{\"code\":\"E4003\",\"message\":\"결과를 찾을 수 없어요.\",\"detail\":null}");
      }
      // B의 분석은 그대로
      assertThat(http.get("/api/v1/analyses/" + bId).bearer(b.accessToken()).send().status())
          .isEqualTo(200);
    }

    @Test
    @DisplayName("ULID 형식이 아닌 id·없는 id도 404 E4003 (422 아님)")
    void invalidIds() {
      LoggedIn me = factories.userWithToken();
      for (String id : List.of("abc", "01J9Z3K5V8Q2M4N6P8R0S2T4VU", "01J9Z3K5V8Q2M4N6P8R0S2T4V6")) {
        Res res = http.get("/api/v1/analyses/" + id).bearer(me.accessToken()).send();
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.errorCode()).isEqualTo("E4003");
      }
    }
  }
}
