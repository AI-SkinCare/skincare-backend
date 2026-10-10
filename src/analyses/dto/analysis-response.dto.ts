// TRD §10 응답 모양. Swagger(/api/docs-json)가 실제 계약이고 앱이 이것으로 타입을 만든다.
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import {
  AGE_BANDS,
  ANALYSIS_STATUSES,
  CONCERNS,
  DEVICE_TYPES,
  GENDERS,
  GRADES,
  LEVELS,
  METRICS,
  RECOMMENDATION_STATUSES,
  ROIS,
  ROUTINE_TIMES,
  SKIN_TYPES,
} from '../../common/enums';
import type {
  AgeBand,
  AnalysisStatus,
  ConcernCode,
  DeviceType,
  Gender,
  Grade,
  Level,
  Metric,
  RecommendationStatus,
  Roi,
  RoutineTime,
  SkinTypeCode,
} from '../../common/enums';
import { ANALYSIS_FAILURE_CODES, AnalysisFailureCode } from '../../common/errors/error-codes';

const ULID_EXAMPLE = '01J9Z3K5V8Q2M4N6P8R0S2T4V6';

export class AnalysisCreatedDto {
  @ApiProperty({ example: ULID_EXAMPLE })
  analysisId!: string;

  @ApiProperty({ enum: ['queued'] })
  status!: 'queued';

  @ApiProperty({ example: 2, description: '내 앞 queued 건수 + 1' })
  queuePosition!: number;

  @ApiProperty({ example: 110, description: 'round(queuePosition × 최근 평균 추천ms / 1000)' })
  estimatedWaitSec!: number;
}

export class SummaryItemDto {
  @ApiProperty({ enum: METRICS })
  metric!: Metric;

  @ApiProperty({ example: 61, description: '원래 지표 0–100 (pigmentation·pore는 높을수록 나쁨)' })
  value!: number;

  @ApiProperty({ example: 61, description: '좋은 방향으로 통일한 점수' })
  score!: number;

  @ApiProperty({ enum: GRADES })
  grade!: Grade;
}

export class RoiScoreDto {
  @ApiProperty({ enum: ROIS.filter((roi) => roi !== 'full_face') })
  roi!: Roi;

  @ApiProperty({ example: 58 })
  moisture!: number;

  @ApiProperty({ example: 71 })
  elasticity!: number;

  @ApiProperty({ example: 35 })
  pigmentation!: number;

  @ApiProperty({ example: 52 })
  pore!: number;

  @ApiProperty({ example: 61 })
  overallScore!: number;

  @ApiProperty({ enum: GRADES })
  grade!: Grade;
}

export class MeasurementDto {
  @ApiProperty({ description: '데모(stub) 값이면 true' })
  isDemo!: boolean;

  @ApiProperty({ example: 0.87 })
  confidence!: number;

  @ApiProperty()
  calibrationApplied!: boolean;

  @ApiProperty({ example: 63 })
  overallScore!: number;

  @ApiProperty({
    type: [SummaryItemDto],
    description: 'moisture → elasticity → pigmentation → pore',
  })
  summary!: SummaryItemDto[];

  @ApiProperty({
    type: [RoiScoreDto],
    description: 'full_face 제외, forehead → … → chin. 비어 있을 수 있음',
  })
  byRoi!: RoiScoreDto[];

  @ApiProperty({ example: 'skin_reg_v1' })
  modelVersion!: string;
}

export class SkinTypeLabelDto {
  @ApiProperty({ enum: SKIN_TYPES })
  code!: SkinTypeCode;

  @ApiProperty({ example: '복합성', description: 'AI 라벨. 화면 표기는 앱이 code로 매핑' })
  label!: string;
}

export class ConcernLabelDto {
  @ApiProperty({ enum: CONCERNS })
  code!: ConcernCode;

  @ApiProperty({ example: '모공', description: 'AI 라벨. 화면 표기는 앱이 code로 매핑' })
  label!: string;
}

export class IngredientDto {
  @ApiProperty({ example: '나이아신아마이드' })
  name!: string;

  @ApiProperty({ type: String, nullable: true })
  purpose!: string | null;

  @ApiProperty({ example: 1 })
  priority!: number;
}

export class RoutineStepDto {
  @ApiProperty({ example: 1 })
  step!: number;

  @ApiProperty({ enum: ROUTINE_TIMES })
  time!: RoutineTime;

  @ApiProperty({ example: '약산성 클렌저로 가볍게 세안합니다.' })
  action!: string;
}

/** status가 done일 때만 나머지 필드가 있다 */
export class RecommendationDto {
  @ApiProperty({ enum: RECOMMENDATION_STATUSES })
  status!: RecommendationStatus;

  @ApiPropertyOptional({ type: SkinTypeLabelDto })
  skinType?: SkinTypeLabelDto;

  @ApiPropertyOptional({ type: [ConcernLabelDto] })
  concerns?: ConcernLabelDto[];

  @ApiPropertyOptional({ type: [IngredientDto], description: '0–5개' })
  ingredients?: IngredientDto[];

  @ApiPropertyOptional({ type: [RoutineStepDto] })
  routine?: RoutineStepDto[];

  @ApiPropertyOptional({
    type: [String],
    description: '문제 분석·원인 / 성분 선택·근거 / 사용법·관리 방안',
  })
  reasoning?: string[];

  @ApiPropertyOptional()
  answer?: string;

  @ApiPropertyOptional({ type: [String] })
  inputNotes?: string[];

  @ApiPropertyOptional()
  disclaimer?: string;

  @ApiPropertyOptional({ description: '화면 비표시(분석용)' })
  filtered?: boolean;

  @ApiPropertyOptional({ description: '화면 비표시(분석용)' })
  usedFallback?: boolean;

  @ApiPropertyOptional({ example: 'rec_sllm_v2' })
  modelVersion?: string;
}

/** 저장한 정규화 설문(클라이언트 코드). 앱이 다음 측정의 기본값으로 쓴다 */
export class SurveyResponseDto {
  @ApiProperty({ enum: GENDERS })
  gender!: Gender;

  @ApiProperty({ enum: AGE_BANDS })
  ageBand!: AgeBand;

  @ApiProperty({ enum: SKIN_TYPES, nullable: true })
  skinType!: SkinTypeCode | null;

  @ApiProperty({ type: 'integer', nullable: true })
  sleepHours!: number | null;

  @ApiProperty({ enum: LEVELS, nullable: true })
  waterIntakeLevel!: Level | null;

  @ApiProperty({ enum: LEVELS, nullable: true })
  sunExposureLevel!: Level | null;

  @ApiProperty({ enum: LEVELS, nullable: true })
  stressLevel!: Level | null;

  @ApiProperty({ enum: CONCERNS, isArray: true })
  concerns!: ConcernCode[];

  @ApiProperty({ type: [String] })
  currentProducts!: string[];

  @ApiProperty({ type: String, nullable: true })
  note!: string | null;
}

export class AnalysisErrorDto {
  @ApiProperty({ enum: ANALYSIS_FAILURE_CODES })
  code!: AnalysisFailureCode;

  @ApiProperty({ example: '추천을 만들지 못했어요. 다시 시도해 주세요.' })
  message!: string;
}

export class AnalysisDetailDto {
  @ApiProperty({ example: ULID_EXAMPLE })
  analysisId!: string;

  @ApiProperty({ enum: ANALYSIS_STATUSES })
  status!: AnalysisStatus;

  @ApiProperty({ example: '2026-10-15T09:12:33Z' })
  createdAt!: string;

  @ApiProperty({ type: String, nullable: true, example: '2026-10-15T09:13:31Z' })
  completedAt!: string | null;

  @ApiProperty({ enum: DEVICE_TYPES })
  deviceType!: DeviceType;

  @ApiProperty({ type: 'integer', nullable: true, description: 'queued일 때만' })
  queuePosition!: number | null;

  @ApiProperty({ type: 'integer', nullable: true, description: 'queued일 때만' })
  estimatedWaitSec!: number | null;

  @ApiProperty({ type: MeasurementDto, description: '추천 상태와 무관하게 항상 포함' })
  measurement!: MeasurementDto;

  @ApiProperty({ type: RecommendationDto })
  recommendation!: RecommendationDto;

  @ApiProperty({ type: SurveyResponseDto })
  survey!: SurveyResponseDto;

  @ApiProperty({ type: AnalysisErrorDto, nullable: true, description: 'failed일 때만' })
  error!: AnalysisErrorDto | null;
}

export class AnalysisListItemDto {
  @ApiProperty({ example: ULID_EXAMPLE })
  analysisId!: string;

  @ApiProperty({ example: '2026-10-15T09:12:33Z' })
  createdAt!: string;

  @ApiProperty({ enum: ANALYSIS_STATUSES })
  status!: AnalysisStatus;

  @ApiProperty({ example: 63 })
  overallScore!: number;

  @ApiProperty()
  isDemo!: boolean;

  @ApiProperty({ enum: CONCERNS, isArray: true, description: '추천 완료 시 고민 코드, 아니면 []' })
  concerns!: ConcernCode[];
}

export class PageInfoDto {
  @ApiProperty({ example: 1 })
  page!: number;

  @ApiProperty({ example: 20 })
  size!: number;

  @ApiProperty({ example: 3 })
  totalItems!: number;

  @ApiProperty({ example: 1, description: 'ceil(totalItems / size), 0건이면 0' })
  totalPages!: number;
}

export class AnalysisListDto {
  @ApiProperty({ type: [AnalysisListItemDto] })
  items!: AnalysisListItemDto[];

  @ApiProperty({ type: PageInfoDto })
  page!: PageInfoDto;
}

export class TrendSummaryDto {
  @ApiProperty({ example: 61 })
  moisture!: number;

  @ApiProperty({ example: 74 })
  elasticity!: number;

  @ApiProperty({ example: 38 })
  pigmentation!: number;

  @ApiProperty({ example: 45 })
  pore!: number;
}

export class TrendPointDto {
  @ApiProperty({ example: ULID_EXAMPLE })
  analysisId!: string;

  @ApiProperty({ example: '2026-10-15', description: 'created_at의 KST 날짜' })
  date!: string;

  @ApiProperty({ example: 63 })
  overallScore!: number;

  @ApiProperty({ type: TrendSummaryDto, description: 'value(원래 값)' })
  summary!: TrendSummaryDto;
}

export class TrendDeltaDto {
  @ApiProperty({ example: 3 })
  overallScore!: number;

  @ApiProperty({ example: 7 })
  moisture!: number;

  @ApiProperty({ example: 2 })
  elasticity!: number;

  @ApiProperty({ example: -2, description: '음수가 좋아진 것' })
  pigmentation!: number;

  @ApiProperty({ example: -2, description: '음수가 좋아진 것' })
  pore!: number;
}

export class AnalysisTrendDto {
  @ApiProperty({ type: TrendPointDto })
  current!: TrendPointDto;

  @ApiProperty({ type: TrendPointDto, nullable: true })
  previous!: TrendPointDto | null;

  @ApiProperty({ type: TrendDeltaDto, nullable: true })
  delta!: TrendDeltaDto | null;
}
