package com.aiskincare.backend.recommendation;

import com.aiskincare.backend.config.AppProperties;
import com.aiskincare.backend.config.Constants;
import com.aiskincare.backend.system.AiHealthService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 기동 순서 (TRD §13.2): ① 복구 쿼리 ② 첫 헬스체크 ③ 워커 폴링(1초)·장기 대기 점검(1분) 시작. APP_ENV=test이거나 시드 실행이면 시작하지
 * 않는다. 종료(SIGTERM) 때는 스케줄러가 폴링만 멈추고 진행 중 작업은 기다리지 않는다 — 재시작 복구가 다시 큐에 넣는다.
 */
@Component
public class BackgroundJobs {
  private static final Logger log = LoggerFactory.getLogger(BackgroundJobs.class);
  public static final String DISABLED_PROPERTY = "app.background-jobs.enabled";

  private final QueueMaintenance maintenance;
  private final AiHealthService aiHealth;
  private final RecommendationWorker worker;
  private final TaskScheduler scheduler;
  private final AppProperties props;
  private final boolean enabled;

  public BackgroundJobs(
      QueueMaintenance maintenance,
      AiHealthService aiHealth,
      RecommendationWorker worker,
      TaskScheduler scheduler,
      AppProperties props,
      @org.springframework.beans.factory.annotation.Value("${app.background-jobs.enabled:true}")
          boolean enabled) {
    this.maintenance = maintenance;
    this.aiHealth = aiHealth;
    this.worker = worker;
    this.scheduler = scheduler;
    this.props = props;
    this.enabled = enabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (props.isTest() || !enabled) return;
    maintenance.recoverInterrupted();
    aiHealth.start();
    scheduler.scheduleWithFixedDelay(worker::tick, Constants.WORKER_POLL_INTERVAL);
    scheduler.scheduleWithFixedDelay(
        maintenance::expireStale,
        Instant.now().plus(Constants.QUEUE_EXPIRY_CHECK_INTERVAL),
        Constants.QUEUE_EXPIRY_CHECK_INTERVAL);
    log.info("worker_started");
  }
}
