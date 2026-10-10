import { randomUUID } from 'node:crypto';
import type { NextFunction, Request, Response } from 'express';
import type { AppRequest } from '../http/app-request';

export const REQUEST_ID_HEADER = 'X-Request-Id';
const VALID_REQUEST_ID = /^[A-Za-z0-9._-]{1,64}$/;

export function resolveRequestId(incoming: unknown): string {
  return typeof incoming === 'string' && VALID_REQUEST_ID.test(incoming) ? incoming : randomUUID();
}

/**
 * TRD §7.5 — 요청 헤더가 형식에 맞으면 그대로, 아니면 UUID v4.
 * 다른 미들웨어보다 먼저 등록해 모든 응답(에러 포함)에 헤더가 붙게 한다.
 * pino-http는 `req.id`를 그대로 requestId로 쓴다.
 */
export function requestIdMiddleware(req: Request, res: Response, next: NextFunction): void {
  const requestId = resolveRequestId(req.headers['x-request-id']);
  (req as AppRequest).id = requestId;
  res.setHeader(REQUEST_ID_HEADER, requestId);
  next();
}
