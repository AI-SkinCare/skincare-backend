import type { Request, Response } from 'express';
import {
  REFRESH_COOKIE_NAME,
  REFRESH_COOKIE_PATH,
  REFRESH_TOKEN_TTL_SEC,
} from '../config/constants';

// TRD §9.1 — 정확히 이 속성:
// refresh_token=<token>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600
// 지울 때도 같은 Path로 Max-Age=0 (Path가 다르면 쿠키 저장소가 지우지 않는다).

function cookie(value: string, maxAge: number, secure: boolean): string {
  return [
    `${REFRESH_COOKIE_NAME}=${value}`,
    'HttpOnly',
    ...(secure ? ['Secure'] : []),
    'SameSite=Strict',
    `Path=${REFRESH_COOKIE_PATH}`,
    `Max-Age=${maxAge}`,
  ].join('; ');
}

export function setRefreshCookie(res: Response, token: string, secure: boolean): void {
  res.append('Set-Cookie', cookie(token, REFRESH_TOKEN_TTL_SEC, secure));
}

export function clearRefreshCookie(res: Response, secure: boolean): void {
  res.append('Set-Cookie', cookie('', 0, secure));
}

/**
 * 쿠키 우선, 없으면 본문 refreshToken [앱 협의, TRD §9.1].
 * 둘 다 없으면 undefined.
 */
export function readRefreshToken(req: Request, bodyToken: string | undefined): string | undefined {
  const cookies: unknown = req.cookies;
  const fromCookie =
    typeof cookies === 'object' && cookies !== null
      ? (cookies as Record<string, unknown>)[REFRESH_COOKIE_NAME]
      : undefined;
  if (typeof fromCookie === 'string' && fromCookie.length > 0) return fromCookie;
  return bodyToken && bodyToken.length > 0 ? bodyToken : undefined;
}
