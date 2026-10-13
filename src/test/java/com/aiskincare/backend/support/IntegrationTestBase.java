package com.aiskincare.backend.support;

import com.aiskincare.backend.system.AiHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * TRD §16.1 통합 테스트 기반: AI_MODE=fixture, APP_ENV=test(워커·헬스체크 자동 시작 안 함), 실제 HTTP + PostgreSQL.
 * 테스트마다 테이블을 비우고 헬스체크를 한 번 돌린다. 지연은 {@link FakeSleeper}로 실제로 기다리지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@Import(IntegrationTestBase.TestBeans.class)
@TestPropertySource(
    properties = {
      "app.env=test",
      "app.jwt.access-secret=test-access-secret-not-real-32bytes!!",
      "app.jwt.refresh-secret=test-refresh-secret-not-real-32bytes!",
      "app.ai.mode=fixture",
      "app.ai.fixture-scenario=ok",
      "app.ai.base-url=",
      "app.ai.internal-token=",
      "app.cookie-secure=true",
      "app.trust-proxy=true",
      "app.cors-origin=",
      "app.analysis-daily-limit=20",
      "app.login-rate-per-min=10",
      "app.queue.limit=10",
      "app.queue.max-wait-min=60",
      "app.queue.worker-concurrency=1",
      "app.seed-password=",
      "logging.level.root=WARN"
    })
public abstract class IntegrationTestBase {

  @TestConfiguration
  static class TestBeans {
    @Bean
    @Primary
    FakeSleeper fakeSleeper() {
      return new FakeSleeper();
    }
  }

  /** 계산이 필요한 값만. 고정 값은 위 @TestPropertySource — 하위 클래스가 같은 키를 덮어쓸 수 있다 */
  @DynamicPropertySource
  static void baseProperties(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @LocalServerPort protected int port;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected FakeSleeper sleeper;
  @Autowired protected AiHealthService aiHealth;
  protected TestHttp http;
  protected Factories factories;

  @BeforeEach
  void baseSetUp() {
    http = new TestHttp(port);
    factories = new Factories(http, jdbc);
    sleeper.reset();
    jdbc.sql(
            "TRUNCATE users, refresh_tokens, analyses, skin_metrics, surveys, recommendations"
                + " RESTART IDENTITY CASCADE")
        .update();
    aiHealth.check();
  }

  protected long count(String table) {
    return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
  }

  protected String statusOf(String analysisId) {
    return jdbc.sql("SELECT status FROM analyses WHERE id = :id")
        .param("id", analysisId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /** 조건이 참이 될 때까지 짧게 기다린다 (워커를 다른 스레드에서 돌린 테스트용) */
  protected void until(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) throw new AssertionError("조건을 기다리다 시간 초과");
      Thread.sleep(10);
    }
  }
}
