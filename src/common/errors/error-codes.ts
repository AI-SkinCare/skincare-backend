// TRD §7.3 — 에러 코드·HTTP 상태·기본 문구의 단일 출처.
// 문구는 사용자에게 그대로 보여도 되는 문장이어야 하고 SC-05 §4 문구 규칙을 지킨다.

export enum ErrorCode {
  E1001 = 'E1001',
  E1002 = 'E1002',
  E1003 = 'E1003',
  E1004 = 'E1004',
  E2001 = 'E2001',
  E2002 = 'E2002',
  E2003 = 'E2003',
  E2004 = 'E2004',
  E3001 = 'E3001',
  E3002 = 'E3002',
  E3003 = 'E3003',
  E4001 = 'E4001',
  E4002 = 'E4002',
  E4003 = 'E4003',
  E4004 = 'E4004',
  E4005 = 'E4005',
}

export interface ErrorDefinition {
  status: number;
  message: string;
  /** 기본 Retry-After(초). E4004는 사유별로 따로 넘긴다. */
  retryAfterSec?: number;
}

export const ERROR_DEFINITIONS: Record<ErrorCode, ErrorDefinition> = {
  [ErrorCode.E1001]: { status: 401, message: '로그인이 필요해요. 다시 로그인해 주세요.' },
  [ErrorCode.E1002]: { status: 403, message: '접근 권한이 없어요.' },
  [ErrorCode.E1003]: { status: 401, message: '이메일 또는 비밀번호가 맞지 않아요.' },
  [ErrorCode.E1004]: { status: 409, message: '이미 가입된 이메일이에요.' },
  [ErrorCode.E2001]: { status: 422, message: '얼굴을 찾지 못했어요. 정면에서 다시 찍어 주세요.' },
  [ErrorCode.E2002]: {
    status: 422,
    message: '사진이 흐리거나 너무 어두워요. 밝은 곳에서 다시 찍어 주세요.',
  },
  [ErrorCode.E2003]: { status: 413, message: '사진 용량이 너무 커요. 10MB 이하로 올려 주세요.' },
  [ErrorCode.E2004]: { status: 415, message: 'JPEG 또는 PNG 사진만 올릴 수 있어요.' },
  [ErrorCode.E3001]: {
    status: 503,
    message: '지금은 분석 서버 점검 중이에요. 잠시 후 다시 시도해 주세요.',
    retryAfterSec: 30,
  },
  [ErrorCode.E3002]: { status: 504, message: '추천을 만들지 못했어요. 다시 시도해 주세요.' },
  [ErrorCode.E3003]: {
    status: 503,
    message: '지금 요청이 많아요. 잠시 후 다시 시도해 주세요.',
    retryAfterSec: 60,
  },
  [ErrorCode.E4001]: { status: 500, message: '문제가 생겼어요. 잠시 후 다시 시도해 주세요.' },
  [ErrorCode.E4002]: { status: 422, message: '입력값을 확인해 주세요.' },
  [ErrorCode.E4003]: { status: 404, message: '결과를 찾을 수 없어요.' },
  [ErrorCode.E4004]: { status: 429, message: '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.' },
  [ErrorCode.E4005]: {
    status: 409,
    message: '지금은 할 수 없는 요청이에요. 화면을 새로고침해 주세요.',
  },
};

/** E2002 중 해상도 부족 — TRD §10.1 처리 순서 6 [구현 결정] */
export const LOW_RESOLUTION_MESSAGE = '사진 해상도가 낮아요. 다시 찍어 주세요.';

/** E4004 사유별 문구 (TRD §7.3) */
export const RATE_LIMIT_REASONS = {
  in_progress: '진행 중인 분석이 끝난 뒤에 다시 시도해 주세요.',
  daily_limit: '오늘 분석할 수 있는 횟수를 모두 사용했어요. 내일 다시 시도해 주세요.',
  login_rate: '로그인 시도가 너무 많아요. 1분 뒤에 다시 시도해 주세요.',
} as const;
export type RateLimitReason = keyof typeof RATE_LIMIT_REASONS;

/** 분석 failed 상태에 기록될 수 있는 코드 (TRD §10.2) */
export const ANALYSIS_FAILURE_CODES = [
  ErrorCode.E3001,
  ErrorCode.E3002,
  ErrorCode.E3003,
  ErrorCode.E4001,
] as const;
export type AnalysisFailureCode = (typeof ANALYSIS_FAILURE_CODES)[number];

export function isErrorCode(value: unknown): value is ErrorCode {
  return typeof value === 'string' && value in ERROR_DEFINITIONS;
}
