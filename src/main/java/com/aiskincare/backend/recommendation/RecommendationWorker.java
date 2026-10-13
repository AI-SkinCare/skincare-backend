package com.aiskincare.backend.recommendation;

import com.aiskincare.backend.aiclient.AiClient;
import com.aiskincare.backend.aiclient.AiError;
import com.aiskincare.backend.aiclient.AiErrorMapper;
import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.RecommendRequest;
import com.aiskincare.backend.aiclient.AiModels.RecommendResult;
import com.aiskincare.backend.aiclient.AiTransform;
import com.aiskincare.backend.aiclient.FixtureScenarioStore;
import com.aiskincare.backend.analyses.AnalysisRepository;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.util.Sleeper;
import com.aiskincare.backend.config.Constants;
import com.aiskincare.backend.system.AiHealthService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 추천 작업 워커 (TRD §13.2). 동시성 1 — GPU 생성이 직렬이다. APP_ENV=test에서는 자동 시작하지 않고 테스트가 {@link #tick()}을 직접
 * 부른다.
 */
@Component
public class RecommendationWorker {
  private static final Logger log = LoggerFactory.getLogger(RecommendationWorker.class);

  private sealed interface Outcome permits Success, Failure {}

  private record Success(RecommendResult result, long ms) implements Outcome {}

  private record Failure(AiError error, long ms) implements Outcome {}

  private final RecommendationQueueRepository queue;
  private final AnalysisRepository analyses;
  private final AiHealthService aiHealth;
  private final FixtureScenarioStore scenarios;
  private final AiClient ai;
  private final Sleeper sleeper;
  private final AtomicBoolean busy = new AtomicBoolean(false);

  public RecommendationWorker(
      RecommendationQueueRepository queue,
      AnalysisRepository analyses,
      AiHealthService aiHealth,
      FixtureScenarioStore scenarios,
      AiClient ai,
      Sleeper sleeper) {
    this.queue = queue;
    this.analyses = analyses;
    this.aiHealth = aiHealth;
    this.scenarios = scenarios;
    this.ai = ai;
    this.sleeper = sleeper;
  }

  public boolean isBusy() {
    return busy.get();
  }

  /** 1초마다: 처리 중이거나 AI 비가용이면 건너뛴다(대기열 유지) */
  public void tick() {
    if (!aiHealth.isAvailable() || !busy.compareAndSet(false, true)) return;
    try {
      queue.acquire().ifPresent(this::process);
    } catch (RuntimeException e) {
      log.error("worker_tick_failed", e);
    } finally {
      busy.set(false);
    }
  }

  private void process(String analysisId) {
    // 워커 작업은 요청 맥락이 없으므로 작업마다 새 requestId (TRD §7.5)
    String requestId = UUID.randomUUID().toString();
    MDC.put("requestId", requestId);
    MDC.put("analysisId", analysisId);
    try {
      var analysis = analyses.findById(analysisId);
      var survey = analyses.survey(analysisId);
      if (analysis.isEmpty() || survey.isEmpty()) {
        // 같은 트랜잭션으로 저장하므로 정상이라면 둘 중 하나만 없을 수 없다. 방금 삭제됐으면 0행이라 아무 일도 없고,
        // 데이터가 어긋났으면 recommending에 멈추지 않게 failed로 끝낸다
        if (analysis.isPresent() && queue.markFailed(analysisId, ErrorCode.E4001, null)) {
          log.error("recommendation_survey_missing");
        } else {
          log.info("recommendation_skipped_deleted");
        }
        return;
      }

      // is_demo=true면 metrics: null (FR-MEA-04)
      RecommendRequest request =
          AiTransform.buildRecommendRequest(
              analysis.get().isDemo()
                  ? null
                  : AiTransform.metricsFromRows(analyses.metrics(analysisId)),
              survey.get());
      // TC-ANA-09를 원문 없이 확인할 수 있게 측정값 전송 여부(ROI 목록 또는 null)만 남긴다
      List<String> metricsSent =
          request.metrics() == null ? null : List.copyOf(request.metrics().keySet());
      Outcome outcome =
          callWithPolicy(
              analysisId, request, new AiCallContext(requestId, scenarios.get(analysisId)));

      switch (outcome) {
        case Success success -> {
          if (!success.result().warnings().isEmpty()) {
            log.atWarn()
                .addKeyValue("warnings", success.result().warnings())
                .log("ai_recommend_count_warning");
          }
          boolean saved = queue.markDone(analysisId, success.ms(), success.result());
          log.atInfo()
              .addKeyValue("recommendMs", success.ms())
              .addKeyValue("metricsSent", metricsSent)
              .log(saved ? "recommendation_done" : "recommendation_discarded");
        }
        case Failure failure -> {
          var mapped = AiErrorMapper.recommend(failure.error());
          log.atLevel(mapped.logLevel())
              .addKeyValue("code", mapped.code().name())
              .addKeyValue("ai", failure.error().toLog())
              .addKeyValue("recommendMs", failure.ms())
              .addKeyValue("metricsSent", metricsSent)
              .log("recommendation_failed");
          if (!queue.markFailed(analysisId, mapped.code(), failure.ms())) {
            log.info("recommendation_discarded");
          }
        }
      }
    } catch (RuntimeException e) {
      log.error("recommendation_unexpected_error", e);
      try {
        queue.markFailed(analysisId, ErrorCode.E4001, null);
      } catch (RuntimeException ignored) {
        // 재시작 복구가 다시 큐에 넣는다
      }
    } finally {
      scenarios.forget(analysisId);
      MDC.remove("analysisId");
      MDC.remove("requestId");
    }
  }

  /**
   * TRD §12.5 워커 열: 연결 오류·503만 5초 후 1회 재시도(attempt_count + 1). 504·422·우리 쪽 타임아웃은 재시도하지 않는다 — AI는
   * 타임아웃 뒤에도 GPU를 붙잡고 생성을 계속한다. recommend_ms는 마지막 호출의 왕복 시간.
   */
  private Outcome callWithPolicy(String analysisId, RecommendRequest request, AiCallContext ctx) {
    for (int attempt = 1; ; attempt++) {
      long started = System.nanoTime();
      try {
        RecommendResult result = ai.recommend(request, ctx);
        return new Success(result, (System.nanoTime() - started) / 1_000_000);
      } catch (AiError error) {
        long ms = (System.nanoTime() - started) / 1_000_000;
        if (attempt == 1 && AiErrorMapper.recommendRetryable(error)) {
          log.atWarn().addKeyValue("ai", error.toLog()).log("ai_recommend_retry");
          try {
            sleeper.sleep(Constants.WORKER_RETRY_DELAY);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failure(error, ms);
          }
          queue.incrementAttempt(analysisId);
          continue;
        }
        return new Failure(error, ms);
      }
    }
  }
}
