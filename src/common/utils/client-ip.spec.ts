import type { Request } from 'express';
import { getClientIp } from './client-ip';

const req = (headers: Record<string, string>, ip = '10.0.0.1') =>
  ({ headers, ip, socket: {} }) as unknown as Request;

describe('getClientIp (TRD §15.2)', () => {
  it('CF-Connecting-IP 우선', () => {
    expect(getClientIp(req({ 'cf-connecting-ip': ' 203.0.113.7 ' }))).toBe('203.0.113.7');
  });
  it('없으면 req.ip', () => {
    expect(getClientIp(req({}))).toBe('10.0.0.1');
  });
});
