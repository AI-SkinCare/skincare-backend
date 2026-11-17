package com.aiskincare.backend.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.recommendation.RecommendationWorker;
import com.aiskincare.backend.support.Factories;
import com.aiskincare.backend.support.IntegrationTestBase;
import com.aiskincare.backend.support.LogLines;
import com.aiskincare.backend.support.TestHttp;
import com.aiskincare.backend.support.TestHttp.Multipart;
import com.aiskincare.backend.support.TestHttp.Res;
import com.aiskincare.backend.support.TestImages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** 개인정보 (TRD §15). 운영과 같은 INFO 로그에 우리 코드는 DEBUG까지 켠다. */
@TestPropertySource(properties = {"logging.level.root=INFO", "logging.level.com.aiskincare=DEBUG"})
@ExtendWith(OutputCaptureExtension.class)
class PrivacyIT extends IntegrationTestBase {
  @Autowired RecommendationWorker worker;

  private static final Pattern MARKER_SAFE = Pattern.compile("[A-Za-z0-9-]+");

  /** 디렉터리 아래(4단계까지) since 이후 바뀐 파일 중 marker를 담은 것 */
  private static List<Path> filesContaining(Path dir, long since, String marker)
      throws IOException {
    assertThat(MARKER_SAFE.matcher(marker).matches()).isTrue();
    byte[] needle = marker.getBytes(StandardCharsets.US_ASCII);
    List<Path> found = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(dir, 4)) {
      for (Path path : (Iterable<Path>) paths::iterator) {
        try {
          if (!Files.isRegularFile(path)) continue;
          if (Files.getLastModifiedTime(path).toMillis() < since
              || Files.size(path) > 50L * 1024 * 1024) continue;
          if (indexOf(Files.readAllBytes(path), needle) >= 0) found.add(path);
        } catch (IOException | SecurityException e) {
          // 사라진 파일·권한 없는 파일은 무시
        }
      }
    } catch (java.io.UncheckedIOException e) {
      // 순회 중 사라진 디렉터리
    }
    return found;
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }

  @Test
  @DisplayName("TC-PRI-01: 분석 처리 후 업로드 관련 파일이 디스크(임시 디렉터리)에 없다")
  void noImageOnDisk() throws IOException {
    String marker = "face-marker-" + System.nanoTime();
    long since = System.currentTimeMillis() - 1000;
    var me = factories.userWithToken();
    Res res =
        factories.submit(
            me,
            TestImages.png(1080, 1440, marker),
            "face.png",
            TestHttp.json().writeValueAsString(Factories.defaultSurvey()),
            null);
    assertThat(res.status()).isEqualTo(202);
    worker.tick();
    assertThat(
            http.get("/api/v1/analyses/" + res.data().get("analysisId").stringValue())
                .bearer(me.accessToken())
                .send()
                .status())
        .isEqualTo(200);

    Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
    assertThat(filesContaining(tmp, since, marker)).isEmpty();
    assertThat(filesContaining(Paths.get("build"), since, marker)).isEmpty();
  }

  @Test
  @DisplayName("TC-PRI-01: 코드 검사 — 업로드를 파일로 쓰는 API를 쓰지 않는다")
  void noDiskApisInCode() throws IOException {
    Pattern forbidden =
        Pattern.compile(
            "transferTo\\s*\\(|createTempFile\\s*\\(|DiskFileItemFactory|FileOutputStream|Files\\.write\\s*\\(|getInputStream\\(\\)\\.transferTo");
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
      for (Path path : (Iterable<Path>) files::iterator) {
        if (!path.toString().endsWith(".java")) continue;
        if (forbidden.matcher(Files.readString(path)).find()) offenders.add(path.toString());
      }
    }
    assertThat(offenders).isEmpty();
    // multipart는 메모리에서만 (임계값 > 최대 파일 크기)
    String yml = Files.readString(Paths.get("src/main/resources/application.yml"));
    assertThat(yml).contains("file-size-threshold: 11MB").contains("max-file-size: 10MB");
  }

  @Test
  @DisplayName("로그 줄이 빠지지 않는다 — MDC(requestId·userId)와 같은 키를 key-value로 넣지 않는다")
  void noDroppedLogLines(CapturedOutput output) {
    int start = output.getAll().length();
    var me = factories.userWithToken();
    assertThat(http.get("/api/v1/users/me").bearer(me.accessToken()).send().status())
        .isEqualTo(200);
    assertThat(http.delete("/api/v1/users/me").bearer(me.accessToken()).send().status())
        .isEqualTo(204);

    String raw = output.getAll().substring(start);
    assertThat(raw).doesNotContain("failed to append");
    LogLines logs = new LogLines(raw);
    String userId = String.valueOf(me.user().id());
    assertThat(logs.lines())
        .anySatisfy(
            l -> {
              assertThat(l.path("message").asString()).isEqualTo("request completed");
              assertThat(l.path("route").asString()).isEqualTo("/api/v1/users/me");
              assertThat(l.path("method").asString()).isEqualTo("GET");
              assertThat(l.path("userId").asString()).isEqualTo(userId);
            });
    assertThat(logs.find("user_deleted").orElseThrow().path("userId").asString()).isEqualTo(userId);
  }

  @Test
  @DisplayName("TC-PRI-02: 전체 흐름의 로그에 설문 원문·토큰·비밀번호·이메일 원문·분류 라벨이 없다")
  void noSecretsInLogs(CapturedOutput output) {
    long now = System.nanoTime();
    String note = "비밀메모-" + now;
    String product = "비밀제품-" + now;
    String email = "private.person" + now + "@example.com";
    List<String> secrets = new ArrayList<>(List.of(note, product, Factories.PASSWORD, email));
    int start = output.getAll().length();

    Res signup =
        http.post("/api/v1/auth/signup")
            .json(
                Map.of(
                    "email",
                    email,
                    "password",
                    Factories.PASSWORD,
                    "nickname",
                    "비밀",
                    "agreements",
                    Map.of(
                        "terms", true, "privacy", true, "imageProcessing", true, "over14", true)))
            .send();
    assertThat(signup.status()).isEqualTo(201);
    Res login =
        http.post("/api/v1/auth/login")
            .header("X-Client", "ios")
            .header("CF-Connecting-IP", Factories.randomIp())
            .json(Map.of("email", email, "password", Factories.PASSWORD))
            .send();
    assertThat(login.status()).isEqualTo(200);
    String accessToken = login.data().get("accessToken").stringValue();
    String refreshToken = login.data().get("refreshToken").stringValue();
    secrets.addAll(List.of(accessToken, refreshToken, Factories.refreshCookieOf(login)));

    Res refreshed =
        http.post("/api/v1/auth/refresh")
            .header("X-Client", "ios")
            .json(Map.of("refreshToken", refreshToken))
            .send();
    assertThat(refreshed.status()).isEqualTo(200);
    secrets.add(refreshed.data().get("accessToken").stringValue());
    secrets.add(refreshed.data().get("refreshToken").stringValue());
    // 재사용 감지 WARN도 남긴다
    assertThat(
            http.post("/api/v1/auth/refresh")
                .json(Map.of("refreshToken", refreshToken))
                .send()
                .status())
        .isEqualTo(401);

    // 재사용 감지로 전부 폐기됐으니 다시 로그인
    Res relogin =
        http.post("/api/v1/auth/login")
            .header("X-Client", "ios")
            .header("CF-Connecting-IP", Factories.randomIp())
            .json(Map.of("email", email, "password", Factories.PASSWORD))
            .send();
    String token = relogin.data().get("accessToken").stringValue();
    String lastRefresh = relogin.data().get("refreshToken").stringValue();
    secrets.addAll(List.of(token, lastRefresh));

    Map<String, Object> survey = new LinkedHashMap<>(Factories.defaultSurvey());
    survey.put("note", note);
    survey.put("currentProducts", List.of(product));
    Res submitted =
        http.post("/api/v1/analyses")
            .bearer(token)
            .multipart(
                new Multipart()
                    .file("image", "face.png", "image/png", Factories.FACE)
                    .field("survey", TestHttp.json().writeValueAsString(survey)))
            .send();
    assertThat(submitted.status()).isEqualTo(202);
    worker.tick();
    assertThat(
            http.get("/api/v1/analyses/" + submitted.data().get("analysisId").stringValue())
                .bearer(token)
                .send()
                .status())
        .isEqualTo(200);
    // 검증 실패 경로도 (survey 원문이 detail·로그에 들어가지 않아야 함)
    Res invalid =
        http.post("/api/v1/analyses")
            .bearer(token)
            .multipart(new Multipart().field("survey", "{\"note\":\"" + note + "\""))
            .send();
    assertThat(invalid.status()).isEqualTo(422);
    assertThat(invalid.raw()).doesNotContain(note);
    assertThat(
            http.post("/api/v1/auth/logout")
                .bearer(token)
                .json(Map.of("refreshToken", lastRefresh))
                .send()
                .status())
        .isEqualTo(204);

    String raw = output.getAll().substring(start);
    assertThat(raw).doesNotContain("failed to append");
    LogLines logs = new LogLines(raw);
    assertThat(logs.lines().size()).isGreaterThan(5);
    for (String secret : secrets) assertThat(logs.raw()).as("로그에 비밀값").doesNotContain(secret);
    assertThat(logs.raw()).contains("p***@example.com");
    // 분류 라벨·안내 문장은 남기지 않고 추천 상태·시간만 남긴다 (TRD §7.6)
    assertThat(logs.raw()).doesNotContain("아토피").doesNotContain("의학적 진단");
    JsonNode done =
        logs.find("recommendation_done").orElseThrow(() -> new AssertionError(logs.raw()));
    assertThat(done.get("recommendationStatus").asString()).isEqualTo("generated");
    JsonNode created = logs.find("analysis_created").orElseThrow();
    assertThat(created.get("uncertain").isBoolean()).isTrue();
    // 요청 로그 공통 필드
    JsonNode request = logs.find("request completed").orElseThrow();
    assertThat(request.get("requestId").isString()).isTrue();
    assertThat(request.get("durationMs").isNumber()).isTrue();
  }
}
