import type { Request } from 'express';

/**
 * TRD §15.2 — nginx·터널 뒤라 모든 요청의 출발 IP가 같다.
 * CF-Connecting-IP → 없으면 req.ip (trust proxy 반영).
 */
export function getClientIp(req: Request): string {
  const header = req.headers['cf-connecting-ip'];
  const value = Array.isArray(header) ? header[0] : header;
  if (value && value.trim().length > 0) return value.trim();
  return req.ip ?? req.socket.remoteAddress ?? 'unknown';
}
