// TRD §6 — env가 아닌 코드 상수로 두는 값들.

export const API_PREFIX = 'api/v1';

export const ACCESS_TOKEN_TTL_SEC = 3600;
export const REFRESH_TOKEN_TTL_SEC = 14 * 24 * 60 * 60;
export const BCRYPT_COST = 12;

export const REFRESH_COOKIE_NAME = 'refresh_token';
export const REFRESH_COOKIE_PATH = '/api/v1/auth';

export const WORKER_POLL_INTERVAL_MS = 1000;
export const WORKER_RETRY_DELAY_MS = 5000;
export const QUEUE_EXPIRY_CHECK_INTERVAL_MS = 60_000;
/** 최근 완료 기록이 없을 때 쓰는 평균 추천 시간 (TRD §13.3) */
export const DEFAULT_RECOMMEND_MS = 55_000;

export const AI_HEALTH_TIMEOUT_MS = 5000;

export const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
export const MIN_IMAGE_SHORT_SIDE_PX = 720;

/** 진행 중 분석이 있을 때 Retry-After (TRD §7.3) */
export const IN_PROGRESS_RETRY_AFTER_SEC = 30;
