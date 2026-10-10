import {
  Controller,
  Delete,
  Get,
  Headers,
  HttpCode,
  Param,
  Post,
  Query,
  Req,
  UseGuards,
  UseInterceptors,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import {
  ApiBearerAuth,
  ApiBody,
  ApiConsumes,
  ApiExtraModels,
  ApiHeader,
  ApiNoContentResponse,
  ApiOperation,
  ApiTags,
  getSchemaPath,
} from '@nestjs/swagger';
import { memoryStorage } from 'multer';
import { FixtureScenarioStore } from '../ai-client/fixture-scenario.store';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { DEVICE_TYPES, FIXTURE_SCENARIOS } from '../common/enums';
import { ErrorCode } from '../common/errors/error-codes';
import type { AppRequest, AuthUser } from '../common/http/app-request';
import { ApiEnvelopeResponse, ApiErrorResponses } from '../common/swagger/api-envelope.decorator';
import { ParseUlidPipe } from '../common/validation/parse-ulid.pipe';
import { MAX_IMAGE_BYTES } from '../config/constants';
import { AnalysesService } from './analyses.service';
import {
  AnalysisCreatedDto,
  AnalysisDetailDto,
  AnalysisListDto,
  AnalysisTrendDto,
} from './dto/analysis-response.dto';
import { ListAnalysesQuery } from './dto/list-analyses.query';
import { AiAvailabilityGuard } from './guards/ai-availability.guard';
import { AnalysisLimitGuard } from './guards/analysis-limit.guard';
import { QueueCapacityGuard } from './guards/queue-capacity.guard';
import {
  AnalysisSubmission,
  AnalysisSubmissionPipe,
  SubmissionParts,
} from './pipes/analysis-submission.pipe';
import { SurveyDto } from './survey';

const FIXTURE_HEADER = 'x-fixture-scenario';
const ApiFixtureHeader = () =>
  ApiHeader({
    name: 'X-Fixture-Scenario',
    required: false,
    enum: FIXTURE_SCENARIOS,
    description: '개발·테스트용 (production에선 무시)',
  });

@ApiTags('analyses')
@ApiBearerAuth()
@Controller('analyses')
@UseGuards(JwtAuthGuard)
@ApiErrorResponses(ErrorCode.E1001)
export class AnalysesController {
  constructor(
    private readonly analyses: AnalysesService,
    private readonly scenarios: FixtureScenarioStore,
  ) {}

  /**
   * TRD §10.1 처리 순서: 인증 → 요청 제한(E4004) → 대기열 상한(E3003) → AI 가용성(E3001)
   * → 파일 수신(memoryStorage, 413 E2003) → 형식 검증 → AI 측정 → 저장 → 202.
   * Guard가 Interceptor보다 먼저 돌기 때문에 제한에 걸리면 파일을 버퍼링하지 않는다.
   */
  @Post()
  @HttpCode(202)
  @UseGuards(AnalysisLimitGuard, QueueCapacityGuard, AiAvailabilityGuard)
  @UseInterceptors(
    // 얼굴 이미지는 디스크에 닿지 않는다 — memoryStorage만 (dest·diskStorage 금지)
    FileInterceptor('image', {
      storage: memoryStorage(),
      limits: { fileSize: MAX_IMAGE_BYTES, files: 1 },
    }),
  )
  @ApiOperation({ summary: '분석 요청 — 측정 + 추천 대기열 등록 (FR-ANA-01·02·05)' })
  @ApiConsumes('multipart/form-data')
  @ApiExtraModels(SurveyDto)
  @ApiBody({
    schema: {
      type: 'object',
      required: ['image', 'survey', 'deviceType'],
      properties: {
        image: {
          type: 'string',
          format: 'binary',
          description: 'JPEG/PNG, 10MB 이하, 짧은 변 720px 이상',
        },
        survey: {
          type: 'string',
          description: `JSON 문자열. 구조: ${getSchemaPath(SurveyDto)}`,
          example: JSON.stringify({ gender: 'female', ageBand: '20s', concerns: ['pore'] }),
        },
        deviceType: { type: 'string', enum: [...DEVICE_TYPES] },
      },
    },
  })
  @ApiFixtureHeader()
  @ApiEnvelopeResponse({ status: 202, type: AnalysisCreatedDto })
  @ApiErrorResponses(
    ErrorCode.E4004,
    ErrorCode.E3003,
    ErrorCode.E3001,
    ErrorCode.E2003,
    ErrorCode.E2004,
    ErrorCode.E2001,
    ErrorCode.E2002,
    ErrorCode.E4002,
    ErrorCode.E3002,
    ErrorCode.E4001,
  )
  async create(
    @CurrentUser() user: AuthUser,
    @SubmissionParts(AnalysisSubmissionPipe) submission: AnalysisSubmission,
    @Req() req: AppRequest,
    @Headers(FIXTURE_HEADER) scenarioHeader?: string,
  ): Promise<AnalysisCreatedDto> {
    try {
      return await this.analyses.create(user.id, submission, {
        requestId: req.id,
        scenario: this.scenarios.fromHeader(scenarioHeader),
      });
    } finally {
      // 이미지 버퍼 참조 해제 (TRD §10.1 처리 순서 8)
      req.file = undefined;
    }
  }

  @Get()
  @ApiOperation({ summary: '이력 목록 — 최신순 (FR-HIS-01)' })
  @ApiEnvelopeResponse({ status: 200, type: AnalysisListDto })
  @ApiErrorResponses(ErrorCode.E4002)
  list(@CurrentUser() user: AuthUser, @Query() query: ListAnalysesQuery): Promise<AnalysisListDto> {
    return this.analyses.list(user.id, query.page, query.size);
  }

  @Get(':id')
  @ApiOperation({ summary: '분석 결과·진행 상태 (FR-ANA-03, FR-REP-01·02)' })
  @ApiEnvelopeResponse({ status: 200, type: AnalysisDetailDto })
  @ApiErrorResponses(ErrorCode.E4003)
  findOne(
    @CurrentUser() user: AuthUser,
    @Param('id', ParseUlidPipe) id: string,
  ): Promise<AnalysisDetailDto> {
    return this.analyses.findOne(user.id, id);
  }

  @Post(':id/recommendation/retry')
  @HttpCode(202)
  @UseGuards(AnalysisLimitGuard, QueueCapacityGuard)
  @ApiOperation({ summary: '실패한 추천 재요청 (FR-ANA-04)' })
  @ApiFixtureHeader()
  @ApiEnvelopeResponse({ status: 202, type: AnalysisCreatedDto })
  @ApiErrorResponses(ErrorCode.E4004, ErrorCode.E3003, ErrorCode.E4003, ErrorCode.E4005)
  retry(
    @CurrentUser() user: AuthUser,
    @Param('id', ParseUlidPipe) id: string,
    @Headers(FIXTURE_HEADER) scenarioHeader?: string,
  ): Promise<AnalysisCreatedDto> {
    return this.analyses.retry(user.id, id, this.scenarios.fromHeader(scenarioHeader));
  }

  @Get(':id/trend')
  @ApiOperation({ summary: '직전 분석 대비 변화 (FR-HIS-02)' })
  @ApiEnvelopeResponse({ status: 200, type: AnalysisTrendDto })
  @ApiErrorResponses(ErrorCode.E4003)
  trend(
    @CurrentUser() user: AuthUser,
    @Param('id', ParseUlidPipe) id: string,
  ): Promise<AnalysisTrendDto> {
    return this.analyses.trend(user.id, id);
  }

  @Delete(':id')
  @HttpCode(204)
  @ApiOperation({ summary: '이력 삭제 (FR-HIS-03)' })
  @ApiNoContentResponse()
  @ApiErrorResponses(ErrorCode.E4003)
  remove(@CurrentUser() user: AuthUser, @Param('id', ParseUlidPipe) id: string): Promise<void> {
    return this.analyses.remove(user.id, id);
  }
}
