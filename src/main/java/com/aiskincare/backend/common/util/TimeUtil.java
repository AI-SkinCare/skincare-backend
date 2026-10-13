package com.aiskincare.backend.common.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** TRD §7.4 — 저장은 UTC, 응답은 밀리초 없는 ISO 8601 UTC. KST는 일일 한도·배치 시각에만 (UTC+9 고정). */
public final class TimeUtil {
  private TimeUtil() {}

  public static final ZoneOffset KST = ZoneOffset.ofHours(9);
  private static final DateTimeFormatter ISO_SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  /** {@code 2026-10-15T09:12:33Z} */
  public static String toIsoUtc(Instant instant) {
    return instant == null ? null : ISO_SECONDS.format(instant.truncatedTo(ChronoUnit.SECONDS));
  }

  /** KST 날짜 {@code YYYY-MM-DD} */
  public static String kstDate(Instant instant) {
    return LocalDate.ofInstant(instant, KST).toString();
  }

  /** now가 속한 KST 날짜의 00:00 */
  public static Instant startOfKstDay(Instant now) {
    return LocalDate.ofInstant(now, KST).atStartOfDay().toInstant(KST);
  }

  /** 다음 KST 00:00까지 남은 초 (올림, 최소 1) */
  public static int secondsUntilNextKstMidnight(Instant now) {
    Instant next = startOfKstDay(now).plus(Duration.ofDays(1));
    long millis = Duration.between(now, next).toMillis();
    return (int) Math.max(1, (millis + 999) / 1000);
  }
}
