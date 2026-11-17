package com.aiskincare.backend.aiclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.support.Factories.LoggedIn;
import com.aiskincare.backend.support.IntegrationTestBase;
import com.aiskincare.backend.support.LogLines;
import com.aiskincare.backend.support.TestHttp.Res;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** TC-ANA-10: AI 401(내부 토큰 불일치) → 클라이언트 503 E3001 + ERROR 로그. AI_MODE=real + 로컬 가짜 AI 서버 */
@TestPropertySource(
    properties = {
      "app.ai.mode=real",
      "app.ai.classify-timeout-ms=2000",
      "app.ai.rec-timeout-ms=2000"
    })
@ExtendWith(OutputCaptureExtension.class)
class AiHttpIT extends IntegrationTestBase {
  private static final String WRONG_TOKEN = "wrong-internal-token";
  private static volatile int classifyStatus = 401;
  private static final HttpServer AI = startFakeAi();

  private static HttpServer startFakeAi() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            String body;
            int status;
            if (exchange.getRequestURI().getPath().equals("/health")) {
              status = 200;
              body =
                  "{\"status\":\"ok\",\"rec_loaded\":true,\"skin_loaded\":true,\"rec_backend\":\"sllm\",\"skin_backend\":\"stub\",\"disease_loaded\":true,\"disease_backend\":\"densenet\"}";
            } else if (classifyStatus == 401) {
              status = 401;
              body =
                  "{\"success\":false,\"error\":{\"code\":\"E1001\",\"message\":\"내부 인증 토큰이 없거나 올바르지 않습니다.\",\"detail\":null}}";
            } else {
              status = classifyStatus;
              body = "<html>bad gateway</html>";
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange
                .getResponseHeaders()
                .set(
                    "Content-Type",
                    status == 200 || status == 401 ? "application/json" : "text/html");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void aiProperties(DynamicPropertyRegistry registry) {
    registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + AI.getAddress().getPort());
    registry.add("app.ai.internal-token", () -> WRONG_TOKEN);
  }

  @AfterAll
  static void stopAi() {
    AI.stop(0);
  }

  @Test
  @DisplayName("TC-ANA-10: 401을 그대로 내보내지 않는다 (앱이 로그아웃시키므로) + ERROR 로그, 토큰은 로그에 없음")
  void ai401(CapturedOutput output) {
    classifyStatus = 401;
    LoggedIn me = factories.userWithToken();
    Res res = factories.submit(me);
    assertThat(res.status()).isEqualTo(503);
    assertThat(res.errorCode()).isEqualTo("E3001");

    LogLines logs = new LogLines(output.getAll());
    JsonNode line =
        logs.find("ai_classify_failed").orElseThrow(() -> new AssertionError(output.getAll()));
    assertThat(line.get("level").stringValue()).isEqualTo("ERROR");
    assertThat(line.get("code").stringValue()).isEqualTo("E3001");
    assertThat(line.get("ai").toString())
        .isEqualTo("{\"kind\":\"http\",\"status\":401,\"code\":\"E1001\"}");
    assertThat(logs.raw()).doesNotContain(WRONG_TOKEN);
    assertThat(count("analyses")).isZero();
  }

  @Test
  @DisplayName("Cloudflare HTML 502 → connection → 503 E3001, aiAvailable을 즉시 false로")
  void cloudflare502() {
    classifyStatus = 502;
    LoggedIn me = factories.userWithToken();
    assertThat(aiHealth.isAvailable()).isTrue();
    Res res = factories.submit(me);
    assertThat(res.status()).isEqualTo(503);
    assertThat(res.errorCode()).isEqualTo("E3001");
    assertThat(aiHealth.isAvailable()).isFalse();
    aiHealth.check();
    assertThat(aiHealth.isAvailable()).isTrue();
  }
}
