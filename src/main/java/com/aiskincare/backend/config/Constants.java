package com.aiskincare.backend.config;

import java.time.Duration;

/** TRD §6 — env가 아닌 코드 상수로 두는 값. */
public final class Constants {
  private Constants() {}

  public static final String API_PREFIX = "/api/v1";

  public static final long ACCESS_TOKEN_TTL_SEC = 3600;
  public static final long REFRESH_TOKEN_TTL_SEC = 14L * 24 * 60 * 60;
  public static final int BCRYPT_COST = 12;

  public static final String REFRESH_COOKIE_NAME = "refresh_token";
  public static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

  public static final Duration WORKER_POLL_INTERVAL = Duration.ofSeconds(1);
  public static final Duration WORKER_RETRY_DELAY = Duration.ofSeconds(5);
  public static final Duration QUEUE_EXPIRY_CHECK_INTERVAL = Duration.ofMinutes(1);

  /** 최근 완료 기록이 없을 때 쓰는 평균 추천 시간 (TRD §13.3) */
  public static final long DEFAULT_RECOMMEND_MS = 55_000;

  public static final Duration AI_HEALTH_TIMEOUT = Duration.ofSeconds(5);

  public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
  public static final int MIN_IMAGE_SHORT_SIDE_PX = 720;

  /** 진행 중 분석이 있을 때 Retry-After (TRD §7.3) */
  public static final int IN_PROGRESS_RETRY_AFTER_SEC = 30;

  public static final int APP_STATEMENT_TIMEOUT_MS = 5000;
}
