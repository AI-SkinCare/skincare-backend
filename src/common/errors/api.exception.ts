import { HttpException } from '@nestjs/common';
import { ERROR_DEFINITIONS, ErrorCode, RATE_LIMIT_REASONS, RateLimitReason } from './error-codes';

export interface ApiExceptionOptions {
  /** 기본 문구 대신 쓸 사용자 문구 (문구 규칙을 지킬 것) */
  message?: string;
  /** 개발용 상세. 화면에 표시하지 않는다. 이미지·설문 원문·토큰을 넣지 않는다. */
  detail?: unknown;
  retryAfterSec?: number;
}

/**
 * 서비스 코드가 던지는 유일한 예외. 전역 필터가 봉투로 바꾼다.
 * `throw new ApiException(ErrorCode.E4003)`
 */
export class ApiException extends HttpException {
  readonly code: ErrorCode;
  readonly userMessage: string;
  readonly detail: unknown;
  readonly retryAfterSec?: number;

  constructor(code: ErrorCode, options: ApiExceptionOptions = {}) {
    const definition = ERROR_DEFINITIONS[code];
    const message = options.message ?? definition.message;
    super(message, definition.status);
    this.code = code;
    this.userMessage = message;
    this.detail = options.detail ?? null;
    this.retryAfterSec = options.retryAfterSec ?? definition.retryAfterSec;
  }

  static rateLimited(reason: RateLimitReason, retryAfterSec: number): ApiException {
    return new ApiException(ErrorCode.E4004, {
      message: RATE_LIMIT_REASONS[reason],
      detail: { reason },
      retryAfterSec,
    });
  }
}
