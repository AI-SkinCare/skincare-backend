// TRD §12.5 — AI 결과 → 백엔드 코드
//
// | AI 결과                 | 측정 (POST /analyses 안)        | 추천 (워커)                          |
// | 401 E1001              | 503 E3001 + ERROR              | failed E3001 + ERROR               |
// | 413 E2003 / 415 E2004  | 그대로                          | —                                  |
// | 422 E2001 / E2002      | 그대로 (AI 문구 우선)             | —                                  |
// | 422 E4002              | 500 E4001 + ERROR              | failed E4001, 재시도 없음             |
// | 503 E3003              | 그대로                          | 5초 후 1회 재시도 → failed E3003       |
// | 504 E3002              | 그대로                          | 재시도 없이 failed E3002              |
// | connection             | 즉시 1회 재시도 → 503 E3001       | 5초 후 1회 재시도 → failed E3001       |
// | timeout (우리 쪽)        | 503 E3001                      | 재시도 없이 failed E3002              |
// | invalid_response       | 500 E4001 + ERROR              | failed E4001 + ERROR               |
// | 그 밖의 4xx/5xx          | 500 E4001 + ERROR              | failed E4001 + ERROR               |

import { AnalysisFailureCode, ErrorCode } from '../common/errors/error-codes';
import { AiError } from './ai-error';

/** 사용자 사진 문제(E2xxx)는 info, AI·연결 문제는 warn, 백엔드·계약 문제는 error */
export type AiFailureLogLevel = 'error' | 'warn' | 'info';

export interface MeasureFailure {
  code: ErrorCode;
  logLevel: AiFailureLogLevel;
  /** E2001/E2002에서 AI가 준 문구 */
  message?: string;
}

export interface RecommendFailure {
  code: AnalysisFailureCode;
  logLevel: AiFailureLogLevel;
}

const INTERNAL_ERROR: MeasureFailure = { code: ErrorCode.E4001, logLevel: 'error' };

export function mapMeasureError(error: AiError): MeasureFailure {
  switch (error.kind) {
    case 'connection':
    case 'timeout':
      return { code: ErrorCode.E3001, logLevel: 'warn' };
    case 'invalid_response':
      return INTERNAL_ERROR;
    case 'http':
      break;
  }
  switch (error.status) {
    case 401:
      // 401을 그대로 내보내면 앱이 로그아웃시킨다.
      return { code: ErrorCode.E3001, logLevel: 'error' };
    case 413:
      return { code: ErrorCode.E2003, logLevel: 'info' };
    case 415:
      return { code: ErrorCode.E2004, logLevel: 'info' };
    case 422:
      if (error.code === ErrorCode.E2001 || error.code === ErrorCode.E2002) {
        return { code: error.code, logLevel: 'info', message: error.aiMessage || undefined };
      }
      return INTERNAL_ERROR; // E4002 = 백엔드 변환 버그
    case 503:
      return error.code === ErrorCode.E3003
        ? { code: ErrorCode.E3003, logLevel: 'warn' }
        : INTERNAL_ERROR;
    case 504:
      return error.code === ErrorCode.E3002
        ? { code: ErrorCode.E3002, logLevel: 'warn' }
        : INTERNAL_ERROR;
    default:
      return INTERNAL_ERROR;
  }
}

/** 측정: 연결 오류만 즉시 1회 재시도 */
export function isMeasureRetryable(error: AiError): boolean {
  return error.kind === 'connection';
}

export function mapRecommendError(error: AiError): RecommendFailure {
  switch (error.kind) {
    case 'connection':
      return { code: ErrorCode.E3001, logLevel: 'warn' };
    case 'timeout':
      return { code: ErrorCode.E3002, logLevel: 'warn' };
    case 'invalid_response':
      return { code: ErrorCode.E4001, logLevel: 'error' };
    case 'http':
      break;
  }
  if (error.status === 401) return { code: ErrorCode.E3001, logLevel: 'error' };
  if (error.status === 503 && error.code === ErrorCode.E3003) {
    return { code: ErrorCode.E3003, logLevel: 'warn' };
  }
  if (error.status === 504 && error.code === ErrorCode.E3002) {
    return { code: ErrorCode.E3002, logLevel: 'warn' };
  }
  return { code: ErrorCode.E4001, logLevel: 'error' };
}

/** 추천: 연결 오류·503만 5초 후 1회. 504·422·우리 쪽 타임아웃은 재시도 금지 */
export function isRecommendRetryable(error: AiError): boolean {
  return error.kind === 'connection' || (error.kind === 'http' && error.status === 503);
}
