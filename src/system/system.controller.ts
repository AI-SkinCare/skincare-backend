import { Controller, Get } from '@nestjs/common';
import { ApiOperation, ApiTags } from '@nestjs/swagger';
import { ErrorCode } from '../common/errors/error-codes';
import { ApiEnvelopeResponse, ApiErrorResponses } from '../common/swagger/api-envelope.decorator';
import { toIsoUtcOrNull } from '../common/utils/time';
import { RecommendationQueueRepository } from '../recommendation/recommendation-queue.repository';
import { AiHealthService } from './ai-health.service';
import { SystemStatusDto } from './dto/system-status.dto';

@ApiTags('system')
@Controller('system')
export class SystemController {
  constructor(
    private readonly aiHealth: AiHealthService,
    private readonly queue: RecommendationQueueRepository,
  ) {}

  /** 인증 없음. DB를 조회하므로 Docker 헬스체크에도 쓴다 (DB 장애면 500 E4001) */
  @Get('status')
  @ApiOperation({ summary: 'AI 가용성·대기열 상태 (FR-SYS-01)' })
  @ApiEnvelopeResponse({ status: 200, type: SystemStatusDto })
  @ApiErrorResponses(ErrorCode.E4001)
  async status(): Promise<SystemStatusDto> {
    const [queueLength, avgMs] = await Promise.all([
      this.queue.queuedCount(),
      this.queue.averageRecommendMs(),
    ]);
    const { aiAvailable, checkedAt } = this.aiHealth.snapshot();
    return {
      aiAvailable,
      queueLength,
      estimatedWaitSec: Math.round((queueLength * avgMs) / 1000),
      checkedAt: toIsoUtcOrNull(checkedAt),
    };
  }
}
