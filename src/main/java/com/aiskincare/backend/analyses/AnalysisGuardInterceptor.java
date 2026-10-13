package com.aiskincare.backend.analyses;

import com.aiskincare.backend.common.error.ApiException;
import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.error.RateLimitReason;
import com.aiskincare.backend.common.util.TimeUtil;
import com.aiskincare.backend.common.web.AuthUser;
import com.aiskincare.backend.config.AppProperties;
import com.aiskincare.backend.config.Constants;
import com.aiskincare.backend.recommendation.RecommendationQueueRepository;
import com.aiskincare.backend.system.AiHealthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * TRD §10.1 처리 순서 2–4. 인증 인터셉터 다음, 파일 수신(multipart는 lazy) 전에 돈다 — 제한에 걸리면 파일을 읽지 않는다.
 *
 * <ol>
 *   <li>진행 중 분석(queued·recommending) 또는 처리 중 제출 → 429 E4004 in_progress
 *   <li>오늘(KST) 생성한 분석 수 ≥ ANALYSIS_DAILY_LIMIT → 429 E4004 daily_limit
 *   <li>queued 건수 ≥ REC_QUEUE_LIMIT → 503 E3003 (검사와 INSERT 사이 경합으로 1–2건 넘을 수 있음, 시연 규모에서 허용)
 *   <li>aiAvailable=false → 503 E3001
 * </ol>
 *
 * 통과하면 처리 중 제출 잠금을 걸고 요청이 끝나면 푼다.
 */
@Component
public class AnalysisGuardInterceptor implements HandlerInterceptor {
  private static final String LOCK_ATTRIBUTE = AnalysisGuardInterceptor.class.getName() + ".lock";

  private final AnalysisRepository analyses;
  private final RecommendationQueueRepository queue;
  private final AiHealthService aiHealth;
  private final SubmissionLock lock;
  private final AppProperties props;

  public AnalysisGuardInterceptor(
      AnalysisRepository analyses,
      RecommendationQueueRepository queue,
      AiHealthService aiHealth,
      SubmissionLock lock,
      AppProperties props) {
    this.analyses = analyses;
    this.queue = queue;
    this.aiHealth = aiHealth;
    this.lock = lock;
    this.props = props;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) return true;
    AnalysisGuards guards = method.getMethodAnnotation(AnalysisGuards.class);
    if (guards == null) return true;
    if (!(request.getAttribute(AuthUser.REQUEST_ATTRIBUTE) instanceof AuthUser user)) {
      throw new ApiException(ErrorCode.E1001);
    }

    if (analyses.hasInProgress(user.id()) || !lock.tryAcquire(user.id())) {
      throw ApiException.rateLimited(
          RateLimitReason.IN_PROGRESS, Constants.IN_PROGRESS_RETRY_AFTER_SEC);
    }
    // preHandle이 예외로 끝나면 afterCompletion이 불리지 않으므로, 거절할 때는 여기서 바로 푼다
    try {
      Instant now = Instant.now();
      if (analyses.countCreatedSince(user.id(), TimeUtil.startOfKstDay(now))
          >= props.analysisDailyLimit()) {
        throw ApiException.rateLimited(
            RateLimitReason.DAILY_LIMIT, TimeUtil.secondsUntilNextKstMidnight(now));
      }
      if (queue.queuedCount() >= props.queue().limit()) throw new ApiException(ErrorCode.E3003);
      if (guards.checkAiAvailability() && !aiHealth.isAvailable()) {
        throw new ApiException(ErrorCode.E3001);
      }
    } catch (RuntimeException e) {
      lock.release(user.id());
      throw e;
    }
    request.setAttribute(LOCK_ATTRIBUTE, user.id());
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    if (request.getAttribute(LOCK_ATTRIBUTE) instanceof Long userId) lock.release(userId);
  }
}
