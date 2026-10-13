package com.aiskincare.backend.analyses;

import com.aiskincare.backend.aiclient.AiClient;
import com.aiskincare.backend.aiclient.AiError;
import com.aiskincare.backend.aiclient.AiErrorMapper;
import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.MeasureSkinInput;
import com.aiskincare.backend.aiclient.AiModels.MetricRatios;
import com.aiskincare.backend.aiclient.AiModels.SkinMetricsResult;
import com.aiskincare.backend.aiclient.FixtureScenarioStore;
import com.aiskincare.backend.analyses.AnalysisPresenter.QueueInfo;
import com.aiskincare.backend.analyses.AnalysisRows.AnalysisRow;
import com.aiskincare.backend.analyses.Scoring.MetricBp;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisCreated;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisDetail;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisList;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisTrend;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.ListItem;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.PageInfo;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.common.enums.Roi;
import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.util.TimeUtil;
import com.aiskincare.backend.common.util.Ulid;
import com.aiskincare.backend.recommendation.RecommendationQueueRepository;
import com.aiskincare.backend.system.AiHealthService;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalysesService {
  private static final Logger log = LoggerFactory.getLogger(AnalysesService.class);

  private final AnalysisRepository analyses;
  private final RecommendationQueueRepository queue;
  private final AiClient ai;
  private final AiHealthService aiHealth;
  private final FixtureScenarioStore scenarios;
  private final TransactionTemplate tx;

  public AnalysesService(
      AnalysisRepository analyses,
      RecommendationQueueRepository queue,
      AiClient ai,
      AiHealthService aiHealth,
      FixtureScenarioStore scenarios,
      TransactionTemplate tx) {
    this.analyses = analyses;
    this.queue = queue;
    this.ai = ai;
    this.aiHealth = aiHealth;
    this.scenarios = scenarios;
    this.tx = tx;
  }

  /** TRD §10.1 처리 순서 7–11: AI 측정 → 버퍼 해제 → 점수 → 트랜잭션 저장 → 202. 측정이 실패하면 분석 행을 만들지 않는다. */
  public AnalysisCreated create(long userId, Submission submission, AiCallContext ctx) {
    long started = System.nanoTime();
    SkinMetricsResult result = measure(submission, ctx);
    int measureMs = (int) ((System.nanoTime() - started) / 1_000_000);

    Map<Roi, MetricBp> bpByRoi = new EnumMap<>(Roi.class);
    for (Map.Entry<Roi, MetricRatios> entry : result.metrics().entrySet()) {
      bpByRoi.put(entry.getKey(), MetricBp.of(entry.getValue()));
    }
    var report = Scoring.report(bpByRoi);

    String analysisId = Ulid.generate();
    // 워커가 커밋 직후 집어 갈 수 있으므로 시나리오를 먼저 기억한다
    scenarios.remember(analysisId, ctx.scenario());
    try {
      tx.executeWithoutResult(
          status -> {
            analyses.insertAnalysis(
                analysisId,
                userId,
                submission.deviceType(),
                result.isStub(),
                result.confidence(),
                result.calibrationApplied(),
                report.overallScore(),
                result.modelVersion(),
                measureMs);
            bpByRoi.forEach((roi, bp) -> analyses.insertMetric(analysisId, roi, bp));
            analyses.insertSurvey(analysisId, submission.survey());
          });
    } catch (RuntimeException e) {
      scenarios.forget(analysisId);
      throw e;
    }
    log.atInfo()
        .addKeyValue("analysisId", analysisId)
        .addKeyValue("isDemo", result.isStub())
        .addKeyValue("overallScore", report.overallScore())
        .addKeyValue("measureMs", measureMs)
        .log("analysis_created");
    return created(analysisId);
  }

  public AnalysisDetail findOne(long userId, String analysisId) {
    AnalysisRow analysis = owned(userId, analysisId);
    var metrics = analyses.metrics(analysisId);
    // 동시에 삭제됐으면 설문이 없다
    var survey = analyses.survey(analysisId).orElseThrow(() -> new ApiException(ErrorCode.E4003));
    var recommendation =
        analysis.status() == AnalysisStatus.DONE
            ? analyses.recommendation(analysisId).orElse(null)
            : null;
    QueueInfo queueInfo =
        analysis.status() == AnalysisStatus.QUEUED ? queueInfo(analysisId) : QueueInfo.NONE;
    return AnalysisPresenter.detail(analysis, metrics, survey, recommendation, queueInfo);
  }

  /** TRD §10.3 — failed만 queued로. 0행이면 남의 것·없음 404, 상태가 다르면 409 E4005 */
  public AnalysisCreated retry(long userId, String analysisId, FixtureScenario scenario) {
    FixtureScenario previous = scenarios.get(analysisId);
    scenarios.remember(analysisId, scenario);
    if (!queue.requeueFailed(analysisId, userId)) {
      scenarios.remember(analysisId, previous);
      throw new ApiException(
          analyses.existsOwned(analysisId, userId) ? ErrorCode.E4005 : ErrorCode.E4003);
    }
    log.atInfo().addKeyValue("analysisId", analysisId).log("recommendation_retry_requested");
    return created(analysisId);
  }

  /** TRD §10.4 — created_at DESC, id DESC */
  public AnalysisList list(long userId, int page, int size) {
    var items =
        analyses.list(userId, page, size).stream()
            .map(
                row ->
                    new ListItem(
                        row.id(),
                        TimeUtil.toIsoUtc(row.createdAt()),
                        row.status(),
                        row.overallScore(),
                        row.isDemo(),
                        row.concerns()))
            .toList();
    long total = analyses.count(userId);
    return new AnalysisList(items, new PageInfo(page, size, total, (total + size - 1) / size));
  }

  /** TRD §10.5 — 현재가 데모 값이면 previous·delta는 null */
  public AnalysisTrend trend(long userId, String analysisId) {
    AnalysisRow current = owned(userId, analysisId);
    var currentPoint = AnalysisPresenter.trendPoint(current, analyses.metrics(current.id()));
    if (current.isDemo()) return new AnalysisTrend(currentPoint, null, null);
    var previous = analyses.previousDone(userId, current.createdAt());
    if (previous.isEmpty()) return new AnalysisTrend(currentPoint, null, null);
    var previousPoint =
        AnalysisPresenter.trendPoint(previous.get(), analyses.metrics(previous.get().id()));
    return new AnalysisTrend(
        currentPoint, previousPoint, AnalysisPresenter.delta(currentPoint, previousPoint));
  }

  /** TRD §10.6 — 상태 무관 삭제. 진행 중이면 워커가 결과를 버린다 (§13.7) */
  public void delete(long userId, String analysisId) {
    if (analyses.deleteOwned(analysisId, userId) == 0) throw new ApiException(ErrorCode.E4003);
    scenarios.forget(analysisId);
    log.atInfo().addKeyValue("analysisId", analysisId).log("analysis_deleted");
  }

  /** IDOR: 없거나 남의 것이면 똑같이 404 E4003 */
  private AnalysisRow owned(long userId, String analysisId) {
    return analyses
        .findOwned(analysisId, userId)
        .orElseThrow(() -> new ApiException(ErrorCode.E4003));
  }

  private AnalysisCreated created(String analysisId) {
    QueueInfo info = queueInfo(analysisId);
    return new AnalysisCreated(
        analysisId, AnalysisStatus.QUEUED, info.queuePosition(), info.estimatedWaitSec());
  }

  private QueueInfo queueInfo(String analysisId) {
    long position = queue.queuePosition(analysisId);
    double avgMs = queue.averageRecommendMs();
    return new QueueInfo(position, Math.round(position * avgMs / 1000));
  }

  /** AI 측정 (TRD §12.5 측정 열): 연결 오류만 즉시 1회 재시도. 성공·실패와 관계없이 끝나면 이미지 버퍼 참조를 끊는다. */
  private SkinMetricsResult measure(Submission submission, AiCallContext ctx) {
    try {
      for (int attempt = 1; ; attempt++) {
        try {
          return ai.measureSkin(
              new MeasureSkinInput(
                  submission.image(), submission.mimeType(), submission.deviceType()),
              ctx);
        } catch (AiError error) {
          if (attempt == 1 && AiErrorMapper.measureRetryable(error)) continue;
          throw measureException(error, ctx);
        }
      }
    } finally {
      submission.releaseImage();
    }
  }

  private ApiException measureException(AiError error, AiCallContext ctx) {
    var failure = AiErrorMapper.measure(error);
    log.atLevel(failure.logLevel())
        .addKeyValue("code", failure.code().name())
        .addKeyValue("ai", error.toLog())
        .log("ai_measure_failed");
    if (error.kind() == AiError.Kind.CONNECTION)
      aiHealth.markUnavailable("measure_connection_error");
    return ApiException.withMessage(failure.code(), failure.message());
  }
}
