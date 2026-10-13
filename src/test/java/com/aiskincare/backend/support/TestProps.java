package com.aiskincare.backend.support;

import com.aiskincare.backend.config.AppProperties;

/** 단위 테스트용 AppProperties — 기본값은 application.yml과 같다. 바꿀 값만 덮어쓴다. */
public final class TestProps {
  public String env = "test";
  public String databaseUrl = "postgres://u:p@localhost:5432/db";
  public String accessSecret = "test-access-secret-not-real-32bytes!!";
  public String refreshSecret = "test-refresh-secret-not-real-32bytes!";
  public String aiMode = "fixture";
  public String aiBaseUrl = null;
  public String aiToken = null;
  public int skinTimeoutMs = 10000;
  public int recTimeoutMs = 120000;
  public int healthIntervalMs = 30000;
  public String fixtureScenario = "ok";
  public int workerConcurrency = 1;
  public int queueLimit = 10;
  public int maxWaitMin = 60;
  public int dailyLimit = 20;
  public int loginRatePerMin = 10;
  public boolean trustProxy = false;
  public boolean cookieSecure = true;

  public AppProperties build() {
    return new AppProperties(
        env,
        databaseUrl,
        new AppProperties.Jwt(accessSecret, refreshSecret),
        null,
        new AppProperties.Ai(
            aiMode,
            aiBaseUrl,
            aiToken,
            skinTimeoutMs,
            recTimeoutMs,
            healthIntervalMs,
            fixtureScenario),
        new AppProperties.Queue(workerConcurrency, queueLimit, maxWaitMin),
        dailyLimit,
        loginRatePerMin,
        trustProxy,
        cookieSecure,
        null);
  }

  public static AppProperties defaults() {
    return new TestProps().build();
  }
}
