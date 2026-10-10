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
  @ApiProperty({ type: 'boolean', example: true, description: '성공이면 항상 true' })
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
  @ApiProperty({ type: 'boolean', example: false, description: '실패면 항상 false' })
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

type SchemaNode = Record<string, unknown>;

/**
 * Nest Swagger는 `type: X, nullable: true`를 `{ nullable: true, allOf: [$ref] }`로 내는데,
 * OpenAPI 3.0은 nullable에 type이 필요하다. 앱 타입 생성기(swift-openapi-generator 등)가
 * 거부하지 않게 문서 전체에서 `type: 'object'`를 채운다.
 */
export function fillNullableRefTypes(node: unknown): void {
  if (Array.isArray(node)) {
    node.forEach(fillNullableRefTypes);
    return;
  }
  if (typeof node !== 'object' || node === null) return;
  const schema = node as SchemaNode;
  if (schema.nullable === true && schema.type === undefined && Array.isArray(schema.allOf)) {
    schema.type = 'object';
  }
  Object.values(schema).forEach(fillNullableRefTypes);
}

/** 보안 요구가 없는 작업(가입·로그인·상태)에 `security: []`를 명시해 "인증 없음"을 드러낸다 */
export function markPublicOperations(paths: Record<string, Record<string, unknown>>): void {
  for (const item of Object.values(paths)) {
    for (const operation of Object.values(item)) {
      if (typeof operation === 'object' && operation !== null && !('security' in operation)) {
        (operation as SchemaNode).security = [];
      }
    }
  }
}
