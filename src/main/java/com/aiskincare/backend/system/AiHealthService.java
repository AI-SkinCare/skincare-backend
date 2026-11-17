package com.aiskincare.backend.system;

import com.aiskincare.backend.aiclient.AiClient;
import com.aiskincare.backend.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * TRD §14.1 — AI 가용성. 기동 시 1회, 이후 AI_HEALTH_INTERVAL_MS마다 GET /health(5초). status ok이고 분류기가 로드돼
 * 있어야(disease_loaded) 가용 — 분류기가 꺼져 있으면 /infer/disease가 503이라 접수를 미리 막는다 (TRD §12.6). 첫 검사 전에는
 * false. 바뀔 때만 로그(끊김 WARN, 복구 INFO).
 */
@Service
public class AiHealthService {
  private static final Logger log = LoggerFactory.getLogger(AiHealthService.class);

  public record Snapshot(boolean aiAvailable, Instant checkedAt) {}

  private final AiClient ai;
  private final AppProperties props;
  private final TaskScheduler scheduler;
  private volatile boolean available = false;
  private volatile Instant checkedAt = null;
  private volatile boolean started = false;
  private final AtomicReference<CompletableFuture<Boolean>> inFlight = new AtomicReference<>();

  public AiHealthService(AiClient ai, AppProperties props, TaskScheduler scheduler) {
    this.ai = ai;
    this.props = props;
    this.scheduler = scheduler;
  }

  public boolean isAvailable() {
    return available;
  }

  public Snapshot snapshot() {
    return new Snapshot(available, checkedAt);
  }

  /** 동시에 여러 번 불려도 GET /health는 한 번만 */
  public boolean check() {
    CompletableFuture<Boolean> mine = new CompletableFuture<>();
    CompletableFuture<Boolean> running = inFlight.compareAndExchange(null, mine);
    if (running != null) return running.join();
    try {
      boolean ok = runCheck();
      mine.complete(ok);
      return ok;
    } finally {
      inFlight.set(null);
    }
  }

  /** 첫 검사 후 주기 검사를 건다 (워커 기동 순서 ② — TRD §13.2) */
  public void start() {
    if (started) return;
    started = true;
    check();
    Duration interval = Duration.ofMillis(props.ai().healthIntervalMs());
    scheduler.scheduleAtFixedRate(this::check, Instant.now().plus(interval), interval);
  }

  /** 분류 중 connection 오류 → 즉시 비가용으로 두고 다음 검사를 앞당긴다 [구현 결정] */
  public void markUnavailable(String reason) {
    setAvailable(false, reason);
    if (started) scheduler.schedule(this::check, Instant.now());
  }

  private boolean runCheck() {
    boolean ok = false;
    String reason = "ok";
    try {
      var health = ai.health();
      ok = "ok".equals(health.status()) && health.diseaseLoaded();
      if (!"ok".equals(health.status())) reason = "status:" + health.status();
      else if (!health.diseaseLoaded()) reason = "disease_not_loaded";
    } catch (RuntimeException e) {
      reason = e.getMessage();
    }
    checkedAt = Instant.now();
    setAvailable(ok, reason);
    return ok;
  }

  private void setAvailable(boolean next, String reason) {
    if (next == available) return;
    available = next;
    if (next) {
      log.info("ai_available");
      // 모델 버전 기록 — 기동 때 AI가 아직 안 떠 있어도 놓치지 않게 가용해질 때마다 (주기 검사에선 상태가 바뀔 때만)
      if (started) logModelInfo();
    } else {
      log.atWarn().addKeyValue("reason", reason).log("ai_unavailable");
    }
  }

  private void logModelInfo() {
    try {
      log.atInfo().addKeyValue("modelInfo", String.valueOf(ai.modelInfo())).log("ai_model_info");
    } catch (RuntimeException e) {
      log.atWarn().addKeyValue("reason", e.getMessage()).log("ai_model_info_failed");
    }
  }
}
