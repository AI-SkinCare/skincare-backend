package com.aiskincare.backend.tools;

import com.aiskincare.backend.support.TestImages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실행 중인 백엔드의 API 전체 점검 (M3 배포 서버 + 실제 AI 연동 확인용).
 *
 * <pre>
 * SMOKE_BASE_URL=https://app.&lt;도메인&gt;/api/v1 ./gradlew smoke
 * </pre>
 *
 * 기본은 임시 계정을 가입시켜 쓰고 끝나면 탈퇴까지 한다 (시드 계정 불필요). 12개 엔드포인트와 주요 오류 경로를 본다. 분석은 분류 → 추천 완료(COMPLETED) 또는
 * 보류(WITHHELD)까지 확인한다 — 어느 쪽이 될지는 분류 결과에 달려 있다.
 *
 * <p>환경변수: SMOKE_BASE_URL(기본 http://localhost:3000/api/v1), SMOKE_EMAIL(기존 계정 — 가입·탈퇴 생략),
 * SMOKE_PASSWORD(기본 SEED_PASSWORD), SMOKE_IMAGE(JPEG/PNG 경로, 없으면 합성 PNG — 분류기는 얼굴이 아니어도 분류하므로 흐름
 * 확인은 되지만 결과는 의미 없음), SMOKE_TIMEOUT_SEC(추천 완료까지 최대 초, 기본 300), SMOKE_SCENARIO(X-Fixture-Scenario,
 * fixture 개발 서버에서만).
 *
 * <p>모두 통과하면 0, 하나라도 기대와 다르면 1로 끝난다. 토큰·설문 원문은 출력하지 않는다.
 */
public final class SmokeFlow {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final Map<String, String> ENV = loadEnv();
  private static final String BASE =
      env("SMOKE_BASE_URL", "http://localhost:3000/api/v1").replaceAll("/+$", "");
  private static final String EXISTING_EMAIL = env("SMOKE_EMAIL", null);
  private static final int TIMEOUT_SEC = Integer.parseInt(env("SMOKE_TIMEOUT_SEC", "300"));

  private static int failures = 0;
  private static int checks = 0;

  private record Image(byte[] bytes, String type, String name) {}

  private record Res(int status, JsonNode body, Map<String, List<String>> headers) {
    JsonNode data() {
      return body == null ? null : body.path("data");
    }

    String errorCode() {
      return body == null ? "" : body.path("error").path("code").asString("");
    }

    String header(String name) {
      for (var e : headers.entrySet())
        if (e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
      return null;
    }
  }

  private SmokeFlow() {}

  /** .env를 읽는다 (환경변수가 우선) */
  private static Map<String, String> loadEnv() {
    Map<String, String> out = new LinkedHashMap<>();
    Path file = Path.of(".env");
    if (Files.exists(file)) {
      try {
        for (String line : Files.readAllLines(file)) {
          String trimmed = line.trim();
          if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
          int i = trimmed.indexOf('=');
          out.put(
              trimmed.substring(0, i).trim(),
              trimmed.substring(i + 1).trim().replaceAll("^\"|\"$", ""));
        }
      } catch (IOException e) {
        // .env가 없거나 읽을 수 없으면 환경변수만
      }
    }
    out.putAll(System.getenv());
    return out;
  }

  private static String env(String name, String fallback) {
    String value = ENV.get(name);
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static boolean check(boolean ok, String label, String detail) {
    checks++;
    System.out.println(
        (ok ? "✔ " : "✘ ") + label + (detail == null || detail.isEmpty() ? "" : " — " + detail));
    if (!ok) failures++;
    return ok;
  }

  private static boolean check(boolean ok, String label) {
    return check(ok, label, "");
  }

  private static void expectError(Res res, int status, String code, String label) {
    check(
        res.status() == status && code.equals(res.errorCode()),
        label + " → " + status + " " + code,
        res.status() == status ? "" : "실제 " + res.status() + " " + res.errorCode());
  }

  private static Res call(String method, String path, String token, Object json, Multipart form) {
    var builder =
        HttpRequest.newBuilder(URI.create(BASE + path))
            .header("X-Client", "ios")
            .header("X-Request-Id", "smoke-" + UUID.randomUUID());
    if (token != null) builder.header("Authorization", "Bearer " + token);
    String scenario = env("SMOKE_SCENARIO", null);
    if (scenario != null) builder.header("X-Fixture-Scenario", scenario);
    HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
    if (json != null) {
      builder.header("Content-Type", "application/json");
      body = HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(json));
    } else if (form != null) {
      builder.header("Content-Type", form.contentType());
      body = HttpRequest.BodyPublishers.ofByteArray(form.bytes());
    }
    try {
      HttpResponse<String> res =
          CLIENT.send(builder.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
      String text = res.body();
      return new Res(
          res.statusCode(),
          text == null || text.isEmpty() ? null : JSON.readTree(text),
          res.headers().map());
    } catch (IOException e) {
      throw new IllegalStateException(method + " " + path + " 실패: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static Res call(String method, String path, String token) {
    return call(method, path, token, null, null);
  }

  private static final class Multipart {
    private final String boundary = "Smoke-" + UUID.randomUUID();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Multipart field(String name, String value) {
      out.writeBytes(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + name
                  + "\"\r\n\r\n"
                  + value
                  + "\r\n")
              .getBytes(StandardCharsets.UTF_8));
      return this;
    }

    Multipart file(String name, Image image) {
      out.writeBytes(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + name
                  + "\"; filename=\""
                  + image.name()
                  + "\"\r\nContent-Type: "
                  + image.type()
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.writeBytes(image.bytes());
      out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
      return this;
    }

    byte[] bytes() {
      var copy = new ByteArrayOutputStream();
      copy.writeBytes(out.toByteArray());
      copy.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      return copy.toByteArray();
    }

    String contentType() {
      return "multipart/form-data; boundary=" + boundary;
    }
  }

  private static Multipart imageForm(Image image, Object survey) {
    return new Multipart().file("image", image).field("survey", JSON.writeValueAsString(survey));
  }

  private static Image loadImage() throws IOException {
    String path = env("SMOKE_IMAGE", null);
    if (path == null) return new Image(TestImages.png(1080, 1440), "image/png", "smoke.png");
    byte[] bytes = Files.readAllBytes(Path.of(path));
    boolean png = bytes.length > 0 && (bytes[0] & 0xff) == 0x89;
    return new Image(bytes, png ? "image/png" : "image/jpeg", png ? "smoke.png" : "smoke.jpg");
  }

  private static final Map<String, Object> SURVEY =
      Map.of(
          "gender",
          "female",
          "ageBand",
          "20s",
          "skinType",
          "combination",
          "concerns",
          List.of("pore"),
          "note",
          "스모크 테스트");

  private static void run() throws IOException, InterruptedException {
    System.out.println("대상: " + BASE + "\n");

    // 1. 시스템 상태 (인증 없음)
    Res status = call("GET", "/system/status", null);
    check(status.status() == 200, "GET /system/status", String.valueOf(status.data()));
    String echoed = status.header("X-Request-Id");
    check(echoed != null && echoed.startsWith("smoke-"), "X-Request-Id를 그대로 돌려줌");
    if (status.data() == null || !status.data().path("aiAvailable").asBoolean(false)) {
      System.out.println("  ! aiAvailable=false — 분석 제출은 503 E3001이 정상");
    }

    // 2. 가입 (임시 계정) — 기존 계정을 주면 건너뜀
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String email = EXISTING_EMAIL != null ? EXISTING_EMAIL : "smoke-" + suffix + "@example.com";
    String password =
        EXISTING_EMAIL != null
            ? env("SMOKE_PASSWORD", env("SEED_PASSWORD", ""))
            : "Smoke!" + suffix;
    if (EXISTING_EMAIL == null) {
      Map<String, Object> agreements =
          Map.of("terms", true, "privacy", true, "imageProcessing", true, "over14", true);
      Res signup =
          call(
              "POST",
              "/auth/signup",
              null,
              Map.of(
                  "email",
                  email,
                  "password",
                  password,
                  "nickname",
                  "스모크",
                  "agreements",
                  agreements),
              null);
      check(
          signup.status() == 201 && signup.data().path("userId").isNumber(),
          "POST /auth/signup → 201");
      expectError(
          call(
              "POST",
              "/auth/signup",
              null,
              Map.of(
                  "email",
                  email.toUpperCase(),
                  "password",
                  password,
                  "nickname",
                  "스모크",
                  "agreements",
                  agreements),
              null),
          409,
          "E1004",
          "같은 이메일(대소문자만 다름) 재가입");
      Map<String, Object> missing = new LinkedHashMap<>(agreements);
      missing.put("over14", false);
      expectError(
          call(
              "POST",
              "/auth/signup",
              null,
              Map.of(
                  "email",
                  "x" + email,
                  "password",
                  password,
                  "nickname",
                  "스모크",
                  "agreements",
                  missing),
              null),
          422,
          "E4002",
          "동의 누락 가입");
    }

    // 3. 로그인
    expectError(
        call("POST", "/auth/login", null, Map.of("email", email, "password", "Wrong!pass1"), null),
        401,
        "E1003",
        "틀린 비밀번호 로그인");
    Res login =
        call("POST", "/auth/login", null, Map.of("email", email, "password", password), null);
    if (!check(
        login.status() == 200,
        "POST /auth/login → 200 (" + email.replaceAll("^(.).*@", "$1***@") + ")",
        login.errorCode())) {
      return;
    }
    String accessToken = login.data().path("accessToken").asString();
    String refreshToken1 = login.data().path("refreshToken").asString(null);
    check(refreshToken1 != null, "[앱 협의] X-Client: ios → data.refreshToken");
    String cookie = login.header("Set-Cookie");
    check(
        cookie != null && cookie.contains("Path=/api/v1/auth"),
        "Set-Cookie refresh_token (Path=/api/v1/auth)");

    // 4. 내 정보
    Res me = call("GET", "/users/me", accessToken);
    check(
        me.status() == 200 && email.toLowerCase().equals(me.data().path("email").asString()),
        "GET /users/me → 200");
    expectError(call("GET", "/users/me", null), 401, "E1001", "토큰 없이 /users/me");

    // 5. 분석 제출 — 오류 경로 먼저 (행이 생기지 않는다)
    Image image = loadImage();
    expectError(
        call(
            "POST",
            "/analyses",
            accessToken,
            null,
            imageForm(new Image(TestImages.gif(), "image/gif", "x.gif"), SURVEY)),
        415,
        "E2004",
        "GIF 제출");
    Res noGender =
        call("POST", "/analyses", accessToken, null, imageForm(image, Map.of("ageBand", "20s")));
    expectError(noGender, 422, "E4002", "성별 없는 설문");
    check(
        noGender.body() != null
            && noGender.body().path("error").path("detail").toString().contains("survey.gender"),
        "  detail에 survey.gender");

    long startedAt = System.currentTimeMillis();
    Res created = call("POST", "/analyses", accessToken, null, imageForm(image, SURVEY));
    check(
        created.status() == 202,
        "POST /analyses → 202 (" + (System.currentTimeMillis() - startedAt) + "ms)",
        created.body() == null
            ? ""
            : String.valueOf(
                created.status() == 202 ? created.data() : created.body().path("error")));
    if (created.status() != 202) return;
    String id = created.data().path("analysisId").asString();
    JsonNode disease = created.data().path("disease");
    check(
        disease.path("labelEn").isString() && disease.path("probabilities").size() == 6,
        "  분류 결과 (6종 점수)",
        "labelEn="
            + disease.path("labelEn").asString()
            + " 점수="
            + disease.path("confidence")
            + " uncertain="
            + disease.path("uncertain")
            + " model="
            + disease.path("modelVersion").asString());
    expectError(
        call("POST", "/analyses", accessToken, null, imageForm(image, SURVEY)),
        429,
        "E4004",
        "진행 중 두 번째 제출");

    // 6. 결과 폴링 (앱과 같은 3초 간격)
    String last = "";
    JsonNode detail = null;
    while (System.currentTimeMillis() - startedAt < TIMEOUT_SEC * 1000L) {
      detail = call("GET", "/analyses/" + id, accessToken).data();
      String st = detail.path("status").asString();
      String state =
          st
              + " (순번 "
              + detail.path("queuePosition")
              + ", 예상 "
              + detail.path("estimatedWaitSec")
              + "초)";
      if (!state.equals(last)) {
        System.out.println(
            "  … " + Math.round((System.currentTimeMillis() - startedAt) / 1000.0) + "s " + state);
      }
      last = state;
      if (List.of("COMPLETED", "WITHHELD", "FAILED").contains(st)) break;
      Thread.sleep(3000);
    }
    String finalStatus = detail == null ? "" : detail.path("status").asString();
    JsonNode recommendation = detail == null ? null : detail.path("recommendation");
    check(
        "COMPLETED".equals(finalStatus) || "WITHHELD".equals(finalStatus),
        "GET /analyses/{id} → "
            + finalStatus
            + " "
            + (detail == null ? "" : detail.path("recommendationStatus").asString(""))
            + " ("
            + Math.round((System.currentTimeMillis() - startedAt) / 1000.0)
            + "s)",
        detail == null ? "" : String.valueOf(detail.path("error")));
    if ("WITHHELD".equals(finalStatus)) {
      check(
          recommendation.isNull() && detail.path("guidance").size() > 0,
          "  보류: 추천 null + 안내 " + detail.path("guidance").size() + "개");
    }
    if ("COMPLETED".equals(finalStatus)) {
      boolean all =
          List.of(
                  "skinType",
                  "concerns",
                  "ingredients",
                  "routine",
                  "reasoning",
                  "answer",
                  "disclaimer",
                  "inputNotes",
                  "inferenceMs")
              .stream()
              .allMatch(recommendation::has);
      check(
          all,
          "  추천 필드 모두",
          recommendation.path("skinType")
              + " 고민 "
              + recommendation.path("concerns").size()
              + " 성분 "
              + recommendation.path("ingredients").size()
              + " 루틴 "
              + recommendation.path("routine").size()
              + " 근거 "
              + recommendation.path("reasoning").size()
              + " model="
              + recommendation.path("modelVersion").asString());
    }
    expectError(call("GET", "/analyses/NOT-A-ULID", accessToken), 404, "E4003", "형식이 틀린 분석 id");

    // 7–8. 이력·재요청
    Res list = call("GET", "/analyses?page=1&size=1", accessToken);
    check(
        list.status() == 200
            && id.equals(list.data().path("items").path(0).path("analysisId").asString()),
        "GET /analyses?size=1 → 최신 1건");
    expectError(call("GET", "/analyses?size=51", accessToken), 422, "E4002", "size=51");
    expectError(
        call("POST", "/analyses/" + id + "/recommendation/retry", accessToken),
        409,
        "E4005",
        "완료·보류 분석 추천 재요청");

    // 10. 토큰 재발급·재사용 감지·로그아웃
    if (refreshToken1 != null) {
      Res refreshed =
          call("POST", "/auth/refresh", null, Map.of("refreshToken", refreshToken1), null);
      String refreshToken2 =
          refreshed.data() == null ? null : refreshed.data().path("refreshToken").asString(null);
      check(
          refreshed.status() == 200
              && refreshToken2 != null
              && !refreshToken2.equals(refreshToken1),
          "POST /auth/refresh (본문 토큰) → 새 토큰");
      if (refreshed.status() == 200) accessToken = refreshed.data().path("accessToken").asString();
      Res logout =
          call(
              "POST",
              "/auth/logout",
              accessToken,
              Map.of("refreshToken", refreshToken2 == null ? "" : refreshToken2),
              null);
      check(logout.status() == 204, "POST /auth/logout → 204");
      expectError(
          call(
              "POST",
              "/auth/refresh",
              null,
              Map.of("refreshToken", refreshToken2 == null ? "" : refreshToken2),
              null),
          401,
          "E1001",
          "로그아웃한 토큰으로 재발급");
      expectError(
          call("POST", "/auth/refresh", null, Map.of("refreshToken", refreshToken1), null),
          401,
          "E1001",
          "교체된 토큰 재사용");
    }

    // 11. 분석 삭제 (액세스 토큰은 만료 전까지 유효)
    check(
        call("DELETE", "/analyses/" + id, accessToken).status() == 204,
        "DELETE /analyses/{id} → 204");
    expectError(call("GET", "/analyses/" + id, accessToken), 404, "E4003", "삭제한 분석 조회");

    // 12. 탈퇴 (임시 계정만)
    if (EXISTING_EMAIL == null) {
      check(
          call("DELETE", "/users/me", accessToken).status() == 204,
          "DELETE /users/me → 204 (임시 계정 정리)");
      expectError(call("GET", "/users/me", accessToken), 401, "E1001", "탈퇴 후 남은 토큰");
    }
  }

  public static void main(String[] args) {
    try {
      run();
    } catch (Exception e) {
      System.err.println("✘ 예외: " + e.getMessage());
      failures++;
    }
    System.out.println(
        failures == 0
            ? "\n스모크 통과 (" + checks + "개 확인)"
            : "\n실패 " + failures + "건 / " + checks + "개");
    System.exit(failures == 0 ? 0 : 1);
  }
}
