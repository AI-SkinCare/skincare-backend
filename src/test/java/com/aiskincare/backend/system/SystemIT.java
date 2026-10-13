package com.aiskincare.backend.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiskincare.backend.support.Factories.LoggedIn;
import com.aiskincare.backend.support.IntegrationTestBase;
import com.aiskincare.backend.support.TestHttp.Res;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** TC-SYS-01: AI 서버가 꺼져도 서비스는 산다 (fixture ai-down) */
@TestPropertySource(properties = "app.ai.fixture-scenario=ai-down")
class SystemIT extends IntegrationTestBase {
  @Test
  @DisplayName("TC-SYS-01: aiAvailable false, 제출 503 E3001(Retry-After 30), 로그인·이력은 정상")
  void aiDown() {
    JsonNode status = http.get("/api/v1/system/status").send().data();
    assertThat(status.get("aiAvailable").booleanValue()).isFalse();
    assertThat(status.get("queueLength").intValue()).isZero();
    assertThat(status.get("estimatedWaitSec").intValue()).isZero();
    assertThat(status.get("checkedAt").stringValue())
        .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");

    LoggedIn me = factories.userWithToken();
    Res res = factories.submit(me);
    assertThat(res.status()).isEqualTo(503);
    assertThat(res.errorCode()).isEqualTo("E3001");
    assertThat(res.body().get("error").get("message").stringValue())
        .isEqualTo("지금은 분석 서버 점검 중이에요. 잠시 후 다시 시도해 주세요.");
    assertThat(res.header("Retry-After")).isEqualTo("30");

    assertThat(http.get("/api/v1/analyses").bearer(me.accessToken()).send().status())
        .isEqualTo(200);
    assertThat(http.get("/api/v1/users/me").bearer(me.accessToken()).send().status())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("가드 순서: 진행 중 분석(E4004)이 AI 가용성(E3001)보다 먼저")
  void guardOrder() {
    LoggedIn me = factories.userWithToken();
    jdbc.sql(
            """
            INSERT INTO analyses (id, user_id, status, device_type, is_demo, confidence, overall_score,
                                  skin_model_version, measure_ms)
            VALUES ('01J9Z3K5V8Q2M4N6P8R0S2T4V6', :u, 'queued', 'smartphone', true, 0, 50, 'x', 1)
            """)
        .param("u", me.user().id())
        .update();
    assertThat(factories.submit(me).errorCode()).isEqualTo("E4004");
  }
}
