package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.MeasureSkinInput;
import com.aiskincare.backend.aiclient.AiModels.RecommendRequest;
import com.aiskincare.backend.common.enums.DeviceType;
import com.aiskincare.backend.common.error.ErrorCode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.json.JsonMapper;

/** 오류 분류(TRD §12.2)를 실제 HTTP로 확인하는 로컬 가짜 AI 서버 */
class HttpAiClientTest {
  private record Reply(int status, String body, String contentType, long delayMs) {
    static Reply json(int status, String body) {
      return new Reply(status, body, "application/json", 0);
    }

    static Reply html(int status, String body) {
      return new Reply(status, body, "text/html", 0);
    }
  }

  private record Seen(String path, Headers headers, String body) {}

  @FunctionalInterface
  private interface Handler {
    Reply handle(String path, String body);
  }

  private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
  private final List<Seen> seen = new CopyOnWriteArrayList<>();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final RecommendRequest REQUEST =
      new RecommendRequest(null, Map.of("gender", "female", "ageBand", "20s"));
  private static final AiCallContext CTX = new AiCallContext("req-123", null);

  @AfterEach
  void stop() {
    servers.forEach(s -> s.stop(0));
  }

  private String start(Handler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
          seen.add(
              new Seen(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body));
          Reply reply = handler.handle(exchange.getRequestURI().getPath(), body);
          try {
            if (reply.delayMs() > 0) Thread.sleep(reply.delayMs());
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reply.contentType());
            exchange.sendResponseHeaders(reply.status(), out.length);
            exchange.getResponseBody().write(out);
          } catch (InterruptedException | IOException e) {
            // 클라이언트가 먼저 끊은 경우
          } finally {
            exchange.close();
          }
        });
    server.start();
    servers.add(server);
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static HttpAiClient client(String baseUrl, int timeoutMs) {
    return new HttpAiClient(
        new HttpAiClient.Options(baseUrl, "internal-test-token", timeoutMs, timeoutMs), JSON);
  }

  private static HttpAiClient client(String baseUrl) {
    return client(baseUrl, 2000);
  }

  private static String aiError(String code) {
    return "{\"success\":false,\"error\":{\"code\":\""
        + code
        + "\",\"message\":\"x\",\"detail\":null}}";
  }

  private static AiError caught(Executable call) {
    AiError error = catchThrowableOfType(AiError.class, () -> call.execute());
    assertThat(error).as("AiError가 나지 않았다").isNotNull();
    return error;
  }

  @Test
  @DisplayName("본문에 error.code가 있는 4xx/5xx → http (status, code)")
  void httpError() throws IOException {
    String url = start((path, body) -> Reply.json(401, aiError("E1001")));
    AiError error = caught(() -> client(url).recommend(REQUEST, CTX));
    assertThat(error.kind()).isEqualTo(AiError.Kind.HTTP);
    assertThat(error.status()).isEqualTo(401);
    assertThat(error.code()).isEqualTo("E1001");
  }

  @Test
  @DisplayName("error.code 없는 5xx(Cloudflare HTML 503) → connection — E3003으로 읽지 않는다")
  void cloudflareHtml() throws IOException {
    String url = start((path, body) -> Reply.html(503, "<html>cloudflare</html>"));
    AiError error = caught(() -> client(url).recommend(REQUEST, CTX));
    assertThat(error.kind()).isEqualTo(AiError.Kind.CONNECTION);
    assertThat(error.status()).isEqualTo(503);
  }

  @Test
  @DisplayName("응답 전 연결 실패 → connection")
  void connectionRefused() throws IOException {
    String url = start((path, body) -> Reply.json(200, "{}"));
    servers.forEach(s -> s.stop(0));
    servers.clear();
    assertThat(caught(() -> client(url).health()).kind()).isEqualTo(AiError.Kind.CONNECTION);
  }

  @Test
  @DisplayName("Cloudflare 524(AI가 125초 안에 응답 안 함) → timeout — 연결 실패처럼 재시도하지 않게")
  void cloudflare524() throws IOException {
    String url = start((path, body) -> Reply.html(524, "<html>A timeout occurred</html>"));
    AiError error = caught(() -> client(url).recommend(REQUEST, CTX));
    assertThat(error.kind()).isEqualTo(AiError.Kind.TIMEOUT);
    assertThat(error.status()).isEqualTo(524);
    assertThat(AiErrorMapper.recommendRetryable(error)).isFalse();
    assertThat(AiErrorMapper.recommend(error).code()).isEqualTo(ErrorCode.E3002);
  }

  @Test
  @DisplayName("우리 타임아웃 초과 → timeout")
  void ourTimeout() throws IOException {
    String url = start((path, body) -> new Reply(200, "{}", "application/json", 500));
    assertThat(caught(() -> client(url, 50).recommend(REQUEST, CTX)).kind())
        .isEqualTo(AiError.Kind.TIMEOUT);
  }

  @Test
  @DisplayName("2xx인데 JSON이 아님 → invalid_response")
  void notJson() throws IOException {
    String url = start((path, body) -> new Reply(200, "not json", "text/plain", 0));
    assertThat(caught(() -> client(url).recommend(REQUEST, CTX)).kind())
        .isEqualTo(AiError.Kind.INVALID_RESPONSE);
  }

  @Test
  @DisplayName("2xx인데 스키마 위반 → invalid_response")
  void schemaViolation() throws IOException {
    String url = start((path, body) -> Reply.json(200, "{\"skin_type\":1}"));
    assertThat(caught(() -> client(url).recommend(REQUEST, CTX)).kind())
        .isEqualTo(AiError.Kind.INVALID_RESPONSE);
  }

  @Test
  @DisplayName(
      "X-Internal-Token·X-Request-Id를 보낸다. /health에는 내부 토큰을 보내지 않는다. 추천 본문은 snake 아님·metrics null")
  void headers() throws IOException {
    String url =
        start(
            (path, body) ->
                path.equals("/health")
                    ? Reply.json(
                        200,
                        "{\"status\":\"ok\",\"rec_loaded\":true,\"skin_loaded\":true,\"rec_backend\":\"sllm\",\"skin_backend\":\"stub\"}")
                    : Reply.json(401, aiError("E1001")));
    assertThat(client(url).health().skinBackend()).isEqualTo("stub");
    caught(() -> client(url).recommend(REQUEST, CTX));
    assertThat(seen.get(0).headers().getFirst("X-Internal-Token")).isNull();
    assertThat(seen.get(1).headers().getFirst("X-Internal-Token")).isEqualTo("internal-test-token");
    assertThat(seen.get(1).headers().getFirst("X-Request-Id")).isEqualTo("req-123");
    var sent = JSON.readTree(seen.get(1).body());
    assertThat(sent.has("metrics")).isTrue();
    assertThat(sent.get("metrics").isNull()).isTrue();
    assertThat(sent.get("survey").get("gender").asString()).isEqualTo("female");
  }

  @Test
  @DisplayName("측정 요청은 multipart image(시그니처 Content-Type)·device_type")
  void measureMultipart() throws IOException {
    String url = start((path, body) -> Reply.json(422, aiError("E2001")));
    var input =
        new MeasureSkinInput(
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}, "image/png", DeviceType.SMARTPHONE);
    AiError error = caught(() -> client(url).measureSkin(input, CTX));
    assertThat(error.kind()).isEqualTo(AiError.Kind.HTTP);
    assertThat(error.status()).isEqualTo(422);
    assertThat(error.code()).isEqualTo("E2001");
    String body = seen.get(0).body();
    assertThat(seen.get(0).path()).isEqualTo("/infer/skin-metrics");
    assertThat(body).contains("name=\"image\"; filename=\"image.png\"");
    assertThat(body).contains("Content-Type: image/png");
    assertThat(body).contains("name=\"device_type\"");
    assertThat(body).contains("smartphone");
  }

  @Test
  @DisplayName("AiError 메시지·toLog에 AI 응답 본문이 들어가지 않는다")
  void noBodyInError() throws IOException {
    String url =
        start(
            (path, body) ->
                Reply.json(
                    422,
                    "{\"success\":false,\"error\":{\"code\":\"E2002\",\"message\":\"사진이 어두워요\"}}"));
    AiError error = caught(() -> client(url).recommend(REQUEST, CTX));
    assertThat(error.getMessage()).isEqualTo("AI http 422 E2002");
    assertThat(error.toLog().toString()).doesNotContain("어두워요");
  }
}
