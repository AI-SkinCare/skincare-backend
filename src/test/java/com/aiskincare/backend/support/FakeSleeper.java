package com.aiskincare.backend.support;

import com.aiskincare.backend.aiclient.FixtureAiClient;
import com.aiskincare.backend.common.util.Sleeper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * 지연 주입 — 실제로 기다리지 않는다. 호출된 지연을 기록한다. holdSlow()를 켜면 rec-slow(60초) 지연만 releaseSlow() 전까지 붙잡는다
 * (TC-PERF-04, 처리 중 상태 확인).
 */
public class FakeSleeper implements Sleeper {
  private final List<Duration> calls = new CopyOnWriteArrayList<>();
  private volatile CountDownLatch slowGate = null;

  @Override
  public void sleep(Duration duration) throws InterruptedException {
    calls.add(duration);
    CountDownLatch gate = slowGate;
    if (gate != null && duration.equals(FixtureAiClient.REC_SLOW_DELAY)) gate.await();
  }

  public List<Duration> calls() {
    return List.copyOf(calls);
  }

  public void reset() {
    releaseSlow();
    calls.clear();
  }

  public void holdSlow() {
    slowGate = new CountDownLatch(1);
  }

  public void releaseSlow() {
    CountDownLatch gate = slowGate;
    slowGate = null;
    if (gate != null) gate.countDown();
  }
}
