import { ArgumentsHost, Catch, ExceptionFilter, HttpException, Logger } from '@nestjs/common';
import type { Response } from 'express';
import type { AppRequest } from '../http/app-request';
import { ApiException } from './api.exception';
import { ERROR_DEFINITIONS, ErrorCode } from './error-codes';

export interface ErrorBody {
  success: false;
  error: { code: ErrorCode; message: string; detail: unknown };
}

/**
 * 모든 예외를 실패 봉투로 바꾸는 유일한 필터 (TRD §7.2).
 * Nest·라이브러리 예외가 원래 모양 그대로 나가지 않게 HTTP 상태로 코드를 정한다.
 */
@Catch()
export class ApiExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger(ApiExceptionFilter.name);

  catch(exception: unknown, host: ArgumentsHost): void {
    const ctx = host.switchToHttp();
    const res = ctx.getResponse<Response>();
    const req = ctx.getRequest<AppRequest>();

    const apiException = this.toApiException(exception);
    if (apiException.code === ErrorCode.E4001 && !(exception instanceof ApiException)) {
      // 미처리 예외: 스택·requestId만. 요청 본문은 남기지 않는다.
      this.logger.error({
        msg: 'unhandled_exception',
        requestId: req.id,
        route: req.route?.path,
        err: exception instanceof Error ? exception : String(exception),
      });
    }

    if (res.headersSent) return;

    const status = ERROR_DEFINITIONS[apiException.code].status;
    if (apiException.retryAfterSec !== undefined) {
      res.setHeader('Retry-After', String(apiException.retryAfterSec));
    }
    const body: ErrorBody = {
      success: false,
      error: {
        code: apiException.code,
        message: apiException.userMessage,
        // E4001 detail은 항상 null — 내부 정보를 흘리지 않는다.
        detail: apiException.code === ErrorCode.E4001 ? null : (apiException.detail ?? null),
      },
    };
    res.status(status).json(body);
  }

  private toApiException(exception: unknown): ApiException {
    if (exception instanceof ApiException) return exception;
    const status = this.statusOf(exception);
    if (status === undefined) return new ApiException(ErrorCode.E4001);
    return new ApiException(this.codeForStatus(status));
  }

  private statusOf(exception: unknown): number | undefined {
    if (exception instanceof HttpException) return exception.getStatus();
    // body-parser 등 http-errors 계열 (status/statusCode + expose)
    if (typeof exception === 'object' && exception !== null) {
      const candidate = exception as { status?: unknown; statusCode?: unknown; expose?: unknown };
      const status = candidate.status ?? candidate.statusCode;
      if (typeof status === 'number' && candidate.expose === true) return status;
    }
    return undefined;
  }

  private codeForStatus(status: number): ErrorCode {
    switch (status) {
      case 401:
        return ErrorCode.E1001;
      case 403:
        return ErrorCode.E1002;
      case 404:
      case 405:
        return ErrorCode.E4003;
      case 413:
        return ErrorCode.E2003;
      case 415:
        return ErrorCode.E2004;
      case 429:
        return ErrorCode.E4004;
      default:
        return status >= 400 && status < 500 ? ErrorCode.E4002 : ErrorCode.E4001;
    }
  }
}
