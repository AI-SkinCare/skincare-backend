package com.aiskincare.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.support.IntegrationTestBase;
import com.aiskincare.backend.support.TestHttp.Res;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 공통 규약 (TRD §7) */
class CommonIT extends IntegrationTestBase {
  @Test
  @DisplayName("성공 봉투 { success: true, data }")
  void successEnvelope() {
    Res res = http.get("/api/v1/system/status").send();
    assertThat(res.status()).isEqualTo(200);
    List<String> keys = new ArrayList<>();
    res.body().propertyNames().forEach(keys::add);
    assertThat(keys).containsExactly("success", "data");
    assertThat(res.body().get("success").booleanValue()).isTrue();
    assertThat(res.data().isObject()).isTrue();
  }

  @Test
  @DisplayName("X-Request-Id: 형식이 맞으면 그대로, 아니면 새로 만든다 — 에러 응답에도")
  void requestId() {
    assertThat(
            http.get("/api/v1/system/status")
                .header("X-Request-Id", "ios-req.01_A")
                .send()
                .header("X-Request-Id"))
        .isEqualTo("ios-req.01_A");
    Res generated = http.get("/api/v1/nope").header("X-Request-Id", "bad id with spaces").send();
    assertThat(generated.status()).isEqualTo(404);
    assertThat(generated.header("X-Request-Id")).matches("^[0-9a-f-]{36}$");
  }

  @Test
  @DisplayName("없는 경로·메서드 → 404 E4003 봉투 (Spring 기본 오류 모양이 나가지 않음)")
  void notFound() {
    for (Res res :
        List.of(
            http.get("/api/v1/nope").send(),
            http.put("/api/v1/system/status").send(),
            http.get("/").send(),
            http.get("/error").send())) {
      assertThat(res.status()).isEqualTo(404);
      assertThat(res.body().toString())
          .isEqualTo(
              "{\"success\":false,\"error\":{\"code\":\"E4003\",\"message\":\"결과를 찾을 수 없어요.\",\"detail\":null}}");
    }
  }

  @Test
  @DisplayName("깨진 JSON → 400이 아니라 422 E4002, 타입 오류도 422")
  void malformedJson() {
    Res res = http.post("/api/v1/auth/login").json("{bad").send();
    assertThat(res.status()).isEqualTo(422);
    assertThat(res.errorCode()).isEqualTo("E4002");
    Res wrongType = http.post("/api/v1/auth/login").json("{\"email\":1,\"password\":true}").send();
    assertThat(wrongType.status()).isEqualTo(422);
    assertThat(wrongType.errorCode()).isEqualTo("E4002");
  }

  @Test
  @DisplayName("multipart가 아닌 제출: 토큰 없으면 401 E1001, 있으면 422 E4002 (image required)")
  void nonMultipartSubmit() {
    assertThat(http.post("/api/v1/analyses").json("{}").send().errorCode()).isEqualTo("E1001");
    var me = factories.userWithToken();
    Res res = http.post("/api/v1/analyses").bearer(me.accessToken()).json("{}").send();
    assertThat(res.status()).isEqualTo(422);
    assertThat(res.body().get("error").get("detail").get("fields").toString())
        .contains("{\"field\":\"image\",\"reason\":\"required\"}");
  }

  @Test
  @DisplayName("보안 헤더")
  void securityHeaders() {
    Res res = http.get("/api/v1/system/status").send();
    assertThat(res.header("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  @DisplayName("OpenAPI(/api/docs-json): 경로·봉투·[앱 협의] refreshToken·보안 요구, 내부 타입 노출 없음")
  void openApi() {
    Res res = http.get("/api/docs-json").send();
    assertThat(res.status()).isEqualTo(200);
    JsonNode doc = res.body();
    // 앱 타입 생성기 호환 — NestJS 때와 같은 OpenAPI 3.0
    assertThat(doc.get("openapi").stringValue()).startsWith("3.0");
    List<String> paths = new ArrayList<>();
    doc.get("paths").propertyNames().forEach(paths::add);
    assertThat(paths)
        .contains(
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/users/me",
            "/api/v1/analyses",
            "/api/v1/analyses/{id}",
            "/api/v1/analyses/{id}/recommendation/retry",
            "/api/v1/system/status");
    assertThat(paths).doesNotContain("/api/v1/analyses/{id}/trend");
    JsonNode schemas = doc.get("components").get("schemas");
    assertThat(schemas.has("ErrorEnvelope")).isTrue();
    assertThat(schemas.has("AuthUser")).as("@CurrentUser 매개변수가 API에 노출됨").isFalse();
    JsonNode token = schemas.get("TokenResponse");
    assertThat(token.get("properties").has("refreshToken")).isTrue();
    assertThat(token.path("required").toString()).doesNotContain("refreshToken");

    // nullable 참조는 { type: object, nullable: true, allOf: [$ref] } — 3.0에서 $ref 옆 nullable은 무시되므로
    JsonNode view = schemas.get("AnalysisView").get("properties");
    JsonNode recommendation = view.get("recommendation");
    assertThat(recommendation.get("type").stringValue()).isEqualTo("object");
    assertThat(recommendation.get("nullable").booleanValue()).isTrue();
    assertThat(recommendation.get("allOf").get(0).get("$ref").stringValue())
        .isEqualTo("#/components/schemas/Recommendation");
    assertThat(view.get("error").get("nullable").booleanValue()).isTrue();
    assertThat(view.get("recommendationStatus").get("nullable").booleanValue()).isTrue();
    assertThat(view.get("recommendationStatus").get("enum").toString())
        .isEqualTo("[\"generated\",\"uncertain\",\"no_concern\"]");
    // 개정 B.1: 작업 상태는 대문자 5종
    assertThat(view.get("status").get("enum").toString())
        .isEqualTo("[\"QUEUED\",\"GENERATING\",\"COMPLETED\",\"WITHHELD\",\"FAILED\"]");
    assertThat(view.has("measurement")).isFalse();
    assertThat(schemas.get("Disease").get("properties").get("labelEn").get("enum").toString())
        .isEqualTo("[\"psoriasis\",\"atopic\",\"acne\",\"normal\",\"rosacea\",\"seborrheic\"]");

    JsonNode ops = doc.get("paths");
    assertThat(ops.get("/api/v1/auth/login").get("post").get("security").toString())
        .isEqualTo("[]");
    assertThat(ops.get("/api/v1/system/status").get("get").get("security").toString())
        .isEqualTo("[]");
    assertThat(ops.get("/api/v1/auth/logout").get("post").get("security").toString())
        .isEqualTo("[{\"bearer\":[]}]");
    assertThat(ops.get("/api/v1/analyses").get("post").get("security").toString())
        .isEqualTo("[{\"bearer\":[]}]");
    // 인증 사용자는 매개변수가 아니다
    for (String path : List.of("/api/v1/users/me", "/api/v1/analyses")) {
      assertThat(ops.get(path).get("get").path("parameters").toString())
          .doesNotContain("AuthUser")
          .doesNotContain("\"user\"");
    }
    // 제출은 multipart 본문 (survey가 쿼리로 새지 않는다, deviceType은 없어짐)
    JsonNode submit = ops.get("/api/v1/analyses").get("post");
    assertThat(submit.path("parameters").toString())
        .doesNotContain("survey")
        .doesNotContain("deviceType");
    JsonNode form =
        submit.get("requestBody").get("content").get("multipart/form-data").get("schema");
    assertThat(form.get("$ref").stringValue()).isEqualTo("#/components/schemas/AnalysisSubmission");
    JsonNode formSchema = schemas.get("AnalysisSubmission");
    assertThat(formSchema.get("properties").get("image").get("format").stringValue())
        .isEqualTo("binary");
    List<String> required = new ArrayList<>();
    formSchema.get("required").forEach(n -> required.add(n.stringValue()));
    assertThat(required).containsExactlyInAnyOrder("image", "survey");
    assertThat(formSchema.get("properties").has("deviceType")).isFalse();
    assertThat(paths).doesNotContain("/error");
    // 상태 코드
    assertThat(ops.get("/api/v1/auth/signup").get("post").get("responses").has("201")).isTrue();
    assertThat(ops.get("/api/v1/analyses").get("post").get("responses").has("202")).isTrue();
    assertThat(ops.get("/api/v1/analyses/{id}").get("delete").get("responses").has("204")).isTrue();
  }
}
