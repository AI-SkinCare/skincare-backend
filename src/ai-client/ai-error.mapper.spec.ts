import { ErrorCode } from '../common/errors/error-codes';
import { AiError } from './ai-error';
import {
  isMeasureRetryable,
  isRecommendRetryable,
  mapMeasureError,
  mapRecommendError,
} from './ai-error.mapper';

const http = (status: number, code?: string, aiMessage?: string) =>
  new AiError('http', { status, code, aiMessage });

// TRD §12.5 표
describe('mapMeasureError (측정 경로)', () => {
  it.each([
    ['TC-ANA-10: AI 401 E1001 → 503 E3001 + ERROR', http(401, 'E1001'), ErrorCode.E3001, 'error'],
    ['413 E2003 그대로', http(413, 'E2003'), ErrorCode.E2003, 'info'],
    ['415 E2004 그대로', http(415, 'E2004'), ErrorCode.E2004, 'info'],
    ['422 E2001 그대로', http(422, 'E2001'), ErrorCode.E2001, 'info'],
    ['422 E2002 그대로', http(422, 'E2002'), ErrorCode.E2002, 'info'],
    ['422 E4002 → 500 E4001 + ERROR (변환 버그)', http(422, 'E4002'), ErrorCode.E4001, 'error'],
    ['503 E3003 그대로', http(503, 'E3003'), ErrorCode.E3003, 'warn'],
    ['504 E3002 그대로', http(504, 'E3002'), ErrorCode.E3002, 'warn'],
    ['connection → 503 E3001', new AiError('connection'), ErrorCode.E3001, 'warn'],
    ['timeout(우리 쪽) → 503 E3001', new AiError('timeout'), ErrorCode.E3001, 'warn'],
    [
      'invalid_response → 500 E4001 + ERROR',
      new AiError('invalid_response'),
      ErrorCode.E4001,
      'error',
    ],
    ['그 밖의 4xx → E4001', http(400, 'E9999'), ErrorCode.E4001, 'error'],
    ['그 밖의 5xx → E4001', http(500, 'E4001'), ErrorCode.E4001, 'error'],
  ])('%s', (_name, error, code, logLevel) => {
    expect(mapMeasureError(error)).toMatchObject({ code, logLevel });
  });

  it('E2001/E2002는 AI 문구가 있으면 그것을 쓴다', () => {
    expect(mapMeasureError(http(422, 'E2002', '사진이 너무 어두워요.')).message).toBe(
      '사진이 너무 어두워요.',
    );
    expect(mapMeasureError(http(422, 'E2001')).message).toBeUndefined();
  });

  it('연결 오류만 즉시 1회 재시도', () => {
    expect(isMeasureRetryable(new AiError('connection'))).toBe(true);
    expect(isMeasureRetryable(new AiError('timeout'))).toBe(false);
    expect(isMeasureRetryable(http(503, 'E3003'))).toBe(false);
  });
});

describe('mapRecommendError (워커)', () => {
  it.each([
    ['401 → failed E3001 + ERROR', http(401, 'E1001'), ErrorCode.E3001, 'error'],
    ['422 E4002 → failed E4001', http(422, 'E4002'), ErrorCode.E4001, 'error'],
    ['503 E3003 → failed E3003', http(503, 'E3003'), ErrorCode.E3003, 'warn'],
    ['504 E3002 → failed E3002', http(504, 'E3002'), ErrorCode.E3002, 'warn'],
    ['connection → failed E3001', new AiError('connection'), ErrorCode.E3001, 'warn'],
    ['timeout(우리 쪽) → failed E3002', new AiError('timeout'), ErrorCode.E3002, 'warn'],
    [
      'invalid_response → failed E4001 + ERROR',
      new AiError('invalid_response'),
      ErrorCode.E4001,
      'error',
    ],
    ['그 밖의 5xx → failed E4001', http(500, 'E4001'), ErrorCode.E4001, 'error'],
  ])('%s', (_name, error, code, logLevel) => {
    expect(mapRecommendError(error)).toEqual({ code, logLevel });
  });

  it('연결 오류·503만 재시도. 504·422·우리 쪽 타임아웃은 재시도 금지', () => {
    expect(isRecommendRetryable(new AiError('connection'))).toBe(true);
    expect(isRecommendRetryable(http(503, 'E3003'))).toBe(true);
    expect(isRecommendRetryable(http(504, 'E3002'))).toBe(false);
    expect(isRecommendRetryable(http(422, 'E4002'))).toBe(false);
    expect(isRecommendRetryable(new AiError('timeout'))).toBe(false);
    expect(isRecommendRetryable(new AiError('invalid_response'))).toBe(false);
  });
});
