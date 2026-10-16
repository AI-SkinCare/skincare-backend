package com.aiskincare.backend.analyses;

import com.aiskincare.backend.aiclient.AiClient;
import com.aiskincare.backend.aiclient.AiError;
import com.aiskincare.backend.aiclient.AiErrorMapper;
import com.aiskincare.backend.aiclient.AiModels.AiCallContext;
import com.aiskincare.backend.aiclient.AiModels.ClassifyInput;
import com.aiskincare.backend.aiclient.AiModels.DiseaseResult;
import com.aiskincare.backend.aiclient.FixtureScenarioStore;
import com.aiskincare.backend.analyses.AnalysisPresenter.QueueInfo;
import com.aiskincare.backend.analyses.AnalysisRows.AnalysisRow;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisList;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.AnalysisView;
import com.aiskincare.backend.analyses.dto.AnalysisResponses.PageInfo;
import com.aiskincare.backend.common.enums.AnalysisStatus;
import com.aiskincare.backend.common.enums.FixtureScenario;
import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.util.Ulid;
import com.aiskincare.backend.recommendation.RecommendationQueueRepository;
import com.aiskincare.backend.system.AiHealthService;
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

  /**
   * TRD §10.1 처리 순서 7–11: AI 분류 → 버퍼 해제 → 트랜잭션 저장(QUEUED) → 202. 분류가 실패하면 분석 행을 만들지 않는다. 불확실한 분류도
   * 일단 접수한다 — 보류는 워커가 받은 AI 응답으로 확정한다(개정 B.1).
   */
  public AnalysisView create(long userId, Submission submission, AiCallContext ctx) {
    long started = System.nanoTime();
    DiseaseResult disease = classify(submission, ctx);
    int classifyMs = (int) ((System.nanoTime() - started) / 1_000_000);

    String analysisId = Ulid.generate();
    // 워커가 커밋 직후 집어 갈 수 있으므로 시나리오를 먼저 기억한다
    scenarios.remember(analysisId, ctx.scenario());
    try {
      tx.executeWithoutResult(
          status -> {
            analyses.insertAnalysis(analysisId, userId, disease, classifyMs);
            analyses.insertSurvey(analysisId, submission.survey());
          });
    } catch (RuntimeException e) {
      scenarios.forget(analysisId);
      throw e;
    }
    // 분류 라벨·점수는 로그에 남기지 않는다 (TRD §7.6)
    log.atInfo()
        .addKeyValue("analysisId", analysisId)
        .addKeyValue("uncertain", disease.uncertain())
        .addKeyValue("classifyMs", classifyMs)
        .log("analysis_created");
    return findOne(userId, analysisId);
  }

  /** POST·재요청·GET이 같은 모양을 돌려준다 (TRD §10.2) */
  public AnalysisView findOne(long userId, String analysisId) {
    AnalysisRow analysis = owned(userId, analysisId);
    // 동시에 삭제됐으면 설문이 없다
    var survey = analyses.survey(analysisId).orElseThrow(() -> new ApiException(ErrorCode.E4003));
    var recommendation =
        analysis.status() == AnalysisStatus.COMPLETED
            ? analyses.recommendation(analysisId).orElse(null)
            : null;
    QueueInfo queueInfo =
        analysis.status() == AnalysisStatus.QUEUED ? queueInfo(analysisId) : QueueInfo.NONE;
    return AnalysisPresenter.view(analysis, survey, recommendation, queueInfo);
  }

  /**
   * TRD §10.3 — FAILED만 QUEUED로, 저장한 분류를 다시 쓴다. 0행이면 남의 것·없음 404, 상태가 다르면(WITHHELD 포함) 409 E4005
   */
  public AnalysisView retry(long userId, String analysisId, FixtureScenario scenario) {
    FixtureScenario previous = scenarios.get(analysisId);
    scenarios.remember(analysisId, scenario);
    if (!queue.requeueFailed(analysisId, userId)) {
      scenarios.remember(analysisId, previous);
      throw new ApiException(
          analyses.existsOwned(analysisId, userId) ? ErrorCode.E4005 : ErrorCode.E4003);
    }
    log.atInfo().addKeyValue("analysisId", analysisId).log("recommendation_retry_requested");
    return findOne(userId, analysisId);
  }

  /** TRD §10.4 — created_at DESC, id DESC */
  public AnalysisList list(long userId, int page, int size) {
    var items =
        analyses.list(userId, page, size).stream().map(AnalysisPresenter::listItem).toList();
    long total = analyses.count(userId);
    return new AnalysisList(items, new PageInfo(page, size, total, (total + size - 1) / size));
  }

  /** TRD §10.5 — 상태 무관 삭제. 진행 중이면 워커가 결과를 버린다 (§13.7) */
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

  private QueueInfo queueInfo(String analysisId) {
    long position = queue.queuePosition(analysisId);
    double avgMs = queue.averageRecommendMs();
    return new QueueInfo(position, Math.round(position * avgMs / 1000));
  }

  /** AI 분류 (TRD §12.5 분류 열): 연결 오류만 즉시 1회 재시도. 성공·실패와 관계없이 끝나면 이미지 버퍼 참조를 끊는다. */
  private DiseaseResult classify(Submission submission, AiCallContext ctx) {
    try {
      for (int attempt = 1; ; attempt++) {
        try {
          return ai.classify(new ClassifyInput(submission.image(), submission.mimeType()), ctx);
        } catch (AiError error) {
          if (attempt == 1 && AiErrorMapper.classifyRetryable(error)) continue;
          throw classifyException(error);
        }
      }
    } finally {
      submission.releaseImage();
    }
  }

  private ApiException classifyException(AiError error) {
    var failure = AiErrorMapper.classify(error);
    log.atLevel(failure.logLevel())
        .addKeyValue("code", failure.code().name())
        .addKeyValue("ai", error.toLog())
        .log("ai_classify_failed");
    if (error.kind() == AiError.Kind.CONNECTION)
      aiHealth.markUnavailable("classify_connection_error");
    return ApiException.withMessage(failure.code(), failure.message());
  }
}
