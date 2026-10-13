package com.aiskincare.backend.config;

import com.aiskincare.backend.common.enums.FixtureScenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** TRD §6 환경변수. 기동 시 검증하고 문제가 있으면 즉시 종료한다(fail fast). 오류 메시지에는 변수 이름만 넣고 값은 넣지 않는다. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    String env,
    String databaseUrl,
    Jwt jwt,
    String corsOrigin,
    Ai ai,
    Queue queue,
    int analysisDailyLimit,
    int loginRatePerMin,
    boolean trustProxy,
    boolean cookieSecure,
    String seedPassword) {

  public record Jwt(String accessSecret, String refreshSecret) {}

  public record Ai(
      String mode,
      String baseUrl,
      String internalToken,
      int skinTimeoutMs,
      int recTimeoutMs,
      int healthIntervalMs,
      String fixtureScenario) {}

  public record Queue(int workerConcurrency, int limit, int maxWaitMin) {}

  private static final Set<String> ENVS = Set.of("development", "test", "production");
  private static final int MIN_SECRET_LENGTH = 32;

  public AppProperties {
    List<String> problems = new ArrayList<>();
    if (!ENVS.contains(env)) problems.add("APP_ENV: development | test | production");
    if (isBlank(databaseUrl)) problems.add("DATABASE_URL: 필수");
    if (jwt == null || isBlank(jwt.accessSecret()) || isBlank(jwt.refreshSecret())) {
      problems.add("JWT_ACCESS_SECRET, JWT_REFRESH_SECRET: 필수");
    } else {
      if (jwt.accessSecret().length() < MIN_SECRET_LENGTH
          || jwt.refreshSecret().length() < MIN_SECRET_LENGTH) {
        problems.add("JWT_ACCESS_SECRET, JWT_REFRESH_SECRET: 32자 이상 (HS256 키 256비트)");
      }
      if (jwt.accessSecret().equals(jwt.refreshSecret())) {
        problems.add("JWT_ACCESS_SECRET과 JWT_REFRESH_SECRET은 서로 달라야 합니다");
      }
    }
    if (ai == null || !Set.of("fixture", "real").contains(ai.mode())) {
      problems.add("AI_MODE: fixture | real");
    } else {
      if ("real".equals(ai.mode()) && (isBlank(ai.baseUrl()) || isBlank(ai.internalToken()))) {
        problems.add("AI_MODE=real이면 AI_BASE_URL과 AI_INTERNAL_TOKEN이 필요합니다");
      }
      if (ai.skinTimeoutMs() <= 0 || ai.recTimeoutMs() <= 0 || ai.healthIntervalMs() < 1000) {
        problems.add("AI_SKIN_TIMEOUT_MS·AI_REC_TIMEOUT_MS > 0, AI_HEALTH_INTERVAL_MS ≥ 1000");
      }
      if (FixtureScenario.fromCode(ai.fixtureScenario()) == null) {
        problems.add("AI_FIXTURE_SCENARIO: ok | no-face | rec-timeout | rec-slow | ai-down");
      }
    }
    if (queue == null || queue.workerConcurrency() != 1) {
      problems.add("REC_WORKER_CONCURRENCY는 1이어야 합니다 (GPU 생성이 직렬)");
    }
    if (queue != null && (queue.limit() < 1 || queue.maxWaitMin() < 1)) {
      problems.add("REC_QUEUE_LIMIT·REC_QUEUE_MAX_WAIT_MIN ≥ 1");
    }
    if (analysisDailyLimit < 1 || loginRatePerMin < 1) {
      problems.add("ANALYSIS_DAILY_LIMIT·LOGIN_RATE_PER_MIN ≥ 1");
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException("환경변수 검증 실패:\n- " + String.join("\n- ", problems));
    }
    if (ai.baseUrl() != null) {
      ai =
          new Ai(
              ai.mode(),
              ai.baseUrl().replaceAll("/+$", ""),
              ai.internalToken(),
              ai.skinTimeoutMs(),
              ai.recTimeoutMs(),
              ai.healthIntervalMs(),
              ai.fixtureScenario());
    }
  }

  public boolean isProduction() {
    return "production".equals(env);
  }

  public boolean isTest() {
    return "test".equals(env);
  }

  public boolean aiReal() {
    return "real".equals(ai.mode());
  }

  public FixtureScenario defaultScenario() {
    return FixtureScenario.fromCode(ai.fixtureScenario());
  }

  /** production은 항상 Secure 쿠키 */
  public boolean secureCookie() {
    return isProduction() || cookieSecure;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
