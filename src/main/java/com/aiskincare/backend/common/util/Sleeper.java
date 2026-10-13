package com.aiskincare.backend.common.util;

import java.time.Duration;

/** 지연 주입 — 워커 재시도 대기·fixture 지연. 테스트는 이 빈을 바꿔 실제로 기다리지 않는다. */
@FunctionalInterface
public interface Sleeper {
  void sleep(Duration duration) throws InterruptedException;

  Sleeper REAL = duration -> Thread.sleep(duration.toMillis());
}
