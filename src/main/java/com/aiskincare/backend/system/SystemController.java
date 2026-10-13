package com.aiskincare.backend.system;

import com.aiskincare.backend.common.error.ErrorCode;
import com.aiskincare.backend.common.util.TimeUtil;
import com.aiskincare.backend.common.web.ApiEnvelope;
import com.aiskincare.backend.common.web.ApiErrorCodes;
import com.aiskincare.backend.common.web.PublicApi;
import com.aiskincare.backend.recommendation.RecommendationQueueRepository;
import com.aiskincare.backend.system.dto.SystemStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "system")
@RestController
@RequestMapping("/system")
public class SystemController {
  private final AiHealthService aiHealth;
  private final RecommendationQueueRepository queue;

  public SystemController(AiHealthService aiHealth, RecommendationQueueRepository queue) {
    this.aiHealth = aiHealth;
    this.queue = queue;
  }

  /** 인증 없음. DB를 조회하므로 Docker 헬스체크에도 쓴다 (DB 장애면 500 E4001) */
  @Operation(summary = "AI 가용성·대기열 상태 (FR-SYS-01)")
  @PublicApi
  @ApiErrorCodes(ErrorCode.E4001)
  @GetMapping("/status")
  public ApiEnvelope<SystemStatusResponse> status() {
    long queueLength = queue.queuedCount();
    double avgMs = queue.averageRecommendMs();
    var snapshot = aiHealth.snapshot();
    return ApiEnvelope.ok(
        new SystemStatusResponse(
            snapshot.aiAvailable(),
            queueLength,
            Math.round(queueLength * avgMs / 1000),
            TimeUtil.toIsoUtc(snapshot.checkedAt())));
  }
}
