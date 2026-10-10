import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { plainToInstance } from 'class-transformer';
import {
  ArrayMaxSize,
  ArrayUnique,
  IsArray,
  IsDefined,
  IsIn,
  IsInt,
  IsOptional,
  IsString,
  Length,
  Max,
  MaxLength,
  Min,
  validateSync,
} from 'class-validator';
import {
  AGE_BANDS,
  AgeBand,
  CONCERNS,
  ConcernCode,
  GENDERS,
  Gender,
  LEVELS,
  Level,
  SKIN_TYPES,
  SkinTypeCode,
} from '../common/enums';
import { FieldError, flattenValidationErrors } from '../common/validation/validation-errors';

/** 저장·응답용 정규화 설문 — 모든 키 포함, 미입력은 null, 배열은 [] (TRD §10.1) */
export interface NormalizedSurvey {
  gender: Gender;
  ageBand: AgeBand;
  skinType: SkinTypeCode | null;
  sleepHours: number | null;
  waterIntakeLevel: Level | null;
  sunExposureLevel: Level | null;
  stressLevel: Level | null;
  concerns: ConcernCode[];
  currentProducts: string[];
  note: string | null;
}

/** multipart의 `survey` JSON 문자열 구조. Swagger 문서에도 쓴다. */
export class SurveyDto {
  @ApiProperty({ enum: GENDERS })
  @IsDefined()
  @IsIn(GENDERS)
  gender!: Gender;

  @ApiProperty({ enum: AGE_BANDS })
  @IsDefined()
  @IsIn(AGE_BANDS)
  ageBand!: AgeBand;

  @ApiPropertyOptional({ enum: SKIN_TYPES, nullable: true, description: '모르면 생략 또는 null' })
  @IsOptional()
  @IsIn(SKIN_TYPES)
  skinType?: SkinTypeCode | null;

  @ApiPropertyOptional({ type: 'integer', minimum: 0, maximum: 14, nullable: true })
  @IsOptional()
  @IsInt()
  @Min(0)
  @Max(14)
  sleepHours?: number | null;

  @ApiPropertyOptional({ enum: LEVELS, nullable: true })
  @IsOptional()
  @IsIn(LEVELS)
  waterIntakeLevel?: Level | null;

  @ApiPropertyOptional({ enum: LEVELS, nullable: true })
  @IsOptional()
  @IsIn(LEVELS)
  sunExposureLevel?: Level | null;

  @ApiPropertyOptional({ enum: LEVELS, nullable: true })
  @IsOptional()
  @IsIn(LEVELS)
  stressLevel?: Level | null;

  @ApiPropertyOptional({ enum: CONCERNS, isArray: true, maxItems: 3, uniqueItems: true })
  @IsOptional()
  @IsArray()
  @ArrayMaxSize(3)
  @ArrayUnique()
  @IsIn(CONCERNS, { each: true })
  concerns?: ConcernCode[] | null;

  @ApiPropertyOptional({ type: [String], maxItems: 5, description: '각 1–30자' })
  @IsOptional()
  @IsArray()
  @ArrayMaxSize(5)
  @IsString({ each: true })
  @Length(1, 30, { each: true })
  currentProducts?: string[] | null;

  @ApiPropertyOptional({ type: String, maxLength: 300, nullable: true })
  @IsOptional()
  @IsString()
  @MaxLength(300)
  note?: string | null;
}

export type SurveyParseResult =
  { ok: true; survey: NormalizedSurvey } | { ok: false; errors: FieldError[] };

/**
 * multipart 필드 `survey`(JSON 문자열)를 파싱·검증·정규화한다.
 * 오류 필드는 `survey.gender`처럼 접두를 붙인다. 원문은 어디에도 남기지 않는다.
 */
export function parseSurvey(raw: unknown): SurveyParseResult {
  if (raw === undefined || raw === null || raw === '') {
    return { ok: false, errors: [{ field: 'survey', reason: 'required' }] };
  }
  if (typeof raw !== 'string') {
    return { ok: false, errors: [{ field: 'survey', reason: 'invalid_type' }] };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, errors: [{ field: 'survey', reason: 'invalid_format' }] };
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { ok: false, errors: [{ field: 'survey', reason: 'invalid_format' }] };
  }

  const dto = plainToInstance(SurveyDto, parsed);
  const errors = validateSync(dto, { whitelist: true, forbidUnknownValues: false });
  if (errors.length > 0) {
    return { ok: false, errors: flattenValidationErrors(errors, 'survey') };
  }
  return { ok: true, survey: normalizeSurvey(dto) };
}

export function normalizeSurvey(dto: SurveyDto): NormalizedSurvey {
  return {
    gender: dto.gender,
    ageBand: dto.ageBand,
    skinType: dto.skinType ?? null,
    sleepHours: dto.sleepHours ?? null,
    waterIntakeLevel: dto.waterIntakeLevel ?? null,
    sunExposureLevel: dto.sunExposureLevel ?? null,
    stressLevel: dto.stressLevel ?? null,
    concerns: dto.concerns ?? [],
    currentProducts: dto.currentProducts ?? [],
    note: dto.note ?? null,
  };
}
