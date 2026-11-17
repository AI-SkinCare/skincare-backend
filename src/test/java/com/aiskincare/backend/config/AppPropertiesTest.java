package com.aiskincare.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.support.TestProps;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TRD §6 기동 시 검증 */
class AppPropertiesTest {
  private static AppProperties with(Consumer<TestProps> change) {
    TestProps props = new TestProps();
    change.accept(props);
    return props.build();
  }

  @Test
  void defaults() {
    var props = TestProps.defaults();
    assertThat(props.aiReal()).isFalse();
    assertThat(props.defaultScenario()).isEqualTo(FixtureScenario.OK);
    assertThat(props.secureCookie()).isTrue();
  }

  @Test
  @DisplayName("필수값 누락이면 실패 (값은 메시지에 넣지 않음)")
  void missing() {
    assertThatThrownBy(() -> with(p -> p.databaseUrl = "")).hasMessageContaining("DATABASE_URL");
    assertThatThrownBy(() -> with(p -> p.env = "staging")).hasMessageContaining("APP_ENV");
  }

  @Test
  @DisplayName("AI_MODE=real인데 주소·토큰 없음 → 실패, 있으면 끝 / 제거")
  void aiReal() {
    assertThatThrownBy(() -> with(p -> p.aiMode = "real")).hasMessageContaining("AI_BASE_URL");
    var props =
        with(
            p -> {
              p.aiMode = "real";
              p.aiBaseUrl = "https://ai.example.test//";
              p.aiToken = "x";
            });
    assertThat(props.ai().baseUrl()).isEqualTo("https://ai.example.test");
  }

  @Test
  @DisplayName("두 JWT 비밀키가 같거나 32자 미만이면 실패, 값은 메시지에 없음")
  void secrets() {
    String same = "same-secret-same-secret-same-secret!!";
    assertThatThrownBy(() -> with(p -> p.refreshSecret = p.accessSecret = same))
        .hasMessageContaining("서로 달라야")
        .hasMessageNotContaining(same);
    assertThatThrownBy(() -> with(p -> p.accessSecret = "short")).hasMessageContaining("32자 이상");
  }

  @Test
  void workerConcurrencyMustBeOne() {
    assertThatThrownBy(() -> with(p -> p.workerConcurrency = 2))
        .hasMessageContaining("REC_WORKER_CONCURRENCY");
  }

  @Test
  void unknownFixtureScenario() {
    assertThatThrownBy(() -> with(p -> p.fixtureScenario = "slow"))
        .hasMessageContaining("AI_FIXTURE_SCENARIO");
  }

  @Test
  @DisplayName("production은 COOKIE_SECURE=false여도 Secure")
  void productionAlwaysSecure() {
    assertThat(
            with(p -> {
                  p.env = "production";
                  p.cookieSecure = false;
                })
                .secureCookie())
        .isTrue();
    assertThat(with(p -> p.cookieSecure = false).secureCookie()).isFalse();
  }

  @Test
  @DisplayName("DATABASE_URL(postgres://) → JDBC URL·계정 (퍼센트 인코딩 해제)")
  void parseDatabaseUrl() {
    assertThat(DataSourceConfig.parse("postgres://skincare:p%40ss@db:5432/skincare"))
        .isEqualTo(
            new DataSourceConfig.JdbcTarget(
                "jdbc:postgresql://db:5432/skincare", "skincare", "p@ss"));
    assertThat(DataSourceConfig.parse("postgresql://u:p@localhost/x?sslmode=disable"))
        .isEqualTo(
            new DataSourceConfig.JdbcTarget(
                "jdbc:postgresql://localhost:5432/x?sslmode=disable", "u", "p"));
  }
}
