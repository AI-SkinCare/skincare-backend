import type { Request, Response } from 'express';
import { isNativeClient } from './client-type';
import { PASSWORD_PATTERN } from './dto/auth-request.dto';
import { clearRefreshCookie, readRefreshToken, setRefreshCookie } from './refresh-cookie';
import { hashToken } from './token-hash';

describe('refresh 쿠키 (TRD §9.1)', () => {
  const capture = () => {
    const headers: string[] = [];
    return {
      res: { append: (_: string, v: string) => headers.push(v) } as unknown as Response,
      headers,
    };
  };

  it('정확히 이 속성', () => {
    const { res, headers } = capture();
    setRefreshCookie(res, 'tok', true);
    expect(headers[0]).toBe(
      'refresh_token=tok; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600',
    );
  });

  it('지울 때도 같은 Path, Max-Age=0. COOKIE_SECURE=false면 Secure 없음', () => {
    const { res, headers } = capture();
    clearRefreshCookie(res, false);
    expect(headers[0]).toBe(
      'refresh_token=; HttpOnly; SameSite=Strict; Path=/api/v1/auth; Max-Age=0',
    );
  });

  it('[앱 협의] 쿠키 우선, 없으면 본문', () => {
    const req = (cookies: Record<string, string>) => ({ cookies }) as unknown as Request;
    expect(readRefreshToken(req({ refresh_token: 'c' }), 'b')).toBe('c');
    expect(readRefreshToken(req({}), 'b')).toBe('b');
    expect(readRefreshToken(req({ refresh_token: '' }), undefined)).toBeUndefined();
  });
});

describe('X-Client [앱 협의]', () => {
  it('ios만 (공백·대소문자 무시)', () => {
    expect(isNativeClient('ios')).toBe(true);
    expect(isNativeClient(' iOS ')).toBe(true);
    expect(isNativeClient('android')).toBe(false);
    expect(isNativeClient(undefined)).toBe(false);
  });
});

describe('비밀번호 규칙 (TRD §9.2)', () => {
  it.each([
    ['Skin!2026', true],
    ['skinskin1', true],
    ['skin!skin', true],
    ['12345678!', true],
    ['skinskin', false], // 1종
    ['Sk1!', false], // 짧음
    ['a1'.repeat(33), false], // 64자 초과
    ['스킨케어1234', false], // ASCII 밖
    ['skin 2026', false], // 공백
  ])('%s → %s', (password, valid) => {
    expect(PASSWORD_PATTERN.test(password)).toBe(valid);
  });
});

describe('토큰 해시', () => {
  it('SHA-256 hex 64자', () => {
    expect(hashToken('abc')).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    );
  });
});
