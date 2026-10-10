import { Type, applyDecorators } from '@nestjs/common';
import {
  ApiExtraModels,
  ApiProperty,
  ApiPropertyOptional,
  ApiResponse,
  getSchemaPath,
} from '@nestjs/swagger';
import { ERROR_DEFINITIONS, ErrorCode } from '../errors/error-codes';

export class SuccessEnvelopeDto {
  @ApiProperty({ enum: [true] })
  success!: true;
}

export class ErrorPayloadDto {
  @ApiProperty({ enum: Object.values(ErrorCode), example: ErrorCode.E4002 })
  code!: ErrorCode;

  @ApiProperty({
    description: '사용자에게 그대로 보여줘도 되는 문장',
    example: '입력값을 확인해 주세요.',
  })
  message!: string;

  @ApiPropertyOptional({
    type: 'object',
    nullable: true,
    additionalProperties: true,
    description:
      '개발용 상세 (화면 비표시). E4002: { fields: [{ field, reason }] }, E4004: { reason }',
  })
  detail!: Record<string, unknown> | null;
}

export class ErrorEnvelopeDto {
  @ApiProperty({ enum: [false] })
  success!: false;

  @ApiProperty({ type: ErrorPayloadDto })
  error!: ErrorPayloadDto;
}

/** 성공 봉투 `{ success: true, data: <type> }` — allOf + getSchemaPath (TRD §7.2) */
export function ApiEnvelopeResponse(options: {
  status: number;
  type: Type<unknown>;
  description?: string;
}) {
  return applyDecorators(
    ApiExtraModels(SuccessEnvelopeDto, options.type),
    ApiResponse({
      status: options.status,
      description: options.description,
      schema: {
        allOf: [
          { $ref: getSchemaPath(SuccessEnvelopeDto) },
          {
            type: 'object',
            required: ['data'],
            properties: { data: { $ref: getSchemaPath(options.type) } },
          },
        ],
      },
    }),
  );
}

/** 실패 봉투. 같은 HTTP 상태의 코드들을 한 응답으로 묶어 설명한다. */
export function ApiErrorResponses(...codes: ErrorCode[]) {
  const byStatus = new Map<number, ErrorCode[]>();
  for (const code of codes) {
    const status = ERROR_DEFINITIONS[code].status;
    byStatus.set(status, [...(byStatus.get(status) ?? []), code]);
  }
  return applyDecorators(
    ApiExtraModels(ErrorEnvelopeDto),
    ...[...byStatus.entries()].map(([status, group]) =>
      ApiResponse({
        status,
        description: group.map((code) => `${code}: ${ERROR_DEFINITIONS[code].message}`).join(' / '),
        schema: { $ref: getSchemaPath(ErrorEnvelopeDto) },
      }),
    ),
  );
}
